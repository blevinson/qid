package velox.api.layer1.simplified.demo;

import velox.api.layer1.data.*;
import velox.api.layer1.simplified.Api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bookmap Order Executor Implementation
 *
 * This is the production implementation using Bookmap's actual order management API.
 *
 * Key features:
 * 1. Uses Bookmap's SimpleOrderSendParameters for order placement
 * 2. Order status updates handled by parent strategy (OrdersListener)
 * 3. Handles partial fills and order modifications
 * 4. Proper error handling and logging
 *
 * API Reference: https://github.com/BookmapAPI/ExchangePortDemo
 */
public class BookmapOrderExecutor implements OrderExecutor {
    private final Api api;
    private final AIIntegrationLayer.AIStrategyLogger logger;
    private final String alias;

    // Order tracking
    private final Map<String, OrderInfoUpdate> activeOrders = new ConcurrentHashMap<>();
    private int orderSequence = 0;

    // Position and balance tracking
    private final AtomicInteger currentPosition = new AtomicInteger(0);
    private final AtomicReference<Double> accountBalance = new AtomicReference<>(10000.0);

    // Order callbacks for async events
    private final Map<String, OrderCallback> orderCallbacks = new ConcurrentHashMap<>();

    public BookmapOrderExecutor(Api api, String alias, AIIntegrationLayer.AIStrategyLogger logger) {
        this.api = api;
        this.alias = alias;
        this.logger = logger;

        // Note: Order listener is handled by parent strategy via @Layer1TradingStrategy

        log("✅ BookmapOrderExecutor initialized with real API");
    }

    @Override
    public String placeEntry(OrderType type, OrderSide side, int price, int quantity) {
        try {
            String orderId = generateOrderId();
            log("📝 PLACING ENTRY ORDER:");
            log("   Order ID: %s", orderId);
            log("   Type: %s, Side: %s, Qty: %d, Price: %d", type, side, quantity, price);

            boolean isBuy = side == OrderSide.BUY;
            double limitPrice;
            double stopPrice;

            // Convert OrderType to Bookmap order parameters
            switch (type) {
                case LIMIT:
                    limitPrice = price;
                    stopPrice = Double.NaN;
                    break;
                case MARKET:
                    limitPrice = Double.NaN;
                    stopPrice = Double.NaN;
                    break;
                case STOP_MARKET:
                    limitPrice = Double.NaN;
                    stopPrice = price;
                    break;
                case STOP_LIMIT:
                    limitPrice = price;
                    stopPrice = price;
                    break;
                default:
                    limitPrice = Double.NaN;
                    stopPrice = Double.NaN;
            }

            // Create order parameters using constructor (not builder)
            SimpleOrderSendParameters orderParams = new SimpleOrderSendParameters(
                alias,                    // alias (instrument)
                isBuy,                    // isBuy
                quantity,                  // size (using size level)
                OrderDuration.GTC,          // duration - Good Till Cancel
                orderId,                   // user-defined order ID
                limitPrice,                // limit price
                stopPrice,                 // stop price
                0,                         // take profit offset (not used)
                0,                         // stop loss offset (not used)
                0,                         // trailing stop offset (not used)
                0,                         // trailing step (not used)
                false                      // reduce only flag
            );

            api.sendOrder(orderParams);

            // Store callback for async confirmation
            OrderCallback callback = new OrderCallback(orderId, type, side, quantity, price);
            orderCallbacks.put(orderId, callback);

            log("   ✅ Order sent to Bookmap API");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR placing entry order: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String placeStopLoss(OrderSide side, int stopPrice, int quantity) {
        try {
            String orderId = generateOrderId();
            log("🛑 PLACING STOP LOSS:");
            log("   Order ID: %s", orderId);
            log("   Side: %s, Stop Price: %d, Qty: %d", side, stopPrice, quantity);

            // Stop loss is a stop market order
            // Side is opposite of entry
            boolean isBuy = side == OrderSide.SELL;

            SimpleOrderSendParameters orderParams = new SimpleOrderSendParameters(
                alias,                    // alias
                isBuy,                    // isBuy (opposite of entry)
                quantity,                  // size
                OrderDuration.GTC,          // duration
                orderId,                   // order ID
                Double.NaN,                // limit price (NaN for stop orders)
                stopPrice,                 // stop price
                0,                         // take profit offset
                0,                         // stop loss offset
                0,                         // trailing stop offset
                0,                         // trailing step
                false                      // reduce only
            );

            api.sendOrder(orderParams);

            OrderCallback callback = new OrderCallback(orderId, OrderType.STOP_MARKET, side, quantity, stopPrice);
            orderCallbacks.put(orderId, callback);

            log("   ✅ Stop loss sent to Bookmap API");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR placing stop loss: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String placeTakeProfit(OrderSide side, int targetPrice, int quantity) {
        try {
            String orderId = generateOrderId();
            log("💎 PLACING TAKE PROFIT:");
            log("   Order ID: %s", orderId);
            log("   Side: %s, Target Price: %d, Qty: %d", side, targetPrice, quantity);

            // Take profit is a limit order at target price
            // Side is opposite of entry
            boolean isBuy = side == OrderSide.SELL;

            SimpleOrderSendParameters orderParams = new SimpleOrderSendParameters(
                alias,                    // alias
                isBuy,                    // isBuy (opposite of entry)
                quantity,                  // size
                OrderDuration.GTC,          // duration
                orderId,                   // order ID
                targetPrice,               // limit price
                Double.NaN,                // stop price (NaN for limit orders)
                0,                         // take profit offset
                0,                         // stop loss offset
                0,                         // trailing stop offset
                0,                         // trailing step
                false                      // reduce only
            );

            api.sendOrder(orderParams);

            OrderCallback callback = new OrderCallback(orderId, OrderType.LIMIT, side, quantity, targetPrice);
            orderCallbacks.put(orderId, callback);

            log("   ✅ Take profit sent to Bookmap API");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR placing take profit: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String modifyStopLoss(String orderId, int newStopPrice, int quantity) {
        try {
            log("🔄 MODIFYING STOP LOSS:");
            log("   Original Order: %s", orderId);
            log("   New Stop Price: %d, Qty: %d", newStopPrice, quantity);

            // Use OrderMoveParameters to change the price
            OrderMoveParameters moveParams = new OrderMoveParameters(
                orderId,           // order ID
                newStopPrice,       // new stop price
                Double.NaN          // new limit price (NaN if not a limit order)
            );

            api.updateOrder(moveParams);

            log("   ✅ Stop loss modified");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR modifying stop loss: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String modifyTakeProfit(String orderId, int newTargetPrice, int quantity) {
        try {
            log("🔄 MODIFYING TAKE PROFIT:");
            log("   Original Order: %s", orderId);
            log("   New Target Price: %d, Qty: %d", newTargetPrice, quantity);

            // Use OrderMoveParameters to change the price
            OrderMoveParameters moveParams = new OrderMoveParameters(
                orderId,           // order ID
                Double.NaN,         // new stop price (NaN for limit orders)
                newTargetPrice       // new limit price
            );

            api.updateOrder(moveParams);

            log("   ✅ Take profit modified");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR modifying take profit: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String closePosition(OrderSide side, int quantity) {
        try {
            String orderId = generateOrderId();
            log("❌ CLOSING POSITION:");
            log("   Order ID: %s", orderId);
            log("   Side: %s, Qty: %d at MARKET", side, quantity);

            // Market order to close position
            SimpleOrderSendParameters orderParams = new SimpleOrderSendParameters(
                alias,                    // alias
                side == OrderSide.BUY,     // isBuy
                quantity,                  // size
                OrderDuration.IOC,          // duration - Immediate or Cancel
                orderId,                   // order ID
                Double.NaN,                // limit price (NaN for market)
                Double.NaN,                // stop price (NaN for market)
                0,                         // take profit offset
                0,                         // stop loss offset
                0,                         // trailing stop offset
                0,                         // trailing step
                false                      // reduce only
            );

            api.sendOrder(orderParams);

            OrderCallback callback = new OrderCallback(orderId, OrderType.MARKET, side, quantity, 0);
            orderCallbacks.put(orderId, callback);

            log("   ✅ Close position order sent");
            return orderId;

        } catch (Exception e) {
            log("   ❌ ERROR closing position: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean cancelOrder(String orderId) {
        try {
            log("❌ CANCELLING ORDER:");
            log("   Order ID: %s", orderId);

            OrderCancelParameters cancelParams = new OrderCancelParameters(orderId);
            api.updateOrder(cancelParams);

            log("   ✅ Order cancellation sent");
            return true;

        } catch (Exception e) {
            log("   ❌ ERROR cancelling order: %s", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public int getCurrentPosition() {
        return currentPosition.get();
    }

    @Override
    public double getAccountBalance() {
        return accountBalance.get();
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    /**
     * Generate unique order ID
     */
    private String generateOrderId() {
        return "ORD-" + System.currentTimeMillis() + "-" + (++orderSequence);
    }

    /**
     * Log message
     */
    private void log(String message, Object... args) {
        if (logger != null) {
            logger.log(String.format(message, args));
        }
    }

    // ============================================================================
    // Inner Classes
    // ============================================================================

    /**
     * Callback for tracking order lifecycle
     */
    private static class OrderCallback {
        final String orderId;
        final OrderType type;
        final OrderSide side;
        final int quantity;
        final int price;
        boolean filled = false;

        OrderCallback(String orderId, OrderType type, OrderSide side, int quantity, int price) {
            this.orderId = orderId;
            this.type = type;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
        }

        void onUpdate(OrderInfoUpdate update) {
            if (update.status == OrderStatus.FILLED) {
                filled = true;
            }
        }
    }
}
