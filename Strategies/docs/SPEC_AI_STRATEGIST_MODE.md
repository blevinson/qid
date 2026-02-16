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
  "instrument": "ES",
  "side": "LONG" | "SHORT",
  "tickSize": 0.25,
  "marketAnalysis": {
    "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
    "keyLevels": ["VWAP 4995", "POC 4992", "Res 5002"],
    "concerns": ["low volume", "news in 12m"]
  },
  "constraintsUsed": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2
  },
  "strategy": {
    "entryIntent": "PULLBACK" | "BREAKOUT" | "MOMENTUM" | "FADE",
    "order": {
      "type": "MARKET" | "LIMIT" | "STOP_MARKET",
      "price": 4995.0,
      "tif": "IOC" | "GTC" | "DAY",
      "expiresInSeconds": 180,
      "fallback": "CANCEL" | "MARKET" | "REPRICE"
    },
    "stopLoss": { "offsetTicks": -25 },
    "takeProfit": { "offsetTicks": 35 },
    "riskRewardRatio": 1.4
  },
  "monitorPlan": null
}
```

**Field Requirements by Action:**

| Action | strategy | monitorPlan | constraintsUsed |
|--------|-----------|--------------|-----------------|
| TRADE | Required | Must be null | Required |
| WAIT | Must be null | Required | Required |
| PASS | Must be null | Must be null | Required |

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
  "instrument": "ES",
  "side": "LONG",
  "tickSize": 0.25,
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["Res 5002"],
    "concerns": ["approaching resistance"]
  },
  "constraintsUsed": {
    "maxRiskTicks": 20,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2
  },
  "monitorPlan": {
    "durationSeconds": 300,
    "upgrade": {
      "priceCrossAbove": 5002.0,
      "volumeIncreasePercent": 30.0,
      "cvdTrend": "POSITIVE"
    },
    "invalidate": {
      "priceCrossBelow": 4985.0,
      "cvdTrend": "NEGATIVE"
    }
  }
}
```

**MonitorPlan Fields:**

| Field | Type | Description |
|-------|-------|-------------|
| `durationSeconds` | Integer | How long to monitor before giving up |
| `upgrade` | ConditionSet | Conditions that upgrade WAIT to TRADE |
| `invalidate` | ConditionSet | Conditions that invalidate the trade idea |

**ConditionSet Fields:**

| Field | Type | Description |
|-------|-------|-------------|
| `priceCrossAbove` | Double | Trigger if price crosses above this level |
| `priceCrossBelow` | Double | Trigger if price crosses below this level |
| `volumeIncreasePercent` | Double | Trigger if volume increased by this % |
| `cvdTrend` | String | "POSITIVE", "NEGATIVE", "NEUTRAL" |

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

### `constraintsUsed` (Required)

**Note:** Constraints come from system configuration, not AI. AI echoes back which constraints are binding.

```json
{
  "maxRiskTicks": 20,
  "maxChaseTicks": 6,
  "maxSlippageTicks": 2
}
```

- **maxRiskTicks**: Maximum distance from entry to stop loss (risk envelope)
- **maxChaseTicks**: Maximum ticks to chase a moving market for entry
- **maxSlippageTicks**: Maximum acceptable slippage on fill

These create an **explicit risk constraints envelope** that the execution layer must respect. The AI should echo back the constraints that influenced its decision.

### `strategy.entryIntent` (Required if action=TRADE)

- **PULLBACK**: Wait for pullback to support/resistance
- **BREAKOUT**: Enter on breakout of key level
- **MOMENTUM**: Enter immediately with momentum
- **FADE**: Fade the move at key level

### `strategy.order` (Required if action=TRADE)

```json
{
  "type": "MARKET" | "LIMIT" | "STOP_MARKET",
  "price": 4995.0,
  "tif": "IOC" | "GTC" | "DAY",
  "expiresInSeconds": 180,
  "fallback": "CANCEL" | "MARKET" | "REPRICE"
}
```

- **type**: Order type
- **price**: Entry price in decimal points (optional for MARKET orders)
- **tif**: Time-in-force (IOC/GTC/DAY). Overrides default if specified.
- **expiresInSeconds**: Maximum time to wait for fill (required for LIMIT/STOP_MARKET)
- **fallback**: Action if time limit expires:
  - `CANCEL`: Cancel order, no trade
  - `MARKET`: Convert to market order
  - `REPRICE`: Adjust price towards current market and extend time

### `strategy.stopLoss` (Required if action=TRADE)

```json
{ "offsetTicks": -25 }
```

- Stop loss offset in ticks from entry price (negative for long, positive for short)
- Should be below logical support (long) or above logical resistance (short)
- Must respect `constraintsUsed.maxRiskTicks` limit
- Execution layer calculates absolute price: `stopLossPrice = entryPrice + (offsetTicks * tickSize)`

### `strategy.takeProfit` (Required if action=TRADE)

```json
{ "offsetTicks": 35 }
```

- Take profit offset in ticks from entry price (positive for long, negative for short)
- Should reference resistance/target levels
- Execution layer calculates absolute price: `takeProfitPrice = entryPrice + (offsetTicks * tickSize)`

### `strategy.riskRewardRatio` (Optional)

- Calculated R:R ratio
- Used for logging and analysis

### `monitorPlan` (Optional, for action=WAIT)

```json
{
  "durationSeconds": 300,
  "upgrade": {
    "priceCrossAbove": 5002.0,
    "priceCrossBelow": 4980.0,
    "volumeIncreasePercent": 30.0,
    "cvdTrend": "POSITIVE"
  },
  "invalidate": {
    "priceCrossBelow": 4985.0,
    "cvdTrend": "NEGATIVE"
  }
}
```

- **durationSeconds**: How long to monitor before giving up
- **upgrade**: Conditions that upgrade WAIT to TRADE
- **invalidate**: Conditions that invalidate the trade idea

**Typed Condition Fields:**

| Field | Type | Description |
|-------|-------|-------------|
| `priceCrossAbove` | Double | Trigger if price crosses above this level |
| `priceCrossBelow` | Double | Trigger if price crosses below this level |
| `volumeIncreasePercent` | Double | Trigger if volume increased by this % |
| `cvdTrend` | String | "POSITIVE", "NEGATIVE", "NEUTRAL" |

This makes WAIT **actionable** and **first-class** rather than just "log and forget".

---

## Units and Rounding Rules

### Pricing Model: Prices in Points, Offsets in Ticks

**We use a dual model for clarity:**
- **Prices** (entry, trigger levels): Decimal points (e.g., 4995.00, 5000.25)
- **Offsets** (stop loss, take profit, risk): Integer ticks (e.g., -25, +40)

This avoids confusion and makes calculations intuitive.

### Tick Size Calculation

```java
// Get instrument-specific tick size
double tickSize = instrument.getTickSize(); // e.g., 0.25 for ES

// Convert tick offset to price offset
double priceOffset = ticks * tickSize;

// Calculate absolute stop/take price
double stopLossPrice = entryPrice + (stopLossTicks * tickSize);
double takeProfitPrice = entryPrice + (takeProfitTicks * tickSize);

// Convert price to tick units (for logging/analysis)
int tickUnits = (int) Math.round(price / tickSize);
```

### Rounding Rules

1. **AI Price Output**: Prices are decimal points (e.g., 4995.0, 5000.25)
2. **Tick Offsets**: Always integers representing tick count (e.g., -25, +40)
3. **Execution Layer**: Applies tick size when placing orders
4. **Display**: Show both price points and tick offsets for clarity

### Example: ES Futures (Tick Size = 0.25)

```
AI Output for LONG entry:
  - Entry Price: 4995.0 (decimal points)
  - Stop Loss: offsetTicks = -25
  - Take Profit: offsetTicks = +35

Execution Calculation:
  - Stop Loss Price: 4995.0 + (-25 * 0.25) = 4988.75
  - Take Profit Price: 4995.0 + (35 * 0.25) = 5003.75

Risk Analysis:
  - Risk: 25 ticks = 6.25 points = $312.50 (ES)
  - Reward: 35 ticks = 8.75 points = $437.50 (ES)
  - R:R Ratio: 1.4:1

Display: Entry @ 4995.0 | SL @ 4988.75 (-25 ticks) | TP @ 5003.75 (+35 ticks)
```

### Why This Model?

**Separation of concerns:**
- **AI** focuses on trade logic and relative distances (offsets in ticks)
- **Execution layer** handles instrument-specific price calculations
- **Logging** shows both forms for analysis

**Benefits:**
- ✅ AI doesn't need to know tick sizes
- ✅ Easy to reason about relative distances (e.g., "risk 20 ticks")
- ✅ Clear separation between trade logic and execution mechanics
- ✅ Works across different instruments (ES, NQ, etc.)

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
// Use atomic compare-and-set for thread-safe state transitions
if (pending.state.compareAndSet(OrderState.PENDING, OrderState.FILLED)) {
    // Handle fill
} else if (pending.state.compareAndSet(OrderState.PENDING, OrderState.CANCELLED)) {
    // Handle cancellation
} else {
    // State already changed - ignore duplicate event
    log("Duplicate event for order " + orderId + ", current state: " + pending.state.get());
}
```

### Idempotency

**Problem:** Duplicate signals can cause duplicate orders

**Solution:** Use idempotency key

```java
// Idempotency key must be DETERMINISTIC, not random
// Use signalId + decisionVersion to prevent duplicate orders
String idempotencyKey = signal.getId() + "_" + decisionVersion;

// Check for existing order with same key
if (idempotencyKeys.containsValue(idempotencyKey)) {
    log("Duplicate signal detected, ignoring: " + idempotencyKey);
    return;
}

// Place order with idempotency key
placeOrder(plan, idempotencyKey);
idempotencyKeys.put(idempotencyKey, orderId);
```

---

## Example Scenarios

### Scenario 1: Pullback Entry (LIMIT)

**Signal:** Iceberg BUY detected @ 5000.0
**Context:** Price at 5002.0, VWAP at 4995.0, strong uptrend

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.85,
  "reasoning": "Strong institutional buying in uptrend",
  "instrument": "ES",
  "side": "LONG",
  "tickSize": 0.25,
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevels": ["VWAP 4995.0", "POC 4992.0"],
    "concerns": []
  },
  "constraintsUsed": {
    "maxRiskTicks": 25,
    "maxChaseTicks": 6,
    "maxSlippageTicks": 2
  },
  "strategy": {
    "entryIntent": "PULLBACK",
    "order": {
      "type": "LIMIT",
      "price": 4995.0,
      "tif": "GTC",
      "expiresInSeconds": 180,
      "fallback": "CANCEL"
    },
    "stopLoss": { "offsetTicks": -25 },
    "takeProfit": { "offsetTicks": 35 },
    "riskRewardRatio": 1.4
  },
  "monitorPlan": null
}
```

**Result:**
- LIMIT order placed at 4995.0 points
- Stop loss calculated: 4995.0 + (-25 * 0.25) = 4988.75 points
- Take profit calculated: 4995.0 + (35 * 0.25) = 5003.75 points
- Risk: 25 ticks = 6.25 points = $312.50 (ES)
- Reward: 35 ticks = 8.75 points = $437.50 (ES)
- If filled within 180s, position opened. If not, order cancelled.

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

// AIDecision.java (enhanced)
public static class AIDecision {
    public String signalId;
    public String instrument;          // ES, NQ, etc.
    public String side;                 // LONG, SHORT
    public double tickSize;            // Instrument tick size (e.g., 0.25 for ES)
    public String version;              // Decision version for idempotency
    public StrategistAction action;
    public double confidence;
    public String reasoning;
    public MarketAnalysis marketAnalysis;
    public Constraints constraintsUsed;
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

// OrderSpec.java
public static class OrderSpec {
    public String type;                    // MARKET/LIMIT/STOP_MARKET
    public Double price;                  // Entry price in decimal points (null for MARKET)
    public String tif;                    // IOC/GTC/DAY - time-in-force
    public Integer expiresInSeconds;       // Max wait time (null for MARKET)
    public String fallback;               // CANCEL/MARKET/REPRICE

    // Helper to check if has expiry
    public boolean hasExpiry() {
        return type != null && !type.equals("MARKET") && expiresInSeconds != null;
    }
}

// StopTakeLevel.java
public static class StopTakeLevel {
    public int offsetTicks;   // Offset from entry price in ticks
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
    public int durationSeconds;
    public ConditionSet upgrade;   // Upgrade conditions
    public ConditionSet invalidate; // Invalidate conditions

    public MonitorPlan() {
        this.upgrade = new ConditionSet();
        this.invalidate = new ConditionSet();
    }
}

// ConditionSet.java
public static class ConditionSet {
    public Double priceCrossAbove;
    public Double priceCrossBelow;
    public Double volumeIncreasePercent;
    public String cvdTrend;
}

// PendingOrder.java (new)
public static class PendingOrder {
    public String orderId;
    public String idempotencyKey;
    public long expiryTime;
    public String fallbackAction;
    public Strategy strategy;
    public final AtomicReference<OrderState> state =
        new AtomicReference<>(OrderState.PENDING);

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

        // Create DETERMINISTIC idempotency key (not random!)
        String idempotencyKey = signal.getId() + "_" + decision.version;

        // Check for duplicate with same key
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
        // Calculate absolute stop/take prices from offsets
        double stopLossPrice = strategy.order.price + (strategy.stopLoss.offsetTicks * tickSize);
        double takeProfitPrice = strategy.order.price + (strategy.takeProfit.offsetTicks * tickSize);

        int orderId = placeMarketOrder(signal.direction, stopLossPrice, takeProfitPrice);

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[MARKET] Order " + orderId + " placed - " + strategy.entryIntent);
        return true;
    }

    private boolean executeLimitEntry(Strategy strategy, Signal signal, int timeLimit,
                                     String fallback, String idempotencyKey) {
        // Calculate absolute stop/take prices from offsets
        double stopLossPrice = strategy.order.price + (strategy.stopLoss.offsetTicks * tickSize);
        double takeProfitPrice = strategy.order.price + (strategy.takeProfit.offsetTicks * tickSize);

        int orderId = placeLimitOrder(signal.direction, strategy.order.price,
                                      stopLossPrice, takeProfitPrice);

        long expiryTime = System.currentTimeMillis() + (timeLimit * 1000L);

        pendingOrders.put(orderId, new PendingOrder(
            String.valueOf(orderId),
            idempotencyKey,
            expiryTime,
            fallback,
            strategy
        ));

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[LIMIT] Order " + orderId + " placed @ " + strategy.order.price + " points, " +
            "expires in " + timeLimit + "s, fallback: " + fallback);
        return true;
    }

    private boolean executeStopMarketEntry(Strategy strategy, Signal signal, int timeLimit,
                                          String fallback, String idempotencyKey) {
        // Calculate absolute stop/take prices from offsets
        double stopLossPrice = strategy.order.price + (strategy.stopLoss.offsetTicks * tickSize);
        double takeProfitPrice = strategy.order.price + (strategy.takeProfit.offsetTicks * tickSize);

        int orderId = placeStopMarketOrder(signal.direction, strategy.order.price,
                                           stopLossPrice, takeProfitPrice);

        long expiryTime = System.currentTimeMillis() + (timeLimit * 1000L);

        pendingOrders.put(orderId, new PendingOrder(
            String.valueOf(orderId),
            idempotencyKey,
            expiryTime,
            fallback,
            strategy
        ));

        idempotencyKeys.put(orderId, idempotencyKey);
        log("[STOP_MARKET] Order " + orderId + " placed @ " + strategy.order.price + " points, " +
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
            decision.monitorPlan.durationSeconds * 1000L,
            market.volume
        );

        activeMonitors.put(monitorId, context);
        log("[MONITOR] Started monitoring " + monitorId + " for " +
            decision.monitorPlan.durationSeconds + "s");
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
        ConditionSet conditions = context.monitorPlan.upgrade;

        if (conditions.priceCrossAbove != null && market.price <= conditions.priceCrossAbove) {
            return false;
        }

        if (conditions.priceCrossBelow != null && market.price >= conditions.priceCrossBelow) {
            return false;
        }

        if (conditions.volumeIncreasePercent != null) {
            double increase = ((market.volume - context.startVolume) / context.startVolume) * 100;
            if (increase < conditions.volumeIncreasePercent) return false;
        }

        if (conditions.cvdTrend != null && !market.cvdTrend.equals(conditions.cvdTrend)) {
            return false;
        }

        return true;
    }

    private boolean checkInvalidateConditions(MonitorContext context, MarketData market) {
        ConditionSet conditions = context.monitorPlan.invalidate;

        if (conditions.priceCrossAbove != null && market.price > conditions.priceCrossAbove) {
            return true;
        }

        if (conditions.priceCrossBelow != null && market.price < conditions.priceCrossBelow) {
            return true;
        }

        if (conditions.cvdTrend != null && market.cvdTrend.equals(conditions.cvdTrend)) {
            return true;
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
                             MarketAnalysis marketAnalysis, long startTime, long expiryTime, double startVolume) {
            this.monitorId = monitorId;
            this.monitorPlan = monitorPlan;
            this.marketAnalysis = marketAnalysis;
            this.startTime = startTime;
            this.expiryTime = expiryTime;
            this.startVolume = startVolume;
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
