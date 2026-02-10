package velox.api.layer1.simplified.demo;

/**
 * VWAP (Volume-Weighted Average Price) Calculator
 */
public class VWAPCalculator {
    private double sumPriceVolume = 0.0;
    private long sumVolume = 0;
    private double vwap = Double.NaN;
    private long lastResetTime = 0;

    /**
     * Update VWAP with new trade
     */
    public void update(double price, int size) {
        sumPriceVolume += price * size;
        sumVolume += size;
        recalculate();
    }

    /**
     * Recalculate VWAP
     */
    private void recalculate() {
        if (sumVolume > 0) {
            vwap = sumPriceVolume / sumVolume;
        }
    }

    /**
     * Get current VWAP
     */
    public double getVWAP() {
        return vwap;
    }

    /**
     * Check if VWAP is initialized
     */
    public boolean isInitialized() {
        return !Double.isNaN(vwap);
    }

    /**
     * Get relationship between price and VWAP
     */
    public String getRelationship(double price) {
        if (Double.isNaN(vwap)) return "UNKNOWN";

        double percentDiff = ((price - vwap) / vwap) * 100;

        if (percentDiff > 0.1) return "ABOVE";
        if (percentDiff < -0.1) return "BELOW";
        return "NEAR";
    }

    /**
     * Get distance in ticks from VWAP
     */
    public double getDistance(double price, int pips) {
        if (Double.isNaN(vwap)) return 0;
        return (price - vwap) / pips;
    }

    /**
     * Get total volume
     */
    public long getTotalVolume() {
        return sumVolume;
    }

    /**
     * Reset VWAP (for new session)
     */
    public void reset() {
        sumPriceVolume = 0.0;
        sumVolume = 0;
        vwap = Double.NaN;
        lastResetTime = System.currentTimeMillis();
    }
}
