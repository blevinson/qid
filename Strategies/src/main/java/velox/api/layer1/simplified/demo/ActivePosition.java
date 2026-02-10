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

    // Performance tracking
    public final AtomicInteger maxUnrealizedPnl = new AtomicInteger(0);
    public final AtomicInteger maxAdverseExcursion = new AtomicInteger(0);

    // Exit tracking
    public final AtomicReference<String> exitReason = new AtomicReference<>();
    public final AtomicReference<String> exitOrderId = new AtomicReference<>();
    public final AtomicInteger exitPrice = new AtomicInteger(0);
    public final AtomicBoolean isClosed = new AtomicBoolean(false);

    public ActivePosition(String id, boolean isLong, int entryPrice, int quantity,
                         int stopLoss, int takeProfit, int breakEvenTrigger, int breakEvenStop,
                         int trailAmount,
                         SignalData signal, AIIntegrationLayer.AIDecision decision) {
        this.id = id;
        this.aiDecisionId = decision != null ? id : null;
        this.isLong = isLong;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTime = System.currentTimeMillis();
        this.stopLossPrice = new AtomicInteger(stopLoss);
        this.takeProfitPrice = new AtomicInteger(takeProfit);
        this.breakEvenTriggerPrice = breakEvenTrigger;
        this.breakEvenStopPrice = breakEvenStop;
        this.trailAmount = trailAmount;
        this.originalSignal = signal;
        this.aiDecision = decision;

        // Initialize tracking prices
        if (isLong) {
            highestPrice.set(entryPrice);
        } else {
            lowestPrice.set(entryPrice);
        }
    }

    /**
     * Check if price has hit stop loss
     */
    public boolean isStopLossHit(int currentPrice) {
        if (isLong) {
            return currentPrice <= stopLossPrice.get();
        } else {
            return currentPrice >= stopLossPrice.get();
        }
    }

    /**
     * Check if price has hit take profit
     */
    public boolean isTakeProfitHit(int currentPrice) {
        if (isLong) {
            return currentPrice >= takeProfitPrice.get();
        } else {
            return currentPrice <= takeProfitPrice.get();
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
        return String.format("ActivePosition[%s] %s %d @ %d (Stop: %d, Target: %d, P&L: $%.2f)",
            id.substring(0, 8),
            isLong ? "LONG" : "SHORT",
            quantity,
            entryPrice,
            stopLossPrice.get(),
            takeProfitPrice.get(),
            calculateUnrealizedPnl(isLong ? highestPrice.get() : lowestPrice.get())
        );
    }
}
