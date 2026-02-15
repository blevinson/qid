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
    - Entry type: MARKET / LIMIT / STOP_MARKET
    - Entry price: May differ from signal
    - Entry reasoning: Why this approach
    - Time limits: How long to wait
    - Fallback plan: If conditions change
        ↓
    Order placed at AI's chosen price/strategy
```

**Benefits:**
- AI picks optimal entry price
- Strategic entry at key levels (VWAP, POC, DOM levels)
- Can wait for pullbacks or breakouts
- Better R:R through smarter entries
- Latency handled by strategic placement

---

## AI Response Schema

### Action Types

| Action | Description | When Used |
|--------|-------------|-----------|
| `TRADE` | Execute trading strategy immediately | Setup is valid, entry conditions met |
| `WAIT` | Monitor but don't place order yet | Interesting but waiting for confirmation |
| `PASS` | No interest in this signal | Signal doesn't meet criteria |

### Strategy Object

```json
{
  "action": "TRADE" | "WAIT" | "PASS",
  "confidence": 0.0-1.0,
  "reasoning": "Brief explanation of the decision",
  "strategy": {
    "entryType": "MARKET" | "LIMIT" | "STOP_MARKET",
    "entryPrice": 4995,
    "entryReasoning": "Why this entry type and price",
    "stopLoss": 4970,
    "takeProfit": 5030,
    "timeLimitSeconds": 300,
    "fallbackAction": "CANCEL" | "MARKET",
    "riskRewardRatio": 1.4
  },
  "marketAnalysis": {
    "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
    "keyLevel": "VWAP support at 4995",
    "concerns": "Low volume, approaching resistance"
  }
}
```

### Entry Types

| Entry Type | Use Case | AI Reasoning Example |
|------------|----------|---------------------|
| `MARKET` | Time-sensitive, must enter now | "Momentum accelerating, don't wait" |
| `LIMIT` | Wait for better price | "Pullback to VWAP offers 5 ticks better entry" |
| `STOP_MARKET` | Wait for confirmation | "Enter on breakout above resistance for confirmation" |

---

## Detailed Field Specifications

### `action` (Required)

- **TRADE**: Place order according to strategy
- **WAIT**: Don't place order, but log interest. Could trigger later if conditions change.
- **PASS**: No interest, signal is ignored.

### `confidence` (Required)

- 0.0 to 1.0 scale
- Higher confidence = more aggressive sizing (future feature)

### `reasoning` (Required)

- Brief explanation of the decision
- Used for logging and review

### `strategy.entryType` (Required if action=TRADE)

| Value | Behavior | Order Placed |
|-------|----------|--------------|
| `MARKET` | Immediate execution | Market order at current price |
| `LIMIT` | Wait for target price | Limit order at `entryPrice` |
| `STOP_MARKET` | Wait for trigger | Stop market with trigger at `entryPrice` |

### `strategy.entryPrice` (Required if entryType ≠ MARKET)

- Price where order should be placed
- **May differ from signal price**
- AI should reference key levels (VWAP, POC, DOM levels)
- Example: Signal at 5000, AI chooses entry at 4995 (VWAP pullback)

### `strategy.entryReasoning` (Required if action=TRADE)

- Why this entry type and price was chosen
- Should reference market structure
- Examples:
  - "Wait for pullback to VWAP (4995) for better R:R"
  - "Enter immediately - momentum accelerating, time-sensitive"
  - "Enter on breakout above 5002 for confirmation"

### `strategy.stopLoss` (Required if action=TRADE)

- Stop loss price in ticks
- AI should place below logical support levels

### `strategy.takeProfit` (Required if action=TRADE)

- Take profit price in ticks
- AI should reference resistance/target levels

### `strategy.timeLimitSeconds` (Required if entryType=LIMIT or STOP_MARKET)

- Maximum time to wait for fill
- After expiry: execute `fallbackAction`
- Typical values: 60-300 seconds

### `strategy.fallbackAction` (Required if entryType=LIMIT or STOP_MARKET)

| Value | Behavior |
|-------|----------|
| `CANCEL` | Cancel order, no trade |
| `MARKET` | Convert to market order, execute immediately |

### `strategy.riskRewardRatio` (Optional)

- Calculated R:R ratio
- Used for logging and analysis

---

## Example Scenarios

### Scenario 1: Pullback Entry

**Signal:** Iceberg BUY detected @ 5000
**Context:** Price at 5002, VWAP at 4995, strong uptrend

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.85,
  "reasoning": "Strong institutional buying in uptrend",
  "strategy": {
    "entryType": "LIMIT",
    "entryPrice": 4995,
    "entryReasoning": "Wait for pullback to VWAP for 7 tick better entry",
    "stopLoss": 4970,
    "takeProfit": 5030,
    "timeLimitSeconds": 180,
    "fallbackAction": "CANCEL",
    "riskRewardRatio": 1.4
  },
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevel": "VWAP at 4995 - strong support",
    "concerns": null
  }
}
```

**Result:** LIMIT order placed at 4995. If filled within 180s, position opened. If not, order cancelled.

---

### Scenario 2: Immediate Entry

**Signal:** Absorption completion @ 5000
**Context:** Price at 5000, momentum accelerating, volume surging

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.90,
  "reasoning": "Absorption complete, momentum accelerating - time critical",
  "strategy": {
    "entryType": "MARKET",
    "entryPrice": 5000,
    "entryReasoning": "Don't wait - setup is time-sensitive, enter now",
    "stopLoss": 4975,
    "takeProfit": 5050,
    "timeLimitSeconds": null,
    "fallbackAction": null,
    "riskRewardRatio": 2.0
  },
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevel": "Prior resistance at 4998 cleared",
    "concerns": null
  }
}
```

**Result:** MARKET order placed immediately.

---

### Scenario 3: Breakout Entry

**Signal:** Iceberg BUY detected @ 5000
**Context:** Price at 5000, strong resistance at 5002, not yet broken

**AI Response:**
```json
{
  "action": "TRADE",
  "confidence": 0.75,
  "reasoning": "Institutional buying at resistance, wait for breakout confirmation",
  "strategy": {
    "entryType": "STOP_MARKET",
    "entryPrice": 5003,
    "entryReasoning": "Enter on breakout above 5002 resistance for confirmation",
    "stopLoss": 4990,
    "takeProfit": 5030,
    "timeLimitSeconds": 300,
    "fallbackAction": "CANCEL",
    "riskRewardRatio": 1.8
  },
  "marketAnalysis": {
    "bias": "BULLISH",
    "keyLevel": "Resistance at 5002 - breakout target",
    "concerns": "False breakout risk if volume doesn't confirm"
  }
}
```

**Result:** STOP_MARKET order with trigger at 5003. If price breaks above, market order executes.

---

### Scenario 4: Wait for Conditions

**Signal:** Iceberg BUY detected @ 5000
**Context:** Price at 5000, but approaching major resistance at 5005, low volume

**AI Response:**
```json
{
  "action": "WAIT",
  "confidence": 0.60,
  "reasoning": "Institutional buying detected but conditions not ideal",
  "strategy": null,
  "marketAnalysis": {
    "bias": "NEUTRAL",
    "keyLevel": "Major resistance at 5005",
    "concerns": "Approaching resistance, volume below average"
  }
}
```

**Result:** No order placed. Signal logged for monitoring.

---

### Scenario 5: Pass

**Signal:** Iceberg BUY detected @ 5000
**Context:** Price at 5000, but bearish trend, CVD negative

**AI Response:**
```json
{
  "action": "PASS",
  "confidence": 0.30,
  "reasoning": "Counter-trend signal in bearish market - skip",
  "strategy": null,
  "marketAnalysis": {
    "bias": "BEARISH",
    "keyLevel": null,
    "concerns": "Signal against trend, CVD negative"
  }
}
```

**Result:** Signal ignored.

---

## Implementation Plan

### Phase 1: Data Model Updates

#### New Fields in `AIDecision` / `TradePlan`

```java
// AIInvestmentStrategist.java

// New action type enum
public enum StrategistAction {
    TRADE("TRADE", "Execute trading strategy"),
    WAIT("WAIT", "Monitor but don't trade yet"),
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

// Enhanced TradePlan for strategist mode
public static class TradePlan {
    // Existing fields
    public String orderType = "BUY";
    public String executionType = "MARKET";
    public Integer entryPrice;
    public Integer stopLossPrice;
    public Integer takeProfitPrice;

    // New strategist fields
    public String entryReasoning;           // Why this entry type/price
    public Integer timeLimitSeconds;        // Max wait time for LIMIT/STOP
    public String fallbackAction;           // CANCEL or MARKET
    public Double riskRewardRatio;          // Calculated R:R

    // Market analysis
    public String marketBias;               // BULLISH/BEARISH/NEUTRAL
    public String keyLevelIdentified;       // Key level referenced
    public String concerns;                 // Any concerns noted
}

// New action field
public StrategistAction strategistAction = StrategistAction.PASS;
```

### Phase 2: Prompt Updates

#### New Prompt Structure

```
You are a trading strategist. A market signal has been detected.

📊 SIGNAL INTEL:
- Type: {signal.type} at {signal.price}
- Score: {signal.score}/100
- Direction: {signal.direction}

📈 MARKET CONTEXT:
- Current Price: {current.price}
- Trend: {market.trend}
- CVD: {market.cvd} ({market.cvdTrend})
- Momentum: {market.momentum}

📍 KEY LEVELS:
- VWAP: {vwap}
- POC: {poc}
- DOM Support: {dom.support}
- DOM Resistance: {dom.resistance}

⏱️ LATENCY: ~{latency}ms average API response time

YOUR ROLE: Analyze this market intelligence and decide:
1. Is this a trading opportunity? (TRADE/WAIT/PASS)
2. If TRADE: What's the optimal entry strategy?
   - MARKET: Enter immediately (time-sensitive)
   - LIMIT: Wait for better price at key level
   - STOP_MARKET: Wait for breakout confirmation

3. Entry price: May differ from signal price
4. Stop loss and take profit: Place at logical levels
5. For LIMIT/STOP: How long to wait? What's the fallback?

RESPOND WITH JSON ONLY:
{
  "action": "TRADE" | "WAIT" | "PASS",
  "confidence": 0.0-1.0,
  "reasoning": "Brief explanation",
  "strategy": {
    "entryType": "MARKET" | "LIMIT" | "STOP_MARKET",
    "entryPrice": N,
    "entryReasoning": "Why this entry approach",
    "stopLoss": N,
    "takeProfit": N,
    "timeLimitSeconds": N,
    "fallbackAction": "CANCEL" | "MARKET",
    "riskRewardRatio": N.N
  },
  "marketAnalysis": {
    "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
    "keyLevel": "Key level referenced",
    "concerns": "Any concerns or null"
  }
}
```

### Phase 3: Execution Logic

#### `AIOrderManager.executeStrategicEntry()`

```java
public boolean executeStrategicEntry(AIDecision decision, Signal signal) {
    TradePlan plan = decision.plan;

    if (plan.executionType.equals("MARKET")) {
        // Immediate execution
        return executeMarketEntry(plan, signal);

    } else if (plan.executionType.equals("LIMIT")) {
        // Place limit order with time tracking
        return executeLimitEntry(plan, signal, plan.timeLimitSeconds, plan.fallbackAction);

    } else if (plan.executionType.equals("STOP_MARKET")) {
        // Place stop market with time tracking
        return executeStopMarketEntry(plan, signal, plan.timeLimitSeconds, plan.fallbackAction);
    }

    return false;
}

private boolean executeLimitEntry(TradePlan plan, Signal signal, int timeLimit, String fallback) {
    // Place limit order at plan.entryPrice
    int orderId = placeLimitOrder(plan.orderType, plan.entryPrice);

    // Track for time limit
    pendingOrders.put(orderId, new PendingOrder(
        orderId,
        System.currentTimeMillis() + (timeLimit * 1000),
        fallback,
        plan
    ));

    return true;
}

// Called periodically to check time limits
public void checkPendingOrderTimeouts() {
    long now = System.currentTimeMillis();
    for (PendingOrder pending : pendingOrders.values()) {
        if (now > pending.expiryTime) {
            if (pending.fallbackAction.equals("MARKET")) {
                cancelOrder(pending.orderId);
                executeMarketEntry(pending.plan, null);
            } else {
                cancelOrder(pending.orderId);
                log("Order " + pending.orderId + " cancelled - time limit expired");
            }
            pendingOrders.remove(pending.orderId);
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
```

#### UI Controls

- Checkbox: "Strategist Mode" (enabled by default)
- Checkbox: "Log Entry Reasoning"
- Performance display: Entry success rates by type (MARKET/LIMIT/STOP)

---

## Performance Tracking

### Metrics to Track

| Metric | Description |
|--------|-------------|
| Entry Type Distribution | % MARKET vs LIMIT vs STOP_MARKET |
| Limit Fill Rate | % of LIMIT orders that filled |
| Stop Fill Rate | % of STOP_MARKET orders that triggered |
| Entry Price vs Signal | Average ticks better/worse than signal |
| R:R by Entry Type | Average R:R achieved by entry type |
| Timeout Rate | % of orders cancelled due to time limit |

### Logging Format

```
[STRATEGIST] Signal: ICEBERG_BUY @ 5000 | Action: TRADE
[STRATEGIST] Entry: LIMIT @ 4995 | Reasoning: Pullback to VWAP
[STRATEGIST] SL: 4970 | TP: 5030 | R:R: 1.4
[STRATEGIST] Time limit: 180s | Fallback: CANCEL
...
[STRATEGIST] LIMIT filled @ 4995 after 45s
```

---

## Migration Path

### Step 1: Add New Fields (Non-Breaking)

- Add `StrategistAction` enum
- Add new fields to `TradePlan`
- Add new fields to `AIDecision`
- Keep existing `shouldTake` for backward compatibility

### Step 2: Update Prompts

- Switch from TAKE/SKIP to TRADE/WAIT/PASS
- Request full strategy object
- Request entry reasoning

### Step 3: Update Execution

- Add `executeStrategicEntry()` method
- Add pending order tracking for LIMIT/STOP
- Add time limit checking

### Step 4: Deprecate Old Fields

- Mark `shouldTake` as deprecated
- Update all references to use `strategistAction`
- Remove old TAKE/SKIP logic

---

## Testing Checklist

- [ ] AI correctly parses TRADE/WAIT/PASS actions
- [ ] MARKET orders execute immediately
- [ ] LIMIT orders placed at correct price
- [ ] STOP_MARKET orders trigger correctly
- [ ] Time limits enforced correctly
- [ ] Fallback actions execute (CANCEL/MARKET)
- [ ] Entry reasoning logged correctly
- [ ] Performance metrics tracked
- [ ] UI displays strategist mode controls

---

## Future Enhancements

1. **Multi-Level Entries**: AI could stage entries at multiple price levels
2. **Dynamic Time Limits**: AI adjusts time limits based on market conditions
3. **Entry Type Learning**: Track which entry types work best in which conditions
4. **Wait → Trade Transitions**: WAIT signals that later become TRADE signals
5. **Alternative Strategies**: AI proposes backup strategy if primary fails
