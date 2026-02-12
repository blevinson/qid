package velox.api.layer1.simplified.demo;

import java.util.*;

/**
 * DOM (Depth of Market) Analyzer
 * Analyzes order book to find support/resistance levels based on liquidity
 *
 * Key features:
 * - Find largest bid/ask walls within a range (real-time S/R)
 * - Calculate bid/ask imbalance ratio
 * - Track liquidity concentration
 */
public class DOMAnalyzer {
    // Configuration
    private final int lookbackTicks;  // How many ticks to look for walls
    private final int minWallSize;    // Minimum size to be considered a "wall"

    // Cached analysis results
    private DOMLevel nearestSupport = null;
    private DOMLevel nearestResistance = null;
    private double imbalanceRatio = 1.0;  // bidVolume / askVolume
    private long totalBidVolume = 0;
    private long totalAskVolume = 0;
    private int currentPrice = 0;

    public DOMAnalyzer() {
        this(50, 100);  // Default: 50 ticks lookback, 100 min wall size
    }

    public DOMAnalyzer(int lookbackTicks, int minWallSize) {
        this.lookbackTicks = lookbackTicks;
        this.minWallSize = minWallSize;
    }

    /**
     * Analyze DOM from order book data
     * Uses OrderFlowStrategyEnhanced.OrderInfo for order data
     *
     * @param priceLevels Map of price -> list of order IDs at that price
     * @param orders Map of orderId -> OrderInfo (from strategy)
     * @param currentPrice Current market price
     */
    public void analyze(Map<Integer, List<String>> priceLevels, Map<String, ?> orders, int currentPrice) {
        this.currentPrice = currentPrice;

        // Reset calculations
        totalBidVolume = 0;
        totalAskVolume = 0;
        nearestSupport = null;
        nearestResistance = null;

        // Calculate volumes at each price level
        Map<Integer, Long> bidVolumesAtPrice = new TreeMap<>(Collections.reverseOrder());
        Map<Integer, Long> askVolumesAtPrice = new TreeMap<>();

        for (Map.Entry<Integer, List<String>> entry : priceLevels.entrySet()) {
            int price = entry.getKey();
            long bidSize = 0;
            long askSize = 0;

            for (String orderId : entry.getValue()) {
                Object infoObj = orders.get(orderId);
                if (infoObj != null) {
                    // Use reflection-like access to get fields from OrderInfo
                    try {
                        // Access fields dynamically - works with any class with these fields
                        java.lang.reflect.Field isBidField = infoObj.getClass().getDeclaredField("isBid");
                        java.lang.reflect.Field sizeField = infoObj.getClass().getDeclaredField("size");
                        isBidField.setAccessible(true);
                        sizeField.setAccessible(true);

                        boolean isBid = isBidField.getBoolean(infoObj);
                        int size = sizeField.getInt(infoObj);

                        if (isBid) {
                            bidSize += size;
                        } else {
                            askSize += size;
                        }
                    } catch (Exception e) {
                        // Skip this order if we can't access fields
                    }
                }
            }

            if (bidSize > 0) {
                bidVolumesAtPrice.put(price, bidSize);
                totalBidVolume += bidSize;
            }
            if (askSize > 0) {
                askVolumesAtPrice.put(price, askSize);
                totalAskVolume += askSize;
            }
        }

        // Calculate imbalance ratio
        if (totalAskVolume > 0) {
            imbalanceRatio = (double) totalBidVolume / totalAskVolume;
        } else if (totalBidVolume > 0) {
            imbalanceRatio = Double.MAX_VALUE;
        } else {
            imbalanceRatio = 1.0;
        }

        // Find nearest support (largest bid below current price)
        int supportRangeLow = currentPrice - lookbackTicks;
        long maxBidVolume = 0;
        int supportPrice = 0;

        for (Map.Entry<Integer, Long> entry : bidVolumesAtPrice.entrySet()) {
            int price = entry.getKey();
            long volume = entry.getValue();

            if (price < currentPrice && price >= supportRangeLow) {
                if (volume > maxBidVolume) {
                    maxBidVolume = volume;
                    supportPrice = price;
                }
            }
        }

        if (maxBidVolume >= minWallSize) {
            nearestSupport = new DOMLevel(supportPrice, maxBidVolume, true, currentPrice);
        }

        // Find nearest resistance (largest ask above current price)
        int resistanceRangeHigh = currentPrice + lookbackTicks;
        long maxAskVolume = 0;
        int resistancePrice = 0;

        for (Map.Entry<Integer, Long> entry : askVolumesAtPrice.entrySet()) {
            int price = entry.getKey();
            long volume = entry.getValue();

            if (price > currentPrice && price <= resistanceRangeHigh) {
                if (volume > maxAskVolume) {
                    maxAskVolume = volume;
                    resistancePrice = price;
                }
            }
        }

        if (maxAskVolume >= minWallSize) {
            nearestResistance = new DOMLevel(resistancePrice, maxAskVolume, false, currentPrice);
        }
    }

    /**
     * Get nearest support level (largest bid wall below current price)
     */
    public DOMLevel getNearestSupport() {
        return nearestSupport;
    }

    /**
     * Get nearest resistance level (largest ask wall above current price)
     */
    public DOMLevel getNearestResistance() {
        return nearestResistance;
    }

    /**
     * Get bid/ask imbalance ratio
     * > 1.0 = more bids (bullish)
     * < 1.0 = more asks (bearish)
     */
    public double getImbalanceRatio() {
        return imbalanceRatio;
    }

    /**
     * Get imbalance sentiment as string
     */
    public String getImbalanceSentiment() {
        if (imbalanceRatio > 2.0) return "STRONG_BULLISH";
        if (imbalanceRatio > 1.3) return "BULLISH";
        if (imbalanceRatio < 0.5) return "STRONG_BEARISH";
        if (imbalanceRatio < 0.75) return "BEARISH";
        return "NEUTRAL";
    }

    /**
     * Get total bid volume
     */
    public long getTotalBidVolume() {
        return totalBidVolume;
    }

    /**
     * Get total ask volume
     */
    public long getTotalAskVolume() {
        return totalAskVolume;
    }

    /**
     * Check if there's significant support nearby
     */
    public boolean hasSupportNearby() {
        return nearestSupport != null && nearestSupport.distanceTicks <= 20;
    }

    /**
     * Check if there's significant resistance nearby
     */
    public boolean hasResistanceNearby() {
        return nearestResistance != null && nearestResistance.distanceTicks <= 20;
    }

    /**
     * Get confluence score adjustment based on DOM
     * Positive = bullish, Negative = bearish
     */
    public int getConfluenceAdjustment(boolean isLong) {
        int adjustment = 0;

        // Imbalance bonus/penalty
        if (isLong) {
            if (imbalanceRatio > 2.0) adjustment += 5;
            else if (imbalanceRatio > 1.3) adjustment += 3;
            else if (imbalanceRatio < 0.5) adjustment -= 5;
            else if (imbalanceRatio < 0.75) adjustment -= 3;
        } else {
            if (imbalanceRatio < 0.5) adjustment += 5;
            else if (imbalanceRatio < 0.75) adjustment += 3;
            else if (imbalanceRatio > 2.0) adjustment -= 5;
            else if (imbalanceRatio > 1.3) adjustment -= 3;
        }

        // Support/Resistance proximity bonus
        if (isLong) {
            if (hasSupportNearby()) adjustment += 3;  // Support below for long
            if (hasResistanceNearby()) adjustment -= 2;  // Resistance above
        } else {
            if (hasResistanceNearby()) adjustment += 3;  // Resistance above for short
            if (hasSupportNearby()) adjustment -= 2;  // Support below
        }

        return adjustment;
    }

    /**
     * DOM Level data class
     */
    public static class DOMLevel {
        public final int price;
        public final long volume;
        public final boolean isSupport;  // true = bid wall, false = ask wall
        public final int distanceTicks;
        public final double strengthPercent;  // Volume as % of total

        public DOMLevel(int price, long volume, boolean isSupport, int currentPrice) {
            this.price = price;
            this.volume = volume;
            this.isSupport = isSupport;
            this.distanceTicks = Math.abs(currentPrice - price);
            this.strengthPercent = 0;  // Will be set after total calculation
        }

        public String getType() {
            return isSupport ? "SUPPORT" : "RESISTANCE";
        }

        @Override
        public String toString() {
            return String.format("%s: %d contracts @ %d (%d ticks away)",
                getType(), volume, price, distanceTicks);
        }
    }
}
