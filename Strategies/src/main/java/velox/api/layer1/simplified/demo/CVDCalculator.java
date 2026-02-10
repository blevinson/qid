package velox.api.layer1.simplified.demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import velox.api.layer1.data.TradeInfo;

/**
 * CVD (Cumulative Volume Delta) Calculator
 * Tracks buying vs selling pressure
 */
public class CVDCalculator {
    private long cvd = 0;  // Cumulative Volume Delta
    private long sessionCVD = 0;  // Session CVD (reset daily)
    private long totalBuyVolume = 0;
    private long totalSellVolume = 0;

    // CVD at specific price levels
    private final Map<Integer, Long> cvdByPrice = new ConcurrentHashMap<>();

    // CVD history for divergence detection
    private final Map<Long, CVDSnapshot> cvdHistory = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_SIZE = 1000;

    /**
     * Update CVD on each trade
     */
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        int priceLevel = (int)price;

        // Calculate delta for this trade
        long delta = tradeInfo.isBidAggressor ? size : -size;

        // Update cumulative values
        cvd += delta;
        sessionCVD += delta;

        if (tradeInfo.isBidAggressor) {
            totalBuyVolume += size;
        } else {
            totalSellVolume += size;
        }

        // Track CVD at price level
        cvdByPrice.merge(priceLevel, delta, Long::sum);

        // Store snapshot periodically (every 100 trades or when price changes significantly)
        if (totalBuyVolume + totalSellVolume % 100 == 0) {
            long timestamp = System.currentTimeMillis();
            cvdHistory.put(timestamp, new CVDSnapshot(price, cvd, priceLevel));

            // Keep history manageable
            if (cvdHistory.size() > MAX_HISTORY_SIZE) {
                cvdHistory.remove(cvdHistory.keySet().iterator().next());
            }
        }
    }

    /**
     * Get current CVD
     */
    public long getCVD() {
        return cvd;
    }

    /**
     * Get session CVD
     */
    public long getSessionCVD() {
        return sessionCVD;
    }

    /**
     * Get CVD at specific price level
     */
    public long getCVDAtPrice(int price) {
        return cvdByPrice.getOrDefault(price, 0L);
    }

    /**
     * Get CVD trend direction
     */
    public String getCVDTrend() {
        if (cvd > 0) return "BULLISH";
        if (cvd < 0) return "BEARISH";
        return "NEUTRAL";
    }

    /**
     * Get CVD strength (normalized)
     */
    public double getCVDStrength() {
        long totalVolume = totalBuyVolume + totalSellVolume;
        if (totalVolume == 0) return 0.0;

        // Return CVD as percentage of total volume
        return (Math.abs(cvd) * 100.0) / totalVolume;
    }

    /**
     * Check for CVD divergence (price moving one way, CVD moving other)
     */
    public DivergenceType checkDivergence(double currentPrice, int lookbackSnapshots) {
        if (cvdHistory.size() < lookbackSnapshots) {
            return DivergenceType.NONE;
        }

        // Get recent snapshots
        CVDSnapshot[] snapshots = cvdHistory.values().toArray(new CVDSnapshot[0]);
        int start = Math.max(0, snapshots.length - lookbackSnapshots);
        CVDSnapshot oldest = snapshots[start];
        CVDSnapshot newest = snapshots[snapshots.length - 1];

        double priceChange = newest.price - oldest.price;
        long cvdChange = newest.cvd - oldest.cvd;

        // Bullish divergence: price down, CVD up (potential reversal up)
        if (priceChange < 0 && cvdChange > 100) {
            return DivergenceType.BULLISH;
        }

        // Bearish divergence: price up, CVD down (potential reversal down)
        if (priceChange > 0 && cvdChange < -100) {
            return DivergenceType.BEARISH;
        }

        return DivergenceType.NONE;
    }

    /**
     * Check if CVD is at extreme levels (potential exhaustion)
     */
    public boolean isAtExtreme(double threshold) {
        return getCVDStrength() > threshold;
    }

    /**
     * Get buy/sell ratio
     */
    public double getBuySellRatio() {
        if (totalSellVolume == 0) return totalBuyVolume > 0 ? Double.MAX_VALUE : 1.0;
        return (double)totalBuyVolume / totalSellVolume;
    }

    /**
     * Reset session CVD
     */
    public void resetSession() {
        sessionCVD = 0;
        totalBuyVolume = 0;
        totalSellVolume = 0;
        cvdByPrice.clear();
        cvdHistory.clear();
    }

    /**
     * CVD snapshot for divergence detection
     */
    public static class CVDSnapshot {
        public final double price;
        public final long cvd;
        public final int priceLevel;
        public final long timestamp;

        public CVDSnapshot(double price, long cvd, int priceLevel) {
            this.price = price;
            this.cvd = cvd;
            this.priceLevel = priceLevel;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Divergence types
     */
    public enum DivergenceType {
        NONE,
        BULLISH,  // Price down, CVD up
        BEARISH   // Price up, CVD down
    }
}
