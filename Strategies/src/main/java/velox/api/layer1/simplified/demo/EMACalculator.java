package velox.api.layer1.simplified.demo;

/**
 * EMA (Exponential Moving Average) Calculator
 */
public class EMACalculator {
    private final double multiplier;
    private double ema = Double.NaN;
    private final int period;
    private boolean initialized = false;
    private int sampleCount = 0;

    public EMACalculator(int period) {
        this.period = period;
        this.multiplier = 2.0 / (period + 1);
    }

    /**
     * Update EMA with new price
     */
    public void update(double price) {
        sampleCount++;

        if (!initialized) {
            ema = price;
            initialized = true;
        } else {
            ema = (price - ema) * multiplier + ema;
        }
    }

    /**
     * Get current EMA value
     */
    public double getEMA() {
        return ema;
    }

    /**
     * Check if EMA is initialized
     */
    public boolean isInitialized() {
        return initialized && sampleCount >= period;
    }

    /**
     * Get relationship between price and EMA
     */
    public String getRelationship(double price) {
        if (Double.isNaN(ema)) return "UNKNOWN";

        double pipsDiff = price - ema;
        double percentDiff = (pipsDiff / ema) * 100;

        if (percentDiff > 0.1) return "ABOVE";
        if (percentDiff < -0.1) return "BELOW";
        return "NEAR";
    }

    /**
     * Get distance in ticks from EMA
     */
    public double getDistance(double price, double pips) {
        if (Double.isNaN(ema)) return 0;
        return (price - ema) / pips;
    }

    /**
     * Get period
     */
    public int getPeriod() {
        return period;
    }

    /**
     * Reset EMA
     */
    public void reset() {
        ema = Double.NaN;
        initialized = false;
        sampleCount = 0;
    }
}
