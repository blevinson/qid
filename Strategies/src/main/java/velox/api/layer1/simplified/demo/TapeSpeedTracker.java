package velox.api.layer1.simplified.demo;

import java.util.*;

/**
 * Tape Speed Tracker
 *
 * Measures the velocity of trades (trades per second, volume per second).
 * High tape speed indicates urgent market activity - "panic" buying or selling.
 *
 * From Carmine Rosato's methodology:
 * "When tape speed increases dramatically, it indicates urgency.
 * Urgent sellers = aggressive selling = likely continuation down.
 * Urgent buyers = aggressive buying = likely continuation up."
 *
 * Use cases:
 * 1. Confirm signal direction - is there urgency behind the move?
 * 2. Detect exhaustion - very high tape speed often precedes reversals
 * 3. Filter false signals - low tape speed = no conviction
 */
public class TapeSpeedTracker {

    // Rolling windows for different timeframes
    private final LinkedList<TradeEvent> tradeEvents = new LinkedList<>();
    private final int maxEvents;

    // Configuration
    private final long windowMs;              // Time window for calculations (e.g., 5000ms = 5 sec)
    private final long shortWindowMs;         // Short-term window (e.g., 1000ms)
    private final int highSpeedThreshold;     // Trades/sec to consider "high speed"
    private final int veryHighSpeedThreshold; // Trades/sec to consider "very high speed"

    // Statistics
    private double baselineTradesPerSec = 0;
    private double baselineVolumePerSec = 0;
    private long totalTrades = 0;
    private long totalVolume = 0;

    // Cached analysis
    private TapeSpeedAnalysis cachedAnalysis;
    private long cacheTime = 0;
    private static final long CACHE_VALIDITY_MS = 500;  // 500ms cache

    /**
     * Individual trade event
     */
    private static class TradeEvent {
        long timestampMs;
        int price;
        int volume;
        boolean isBuy;  // Aggressor side

        TradeEvent(int price, int volume, boolean isBuy) {
            this.timestampMs = System.currentTimeMillis();
            this.price = price;
            this.volume = volume;
            this.isBuy = isBuy;
        }
    }

    /**
     * Result of tape speed analysis
     */
    public static class TapeSpeedAnalysis {
        // Current speed metrics
        public double tradesPerSecond;        // Current trades/sec
        public double volumePerSecond;        // Current volume/sec
        public double avgTradeSize;           // Average trade size

        // Short-term (more sensitive)
        public double shortTermTradesPerSec;
        public double shortTermVolumePerSec;

        // Comparison to baseline
        public double speedVsBaseline;        // Current / baseline ratio
        public String speedLevel;             // "VERY_SLOW", "SLOW", "NORMAL", "FAST", "VERY_FAST", "EXTREME"

        // Directional analysis
        public double buyTradesPerSec;        // Buy trades/sec
        public double sellTradesPerSec;       // Sell trades/sec
        public double buyVolumePerSec;        // Buy volume/sec
        public double sellVolumePerSec;       // Sell volume/sec
        public String dominantSide;           // "BUYERS", "SELLERS", "BALANCED"
        public double dominanceRatio;         // buy / sell ratio

        // Urgency signals
        public boolean isHighSpeed;           // Above high speed threshold
        public boolean isVeryHighSpeed;       // Above very high threshold
        public boolean isAcceleration;        // Speed increasing rapidly
        public boolean isExhaustion;          // Very high speed with declining volume

        // Price behavior during high speed
        public int priceChange;               // Price change during window (ticks)
        public String priceDirection;         // "UP", "DOWN", "FLAT"

        // Interpretation
        public String interpretation;
        public String signal;

        /**
         * Get a summary for logging/AI
         */
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Tape Speed: %.1f trades/sec (%s)\n",
                tradesPerSecond, speedLevel));
            sb.append(String.format("  Volume: %.0f/sec, Avg Size: %.1f\n",
                volumePerSecond, avgTradeSize));
            sb.append(String.format("  Dominant: %s (%.1fx baseline)\n",
                dominantSide, speedVsBaseline));

            if (isHighSpeed) {
                sb.append(String.format("  ⚠️ HIGH SPEED - %s\n", signal));
            }
            if (isExhaustion) {
                sb.append("  ⚠️ EXHAUSTION pattern detected\n");
            }

            return sb.toString();
        }
    }

    /**
     * Create with custom configuration
     */
    public TapeSpeedTracker(int maxEvents, long windowMs, int highSpeedThreshold, int veryHighSpeedThreshold) {
        this.maxEvents = maxEvents;
        this.windowMs = windowMs;
        this.shortWindowMs = windowMs / 5;  // 1/5 of main window
        this.highSpeedThreshold = highSpeedThreshold;
        this.veryHighSpeedThreshold = veryHighSpeedThreshold;
    }

    /**
     * Default constructor with sensible defaults
     */
    public TapeSpeedTracker() {
        this(500, 5000, 10, 25);  // 500 events, 5 sec window, 10 t/s = high, 25 t/s = very high
    }

    /**
     * Record a trade
     */
    public void recordTrade(int price, int volume, boolean isBuy) {
        synchronized (tradeEvents) {
            tradeEvents.add(new TradeEvent(price, volume, isBuy));
            totalTrades++;
            totalVolume += volume;

            // Trim old events
            long cutoff = System.currentTimeMillis() - windowMs * 2;  // Keep 2x window
            while (!tradeEvents.isEmpty() && tradeEvents.peekFirst().timestampMs < cutoff) {
                tradeEvents.removeFirst();
            }

            // Also limit by count
            while (tradeEvents.size() > maxEvents) {
                tradeEvents.removeFirst();
            }
        }

        // Invalidate cache
        cachedAnalysis = null;
    }

    /**
     * Analyze current tape speed
     */
    public TapeSpeedAnalysis analyze() {
        // Return cached if valid
        if (cachedAnalysis != null && System.currentTimeMillis() - cacheTime < CACHE_VALIDITY_MS) {
            return cachedAnalysis;
        }

        TapeSpeedAnalysis analysis = new TapeSpeedAnalysis();
        long now = System.currentTimeMillis();

        synchronized (tradeEvents) {
            if (tradeEvents.isEmpty()) {
                analysis.speedLevel = "NO_DATA";
                analysis.dominantSide = "BALANCED";
                analysis.dominanceRatio = 1.0;
                analysis.interpretation = "No trades recorded";
                analysis.signal = "NO_DATA";
                cachedAnalysis = analysis;
                cacheTime = now;
                return analysis;
            }

            // Collect stats for main window
            int tradeCount = 0;
            long volumeSum = 0;
            int buyCount = 0;
            int sellCount = 0;
            long buyVolume = 0;
            long sellVolume = 0;
            int firstPrice = 0;
            int lastPrice = 0;
            int priceSum = 0;
            long windowStart = now - windowMs;

            // Short-term stats
            int shortTradeCount = 0;
            long shortVolumeSum = 0;
            long shortWindowStart = now - shortWindowMs;

            // Previous period for acceleration detection
            int prevPeriodTrades = 0;
            long prevPeriodStart = now - windowMs * 2;

            for (TradeEvent event : tradeEvents) {
                if (event.timestampMs >= windowStart) {
                    tradeCount++;
                    volumeSum += event.volume;
                    priceSum += event.price;
                    lastPrice = event.price;

                    if (tradeCount == 1) {
                        firstPrice = event.price;
                    }

                    if (event.isBuy) {
                        buyCount++;
                        buyVolume += event.volume;
                    } else {
                        sellCount++;
                        sellVolume += event.volume;
                    }

                    // Short-term
                    if (event.timestampMs >= shortWindowStart) {
                        shortTradeCount++;
                        shortVolumeSum += event.volume;
                    }
                }

                // Previous period
                if (event.timestampMs >= prevPeriodStart && event.timestampMs < windowStart) {
                    prevPeriodTrades++;
                }
            }

            // Calculate rates
            double windowSec = windowMs / 1000.0;
            double shortWindowSec = shortWindowMs / 1000.0;

            analysis.tradesPerSecond = tradeCount / windowSec;
            analysis.volumePerSecond = volumeSum / windowSec;
            analysis.avgTradeSize = tradeCount > 0 ? (double) volumeSum / tradeCount : 0;

            analysis.shortTermTradesPerSec = shortTradeCount / shortWindowSec;
            analysis.shortTermVolumePerSec = shortVolumeSum / shortWindowSec;

            // Directional analysis
            analysis.buyTradesPerSec = buyCount / windowSec;
            analysis.sellTradesPerSec = sellCount / windowSec;
            analysis.buyVolumePerSec = buyVolume / windowSec;
            analysis.sellVolumePerSec = sellVolume / windowSec;

            // Dominance
            double totalDirectionalVol = buyVolume + sellVolume;
            if (totalDirectionalVol > 0) {
                analysis.dominanceRatio = (double) buyVolume / sellVolume;
                if (analysis.dominanceRatio > 1.5) {
                    analysis.dominantSide = "BUYERS";
                } else if (analysis.dominanceRatio < 0.67) {
                    analysis.dominantSide = "SELLERS";
                } else {
                    analysis.dominantSide = "BALANCED";
                }
            } else {
                analysis.dominanceRatio = 1.0;
                analysis.dominantSide = "BALANCED";
            }

            // Price change during window
            analysis.priceChange = lastPrice - firstPrice;
            if (analysis.priceChange > 0) {
                analysis.priceDirection = "UP";
            } else if (analysis.priceChange < 0) {
                analysis.priceDirection = "DOWN";
            } else {
                analysis.priceDirection = "FLAT";
            }

            // Update baseline (rolling average)
            if (baselineTradesPerSec == 0) {
                baselineTradesPerSec = analysis.tradesPerSecond;
                baselineVolumePerSec = analysis.volumePerSecond;
            } else {
                baselineTradesPerSec = baselineTradesPerSec * 0.95 + analysis.tradesPerSecond * 0.05;
                baselineVolumePerSec = baselineVolumePerSec * 0.95 + analysis.volumePerSecond * 0.05;
            }

            // Compare to baseline
            analysis.speedVsBaseline = baselineTradesPerSec > 0 ?
                analysis.tradesPerSecond / baselineTradesPerSec : 1.0;

            // Determine speed level
            if (analysis.tradesPerSecond < highSpeedThreshold * 0.3) {
                analysis.speedLevel = "VERY_SLOW";
            } else if (analysis.tradesPerSecond < highSpeedThreshold * 0.6) {
                analysis.speedLevel = "SLOW";
            } else if (analysis.tradesPerSecond < highSpeedThreshold) {
                analysis.speedLevel = "NORMAL";
            } else if (analysis.tradesPerSecond < veryHighSpeedThreshold) {
                analysis.speedLevel = "FAST";
            } else if (analysis.tradesPerSecond < veryHighSpeedThreshold * 1.5) {
                analysis.speedLevel = "VERY_FAST";
            } else {
                analysis.speedLevel = "EXTREME";
            }

            // High speed flags
            analysis.isHighSpeed = analysis.tradesPerSecond >= highSpeedThreshold;
            analysis.isVeryHighSpeed = analysis.tradesPerSecond >= veryHighSpeedThreshold;

            // Acceleration detection (short-term vs main window)
            if (analysis.shortTermTradesPerSec > analysis.tradesPerSecond * 1.5) {
                analysis.isAcceleration = true;
            }

            // Exhaustion detection (very high speed but declining volume per trade)
            if (analysis.isVeryHighSpeed && analysis.avgTradeSize < baselineVolumePerSec / baselineTradesPerSec * 0.5) {
                analysis.isExhaustion = true;
            }

            // Generate interpretation
            generateInterpretation(analysis);
        }

        cachedAnalysis = analysis;
        cacheTime = now;
        return analysis;
    }

    /**
     * Generate interpretation and signal
     */
    private void generateInterpretation(TapeSpeedAnalysis analysis) {
        StringBuilder sb = new StringBuilder();

        if (analysis.isExhaustion) {
            sb.append("EXHAUSTION: Very high speed (").append(String.format("%.1f", analysis.tradesPerSecond))
              .append(" t/s) with small avg size = running out of steam. ");
            analysis.signal = "EXHAUSTION_" + (analysis.dominantSide.equals("BUYERS") ? "BUYERS" : "SELLERS");

        } else if (analysis.isVeryHighSpeed) {
            sb.append("VERY HIGH SPEED: ").append(String.format("%.1f", analysis.tradesPerSecond))
              .append(" trades/sec. Urgent ").append(analysis.dominantSide).append(". ");
            if (analysis.isAcceleration) {
                sb.append("ACCELERATING. ");
            }
            analysis.signal = "URGENT_" + analysis.dominantSide;

        } else if (analysis.isHighSpeed) {
            sb.append("HIGH SPEED: Above normal activity. ")
              .append(analysis.dominantSide).append(" in control. ");
            analysis.signal = "ACTIVE_" + analysis.dominantSide;

        } else if (analysis.speedLevel.equals("VERY_SLOW")) {
            sb.append("VERY SLOW: Market is quiet. Low conviction. ");
            analysis.signal = "QUIET";

        } else {
            sb.append("NORMAL SPEED: ").append(String.format("%.1f", analysis.tradesPerSecond))
              .append(" trades/sec. ");
            analysis.signal = "NORMAL";
        }

        // Add price action context
        sb.append("Price moving ").append(analysis.priceDirection);
        if (Math.abs(analysis.priceChange) > 5) {
            sb.append(" (").append(analysis.priceChange).append(" ticks)");
        }

        analysis.interpretation = sb.toString();
    }

    /**
     * Check if current conditions are favorable for a signal direction
     */
    public boolean isFavorableForDirection(boolean isLong) {
        TapeSpeedAnalysis analysis = analyze();

        // No data = not favorable
        if ("NO_DATA".equals(analysis.speedLevel)) {
            return false;
        }

        // Quiet market = low conviction, not favorable
        if ("VERY_SLOW".equals(analysis.speedLevel)) {
            return false;
        }

        // Check alignment (null-safe)
        String side = analysis.dominantSide;
        String sig = analysis.signal != null ? analysis.signal : "";

        if (isLong) {
            // For longs, want buyer dominance or exhaustion of sellers
            if ("BUYERS".equals(side)) return true;
            if (sig.contains("EXHAUSTION_SELLERS")) return true;
        } else {
            // For shorts, want seller dominance or exhaustion of buyers
            if ("SELLERS".equals(side)) return true;
            if (sig.contains("EXHAUSTION_BUYERS")) return true;
        }

        return false;
    }

    /**
     * Get tape speed bonus/penalty for scoring
     */
    public int getSpeedScoreAdjustment(boolean isLong) {
        TapeSpeedAnalysis analysis = analyze();

        // No data = no adjustment
        if ("NO_DATA".equals(analysis.speedLevel)) {
            return 0;
        }

        int adjustment = 0;

        // Base adjustment from speed level
        switch (analysis.speedLevel) {
            case "VERY_SLOW":
                adjustment -= 5;  // Low conviction
                break;
            case "SLOW":
                adjustment -= 2;
                break;
            case "NORMAL":
                break;  // No adjustment
            case "FAST":
                adjustment += 2;
                break;
            case "VERY_FAST":
                adjustment += 5;
                break;
            case "EXTREME":
                adjustment += 8;  // But watch for exhaustion
                break;
        }

        // Direction alignment bonus (null-safe)
        String side = analysis.dominantSide;
        String sig = analysis.signal != null ? analysis.signal : "";

        if (isLong && "BUYERS".equals(side)) {
            adjustment += 3;
        } else if (!isLong && "SELLERS".equals(side)) {
            adjustment += 3;
        }

        // Exhaustion reversal bonus
        if (analysis.isExhaustion) {
            if (isLong && sig.contains("SELLERS")) {
                adjustment += 5;  // Exhausted sellers = potential long
            } else if (!isLong && sig.contains("BUYERS")) {
                adjustment += 5;  // Exhausted buyers = potential short
            }
        }

        return adjustment;
    }

    /**
     * Get summary string for logging/AI
     */
    public String getSummary() {
        return analyze().getSummary();
    }

    /**
     * Get total trades recorded
     */
    public long getTotalTrades() {
        return totalTrades;
    }

    /**
     * Get total volume recorded
     */
    public long getTotalVolume() {
        return totalVolume;
    }

    /**
     * Get baseline trades per second
     */
    public double getBaselineTradesPerSec() {
        return baselineTradesPerSec;
    }

    /**
     * Reset tracker
     */
    public void reset() {
        synchronized (tradeEvents) {
            tradeEvents.clear();
        }
        totalTrades = 0;
        totalVolume = 0;
        baselineTradesPerSec = 0;
        baselineVolumePerSec = 0;
        cachedAnalysis = null;
    }
}
