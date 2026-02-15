package velox.api.layer1.simplified.demo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Price Delta Tracker
 *
 * Tracks delta (aggressive buyers vs sellers) at each price level.
 * This allows detection of:
 *
 * 1. **Delta Outliers** - Large institutional interest at specific prices
 *    (e.g., +4200 delta at 5810 = "Big Fish" entered there)
 *
 * 2. **Big Fish Levels** - Prices where significant institutional activity occurred
 *
 * 3. **Defense Detection** - When price returns to a Big Fish level and they defend it
 *
 * 4. **Trapped Traders** - When aggressive participants are on the wrong side
 *
 * Based on Carmine Rosato's order flow methodology:
 * "Large outliers in Delta (e.g., +1,600 or +4,000) indicate significant
 * institutional interest at a specific price"
 */
public class PriceDeltaTracker {

    // Per-price delta tracking
    private final Map<Integer, PriceLevelData> priceLevels = new ConcurrentHashMap<>();

    // Big Fish levels - prices with significant institutional activity
    private final Map<Integer, BigFishLevel> bigFishLevels = new ConcurrentHashMap<>();

    // Configuration
    private final int outlierThreshold;      // Delta threshold to consider "outlier" (e.g., 500)
    private final int bigFishThreshold;       // Delta threshold for "Big Fish" (e.g., 2000)
    private final long levelExpiryMs;         // How long to remember a level (e.g., 30 min)
    private final int priceRange;             // How many price levels to track

    // Rolling window for statistics
    private final LinkedList<DeltaEvent> recentDeltas = new LinkedList<>();
    private static final int MAX_RECENT_EVENTS = 1000;

    // Statistics
    private double averageDelta = 0;
    private double stdDevDelta = 0;
    private long totalEvents = 0;

    /**
     * Data for a single price level
     */
    public static class PriceLevelData {
        public int price;
        public long delta;           // Cumulative delta at this price
        public long buyVolume;       // Total aggressive buying
        public long sellVolume;      // Total aggressive selling
        public int tradeCount;       // Number of trades
        public long lastUpdateMs;    // Last update timestamp
        public boolean isOutlier;    // Is this an outlier level?
        public boolean isBigFish;    // Is this a Big Fish level?

        public PriceLevelData(int price) {
            this.price = price;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        public double getImbalanceRatio() {
            long total = buyVolume + sellVolume;
            return total > 0 ? (double) (buyVolume - sellVolume) / total : 0;
        }

        public String getBias() {
            if (delta > 500) return "STRONG_BUY";
            if (delta > 100) return "BUY";
            if (delta < -500) return "STRONG_SELL";
            if (delta < -100) return "SELL";
            return "NEUTRAL";
        }
    }

    /**
     * Big Fish Level - significant institutional activity
     */
    public static class BigFishLevel {
        public int price;
        public long delta;              // Delta when first detected
        public long totalVolume;        // Total volume at this level
        public long firstDetectedMs;    // When first detected
        public long lastDefenseMs;      // Last time defended
        public int defenseCount;        // How many times defended
        public boolean isBuyer;         // true = buyer, false = seller
        public boolean isActive;        // Is still active?

        public BigFishLevel(int price, long delta, boolean isBuyer) {
            this.price = price;
            this.delta = delta;
            this.isBuyer = isBuyer;
            this.firstDetectedMs = System.currentTimeMillis();
            this.isActive = true;
        }

        public long getAgeMs() {
            return System.currentTimeMillis() - firstDetectedMs;
        }

        public void recordDefense() {
            this.lastDefenseMs = System.currentTimeMillis();
            this.defenseCount++;
        }
    }

    /**
     * Individual delta event for rolling statistics
     */
    private static class DeltaEvent {
        int price;
        long delta;
        long timestampMs;

        DeltaEvent(int price, long delta) {
            this.price = price;
            this.delta = delta;
            this.timestampMs = System.currentTimeMillis();
        }
    }

    /**
     * Result of analyzing current price for Big Fish behavior
     */
    public static class FishAnalysis {
        public boolean hasBigFishNearby;
        public BigFishLevel nearestBigFish;
        public int distanceToBigFish;
        public boolean isDefending;
        public String signal;           // "DEFENDED_BUY", "DEFENDED_SELL", "NONE"
        public String reasoning;
    }

    public PriceDeltaTracker(int outlierThreshold, int bigFishThreshold, long levelExpiryMs, int priceRange) {
        this.outlierThreshold = outlierThreshold;
        this.bigFishThreshold = bigFishThreshold;
        this.levelExpiryMs = levelExpiryMs;
        this.priceRange = priceRange;
    }

    /**
     * Default constructor with sensible defaults
     */
    public PriceDeltaTracker() {
        this(500, 2000, 30 * 60 * 1000, 100);  // 500 outlier, 2000 Big Fish, 30 min expiry, 100 price range
    }

    /**
     * Record a trade at a specific price
     * @param price Price in ticks
     * @param volume Volume of the trade
     * @param isBuy true if aggressive buyer, false if aggressive seller
     */
    public void recordTrade(int price, int volume, boolean isBuy) {
        long delta = isBuy ? volume : -volume;

        // Update price level data
        PriceLevelData level = priceLevels.computeIfAbsent(price, PriceLevelData::new);
        level.delta += delta;
        if (isBuy) {
            level.buyVolume += volume;
        } else {
            level.sellVolume += volume;
        }
        level.tradeCount++;
        level.lastUpdateMs = System.currentTimeMillis();

        // Check for outlier
        level.isOutlier = Math.abs(level.delta) >= outlierThreshold;

        // Check for Big Fish
        if (Math.abs(level.delta) >= bigFishThreshold && !level.isBigFish) {
            level.isBigFish = true;
            BigFishLevel bigFish = new BigFishLevel(price, level.delta, level.delta > 0);
            bigFishLevels.put(price, bigFish);
        }

        // Add to rolling window for statistics
        synchronized (recentDeltas) {
            recentDeltas.add(new DeltaEvent(price, delta));
            while (recentDeltas.size() > MAX_RECENT_EVENTS) {
                recentDeltas.removeFirst();
            }
            updateStatistics();
        }

        // Cleanup old levels periodically
        if (totalEvents % 100 == 0) {
            cleanupOldLevels();
        }
    }

    /**
     * Update rolling statistics
     */
    private void updateStatistics() {
        if (recentDeltas.isEmpty()) return;

        double sum = 0;
        for (DeltaEvent event : recentDeltas) {
            sum += event.delta;
        }
        averageDelta = sum / recentDeltas.size();

        double sumSquaredDiff = 0;
        for (DeltaEvent event : recentDeltas) {
            sumSquaredDiff += Math.pow(event.delta - averageDelta, 2);
        }
        stdDevDelta = Math.sqrt(sumSquaredDiff / recentDeltas.size());
        totalEvents++;
    }

    /**
     * Clean up expired levels
     */
    private void cleanupOldLevels() {
        long now = System.currentTimeMillis();
        long expiryThreshold = now - levelExpiryMs;

        // Remove expired price levels (but keep Big Fish levels longer)
        priceLevels.entrySet().removeIf(entry -> {
            if (entry.getValue().isBigFish) return false;  // Keep Big Fish levels
            return entry.getValue().lastUpdateMs < expiryThreshold;
        });

        // Deactivate old Big Fish levels
        for (BigFishLevel fish : bigFishLevels.values()) {
            if (fish.getAgeMs() > levelExpiryMs * 2) {  // Double time for Big Fish
                fish.isActive = false;
            }
        }
    }

    /**
     * Analyze current price for Big Fish behavior
     * Call this when price approaches a potentially important level
     */
    public FishAnalysis analyzeForBigFish(int currentPrice, int lookbackTicks) {
        FishAnalysis analysis = new FishAnalysis();
        analysis.hasBigFishNearby = false;
        analysis.signal = "NONE";

        // Find nearest active Big Fish level
        BigFishLevel nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (BigFishLevel fish : bigFishLevels.values()) {
            if (!fish.isActive) continue;

            int distance = Math.abs(currentPrice - fish.price);
            if (distance <= lookbackTicks && distance < nearestDistance) {
                nearest = fish;
                nearestDistance = distance;
            }
        }

        if (nearest == null) {
            analysis.reasoning = "No Big Fish levels nearby";
            return analysis;
        }

        analysis.hasBigFishNearby = true;
        analysis.nearestBigFish = nearest;
        analysis.distanceToBigFish = nearestDistance;

        // Check for defense
        // Defense = price returned to Big Fish level AND delta is building in same direction
        PriceLevelData currentLevel = priceLevels.get(currentPrice);

        if (nearestDistance <= 3 && currentLevel != null) {  // Within 3 ticks of Big Fish
            boolean defendingBuyer = nearest.isBuyer && currentLevel.delta > 0 && currentLevel.delta >= 100;
            boolean defendingSeller = !nearest.isBuyer && currentLevel.delta < 0 && currentLevel.delta <= -100;

            if (defendingBuyer) {
                analysis.isDefending = true;
                analysis.signal = "DEFENDED_BUY";
                analysis.reasoning = String.format(
                    "Big Fish BUYER @ %d (delta: +%d) defending at %d (current delta: +%d)",
                    nearest.price, nearest.delta, currentPrice, currentLevel.delta);
                nearest.recordDefense();
            } else if (defendingSeller) {
                analysis.isDefending = true;
                analysis.signal = "DEFENDED_SELL";
                analysis.reasoning = String.format(
                    "Big Fish SELLER @ %d (delta: %d) defending at %d (current delta: %d)",
                    nearest.price, nearest.delta, currentPrice, currentLevel.delta);
                nearest.recordDefense();
            } else {
                analysis.reasoning = String.format(
                    "Big Fish @ %d nearby but no defense detected", nearest.price);
            }
        } else {
            analysis.reasoning = String.format(
                "Big Fish @ %d is %d ticks away", nearest.price, nearestDistance);
        }

        return analysis;
    }

    /**
     * Get delta at a specific price
     */
    public long getDeltaAtPrice(int price) {
        PriceLevelData level = priceLevels.get(price);
        return level != null ? level.delta : 0;
    }

    /**
     * Get price level data
     */
    public PriceLevelData getPriceLevel(int price) {
        return priceLevels.get(price);
    }

    /**
     * Get all outlier levels (delta >= threshold)
     */
    public List<PriceLevelData> getOutlierLevels() {
        List<PriceLevelData> outliers = new ArrayList<>();
        for (PriceLevelData level : priceLevels.values()) {
            if (level.isOutlier) {
                outliers.add(level);
            }
        }
        outliers.sort((a, b) -> Long.compare(Math.abs(b.delta), Math.abs(a.delta)));
        return outliers;
    }

    /**
     * Get all active Big Fish levels
     */
    public List<BigFishLevel> getActiveBigFishLevels() {
        List<BigFishLevel> active = new ArrayList<>();
        for (BigFishLevel fish : bigFishLevels.values()) {
            if (fish.isActive) {
                active.add(fish);
            }
        }
        active.sort((a, b) -> Long.compare(Math.abs(b.delta), Math.abs(a.delta)));
        return active;
    }

    /**
     * Get top N price levels by |delta|
     */
    public List<PriceLevelData> getTopDeltaLevels(int n) {
        List<PriceLevelData> top = new ArrayList<>(priceLevels.values());
        top.sort((a, b) -> Long.compare(Math.abs(b.delta), Math.abs(a.delta)));
        return top.subList(0, Math.min(n, top.size()));
    }

    /**
     * Get statistics summary
     */
    public String getStatisticsSummary() {
        return String.format(
            "PriceDeltaTracker: %d levels tracked, %d outliers, %d Big Fish, avgDelta=%.1f, stdDev=%.1f",
            priceLevels.size(), getOutlierLevels().size(), getActiveBigFishLevels().size(),
            averageDelta, stdDevDelta);
    }

    /**
     * Get formatted summary of important levels for AI/display
     */
    public String getImportantLevelsSummary() {
        StringBuilder sb = new StringBuilder();

        List<BigFishLevel> bigFish = getActiveBigFishLevels();
        if (!bigFish.isEmpty()) {
            sb.append("Big Fish Levels:\n");
            for (BigFishLevel fish : bigFish) {
                sb.append(String.format("  %s @ %d (delta: %+d, defenses: %d)\n",
                    fish.isBuyer ? "BUYER" : "SELLER", fish.price, fish.delta, fish.defenseCount));
            }
        }

        List<PriceLevelData> outliers = getOutlierLevels();
        if (!outliers.isEmpty()) {
            sb.append("Delta Outliers:\n");
            for (int i = 0; i < Math.min(5, outliers.size()); i++) {
                PriceLevelData level = outliers.get(i);
                sb.append(String.format("  Price %d: delta=%+d (%s)\n",
                    level.price, level.delta, level.getBias()));
            }
        }

        return sb.toString();
    }

    /**
     * Reset all tracking data
     */
    public void reset() {
        priceLevels.clear();
        bigFishLevels.clear();
        synchronized (recentDeltas) {
            recentDeltas.clear();
        }
        averageDelta = 0;
        stdDevDelta = 0;
        totalEvents = 0;
    }
}
