package velox.api.layer1.simplified.demo;

/**
 * Order Execution Interface
 * Provides methods for AI to place and manage orders
 */
public interface OrderExecutor {
    /**
     * Order types
     */
    enum OrderType {
        MARKET,
        LIMIT,
        STOP_MARKET,
        STOP_LIMIT
    }

    enum OrderSide {
        BUY,
        SELL
    }

    /**
     * Place an entry order
     * @param type Order type (MARKET, LIMIT, STOP_MARKET, STOP_LIMIT)
     * @param side Order side (BUY, SELL)
     * @param price Price for limit/stop orders (0 for market)
     * @param quantity Number of contracts
     * @return Order ID
     */
    String placeEntry(OrderType type, OrderSide side, int price, int quantity);

    /**
     * Place a stop loss order
     * @param side Order side (opposite of entry)
     * @param stopPrice Stop loss price
     * @param quantity Quantity to close
     * @return Order ID
     */
    String placeStopLoss(OrderSide side, int stopPrice, int quantity);

    /**
     * Place a take profit order
     * @param side Order side (opposite of entry)
     * @param targetPrice Take profit price
     * @param quantity Quantity to close
     * @return Order ID
     */
    String placeTakeProfit(OrderSide side, int targetPrice, int quantity);

    /**
     * Modify an existing stop loss order
     * @param orderId Original stop loss order ID
     * @param newStopPrice New stop loss price
     * @param quantity Quantity (can be partial)
     * @return New order ID (if order had to be replaced)
     */
    String modifyStopLoss(String orderId, int newStopPrice, int quantity);

    /**
     * Modify an existing take profit order
     * @param orderId Original take profit order ID
     * @param newTargetPrice New take profit price
     * @param quantity Quantity (can be partial)
     * @return New order ID (if order had to be replaced)
     */
    String modifyTakeProfit(String orderId, int newTargetPrice, int quantity);

    /**
     * Close position at market
     * @param side Order side (opposite of entry)
     * @param quantity Quantity to close
     * @return Order ID
     */
    String closePosition(OrderSide side, int quantity);

    /**
     * Cancel an order
     * @param orderId Order ID to cancel
     * @return true if cancelled successfully
     */
    boolean cancelOrder(String orderId);

    /**
     * Get current position size
     * @return Positive for long, negative for short, 0 for flat
     */
    int getCurrentPosition();

    /**
     * Get account balance
     * @return Current account balance
     */
    double getAccountBalance();
}
