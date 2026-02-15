package velox.api.layer1.simplified.demo;

import java.util.*;

/**
 * Volume Tail Detector
 *
 * Detects "Volume Tails" - low volume areas at the extremes of a price range.
 * These indicate lack of interest and often signal potential reversals.
 *
 * From Carmine Rosato's methodology:
 * "A Volume Tail at the top or bottom of a range indicates a lack of interest
 * and a likely reversal"
 *
 * Volume tails occur when price quickly trades through a level with minimal
 * volume, suggesting no real conviction. When price returns to these levels,
 * they often act as "vacuum" zones that price gets pulled through quickly.
 */
public class VolumeTailDetector {

    // Volume profile for the current range
    private final Map<Integer, Long> volumeAtPrice = new LinkedHashMap<>();
    private final int maxPriceLevels;

    // Configuration
    private final double tailThresholdPercent;  // Volume < this % of average = tail
    private final int minRangeSize;             // Minimum range size to analyze

    // Range tracking
    private int rangeHigh = Integer.MIN_VALUE;
    private int rangeLow = Integer.MAX_VALUE;
    private long totalVolume = 0;
    private long lastUpdateTime = 0;

    // Cache for analysis results
    private VolumeTailAnalysis cachedAnalysis;
    private long cacheTime = 0;
    private static final long CACHE_VALIDITY_MS = 5000;  // 5 seconds

    /**
     * Result of volume tail analysis
     */
    public static class VolumeTailAnalysis {
        public boolean hasUpperTail;          // Low volume at top of range
        public boolean hasLowerTail;          // Low volume at bottom of range
        public int upperTailStart;            // Where upper tail begins
        public int lowerTailEnd;              // Where lower tail ends
        public int upperTailLength;           // Length in ticks
        public int lowerTailLength;           // Length in ticks
        public double upperTailStrength;      // 0-1, how pronounced the tail is
        public double lowerTailStrength;      // 0-1, how pronounced the tail is
        public String bias;                   // "BULLISH" (lower tail), "BEARISH" (upper tail), "NEUTRAL"
        public String reasoning;

        public boolean hasAnyTail() {
            return hasUpperTail || hasLowerTail;
        }

        public boolean hasBothTails() {
            return hasUpperTail && hasLowerTail;
        }
    }

    /**
     * Represents a volume tail zone
     */
    public static class VolumeTail {
        public int startPrice;
        public int endPrice;
        public int lengthTicks;
        public long avgVolume;               // Average volume in tail
        public long expectedVolume;          // What volume should be
        public double weaknessRatio;         // avgVolume / expectedVolume (lower = weaker)
        public boolean isUpper;              // true = upper tail, false = lower tail
        public long detectedTime;

        public VolumeTail(int startPrice, int endPrice, boolean isUpper) {
            this.startPrice = startPrice;
            this.endPrice = endPrice;
            this.isUpper = isUpper;
            this.lengthTicks = Math.abs(endPrice - startPrice) + 1;
            this.detectedTime = System.currentTimeMillis();
        }
    }

    public VolumeTailDetector(int maxPriceLevels, double tailThresholdPercent, int minRangeSize) {
        this.maxPriceLevels = maxPriceLevels;
        this.tailThresholdPercent = tailThresholdPercent;
        this.minRangeSize = minRangeSize;
    }

    /**
     * Default constructor with sensible defaults
     */
    public VolumeTailDetector() {
        this(200, 0.3, 10);  // 200 levels, 30% of average = tail, min 10 tick range
    }

    /**
     * Record volume at a price
     */
    public void recordVolume(int price, long volume) {
        volumeAtPrice.merge(price, volume, Long::sum);
        totalVolume += volume;

        // Update range
        if (price > rangeHigh) rangeHigh = price;
        if (price < rangeLow) rangeLow = price;

        lastUpdateTime = System.currentTimeMillis();

        // Trim if too many levels
        if (volumeAtPrice.size() > maxPriceLevels) {
            trimOldLevels();
        }

        // Invalidate cache
        cachedAnalysis = null;
    }

    /**
     * Trim old price levels to stay within limit
     */
    private void trimOldLevels() {
        // Remove levels with lowest volume (least significant)
        if (volumeAtPrice.size() <= maxPriceLevels) return;

        // Find median volume
        List<Long> volumes = new ArrayList<>(volumeAtPrice.values());
        Collections.sort(volumes);
        long medianVolume = volumes.get(volumes.size() / 2);

        // Remove prices with below-median volume at extremes
        Iterator<Map.Entry<Integer, Long>> it = volumeAtPrice.entrySet().iterator();
        while (it.hasNext() && volumeAtPrice.size() > maxPriceLevels * 0.8) {
            Map.Entry<Integer, Long> entry = it.next();
            if (entry.getValue() < medianVolume * 0.5) {
                // Only remove if not near current range extremes
                int price = entry.getKey();
                if (Math.abs(price - rangeHigh) > 5 && Math.abs(price - rangeLow) > 5) {
                    totalVolume -= entry.getValue();
                    it.remove();
                }
            }
        }
    }

    /**
     * Analyze current range for volume tails
     */
    public VolumeTailAnalysis analyzeTails() {
        // Return cached result if still valid
        if (cachedAnalysis != null && System.currentTimeMillis() - cacheTime < CACHE_VALIDITY_MS) {
            return cachedAnalysis;
        }

        VolumeTailAnalysis analysis = new VolumeTailAnalysis();

        int rangeSize = rangeHigh - rangeLow + 1;
        if (rangeSize < minRangeSize || volumeAtPrice.isEmpty()) {
            analysis.bias = "NEUTRAL";
            analysis.reasoning = "Range too small or no data";
            cachedAnalysis = analysis;
            cacheTime = System.currentTimeMillis();
            return analysis;
        }

        // Calculate average volume
        double avgVolume = (double) totalVolume / volumeAtPrice.size();
        double tailThreshold = avgVolume * tailThresholdPercent;

        // Scan for upper tail (top of range with low volume)
        int upperTailStart = rangeHigh;
        int consecutiveLowVol = 0;
        int maxConsecutiveLowVol = 0;

        for (int price = rangeHigh; price >= rangeLow; price--) {
            long vol = volumeAtPrice.getOrDefault(price, 0L);
            if (vol < tailThreshold) {
                consecutiveLowVol++;
                if (consecutiveLowVol > maxConsecutiveLowVol) {
                    maxConsecutiveLowVol = consecutiveLowVol;
                    upperTailStart = price;
                }
            } else {
                break;  // Stop at first high-volume level
            }
        }

        // Scan for lower tail (bottom of range with low volume)
        int lowerTailEnd = rangeLow;
        consecutiveLowVol = 0;
        maxConsecutiveLowVol = 0;

        for (int price = rangeLow; price <= rangeHigh; price++) {
            long vol = volumeAtPrice.getOrDefault(price, 0L);
            if (vol < tailThreshold) {
                consecutiveLowVol++;
                if (consecutiveLowVol > maxConsecutiveLowVol) {
                    maxConsecutiveLowVol = consecutiveLowVol;
                    lowerTailEnd = price;
                }
            } else {
                break;  // Stop at first high-volume level
            }
        }

        // Determine if we have meaningful tails
        analysis.hasUpperTail = (rangeHigh - upperTailStart + 1) >= 2;
        analysis.hasLowerTail = (lowerTailEnd - rangeLow + 1) >= 2;

        analysis.upperTailStart = upperTailStart;
        analysis.upperTailLength = analysis.hasUpperTail ? (rangeHigh - upperTailStart + 1) : 0;

        analysis.lowerTailEnd = lowerTailEnd;
        analysis.lowerTailLength = analysis.hasLowerTail ? (lowerTailEnd - rangeLow + 1) : 0;

        // Calculate tail strength (0-1)
        if (analysis.hasUpperTail) {
            double upperTailVol = getAverageVolumeInRange(upperTailStart, rangeHigh);
            analysis.upperTailStrength = 1.0 - (upperTailVol / avgVolume);
        }
        if (analysis.hasLowerTail) {
            double lowerTailVol = getAverageVolumeInRange(rangeLow, lowerTailEnd);
            analysis.lowerTailStrength = 1.0 - (lowerTailVol / avgVolume);
        }

        // Determine bias
        if (analysis.hasLowerTail && !analysis.hasUpperTail) {
            analysis.bias = "BULLISH";
            analysis.reasoning = String.format("Lower tail detected (%d ticks) - price rejected lows, potential reversal up",
                analysis.lowerTailLength);
        } else if (analysis.hasUpperTail && !analysis.hasLowerTail) {
            analysis.bias = "BEARISH";
            analysis.reasoning = String.format("Upper tail detected (%d ticks) - price rejected highs, potential reversal down",
                analysis.upperTailLength);
        } else if (analysis.hasBothTails()) {
            if (analysis.lowerTailStrength > analysis.upperTailStrength) {
                analysis.bias = "BULLISH";
                analysis.reasoning = String.format("Both tails, but lower tail stronger (%.0f%% vs %.0f%%)",
                    analysis.lowerTailStrength * 100, analysis.upperTailStrength * 100);
            } else if (analysis.upperTailStrength > analysis.lowerTailStrength) {
                analysis.bias = "BEARISH";
                analysis.reasoning = String.format("Both tails, but upper tail stronger (%.0f%% vs %.0f%%)",
                    analysis.upperTailStrength * 100, analysis.lowerTailStrength * 100);
            } else {
                analysis.bias = "NEUTRAL";
                analysis.reasoning = "Both tails with equal strength - consolidation";
            }
        } else {
            analysis.bias = "NEUTRAL";
            analysis.reasoning = "No significant volume tails detected";
        }

        cachedAnalysis = analysis;
        cacheTime = System.currentTimeMillis();
        return analysis;
    }

    /**
     * Get average volume in a price range
     */
    private double getAverageVolumeInRange(int lowPrice, int highPrice) {
        long total = 0;
        int count = 0;
        for (int price = lowPrice; price <= highPrice; price++) {
            Long vol = volumeAtPrice.get(price);
            if (vol != null) {
                total += vol;
                count++;
            }
        }
        return count > 0 ? (double) total / count : 0;
    }

    /**
     * Check if a price is in a volume tail zone
     */
    public boolean isInTailZone(int price) {
        VolumeTailAnalysis analysis = analyzeTails();

        if (analysis.hasUpperTail && price >= analysis.upperTailStart && price <= rangeHigh) {
            return true;
        }
        if (analysis.hasLowerTail && price >= rangeLow && price <= analysis.lowerTailEnd) {
            return true;
        }
        return false;
    }

    /**
     * Get the range high
     */
    public int getRangeHigh() {
        return rangeHigh;
    }

    /**
     * Get the range low
     */
    public int getRangeLow() {
        return rangeLow;
    }

    /**
     * Get volume at a specific price
     */
    public long getVolumeAtPrice(int price) {
        return volumeAtPrice.getOrDefault(price, 0L);
    }

    /**
     * Get POC (Point of Control) - price with highest volume
     */
    public int getPOC() {
        int pocPrice = rangeLow;
        long maxVolume = 0;

        for (Map.Entry<Integer, Long> entry : volumeAtPrice.entrySet()) {
            if (entry.getValue() > maxVolume) {
                maxVolume = entry.getValue();
                pocPrice = entry.getKey();
            }
        }
        return pocPrice;
    }

    /**
     * Get summary string for logging/AI
     */
    public String getSummary() {
        VolumeTailAnalysis analysis = analyzeTails();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Volume Profile: Range %d-%d, POC @ %d\n",
            rangeLow, rangeHigh, getPOC()));

        if (analysis.hasAnyTail()) {
            sb.append(String.format("Tails: Upper=%b (%d ticks), Lower=%b (%d ticks)\n",
                analysis.hasUpperTail, analysis.upperTailLength,
                analysis.hasLowerTail, analysis.lowerTailLength));
            sb.append("Bias: ").append(analysis.bias).append(" - ").append(analysis.reasoning);
        } else {
            sb.append("No volume tails detected");
        }

        return sb.toString();
    }

    /**
     * Reset for new session
     */
    public void reset() {
        volumeAtPrice.clear();
        rangeHigh = Integer.MIN_VALUE;
        rangeLow = Integer.MAX_VALUE;
        totalVolume = 0;
        cachedAnalysis = null;
    }
}
