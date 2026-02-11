# Bookmap Trading Skills

This document describes the available Bookmap trading-related skills and capabilities in this codebase.

## Available Skills

### 1. Order Flow Philosophy (order-flow-philosophy)
**Purpose:** Foundational philosophy of order flow trading. Understanding how market data reveals past, present, and future price movements with high probability.

**Key Concepts:**
- Order flow analysis and interpretation
- Market microstructure
- Price movement prediction
- High-probability trading setups
- Confluence signals

**Related Files:**
- `/docs/ORDER_FLOW_TRADING_PLAN.md` - Comprehensive order flow trading strategy documentation
- `/docs/ORDER_FLOW_STRATEGY_README.md` - Order flow strategy overview

---

### 2. Bookmap Indicators Development (bookmap-indicators)
**Purpose:** Expert knowledge for developing Bookmap L1 API indicators and strategies.

**Capabilities:**
- Price plotting and graph types
- Listener interfaces (data, order, position, balance)
- Custom icons and markers
- Performance tracking
- Debugging techniques
- Common pitfalls and solutions

**Key Classes:**
- `OrderFlowStrategyEnhanced` - Main order flow strategy implementation
- `AbsorptionDetector` - Detects absorption patterns
- `LiquidityTracker` - Tracks liquidity levels
- `ConfluenceSignal` - Signal confluence scoring

**API Version:** Bookmap API 7.6.0.20

---

### 3. Real Order Execution (NEW!)
**Purpose:** Production-ready order execution using Bookmap's actual trading API.

**Implementation:** `BookmapOrderExecutor.java`

**Key Features:**
- ✅ Real order placement (MARKET, LIMIT, STOP_MARKET, STOP_LIMIT)
- ✅ Stop loss and take profit management
- ✅ Order modification and cancellation
- ✅ Real-time order status tracking via `OrdersListener`
- ✅ Position tracking via `ExecutionInfo`
- ✅ Balance tracking
- ✅ Error handling and logging

**API Usage:**
```java
// Place entry order
orderExecutor.placeEntry(
    OrderType.LIMIT,
    OrderSide.BUY,
    price,
    quantity
);

// Place stop loss
orderExecutor.placeStopLoss(
    OrderSide.SELL,
    stopPrice,
    quantity
);

// Modify stop loss
orderExecutor.modifyStopLoss(
    orderId,
    newStopPrice,
    quantity
);

// Cancel order
orderExecutor.cancelOrder(orderId);

// Get current position
int position = orderExecutor.getCurrentPosition();
```

**Important Notes:**
- ⚠️ **Always test in paper trading mode first!**
- ⚠️ Uses Bookmap's `SimpleOrderSendParameters` with constructor (not builder)
- ⚠️ Prices use `Double.NaN` for market orders (not 0)
- ⚠️ ExecutionInfo doesn't have `isBuy` field - must look up order
- ⚠️ Field names: `filled`, `unfilled` (not `size`, `unfilledSize`)

**API Reference:**
- [Bookmap API Documentation](https://bookmap.com/knowledgebase/docs/API)
- [ExchangePort Demo](https://github.com/BookmapAPI/ExchangePortDemo)
- [Bookmap GitHub](https://github.com/BookmapAPI/DemoStrategies)

**Related Files:**
- `/src/main/java/velox/api/layer1/simplified/demo/BookmapOrderExecutor.java` - Main implementation
- `/src/main/java/velox/api/layer1/simplified/demo/OrderExecutor.java` - Interface definition
- `/src/main/java/velox/api/layer1/simplified/demo/SimpleOrderExecutor.java` - Simulation implementation (legacy, for testing only)

---

### 4. AI Integration (ai-integration)
**Purpose:** AI-driven trading assistant with chat interface and adaptive thresholds.

**Components:**
- `AIIntegrationLayer` - Claude API integration for signal analysis
- `AIOrderManager` - AI-powered order execution with risk management
- `AIThresholdService` - AI chat interface for threshold optimization
- `ConfluenceSignal` - Signal confluence scoring system

**AI Features:**
- Real-time signal analysis via Claude API
- Adaptive threshold calculation based on market conditions
- Natural language interface for strategy adjustments
- Risk management (max positions, daily loss limits)
- Break-even automation

**Related Files:**
- `/src/main/java/velox/api/layer1/simplified/demo/AIIntegrationLayer.java`
- `/src/main/java/velox/api/layer1/simplified/demo/AIOrderManager.java`
- `/src/main/java/velox/api/layer1/simplified/demo/AIThresholdService.java`

---

## Integration Example

```java
// In OrderFlowStrategyEnhanced.java

// 1. Initialize order executor with real Bookmap API
orderExecutor = new BookmapOrderExecutor(
    api,                    // Bookmap Api instance
    alias,                  // Instrument alias
    logger                   // AIStrategyLogger for logging
);

// 2. Create AI order manager
aiOrderManager = new AIOrderManager(
    orderExecutor,
    logger
);

// 3. Configure risk management
aiOrderManager.maxPositions = 3;
aiOrderManager.dailyLossLimit = 500;
aiOrderManager.breakEvenEnabled = true;
aiOrderManager.breakEvenTicks = 3;

// 4. AI generates signal
ConfluenceSignal signal = detectConfluence();

// 5. AI Order Manager executes via real Bookmap API
if (aiOrderManager.shouldEnterPosition(signal)) {
    String orderId = orderExecutor.placeEntry(
        OrderType.LIMIT,
        signal.side,
        signal.price,
        signal.quantity
    );

    // Place stop loss and take profit
    orderExecutor.placeStopLoss(signal.side, signal.stopLoss, signal.quantity);
    orderExecutor.placeTakeProfit(signal.side, signal.target, signal.quantity);
}
```

---

## Testing Checklist

Before using real order execution with live trading:

- [ ] Paper trading mode enabled in Bookmap
- [ ] Connected to supported broker (Tradovate, etc.)
- [ ] Tested with minimum position size (1 contract)
- [ ] Verified stop loss functionality
- [ ] Verified take profit functionality
- [ ] Verified order cancellation works
- [ ] Verified position tracking accuracy
- [ ] Checked daily loss limits are enforced
- [ ] Reviewed logs for errors
- [ ] Confirmed AI integration works with real orders

⚠️ **IMPORTANT:** Never deploy to live trading without thorough paper trading testing!

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│           OrderFlowStrategyEnhanced                    │
│  (Main Strategy - Coordinates All Components)          │
└──────────────────┬──────────────────────────────────────┘
                   │
       ┌───────────┴───────────┐
       │                           │
┌──────▼──────────┐    ┌─────▼──────────────┐
│  BookmapOrder   │    │  AIIntegrationLayer  │
│  Executor        │    │  (Claude API)       │
└────────┬─────────┘    └─────────┬──────────┘
         │                         │
         │                   ┌──────▼─────────────┐
         │                   │  AIOrderManager    │
         │                   │  - Risk Mgmt      │
         │                   │  - Position Mgmt   │
         │                   └────────────────────┘
         │
    ┌────▼───────────────────────┐
    │  Bookmap API               │
    │  - sendOrder()             │
    │  - updateOrder()            │
    │  - OrdersListener callbacks   │
    └────────────────────────────┘
```

---

## Version History

- **v1.0** (2025-02-11) - Initial BookmapOrderExecutor implementation
  - Real order execution via Bookmap Layer1 API
  - Order management (place, modify, cancel)
  - Order status tracking
  - Position and balance tracking

---

## Support & Documentation

- **Order Flow Trading Plan:** `/docs/ORDER_FLOW_TRADING_PLAN.md`
- **Order Flow README:** `/docs/ORDER_FLOW_STRATEGY_README.md`
- **Bookmap API Docs:** https://bookmap.com/knowledgebase/docs/API
- **Bookmap GitHub:** https://github.com/BookmapAPI/DemoStrategies
- **Issue Tracker:** Report bugs and feature requests in project issues
