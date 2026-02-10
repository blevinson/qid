package velox.api.layer1.simplified.demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Volume Profile Calculator
 * Tracks volume at each price level (heatmap-like data)
 */
public class VolumeProfileCalculator {
    // Volume at each price level
    private final Map<Integer, Long> volumeAtPrice = new ConcurrentHashMap<>();

    // Track trades for more detailed analysis
    private final Map<Integer, TradeStats> tradesAtPrice = new ConcurrentHashMap<>();

    // Total volume traded
    private long totalVolume = 0;

    // Price range tracking
    private int highestPrice = Integer.MIN_VALUE;
    private int lowestPrice = Integer.MAX_VALUE;

    // Volume profile settings
    private final int priceWindowTicks;  // How many ticks to consider "nearby"
    private final long minVolumeForNode;  // Minimum volume to be considered a "node"

    public VolumeProfileCalculator() {
        this(50, 1000);  // Default: 50 tick window, 1000 min volume for node
    }

    public VolumeProfileCalculator(int priceWindowTicks, long minVolumeForNode) {
        this.priceWindowTicks = priceWindowTicks;
        this.minVolumeForNode = minVolumeForNode;
    }

    /**
     * Update volume profile on each trade
     */
    public void onTrade(double price, int size) {
        int priceLevel = (int)price;

        // Update volume at price
        volumeAtPrice.merge(priceLevel, (long)size, Long::sum);
        totalVolume += size;

        // Update trade stats
        tradesAtPrice.computeIfAbsent(priceLevel, k -> new TradeStats())
                      .addTrade(size);

        // Update price range
        if (priceLevel > highestPrice) highestPrice = priceLevel;
        if (priceLevel < lowestPrice) lowestPrice = priceLevel;
    }

    /**
     * Get volume at specific price level
     */
    public long getVolumeAtPrice(int price) {
        return volumeAtPrice.getOrDefault(price, 0L);
    }

    /**
     * Get total volume in a price range
     */
    public long getVolumeInRange(int minPrice, int maxPrice) {
        long total = 0;
        for (int p = minPrice; p <= maxPrice; p++) {
            total += volumeAtPrice.getOrDefault(p, 0L);
        }
        return total;
    }

    /**
     * Get volume near a price (within priceWindowTicks)
     */
    public VolumeArea getVolumeNearPrice(int price) {
        int minPrice = price - priceWindowTicks;
        int maxPrice = price + priceWindowTicks;

        long volumeAtLevel = getVolumeAtPrice(price);
        long totalNearby = getVolumeInRange(minPrice, maxPrice);
        long volumeBelow = getVolumeInRange(minPrice, price - 1);
        long volumeAbove = getVolumeInRange(price + 1, maxPrice);

        double ratio = totalNearby > 0 ? (double)volumeAtLevel / totalNearby : 0.0;

        return new VolumeArea(price, volumeAtLevel, totalNearby, volumeBelow, volumeAbove, ratio);
    }

    /**
     * Check if price is at a high-volume node (support/resistance)
     */
    public boolean isHighVolumeNode(int price) {
        return getVolumeAtPrice(price) >= minVolumeForNode;
    }

    /**
     * Get Point of Control (POC) - price with highest volume
     */
    public int getPOC() {
        return volumeAtPrice.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(0);
    }

    /**
     * Get Value Area (prices containing 70% of volume)
     */
    public ValueArea getValueArea() {
        if (totalVolume == 0) {
            return new ValueArea(0, 0, 0, 0);
        }

        long targetVolume = (long)(totalVolume * 0.70);
        long accumulatedVolume = 0;
        int vaHigh = 0;
        int vaLow = 0;

        // Start from POC and expand outward
        int poc = getPOC();
        int above = poc;
        int below = poc;

        while (accumulatedVolume < targetVolume && (above <= highestPrice || below >= lowestPrice)) {
            long volAbove = above <= highestPrice ? getVolumeAtPrice(above) : 0;
            long volBelow = below >= lowestPrice ? getVolumeAtPrice(below) : 0;

            if (volAbove >= volBelow && above <= highestPrice) {
                accumulatedVolume += volAbove;
                vaHigh = above;
                above++;
            } else if (below >= lowestPrice) {
                accumulatedVolume += volBelow;
                vaLow = below;
                below--;
            } else {
                break;
            }
        }

        return new ValueArea(vaLow, vaHigh, accumulatedVolume, totalVolume);
    }

    /**
     * Get volume imbalance at a price (bid vs ask pressure)
     */
    public VolumeImbalance getImbalance(int price) {
        TradeStats stats = tradesAtPrice.get(price);
        if (stats == null) {
            return new VolumeImbalance(0, 0, 0.0, "NEUTRAL");
        }

        double ratio = stats.askVolume > 0 ? (double)stats.bidVolume / stats.askVolume :
                       stats.bidVolume > 0 ? Double.MAX_VALUE : 0.0;

        String sentiment;
        if (ratio > 2.0) sentiment = "STRONG_BUYING";
        else if (ratio > 1.2) sentiment = "BUYING";
        else if (ratio < 0.5) sentiment = "STRONG_SELLING";
        else if (ratio < 0.8) sentiment = "SELLING";
        else sentiment = "BALANCED";

        return new VolumeImbalance(stats.bidVolume, stats.askVolume, ratio, sentiment);
    }

    /**
     * Get trade statistics at a price
     */
    public TradeStats getTradeStats(int price) {
        return tradesAtPrice.get(price);
    }

    /**
     * Get total volume
     */
    public long getTotalVolume() {
        return totalVolume;
    }

    /**
     * Get price range
     */
    public PriceRange getPriceRange() {
        return new PriceRange(lowestPrice, highestPrice);
    }

    /**
     * Reset volume profile
     */
    public void reset() {
        volumeAtPrice.clear();
        tradesAtPrice.clear();
        totalVolume = 0;
        highestPrice = Integer.MIN_VALUE;
        lowestPrice = Integer.MAX_VALUE;
    }

    /**
     * Volume area data
     */
    public static class VolumeArea {
        public final int price;
        public final long volumeAtPrice;
        public final long totalNearby;
        public final long volumeBelow;
        public final long volumeAbove;
        public final double volumeRatio;  // 0.0 to 1.0

        public VolumeArea(int price, long volumeAtPrice, long totalNearby,
                         long volumeBelow, long volumeAbove, double volumeRatio) {
            this.price = price;
            this.volumeAtPrice = volumeAtPrice;
            this.totalNearby = totalNearby;
            this.volumeBelow = volumeBelow;
            this.volumeAbove = volumeAbove;
            this.volumeRatio = volumeRatio;
        }

        public String getLevelType() {
            if (volumeRatio > 0.3) return "HIGH_VOLUME_NODE";
            if (volumeRatio < 0.05) return "LOW_VOLUME_NODE";
            return "NORMAL";
        }
    }

    /**
     * Value area data
     */
    public static class ValueArea {
        public final int vaLow;
        public final int vaHigh;
        public final long volumeInVA;
        public final long totalVolume;

        public ValueArea(int vaLow, int vaHigh, long volumeInVA, long totalVolume) {
            this.vaLow = vaLow;
            this.vaHigh = vaHigh;
            this.volumeInVA = volumeInVA;
            this.totalVolume = totalVolume;
        }

        public double getVAPercentage() {
            return totalVolume > 0 ? (volumeInVA * 100.0) / totalVolume : 0.0;
        }
    }

    /**
     * Volume imbalance data
     */
    public static class VolumeImbalance {
        public final long bidVolume;
        public final long askVolume;
        public final double ratio;  // bid/ask
        public final String sentiment;

        public VolumeImbalance(long bidVolume, long askVolume, double ratio, String sentiment) {
            this.bidVolume = bidVolume;
            this.askVolume = askVolume;
            this.ratio = ratio;
            this.sentiment = sentiment;
        }
    }

    /**
     * Trade statistics at a price level
     */
    public static class TradeStats {
        public long bidVolume = 0;
        public long askVolume = 0;
        public int tradeCount = 0;
        public int largestTrade = 0;

        public void addTrade(int size) {
            // Simplified - in real implementation, track bid/ask separately
            tradeCount++;
            if (size > largestTrade) largestTrade = size;
        }

        public void addBidTrade(int size) {
            bidVolume += size;
            tradeCount++;
            if (size > largestTrade) largestTrade = size;
        }

        public void addAskTrade(int size) {
            askVolume += size;
            tradeCount++;
            if (size > largestTrade) largestTrade = size;
        }

        public double getAvgTradeSize() {
            return tradeCount > 0 ? (double)(bidVolume + askVolume) / tradeCount : 0;
        }
    }

    /**
     * Price range
     */
    public static class PriceRange {
        public final int low;
        public final int high;
        public final int range;

        public PriceRange(int low, int high) {
            this.low = low;
            this.high = high;
            this.range = high - low;
        }
    }
}
