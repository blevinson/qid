# Position Management Settings Specification

## Overview

This document specifies UI-configurable fields for position management in the Qid trading system. Currently, several position management features exist in `AIOrderManager.java` but are not exposed in the settings panel.

## Current State

### Existing Code (Not in UI)

| Field | Location | Current Default | Description |
|-------|----------|-----------------|-------------|
| `breakEvenEnabled` | AIOrderManager:22 | `true` | Enable auto break-even |
| `breakEvenTicks` | AIOrderManager:24 | `3` | Ticks profit before moving to BE |
| `trailingStopEnabled` | AIOrderManager:23 | `false` | Enable trailing stop |
| `trailAmountTicks` | AIOrderManager:25 | `2` | Trailing distance in ticks |
| `maxPositions` | AIOrderManager:29 | `1` | Max concurrent positions |
| `maxDailyLoss` | AIOrderManager:30 | `500.0` | Daily loss limit in dollars |

### Existing UI Settings (OrderFlowStrategyEnhanced.java)

| Field | Line | Default | Description |
|-------|------|---------|-------------|
| `maxPosition` | 132 | `1` | Max concurrent positions |
| `dailyLossLimit` | 138 | `500.0` | Daily loss limit |
| `maxSlippageTicks` | 141 | `50` | Max price slippage |

## Proposed Settings

### 0. Account & Contract Settings (NEW - Priority: Critical)

These settings define the financial parameters for position sizing and risk calculation.

```
@Parameter(name = "Account Balance ($)")
private Double accountBalance = 10000.0;

@Parameter(name = "Tick Value ($)")
private Double tickValue = 12.50;

@Parameter(name = "Day Margin Per Contract ($)")
private Double dayMarginPerContract = 6845.0;

@Parameter(name = "Overnight Margin Per Contract ($)")
private Double overnightMarginPerContract = 12000.0;
```

**Purpose:** Configure account and contract-specific financial parameters.

**Margin Explained:**
- **Day Margin** - Collateral needed for intraday positions (released when closed)
- **Overnight Margin** - Higher collateral if holding past market close
- **You don't "pay" margin** - it's held as collateral, returned when position closes
- **Your actual loss** = Stop distance in ticks × tick value × contracts

**Example (ES Futures):**
```
Account: $10,000
Day Margin: $6,845 per contract
Tick Value: $12.50

Position: 1 contract, 30-tick stop
├─ Margin Required: $6,845 (you need this available)
├─ Max Risk: 30 × $12.50 = $375 (what you lose if stop hits)
└─ Buying Power Used: 68.5%
```

**Common Instrument Values:**

| Instrument | Tick Value | Day Margin | Overnight Margin |
|------------|------------|------------|------------------|
| ES (S&P 500) | $12.50 | $500-$6,845 | ~$12,000 |
| NQ (Nasdaq) | $5.00 | $500-$1,500 | ~$15,000 |
| YM (Dow) | $5.00 | $500-$2,500 | ~$10,000 |
| CL (Crude Oil) | $10.00 | $500-$2,000 | ~$6,000 |
| GC (Gold) | $10.00 | $500-$1,000 | ~$5,000 |

**UI Location:** Settings panel - Account section (top)

### 1. Risk/Reward Defaults

```
@Parameter(name = "Default Stop Loss (ticks)")
private Integer defaultStopLossTicks = 30;

@Parameter(name = "Default Take Profit (ticks)")
private Integer defaultTakeProfitTicks = 70;

@Parameter(name = "R:R Ratio Display")
private String rrRatioDisplay = "1:2.3";  // Calculated, read-only
```

**Purpose:** Define default SL/TP distances when AI doesn't provide specific levels.

**Location:** Settings panel - Risk Management section

### 2. Break-Even Controls

```
@Parameter(name = "Break-Even Enabled")
private Boolean breakEvenEnabled = true;

@Parameter(name = "Break-Even Trigger (ticks)")
private Integer breakEvenTicks = 3;

@Parameter(name = "Break-Even Offset (ticks)")
private Integer breakEvenOffset = 1;  // Stop moves to entry + 1 tick
```

**Purpose:** Configure automatic break-even behavior.

**Logic:**
- When price moves `breakEvenTicks` in profit direction
- Move stop loss to `entryPrice ± breakEvenOffset`

**UI Location:** Settings panel - Position Management section

### 3. Trailing Stop Controls

```
@Parameter(name = "Trailing Stop Enabled")
private Boolean trailingStopEnabled = false;

@Parameter(name = "Trail Distance (ticks)")
private Integer trailAmountTicks = 2;

@Parameter(name = "Trail Trigger (ticks)")
private Integer trailTriggerTicks = 10;  // Start trailing after X ticks profit
```

**Purpose:** Configure trailing stop behavior.

**Logic:**
- Trailing activates after `trailTriggerTicks` profit
- Stop trails by `trailAmountTicks` behind price
- Only moves in profit direction (never backward)

**UI Location:** Settings panel - Position Management section

### 4. Position Time Limits

```
@Parameter(name = "Max Hold Time (minutes)")
private Integer maxHoldTimeMinutes = 0;  // 0 = disabled

@Parameter(name = "Auto-Close at EOD")
private Boolean autoCloseEOD = false;

@Parameter(name = "EOD Close Time (HH:MM)")
private String eodCloseTime = "16:00";
```

**Purpose:** Time-based position management.

**Logic:**
- If `maxHoldTimeMinutes > 0`, close position after time limit
- If `autoCloseEOD = true`, flatten all positions at specified time

**UI Location:** Settings panel - Position Management section

### 5. Position Sizing

```
@Parameter(name = "Position Sizing Mode")
private String positionSizeMode = "FIXED";  // FIXED, RISK_BASED

@Parameter(name = "Fixed Contracts")
private Integer fixedContracts = 1;

@Parameter(name = "Risk Per Trade (%)")
private Double riskPerTradePercent = 1.0;

@Parameter(name = "Max Contracts")
private Integer maxContracts = 10;
```

**Purpose:** Configure how position size is calculated.

**Logic:**
- `FIXED`: Always use `fixedContracts`
- `RISK_BASED`: Calculate based on `riskPerTradePercent` and stop distance

**UI Location:** Settings panel - Risk Management section

### 6. Position Mode

```
@Parameter(name = "Position Mode")
private String positionMode = "SINGLE";  // SINGLE, HEDGE

@Parameter(name = "Allow Opposing Positions")
private Boolean allowOpposingPositions = false;
```

**Purpose:** Control multi-position behavior.

**Logic:**
- `SINGLE`: Only one position at a time (current behavior)
- `HEDGE`: Allow long and short simultaneously

**UI Location:** Settings panel - Position Management section

## UI Layout Proposal

```
┌─────────────────────────────────────────────────────────┐
│ Settings Panel                                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ ┌─ ACCOUNT & CONTRACT ────────────────────────────────┐ │
│ │ Account Balance ($):          [ 10,000 ] ▲▼        │ │
│ │ Tick Value ($):               [ 12.50  ] ▲▼        │ │
│ │ Day Margin/Contract ($):      [ 6,845  ] ▲▼        │ │
│ │ Overnight Margin/Contract ($):[ 12,000 ] ▲▼        │ │
│ │                                                       │ │
│ │ ── CALCULATED (read-only) ──                        │ │
│ │ Available for Trading:        $10,000               │ │
│ │ Max Contracts (Day):          1                     │ │
│ └───────────────────────────────────────────────────────┘ │
│                                                         │
│ ┌─ RISK MANAGEMENT ───────────────────────────────────┐ │
│ │ Default Stop Loss (ticks):    [  30  ] ▲▼          │ │
│ │ Default Take Profit (ticks):  [  70  ] ▲▼          │ │
│ │ R:R Ratio:                    1:2.3  (auto)        │ │
│ │                                                       │ │
│ │ ── RISK CALCULATOR ──                               │ │
│ │ Risk Per Contract:            $375.00               │ │
│ │ Target Per Contract:          $875.00               │ │
│ │                                                       │ │
│ │ Position Sizing:              [FIXED ▼]             │ │
│ │ Fixed Contracts:              [  1   ] ▲▼          │ │
│ │ Risk Per Trade (%):           [ 1.0  ] ▲▼          │ │
│ │   → Dollar Risk:              $100.00               │ │
│ │ Max Contracts:                [ 10   ] ▲▼          │ │
│ │                                                       │ │
│ │ Daily Loss Limit ($):         [ 500  ] ▲▼          │ │
│ │ Max Slippage (ticks):         [ 50   ] ▲▼          │ │
│ └───────────────────────────────────────────────────────┘ │
│                                                         │
│ ┌─ POSITION MANAGEMENT ───────────────────────────────┐ │
│ │ Max Positions:                [  1   ] ▲▼          │ │
│ │ Position Mode:                [SINGLE▼]             │ │
│ │                                                       │ │
│ │ [✓] Break-Even Enabled                              │ │
│ │     Trigger (ticks):          [  3   ] ▲▼          │ │
│ │     Offset (ticks):           [  1   ] ▲▼          │ │
│ │                                                       │ │
│ │ [ ] Trailing Stop Enabled                           │ │
│ │     Trail Distance (ticks):   [  2   ] ▲▼          │ │
│ │     Trail Trigger (ticks):    [ 10   ] ▲▼          │ │
│ │                                                       │ │
│ │ [ ] Max Hold Time Enabled                           │ │
│ │     Max Hold (minutes):       [  0   ] ▲▼          │ │
│ │                                                       │ │
│ │ [ ] Auto-Close at EOD                               │ │
│ │     Close Time:               [16:00 ]              │ │
│ └───────────────────────────────────────────────────────┘ │
│                                                         │
│              [Apply] [Reset Defaults]                   │
└─────────────────────────────────────────────────────────┘
```

## Trade Preview Dialog (Before Entry)

When AI decides to TAKE a signal, show a preview with calculated values:

```
┌─────────────────────────────────────────────────────────┐
│ ⚠️ TRADE PREVIEW                                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ SIGNAL: BULLISH ICEBERG                                 │
│ Direction: LONG                                         │
│ Score: 72/135                                           │
│                                                         │
│ ── ORDER DETAILS ──────────────────────────────────────│
│ Entry Price:     43250.00                               │
│ Stop Loss:       43220.00 (30 ticks below)             │
│ Take Profit:     43320.00 (70 ticks above)             │
│                                                         │
│ ── FINANCIAL IMPACT ───────────────────────────────────│
│ Contracts:       1                                      │
│ Margin Required: $6,845  (68.5% of account)            │
│ Max Risk:        $375    (if stop hits)                │
│ Max Profit:      $875    (if target hits)              │
│ R:R Ratio:       1:2.3                                 │
│                                                         │
│ ── ACCOUNT AFTER TRADE ────────────────────────────────│
│ If Stop Hits:    $9,625  (-$375)                       │
│ If Target Hits:  $10,875 (+$875)                       │
│                                                         │
│ ── VALIDATION ─────────────────────────────────────────│
│ ✅ Sufficient margin ($6,845 < $10,000)                │
│ ✅ Within daily loss limit ($375 < $500 remaining)     │
│ ✅ Max positions not reached (0/1)                     │
│                                                         │
│         [CONFIRM ENTRY]    [SKIP THIS SIGNAL]          │
└─────────────────────────────────────────────────────────┘
```

## Active Position Display

Real-time display of open position:

```
┌─────────────────────────────────────────────────────────┐
│ 📊 ACTIVE POSITION                                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ LONG 1 ES @ 43250.00                                    │
│ Duration: 5m 32s                                        │
│                                                         │
│ ┌─────────────┬─────────────┬─────────────┐           │
│ │   CURRENT   │   STOP      │   TARGET    │           │
│ │  43265.00   │  43220.00   │  43320.00   │           │
│ │   +15 ticks │  -30 ticks  │  +70 ticks  │           │
│ └─────────────┴─────────────┴─────────────┘           │
│                                                         │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ Unrealized P&L:  +$187.50  (+15 ticks)             │ │
│ │ Max Favorable:   +$250.00  (+20 ticks MFE)         │ │
│ │ Max Adverse:     -$62.50   (-5 ticks MAE)          │ │
│ │ Margin Used:     $6,845                          │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ Break-even at: 43253.00 (3 ticks profit)              │
│ Status: Waiting for break-even trigger                 │
│                                                         │
│              [MOVE TO BE] [CLOSE POSITION]             │
└─────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 0: Account & Contract Settings (Priority: Critical)
1. Add `accountBalance`, `tickValue`, `dayMarginPerContract`, `overnightMarginPerContract`
2. Add calculated fields: available for trading, max contracts
3. Add risk calculator display (risk per contract in dollars)
4. Validate margin before entry

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add @Parameter fields and UI
- `AIOrderManager.java` - Add margin validation in `executeEntry()`
- `SignalData.java` - Add account data from settings

**Validation Rules:**
```java
// Before entry, validate:
if (marginRequired > accountBalance) {
    reject("Insufficient margin: need $" + marginRequired + ", have $" + accountBalance);
}
if (riskDollars > dailyLossRemaining) {
    reject("Risk exceeds daily limit: $" + riskDollars + " > $" + dailyLossRemaining);
}
```

### Phase 1: Expose Existing Fields (Priority: High)
1. Add `breakEvenEnabled` to UI
2. Add `breakEvenTicks` to UI
3. Add `trailingStopEnabled` to UI
4. Add `trailAmountTicks` to UI

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add @Parameter fields and UI controls
- Sync values to `AIOrderManager` on initialization and change

### Phase 2: Risk/Reward Defaults (Priority: High)
1. Add `defaultStopLossTicks` and `defaultTakeProfitTicks`
2. Use in `createDefaultPlan()` when AI doesn't provide levels
3. Display R:R ratio

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add parameters and UI
- `AIInvestmentStrategist.java` - Use defaults in `createDefaultPlan()`

### Phase 3: Time Limits (Priority: Medium)
1. Add `maxHoldTimeMinutes`
2. Add `autoCloseEOD` and `eodCloseTime`
3. Implement time-based checks in `onPriceUpdate()`

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add parameters and UI
- `AIOrderManager.java` - Add time limit checks

### Phase 4: Position Sizing (Priority: Medium)
1. Add position sizing mode selector
2. Implement risk-based calculation
3. Add max contracts limit

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add parameters and UI
- `AIOrderManager.java` - Update `calculatePositionSize()`
- `SignalData.java` - Ensure account/risk data populated

### Phase 5: Position Mode (Priority: Low)
1. Add SINGLE/HEDGE mode selector
2. Update position limit logic for hedge mode
3. Handle opposing position scenarios

**Files to modify:**
- `OrderFlowStrategyEnhanced.java` - Add parameter and UI
- `AIOrderManager.java` - Update position limit checks

## Field Synchronization

Settings must sync to `AIOrderManager` at:
1. **Initialization** - In `initialize()` after AIOrderManager creation
2. **Change** - In UI change listeners
3. **Session Start** - When trading session begins

```java
private void syncPositionManagementSettings() {
    if (aiOrderManager != null) {
        aiOrderManager.breakEvenEnabled = breakEvenEnabled;
        aiOrderManager.breakEvenTicks = breakEvenTicks;
        aiOrderManager.trailingStopEnabled = trailingStopEnabled;
        aiOrderManager.trailAmountTicks = trailAmountTicks;
        aiOrderManager.maxPositions = maxPosition;
        aiOrderManager.maxDailyLoss = dailyLossLimit;
        aiOrderManager.maxPriceSlippageTicks = maxSlippageTicks;
    }
}
```

## Testing Checklist

- [ ] Break-even triggers at correct price level
- [ ] Break-even offset moves stop correctly
- [ ] Trailing stop follows price correctly
- [ ] Trailing only moves in profit direction
- [ ] Time limit closes position correctly
- [ ] EOD close works at specified time
- [ ] Position sizing calculates correctly
- [ ] Max contracts limit enforced
- [ ] Settings persist across restarts
- [ ] UI updates reflect in AIOrderManager

## Related Files

| File | Purpose |
|------|---------|
| `OrderFlowStrategyEnhanced.java` | Main strategy, UI, parameters |
| `AIOrderManager.java` | Order/position management logic |
| `ActivePosition.java` | Position data model |
| `AIInvestmentStrategist.java` | Trade plan generation |
| `SignalData.java` | Signal context data |

## Questions to Resolve

1. **Account balance source:** Manual entry vs. fetch from broker API?
2. **Margin values:** Hardcoded defaults vs. fetch from broker?
3. **Break-even offset:** Should stop move to exact entry or entry + 1 tick?
4. **Trailing trigger:** Start immediately or after X ticks profit?
5. **Time limits:** Soft warning or hard close?
6. **Position sizing:** Use account balance from where?
7. **Hedge mode:** How to handle conflicting signals?
8. **Trade preview:** Require confirmation or auto-execute?
9. **Multiple instruments:** Different tick values per symbol?

## Risk Calculation Reference

**Formulas:**

```java
// Risk per contract
double riskPerContract = stopLossTicks * tickValue;

// Risk for position
double positionRisk = riskPerContract * contracts;

// Margin required
double marginRequired = dayMarginPerContract * contracts;

// Buying power used
double buyingPowerPercent = (marginRequired / accountBalance) * 100;

// Contracts from risk percentage
int contractsFromRisk = (int) ((accountBalance * riskPercent / 100) / riskPerContract);

// R:R ratio
double rrRatio = (double) takeProfitTicks / stopLossTicks;
```

**Example (ES, 1 contract, 30-tick stop, 70-tick target):**
```
riskPerContract = 30 × $12.50 = $375
positionRisk = $375 × 1 = $375
marginRequired = $6,845 × 1 = $6,845
buyingPowerPercent = ($6,845 / $10,000) × 100 = 68.5%
rrRatio = 70 / 30 = 2.33 (1:2.3)
```
