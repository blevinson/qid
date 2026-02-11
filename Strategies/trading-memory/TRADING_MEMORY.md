# Trading Memory

## Overview

This file contains trading patterns, lessons learned, and best practices for the Qid AI Investment Strategist trading system.

**System:** Qid AI Investment Strategist
**Branch:** feature/memory-sessions-pattern
**Last Updated:** 2025-02-11

## Pattern Documentation

See `setups/` directory for detailed pattern documentation.

### Available Patterns

- **Bullish Breakout with Iceberg Orders** (`setups/bullish-breakout-iceberg.md`)
  - Iceberg orders on BID (5+ orders at same price)
  - CVD trending up (> +1000)
  - Price above VWAP
  - At least 2/3 EMAs bullish (9, 21, 50)

## Lessons Learned

See `lessons/` directory for lessons learned from trading sessions.

### Recent Lessons

Lessons are automatically generated after each trading session and organized by date:
- `lessons/2025/02/` - Lessons from February 2025

## Memory System

This memory system powers the AI Investment Strategist by:
1. **Pattern Recognition:** Search historical setups to find similar market conditions
2. **Decision Support:** Provide context for AI trading decisions
3. **Continuous Learning:** Auto-generate lessons from trading outcomes
4. **Performance Tracking:** Track win rates and profitability of patterns

## Adding New Patterns

To document a new trading pattern:
1. Create a new markdown file in `setups/` directory
2. Use the template from `bullish-breakout-iceberg.md`
3. Include: Pattern Definition, Historical Performance, Notes
4. Memory system will automatically index new patterns

## Reviewing Sessions

Session transcripts are stored in `sessions/` directory:
- Format: `YYYY-MM-DD-INSTRUMENT.jsonl`
- Contains: All signals, decisions, orders, and outcomes
- Use for: Post-trade analysis and strategy refinement

---

**Memory System Version:** 1.0
**Integration:** OrderFlowStrategyEnhanced with AI Investment Strategist
