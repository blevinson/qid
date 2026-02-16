# AI Strategist Mode Specification

## Overview

### Paradigm Shift

| Current | Proposed |
|---------|----------|
| Signal = Trade Trigger | Signal = Market Intelligence |
| AI = Gatekeeper (TAKE/SKIP) | AI = Strategist (Where/How/When) |
| Entry at signal price | Entry at AI-chosen price |
| Binary decision | Full trading strategy |

### Philosophy

> "Signals are sensors that tell us what's happening. AI is the brain that decides what to do about it."

The AI Strategist Mode transforms Qid from a "signal screener with AI filter" into a "market intelligence system with AI strategist."

---

## Current vs Proposed Flow

### Current Flow (Gatekeeper Mode)

```
Signal Detected @ 5000
        ↓
    AI Evaluates
        ↓
    ┌─────────┐
    │ TAKE/SKIP │
    └─────────┘
        ↓ TAKE
    Order at signal price (±drift adjustment)
```

**Limitations:**
- AI tied to signal price as entry
- Can only accept or reject
- No strategic entry placement
- Latency handled by drift prediction only

### Proposed Flow (Strategist Mode)

```
Signal Detected @ 5000 (Iceberg, CVD +500, Trend UP)
        ↓
    AI Analyzes Market Intel
        ↓
    ┌─────────────────────────────────────┐
    │ What's the optimal entry strategy?  │
    └─────────────────────────────────────┘
        ↓
    AI Strategizes:
    - Entry intent: PULLBACK / BREAKOUT / MOMENTUM / FADE
    - Order type: MARKET / LIMIT / STOP_MARKET
    - Entry price: May differ from signal
    - Entry reasoning: Why this approach
    - Risk constraints: Max risk, chase, slippage
    - Time limits: How long to wait
    - Fallback plan: CANCEL / MARKET / REPRICE
        ↓
    Order placed at AI's chosen price/strategy
```

**Benefits:**
- AI picks optimal entry price
- Strategic entry at key levels (VWAP, POC, DOM levels)
- Can wait for pullbacks or breakouts
- Better R:R through smarter entries
- Latency handled by strategic placement
- Explicit risk constraints envelope

---

## AI Response Schema

### Core Schema

```json
{
  "action": "TRADE" | "WAIT" | "PASS",
  "confidence": 0.0,
  "reasoning": "",
  "marketAnalysis": {
    "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
    "keyLevels": ["VWAP 4995", "POC 4992", "Res 5002"],
    "concerns": ["low volume", "news in 12m"]
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "IOC" | "GTC" | "DAY"
  },
  "strategy": {
    "entryIntent": "PULLBACK" | "BREAKOUT" | "MOMENTUM" | "FADE",
    "order": {
      "type": "MARKET" | "LIMIT" | "STOP_MARKET",
      "price": 4995,
      "expiresInSeconds": 180,
      "fallback": "CANCEL" | "MARKET" | "REPRICE"
    },
    "stopLoss": { "price": 4970 },
    "takeProfit": { "price": 5030 },
    "riskRewardRatio": 1.4
  },
  "monitorPlan": null
}
```

### Action Types

| Action | Description | When Used |
|--------|-------------|-----------|
| `TRADE` | Execute trading strategy | Setup is valid, entry conditions met |
| `WAIT` | Monitor with conditions | Interesting but waiting for specific conditions |
| `PASS` | No interest in this signal | Signal doesn't meet criteria |

### Entry Intents (Strategy Intent)

| Intent | Description | Typical Order Type |
|--------|-------------|-------------------|
| `PULLBACK` | Wait for price to pull back to support | LIMIT |
| `BREAKOUT` | Enter on break above/below level | STOP_MARKET |
| `MOMENTUM` | Enter immediately with momentum | MARKET |
| `FADE` | Fade move at key level (contrarian) | LIMIT or MARKET |

**Why Separation Matters:**
- `entryIntent` = **Why** we want to trade (market structure analysis)
- `order.type` = **How** we execute (order mechanics)
- This allows the same intent to be executed differently based on conditions

### Order Types

| Order Type | Use Case | Typical Intent |
|------------|----------|---------------|
| `MARKET` | Immediate execution | MOMENTUM |
| `LIMIT` | Wait for specific price | PULLBACK, FADE |
| `STOP_MARKET` | Trigger on break | BREAKOUT |

### WAIT Action with Monitor Plan

The `WAIT` action is **actionable** when a `monitorPlan` is provided:

```json
{
  "action": "WAIT",
  "confidence": 0.60,
  "reasoning": "Interesting setup but waiting for confirmation",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["Res 5002"],
    "concerns": ["approaching resistance"]
  },
  "monitorPlan": {
    "upgradeToTradeIf": {
      "priceAbove": 5002,
      "volumeIncreasePercent": 30,
      "cvdTrend": "POSITIVE"
    },
    "invalidateIf": {
      "priceBelow": 4985,
      "cvdTrend": "NEGATIVE"
    },
    "monitorDurationSeconds": 300
  }
}
```

This makes WAIT a **first-class action** that can lead to a TRADE decision if conditions are met.

---

## Detailed Field Specifications

### `action` (Required)

- **TRADE**: Place order according to strategy
- **WAIT**: Monitor market with monitorPlan. May upgrade to TRADE if conditions met.
- **PASS**: No interest, signal is ignored.

### `confidence` (Required)

- 0.0 to 1.0 scale
- Higher confidence = more aggressive sizing (future feature)
- Used for logging and review

### `reasoning` (Required)

- Brief explanation of the decision
- Used for logging and review
- Should reference market structure

### `marketAnalysis` (Required)

```json
{
  "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
  "keyLevels": ["VWAP 4995", "POC 4992", "Res 5002"],
  "concerns": ["low volume", "news in 12m"]
}
```

- **bias**: Overall market directional bias
- **keyLevels**: Array of key price levels (VWAP, POC, S/R levels)
- **concerns**: Array of concerns that factor into decision (can be empty array `[]`)

### `constraints` (Required)

```json
{
  "maxRiskTicks": 20,
  "maxChaseTicks": 6,
  "maxSlippageTicks": 2,
  "timeInForceDefault": "IOC" | "GTC" | "DAY"
}
```

- **maxRiskTicks**: Maximum distance from entry to stop loss (risk envelope)
- **maxChaseTicks**: Maximum ticks to chase a moving market for entry
- **maxSlippageTicks**: Maximum acceptable slippage on fill
- **timeInForceDefault**: Default time-in-force if not specified in order (IOC/GTC/DAY)

These create an **explicit risk constraints envelope** that the execution layer must respect.

### `strategy.entryIntent` (Required if action=TRADE)

- **PULLBACK**: Wait for pullback to support/resistance
- **BREAKOUT**: Enter on breakout of key level
- **MOMENTUM**: Enter immediately with momentum
- **FADE**: Fade the move at key level

### `strategy.order` (Required if action=TRADE)

```json
{
  "type": "MARKET" | "LIMIT" | "STOP_MARKET",
  "price": 4995,
  "expiresInSeconds": 180,
  "fallback": "CANCEL" | "MARKET" | "REPRICE"
}
```

- **type**: Order type
- **price**: Entry price (optional for MARKET orders)
- **expiresInSeconds**: Maximum time to wait for fill (required for LIMIT/STOP_MARKET)
- **fallback**: Action if time limit expires:
  - `CANCEL`: Cancel order, no trade
  - `MARKET`: Convert to market order
  - `REPRICE`: Adjust price towards current market and extend time

### `strategy.stopLoss` (Required if action=TRADE)

```json
{ "price": 4970 }
```

- Stop loss price
- Should be below logical support (long) or above logical resistance (short)
- Must respect `constraints.maxRiskTicks` limit

### `strategy.takeProfit` (Required if action=TRADE)

```json
{ "price": 5030 }
```

- Take profit price
- Should reference resistance/target levels

### `strategy.riskRewardRatio` (Optional)

- Calculated R:R ratio
- Used for logging and analysis

### `monitorPlan` (Optional, for action=WAIT)

```json
{
  "upgradeToTradeIf": {
    "priceAbove": 5002,
    "priceBelow": 4980,
    "volumeIncreasePercent": 30,
    "cvdTrend": "POSITIVE"
  },
  "invalidateIf": {
    "priceBelow": 4985,
    "cvdTrend": "NEGATIVE"
  },
  "monitorDurationSeconds": 300
}
```

- **upgradeToTradeIf**: Conditions that upgrade WAIT to TRADE
- **invalidateIf**: Conditions that invalidate the trade idea
- **monitorDurationSeconds**: How long to monitor before giving up

This makes WAIT **actionable** and **first-class** rather than just "log and forget".

---

## Units and Rounding Rules

### All Prices in Ticks

**All price fields are in ticks, not points or dollars.**

- **entryPrice**: Integer ticks
- **stopLoss.price**: Integer ticks
- **takeProfit.price**: Instrument-specific integer ticks
- **order.price**: Integer ticks

### Tick Size Calculation

```java
// Get instrument-specific tick size
double tickSize = instrument.getTickSize(); // e.g., 0.25 for ES

// Convert ticks to price
double price = ticks * tickSize;

// Convert price to ticks
int ticks = (int) Math.round(price / tickSize);
```

### Rounding Rules

1. **User Input → Ticks**: Always round to nearest tick using `Math.round()`
2. **AI Output → Ticks**: AI returns integers, assumed to be tick count
3. **Display**: Convert ticks back to price for display (4 decimal places for ES)

### Example: ES Futures

```
Tick Size: 0.25
Point Value: $50

AI Output: 4995 ticks
  → Price: 4995 * 0.25 = 1248.75

AI Output: SL @ 4970 ticks
  → Price: 4970 * 0.25 = 1242.50

Display: Entry @ 1248.75 | SL @ 1242.50
```

---

## Position Management (HOLD/MANAGE)

**Note:** This is a placeholder for future implementation. Initial version focuses on entry only.

### Future Schema Extension

For managing existing positions, the schema will be extended:

```json
{
  "action": "MANAGE" | "HOLD" | "CLOSE",
  "positionId": "uuid",
  "strategy": {
    "actionType": "TRAIL_STOP" | "MOVE_SL" | "SCALE_OUT" | "SCALE_IN",
    "newStopLoss": { "price": 4975 },
    "trailOffsetTicks": 10,
    "scaleOutQuantity": 1
  },
  "reasoning": "Moving stop to breakeven as price approaches resistance"
}
```

### Action Types for Position Management

| Action | Description |
|--------|-------------|
| `HOLD` | Maintain current position and stop/target |
| `MANAGE` | Adjust stop/target, trail, or scale |
| `CLOSE` | Close position immediately |

This will be added in a later phase after entry strategy is validated.

---

## Order State Machine

### State Diagram

```
        ┌─────────────┐
        │   PENDING   │
        │ (Order sent)│
        └──────┬──────┘
               │
               ├─→ [Filled] ──→ FILLED ──→ CLOSED
               │
               ├─→ [Rejected] ──→ FAILED
               │
               ├─→ [Cancelled] ──→ CANCELLED
               │
               └─→ [Timeout] ──→ [Fallback Action]
                                │
                                ├─→ CANCEL
                                ├─→ MARKET
                                └─→ REPRICE ──→ [New Order]
```

### States

| State | Description |
|-------|-------------|
| `PENDING` | Order submitted, awaiting fill |
| `FILLED` | Order filled completely or partially |
| `CANCELLED` | Order cancelled before fill |
| `FAILED` | Order rejected by exchange |
| `CLOSED` | Position closed (SL/TP hit or manual) |

### Events

| Event | Description | Valid Transitions |
|-------|-------------|-------------------|
| `ORDER_SUBMITTED` | Order sent to exchange | → PENDING |
| `ORDER_FILLED` | Order filled | PENDING → FILLED |
| `ORDER_REJECTED` | Order rejected | PENDING → FAILED |
| `ORDER_CANCELLED` | Order cancelled | PENDING → CANCELLED |
| `TIMEOUT` | Time limit expired | PENDING → [Fallback Action] |
| `POSITION_CLOSED` | SL/TP hit | FILLED → CLOSED |

### Race Condition Handling

**Scenario:** Order fills while timeout callback fires

```java
// Use atomic compare-and-set
if (orderState.compareAndSet(OrderState.PENDING, OrderState.FILLED)) {
    // Handle fill
} else if (orderState.compareAndSet(OrderState.PENDING, OrderState.CANCELLED)) {
    // Handle cancellation
} else {
    // State already changed - ignore duplicate event
    log("Duplicate event for order " + orderId + ", current state: " + orderState);
}
```

### Idempotency

**Problem:** Duplicate signals can cause duplicate orders

**Solution:** Use idempotency key

```java
// Signal contains unique identifier
String idempotencyKey = signal.getId() + "_" + AI.getId();

// Check for existing order
if (activeOrders.containsKey(idempotencyKey)) {
    log("Duplicate signal detected, ignoring: " + idempotencyKey);
    return;
}

// Place order with idempotency key
placeOrder(plan, idempotencyKey);
activeOrders.put(idempotencyKey, orderId);
```

---

## Example Scenarios

### Scenario 1: Pullback Entry (LIMIT)

**Signal:** Iceberg BUY detected @ 5000 ticks
**Context:** Price at 5002, VWAP at 4995, strong uptrend

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.85,
  "reasoning": "Strong institutional buying in uptrend",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["VWAP 4995", "POC 4992"],
    "concerns": []
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "GTC"
  },
  "strategy": {
    "entryIntent": "PULLBACK",
    "order": {
      "type": "LIMIT",
      "price": 4995,
      "expiresInSeconds": 180,
      "fallback": "CANCEL"
    },
    "stopLoss": { "price": 4970 },
    "takeProfit": { "price": 5030 },
    "riskRewardRatio": 1.4
  },
  "monitorPlan": null
}
```

**Result:** LIMIT order placed at 4995 ticks. If filled within 180s, position opened. If not, order cancelled.

---

### Scenario 2: Momentum Entry (MARKET)

**Signal:** Absorption completion @ 5000 ticks
**Context:** Price at 5000, momentum accelerating, volume surging

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.90,
  "reasoning": "Absorption complete, momentum accelerating - time critical",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["Prior res 4998"],
    "concerns": []
  },
  "constraints": {
    "maxRiskTicks": 25,
    "maxChaseTicks": 4,
    "maxSlippageTicks": 3,
    "timeInForceDefault": "IOC"
  },
  "strategy": {
    "entryIntent": "MOMENTUM",
    "order": {
      "type": "MARKET",
      "price": null,
      "expiresInSeconds": null,
      "fallback": null
    },
    "stopLoss": { "price": 4975 },
    "takeProfit": { "price": 5050 },
    "riskRewardRatio": 2.0
  },
  "monitorPlan": null
}
```

**Result:** MARKET order placed immediately at best available price.

---

### Scenario 3: Breakout Entry (STOP_MARKET)

**Signal:** Iceberg BUY detected @ 5000 ticks
**Context:** Price at 5000, strong resistance at 5002, not yet broken

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.75,
  "reasoning": "Institutional buying at resistance, wait for breakout confirmation",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["Res 5002"],
    "concerns": ["False breakout risk if volume doesn't confirm"]
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 8,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "GTC"
  },
  "strategy": {
    "entryIntent": "BREAKOUT",
    "order": {
      "type": "STOP_MARKET",
      "price": 5003,
      "expiresInSeconds": 300,
      "fallback": "CANCEL"
    },
    "stopLoss": { "price": 4990 },
    "takeProfit": { "price": 5030 },
    "riskRewardRatio": 1.8
  },
  "monitorPlan": null
}
```

**Result:** STOP_MARKET order with trigger at 5003 ticks. If price breaks above, market order executes.

---

### Scenario 4: Wait with Monitor Plan

**Signal:** Iceberg BUY detected @ 5000 ticks
**Context:** Price at 5000, approaching resistance at 5005, low volume

**AI Response:**
```json
{
  "action": "WAIT",
  "confidence": 0.60,
  "reasoning": "Interesting setup but waiting for breakout confirmation",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["Res 5002", "Major res 5005"],
    "concerns": ["Approaching resistance", "Volume below average"]
  },
  "constraints": {
    "maxRiskTicks": 25,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "GTC"
  },
  "strategy": null,
  "monitorPlan": {
    "upgradeToTradeIf": {
      "priceAbove": 5002,
      "volumeIncreasePercent": 30,
      "cvdTrend": "POSITIVE"
    },
    "invalidateIf": {
      "priceBelow": 4985,
      "cvdTrend": "NEGATIVE"
    },
    "monitorDurationSeconds": 300
  }
}
```

**Result:** No order placed. Signal logged for monitoring. System will check upgrade/invalidate conditions for 5 minutes.

---

### Scenario 5: Pass

**Signal:** Iceberg BUY detected @ 5000 ticks
**Context:** Price at 5000, bearish trend, CVD negative

**AI Response:**
```json
{
  "action": "PASS",
  "confidence": 0.30,
  "reasoning": "Counter-trend signal in bearish market - skip",
  "marketAnalysis": {
    "bias": "BEARISH",
    "keyLevels": [],
    "concerns": ["Signal against trend", "CVD negative"]
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 5,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "IOC"
  },
  "strategy": null,
  "monitorPlan": null
}
```

**Result:** Signal ignored.

---

### Scenario 6: REPRICE Fallback

**Signal:** Absorption @ 5000 ticks
**Context:** Price moving fast, wants to chase but with limit

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.80,
  "reasoning": "Strong momentum, enter with reprice fallback",
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["VWAP 4995"],
    "concerns": ["Fast moving market"]
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "GTC"
  },
  "strategy": {
    "entryIntent": "MOMENTUM",
    "order": {
      "type": "LIMIT",
      "price": 5002,
      "expiresInSeconds": 60,
      "fallback": "REPRICE"
    },
    "stopLoss": { "price": 4980 },
    "takeProfit": { "price": 5040 },
    "riskRewardRatio": 2.0
  },
  "monitorPlan": null
}
```

**Result:** LIMIT order at 5002 ticks. If not filled in 60s, price adjusted towards current market (respecting maxChaseTicks) and extended time.

---

## Implementation Plan

### Phase 1: Data Model Updates

#### New Enums

```java
// StrategistAction.java
public enum StrategistAction {
    TRADE("TRADE", "Execute trading strategy"),
    WAIT("WAIT", "Monitor with conditions, may upgrade to trade"),
    PASS("PASS", "No interest in this signal");

    private final String value;
    private final String description;

    StrategistAction(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static StrategistAction fromString(String value) {
        for (StrategistAction action : values()) {
            if (action.value.equalsIgnoreCase(value)) {
                return action;
            }
        }
        return PASS; // Default to PASS if unknown
    }
}

// EntryIntent.java
public enum EntryIntent {
    PULLBACK("PULLBACK", "Wait for pullback to support/resistance"),
    BREAKOUT("BREAKOUT", "Enter on breakout of key level"),
    MOMENTUM("MOMENTUM", "Enter immediately with momentum"),
    FADE("FADE", "Fade the move at key level");

    // ... similar to StrategistAction
}

// OrderType.java (enhanced)
public enum OrderType {
    MARKET("MARKET", "Immediate execution"),
    LIMIT("LIMIT", "Wait for specific price"),
    STOP_MARKET("STOP_MARKET", "Trigger on break");

    // ... similar to StrategistAction
}

// FallbackAction.java
public enum FallbackAction {
    CANCEL("CANCEL", "Cancel order, no trade"),
    MARKET("MARKET", "Convert to market order"),
    REPRICE("REPRICE", "Adjust price towards market and extend time");

    // ... similar to StrategistAction
}

// OrderState.java
public enum OrderState {
    PENDING("PENDING", "Order submitted, awaiting fill"),
    FILLED("FILLED", "Order filled"),
    CANCELLED("CANCELLED", "Order cancelled"),
    FAILED("FAILED", "Order rejected"),
    CLOSED("CLOSED", "Position closed");

    // ... similar to StrategistAction
}
```

#### Enhanced Data Classes

```java
// MarketAnalysis.java
public static class MarketAnalysis {
    public String bias;                    // BULLISH/BEARISH/NEUTRAL
    public List<String> keyLevels;         // Key levels
    public List<String> concerns;         // Concerns

    public MarketAnalysis() {
        this.keyLevels = new ArrayList<>();
        this.concerns = new ArrayList<>();
    }
}

// Constraints.java
public static class Constraints {
    public int maxRiskTicks = 20;
    public int maxChaseTicks = 6;
    public int maxSlippageTicks = 2;
    public String timeInForceDefault = "GTC"; // IOC/GTC/DAY
}

// OrderSpec.java
public static class OrderSpec {
    public String type;                    // MARKET/LIMIT/STOP_MARKET
    public Integer price;                  // Entry price in ticks (null for MARKET)
    public Integer expiresInSeconds;       // Max wait time (null for MARKET)
    public String fallback;               // CANCEL/MARKET/REPRICE

    // Helper to check if has expiry
    public boolean hasExpiry() {
        return type != null && !type.equals("MARKET") && expiresInSeconds != null;
    }
}

// StopTakeLevel.java
public static class StopTakeLevel {
    public int price;                     // Price in ticks
}

// Strategy.java
public static class Strategy {
    public String entryIntent;            // PULLBACK/BREAKOUT/MOMENTUM/FADE
    public OrderSpec order;
    public StopTakeLevel stopLoss;
    public StopTakeLevel takeProfit;
    public Double riskRewardRatio;
}

// MonitorPlan.java
public static class MonitorPlan {
    public Map<String, Object> upgradeToTradeIf;  // Upgrade conditions
    public Map<String, Object> invalidateIf;      // Invalidate conditions
    public int monitorDurationSeconds;

    public MonitorPlan() {
        this.upgradeToTradeIf = new HashMap<>();
        this.invalidateIf = new HashMap<>();
    }
}

// PendingOrder.java (new)
public static class PendingOrder {
    public String orderId;
    public String idempotencyKey;
    public long expiryTime;
    public String fallbackAction;
    public Strategy strategy;
    public OrderState state = OrderState.PENDING;

    public PendingOrder(String orderId, String idempotencyKey, long expiryTime,
                        String fallbackAction, Strategy strategy) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.expiryTime = expiryTime;
        this.fallbackAction = fallbackAction;
        this.strategy = strategy;
    }
}

// Enhanced TradePlan for strategist mode
public static class TradePlan {
    // Existing fields (keep for backward compatibility)
    public String orderType = "BUY";
    public String executionType = "MARKET";
    public Integer entryPrice;
    public Integer stopLossPrice;
    public Integer takeProfitPrice;

    // New strategist fields
    public String entryReasoning;           // Why this entry type/price
    public Integer timeLimitSeconds;        // Max wait time for LIMIT/STOP
    public String fallbackAction;           // CANCEL/MARKET/REPRICE
    public Double riskRewardRatio;          // Calculated R:R

    // New analyst fields
    public String entryIntent;              // PULLBACK/BREAKOUT/MOMENTUM/FADE
    public Constraints constraints;
    public MarketAnalysis marketAnalysis;
    public MonitorPlan monitorPlan;
}

// Enhanced AIDecision
public static class AIDecision {
    public String signalId;
    public StrategistAction action;
    public double confidence;
    public String reasoning;
    public MarketAnalysis marketAnalysis;
    public Constraints constraints;
    public Strategy strategy;
    public MonitorPlan monitorPlan;
    public TradePlan plan;

    // Helper to check if actionable
    public boolean isTradeAction() {
        return action == StrategistAction.TRADE;
    }

    public boolean isWaitAction() {
        return action == StrategistAction.WAIT && monitorPlan != null;
    }
}
```

### Phase 2: Prompt Updates

```java
String prompt = """
You are a trading strategist. A market signal has been detected.

📊 SIGNAL INTEL:
- Type: {signal.type} at {signal.price} ticks
- Score: {signal.score}/100
- Direction: {signal.direction}

📈 MARKET CONTEXT:
- Current Price: {current.price} ticks
- Trend: {market.trend}
- CVD: {market.cvd} ({market.cvdTrend})
- Momentum: {market.momentum}

📍 KEY LEVELS (in ticks):
- VWAP: {vwap}
- POC: {poc}
- DOM Support: {dom.support}
- DOM Resistance: {dom.resistance}

⏱️ LATENCY: ~{latency}ms average API response time

YOUR ROLE: Analyze this market intelligence and decide:

1. Is this a trading opportunity? (TRADE/WAIT/PASS)
   - TRADE: Execute trading strategy
   - WAIT: Monitor with upgrade/invalidate conditions
   - PASS: No interest

2. If TRADE: What's the optimal entry strategy?
   - Entry Intent: PULLBACK (wait for pullback), BREAKOUT (enter on break),
     MOMENTUM (enter now), FADE (fade at level)
   - Order Type: MARKET (immediate), LIMIT (wait for price), STOP_MARKET (trigger on break)
   - Entry Price: May differ from signal price (in ticks)

3. Risk constraints envelope:
   - maxRiskTicks: Maximum risk in ticks (stop distance)
   - maxChaseTicks: Maximum ticks to chase market
   - maxSlippageTicks: Maximum acceptable slippage

4. Stop loss and take profit: Place at logical levels (in ticks)

5. For LIMIT/STOP: How long to wait? What's the fallback?
   - expiresInSeconds: Max time to wait
   - fallback: CANCEL (cancel), MARKET (convert to market), REPRICE (adjust price)

6. For WAIT: Provide monitor plan:
   - upgradeToTradeIf: Conditions to upgrade to trade
   - invalidateIf: Conditions that invalidate the idea
   - monitorDurationSeconds: How long to monitor

IMPORTANT: All prices are in TICKS, not points or dollars.

RESPOND WITH JSON ONLY:
{
  "action": "TRADE" | "WAIT" | "PASS",
  "confidence": 0.0,
  "reasoning": "Brief explanation",
  "marketAnalysis": {
    "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
    "keyLevels": ["VWAP 4995", "POC 4992"],
    "concerns": ["low volume"]
  },
  "constraints": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2,
    "timeInForceDefault": "IOC" | "GTC" | "DAY"
  },
  "strategy": {
    "entryIntent": "PULLBACK" | "BREAKOUT" | "MOMENTUM" | "FADE",
    "order": {
      "type": "MARKET" | "LIMIT" | "STOP_MARKET",
      "price": 4995,
      "expiresInSeconds": 180,
      "fallback": "CANCEL" | "MARKET" | "REPRICE"
    },
    "stopLoss": { "price": 4970 },
    "takeProfit": { "price": 5030 },
    "riskRewardRatio": 1.4
  },
  "monitorPlan": null
}
""";
```

### Phase 3: Execution Logic

#### AIOrderManager with State Machine

```java
public class AIOrderManager {
    private Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();
    private Map<String, String> idempotencyKeys = new ConcurrentHashMap<>(); // orderId -> key

    public boolean executeStrategicEntry(AIDecision decision, Signal signal) {
        if (decision.action != StrategistAction.TRADE) {
            return false;
        }

        Strategy strategy = decision.strategy;
        OrderSpec order = strategy.order;

        // Create idempotency key
        String idempotencyKey = signal.getId() + "_" + UUID.randomUUID().toString();

        // Check for duplicate
        if (idempotencyKeys.containsValue(idempotencyKey)) {
            log("Duplicate signal detected, ignoring: " + idempotencyKey);
            return false;
        }

        // Execute based on order type
        if (order.type.equals("MARKET")) {
            return executeMarketEntry(strategy, signal, idempotencyKey);

        } else if (order.type.equals("LIMIT")) {
            return executeLimitEntry(strategy, signal, order.expiresInSeconds,
                                    order.fallback, idempotencyKey);

        } else if (order.type.equals("STOP_MARKET")) {
            return executeStopMarketEntry(strategy, signal, order.expiresInSeconds,
                                         order.fallback, idempotencyKey);
        }

        return false;
    }

    private boolean executeMarketEntry(Strategy strategy, Signal signal, String idempotencyKey) {
        int orderId = placeMarketOrder(signal.direction, strategy.stopLoss.price,
                                      strategy.takeProfit.price);

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[MARKET] Order " + orderId + " placed - " + strategy.entryIntent);
        return true;
    }

    private boolean executeLimitEntry(Strategy strategy, Signal signal, int timeLimit,
                                     String fallback, String idempotencyKey) {
        int orderId = placeLimitOrder(signal.direction, strategy.order.price,
                                      strategy.stopLoss.price, strategy.takeProfit.price);

        long expiryTime = System.currentTimeMillis() + (timeLimit * 1000);

        pendingOrders.put(orderId, new PendingOrder(
            String.valueOf(orderId),
            idempotencyKey,
            expiryTime,
            fallback,
            strategy
        ));

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[LIMIT] Order " + orderId + " placed @ " + strategy.order.price + " ticks, " +
            "expires in " + timeLimit + "s, fallback: " + fallback);
        return true;
    }

    private boolean executeStopMarketEntry(Strategy strategy, Signal signal, int timeLimit,
                                          String fallback, String idempotencyKey) {
        int orderId = placeStopMarketOrder(signal.direction, strategy.order.price,
                                           strategy.stopLoss.price, strategy.takeProfit.price);

        long expiryTime = System.currentTimeMillis() + (timeLimit * 1000);

        pendingOrders.put(orderId, new PendingOrder(
            String.valueOf(orderId),
            idempotencyKey,
            expiryTime,
            fallback,
            strategy
        ));

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[STOP_MARKET] Order " + orderId + " placed @ " + strategy.order.price + " ticks, " +
            "expires in " + timeLimit + "s, fallback: " + fallback);
        return true;
    }

    public void checkPendingOrderTimeouts() {
        long now = System.currentTimeMillis();

        for (PendingOrder pending : new ArrayList<>(pendingOrders.values())) {
            if (now > pending.expiryTime && pending.state == OrderState.PENDING) {
                handleOrderTimeout(pending);
            }
        }
    }

    private void handleOrderTimeout(PendingOrder pending) {
        if (!pending.state.compareAndSet(OrderState.PENDING, OrderState.CANCELLED)) {
            // State already changed, ignore
            return;
        }

        String fallback = pending.fallbackAction;

        if (fallback.equals("CANCEL")) {
            cancelOrder(pending.orderId);
            log("[TIMEOUT] Order " + pending.orderId + " cancelled - time limit expired");

        } else if (fallback.equals("MARKET")) {
            cancelOrder(pending.orderId);
            executeMarketEntry(pending.strategy, null, pending.idempotencyKey);
            log("[TIMEOUT] Order " + pending.orderId + " converted to MARKET");

        } else if (fallback.equals("REPRICE")) {
            // Adjust price towards current market (respecting maxChaseTicks)
            int currentPrice = getCurrentMarketPrice();
            int maxChase = pending.strategy.order.price + pending.strategy.constraints.maxChaseTicks;

            int newPrice = Math.min(currentPrice, maxChase);

            // Cancel old order and place new one
            cancelOrder(pending.orderId);
            pending.strategy.order.price = newPrice;
            pending.expiryTime = System.currentTimeMillis() + 30000; // 30s more

            executeLimitEntry(pending.strategy, null, 30, "CANCEL", pending.idempotencyKey);
            log("[TIMEOUT] Order " + pending.orderId + " re-priced to " + newPrice + " ticks");
        }

        pendingOrders.remove(pending.orderId);
        idempotencyKeys.remove(pending.orderId);
    }

    public void onOrderFilled(String orderId, int fillPrice) {
        PendingOrder pending = pendingOrders.get(orderId);
        if (pending != null) {
            pending.state = OrderState.FILLED;
            log("[FILLED] Order " + orderId + " filled @ " + fillPrice + " ticks");
            pendingOrders.remove(orderId);
            idempotencyKeys.remove(orderId);
        }
    }

    public void onOrderCancelled(String orderId) {
        PendingOrder pending = pendingOrders.get(orderId);
        if (pending != null) {
            pending.state = OrderState.CANCELLED;
            log("[CANCELLED] Order " + orderId);
            pendingOrders.remove(orderId);
            idempotencyKeys.remove(orderId);
        }
    }
}
```

#### MonitorPlan Execution

```java
public class MonitorPlanExecutor {
    private Map<String, MonitorContext> activeMonitors = new ConcurrentHashMap<>();

    public void startMonitoring(AIDecision decision, Signal signal) {
        if (decision.action != StrategistAction.WAIT || decision.monitorPlan == null) {
            return;
        }

        String monitorId = signal.getId() + "_" + UUID.randomUUID().toString();
        MonitorContext context = new MonitorContext(
            monitorId,
            decision.monitorPlan,
            decision.marketAnalysis,
            System.currentTimeMillis(),
            decision.monitorPlan.monitorDurationSeconds * 1000
        );

        activeMonitors.put(monitorId, context);
        log("[MONITOR] Started monitoring " + monitorId + " for " +
            decision.monitorPlan.monitorDurationSeconds + "s");
    }

    public void checkMonitors(MarketData market) {
        long now = System.currentTimeMillis();

        for (MonitorContext context : new ArrayList<>(activeMonitors.values())) {
            if (now > context.expiryTime) {
                // Monitor expired
                log("[MONITOR] " + context.monitorId + " expired - no upgrade");
                activeMonitors.remove(context.monitorId);
                continue;
            }

            // Check upgrade conditions
            if (checkUpgradeConditions(context, market)) {
                log("[MONITOR] " + context.monitorId + " - upgrading to TRADE");
                // Trigger AI re-evaluation with new conditions
                triggerTradeDecision(context);
                activeMonitors.remove(context.monitorId);
                continue;
            }

            // Check invalidate conditions
            if (checkInvalidateConditions(context, market)) {
                log("[MONITOR] " + context.monitorId + " - invalidated");
                activeMonitors.remove(context.monitorId);
                continue;
            }
        }
    }

    private boolean checkUpgradeConditions(MonitorContext context, MarketData market) {
        Map<String, Object> conditions = context.monitorPlan.upgradeToTradeIf;

        if (conditions.containsKey("priceAbove")) {
            int threshold = (int) conditions.get("priceAbove");
            if (market.price <= threshold) return false;
        }

        if (conditions.containsKey("priceBelow")) {
            int threshold = (int) conditions.get("priceBelow");
            if (market.price >= threshold) return false;
        }

        if (conditions.containsKey("volumeIncreasePercent")) {
            double threshold = (double) conditions.get("volumeIncreasePercent");
            double increase = ((market.volume - context.startVolume) / context.startVolume) * 100;
            if (increase < threshold) return false;
        }

        if (conditions.containsKey("cvdTrend")) {
            String trend = (String) conditions.get("cvdTrend");
            if (!market.cvdTrend.equals(trend)) return false;
        }

        return true;
    }

    private boolean checkInvalidateConditions(MonitorContext context, MarketData market) {
        Map<String, Object> conditions = context.monitorPlan.invalidateIf;

        if (conditions.containsKey("priceAbove")) {
            int threshold = (int) conditions.get("priceAbove");
            if (market.price > threshold) return true;
        }

        if (conditions.containsKey("priceBelow")) {
            int threshold = (int) conditions.get("priceBelow");
            if (market.price < threshold) return true;
        }

        if (conditions.containsKey("cvdTrend")) {
            String trend = (String) conditions.get("cvdTrend");
            if (market.cvdTrend.equals(trend)) return true;
        }

        return false;
    }

    private void triggerTradeDecision(MonitorContext context) {
        // Call AI again with updated context to get TRADE strategy
        // This is a placeholder - actual implementation would call AI service
        log("[MONITOR] Triggering AI re-evaluation for " + context.monitorId);
    }

    public static class MonitorContext {
        public String monitorId;
        public MonitorPlan monitorPlan;
        public MarketAnalysis marketAnalysis;
        public long startTime;
        public long expiryTime;
        public double startVolume;

        public MonitorContext(String monitorId, MonitorPlan monitorPlan,
                             MarketAnalysis marketAnalysis, long startTime, long expiryTime) {
            this.monitorId = monitorId;
            this.monitorPlan = monitorPlan;
            this.marketAnalysis = marketAnalysis;
            this.startTime = startTime;
            this.expiryTime = expiryTime;
        }
    }
}
```

### Phase 4: UI/Settings Updates

#### New Settings

```java
// OrderFlowStrategyEnhanced.java

// Strategist mode settings
public boolean strategistModeEnabled = true;  // Enable strategist mode vs gatekeeper
public boolean logEntryReasoning = true;      // Log AI's entry reasoning
public boolean trackStrategistPerformance = true; // Track by entry type
public boolean enableWaitMonitoring = true;   // Enable WAIT monitor plans
```

#### UI Controls

- Checkbox: "Strategist Mode" (enabled by default)
- Checkbox: "Log Entry Reasoning"
- Checkbox: "Enable WAIT Monitoring"
- Performance display: Entry success rates by type (MARKET/LIMIT/STOP)
- Monitor panel: Active WAIT signals with conditions

---

## Performance Tracking

### Metrics to Track

| Metric | Description |
|--------|-------------|
| Entry Type Distribution | % MARKET vs LIMIT vs STOP_MARKET |
| Entry Intent Distribution | % PULLBACK vs BREAKOUT vs MOMENTUM vs FADE |
| Limit Fill Rate | % of LIMIT orders that filled |
| Stop Fill Rate | % of STOP_MARKET orders that triggered |
| Entry Price vs Signal | Average ticks better/worse than signal |
| R:R by Entry Type | Average R:R achieved by entry type |
| Timeout Rate | % of orders cancelled due to time limit |
| Fallback Usage | % of orders that used MARKET vs REPRICE fallback |
| WAIT Upgrade Rate | % of WAIT signals that upgraded to TRADE |
| WAIT Invalidate Rate | % of WAIT signals that were invalidated |

### Logging Format

```
[STRATEGIST] Signal: ICEBERG_BUY @ 5000 ticks | Action: TRADE
[STRATEGIST] Intent: PULLBACK | Order: LIMIT @ 4995 ticks
[STRATEGIST] Reasoning: Wait for pullback to VWAP
[STRATEGIST] Constraints: maxRisk=20, maxChase=6, maxSlippage=2
[STRATEGIST] SL: 4970 ticks | TP: 5030 ticks | R:R: 1.4
[STRATEGIST] Time limit: 180s | Fallback: CANCEL
...
[STRATEGIST] LIMIT filled @ 4995 ticks after 45s
```

---

## Testing Checklist

### Unit Tests

- [ ] AI correctly parses TRADE/WAIT/PASS actions
- [ ] AI correctly parses entryIntent (PULLBACK/BREAKOUT/MOMENTUM/FADE)
- [ ] AI correctly parses order types (MARKET/LIMIT/STOP_MARKET)
- [ ] AI correctly parses fallback actions (CANCEL/MARKET/REPRICE)
- [ ] Constraint validation (maxRiskTicks, maxChaseTicks, maxSlippageTicks)
- [ ] Tick size conversion (ticks ↔ price)
- [ ] Tick rounding rules
- [ ] Idempotency key prevents duplicate orders
- [ ] Order state machine transitions
- [ ] Race condition handling (fill vs timeout)

### Integration Tests

- [ ] MARKET orders execute immediately
- [ ] LIMIT orders placed at correct price
- [ ] STOP_MARKET orders trigger correctly
- [ ] Time limits enforced correctly
- [ ] CANCEL fallback works
- [ ] MARKET fallback works
- [ ] REPRICE fallback respects maxChaseTicks
- [ ] Entry reasoning logged correctly
- [ ] Market analysis logged correctly
- [ ] WAIT monitor plan starts
- [ ] WAIT upgrade conditions work
- [ ] WAIT invalidate conditions work
- [ ] WAIT monitor expires correctly

### Edge Cases

- [ ] Signal price = 0 or invalid
- [ ] AI returns invalid JSON
- [ ] AI returns missing required fields
- [ ] Order rejected by exchange
- [ ] Order fills while timeout callback fires
- [ ] Duplicate signal received
- [ ] Market moves too fast (exceeds maxChaseTicks)
- [ ] Slippage exceeds maxSlippageTicks
- [ ] WAIT with no monitorPlan (should be logged as PASS)
- [ ] Multiple concurrent WAIT monitors

### Slippage Guardrails

- [ ] MARKET order filled within maxSlippageTicks
- [ ] LIMIT order fill price ≤ entryPrice (long)
- [ ] STOP_MARKET fill price ≤ triggerPrice + maxSlippageTicks
- [ ] REPRICE respects maxChaseTicks

---

## Migration Path

### Step 1: Add New Fields (Non-Breaking)

- Add new enums (StrategistAction, EntryIntent, FallbackAction, OrderState)
- Add new data classes (MarketAnalysis, Constraints, OrderSpec, etc.)
- Add new fields to TradePlan
- Add new fields to AIDecision
- Keep existing `shouldTake` for backward compatibility

### Step 2: Update Prompts

- Switch from TAKE/SKIP to TRADE/WAIT/PASS
- Request full strategy object with entryIntent
- Request constraints envelope
- Request monitorPlan for WAIT actions
- Update documentation to emphasize tick-based prices

### Step 3: Update Execution

- Add `executeStrategicEntry()` method
- Add `AIOrderManager` with pending order tracking
- Add `MonitorPlanExecutor` for WAIT monitoring
- Add time limit checking
- Add order state machine
- Add idempotency key handling

### Step 4: Add Tests

- Unit tests for new data models
- Unit tests for order state machine
- Integration tests for all order types
- Edge case tests for race conditions

### Step 5: Deprecate Old Fields

- Mark `shouldTake` as deprecated
- Update all references to use `strategistAction`
- Remove old TAKE/SKIP logic
- Remove old gatekeeper mode (optional - can keep as fallback)

---

## Future Enhancements

1. **Multi-Level Entries**: AI could stage entries at multiple price levels
2. **Dynamic Time Limits**: AI adjusts time limits based on market conditions
3. **Entry Type Learning**: Track which entry types work best in which conditions
4. **Position Management**: Extend schema for HOLD/MANAGE/CLOSE actions
5. **Scale In/Out**: AI manages position sizing dynamically
6. **Alternative Strategies**: AI proposes backup strategy if primary fails
7. **Tick Size Optimization**: AI chooses optimal tick rounding for better fills
8. **Slippage Prediction**: AI predicts and accounts for slippage in entry price
9. **Market Regime Detection**: AI adapts strategy based on market regime
10. **Multi-Timeframe**: AI considers multiple timeframes for entry timing
