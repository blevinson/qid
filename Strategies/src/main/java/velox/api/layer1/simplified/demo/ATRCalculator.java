package velox.api.layer1.simplified.demo;

/**
 * ATR (Average True Range) Calculator
 * Measures volatility
 */
public class ATRCalculator {
    private final int period;
    private final double[] trueRanges;
    private int index = 0;
    private boolean filled = false;
    private double atr = Double.NaN;
    private double previousClose = Double.NaN;

    public ATRCalculator(int period) {
        this.period = period;
        this.trueRanges = new double[period];
    }

    /**
     * Update ATR with new data
     */
    public void update(double high, double low, double close) {
        double trueRange;

        if (Double.isNaN(previousClose)) {
            trueRange = high - low;
        } else {
            double tr1 = high - low;
            double tr2 = Math.abs(high - previousClose);
            double tr3 = Math.abs(low - previousClose);
            trueRange = Math.max(tr1, Math.max(tr2, tr3));
        }

        trueRanges[index] = trueRange;
        index = (index + 1) % period;

        if (index == 0) filled = true;

        if (filled) {
            double sum = 0;
            for (double tr : trueRanges) {
                sum += tr;
            }
            atr = sum / period;
        }

        previousClose = close;
    }

    /**
     * Get current ATR
     */
    public double getATR() {
        return atr;
    }

    /**
     * Check if ATR is initialized
     */
    public boolean isInitialized() {
        return !Double.isNaN(atr);
    }

    /**
     * Get ATR level classification
     */
    public String getATRLevel(double baselineATR) {
        if (Double.isNaN(atr) || Double.isNaN(baselineATR)) return "UNKNOWN";

        double ratio = atr / baselineATR;

        if (ratio > 1.5) return "HIGH";
        if (ratio > 0.8) return "MODERATE";
        return "LOW";
    }

    /**
     * Reset ATR
     */
    public void reset() {
        index = 0;
        filled = false;
        atr = Double.NaN;
        previousClose = Double.NaN;
        for (int i = 0; i < period; i++) {
            trueRanges[i] = 0;
        }
    }
}
