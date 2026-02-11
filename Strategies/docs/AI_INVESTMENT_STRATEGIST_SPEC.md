# AI Investment Strategizer - Specification

**Document Version:** 1.0
**Date:** 2025-02-11
**Status:** Design Specification
**Branch:** feature/memory-sessions-pattern

---

## Executive Summary

This document specifies the **AI Investment Strategizer** - an intelligent trading agent that proactively searches for setups, places strategic pending orders, and learns from outcomes. Unlike "speed daemon" HFT systems, this is an **investment strategist** that uses observation, data analysis, and intelligence to find high-probability setups.

**Core Philosophy:**
- **NOT** a reactive signal follower (wait for signal → execute)
- **IS** a proactive setup finder (analyze market → find setup → place strategic order)
- **Speed not critical** (we use buy stops, not market orders)
- **Intelligence is everything** (memory-driven decision making)

**Key Benefits:**
- Proactive setup detection using historical pattern memory
- Strategic entry levels (buy stops, not market orders)
- Memory-driven SL/TP optimization
- Continuous learning from session transcripts
- AI searches memory before acting

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Investment Strategizer vs Speed Daemon](#investment-strategizer-vs-speed-daemon)
3. [Architecture](#architecture)
4. [Setup Detection Process](#setup-detection-process)
5. [Order Placement Strategy](#order-placement-strategy)
6. [Memory Integration](#memory-integration)
7. [Session Management](#session-management)
8. [Data Structures](#data-structures)
9. [Implementation Plan](#implementation-plan)
10. [Use Cases](#use-cases)

---

## System Overview

### What It Does

**The AI Investment Strategizer is an intelligence layer ON TOP OF your existing OrderFlowStrategyEnhanced system:**

```
┌─────────────────────────────────────────────────────────────────┐
│          EXISTING OrderFlowStrategyEnhanced (Already Built)     │
│  ✅ Iceberg detection (MBO order tracking)                      │
│  ✅ Spoofing detection (large orders cancelled)                 │
│  ✅ Absorption detection (large orders holding)                 │
│  ✅ Confluence scoring (13-factor system, 0-135 points)         │
│  ✅ CVD tracking & divergence detection                         │
│  ✅ VWAP calculation & alignment                                │
│  ✅ EMA 9/21/50 trend alignment                                 │
│  ✅ Volume profile & POC analysis                               │
│  ✅ Volume imbalance detection                                  │
│  ✅ ATR calculation & volatility                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 AI INVESTMENT STRATEGIST (New Layer)            │
│                                                                  │
│  1. RECEIVES: Confluence scores & signals from existing system  │
│  2. SEARCHES: Memory for similar historical setups              │
│  3. ANALYZES: What happened last time we saw this pattern?      │
│  4. PLANS: Optimal entry/exit based on historical outcomes      │
│  5. DECIDES: Take this setup? (intelligence, not just score)    │
│  6. EXECUTES: Strategic BUY STOP orders (not market orders)     │
│  7. LEARNS: Logs outcomes to improve future decisions           │
└─────────────────────────────────────────────────────────────────┘
```

### Key Integration Points

**The AI LEVERAGES existing systems:**

1. **Signal Detection** (Already Built)
   - OrderFlowStrategyEnhanced detects iceberg/spoofing/absorption
   - Confluence scoring system (0-135 points)
   - All indicators working (CVD, VWAP, EMAs, Volume Profile)
   - **AI Enhancement**: Searches memory for similar setups before deciding

2. **Order Management** (Phase 2 - Already Spec'd)
   - Bracket orders (SL/TP)
   - Breakeven triggers
   - Position tracking
   - Risk limits
   - **AI Enhancement**: Calculates optimal SL/TP from historical data

3. **Market Data** (Already Flowing)
   - MBO data (iceberg orders)
   - Trade data (absorption, delta)
   - DOM data (volume imbalance)
   - **AI Enhancement**: Pattern recognition + historical context

### Key Characteristics

| **Aspect** | **Speed Daemon (HFT)** | **Investment Strategizer (Ours)** |
|------------|------------------------|-----------------------------------|
| **Trigger** | Signal fires → React immediately | AI searches → Finds setup proactively |
| **Speed** | Microseconds matter | Intelligence matters |
| **Entry Type** | Market orders (immediate fill) | Buy stops (wait for breakout) |
| **Decision Basis** | Signal score | Historical patterns + context |
| **SL/TP** | Fixed ratios (1:2) | Memory-driven (based on similar setups) |
| **Learning** | Minimal/None | Continuous from transcripts |
| **Memory** | None required | Essential (searches before acting) |

---

## Investment Strategizer vs Speed Daemon

### Scenario: BTC at $43,200 with Iceberg Orders

**Speed Daemon Approach:**
```
10:15:00.500 - ICEBERG signal detected at 43200
10:15:00.501 - Check score: 72/100 ✓
10:15:00.502 - Score > threshold? Yes
10:15:00.503 - SEND MARKET ORDER BUY
10:15:00.550 - Filled at 43201 (slippage +1)
10:15:00.551 - Set SL: 43171, TP: 43261 (fixed 1:2)
→ RACE AGAINST TIME
```

**Investment Strategist Approach:**
```
10:15:00 - Iceberg detected at 43200
10:15:01 - Search memory: "Similar setups?"
10:15:02 - Found: 12 bullish iceberg + VWAP setups since Jan
10:15:03 - Win rate: 78% when entered above 43220
10:15:04 - Last setup (Feb 8): Entry 43250, won in 45 min
10:15:05 - Optimal SL: below 43195 (swing low)
10:15:06 - Optimal TP: 43400 (based on 1:2.3 avg R:R)
10:15:07 - PLACE BUY STOP @ 43250
10:15:08 - Wait for fill (no rush, strategic entry)
→ INTELLIGENT ANALYSIS
```

### Why This Works

**Speed Daemon Issues:**
- Chases signals (FOMO)
- Fixed SL/TP (not adaptive)
- No learning (repeats mistakes)
- Slippage kills profits
- Overtrades (every signal)

**Investment Strategizer Advantages:**
- Waits for strategic levels (patience)
- Memory-based SL/TP (adaptive)
- Learns continuously (improves)
- No slippage (pending orders)
- Selective (only high-probability setups)

---

## Integration with Existing OrderFlowStrategyEnhanced

### What's Already Built (You Have This Now)

**File:** `OrderFlowStrategyEnhanced.java`

```java
// ========== ALREADY IMPLEMENTED ==========

// 1. Pattern Detection
private void onIcebergDetected(boolean isBid, int price, int totalSize) {
    // Detects iceberg orders (5+ orders at same price)
    // Currently: Shows signal, logs to console
    // AI Enhancement: Search memory for similar setups
}

// 2. Confluence Scoring (13-factor system)
private int calculateConfluenceScore(boolean isBid, int price, int totalSize) {
    // Returns: 0-135 points
    // Factors: Iceberg (40) + CVD (25) + Volume Profile (20) +
    //          Imbalance (10) + EMAs (15) + VWAP (10) + Time (10) + Size (5)
    // Already implemented and working!
}

// 3. Indicators (All working)
private CVDCalculator cvdCalculator;           // CVD tracking
private VWAPCalculator vwapCalculator;         // VWAP
private EMA ema9, ema21, ema50;                // Trend alignment
private VolumeProfileCalculator volumeProfile; // POC, value area
private ATRCalculator atrCalculator;           // Volatility

// 4. Signal Data (Complete context)
SignalData signal = new SignalData();
signal.score = 72;  // Confluence score
signal.thresholdPassed = true;
signal.scoreBreakdown.icebergPoints = 25;
signal.scoreBreakdown.cvdPoints = 15;
signal.scoreBreakdown.trendPoints = 10;
// ... all 13 factors
```

### What AI Adds (Intelligence Layer)

```java
// ========== NEW AI LAYER ==========

// 5. Memory Search (NEW)
private TradingMemoryService memoryService;

private void onIcebergDetected(boolean isBid, int price, int totalSize) {
    // OLD: Just log signal
    log("ICEBERG: " + price + ", score: " + score);

    // NEW: Search memory first
    String query = String.format(
        "Bullish iceberg VWAP support %s %s CVD %s",
        instrument, timeOfDay, cvdTrend
    );

    List<HistoricalSetup> similar = memoryService.search(query, 10);

    if (!similar.isEmpty()) {
        double winRate = calculateWinRate(similar);
        log("Found %d similar setups, %.0f%% win rate",
            similar.size(), winRate * 100);

        if (winRate >= 0.70) {
            // High confidence - plan strategic entry
            planStrategicEntry(similar, price, isBid);
        } else {
            // Low historical win rate - skip
            log("SKIP: Low historical win rate (%.0f%%)", winRate * 100);
        }
    }
}

// 6. Strategic Planning (NEW)
private void planStrategicEntry(List<HistoricalSetup> similar, int price, boolean isBid) {
    // Calculate optimal levels from history
    int avgStopTicks = findOptimalStop(similar);
    double avgRiskReward = findAverageRR(similar);

    // Strategic entry (BUY STOP, not market)
    int entry = price + 50;  // Above resistance
    int stopLoss = price - avgStopTicks;
    int takeProfit = (int)(entry + (avgStopTicks * avgRiskReward));

    // Place order with reasoning
    String reasoning = String.format(
        "Based on %d similar setups: %.0f%% win rate, " +
        "optimal entry above resistance, avg R:R %.1f:1",
        similar.size(), calculateWinRate(similar) * 100, avgRiskReward
    );

    placeBuyStop(entry, stopLoss, takeProfit, reasoning);

    // Log to transcript
    transcriptWriter.logSetup(
        setupType: "BULLISH_BREAKOUT",
        score: signal.score,
        similarSetups: similar.size(),
        historicalWinRate: calculateWinRate(similar),
        plan: reasoning
    );
}

// 7. Continuous Learning (NEW)
private void onPositionClosed(String tradeId, boolean won, double pnl) {
    // OLD: Just log P&L
    log("Trade closed: " + (won ? "WON" : "LOST") + ", P&L: $" + pnl);

    // NEW: Learn from outcome
    transcriptWriter.logOutcome(tradeId, won, pnl);

    // Update memory with lessons learned
    if (won) {
        String lesson = String.format(
            "Bullish iceberg setup won again. " +
            "Entry above resistance working. %d/%10 wins this month.",
            monthlyWins, monthlyTotal
        );
        memoryService.addLesson(lesson);
    } else {
        String lesson = String.format(
            "Bullish iceberg setup lost. " +
            "Possible reasons: %s. Consider wider SL next time.",
            analyzeLossReason(tradeId)
        );
        memoryService.addLesson(lesson);
    }
}
```

### Integration Flow

```
OrderFlowStrategyEnhanced.detectIceberg()
    ↓
    Calculates: Confluence score = 72/135
    ↓
    Calls: AI Strategizer.shouldWeTakeThisSetup(score, context)?
    ↓
    AI Searches: Memory for similar setups
    ↓
    AI Returns: "12 similar setups, 78% win rate - TAKE IT"
    ↓
    AI Plans: Entry $43,250, SL $42,950, TP $44,050 (based on history)
    ↓
    AI Places: BUY STOP @ $43,250
    ↓
    [FILL]
    ↓
    Phase 2 Order Manager: Takes over (already built)
    ↓
    Position Closes: TP hit @ $44,050
    ↓
    AI Learns: Logs outcome, updates memory
```

---

## Minimal Integration Code

### What You Need to Add to OrderFlowStrategyEnhanced.java

**Current code (line ~640 in OrderFlowStrategyEnhanced.java):**

```java
private void onIcebergDetected(boolean isBid, int price, int totalSize) {
    // Calculate confluence score
    int score = calculateConfluenceScore(isBid, price, totalSize);

    // Check threshold
    if (score >= confluenceThreshold) {
        // Create signal
        SignalData signal = createSignalData(isBid, price, totalSize);

        // CURRENT: Just log it
        log("🎯 SIGNAL: %s @ %d, Score: %d",
            isBid ? "LONG" : "SHORT", price, score);

        // Show in UI
        if (isBid) {
            buyIcebergIndicator.addPoint(price, sequenceNumber);
        } else {
            sellIcebergIndicator.addPoint(price, sequenceNumber);
        }
    }
}
```

**Enhanced with AI layer (add ~20 lines):**

```java
// ========== NEW: Add these fields ==========
private TradingMemoryService memoryService;
private TranscriptWriter transcriptWriter;
private AIInvestmentStrategist aiStrategist;

@Override
public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
    // ... existing initialization code ...

    // ========== NEW: Initialize AI services ==========
    memoryService = new TradingMemoryService(api, alias);
    transcriptWriter = new TranscriptWriter(sessionsDir, alias);
    aiStrategist = new AIInvestmentStrategist(memoryService, transcriptWriter);

    log("🤖 AI Investment Strategist initialized");
}

private void onIcebergDetected(boolean isBid, int price, int totalSize) {
    // Calculate confluence score (existing code, no changes)
    int score = calculateConfluenceScore(isBid, price, totalSize);

    // Check threshold (existing code)
    if (score >= confluenceThreshold) {
        // Create signal (existing code)
        SignalData signal = createSignalData(isBid, price, totalSize);

        // Show in UI (existing code)
        if (isBid) {
            buyIcebergIndicator.addPoint(price, sequenceNumber);
        } else {
            sellIcebergIndicator.addPoint(price, sequenceNumber);
        }

        // ========== NEW: Ask AI before trading ==========
        if (aiStrategist != null) {
            aiStrategist.evaluateSetup(signal, new AIStrategistCallback() {
                @Override
                public void onDecision(AIDecision decision) {
                    if (decision.shouldTake) {
                        log("✅ AI: TAKE THIS SETUP - %s", decision.reasoning);

                        // Place strategic order
                        placeStrategicOrder(decision.plan);

                    } else {
                        log("⛔ AI: SKIP - %s", decision.reasoning);
                        // Don't trade this setup
                    }
                }
            });
        }
    }
}

// ========== NEW: Strategic order placement ==========
private void placeStrategicOrder(TradePlan plan) {
    log("🎯 PLACING ORDER: %s @ %.2f, SL: %.2f, TP: %.2f",
        plan.orderType, plan.entryPrice, plan.stopLossPrice, plan.takeProfitPrice);
    log("   Reasoning: %s", plan.reasoning);

    // Place BUY STOP order (not market order!)
    // This integrates with Phase 2 order manager
    placeOrder(
        OrderType.BUY_STOP,
        plan.entryPrice,
        plan.stopLossPrice,
        plan.takeProfitPrice,
        plan.contracts
    );
}
```

### That's It!

**Total changes to OrderFlowStrategyEnhanced.java:**
- **3 new fields** (memoryService, transcriptWriter, aiStrategist)
- **5 lines in initialize()** (create AI services)
- **15 lines in onIcebergDetected()** (ask AI before trading)
- **1 new method** (placeStrategicOrder)

**Everything else stays the same!**
- Confluence scoring: ✅ No changes
- Indicators: ✅ No changes
- Pattern detection: ✅ No changes
- Phase 2 order manager: ✅ No changes

The AI layer is **non-invasive** - it adds intelligence on top of what you already have!

---

## Architecture

### System Components

```
┌──────────────────────────────────────────────────────────────────┐
│                      AI Investment Strategist                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │           Market Scanner (Real-time Analysis)              │ │
│  │  - Monitor MBO data (iceberg, spoofing, absorption)        │ │
│  │  - Track indicators (CVD, VWAP, EMAs, Volume Profile)     │ │
│  │  - Detect patterns forming in real-time                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Setup Analyzer (Pattern Recognition)               │ │
│  │  - Identify setup characteristics                          │ │
│  │  - Classify setup type (breakout, pullback, reversal)     │ │
│  │  - Assess setup quality (0-100 score)                      │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Memory Search (Historical Context)                 │ │
│  │  - Query: "What happened last time we saw this?"           │ │
│  │  - Find similar setups in trading memory                  │ │
│  │  - Retrieve outcomes, optimal levels, win rates           │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Strategic Planner (AI Decision Engine)             │ │
│  │  - Analyze current setup vs historical                    │ │
│  │  - Calculate optimal entry level                          │ │
│  │  - Calculate optimal SL/TP from history                    │ │
│  │  - Generate trade plan with reasoning                      │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Order Manager (Phase 2 Integration)               │ │
│  │  - Place BUY STOP orders (not market orders)              │ │
│  │  - Monitor position state                                 │ │
│  │  - Execute bracket orders (SL/TP)                         │ │
│  │  - Breakeven triggers                                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Session Manager (Learning & Memory)               │ │
│  │  - Log setup analysis to transcript                       │ │
│  │  - Log order placement                                    │ │
│  │  - Log outcome when position closes                       │ │
│  │  - Update trading memory with lessons learned             │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Foundation Layer                             │
│  ┌──────────────────────────┐  ┌──────────────────────────────┐ │
│  │  Trading Memory Service  │  │  Session Transcript Store    │ │
│  │  (MEMORY_SESSIONS_       │  │  (JSONL transcripts)         │ │
│  │   PATTERN_SPEC.md)       │  │                              │ │
│  └──────────────────────────┘  └──────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │   Market Data (MBO, Trades, DOM, Indicators)              │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Data Flow

```
MARKET DATA
    ↓
DETECT: Iceberg orders at $43,200, VWAP support, bullish CVD
    ↓
ANALYZE: Setup type = "Bullish Breakout", Quality = 78/100
    ↓
SEARCH MEMORY: "Found 12 similar setups, 78% win rate"
    ↓
PLAN: Entry $43,250, SL $42,950, TP $44,050 (based on history)
    ↓
PLACE ORDER: BUY STOP @ $43,250 (strategic, not market order)
    ↓
WAIT: For fill (could be minutes or hours)
    ↓
[FILL] @ $43,250
    ↓
MANAGE: Phase 2 order manager takes over (bracket orders, breakeven)
    ↓
CLOSE: TP hit @ $44,050 (+$800) ✅
    ↓
LOG: Outcome to transcript, update memory with success
    ↓
LEARN: "This setup wins 80% when entered before 11 AM"
```

---

## Setup Detection Process

### Step 1: Continuous Market Scanning

**What AI Monitors:**

```java
class MarketScanner {
    // Pattern Detection
    int icebergOrderCount;         // Iceberg orders at same price
    int totalIcebergSize;          // Total size
    boolean spoofDetected;         // Large orders cancelled
    boolean absorptionDetected;    // Large orders holding

    // Indicators
    double cvd;                    // Cumulative Volume Delta
    String cvdTrend;               // "BULLISH", "BEARISH", "NEUTRAL"
    double vwap;                   // Volume Weighted Avg Price
    double ema9, ema21, ema50;     // EMA values
    double price;

    // Volume Profile
    int poc;                       // Point of Control
    int valueAreaLow;              // Value Area Low
    int valueAreaHigh;             // Value Area High
    int volumeAtPrice;             // Volume at current price

    // Market State
    String trend;                  // "BULLISH", "BEARISH"
    boolean isVolatile;            // True if ATR > 2x average
    String timeOfDay;              // "10:15 AM"
}
```

### Step 2: Setup Classification

**Setup Types AI Recognizes:**

1. **Bullish Breakout Setup**
   - Iceberg orders on BID + CVD bullish + Price above VWAP
   - Enter above resistance (BUY STOP)
   - Target: Next resistance level

2. **Bearish Breakdown Setup**
   - Iceberg orders on ASK + CVD bearish + Price below VWAP
   - Enter below support (SELL STOP)
   - Target: Next support level

3. **Pullback to Value Setup**
   - Price pulls back to POC/VWAP + Absorption detected
   - Enter at value area (LIMIT order)
   - Target: Return to highs/lows

4. **Reversal Setup**
   - CVD divergence + Extremes + Volume spike
   - Enter on break of swing high/low (STOP order)
   - Target: Measured move

### Step 3: Setup Quality Scoring

**Confluence Score (0-100):**

```java
int calculateSetupQuality(SetupType type, MarketContext ctx) {
    int score = 0;

    // 1. Pattern Strength (max 40 points)
    score += Math.min(40, icebergOrderCount * 2);

    // 2. CVD Confirmation (max 25 points)
    if (type.isBullish() && ctx.cvd > 0) score += 25;
    if (type.isBearish() && ctx.cvd < 0) score += 25;

    // 3. Volume Profile (max 20 points)
    if (ctx.volumeAtPrice > ctx.averageVolume * 2) score += 20;

    // 4. Volume Imbalance (max 10 points)
    if (ctx.imbalanceRatio > 3.0 && type.isBullish()) score += 10;

    // 5. Trend Alignment (max 15 points)
    int emaAlignment = countEmaAlignment(type, ctx);
    score += emaAlignment * 5;  // 0, 5, 10, or 15 points

    // 6. VWAP Alignment (max 10 points)
    if ((type.isBullish() && ctx.price > ctx.vwap) ||
        (type.isBearish() && ctx.price < ctx.vwap)) {
        score += 10;
    }

    // 7. Time of Day (max 10 points)
    int hour = getHour();
    if (hour >= 10 && hour <= 15) score += 10;
    else if (hour >= 9 && hour <= 16) score += 5;

    return score;  // Max: ~130 points, scale to 0-100
}
```

**Quality Thresholds:**
- **90-100**: A+ setup, full position size
- **75-89**: A setup, standard position
- **60-74**: B setup, reduce size 50%
- **< 60**: C setup, SKIP

---

## Order Placement Strategy

### Strategic Entry Levels

**NOT Market Orders - We Use Buy Stops:**

**Example: Bullish Breakout Setup**

```java
// Market data
Price: $43,200
Iceberg orders: 8 at $43,200 (total size: 80)
Resistance: $43,220

// AI searches memory
→ Found: "Bullish iceberg + VWAP support = 78% win rate"
→ Last 5 similar setups: All won when entered above resistance

// AI calculates optimal entry
Entry Price: $43,250 (above resistance + padding)
Order Type: BUY STOP (trigger when bid >= 43,250)
Duration: Good Till Cancelled (GTC)

// Why NOT market order at $43,200?
→ Risk: False breakout, could get trapped
→ Memory: "Wait for confirmation above resistance = 78% win"
→ Strategy: Let market prove direction before entering
```

### Memory-Driven SL/TP Calculation

**Query Memory for Similar Setups:**

```java
// Search memory for similar setups
List<HistoricalSetup> similar = memoryService.search(
    query: "Bullish iceberg VWAP support BTC 10-11AM",
    maxResults: 20
);

// Analyze outcomes
double avgWinRate = similar.stream()
    .filter(s -> s.outcome == Outcome.WON)
    .count() / (double)similar.size();

// Find optimal stop loss
int optimalStopTicks = findOptimalStop(similar);
// Result: "SL below swing low (30 ticks) = best win rate"

// Find optimal take profit
double avgRiskReward = similar.stream()
    .mapToDouble(s -> s.riskReward)
    .average()
    .orElse(2.0);
// Result: "Average winning R:R = 2.3:1"

// Calculate levels
int entry = 43250;
int stopLoss = entry - (optimalStopTicks * tickSize);
int takeProfit = entry + (int)(optimalStopTicks * avgRiskReward * tickSize);

// Place order
placeBuyStop(entry, stopLoss, takeProfit,
    reasoning: "8 iceberg orders, VWAP support, 78% historical win rate");
```

**Why This Works:**

| **Fixed SL/TP (Speed Daemon)** | **Memory-Driven SL/TP (Strategist)** |
|-------------------------------|--------------------------------------|
| SL: Always 20 ticks | SL: Based on volatility of similar setups |
| TP: Always 1:2 ratio | TP: Based on avg R:R of similar setups |
| Not adaptive | Learns from history |
| Same SL/TP for all setups | Customized per setup type |
| Ignoring market context | Respects market conditions |

---

## Memory Integration

### Foundation: Memory & Sessions Pattern

**This specification builds on:**
- **MEMORY_SESSIONS_PATTERN_SPEC.md** - Core memory architecture
- Implements dual-source memory (files + transcripts)
- Semantic search via vector embeddings
- Hybrid search (vector + keyword)

### Memory Structure for Strategist

**1. Trading Memory Files (Markdown)**

```
trading-memory/
├── TRADING_MEMORY.md
├── setups/
│   ├── bullish-breakout-iceberg.md
│   ├── bearish-breakdown-spoof.md
│   ├── pullback-to-poc.md
│   └── reversal-cvd-divergence.md
└── lessons/
    └── 2025/
        └── 02/
            ├── btc-volatile-signal.md
            └── eth-fakeout-lesson.md
```

**Example: `setups/bullish-breakout-iceberg.md`**

```markdown
# Bullish Breakout with Iceberg Orders

## Pattern Definition
- Iceberg orders on BID (5+ orders at same price)
- CVD trending up (> +1000)
- Price above VWAP
- At least 2/3 EMAs bullish (9, 21, 50)

## Historical Performance
- Total occurrences: 47
- Win rate: 78.7% (37 wins, 10 losses)
- Average win: +$450
- Average loss: -$280
- Profit factor: 4.2

## Optimal Entry Strategy
- Entry: BUY STOP above resistance (5-10 ticks above iceberg price)
- Wait for confirmation (bid > resistance)
- Don't chase if price runs away

## Stop Loss Strategy
- Below recent swing low (20-30 ticks)
- If volatility high (ATR > 2x), widen to 40 ticks
- Tight SL (15 ticks) if strong absorption + volume confirmation

## Take Profit Strategy
- Primary target: Next resistance level
- Average R:R: 2.3:1
- If strong momentum, consider holding for 3:1
- If weak momentum, take 1.5:1 at first resistance

## Time of Day Effects
- 10:00-11:30 AM: 85% win rate (best time)
- 11:30 AM - 2:00 PM: 72% win rate
- After 3:00 PM: 60% win rate (avoid)

## Market Conditions
- Works best in: Trending markets, moderate volatility
- Avoid in: Choppy/ranging markets, extreme volatility
- CVD divergence bonus: +10% win rate

## Key Lessons Learned
1. **Feb 8, 2025**: Won with entry above 43250, SL below 43195
2. **Feb 5, 2025**: Lost with tight SL (15 ticks) - volatility too high
3. **Feb 1, 2025**: Won by waiting for pullback to VWAP before entry
4. **Jan 28, 2025**: Fakeout loss - iceberg was spoof, not real

## Related Setups
- Bearish Breakdown with Iceberg (mirror setup)
- Pullback to POC (alternative entry strategy)
```

**2. Session Transcripts (JSONL)**

```jsonl
{"type": "session", "id": "uuid", "timestamp": "2025-02-11T10:15:00Z", "instrument": "BTC_USDT"}
{"type": "setup_detected", "timestamp": "2025-02-11T10:15:30Z", "setup": "bullish-breakout-iceberg", "score": 78, "price": 43200, "reasoning": "8 iceberg orders, CVD +1250, above VWAP"}
{"type": "memory_search", "timestamp": "2025-02-11T10:15:32Z", "query": "bullish iceberg VWAP support", "results": 12, "avg_win_rate": 0.78}
{"type": "order_placed", "timestamp": "2025-02-11T10:15:45Z", "order_id": "uuid", "type": "BUY_STOP", "entry": 43250, "sl": 42950, "tp": 44050, "reasoning": "Based on similar setups: 78% win rate, optimal entry above resistance"}
{"type": "order_filled", "timestamp": "2025-02-11T10:22:15Z", "order_id": "uuid", "fill_price": 43250, "position_size": 1}
{"type": "breakeven_triggered", "timestamp": "2025-02-11T10:28:00Z", "order_id": "uuid", "new_sl": 43251}
{"type": "position_closed", "timestamp": "2025-02-11T11:05:30Z", "order_id": "uuid", "exit_price": 44050, "exit_reason": "TAKE_PROFIT", "pnl": 800.00, "duration_minutes": 43}
{"type": "lesson_learned", "timestamp": "2025-02-11T11:06:00Z", "lesson": "Bullish iceberg setups won again when entered above resistance. 9/10 wins this month with this strategy."}
```

### Memory Search Queries

**Examples of What AI Searches For:**

```java
// Before entering setup
String query1 = String.format(
    "Bullish iceberg VWAP support %s %s CVD %s",
    instrument,
    timeOfDay,
    cvdTrend
);
// Result: "Found 12 similar setups, 78% win rate, avg R:R 2.3:1"

// During position management
String query2 = String.format(
    "Bullish iceberg win rate when breakeven triggered %s",
    instrument
);
// Result: "Breakeven increases win rate from 72% to 89%"

// After session
String query3 = String.format(
    "Best performing setups %s morning",
    instrument
);
// Result: "Bullish iceberg 10-11 AM: 85% win rate"
```

---

## Session Management

### Session Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│ 1. PRE-SESSION (Before Market Open)                        │
│    - AI reviews yesterday's transcript                     │
│    - Analyzes what setups worked                           │
│    - Identifies lessons learned                            │
│    - Creates session plan                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. SESSION START (Market Open)                             │
│    - Initialize session transcript file                    │
│    - Record session plan                                   │
│    - Start market scanning                                 │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. SETUP DETECTION (Continuous)                            │
│    - Detect patterns forming                               │
│    - Classify setup type                                   │
│    - Score setup quality                                   │
│    - If score < 60: SKIP                                  │
│    - If score ≥ 60: Search memory                          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. MEMORY SEARCH (For Each Qualified Setup)                │
│    - Query: "Similar setups?"                              │
│    - Retrieve: Win rates, optimal levels                   │
│    - If win rate < 65%: SKIP                              │
│    - If win rate ≥ 65%: Plan trade                         │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. ORDER PLACEMENT (Strategic)                             │
│    - Calculate entry level (BUY STOP, not market)          │
│    - Calculate SL/TP from history                           │
│    - Place pending order                                   │
│    - Log to transcript                                     │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 6. ORDER FILLED (Triggered)                                │
│    - Hand off to Phase 2 Order Manager                     │
│    - Bracket orders active (SL/TP)                         │
│    - Breakeven monitoring                                  │
│    - Log fill to transcript                                │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 7. POSITION CLOSED (Exit)                                  │
│    - Record outcome (TP/SL/BE)                             │
│    - Calculate P&L                                         │
│    - Log to transcript                                    │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 8. POST-SESSION (Market Close)                             │
│    - Analyze all trades from session                       │
│    - Generate session summary                              │
│    - Update trading memory with lessons                    │
│    - Identify patterns for tomorrow                        │
└─────────────────────────────────────────────────────────────┘
```

### Integration with Phase 2 Order Management

**Setup Phase (AI Strategist):**
```
AI finds setup → Searches memory → Places BUY STOP
→ "Strategic entry, no rush, let market come to us"
```

**Execution Phase (Phase 2 Order Manager):**
```
Buy stop fills → Position tracking → Bracket orders
→ "Automatic management, breakeven, SL/TP execution"
```

**Learning Phase (Memory System):**
```
Position closes → Log outcome → Update memory
→ "Continuous improvement, smarter decisions next time"
```

---

## Data Structures

### Setup Detection

```java
public class Setup {
    // Identification
    String setupId;
    SetupType type;  // BULLISH_BREAKOUT, BEARISH_BREAKDOWN, etc.
    long detectedTime;

    // Quality Score
    int confluenceScore;  // 0-100
    boolean isQualified;  // score >= 60

    // Market Context
    MarketContext context;

    // Historical Analysis (from memory search)
    List<HistoricalSetup> similarSetups;
    double historicalWinRate;
    double avgRiskReward;
    int optimalStopTicks;
    int optimalTargetTicks;

    // Trade Plan
    TradePlan plan;
}

public enum SetupType {
    BULLISH_BREAKOUT,
    BEARISH_BREAKDOWN,
    PULLBACK_TO_VALUE,
    REVERSAL_CVD_DIVERGENCE,
    ABSORPTION_FADE
}

public class MarketContext {
    String instrument;
    double price;
    long cvd;
    String cvdTrend;
    double vwap;
    double ema9, ema21, ema50;
    int emaAlignment;
    int poc;
    int valueAreaLow, valueAreaHigh;
    String trend;
    boolean isVolatile;
    String timeOfDay;
}

public class HistoricalSetup {
    String setupId;
    LocalDate date;
    String timeOfDay;
    SetupType type;
    boolean won;
    double entryPrice;
    double exitPrice;
    double pnl;
    int stopLossTicks;
    int takeProfitTicks;
    double riskReward;
    int durationMinutes;
    String notes;
}
```

### Trade Plan

```java
public class TradePlan {
    // Entry Strategy
    OrderType orderType;  // BUY_STOP, SELL_STOP, LIMIT
    double entryPrice;
    String reasoning;  // "8 iceberg orders, 78% historical win rate"

    // Risk Management
    double stopLossPrice;
    double takeProfitPrice;
    double breakEvenPrice;
    int stopLossTicks;
    int takeProfitTicks;
    double riskRewardRatio;

    // Position Sizing
    int contracts;
    double totalRisk;
    double totalRiskPercent;

    // Historical Basis
    int similarSetupsCount;
    double historicalWinRate;
    String historicalReasoning;  // "Last 5 similar setups: all won"

    // Confidence
    ConfidenceLevel confidence;  // HIGH, MEDIUM, LOW
    String warnings;  // "High volatility, consider wider SL"
}
```

### Session Transcript Events

```java
public class TranscriptEvent {
    String type;  // "setup_detected", "memory_search", "order_placed", etc.
    long timestamp;
    JsonObject data;

    // Example: order_placed
    // {
    //   "order_id": "uuid",
    //   "type": "BUY_STOP",
    //   "entry": 43250,
    //   "sl": 42950,
    //   "tp": 44050,
    //   "reasoning": "Based on 12 similar setups: 78% win rate",
    //   "confidence": "HIGH"
    // }
}
```

---

## Implementation Plan

### Phase 1: Foundation (Week 1)

**Goal:** Basic memory infrastructure

1. **Implement Memory Pattern**
   - Read MEMORY_SESSIONS_PATTERN_SPEC.md
   - Implement TradingMemoryService
   - Implement TranscriptWriter
   - Create initial trading-memory/ directory

2. **Create Setup Detection**
   - SetupClassifier (identify setup types)
   - SetupQualityScorer (confluence 0-100)
   - MarketScanner (continuous monitoring)

**Deliverable:** Can detect setups, calculate quality scores

### Phase 2: Memory Integration (Week 2)

**Goal:** Semantic search for similar setups

1. **Embedding Service**
   - ClaudeEmbeddingProvider (use existing AIThresholdService pattern)
   - Vector search in memory database
   - Hybrid search (vector + keyword)

2. **Historical Analysis**
   - Query memory for similar setups
   - Calculate historical win rates
   - Find optimal SL/TP from history

**Deliverable:** Can search memory and find similar setups

### Phase 3: Strategic Order Placement (Week 3)

**Goal:** Place intelligent pending orders

1. **Trade Planner**
   - Calculate optimal entry (BUY STOP levels)
   - Calculate memory-based SL/TP
   - Generate trade plans with reasoning

2. **Order Integration**
   - Integrate with Phase 2 order manager
   - Place BUY STOP orders (not market orders)
   - Hand off to order manager on fill

**Deliverable:** Places strategic orders based on memory

### Phase 4: Continuous Learning (Week 4)

**Goal:** Learn from outcomes

1. **Session Management**
   - Log all events to transcripts
   - Post-session analysis
   - Update memory with lessons

2. **Memory Updates**
   - Auto-generate setup documentation
   - Track performance by setup type
   - Identify best times/conditions

**Deliverable:** System learns and improves

---

## Use Cases

### Use Case 1: Morning Setup Detection

**Time:** 10:15 AM
**Market:** BTC_USDT at $43,200

**AI Action Flow:**

```
1. DETECT: 8 iceberg orders at $43,200, CVD +1250, above VWAP
2. CLASSIFY: BULLISH_BREAKOUT setup
3. SCORE: 78/100 (qualified)
4. SEARCH MEMORY: "Bullish iceberg VWAP support BTC 10-11AM"
   → Found: 12 similar setups, 85% win rate in this timeframe
5. PLAN: Entry $43,250 (above resistance), SL $42,950, TP $44,050
6. PLACE: BUY STOP @ $43,250
7. LOG: "Bullish breakout, 85% historical win rate, waiting for fill"
```

### Use Case 2: Learning from Loss

**Time:** 2:30 PM
**Outcome:** Stop Loss hit at $42,950 (-$300)

**AI Learning:**

```
1. LOG: "Position closed, SL hit, -$300"
2. ANALYZE: "Why did this setup fail?"
3. SEARCH: "Bullish iceberg setups afternoon 2-3 PM"
   → Found: 8 similar setups, only 50% win rate
4. LEARN: "Afternoon bullish iceberg setups have lower win rate"
5. UPDATE MEMORY: Add lesson to trading-memory/lessons/2025/02/
6. ADJUST: "Skip bullish iceberg after 2 PM unless score > 85"
```

### Use Case 3: Session Summary

**Time:** 4:00 PM (Market Close)

**AI Generates:**

```
SESSION SUMMARY - February 11, 2025
=====================================

Setups Detected: 8
Qualified Setups (score ≥ 60): 5
Trades Taken: 3

Performance:
- Wins: 2
- Losses: 1
- Win Rate: 67%
- Total P&L: +$950

Best Setup:
- Bullish Breakout (10:15 AM): +$800
- Score: 78, Historical win rate: 85%

Worst Setup:
- Pullback to POC (1:30 PM): -$150
- Score: 62, Historical win rate: 55%

Key Lessons:
1. Bullish iceberg setups: 100% win rate (3/3) this session
2. Afternoon setups weak: Only 1/2 won, consider skipping
3. Tight SL (15 ticks) failed: Volatility too high

Tomorrow's Plan:
- Focus on bullish iceberg setups 10-11 AM
- Use wider SL (25+ ticks) in afternoon
- Skip pullback setups unless score > 70
```

---

## Related Documents

### Foundation
- **MEMORY_SESSIONS_PATTERN_SPEC.md** - Core memory and sessions architecture
- **TRADING_STRATEGY_PLAN.md** - Phase 2: Order Management
- **AI_INTEGRATION_PLAN.md** - AI modes and decision making

### Data Sources
- **ORDER_FLOW_TRADING_PLAN.md** - Market data and indicators
- **BOOKMAP_DATA_ANALYSIS.md** - Pattern detection methods

### Implementation
- **ENHANCED_CONFLUENCE_IMPLEMENTATION.txt** - Confluence scoring
- **RISK_MANAGEMENT_DETAILS.md** - Risk parameters

---

## Success Metrics

### Setup Detection Quality
- True Positive Rate: > 80% (detected setups are profitable)
- False Positive Rate: < 20% (skip low-quality setups)
- Setup Classification Accuracy: > 90%

### Memory Search Effectiveness
- Relevant Results: > 70% of top 5 results are truly similar
- Historical Win Rate Accuracy: Predicted vs Actual within ±10%
- SL/TP Recommendations: Improve win rate by > 15%

### Trading Performance
- Win Rate: > 70% (vs 50% for signal-following)
- Average R:R: > 2.0:1
- Profit Factor: > 2.5
- Max Drawdown: < 10%

### Learning Effectiveness
- Memory Queries per Setup: 3-5 (active learning)
- New Lessons per Week: 5-10 (continuous improvement)
- Win Rate Improvement: > 10% over 4 weeks

---

## Next Steps

1. **Review and approve** this specification
2. **Implement Phase 1** (Foundation - Week 1)
3. **Create initial trading memory** (setup documentation)
4. **Test with replay data** (validate setup detection)
5. **Integrate with existing** AIThresholdService and OrderFlowStrategyEnhanced

---

**Document Status:** Ready for Implementation
**Dependencies:** MEMORY_SESSIONS_PATTERN_SPEC.md
**Integration:** Phase 2 Order Management (ORDER_FLOW_STRATEGY_README.md)
