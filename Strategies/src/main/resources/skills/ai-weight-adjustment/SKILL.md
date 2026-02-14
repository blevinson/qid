---
name: ai-weight-adjustment
description: AI-driven confluence weight adjustment for trading signals. Use when fine-tuning signal scoring sensitivity, adjusting iceberg/CVD/EMA/VWAP weights, or optimizing detection thresholds. AI Chat can view and modify weights within safety bounds.
license: Proprietary
compatibility: Bookmap L1 API 7.6.0.20, Qid Trading Strategy v2.1+
metadata:
  author: Qid Trading
  version: "1.0"
---

# AI Weight Adjustment Skill

This skill enables AI to dynamically adjust confluence scoring weights within safety bounds, allowing fine-tuning of signal detection sensitivity.

## Overview

The confluence scoring system uses 16 adjustable weights that determine how signals are scored. AI can view and modify these weights through the AI Chat interface or the Investment Strategist.

### When to Use

- Adjusting signal sensitivity based on market conditions
- Fine-tuning scoring after reviewing win rates
- Optimizing detection for specific instruments
- AI-driven threshold optimization

## Available Tools

### AI Chat Tools

The AI Chat has access to these weight-related tools:

| Tool | Description |
|------|-------------|
| `get_weights` | Get current confluence weight values |
| `adjust_weight` | Modify a specific weight (within bounds) |
| `get_thresholds` | View current detection thresholds |
| `adjust_threshold` | Modify detection thresholds |

### Investment Strategist

The AI Investment Strategist can adjust weights as part of its TAKE/SKIP decision response by including a `weightAdjustment` object.

## Weight Categories

### Iceberg Detection

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `icebergMax` | 40 | 20-60 | Max points for iceberg detection |
| `icebergMultiplier` | 2 | 1-4 | Points per iceberg order |

### CVD (Cumulative Volume Delta)

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `cvdAlignMax` | 25 | 10-40 | Bonus when CVD aligns with signal |
| `cvdDivergePenalty` | 30 | 15-50 | Penalty when CVD opposes signal |

### Volume Analysis

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `volumeProfileMax` | 20 | 10-30 | Max points for high volume node |
| `volumeImbalanceMax` | 10 | 5-20 | Max points for volume imbalance |

### EMA (Exponential Moving Averages)

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `emaAlignMax` | 20 | 10-30 | Bonus for full EMA alignment |
| `emaAlignPartial` | 10 | 5-15 | Bonus for partial alignment (2/3) |
| `emaDivergePenalty` | 15 | 8-25 | Penalty for full divergence |
| `emaDivergePartial` | 8 | 4-12 | Penalty for partial divergence |

### VWAP (Volume Weighted Average Price)

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `vwapAlign` | 10 | 5-15 | Bonus when aligned with VWAP |
| `vwapDiverge` | 5 | 2-10 | Penalty when against VWAP |

### Time of Day

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `timeOfDayMax` | 5 | 0-15 | Bonus during prime trading hours |
| `timeOfDaySecondary` | 2 | 0-8 | Bonus during secondary hours |

### DOM (Depth of Market)

| Weight | Default | Bounds | Description |
|--------|---------|--------|-------------|
| `domMax` | 10 | 5-20 | Max adjustment from DOM imbalance |

## Usage Examples

### Via AI Chat

**Example 1: View Current Weights**

```
User: What are the current confluence weights?

AI: I'll check the current weights for you.
[Calls get_weights tool]

Current Confluence Weights:
- Iceberg: max=40, per_order=2
- CVD: align_bonus=25, diverge_penalty=-30
- Volume Profile: max=20
- Volume Imbalance: max=10
- EMA: align=20 (partial=10), diverge=-15 (partial=-8)
- VWAP: align=10, diverge=-5
- Time of Day: prime=5, secondary=2
- DOM: max_adjustment=+/-10
MAX POSSIBLE SCORE: 130
```

**Example 2: Adjust a Weight**

```
User: Increase the iceberg max to 50 since we're seeing strong icebergs

AI: I'll adjust the icebergMax weight to 50.
[Calls adjust_weight tool with name="icebergMax", value=50]

✓ Weight adjusted: icebergMax = 50

This increases the maximum points from iceberg detection, making iceberg-heavy signals score higher.
```

**Example 3: Market Condition Adjustment**

```
User: We're in a volatile market, adjust weights to reduce false signals

AI: I'll increase the threshold and tighten scoring for volatile conditions.
[Calls adjust_weight tools]

Adjusted:
- cvdDivergePenalty: 30 → 40 (stricter CVD confirmation)
- emaDivergePenalty: 15 → 20 (require better trend alignment)
- minConfluenceScore: 70 → 85 (higher threshold)

These changes reduce signal frequency but improve quality in volatile conditions.
```

### Via Investment Strategist JSON

The AI Investment Strategist can include weight adjustments in its response:

```json
{
  "decision": "TAKE",
  "confidence": "HIGH",
  "thresholdAdjustment": {
    "minConfluenceScore": 75,
    "weightAdjustment": {
      "icebergMax": 45,
      "cvdAlignMax": 30,
      "reasoning": "Increased iceberg and CVD weights for trending market"
    }
  }
}
```

## Settings Panel

Weights can also be adjusted manually in the Bookmap settings panel:

1. Open Strategy Settings
2. Expand "Confluence Weights" section
3. Adjust individual weight spinners
4. Click "Reset Weights" to restore defaults

**Note:** If "AI Managed Weights" is enabled (default), AI can override manual settings. Disable this toggle for manual-only control.

## Safety Bounds

All weights have safety bounds to prevent extreme values:

- **Lower bounds**: Prevent weights from being too weak
- **Upper bounds**: Prevent weights from dominating scoring
- **Automatic clamping**: Values outside bounds are clamped

Example: Setting `icebergMax` to 100 would be clamped to 60.

## Best Practices

### When to Increase Weights

- **Strong market conditions** for that factor
- **High win rate** on signals with that factor
- **Low signal frequency** - need more signals

### When to Decrease Weights

- **Noisy/choppy markets** causing false signals
- **Low win rate** on signals with that factor
- **Too many signals** - need higher quality

### Recommended Approach

1. **Start with defaults** - run for 1-2 hours
2. **Review win rates** - identify weak/strong factors
3. **Small adjustments** - change 1-2 weights at a time
4. **Monitor impact** - check signal quality after changes
5. **Document changes** - note what worked/didn't

## Integration Points

### Code: ConfluenceWeights.java

```java
// Get weight value
int cvdWeight = confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX);

// Set weight value (clamped to bounds)
int actualValue = confluenceWeights.set(ConfluenceWeights.CVD_ALIGN_MAX, 35);

// Apply AI adjustment
WeightAdjustment adj = new WeightAdjustment();
adj.cvdAlignMax = 35;
adj.reasoning = "Increased for trending market";
confluenceWeights.applyAdjustments(adj);
```

### Code: AIToolsProvider.java

```java
// Tool: get_weights
case "get_weights" -> getWeights();

// Tool: adjust_weight
case "adjust_weight" -> adjustWeight(arguments);
```

## Troubleshooting

### "Weight adjustment ignored"

- **Cause**: `aiManagedWeights` toggle is disabled
- **Solution**: Enable "AI Managed Weights" in settings

### "Value clamped to bounds"

- **Cause**: Requested value outside safety bounds
- **Solution**: Check the bounds table above

### "No effect on signals"

- **Cause**: Weight change too small or other factors dominating
- **Solution**: Make larger adjustments or review confluence breakdown

## Related Skills

- `order-flow-philosophy` - Understanding order flow patterns
- `bookmap-indicators` - Developing Bookmap indicators
- See `META-INF/SKILL.md` for full trading system documentation
