package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradeLogger;
import velox.api.layer1.simplified.demo.storage.SessionStateManager;

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
    private double tickValue = 12.50;  // Dollar value per tick (ES futures default)

    // Risk limits
    public int maxPositions = 1;
    public double maxDailyLoss = 500.0;

    // Signal staleness protection (configurable)
    // Note: Threshold must account for AI response time (can be 60+ seconds with retries)
    public long maxSignalAgeMs = 180_000;  // 3 minutes - allows for AI response delays
    public int maxPriceSlippageTicks = 50; // Skip if price moved > 50 ticks
    private Supplier<Integer> currentPriceSupplier;  // Supplies current price in tick units

    // Suppliers for historical context (set from strategy)
    private Supplier<String> symbolSupplier;
    private Supplier<ConfluenceWeights> weightsSupplier;
    private Supplier<Integer> minConfluenceScoreSupplier;
    private Supplier<Integer> confluenceThresholdSupplier;
    private Supplier<Double> thresholdMultiplierSupplier;
    private Supplier<Integer> icebergMinOrdersSupplier;
    private Supplier<Integer> spoofMinSizeSupplier;
    private Supplier<Integer> absorptionMinSizeSupplier;

    // Statistics
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicInteger winningTrades = new AtomicInteger(0);
    private double dailyPnl = 0.0;

    // Trade logging and session state
    private TradeLogger tradeLogger;
    private SessionStateManager sessionStateManager;

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
     * Set current time supplier for staleness checks (for replay mode support)
     */
    private Supplier<Long> currentTimeSupplier;
    public void setCurrentTimeSupplier(Supplier<Long> supplier) {
        this.currentTimeSupplier = supplier;
    }

    /**
     * Set suppliers for historical context recording
     */
    public void setContextSuppliers(
            Supplier<String> symbolSupplier,
            Supplier<ConfluenceWeights> weightsSupplier,
            Supplier<Integer> minConfluenceScoreSupplier,
            Supplier<Integer> confluenceThresholdSupplier,
            Supplier<Double> thresholdMultiplierSupplier,
            Supplier<Integer> icebergMinOrdersSupplier,
            Supplier<Integer> spoofMinSizeSupplier,
            Supplier<Integer> absorptionMinSizeSupplier) {
        this.symbolSupplier = symbolSupplier;
        this.weightsSupplier = weightsSupplier;
        this.minConfluenceScoreSupplier = minConfluenceScoreSupplier;
        this.confluenceThresholdSupplier = confluenceThresholdSupplier;
        this.thresholdMultiplierSupplier = thresholdMultiplierSupplier;
        this.icebergMinOrdersSupplier = icebergMinOrdersSupplier;
        this.spoofMinSizeSupplier = spoofMinSizeSupplier;
        this.absorptionMinSizeSupplier = absorptionMinSizeSupplier;
    }

    /**
     * Set trade logger for persistent trade history
     */
    public void setTradeLogger(TradeLogger tradeLogger) {
        this.tradeLogger = tradeLogger;
        fileLog("📝 TradeLogger set: " + (tradeLogger != null ? "ENABLED" : "DISABLED"));
    }

    /**
     * Set session state manager for persistent daily stats
     */
    public void setSessionStateManager(SessionStateManager sessionStateManager) {
        this.sessionStateManager = sessionStateManager;
        // Load persisted stats into memory
        if (sessionStateManager != null) {
            SessionStateManager.SessionState state = sessionStateManager.getCurrentState();
            this.dailyPnl = state.dailyPnl;
            this.totalTrades.set(state.totalTrades);
            this.winningTrades.set(state.winningTrades);
            fileLog("📊 SessionState loaded: " + state);
        }
    }

    /**
     * Set tick value (dollar value per tick)
     */
    public void setTickValue(double tickValue) {
        this.tickValue = tickValue;
    }

    /**
     * Check if signal is too old or price has moved too much
     * @return null if OK to proceed, or rejection reason if should skip
     */
    private String checkSignalStaleness(SignalData signal) {
        // Use currentTimeSupplier if available (for replay mode support)
        // Otherwise fall back to wall clock time
        long now = currentTimeSupplier != null ? currentTimeSupplier.get() : System.currentTimeMillis();

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
        fileLog("🔥 executeEntry[" + instanceId + "] CALLED: isLong=%s, price=%d, SL=%d, TP=%d, execType=%s, hasCallback=%s".formatted(
            decision.isLong, signal.price, decision.stopLoss, decision.takeProfit,
            decision.executionType, markerCallback != null));

        log("🔥 executeEntry[" + instanceId + "] CALLED: isLong=%s, price=%d, SL=%d, TP=%d, execType=%s".formatted(
            decision.isLong, signal.price, decision.stopLoss, decision.takeProfit, decision.executionType));
        try {
            // Check signal staleness
            String stalenessReason = checkSignalStaleness(signal);
            if (stalenessReason != null) {
                log("🚫 STALE SIGNAL REJECTED: %s", stalenessReason);
                fileLog("🚫 STALE SIGNAL REJECTED: " + stalenessReason);

                // Place slippage rejection marker on chart
                if (markerCallback != null && currentPriceSupplier != null) {
                    int currentPrice = currentPriceSupplier.get();
                    int slippage = Math.abs(signal.price - currentPrice);
                    markerCallback.onSlippageRejectedMarker(signal.price, currentPrice, slippage);
                }
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

            // Determine execution type
            String execType = decision.executionType != null ? decision.executionType : "MARKET";
            int triggerPrice = decision.triggerPrice != null ? decision.triggerPrice : signal.price;

            // Calculate break-even level (in tick units)
            int breakEvenTrigger = decision.isLong ?
                signal.price + breakEvenTicks :
                signal.price - breakEvenTicks;

            int breakEvenStop = decision.isLong ?
                signal.price + 1 :  // Entry + 1 tick
                signal.price - 1;   // Entry - 1 tick

            // Calculate entry slippage (signal price vs current price)
            int entrySlippage = 0;
            if (currentPriceSupplier != null) {
                int currentPrice = currentPriceSupplier.get();
                if (currentPrice > 0) {
                    entrySlippage = Math.abs(currentPrice - signal.price);
                }
            }

            // Get context values from suppliers for historical recording
            String symbol = symbolSupplier != null ? symbolSupplier.get() : "UNKNOWN";
            ConfluenceWeights weights = weightsSupplier != null ? weightsSupplier.get() : null;
            int minConfluenceScore = minConfluenceScoreSupplier != null ? minConfluenceScoreSupplier.get() : 0;
            int confluenceThreshold = confluenceThresholdSupplier != null ? confluenceThresholdSupplier.get() : 50;
            double thresholdMultiplier = thresholdMultiplierSupplier != null ? thresholdMultiplierSupplier.get() : 3.0;
            int icebergMinOrders = icebergMinOrdersSupplier != null ? icebergMinOrdersSupplier.get() : 10;
            int spoofMinSize = spoofMinSizeSupplier != null ? spoofMinSizeSupplier.get() : 20;
            int absorptionMinSize = absorptionMinSizeSupplier != null ? absorptionMinSizeSupplier.get() : 50;

            // Create active position tracker with full context
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
                entrySlippage,     // Entry slippage in ticks
                signal,
                decision,
                symbol,
                weights,
                minConfluenceScore,
                confluenceThreshold,
                thresholdMultiplier,
                icebergMinOrders,
                spoofMinSize,
                absorptionMinSize
            );

            // Store pips for later use
            this.pips = signal.pips;

            // Calculate offsets in ticks
            int slOffsetTicks = Math.abs(signal.price - decision.stopLoss);
            int tpOffsetTicks = Math.abs(decision.takeProfit - signal.price);

            OrderExecutor.OrderSide entrySide = decision.isLong ?
                OrderExecutor.OrderSide.BUY : OrderExecutor.OrderSide.SELL;

            String entryOrderId;

            // ========== ORDER TYPE SELECTION ==========
            if ("STOP_MARKET".equalsIgnoreCase(execType)) {
                // STOP_MARKET: Place stop order at trigger price, executes as market when triggered
                log("📊 STOP_MARKET order: trigger=%d ticks (%.2f)", triggerPrice, triggerPrice * signal.pips);
                log("📊 SL/TP will be placed AFTER fill (pending order tracking)");
                fileLog("📝 STOP_MARKET: trigger=" + triggerPrice + " SL=" + decision.stopLoss + " TP=" + decision.takeProfit);

                entryOrderId = orderExecutor.placeEntry(
                    OrderExecutor.OrderType.STOP_MARKET,
                    entrySide,
                    triggerPrice * signal.pips,  // Trigger price as actual price
                    positionSize
                );

                if (entryOrderId != null) {
                    // Track pending order for SL/TP placement after fill
                    position.pendingSLTP.set(true);
                    position.pendingTriggerPrice.set(triggerPrice);
                    log("📥 Stop market order placed: %s (waiting for trigger at %d)", entryOrderId, triggerPrice);
                }

            } else if ("LIMIT".equalsIgnoreCase(execType)) {
                // LIMIT: Place limit order at trigger price
                log("📊 LIMIT order: price=%d ticks (%.2f)", triggerPrice, triggerPrice * signal.pips);
                log("📊 SL/TP will be placed AFTER fill (pending order tracking)");
                fileLog("📝 LIMIT: price=" + triggerPrice + " SL=" + decision.stopLoss + " TP=" + decision.takeProfit);

                entryOrderId = orderExecutor.placeEntry(
                    OrderExecutor.OrderType.LIMIT,
                    entrySide,
                    triggerPrice * signal.pips,  // Limit price as actual price
                    positionSize
                );

                if (entryOrderId != null) {
                    // Track pending order for SL/TP placement after fill
                    position.pendingSLTP.set(true);
                    position.pendingTriggerPrice.set(triggerPrice);
                    log("📥 Limit order placed: %s (waiting for fill at %d)", entryOrderId, triggerPrice);
                }

            } else {
                // MARKET (default): Use bracket order with native SL/TP
                log("📊 MARKET order with bracket SL/TP");
                log("📊 Bracket order offsets (ticks): SL=%d, TP=%d", slOffsetTicks, tpOffsetTicks);
                log("📊 Entry signal: %d ticks, SL target: %d ticks, TP target: %d ticks", signal.price, decision.stopLoss, decision.takeProfit);
                log("📊 Actual prices: Entry=%.2f, SL=%.2f, TP=%.2f",
                    signal.price * signal.pips, decision.stopLoss * signal.pips, decision.takeProfit * signal.pips);
                log("⚠️ NOTE: Market order may fill at different price - Bookmap applies offsets from FILL price");
                fileLog("📝 NATIVE BRACKET: signal_entry=" + signal.price + "ticks SL_target=" + decision.stopLoss + "ticks TP_target=" + decision.takeProfit + "ticks");
                fileLog("📝 Offsets sent to Bookmap: SL_offset=" + slOffsetTicks + "ticks TP_offset=" + tpOffsetTicks + "ticks");

                entryOrderId = orderExecutor.placeBracketOrder(
                    OrderExecutor.OrderType.MARKET,
                    entrySide,
                    Double.NaN,  // Market order - no price needed
                    positionSize,
                    slOffsetTicks,   // Stop loss offset in ticks
                    tpOffsetTicks    // Take profit offset in ticks
                );

                // For bracket orders, SL/TP are managed by Bookmap
                position.pendingSLTP.set(false);
            }

            if (entryOrderId == null) {
                log("❌ ORDER FAILED!");
                fileLog("❌ ORDER FAILED!");
                return null;
            }

            log("📥 Order ID: %s (execType: %s)", entryOrderId, execType);
            fileLog("✅ ORDER PLACED: " + entryOrderId + " (" + execType + ")");

            position.entryOrderId.set(entryOrderId);
            // SL/TP order IDs are managed by Bookmap (for bracket orders) or pending (for stop/limit)
            position.stopLossOrderId.set(entryOrderId + "-SL");  // Placeholder for tracking
            position.takeProfitOrderId.set(entryOrderId + "-TP");

            // Track position
            activePositions.put(positionId, position);
            log("✅ Position tracked: %s", positionId.substring(0, 8));

            // Notify strategy about pending bracket order (for fill tracking)
            // This allows strategy to update markers with actual fill price later
            if (markerCallback != null) {
                markerCallback.onBracketOrderPlaced(
                    entryOrderId, positionId, decision.isLong,
                    signal.price, slOffsetTicks, tpOffsetTicks,
                    signal.score, decision.reasoning
                );
                fileLog("📍 onBracketOrderPlaced called: orderId=" + entryOrderId + " signalPrice=" + signal.price + " SL_off=" + slOffsetTicks + " TP_off=" + tpOffsetTicks);
            }

            // Place AI entry marker on chart
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
            log("   Execution Type: %s", execType);
            if (!"MARKET".equalsIgnoreCase(execType) && decision.triggerPrice != null) {
                log("   Trigger Price: %d (%.2f)", decision.triggerPrice, decision.triggerPrice * signal.pips);
            }
            log("   %s %d contract(s) @ %d", decision.isLong ? "LONG" : "SHORT", positionSize, signal.price);
            log("   Stop Loss: %d (-$%.0f)", decision.stopLoss, signal.risk.stopLossValue);
            log("   Take Profit: %d (+$%.0f)", decision.takeProfit, signal.risk.takeProfitValue);
            log("   Break-even: %d (%d ticks profit)", breakEvenStop, breakEvenTicks);
            log("   Reasoning: %s", decision.reasoning);
            if (decision.executionReasoning != null) {
                log("   Exec Reasoning: %s", decision.executionReasoning);
            }

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
            // Note: For bracket orders, skip internal break-even since Bookmap manages SL/TP
            if (breakEvenEnabled && position.shouldTriggerBreakEven(currentPrice)) {
                String stopOrderId = position.stopLossOrderId.get();
                boolean isBracketOrder = stopOrderId != null && stopOrderId.endsWith("-SL");
                if (isBracketOrder) {
                    // Skip break-even for bracket orders - Bookmap manages the order
                    // Just mark it as moved so we don't keep checking
                    position.breakEvenMoved.set(true);
                    fileLog("🟡 Break-even skipped for bracket order (Bookmap manages SL/TP)");
                } else {
                    moveStopToBreakEven(position);
                }
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

            // Check if this is a bracket order (placeholder ID ends with -SL)
            // Bracket orders have SL/TP managed by Bookmap - we can't modify them directly
            boolean isBracketOrder = stopOrderId.endsWith("-SL");
            if (isBracketOrder) {
                log("⚠️ Break-even for bracket order: visual update only (Bookmap manages SL/TP)");
                fileLog("🟡 moveStopToBreakEven: BRACKET ORDER detected, skipping order modification");

                // Still update internal tracking and visual line
                int newStopPriceTicks = position.breakEvenStopPrice;
                position.stopLossPrice.set(newStopPriceTicks);
                position.breakEvenMoved.set(true);

                // Place break-even marker on chart (updates visual line)
                if (markerCallback != null) {
                    markerCallback.onBreakEvenMarker(newStopPriceTicks, position.breakEvenTriggerPrice);
                }

                log("🟡 BREAK-EVEN TRIGGERED (visual only):");
                log("   Position: %s", position.id.substring(0, 8));
                log("   Stop moved: %d → %d ticks (Bookmap manages actual order)",
                    position.breakEvenTriggerPrice, newStopPriceTicks);
                return;
            }

            int newStopPriceTicks = position.breakEvenStopPrice;
            double newStopPriceActual = newStopPriceTicks * pips;  // Convert to actual price

            fileLog("🟡 moveStopToBreakEven: orderId=" + stopOrderId + ", newStopPrice=" + newStopPriceActual);

            // Modify stop loss order (for non-bracket orders)
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
            String stopOrderId = position.stopLossOrderId.get();

            // Check if this is a bracket order (placeholder ID ends with -SL)
            boolean isBracketOrder = stopOrderId != null && stopOrderId.endsWith("-SL");
            if (isBracketOrder) {
                // For bracket orders, just update internal tracking (Bookmap manages the order)
                int newStopPriceTicks = position.calculateTrailStop(currentPrice);
                int oldStop = position.stopLossPrice.get();
                position.stopLossPrice.set(newStopPriceTicks);

                double lockedProfit = position.isLong ?
                    (currentPrice - newStopPriceTicks) * tickValue :
                    (newStopPriceTicks - currentPrice) * tickValue;

                log("📍 TRAILING STOP (visual only for bracket order):");
                log("   Position: %s", position.id.substring(0, 8));
                log("   Stop trailed: %d → %d ticks (Bookmap manages actual order)", oldStop, newStopPriceTicks);
                log("   Locked in profit: $%.2f", lockedProfit);
                return;
            }

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
                    (currentPrice - newStopPriceTicks) * tickValue :
                    (newStopPriceTicks - currentPrice) * tickValue;

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
        if (position == null || position.isClosed.get()) {
            log("⚠️ closePosition called but position %s (closed=%b)",
                positionId.substring(0, 8), position != null && position.isClosed.get());
            return;
        }

        try {
            // Mark as closed FIRST to prevent double-processing
            position.close(exitPrice, reason, triggerOrderId);

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

            // Update statistics (calculate PnL first for marker callback)
            double pnl = position.getRealizedPnl();
            dailyPnl += pnl;
            totalTrades.incrementAndGet();
            if (pnl > 0) {
                winningTrades.incrementAndGet();
            }

            // Log trade to CSV and update session state
            if (tradeLogger != null) {
                try {
                    int mfeTicks = (int) (position.maxUnrealizedPnl.get() / tickValue);
                    int maeTicks = (int) (position.maxAdverseExcursion.get() / tickValue);
                    double aiConfidence = position.aiDecision != null ? position.aiDecision.confidence : 0.0;
                    int signalScore = position.originalSignal != null ? position.originalSignal.score : 0;

                    TradeLogger.TradeRecord record = tradeLogger.createRecord(
                        position.id,
                        position.symbol,
                        position.isLong,
                        position.entryPrice,
                        exitPrice,
                        position.quantity,
                        position.stopLossPrice.get(),
                        position.takeProfitPrice.get(),
                        (int) (position.getTimeInPosition() / 1000),
                        signalScore,
                        position.entrySlippage,
                        reason,
                        mfeTicks,
                        maeTicks,
                        aiConfidence
                    );
                    tradeLogger.logTrade(record);
                } catch (Exception e) {
                    fileLog("⚠️ Failed to log trade: " + e.getMessage());
                }
            }

            // Update session state manager
            if (sessionStateManager != null) {
                sessionStateManager.updateAfterTrade(pnl, pnl > 0);
            }

            // Place AI exit marker on chart
            if (markerCallback != null) {
                boolean isWin = exitPrice != ((position.isLong ? position.stopLossPrice.get() : position.stopLossPrice.get()));
                markerCallback.onExitMarker(exitPrice, reason, pnl, isWin);
            }

            // Calculate R:R and tick distances
            int slTicks = Math.abs(position.stopLossPrice.get() - position.entryPrice);
            int tpTicks = Math.abs(position.takeProfitPrice.get() - position.entryPrice);
            double rrRatio = slTicks > 0 ? (double) tpTicks / slTicks : 0;
            double slDollars = slTicks * tickValue * position.quantity;
            double tpDollars = tpTicks * tickValue * position.quantity;

            // Log closure
            String emoji = pnl > 0 ? "💎" : "🛑";
            log("%s POSITION CLOSED:", emoji);
            log("   Position ID: %s", positionId.substring(0, 8));
            log("   Symbol: %s", position.symbol);
            log("   %s %d @ %d → %d",
                position.isLong ? "LONG" : "SHORT",
                position.quantity,
                position.entryPrice,
                exitPrice);
            log("   Reason: %s", reason);
            log("   P&L: $%.2f", pnl);

            // Log signal details
            if (position.originalSignal != null) {
                SignalData sig = position.originalSignal;
                log("📊 SIGNAL DETAILS:");
                log("   Type: %s | Direction: %s", sig.detection != null ? sig.detection.type : "N/A", sig.direction);
                log("   Confluence Score: %d (threshold: %d)", sig.score, sig.threshold);
                if (sig.market != null) {
                    log("   Market: trend=%s, CVD=%d (%s), VWAP=%s",
                        sig.market.trend, sig.market.cvd, sig.market.cvdTrend, sig.market.priceVsVwap);
                }
                if (sig.detection != null) {
                    log("   Detection: orders=%d, size=%d, span=%.1fs",
                        sig.detection.totalOrders, sig.detection.totalSize, sig.detection.timeSpanMs / 1000.0);
                }
            }

            // Log SL/TP and R:R
            log("🎯 RISK MANAGEMENT:");
            log("   Stop Loss: %d ticks ($%.0f risk)", slTicks, slDollars);
            log("   Take Profit: %d ticks ($%.0f target)", tpTicks, tpDollars);
            log("   R:R Ratio: 1:%.1f", rrRatio);
            log("   Entry Slippage: %d ticks", position.entrySlippage);
            log("   Time in trade: %d seconds", position.getTimeInPosition() / 1000);
            log("   Max Favorable: $%.2f", position.maxUnrealizedPnl.get() / 100.0);
            log("   Max Adverse: -$%.2f", Math.abs(position.maxAdverseExcursion.get() / 100.0));

            // Log AI decision
            if (position.aiDecision != null) {
                log("🤖 AI DECISION:");
                log("   Confidence: %.0f%%", position.aiDecision.confidence * 100);
                log("   Reasoning: %s", position.aiDecision.reasoning);
            }

            // Log entry context for historical analysis
            if (position.entryContext != null) {
                log("📋 ENTRY CONTEXT:");
                log("   Thresholds: minScore=%d, conf=%d, mult=%.1f",
                    position.entryContext.minConfluenceScore,
                    position.entryContext.confluenceThreshold,
                    position.entryContext.thresholdMultiplier);
                log("   Detection: iceberg=%d, spoof=%d, absorb=%d",
                    position.entryContext.icebergMinOrders,
                    position.entryContext.spoofMinSize,
                    position.entryContext.absorptionMinSize);
                log("   Weights: iceMax=%d, cvdAlign=%d, cvdDiv=%d, emaAlign=%d, vwapAlign=%d, vwapDiv=%d",
                    position.entryContext.icebergMax,
                    position.entryContext.cvdAlignMax,
                    position.entryContext.cvdDivergePenalty,
                    position.entryContext.emaAlignMax,
                    position.entryContext.vwapAlign,
                    position.entryContext.vwapDiverge);
            }

            // Log daily summary
            log("📊 Daily Summary:");
            log("   Daily P&L: $%.2f", dailyPnl);
            log("   Trades Today: %d", totalTrades.get());
            log("   Win Rate: %.1f%%", getWinRate());

        } catch (Exception e) {
            log("❌ Failed to close position: %s", e.getMessage());
        } finally {
            // ALWAYS remove from active positions, even if there was an error
            activePositions.remove(positionId);
            log("🧹 Position removed from active map (size now: %d)", activePositions.size());
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
     * Update position with actual fill price and SL/TP
     * Called by strategy when order execution completes
     *
     * IMPORTANT: This fixes the data integrity issue where signal-based prices
     * were being used instead of actual fill prices. This affects:
     * - Trade logging accuracy
     * - Performance statistics
     * - P&L calculations
     * - Memory/learning data
     *
     * @param positionId Position to update
     * @param actualFillPrice Actual fill price in ticks
     * @param actualSlPrice Actual stop loss price in ticks (fill + offset)
     * @param actualTpPrice Actual take profit price in ticks (fill + offset)
     * @param slippageTicks Difference between signal and fill price
     */
    public void updatePositionOnFill(String positionId, int actualFillPrice, int actualSlPrice, int actualTpPrice, int slippageTicks) {
        ActivePosition position = activePositions.get(positionId);
        if (position == null) {
            log("⚠️ Cannot update position - not found: %s", positionId != null ? positionId.substring(0, 8) : "null");
            fileLog("⚠️ updatePositionOnFill: position not found: " + positionId);
            return;
        }

        int oldEntry = position.entryPrice;
        int oldSl = position.stopLossPrice.get();
        int oldTp = position.takeProfitPrice.get();

        // Update position with actual values
        position.entryPrice = actualFillPrice;
        position.stopLossPrice.set(actualSlPrice);
        position.takeProfitPrice.set(actualTpPrice);
        position.entrySlippage = slippageTicks;

        log("📍 POSITION UPDATED WITH ACTUAL FILL PRICES:");
        log("   Entry: %d → %d ticks (slippage: %d)", oldEntry, actualFillPrice, slippageTicks);
        log("   SL: %d → %d ticks (diff: %d)", oldSl, actualSlPrice, Math.abs(actualSlPrice - oldSl));
        log("   TP: %d → %d ticks (diff: %d)", oldTp, actualTpPrice, Math.abs(actualTpPrice - oldTp));
        log("   Actual prices: Entry=$%.2f, SL=$%.2f, TP=$%.2f",
            actualFillPrice * pips, actualSlPrice * pips, actualTpPrice * pips);

        fileLog(String.format("📍 POSITION UPDATED: entry %d→%d SL %d→%d TP %d→%d (slippage=%d)",
            oldEntry, actualFillPrice, oldSl, actualSlPrice, oldTp, actualTpPrice, slippageTicks));
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
     * Get all active positions (for bracket order fill detection)
     */
    public Map<String, ActivePosition> getActivePositions() {
        return activePositions;
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

        /**
         * Called when signal is rejected due to slippage
         * @param signalPrice original signal price
         * @param currentPrice current market price
         * @param slippageTicks how many ticks price moved
         */
        void onSlippageRejectedMarker(int signalPrice, int currentPrice, int slippageTicks);

        /**
         * Called when bracket order is placed, before fill
         * Strategy should track this to update markers with actual fill price later
         * @param orderId Entry order ID from Bookmap
         * @param positionId Position ID for tracking
         * @param isLong Direction
         * @param signalPrice Signal price in ticks (what we expected)
         * @param slOffset Stop loss offset in ticks
         * @param tpOffset Take profit offset in ticks
         * @param score Signal score
         * @param reasoning AI reasoning
         */
        void onBracketOrderPlaced(String orderId, String positionId, boolean isLong,
                                  int signalPrice, int slOffset, int tpOffset,
                                  int score, String reasoning);
    }
}
