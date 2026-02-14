package velox.api.layer1.simplified.demo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Active Position Tracker
 * Tracks AI's open positions with all relevant data
 */
public class ActivePosition {
    // Position identification
    public final String id;
    public final String aiDecisionId;  // Link to AI decision that created this

    // Position details
    public final boolean isLong;
    public final int entryPrice;
    public final int quantity;
    public final long entryTime;
    public final int entrySlippage;  // Ticks of slippage at entry (signal price vs fill price)

    // Instrument
    public final String symbol;

    // Orders
    public final AtomicReference<String> entryOrderId = new AtomicReference<>();
    public final AtomicReference<String> stopLossOrderId = new AtomicReference<>();
    public final AtomicReference<String> takeProfitOrderId = new AtomicReference<>();

    // Stop and target prices
    public final AtomicInteger stopLossPrice;
    public final AtomicInteger takeProfitPrice;

    // Break-even tracking
    public final int breakEvenTriggerPrice;  // Price level to trigger break-even
    public final int breakEvenStopPrice;      // Stop price when at break-even
    public final AtomicBoolean breakEvenMoved = new AtomicBoolean(false);

    // Trailing stop tracking
    public final int trailAmount;            // How many ticks to trail
    public final AtomicInteger highestPrice = new AtomicInteger(0);   // For long positions
    public final AtomicInteger lowestPrice = new AtomicInteger(Integer.MAX_VALUE);  // For short positions

    // AI decision data
    public final SignalData originalSignal;
    public final AIIntegrationLayer.AIDecision aiDecision;

    // Historical snapshot of thresholds and weights at entry time
    public final EntryContext entryContext;

    // Performance tracking
    public final AtomicInteger maxUnrealizedPnl = new AtomicInteger(0);
    public final AtomicInteger maxAdverseExcursion = new AtomicInteger(0);

    // Exit tracking
    public final AtomicReference<String> exitReason = new AtomicReference<>();
    public final AtomicReference<String> exitOrderId = new AtomicReference<>();
    public final AtomicInteger exitPrice = new AtomicInteger(0);
    public final AtomicBoolean isClosed = new AtomicBoolean(false);

    /**
     * Snapshot of thresholds and weights at entry time for historical analysis
     */
    public static class EntryContext {
        // Instrument
        public String symbol;

        // Signal thresholds
        public int minConfluenceScore;
        public int confluenceThreshold;
        public double thresholdMultiplier;

        // Detection thresholds
        public int icebergMinOrders;
        public int spoofMinSize;
        public int absorptionMinSize;

        // Confluence weights
        public int icebergMax;
        public int icebergMultiplier;
        public int cvdAlignMax;
        public int cvdDivergePenalty;
        public int emaAlignMax;
        public int vwapAlign;
        public int vwapDiverge;

        /**
         * Format for logging
         */
        public String toLogString() {
            return String.format(
                "Symbol: %s | Thresholds: minScore=%d, conf=%d, mult=%.1f, iceberg=%d, spoof=%d, absorb=%d | Weights: iceMax=%d, cvdAlign=%d, cvdDiv=%d, emaAlign=%d, vwapAlign=%d, vwapDiv=%d",
                symbol,
                minConfluenceScore, confluenceThreshold, thresholdMultiplier,
                icebergMinOrders, spoofMinSize, absorptionMinSize,
                icebergMax, cvdAlignMax, cvdDivergePenalty, emaAlignMax, vwapAlign, vwapDiverge
            );
        }
    }

    public ActivePosition(String id, boolean isLong, int entryPrice, int quantity,
                         int stopLoss, int takeProfit, int breakEvenTrigger, int breakEvenStop,
                         int trailAmount, int entrySlippage,
                         SignalData signal, AIIntegrationLayer.AIDecision decision,
                         String symbol, ConfluenceWeights weights,
                         int minConfluenceScore, int confluenceThreshold, double thresholdMultiplier,
                         int icebergMinOrders, int spoofMinSize, int absorptionMinSize) {
        this.id = id;
        this.aiDecisionId = decision != null ? id : null;
        this.isLong = isLong;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTime = System.currentTimeMillis();
        this.entrySlippage = entrySlippage;
        this.symbol = symbol;
        this.stopLossPrice = new AtomicInteger(stopLoss);
        this.takeProfitPrice = new AtomicInteger(takeProfit);
        this.breakEvenTriggerPrice = breakEvenTrigger;
        this.breakEvenStopPrice = breakEvenStop;
        this.trailAmount = trailAmount;
        this.originalSignal = signal;
        this.aiDecision = decision;

        // Capture entry context for historical analysis
        this.entryContext = new EntryContext();
        this.entryContext.symbol = symbol;
        this.entryContext.minConfluenceScore = minConfluenceScore;
        this.entryContext.confluenceThreshold = confluenceThreshold;
        this.entryContext.thresholdMultiplier = thresholdMultiplier;
        this.entryContext.icebergMinOrders = icebergMinOrders;
        this.entryContext.spoofMinSize = spoofMinSize;
        this.entryContext.absorptionMinSize = absorptionMinSize;
        if (weights != null) {
            this.entryContext.icebergMax = weights.get(ConfluenceWeights.ICEBERG_MAX);
            this.entryContext.icebergMultiplier = weights.get(ConfluenceWeights.ICEBERG_MULTIPLIER);
            this.entryContext.cvdAlignMax = weights.get(ConfluenceWeights.CVD_ALIGN_MAX);
            this.entryContext.cvdDivergePenalty = weights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY);
            this.entryContext.emaAlignMax = weights.get(ConfluenceWeights.EMA_ALIGN_MAX);
            this.entryContext.vwapAlign = weights.get(ConfluenceWeights.VWAP_ALIGN);
            this.entryContext.vwapDiverge = weights.get(ConfluenceWeights.VWAP_DIVERGE);
        }

        // Initialize tracking prices
        if (isLong) {
            highestPrice.set(entryPrice);
        } else {
            lowestPrice.set(entryPrice);
        }
    }

    /**
     * Check if price has hit stop loss
     * Uses 1-tick tolerance to account for price not hitting exactly
     */
    public boolean isStopLossHit(int currentPrice) {
        if (isLong) {
            // For LONG, SL is below entry - trigger if price <= SL + 1 tick
            return currentPrice <= stopLossPrice.get() + 1;
        } else {
            // For SHORT, SL is above entry - trigger if price >= SL - 1 tick
            return currentPrice >= stopLossPrice.get() - 1;
        }
    }

    /**
     * Check if price has hit take profit
     * Uses 1-tick tolerance to account for price not hitting exactly
     */
    public boolean isTakeProfitHit(int currentPrice) {
        if (isLong) {
            // For LONG, TP is above entry - trigger if price >= TP - 1 tick
            return currentPrice >= takeProfitPrice.get() - 1;
        } else {
            // For SHORT, TP is below entry - trigger if price <= TP + 1 tick
            return currentPrice <= takeProfitPrice.get() + 1;
        }
    }

    /**
     * Check if break-even should be triggered
     */
    public boolean shouldTriggerBreakEven(int currentPrice) {
        if (breakEvenMoved.get()) return false;

        if (isLong) {
            return currentPrice >= breakEvenTriggerPrice;
        } else {
            return currentPrice <= breakEvenTriggerPrice;
        }
    }

    /**
     * Check if trailing stop should move
     */
    public boolean shouldTrailStop(int currentPrice) {
        int newTrailStop;

        if (isLong) {
            if (currentPrice > highestPrice.get()) {
                highestPrice.set(currentPrice);
                newTrailStop = currentPrice - trailAmount;
                return newTrailStop > stopLossPrice.get();
            }
        } else {
            if (currentPrice < lowestPrice.get()) {
                lowestPrice.set(currentPrice);
                newTrailStop = currentPrice + trailAmount;
                return newTrailStop < stopLossPrice.get() || stopLossPrice.get() == 0;
            }
        }

        return false;
    }

    /**
     * Calculate trailing stop price
     */
    public int calculateTrailStop(int currentPrice) {
        if (isLong) {
            return currentPrice - trailAmount;
        } else {
            return currentPrice + trailAmount;
        }
    }

    /**
     * Calculate unrealized P&L
     */
    public double calculateUnrealizedPnl(int currentPrice) {
        double priceDiff = currentPrice - entryPrice;
        if (!isLong) {
            priceDiff = -priceDiff;
        }
        return priceDiff * quantity * 12.5;  // ES futures: $12.50 per tick
    }

    /**
     * Update max unrealized P&L and max adverse excursion
     */
    public void updatePerformanceMetrics(int currentPrice) {
        double unrealizedPnl = calculateUnrealizedPnl(currentPrice);

        // Update max favorable excursion
        if (unrealizedPnl > maxUnrealizedPnl.get()) {
            maxUnrealizedPnl.set((int) unrealizedPnl);
        }

        // Update max adverse excursion
        if (unrealizedPnl < maxAdverseExcursion.get()) {
            maxAdverseExcursion.set((int) unrealizedPnl);
        }
    }

    /**
     * Mark position as closed
     */
    public void close(int exitPrice, String reason, String exitOrderId) {
        this.isClosed.set(true);
        this.exitPrice.set(exitPrice);
        this.exitReason.set(reason);
        this.exitOrderId.set(exitOrderId);
    }

    /**
     * Get time in position in milliseconds
     */
    public long getTimeInPosition() {
        long endTime = exitOrderId.get() != null ? System.currentTimeMillis() : System.currentTimeMillis();
        return endTime - entryTime;
    }

    /**
     * Get final realized P&L
     */
    public double getRealizedPnl() {
        if (!isClosed.get()) return 0;

        double priceDiff = exitPrice.get() - entryPrice;
        if (!isLong) {
            priceDiff = -priceDiff;
        }
        return priceDiff * quantity * 12.5;
    }

    @Override
    public String toString() {
        return String.format("ActivePosition[%s] %s %d @ %d (Slip: %d, Stop: %d, Target: %d, P&L: $%.2f)",
            id.substring(0, 8),
            isLong ? "LONG" : "SHORT",
            quantity,
            entryPrice,
            entrySlippage,
            stopLossPrice.get(),
            takeProfitPrice.get(),
            calculateUnrealizedPnl(isLong ? highestPrice.get() : lowestPrice.get())
        );
    }
}
