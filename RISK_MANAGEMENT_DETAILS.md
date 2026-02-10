# Risk Management System - Detailed Design

## Core Principles

1. **Never risk more than 1% of account per trade**
2. **Always use 1:2 or better risk-reward**
3. **Position size calculated from stop distance**
4. **Daily loss limit protects account**
5. **No exceptions**

---

## Risk-Reward Ratio (R:R)

### The Setup:
```
Entry Price: 5980.00
Stop Loss:   5977.00 (3 ticks below)
Risk:        3 ticks = $150 (on ES)

Take Profit: 5986.00 (6 ticks above)
Reward:      6 ticks = $300 (on ES)

Risk-Reward: 1:2 (risk $150 to make $300)
```

### Why 1:2?
- Win 50% of trades → Break even
- Win 40% of trades → Small profit
- Win 33% of trades → Small loss
- **Target**: Win 45-50% → Profitable!

### Flexible R:R:
- **Conservative**: 1:1.5 (3 ticks risk, 4.5 ticks reward)
- **Standard**: 1:2 (3 ticks risk, 6 ticks reward)
- **Aggressive**: 1:3 (3 ticks risk, 9 ticks reward)

**Configurable per strategy preferences**

---

## Position Sizing Formula

### The Math:
```
Step 1: Calculate $ Risk Per Trade
  Account Size: $10,000
  Risk %:       1%
  $ Risk:       $10,000 × 0.01 = $100

Step 2: Calculate Stop Distance in $
  Entry:  5980.00
  Stop:   5977.00
  Ticks:  3 ticks
  $ Risk/Tick: $50 (ES futures)
  $ Stop: 3 × $50 = $150

Step 3: Calculate Contracts
  $ Risk Per Trade = $100
  $ Stop per contract = $150
  Contracts = $100 / $150 = 0.67

Step 4: Round to MINIMUM (can't trade fractions)
  → 1 contract (minimum)
  → Risk is now $150 (1.5% of account)

Step 5: Apply MAX Limits
  Min contracts: 1
  Max contracts: 3
  Final answer: 1 contract
```

### Example 2: Larger Account
```
Account: $50,000
Risk %:  1%
$ Risk:  $500

Stop: 3 ticks = $150 per contract
Contracts: $500 / $150 = 3.33

→ Round down: 3 contracts
→ Total risk: 3 × $150 = $450 (0.9% of account)
✅ Within 1% limit
```

### Example 3: Tighter Stop
```
Account: $10,000
Risk %:  1%
$ Risk:  $100

Stop: 2 ticks = $100 per contract
Contracts: $100 / $100 = 1 contract

→ Total risk: $100 (exactly 1%)
✅ Perfect sizing
```

### Example 4: Wider Stop
```
Account: $10,000
Risk %:  1%
$ Risk:  $100

Stop: 6 ticks = $300 per contract
Contracts: $100 / $300 = 0.33

→ Round up to minimum: 1 contract
→ Total risk: $300 (3% of account)
❌ EXCEEDS 1% LIMIT!

Action: SKIP TRADE or REDUCE POSITION
→ Don't take the trade, or
→ Tighten stop to 3 ticks
```

---

## Position Sizing Rules

### Hard Limits:
```
MINIMUM CONTRACTS: 1
MAXIMUM CONTRACTS: 3
MAX RISK PER TRADE: 1% of account
MAX DAILY LOSS: $500 (5% of $10k account)
```

### Decision Tree:
```
Is stop distance > 5 ticks?
  YES → Skip trade (too much risk)
  NO  → Continue

Calculate contracts based on 1% risk
Is result < 1 contract?
  YES → Use 1 contract minimum
  NO  → Continue

Is result > 3 contracts?
  YES → Cap at 3 contracts
  NO  → Use calculated size

Is total risk > 1% of account?
  YES → Skip trade or reduce size
  NO  → TAKE THE TRADE
```

---

## Daily Loss Limits

### Hard Stop:
```
Account: $10,000
Daily Loss Limit: $500 (5%)

Current P&L: -$450
Next trade risk: $150

Can I trade?
- Potential loss: -$450 + (-$150) = -$600
- Exceeds $500 limit
→ ❌ STOP TRADING FOR TODAY
```

### Warning Level:
```
Account: $10,000
Daily Loss Limit: $500
Warning Level: $350 (3.5%)

Current P&L: -$300

⚠️ WARNING: Approaching daily loss limit!
- Reduce position size by 50%
- Only take A+ setups (score ≥ 70)
```

### Reset:
```
New Day → Reset P&L to $0
Weekly reset (optional)
Monthly reset (optional)
```

---

## Drawdown Protection

### Levels:
```
Account Peak: $10,500
Current:      $10,000
Drawdown:     $500 (4.8%)

✅ GREEN: < 5% → Normal trading
⚠️ YELLOW: 5-10% → Reduce size 50%
🔴 RED: > 10% → STOP TRADING
```

### Recovery:
```
Hit 10% drawdown → PAUSE
Review recent trades
Analyze what went wrong
Adjust strategy parameters
Resume when confident
```

---

## Volatility Adjustments

### High Volatility (ATR > 1.0):
```
Market: Fast, large moves
Problem: Wide stops required

Solution:
  - Double stop distance (6 ticks instead of 3)
  - Keep target at 1:2 ratio (12 ticks)
  - Reduce position size by 50%
  - Require higher confluence (60 instead of 50)

Example:
  Normal: 3 tick stop, 6 tick target, 2 contracts
  High Vol: 6 tick stop, 12 tick target, 1 contract
  Same $ risk, better fills in fast market
```

### Low Volatility (ATR < 0.25):
```
Market: Slow, small moves
Problem: Tight stops, small targets

Solution:
  - Normal stops (3 ticks)
  - Normal targets (6 ticks)
  - Normal position size
  - Lower confluence (40 instead of 50)

Example:
  Normal: 3 tick stop, 6 tick target, 2 contracts
  Low Vol: Same (market is quiet, trade normally)
```

---

## Trade Examples

### Example 1: Perfect Setup
```
Account: $10,000
Risk: 1% = $100

Signal: LONG at 5980
Score: 65 (good confluence)
Trend: BULLISH ✅

Stop Loss: 5977 (-3 ticks = $150)
Take Profit: 5986 (+6 ticks = $300)

Position Sizing:
  $ Risk: $100
  $ Stop: $150 per contract
  Contracts: $100 / $150 = 0.67
  → Round to minimum: 1 contract
  → Actual risk: $150 (1.5%)

Decision: TAKE THE TRADE
  - Slightly above 1% but acceptable
  - Good confluence (score 65)
  - Strong trend alignment
```

### Example 2: Skip Trade
```
Account: $10,000
Risk: 1% = $100

Signal: SHORT at 5985
Score: 45 (below threshold 50)
Trend: NEUTRAL

Stop Loss: 5991 (+6 ticks = $300)
Take Profit: 5973 (-12 ticks = $600)

Position Sizing:
  $ Risk: $100
  $ Stop: $300 per contract
  Contracts: $100 / $300 = 0.33
  → Round to 1 contract
  → Actual risk: $300 (3%!)

Decision: ❌ SKIP THE TRADE
  - Risk too high (3% vs 1% limit)
  - Score below threshold
  - No clear trend
  - Wide stop required
```

### Example 3: Reduce Size
```
Account: $10,000
Risk: 1% = $100

Signal: LONG at 5980
Score: 70 (excellent)
Trend: BULLISH ✅

Stop Loss: 5974 (-6 ticks = $300)
Take Profit: 5992 (+12 ticks = $600)

Position Sizing:
  $ Risk: $100
  $ Stop: $300 per contract
  Contracts: 0.33 → 1 contract minimum
  → But risk is $300 (3%)

Options:
  A) Skip trade
  B) Tighten stop to 3 ticks
  C) Accept higher risk (NOT RECOMMENDED)

Best Choice: B
  - Move stop to 5977 (3 ticks)
  - Risk: $150 (1.5%)
  - Still within acceptable range
  - Take trade with tighter stop
```

### Example 4: Multiple Contracts
```
Account: $50,000
Risk: 1% = $500

Signal: SHORT at 5985
Score: 75 (excellent)
Trend: BEARISH ✅

Stop Loss: 5991 (+6 ticks = $300)
Take Profit: 5973 (-12 ticks = $600)

Position Sizing:
  $ Risk: $500
  $ Stop: $300 per contract
  Contracts: $500 / $300 = 1.67
  → Round to 2 contracts
  → Total risk: 2 × $300 = $600 (1.2%)

Decision: TAKE THE TRADE
  - 2 contracts
  - Risk: $600 (1.2%)
  - Slightly above 1% but acceptable
  - Excellent confluence
```

---

## Summary Checklist

Before Every Trade:
- [ ] Score ≥ threshold (50)?
- [ ] Trend aligned?
- [ ] Risk calculated correctly?
- [ ] Position size ≤ 1% risk?
- [ ] Stop loss set?
- [ ] Take profit set (1:2 or better)?
- [ ] Under daily loss limit?
- [ ] Under max drawdown limit?
- [ ] Volatility adjustment applied?

If NO to any → SKIP THE TRADE

---

## Configuration Parameters

```java
@Parameter(name = "Account Size ($)")
private Double accountSize = 10000.0;

@Parameter(name = "Risk Per Trade (%)")
private Double riskPerTrade = 1.0;

@Parameter(name = "Stop Loss Ticks")
private Integer stopLossTicks = 3;

@Parameter(name = "Take Profit Ticks")
private Integer takeProfitTicks = 6;

@Parameter(name = "Min Contracts")
private Integer minContracts = 1;

@Parameter(name = "Max Contracts")
private Integer maxContracts = 3;

@Parameter(name = "Daily Loss Limit ($)")
private Double dailyLossLimit = 500.0;

@Parameter(name = "Max Drawdown (%)")
private Double maxDrawdownPercent = 10.0;
```

---

## Key Takeaways

1. **Risk is calculated FIRST**, then position size
2. **Never exceed 1% risk** (except min contract rounding)
3. **Always use 1:2 R:R** or better
4. **Skip trades if stop is too wide**
5. **Daily loss limit protects account**
6. **Volatility adjustments reduce risk**
7. **Position size = Account Risk / Stop Distance**
8. **Round properly, respect min/max limits**

**This system keeps you in the game long enough to profit!**
