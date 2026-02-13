package velox.api.layer1.simplified.demo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * AI Order Manager
 * Handles all AI order placement and management
 */
public class AIOrderManager {
    private final OrderExecutor orderExecutor;
    private final AIIntegrationLayer.AIStrategyLogger logger;
    private final AIMarkerCallback markerCallback;

    // Active positions tracking
    private final Map<String, ActivePosition> activePositions = new ConcurrentHashMap<>();

    // Configuration
    public boolean breakEvenEnabled = true;
    public boolean trailingStopEnabled = false;  // Disabled by default
    public int breakEvenTicks = 3;
    public int trailAmountTicks = 2;
    private double pips = 1.0;  // Tick size - set from signal during executeEntry

    // Risk limits
    public int maxPositions = 1;
    public double maxDailyLoss = 500.0;

    // Signal staleness protection (configurable)
    // Note: Threshold must account for AI response time (can be 60+ seconds with retries)
    public long maxSignalAgeMs = 180_000;  // 3 minutes - allows for AI response delays
    public int maxPriceSlippageTicks = 20; // Skip if price moved > 20 ticks (reasonable for ES)
    private Supplier<Integer> currentPriceSupplier;  // Supplies current price in tick units

    // Statistics
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicInteger winningTrades = new AtomicInteger(0);
    private double dailyPnl = 0.0;

    // Unique instance ID for debugging
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public AIOrderManager(OrderExecutor orderExecutor, AIIntegrationLayer.AIStrategyLogger logger, AIMarkerCallback markerCallback) {
        this.orderExecutor = orderExecutor;
        this.logger = logger;
        this.markerCallback = markerCallback;
        // Log if callback is null at creation time
        fileLog("🏗️ AIOrderManager[" + instanceId + "] constructor: markerCallback=" + (markerCallback != null ? "NOT NULL" : "NULL") + ", orderExecutor=" + (orderExecutor != null ? "NOT NULL" : "NULL") + ", logger=" + (logger != null ? "NOT NULL" : "NULL"));
    }

    /**
     * Check if marker callback is set (for debugging)
     */
    public boolean hasMarkerCallback() {
        return markerCallback != null;
    }

    /**
     * Get instance ID (for debugging)
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Set current price supplier for staleness checks
     */
    public void setCurrentPriceSupplier(Supplier<Integer> supplier) {
        this.currentPriceSupplier = supplier;
    }

    /**
     * Check if signal is too old or price has moved too much
     * @return null if OK to proceed, or rejection reason if should skip
     */
    private String checkSignalStaleness(SignalData signal) {
        long now = System.currentTimeMillis();

        // Skip staleness check if timestamp is not set (0 or negative)
        // This handles unit tests and edge cases where timestamp isn't available
        if (signal.timestamp > 0) {
            long signalAge = now - signal.timestamp;

            // Check time staleness
            if (signalAge > maxSignalAgeMs) {
                return String.format("Signal too old: %d seconds (max %d)",
                    signalAge / 1000, maxSignalAgeMs / 1000);
            }

            // Log if signal is getting old but still OK
            if (signalAge > 30_000) {
                log("⏰ Signal age: %d seconds (approaching staleness limit)", signalAge / 1000);
            }
        }

        // Check price slippage if enabled (> 0) and we have a price supplier
        // Skip if currentPrice is 0 (uninitialized) to avoid false rejections
        if (maxPriceSlippageTicks > 0 && currentPriceSupplier != null && signal.pips > 0) {
            int currentPrice = currentPriceSupplier.get();

            // Debug: Log price values to understand unit mismatch
            fileLog("🔍 STALENESS DEBUG: signalPrice=" + signal.price + " ticks, currentPrice=" + currentPrice + " ticks, pips=" + signal.pips);
            fileLog("🔍 STALENESS DEBUG: signal.actualPrice=" + (signal.price * signal.pips) + ", current.actualPrice=" + (currentPrice * signal.pips));

            // Only check slippage if we have a valid current price (> 0)
            if (currentPrice > 0) {
                int signalPrice = signal.price;
                int slippage = Math.abs(currentPrice - signalPrice);

                if (slippage > maxPriceSlippageTicks) {
                    return String.format("Price moved too much: %d ticks (signal=%d, current=%d, max=%d)",
                        slippage, signalPrice, currentPrice, maxPriceSlippageTicks);
                }

                // Warn if there's some slippage but within tolerance
                if (slippage > 0) {
                    log("⚠️ Price slippage: %d ticks (within tolerance)", slippage);
                }
            }
        }

        return null;  // OK to proceed
    }

    /**
     * Execute AI decision to TAKE a signal
     */
    public String executeEntry(AIIntegrationLayer.AIDecision decision, SignalData signal) {
        // FILE LOG for debugging - include instance ID
        fileLog("🔥 executeEntry[" + instanceId + "] CALLED: isLong=%s, price=%d, SL=%d, TP=%d, hasCallback=%s".formatted(
            decision.isLong, signal.price, decision.stopLoss, decision.takeProfit, markerCallback != null));

        log("🔥 executeEntry[" + instanceId + "] CALLED: isLong=%s, price=%d, SL=%d, TP=%d".formatted(
            decision.isLong, signal.price, decision.stopLoss, decision.takeProfit));
        try {
            // Check signal staleness
            String stalenessReason = checkSignalStaleness(signal);
            if (stalenessReason != null) {
                log("🚫 STALE SIGNAL REJECTED: %s", stalenessReason);
                fileLog("🚫 STALE SIGNAL REJECTED: " + stalenessReason);
                return null;
            }
            log("✅ Staleness check PASSED");
            fileLog("✅ Staleness check PASSED");

            // Check if we can add a position
            if (activePositions.size() >= maxPositions) {
                log("⚠️ MAX POSITIONS REACHED (%d), cannot take signal", maxPositions);
                fileLog("⚠️ MAX POSITIONS REACHED: " + maxPositions);
                return null;
            }
            log("✅ Max positions check PASSED (current: %d)", activePositions.size());
            fileLog("✅ Max positions check PASSED (current: " + activePositions.size() + ")");

            // Check daily loss limit
            if (dailyPnl <= -maxDailyLoss) {
                log("⚠️ DAILY LOSS LIMIT REACHED ($%.2f), stopping trading", dailyPnl);
                fileLog("⚠️ DAILY LOSS LIMIT REACHED: $" + dailyPnl);
                return null;
            }
            log("✅ Daily loss check PASSED (current PnL: $%.2f)", dailyPnl);
            fileLog("✅ Daily loss check PASSED (current PnL: $" + dailyPnl + ")");

            // Calculate position size based on risk
            int positionSize = calculatePositionSize(signal);

            // Create position ID
            String positionId = UUID.randomUUID().toString();

            // Calculate break-even level (in tick units)
            int breakEvenTrigger = decision.isLong ?
                signal.price + breakEvenTicks :
                signal.price - breakEvenTicks;

            int breakEvenStop = decision.isLong ?
                signal.price + 1 :  // Entry + 1 tick
                signal.price - 1;   // Entry - 1 tick

            // Create active position tracker
            ActivePosition position = new ActivePosition(
                positionId,
                decision.isLong,
                signal.price,
                positionSize,
                decision.stopLoss,
                decision.takeProfit,
                breakEvenTrigger,
                breakEvenStop,
                trailAmountTicks,  // Trail amount in tick units
                signal,
                decision
            );

            // Place entry order
            // Convert tick prices to actual prices for Bookmap API
            this.pips = signal.pips;  // Store for later use in modify/stop methods
            double entryPriceActual = signal.price * pips;
            double stopLossPriceActual = decision.stopLoss * pips;
            double takeProfitPriceActual = decision.takeProfit * pips;

            log("📊 Price conversion: pips=%.4f", pips);
            log("📊 Entry: %d ticks → %.2f actual", signal.price, entryPriceActual);
            log("📊 SL: %d ticks → %.2f actual", decision.stopLoss, stopLossPriceActual);
            log("📊 TP: %d ticks → %.2f actual", decision.takeProfit, takeProfitPriceActual);

            log("📤 Placing ENTRY order: %s %d @ %.2f", decision.isLong ? "BUY" : "SELL", positionSize, entryPriceActual);
            OrderExecutor.OrderSide entrySide = decision.isLong ?
                OrderExecutor.OrderSide.BUY : OrderExecutor.OrderSide.SELL;

            String entryOrderId = orderExecutor.placeEntry(
                OrderExecutor.OrderType.MARKET,  // Use market orders for immediate execution
                entrySide,
                entryPriceActual,  // Actual price (ticks * pips)
                positionSize
            );
            log("📥 Entry order ID: %s", entryOrderId);

            position.entryOrderId.set(entryOrderId);

            // Place stop loss
            log("📤 Placing STOP LOSS order: %s @ %.2f", decision.isLong ? "SELL" : "BUY", stopLossPriceActual);
            OrderExecutor.OrderSide stopSide = decision.isLong ?
                OrderExecutor.OrderSide.SELL : OrderExecutor.OrderSide.BUY;

            String stopOrderId = orderExecutor.placeStopLoss(
                stopSide,
                stopLossPriceActual,  // Actual price
                positionSize
            );
            log("📥 Stop loss order ID: %s", stopOrderId);

            position.stopLossOrderId.set(stopOrderId);

            // Place take profit
            log("📤 Placing TAKE PROFIT order: %s @ %.2f", decision.isLong ? "SELL" : "BUY", takeProfitPriceActual);
            String targetOrderId = orderExecutor.placeTakeProfit(
                stopSide,  // Same side as stop (opposite of entry)
                takeProfitPriceActual,  // Actual price
                positionSize
            );
            log("📥 Take profit order ID: %s", targetOrderId);

            position.takeProfitOrderId.set(targetOrderId);

            // Validate all orders were placed
            if (entryOrderId == null || stopOrderId == null || targetOrderId == null) {
                log("❌ ORDER PLACEMENT FAILED - one or more orders returned null!");
                log("   Entry: %s, SL: %s, TP: %s", entryOrderId, stopOrderId, targetOrderId);
                log("   Marker will NOT be placed, position NOT tracked");
                fileLog("❌ ORDER PLACEMENT FAILED - Entry: " + entryOrderId + ", SL: " + stopOrderId + ", TP: " + targetOrderId);
                return null;
            }

            fileLog("✅ ALL ORDERS PLACED - Entry: " + entryOrderId + ", SL: " + stopOrderId + ", TP: " + targetOrderId);

            // Track position
            activePositions.put(positionId, position);
            log("✅ Position tracked: %s", positionId.substring(0, 8));

            // Place AI entry marker on chart (with SL/TP for line drawing)
            log("📍 Calling markerCallback.onEntryMarker...");
            fileLog("📍 markerCallback is: " + (markerCallback != null ? "NOT NULL" : "NULL"));
            if (markerCallback != null) {
                markerCallback.onEntryMarker(decision.isLong, signal.price, signal.score, decision.reasoning, decision.stopLoss, decision.takeProfit);
                log("✅ markerCallback.onEntryMarker completed");
                fileLog("✅ markerCallback.onEntryMarker completed");
            } else {
                log("⚠️ markerCallback is NULL!");
                fileLog("⚠️ markerCallback is NULL!");
            }

            log("🤖 AI ENTRY ORDER PLACED:");
            log("   Position ID: %s", positionId.substring(0, 8));
            log("   %s %d contract(s) @ %d", decision.isLong ? "LONG" : "SHORT", positionSize, signal.price);
            log("   Stop Loss: %d (-$%.0f)", decision.stopLoss, signal.risk.stopLossValue);
            log("   Take Profit: %d (+$%.0f)", decision.takeProfit, signal.risk.takeProfitValue);
            log("   Break-even: %d (%d ticks profit)", breakEvenStop, breakEvenTicks);
            log("   Reasoning: %s", decision.reasoning);

            return positionId;

        } catch (Exception e) {
            log("❌ Failed to execute entry: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Handle AI decision to SKIP a signal
     */
    public void executeSkip(AIIntegrationLayer.AIDecision decision, SignalData signal) {
        log("⚪ AI SKIP:");
        log("   %s @ %d (Score: %d)", signal.direction, signal.price, signal.score);
        log("   Reasoning: %s", decision.reasoning);
        log("   Confidence: %d%%", decision.confidence);

        // Place AI skip marker on chart
        if (markerCallback != null) {
            markerCallback.onSkipMarker(signal.price, signal.score, decision.reasoning);
        }
    }

    /**
     * Monitor positions and make adjustments
     * Called on each price update
     */
    private long lastTpSlLogTime = 0;
    public void onPriceUpdate(int currentPrice, long timestamp) {
        for (ActivePosition position : activePositions.values()) {
            if (position.isClosed.get()) continue;

            // Update performance metrics
            position.updatePerformanceMetrics(currentPrice);

            // Debug: Log when close to TP/SL (throttled)
            long now = System.currentTimeMillis();
            if (now - lastTpSlLogTime > 5000) {
                int slDist = Math.abs(currentPrice - position.stopLossPrice.get());
                int tpDist = Math.abs(currentPrice - position.takeProfitPrice.get());
                if (slDist <= 5 || tpDist <= 5) {
                    fileLog("📍 TP/SL Proximity: price=" + currentPrice + " SL=" + position.stopLossPrice.get() +
                        " TP=" + position.takeProfitPrice.get() + " (SL dist=" + slDist + ", TP dist=" + tpDist + ")");
                    lastTpSlLogTime = now;
                }
            }

            // Check if stop loss was hit
            if (position.isStopLossHit(currentPrice)) {
                fileLog("🛑 SL HIT DETECTED: price=" + currentPrice + " SL=" + position.stopLossPrice.get());
                closePosition(position.id, currentPrice, "Stop Loss Hit", null);
                continue;
            }

            // Check if take profit was hit
            if (position.isTakeProfitHit(currentPrice)) {
                fileLog("💎 TP HIT DETECTED: price=" + currentPrice + " TP=" + position.takeProfitPrice.get());
                closePosition(position.id, currentPrice, "Take Profit Hit", null);
                continue;
            }

            // Check break-even trigger
            if (breakEvenEnabled && position.shouldTriggerBreakEven(currentPrice)) {
                moveStopToBreakEven(position);
            }

            // Check trailing stop
            if (trailingStopEnabled && position.shouldTrailStop(currentPrice)) {
                trailStop(position, currentPrice);
            }
        }
    }

    /**
     * Move stop loss to break-even
     */
    private void moveStopToBreakEven(ActivePosition position) {
        try {
            // Check if we have a valid stop loss order ID
            String stopOrderId = position.stopLossOrderId.get();
            if (stopOrderId == null || stopOrderId.isEmpty()) {
                log("⚠️ Cannot move to break-even: no stop loss order ID");
                fileLog("⚠️ moveStopToBreakEven: no stop loss order ID for position " + position.id);
                return;
            }

            int newStopPriceTicks = position.breakEvenStopPrice;
            double newStopPriceActual = newStopPriceTicks * pips;  // Convert to actual price

            fileLog("🟡 moveStopToBreakEven: orderId=" + stopOrderId + ", newStopPrice=" + newStopPriceActual);

            // Modify stop loss order
            String newStopOrderId = orderExecutor.modifyStopLoss(
                stopOrderId,
                newStopPriceActual,  // Actual price
                position.quantity
            );

            if (newStopOrderId != null) {
                position.stopLossOrderId.set(newStopOrderId);
                position.stopLossPrice.set(newStopPriceTicks);  // Keep tick value for internal tracking
                position.breakEvenMoved.set(true);

                // Place break-even marker on chart
                if (markerCallback != null) {
                    markerCallback.onBreakEvenMarker(newStopPriceTicks, position.breakEvenTriggerPrice);
                }

                log("🟡 BREAK-EVEN TRIGGERED:");
                log("   Position: %s", position.id.substring(0, 8));
                log("   Stop moved: %d → %d (actual: %.2f)",
                    position.breakEvenTriggerPrice, newStopPriceTicks, newStopPriceActual);
                log("   Now risking only 1 tick");
            }
        } catch (Exception e) {
            log("❌ Failed to move stop to break-even: %s", e.getMessage());
        }
    }

    /**
     * Trail stop loss
     */
    private void trailStop(ActivePosition position, int currentPrice) {
        try {
            int newStopPriceTicks = position.calculateTrailStop(currentPrice);
            double newStopPriceActual = newStopPriceTicks * pips;  // Convert to actual price

            // Modify stop loss order
            String newStopOrderId = orderExecutor.modifyStopLoss(
                position.stopLossOrderId.get(),
                newStopPriceActual,  // Actual price
                position.quantity
            );

            if (newStopOrderId != null) {
                int oldStop = position.stopLossPrice.get();
                position.stopLossOrderId.set(newStopOrderId);
                position.stopLossPrice.set(newStopPriceTicks);  // Keep tick value for internal tracking

                double lockedProfit = position.isLong ?
                    (currentPrice - newStopPriceTicks) * 12.5 :
                    (newStopPriceTicks - currentPrice) * 12.5;

                log("📍 TRAILING STOP:");
                log("   Position: %s", position.id.substring(0, 8));
                log("   Stop trailed: %d → %d (actual: %.2f)", oldStop, newStopPriceTicks, newStopPriceActual);
                log("   Locked in profit: $%.2f", lockedProfit);
            }
        } catch (Exception e) {
            log("❌ Failed to trail stop: %s", e.getMessage());
        }
    }

    /**
     * Close position
     */
    public void closePosition(String positionId, int exitPrice, String reason, String triggerOrderId) {
        ActivePosition position = activePositions.get(positionId);
        if (position == null || position.isClosed.get()) return;

        try {
            // Cancel existing orders
            if (position.stopLossOrderId.get() != null) {
                orderExecutor.cancelOrder(position.stopLossOrderId.get());
            }
            if (position.takeProfitOrderId.get() != null) {
                orderExecutor.cancelOrder(position.takeProfitOrderId.get());
            }

            // Close position at market
            OrderExecutor.OrderSide closeSide = position.isLong ?
                OrderExecutor.OrderSide.SELL : OrderExecutor.OrderSide.BUY;

            String exitOrderId = orderExecutor.closePosition(closeSide, position.quantity);

            // Mark position as closed
            position.close(exitPrice, reason, exitOrderId != null ? exitOrderId : triggerOrderId);

            // Update statistics (calculate PnL first for marker callback)
            double pnl = position.getRealizedPnl();
            dailyPnl += pnl;
            totalTrades.incrementAndGet();
            if (pnl > 0) {
                winningTrades.incrementAndGet();
            }

            // Place AI exit marker on chart
            if (markerCallback != null) {
                boolean isWin = exitPrice != ((position.isLong ? position.stopLossPrice.get() : position.stopLossPrice.get()));
                markerCallback.onExitMarker(exitPrice, reason, pnl, isWin);
            }

            // Log closure
            String emoji = pnl > 0 ? "💎" : "🛑";
            log("%s POSITION CLOSED:", emoji);
            log("   Position ID: %s", positionId.substring(0, 8));
            log("   %s %d @ %d → %d",
                position.isLong ? "LONG" : "SHORT",
                position.quantity,
                position.entryPrice,
                exitPrice);
            log("   Reason: %s", reason);
            log("   P&L: $%.2f", pnl);
            log("   Time in trade: %d seconds", position.getTimeInPosition() / 1000);
            log("   Max Favorable: $%.2f", position.maxUnrealizedPnl.get() / 100.0);
            log("   Max Adverse: -$%.2f", Math.abs(position.maxAdverseExcursion.get() / 100.0));

            // Log daily summary
            log("📊 Daily Summary:");
            log("   Daily P&L: $%.2f", dailyPnl);
            log("   Trades Today: %d", totalTrades.get());
            log("   Win Rate: %.1f%%", getWinRate());

        } catch (Exception e) {
            log("❌ Failed to close position: %s", e.getMessage());
        }
    }

    /**
     * Early exit based on AI analysis
     * Can be called when AI detects changing conditions
     */
    public void earlyExit(String positionId, String reason) {
        ActivePosition position = activePositions.get(positionId);
        if (position == null || position.isClosed.get()) return;

        int currentPrice = position.isLong ?
            position.highestPrice.get() :
            position.lowestPrice.get();

        closePosition(positionId, currentPrice, "Early Exit: " + reason, null);
    }

    /**
     * Cancel pending entry order
     * Can be called if AI changes mind before fill
     */
    public boolean cancelEntry(String positionId) {
        ActivePosition position = activePositions.get(positionId);
        if (position == null) return false;

        try {
            boolean cancelled = orderExecutor.cancelOrder(position.entryOrderId.get());
            if (cancelled) {
                activePositions.remove(positionId);
                log("❌ ENTRY ORDER CANCELLED:");
                log("   Position ID: %s", positionId.substring(0, 8));
                log("   Order cancelled before fill");
            }
            return cancelled;
        } catch (Exception e) {
            log("❌ Failed to cancel entry: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Calculate position size based on risk
     */
    private int calculatePositionSize(SignalData signal) {
        if (signal.account == null || signal.risk == null) {
            return 1;  // Default to 1 contract
        }

        double accountRisk = signal.account.accountSize * (signal.risk.totalRiskPercent / 100.0);
        double stopRisk = signal.risk.stopLossValue;

        int contracts = (int) Math.floor(accountRisk / stopRisk);

        // Apply limits
        contracts = Math.max(1, contracts);  // Minimum 1
        contracts = Math.min(signal.account.maxContracts, contracts);  // Maximum

        return contracts;
    }

    /**
     * Get win rate
     */
    public double getWinRate() {
        int total = totalTrades.get();
        if (total == 0) return 0.0;
        return (winningTrades.get() * 100.0) / total;
    }

    /**
     * Get daily P&L
     */
    public double getDailyPnl() {
        return dailyPnl;
    }

    /**
     * Reset daily statistics
     */
    public void resetDailyStats() {
        dailyPnl = 0.0;
    }

    /**
     * Get active positions count
     */
    public int getActivePositionCount() {
        return activePositions.size();
    }

    /**
     * Get total trades count
     */
    public int getTotalTrades() {
        return totalTrades.get();
    }

    /**
     * Get winning trades count
     */
    public int getWinningTrades() {
        return winningTrades.get();
    }

    /**
     * Get losing trades count
     */
    public int getLosingTrades() {
        return totalTrades.get() - winningTrades.get();
    }

    /**
     * Check if can take new position
     */
    public boolean canTakeNewPosition() {
        return activePositions.size() < maxPositions && dailyPnl > -maxDailyLoss;
    }

    /**
     * Log helper
     */
    private void log(String message, Object... args) {
        if (logger != null) {
            logger.log(String.format(message, args));
        }
    }

    /**
     * File log for debugging
     */
    private void fileLog(String message) {
        try (java.io.PrintWriter fw = new java.io.PrintWriter(
                new java.io.FileWriter(System.getProperty("user.home") + "/ai-execution.log", true))) {
            fw.println(new java.util.Date() + " " + message);
            fw.flush();
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Clean up closed positions (call periodically)
     */
    public void cleanupClosedPositions() {
        activePositions.entrySet().removeIf(entry -> entry.getValue().isClosed.get());
    }

    /**
     * Callback interface for placing AI action markers on chart
     */
    public interface AIMarkerCallback {
        /**
         * Called when AI enters a trade
         * @param isLong true for long, false for short
         * @param price entry price
         * @param score confluence score
         * @param reasoning AI reasoning
         * @param stopLossPrice stop loss price level
         * @param takeProfitPrice take profit price level
         */
        void onEntryMarker(boolean isLong, int price, int score, String reasoning, int stopLossPrice, int takeProfitPrice);

        /**
         * Called when AI skips a signal
         */
        void onSkipMarker(int price, int score, String reasoning);

        /**
         * Called when position exits (SL/TP/Manual)
         */
        void onExitMarker(int price, String reason, double pnl, boolean isWin);

        /**
         * Called when break-even is triggered
         */
        void onBreakEvenMarker(int newStopPrice, int triggerPrice);
    }
}
