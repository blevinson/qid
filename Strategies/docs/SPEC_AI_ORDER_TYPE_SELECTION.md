# Specification: AI-Driven Order Type Selection

**Version:** 1.0
**Date:** 2025-02-15
**Status:** Draft
**Author:** Claude + Brant

---

## 1. Overview

### 1.1 Problem Statement

Currently, the Qid trading system uses **market orders exclusively** for all entries. This creates several issues:

1. **Slippage Risk**: In fast-moving markets, fill prices can be significantly worse than signal prices
2. **No Confirmation**: Entries occur immediately without waiting for price confirmation
3. **Missed Opportunities**: Cannot wait for pullbacks (better price) or breakouts (confirmation)
4. **One-Size-Fits-All**: All signals treated the same regardless of market context

### 1.2 Proposed Solution

Enable the **AI Investment Strategist** to choose the optimal order type for each signal based on market context:

| Order Type | Use Case | Benefit |
|------------|----------|---------|
| **MARKET** | Strong momentum, time-sensitive | Immediate execution |
| **STOP_MARKET** | Breakout setups, level confirmation | Only enters if price confirms direction |
| **LIMIT** | Reversal setups, pullback entries | Better entry price |

### 1.3 Goals

- Improve entry quality through context-aware order type selection
- Reduce slippage by avoiding market orders when inappropriate
- Filter false signals by requiring price confirmation
- Maintain user control with override options

---

## 2. Technical Design

### 2.1 Current Architecture

```
Signal Detected → AI Evaluates → Returns TAKE/SKIP → TradePlan (orderType: "BUY"/"SELL")
                                    ↓
                            Market Order Executed
```

### 2.2 Proposed Architecture

```
Signal Detected → AI Evaluates → Returns TAKE/SKIP + OrderType + StopPrice
                                    ↓
                            OrderExecutor uses planned order type
                            (MARKET, STOP_MARKET, or LIMIT)
```

### 2.3 Data Model Changes

#### 2.3.1 TradePlan Enhancement

**File:** `AIInvestmentStrategist.java` (TradePlan class)

```java
public static class TradePlan {
    // Existing fields
    public String orderType;        // "BUY", "SELL" (direction only)
    public int entryPrice;
    public int stopLossPrice;
    public int takeProfitPrice;
    public int contracts;
    public String notes;

    // NEW FIELDS
    public OrderExecutionType executionType;  // MARKET, STOP_MARKET, LIMIT
    public Integer triggerPrice;              // Price for STOP_MARKET or LIMIT orders
    public String executionReasoning;         // AI's reasoning for order type choice
}

public enum OrderExecutionType {
    MARKET,       // Execute immediately at market
    STOP_MARKET,  // Wait for price to reach trigger, then execute as market
    LIMIT         // Place limit order at trigger price
}
```

#### 2.3.2 AI Response Schema Update

**Current JSON response:**
```json
{
  "action": "TAKE",
  "confidence": 0.85,
  "reasoning": "Strong bullish setup...",
  "plan": {
    "orderType": "BUY",
    "entryPrice": 5000,
    "stopLossPrice": 4970,
    "takeProfitPrice": 5070
  }
}
```

**New JSON response:**
```json
{
  "action": "TAKE",
  "confidence": 0.85,
  "reasoning": "Strong bullish setup...",
  "plan": {
    "orderType": "BUY",
    "executionType": "STOP_MARKET",
    "entryPrice": 5000,
    "triggerPrice": 5002,
    "stopLossPrice": 4970,
    "takeProfitPrice": 5070,
    "executionReasoning": "Wait for breakout above 5002 resistance before entry"
  }
}
```

### 2.4 AI Prompt Enhancement

#### 2.4.1 System Prompt Addition

Add to the AI system prompt in `AIInvestmentStrategist.java`:

```
ORDER TYPE SELECTION:

Choose the execution type based on market context:

1. MARKET - Use when:
   - Strong momentum with trend confirmation
   - Time-sensitive opportunity (absorption completing)
   - Price already at optimal entry level
   - High confidence signal that shouldn't be missed

2. STOP_MARKET - Use when:
   - Breakout setup near resistance/support
   - Want confirmation of direction before entry
   - Signal detected but price hasn't confirmed yet
   - DOM shows significant level nearby

3. LIMIT - Use when:
   - Reversal signal, expecting pullback
   - Price extended and likely to retrace
   - Want better entry price than current
   - Can afford to wait for fill

For STOP_MARKET and LIMIT orders, provide:
- triggerPrice: The price level to place the order
- executionReasoning: Why this order type was chosen

Example response:
{
  "action": "TAKE",
  "confidence": 0.8,
  "reasoning": "Iceberg buying detected at support",
  "plan": {
    "orderType": "BUY",
    "executionType": "STOP_MARKET",
    "entryPrice": 5000,
    "triggerPrice": 5002,
    "stopLossPrice": 4970,
    "takeProfitPrice": 5070,
    "executionReasoning": "Wait for breakout above 5002 resistance"
  }
}
```

#### 2.4.2 Context Provided to AI

The AI already receives:
- Signal price and direction
- DOM support/resistance levels
- Volume profile (POC, Value Area)
- VWAP position
- EMA alignment
- CVD direction
- Session phase

This context is sufficient for intelligent order type selection.

### 2.5 Execution Flow Changes

#### 2.5.1 AIOrderManager Enhancement

**File:** `AIOrderManager.java`

```java
private void executeTradePlan(SignalData signal, AIDecision decision) {
    TradePlan plan = decision.plan;
    OrderExecutor.OrderSide side = plan.orderType.startsWith("BUY")
        ? OrderExecutor.OrderSide.BUY
        : OrderExecutor.OrderSide.SELL;

    // Determine execution type
    OrderExecutionType execType = plan.executionType != null
        ? plan.executionType
        : OrderExecutionType.MARKET;  // Default to market

    // Calculate position size
    int positionSize = calculatePositionSize(signal, plan);

    switch (execType) {
        case MARKET:
            // Current behavior - immediate market order with bracket SL/TP
            orderExecutor.placeBracketOrder(
                OrderExecutor.OrderType.MARKET,
                side,
                Double.NaN,  // Market order - no price
                positionSize,
                slOffsetTicks,
                tpOffsetTicks
            );
            break;

        case STOP_MARKET:
            // Place stop market order at trigger price
            // When triggered, becomes market order
            double triggerPrice = plan.triggerPrice * signal.pips;
            orderExecutor.placeEntry(
                OrderExecutor.OrderType.STOP_MARKET,
                side,
                triggerPrice,
                positionSize
            );
            // Note: SL/TP will need to be placed after fill
            pendingStopOrders.put(entryOrderId, new PendingSLTP(plan, signal));
            break;

        case LIMIT:
            // Place limit order at trigger price
            double limitPrice = plan.triggerPrice * signal.pips;
            orderExecutor.placeEntry(
                OrderExecutor.OrderType.LIMIT,
                side,
                limitPrice,
                positionSize
            );
            // Note: SL/TP will need to be placed after fill
            pendingLimitOrders.put(entryOrderId, new PendingSLTP(plan, signal));
            break;
    }

    logTradeExecution(signal, decision, execType);
}
```

#### 2.5.2 Pending Order SL/TP Management

When using STOP_MARKET or LIMIT orders, SL/TP cannot be set until the order fills:

```java
// Track pending orders that need SL/TP after fill
private Map<String, PendingSLTP> pendingOrders = new ConcurrentHashMap<>();

private static class PendingSLTP {
    TradePlan plan;
    SignalData signal;
    long createdAt;
}

// Called when order fills (from OrdersListener)
private void onOrderFilled(String orderId, double fillPrice, int quantity) {
    PendingSLTP pending = pendingOrders.remove(orderId);
    if (pending != null) {
        // Now place SL/TP orders
        placeSLTPOrders(pending.plan, pending.signal, fillPrice, quantity);
    }
}
```

### 2.6 Settings Panel Changes

#### 2.6.1 New UI Controls

**File:** `OrderFlowStrategyEnhanced.java`

Add to settings panel:

```
┌─────────────────────────────────────────────────────────┐
│ Order Execution                                          │
├─────────────────────────────────────────────────────────┤
│ Execution Mode:  [○ AI Choice  ○ Market Only  ○ Limit Only] │
│                                                          │
│ [✓] Allow AI to choose order type                       │
│     When enabled, AI selects MARKET, STOP_MARKET, or    │
│     LIMIT based on market context                        │
│                                                          │
│ Default Order Type: [MARKET ▼]                          │
│     Used when AI doesn't specify or setting is disabled │
│                                                          │
│ [✓] Log order type reasoning                            │
│     Show AI's reasoning for order type selection        │
└─────────────────────────────────────────────────────────┘
```

#### 2.6.2 New Parameters

```java
@Parameter(name = "Allow AI Order Type Selection")
private Boolean allowAIOrderTypeSelection = true;

@Parameter(name = "Default Order Type")
private String defaultOrderType = "MARKET";  // MARKET, STOP_MARKET, LIMIT

@Parameter(name = "Log Order Type Reasoning")
private Boolean logOrderTypeReasoning = true;
```

### 2.7 Logging and Transparency

#### 2.7.1 Trade Log Enhancement

Add to trade logging:

```
========== TRADE EXECUTION ==========
Signal: LONG ICEBERG @ 5000.00
Score: 75 | AI Confidence: 85%

Order Type: STOP_MARKET
Trigger Price: 5002.00
Reasoning: Wait for breakout above 5002 resistance before entry

Entry Order Placed: BUY STOP @ 5002.00
SL: 4970.00 (30 ticks) | TP: 5070.00 (70 ticks)
=====================================
```

#### 2.7.2 Performance Tracking

Track order type performance for analysis:

```java
public static class OrderTypeStats {
    public int marketCount, marketWins;
    public int stopMarketCount, stopMarketWins;
    public int limitCount, limitWins;

    public double getWinRate(String orderType) { ... }
}
```

---

## 3. Implementation Plan

### Phase 1: Core Infrastructure (Day 1)

1. **Add OrderExecutionType enum** to `AIInvestmentStrategist.java`
2. **Enhance TradePlan class** with new fields
3. **Update AI response parsing** in `parseDecision()`
4. **Add new parameters** to `OrderFlowStrategyEnhanced.java`

### Phase 2: AI Integration (Day 1)

1. **Update system prompt** with order type selection guidance
2. **Update user prompt** to request order type reasoning
3. **Add validation** for trigger price vs entry price
4. **Test AI responses** with various market contexts

### Phase 3: Execution Logic (Day 2)

1. **Update AIOrderManager** to handle different order types
2. **Add pending order tracking** for STOP_MARKET/LIMIT
3. **Implement SL/TP placement on fill** for pending orders
4. **Add execution reasoning to logs**

### Phase 4: UI and Settings (Day 2)

1. **Add settings panel controls** for order type options
2. **Add order type column** to performance dashboard
3. **Update trade log format** with execution details
4. **Add order type statistics** tracking

### Phase 5: Testing (Day 3)

1. **Unit tests** for order type selection logic
2. **Simulation mode testing** with various scenarios
3. **Paper trading validation** before live use
4. **Performance comparison** (market vs AI-selected)

---

## 4. Risk Considerations

### 4.1 Potential Risks

| Risk | Mitigation |
|------|------------|
| Stop orders not triggered | Log missed opportunities, fallback to market option |
| Limit orders not filled | Timeout mechanism, cancel and retry at market |
| AI chooses wrong type | User override setting, performance tracking |
| Increased complexity | Clear logging, simple UI controls |

### 4.2 Safety Mechanisms

1. **Default to MARKET**: If AI doesn't specify or setting is disabled
2. **Trigger price validation**: Must be within reasonable range of signal price
3. **Order timeout**: Cancel unfilled orders after configurable period
4. **Fallback logic**: If order fails, log error and continue

### 4.3 Rollback Plan

The feature can be disabled by:
1. Setting "Allow AI Order Type Selection" = false
2. Setting "Default Order Type" = MARKET

This reverts to current behavior immediately.

---

## 5. Success Metrics

### 5.1 Key Performance Indicators

1. **Fill Rate Improvement**: Percentage of better-than-market fills
2. **False Signal Filter**: Number of stop orders that never triggered (avoided losses)
3. **Slippage Reduction**: Average improvement in entry price vs market
4. **Win Rate by Order Type**: Compare MARKET vs STOP_MARKET vs LIMIT

### 5.2 Monitoring

- Track order type distribution over time
- Monitor AI reasoning quality
- Compare performance before/after feature

---

## 6. Future Enhancements

### 6.1 Potential Future Features

1. **Trailing stop entries**: Trigger price follows market until hit
2. **OCO orders**: One-cancels-other for multiple entry levels
3. **Iceberg orders**: Hide full position size
4. **Time-based orders**: Only valid during certain session phases

### 6.2 AI Improvements

1. **Learn from outcomes**: AI adjusts order type choices based on results
2. **Market regime detection**: Different strategies for different market conditions
3. **Multi-level entries**: Scale into positions at multiple price levels

---

## 7. Acceptance Criteria

- [ ] AI returns order type in TradePlan response
- [ ] STOP_MARKET orders execute correctly on price trigger
- [ ] LIMIT orders execute correctly at limit price
- [ ] SL/TP placed correctly after pending order fills
- [ ] Settings panel allows user control
- [ ] Order type reasoning logged and visible
- [ ] Performance statistics tracked by order type
- [ ] Fallback to MARKET works when feature disabled
- [ ] No regression in existing market order functionality

---

## 8. References

- `BookmapOrderExecutor.java` - Order execution implementation
- `AIInvestmentStrategist.java` - AI decision making
- `AIOrderManager.java` - Order management logic
- `OrderExecutor.java` - Order type interface
