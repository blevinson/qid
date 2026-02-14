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
│ ┌─ RISK MANAGEMENT ───────────────────────────────────┐ │
│ │ Default Stop Loss (ticks):    [  30  ] ▲▼          │ │
│ │ Default Take Profit (ticks):  [  70  ] ▲▼          │ │
│ │ R:R Ratio:                    1:2.3  (auto)        │ │
│ │                                                       │ │
│ │ Position Sizing:              [FIXED ▼]             │ │
│ │ Fixed Contracts:              [  1   ] ▲▼          │ │
│ │ Risk Per Trade (%):           [ 1.0  ] ▲▼          │ │
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

## Implementation Plan

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

1. **Break-even offset:** Should stop move to exact entry or entry + 1 tick?
2. **Trailing trigger:** Start immediately or after X ticks profit?
3. **Time limits:** Soft warning or hard close?
4. **Position sizing:** Use account balance from where?
5. **Hedge mode:** How to handle conflicting signals?
