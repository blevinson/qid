package velox.api.layer1.simplified.demo;

import java.util.*;

/**
 * Stop Hunt Detector
 *
 * Detects when price rapidly sweeps through a level (likely hitting stop losses)
 * and then reverses. This is a classic institutional tactic:
 * 1. Push price through an obvious support/resistance level
 * 2. Trigger stop losses (liquidity grab)
 * 3. Reverse and move in the intended direction
 *
 * From Carmine Rosato's methodology:
 * "A stop hunt is when price quickly trades through a level where retail traders
 * have their stops, triggering them, then reverses. This provides liquidity for
 * institutional players to enter in the opposite direction."
 *
 * Key indicators:
 * - Rapid price movement through a level
 * - High volume (stops being triggered)
 * - Quick reversal
 * - Often occurs at round numbers, previous highs/lows, VWAP
 */
public class StopHuntDetector {

    // Price movement tracking
    private final LinkedList<PriceMovement> priceMovements = new LinkedList<>();
    private final int maxMovements;

    // Detected stop hunts
    private final LinkedList<StopHuntEvent> detectedHunts = new LinkedList<>();
    private final int maxHunts;

    // Configuration
    private final long detectionWindowMs;      // Time window for sweep detection (e.g., 3000ms)
    private final int sweepThresholdTicks;     // How fast is a "sweep" (e.g., 10 ticks in 3 sec)
    private final int reversalThresholdTicks;  // How much reversal to confirm (e.g., 5 ticks)
    private final long reversalWindowMs;       // Time to look for reversal (e.g., 5000ms)
    private final int minVolumeMultiplier;     // Volume must be X times average

    // Volume tracking for context
    private long avgTradeVolume = 0;
    private long totalVolume = 0;
    private long volumeCount = 0;

    // Key levels (updated externally)
    private int vwapPrice = 0;
    private int previousHigh = Integer.MIN_VALUE;
    private int previousLow = Integer.MAX_VALUE;
    private int sessionOpen = 0;

    // Cached analysis
    private StopHuntAnalysis cachedAnalysis;
    private long cacheTime = 0;
    private static final long CACHE_VALIDITY_MS = 500;

    /**
     * Price movement event
     */
    private static class PriceMovement {
        long timestampMs;
        int price;
        int volume;
        boolean isBuy;

        PriceMovement(int price, int volume, boolean isBuy) {
            this.timestampMs = System.currentTimeMillis();
            this.price = price;
            this.volume = volume;
            this.isBuy = isBuy;
        }
    }

    /**
     * Detected stop hunt event
     */
    public static class StopHuntEvent {
        public long timestampMs;
        public int sweepHigh;              // Highest price during sweep
        public int sweepLow;               // Lowest price during sweep
        public int sweepStartPrice;        // Price before sweep
        public int reversalPrice;          // Price after reversal
        public int sweepTicks;             // Total sweep distance
        public int reversalTicks;          // Reversal distance
        public boolean wasBullish;         // True = sweep up then down (bearish), False = sweep down then up (bullish)
        public long sweepVolume;           // Volume during sweep
        public String levelType;           // "ROUND_NUMBER", "PREVIOUS_HIGH", "PREVIOUS_LOW", "VWAP", "SESSION_OPEN", "UNKNOWN"
        public int levelPrice;             // The level that was swept
        public long detectedTime;
        public boolean confirmed;          // Did reversal complete?
        public int qualityScore;           // 1-10, how clean was the setup

        public long getAgeMs() {
            return System.currentTimeMillis() - detectedTime;
        }

        public String getSummary() {
            String direction = wasBullish ? "SWEEP LOWS → REVERSE UP" : "SWEEP HIGHS → REVERSE DOWN";
            return String.format("%s: Swept %d ticks to %d, reversed %d ticks. Level: %s @ %d. Quality: %d/10",
                direction, sweepTicks, wasBullish ? sweepLow : sweepHigh,
                reversalTicks, levelType, levelPrice, qualityScore);
        }
    }

    /**
     * Analysis result for current market state
     */
    public static class StopHuntAnalysis {
        public boolean recentStopHunt;           // Was there a recent stop hunt?
        public StopHuntEvent mostRecentHunt;     // The most recent hunt
        public int ticksFromHuntLevel;           // Distance from hunt level
        public String huntSignal;                // "BULLISH_REVERSAL", "BEARISH_REVERSAL", "NONE"
        public int signalStrength;               // 0-10

        // Real-time detection
        public boolean isPotentialSweep;         // Price moving fast through level
        public int sweepDirection;               // 1 = up, -1 = down, 0 = none
        public int sweepProgress;                // How far into sweep (ticks)

        // Context
        public int nearLevel;                    // Nearby key level
        public String nearLevelType;             // Type of nearby level
        public int distanceToLevel;              // Ticks to level

        public String interpretation;
        public String recommendedAction;

        public String getSummary() {
            if (recentStopHunt && mostRecentHunt != null) {
                return String.format("Recent Stop Hunt: %s. Signal: %s (strength: %d/10). %s",
                    mostRecentHunt.getSummary(), huntSignal, signalStrength, interpretation);
            } else if (isPotentialSweep) {
                return String.format("POTENTIAL SWEEP in progress: %s %d ticks. Near %s @ %d",
                    sweepDirection > 0 ? "UP" : "DOWN", sweepProgress,
                    nearLevelType, nearLevel);
            }
            return "No recent stop hunt detected. " + interpretation;
        }
    }

    /**
     * Create with custom configuration
     */
    public StopHuntDetector(int maxMovements, int maxHunts, long detectionWindowMs,
                           int sweepThresholdTicks, int reversalThresholdTicks,
                           long reversalWindowMs, int minVolumeMultiplier) {
        this.maxMovements = maxMovements;
        this.maxHunts = maxHunts;
        this.detectionWindowMs = detectionWindowMs;
        this.sweepThresholdTicks = sweepThresholdTicks;
        this.reversalThresholdTicks = reversalThresholdTicks;
        this.reversalWindowMs = reversalWindowMs;
        this.minVolumeMultiplier = minVolumeMultiplier;
    }

    /**
     * Default constructor
     */
    public StopHuntDetector() {
        this(500, 50, 3000, 8, 5, 5000, 2);
        // 500 movements, 50 hunts, 3 sec sweep window, 8 tick sweep, 5 tick reversal,
        // 5 sec reversal window, 2x volume
    }

    /**
     * Update key levels (call from strategy)
     */
    public void updateKeyLevels(int vwap, int prevHigh, int prevLow, int sessionOpenPrice) {
        this.vwapPrice = vwap;
        if (prevHigh != Integer.MIN_VALUE) this.previousHigh = prevHigh;
        if (prevLow != Integer.MAX_VALUE) this.previousLow = prevLow;
        if (sessionOpenPrice > 0) this.sessionOpen = sessionOpenPrice;
    }

    /**
     * Record a trade
     */
    public void recordTrade(int price, int volume, boolean isBuy) {
        synchronized (priceMovements) {
            priceMovements.add(new PriceMovement(price, volume, isBuy));

            // Update volume stats
            totalVolume += volume;
            volumeCount++;
            avgTradeVolume = totalVolume / volumeCount;

            // Trim old movements
            long cutoff = System.currentTimeMillis() - reversalWindowMs * 2;
            while (!priceMovements.isEmpty() && priceMovements.peekFirst().timestampMs < cutoff) {
                priceMovements.removeFirst();
            }

            while (priceMovements.size() > maxMovements) {
                priceMovements.removeFirst();
            }
        }

        // Invalidate cache
        cachedAnalysis = null;

        // Check for stop hunt pattern
        checkForStopHunt();
    }

    /**
     * Check if current price action forms a stop hunt pattern
     */
    private void checkForStopHunt() {
        synchronized (priceMovements) {
            if (priceMovements.size() < 10) return;

            long now = System.currentTimeMillis();
            long windowStart = now - detectionWindowMs;

            // Get movements in detection window
            List<PriceMovement> recentMoves = new ArrayList<>();
            for (PriceMovement move : priceMovements) {
                if (move.timestampMs >= windowStart) {
                    recentMoves.add(move);
                }
            }

            if (recentMoves.isEmpty()) return;

            // Find sweep extremes
            int sweepHigh = Integer.MIN_VALUE;
            int sweepLow = Integer.MAX_VALUE;
            int firstPrice = recentMoves.get(0).price;
            long sweepVolume = 0;

            for (PriceMovement move : recentMoves) {
                if (move.price > sweepHigh) sweepHigh = move.price;
                if (move.price < sweepLow) sweepLow = move.price;
                sweepVolume += move.volume;
            }

            int sweepUp = sweepHigh - firstPrice;
            int sweepDown = firstPrice - sweepLow;
            int totalSweep = sweepHigh - sweepLow;

            // Check if this qualifies as a sweep
            if (totalSweep < sweepThresholdTicks) return;
            if (sweepVolume < avgTradeVolume * minVolumeMultiplier) return;

            // Determine sweep direction
            boolean sweptUpFirst = sweepUp > sweepDown;
            int sweepEndPrice = sweptUpFirst ? sweepHigh : sweepLow;
            int levelSwept = sweptUpFirst ? sweepHigh : sweepLow;

            // Check for reversal
            int reversal = 0;
            int currentPrice = recentMoves.get(recentMoves.size() - 1).price;

            if (sweptUpFirst) {
                reversal = sweepHigh - currentPrice;  // Looking for move down after up sweep
            } else {
                reversal = currentPrice - sweepLow;   // Looking for move up after down sweep
            }

            // Check if reversal is significant
            if (reversal < reversalThresholdTicks) return;

            // Identify the level type
            String levelType = identifyLevel(levelSwept);
            int levelPrice = levelSwept;

            // Check if we already detected this hunt
            for (StopHuntEvent hunt : detectedHunts) {
                if (Math.abs(hunt.levelPrice - levelPrice) <= 2 &&
                    now - hunt.detectedTime < 30000) {  // 30 second dedup
                    return;
                }
            }

            // Create stop hunt event
            StopHuntEvent hunt = new StopHuntEvent();
            hunt.timestampMs = now;
            hunt.sweepHigh = sweepHigh;
            hunt.sweepLow = sweepLow;
            hunt.sweepStartPrice = firstPrice;
            hunt.reversalPrice = currentPrice;
            hunt.sweepTicks = totalSweep;
            hunt.reversalTicks = reversal;
            hunt.wasBullish = !sweptUpFirst;  // Swept down = bullish reversal
            hunt.sweepVolume = sweepVolume;
            hunt.levelType = levelType;
            hunt.levelPrice = levelPrice;
            hunt.detectedTime = now;
            hunt.confirmed = true;
            hunt.qualityScore = calculateQuality(hunt);

            // Add to detected hunts
            detectedHunts.addFirst(hunt);
            while (detectedHunts.size() > maxHunts) {
                detectedHunts.removeLast();
            }

            log("🎯 Stop Hunt detected: " + hunt.getSummary());
        }
    }

    /**
     * Identify what type of level was swept
     */
    private String identifyLevel(int price) {
        // Check round numbers (multiples of 10 or 25)
        if (price % 25 == 0) return "ROUND_NUMBER";
        if (price % 10 == 0) return "ROUND_NUMBER";

        // Check previous high/low
        if (previousHigh != Integer.MIN_VALUE && Math.abs(price - previousHigh) <= 2) {
            return "PREVIOUS_HIGH";
        }
        if (previousLow != Integer.MAX_VALUE && Math.abs(price - previousLow) <= 2) {
            return "PREVIOUS_LOW";
        }

        // Check VWAP
        if (vwapPrice > 0 && Math.abs(price - vwapPrice) <= 2) {
            return "VWAP";
        }

        // Check session open
        if (sessionOpen > 0 && Math.abs(price - sessionOpen) <= 2) {
            return "SESSION_OPEN";
        }

        return "UNKNOWN";
    }

    /**
     * Calculate quality score for a stop hunt
     */
    private int calculateQuality(StopHuntEvent hunt) {
        int score = 5;  // Base score

        // Speed bonus (faster = better)
        if (hunt.sweepTicks >= 10) score++;
        if (hunt.sweepTicks >= 15) score++;

        // Reversal bonus
        if (hunt.reversalTicks >= hunt.sweepTicks * 0.5) score++;
        if (hunt.reversalTicks >= hunt.sweepTicks * 0.75) score++;

        // Level type bonus (known levels are better)
        if (!hunt.levelType.equals("UNKNOWN")) score++;

        // Volume bonus
        if (hunt.sweepVolume >= avgTradeVolume * 3) score++;

        return Math.min(10, score);
    }

    /**
     * Analyze current market for stop hunt signals
     */
    public StopHuntAnalysis analyze() {
        // Return cached if valid
        if (cachedAnalysis != null && System.currentTimeMillis() - cacheTime < CACHE_VALIDITY_MS) {
            return cachedAnalysis;
        }

        StopHuntAnalysis analysis = new StopHuntAnalysis();
        long now = System.currentTimeMillis();

        // Find most recent significant stop hunt
        for (StopHuntEvent hunt : detectedHunts) {
            if (now - hunt.detectedTime < 60000) {  // Within last minute
                analysis.recentStopHunt = true;
                analysis.mostRecentHunt = hunt;

                // Generate signal
                if (hunt.wasBullish) {
                    analysis.huntSignal = "BULLISH_REVERSAL";
                    analysis.interpretation = "Stop sweep below " + hunt.levelType +
                        " triggered stops, now reversing up. Potential long entry.";
                } else {
                    analysis.huntSignal = "BEARISH_REVERSAL";
                    analysis.interpretation = "Stop sweep above " + hunt.levelType +
                        " triggered stops, now reversing down. Potential short entry.";
                }

                analysis.signalStrength = hunt.qualityScore;
                break;
            }
        }

        // Check for potential sweep in progress
        synchronized (priceMovements) {
            if (!priceMovements.isEmpty()) {
                long windowStart = now - 1000;  // 1 second window
                int recentHigh = Integer.MIN_VALUE;
                int recentLow = Integer.MAX_VALUE;
                int recentVolume = 0;

                for (PriceMovement move : priceMovements) {
                    if (move.timestampMs >= windowStart) {
                        if (move.price > recentHigh) recentHigh = move.price;
                        if (move.price < recentLow) recentLow = move.price;
                        recentVolume += move.volume;
                    }
                }

                int recentRange = recentHigh - recentLow;
                if (recentRange >= sweepThresholdTicks * 0.5 &&
                    recentVolume >= avgTradeVolume * minVolumeMultiplier) {
                    analysis.isPotentialSweep = true;
                    analysis.sweepProgress = recentRange;
                    analysis.sweepDirection = (recentHigh - priceMovements.getLast().price) <
                                             (priceMovements.getLast().price - recentLow) ? 1 : -1;
                }
            }
        }

        // Find nearest key level
        if (!priceMovements.isEmpty()) {
            int currentPrice = priceMovements.getLast().price;
            analysis.nearLevel = findNearestLevel(currentPrice);
            analysis.nearLevelType = identifyLevel(analysis.nearLevel);
            analysis.distanceToLevel = Math.abs(currentPrice - analysis.nearLevel);
        }

        // Default interpretation
        if (!analysis.recentStopHunt) {
            analysis.interpretation = "No recent stop hunt. Watching key levels.";
            analysis.huntSignal = "NONE";
            analysis.signalStrength = 0;
        }

        // Recommended action
        if (analysis.recentStopHunt && analysis.signalStrength >= 7) {
            analysis.recommendedAction = analysis.huntSignal.contains("BULLISH") ?
                "Consider LONG entry on pullback to sweep level" :
                "Consider SHORT entry on pullback to sweep level";
        } else if (analysis.isPotentialSweep) {
            analysis.recommendedAction = "WAIT - potential stop sweep in progress";
        } else {
            analysis.recommendedAction = "Monitor for stop hunts at key levels";
        }

        cachedAnalysis = analysis;
        cacheTime = now;
        return analysis;
    }

    /**
     * Find nearest key level
     */
    private int findNearestLevel(int currentPrice) {
        List<Integer> levels = new ArrayList<>();

        if (previousHigh != Integer.MIN_VALUE) levels.add(previousHigh);
        if (previousLow != Integer.MAX_VALUE) levels.add(previousLow);
        if (vwapPrice > 0) levels.add(vwapPrice);
        if (sessionOpen > 0) levels.add(sessionOpen);

        // Add nearby round numbers
        int lowerRound = (currentPrice / 10) * 10;
        int upperRound = lowerRound + 10;
        levels.add(lowerRound);
        levels.add(upperRound);

        int nearest = levels.get(0);
        int minDist = Math.abs(currentPrice - nearest);

        for (int level : levels) {
            int dist = Math.abs(currentPrice - level);
            if (dist < minDist) {
                minDist = dist;
                nearest = level;
            }
        }

        return nearest;
    }

    /**
     * Get score adjustment for a signal based on stop hunt context
     */
    public int getStopHuntScoreAdjustment(boolean isLong) {
        StopHuntAnalysis analysis = analyze();

        if (analysis.recentStopHunt && analysis.mostRecentHunt != null) {
            boolean signalMatchesHunt = (isLong && analysis.huntSignal.equals("BULLISH_REVERSAL")) ||
                                       (!isLong && analysis.huntSignal.equals("BEARISH_REVERSAL"));

            if (signalMatchesHunt) {
                return analysis.signalStrength;  // Add 0-10 points based on quality
            } else {
                return -3;  // Signal against stop hunt reversal
            }
        }

        // Check if near key level (potential stop hunt coming)
        if (analysis.distanceToLevel <= 3) {
            return -2;  // Cautious near key levels
        }

        return 0;
    }

    /**
     * Get recent stop hunts
     */
    public List<StopHuntEvent> getRecentStopHunts(int count) {
        List<StopHuntEvent> recent = new ArrayList<>();
        for (StopHuntEvent hunt : detectedHunts) {
            if (recent.size() >= count) break;
            recent.add(hunt);
        }
        return recent;
    }

    /**
     * Get summary for logging/AI
     */
    public String getSummary() {
        return analyze().getSummary();
    }

    /**
     * Reset detector
     */
    public void reset() {
        synchronized (priceMovements) {
            priceMovements.clear();
        }
        detectedHunts.clear();
        totalVolume = 0;
        volumeCount = 0;
        avgTradeVolume = 0;
        cachedAnalysis = null;
    }

    private void log(String message) {
        System.out.println(message);
    }
}
