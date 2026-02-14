---
name: qid-trading-strategy
description: AI-driven investment strategist trading system for Bookmap. Implements order flow analysis (iceberg/spoof/absorption detection), 13-factor confluence scoring, memory-based decision making, and strategic order placement. Use when developing trading strategies, understanding order flow patterns, or integrating AI with Bookmap.
license: Proprietary
compatibility: Bookmap L1 API 7.6.0.20, Java 17
metadata:
  author: Qid Trading
  version: "2.1"
  last_updated: "2025-02-13"
---

# Qid - AI Investment Strategist Trading System

---

## Overview

Qid is an **AI-driven investment strategist** that operates as an intelligence layer on top of Bookmap's order flow analysis. Unlike reactive signal-following systems, Qid proactively searches for setups, places strategic pending orders, and continuously learns from outcomes.

**Core Philosophy:** Intelligence over speed. Qid is NOT an HFT "speed daemon" - it's an investment strategist that uses observation, data analysis, and historical pattern memory to make intelligent trading decisions.

**Key Innovation:** Searches memory BEFORE acting, then learns from EVERY outcome.

---

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│  EXISTING: OrderFlowStrategyEnhanced (Bookmap Java API)    │
│  ✅ Iceberg detection (MBO order tracking)                   │
│  ✅ Spoofing detection (large orders cancelled)              │
│  ✅ Absorption detection (large orders holding)              │
│  ✅ Confluence scoring (13-factor, 0-135 points)            │
│  ✅ CVD tracking & divergence                               │
│  ✅ VWAP calculation & alignment                            │
│  ✅ EMA 9/21/50 trend alignment                             │
│  ✅ Volume profile & POC analysis                           │
│  ✅ Volume imbalance detection                              │
│  ✅ ATR calculation & volatility                            │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  NEW: AI Investment Strategist (Intelligence Layer)        │
│  🧠 Memory Search (find similar historical setups)         │
│  🤖 Decision Engine (take/skip based on history)            │
│  📊 Strategic Planner (optimal entry/exit levels)           │
│  💬 Chat Interface (ask AI trading questions)               │
│  📝 Transcript Writer (log everything to JSONL)            │
│  🎓 Learning System (update memory from outcomes)          │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

```
1. MARKET DATA (MBO, Trades, DOM)
   ↓
2. PATTERN DETECTION (Iceberg/Spoof/Absorption)
   ↓
3. CONFLUENCE SCORING (13 factors → 0-135 points)
   ↓
4. AI MEMORY SEARCH ("What happened last time?")
   ↓
5. DECISION (TAKE/SKIP based on historical win rate)
   ↓
6. STRATEGIC ORDER (BUY STOP, not market order)
   ↓
7. POSITION MANAGEMENT (Phase 2: SL/TP/Breakeven)
   ↓
8. OUTCOME LOGGING (Learn for next time)
```

---

## Order Flow Patterns

### 1. Iceberg Detection

**Definition:** Hidden large orders revealed through repeated small executions at the same price.

**Detection Criteria:**
- 5+ orders at same price within short window
- Total size ≥ 20 contracts
- Individual orders ≤ 50% of expected size

**Scoring:** Up to 40 points (2 points per order)

**Example:**
```
Time       | Price  | Size | Order Type
10:15:01   | 43200  | 10   | BUY
10:15:03   | 43200  | 8    | BUY
10:15:05   | 43200  | 12   | BUY
→ ICEBERG DETECTED (3 orders, 30 contracts)
```

### 2. Spoofing Detection

**Definition:** Large orders placed to create false impression, then cancelled before execution.

**Detection Criteria:**
- Large order (≥ 50 contracts) appears
- Cancelled within 5 seconds
- No fill occurs

**Scoring:** -15 points (penalty if detected near signal)

**Example:**
```
10:15:00  ASK: 43210 - 100 contracts (large)
10:15:02  ASK: 43210 - 0 contracts   (CANCELLED)
→ SPOOF DETECTED (fake liquidity)
```

### 3. Absorption Detection

**Definition:** Large orders absorbing supply/demand at key levels without price movement.

**Detection Criteria:**
- High volume at price (≥ 3x average)
- Price remains stable (doesn't break through)
- Orders hold or add more size

**Scoring:** Up to 20 points

**Example:**
```
Price: 43200
Volume at level: 500 contracts (5x average)
Price after 30 seconds: Still 43200
→ ABSORPTION DETECTED (support holding)
```

---

## Confluence Scoring System

### 13-Factor Scoring (0-135 Points)

**Primary Factors:**
1. **Iceberg Orders** (max 40 points)
   - 2 points per order
   - Capped at 20 orders = 40 points

2. **CVD Confirmation** (max 25 points)
   - CVD confirms direction: +15 points
   - CVD divergence: +10 points
   - CVD extreme: -10 points (potential reversal)

3. **Volume Profile** (max 20 points)
   - High-volume node (POC level): +20 points
   - Low-volume zone: +5 points
   - Normal volume: 0 points

4. **Volume Imbalance** (max 10 points)
   - Strong buying/selling pressure (ratio > 3:1): +10 points
   - Moderate pressure: +5 points

5. **EMA Trend Alignment** (max 15 points)
   - 3/3 EMAs aligned: +15 points
   - 2/3 EMAs aligned: +10 points
   - 1/3 EMAs aligned: +5 points

6. **VWAP Alignment** (max 10 points)
   - Price above VWAP (for longs): +10 points
   - Price below VWAP (for shorts): +10 points

7. **Time of Day** (max 10 points)
   - Prime hours (10 AM - 3 PM): +10 points
   - Secondary hours: +5 points

8. **Size Bonus** (max 5 points)
   - Very large iceberg (≥ 50 contracts): +5 points
   - Large iceberg (≥ 30 contracts): +3 points

**Threshold:**
- **Default:** 60 points (configurable)
- **Conservative:** 70 points (fewer, higher-quality signals)
- **Aggressive:** 50 points (more signals, lower quality)

---

## Memory & Sessions Pattern

### Purpose

Enable AI to remember past trades, learn patterns, and make intelligent decisions based on historical outcomes.

### Memory Structure

```
trading-memory/
├── TRADING_MEMORY.md              # Main knowledge base
├── setups/                        # Pattern documentation
│   ├── bullish-breakout-iceberg.md
│   ├── bearish-breakdown-spoof.md
│   ├── pullback-to-poc.md
│   └── reversal-cvd-divergence.md
└── lessons/                       # Lessons learned
    └── 2025/02/
        ├── btc-volatile-signal.md
        └── eth-fakeout-lesson.md

sessions/                          # Session transcripts (JSONL)
├── 2025-02-11-BTC_USDT.jsonl
└── 2025-02-11-ETH_USDT.jsonl
```

### Memory Search Process

**When signal detected:**

```java
// 1. Build search query from current context
String query = String.format(
    "Bullish iceberg VWAP support BTC 10-11AM CVD bullish"
);

// 2. Search memory for similar setups
List<MemorySearchResult> results = memoryService.search(query, 10);

// 3. Analyze results
→ "Found 12 similar setups, 78% win rate"
→ "Last time (Feb 8): Entry 43250, won in 45 minutes"
→ "Optimal SL: below 43195, TP: 43400"

// 4. Make intelligent decision
if (winRate >= 0.70 && results.size() >= 5) {
    TAKE_SETUP(entry_above_resistance);
} else {
    SKIP_SETUP(low_historical_win_rate);
}
```

### Session Transcripts

**Format:** JSONL (one JSON object per line)

**Example:**
```jsonl
{"type": "session", "id": "uuid", "timestamp": "2025-02-11T10:15:00Z", "instrument": "BTC_USDT"}
{"type": "setup_detected", "timestamp": "2025-02-11T10:15:30Z", "setup": "bullish-breakout-iceberg", "score": 78, "price": 43200}
{"type": "memory_search", "timestamp": "2025-02-11T10:15:32Z", "query": "bullish iceberg VWAP support", "results": 12, "avg_win_rate": 0.78}
{"type": "order_placed", "timestamp": "2025-02-11T10:15:45Z", "order_id": "uuid", "type": "BUY_STOP", "entry": 43250, "sl": 42950, "tp": 44050}
{"type": "order_filled", "timestamp": "2025-02-11T10:22:15Z", "order_id": "uuid", "fill_price": 43250}
{"type": "position_closed", "timestamp": "2025-02-11T11:05:30Z", "order_id": "uuid", "exit_price": 44050, "pnl": 800.00}
{"type": "lesson_learned", "timestamp": "2025-02-11T11:06:00Z", "lesson": "Bullish iceberg setups won 9/10 times with entry above resistance"}
```

---

## AI Decision Process

### Not Just Score-Based

**Speed Daemon (what we're NOT):**
```
IF score >= threshold:
    EXECUTE_MARKET_ORDER()
```

**Investment Strategizer (what we ARE):**
```
IF score >= threshold:
    search_memory_for_similar_setups()
    IF historical_win_rate >= 70% AND similar_setups >= 5:
        plan_strategic_entry_based_on_history()
        place_buy_stop_order()  # Wait for fill, no rush
    ELSE:
        skip_setup()  # Learn to avoid low-probability setups
```

### Decision Factors

AI considers (in order of importance):

1. **Historical Win Rate** (from memory search)
   - ≥ 80%: HIGH confidence, full position
   - 70-79%: MEDIUM confidence, standard position
   - 60-69%: LOW confidence, skip or reduce size 50%
   - < 60%: SKIP

2. **Sample Size**
   - 10+ similar setups: High reliability
   - 5-9 similar setups: Medium reliability
   - < 5 similar setups: Low reliability

3. **Recent Performance** (last 2 weeks)
   - Win rate improving: Increase confidence
   - Win rate declining: Decrease confidence

4. **Market Conditions** (volatility, time of day)
   - High volatility: Widen stops
   - Low volatility: Tighten stops
   - Off-hours: Skip or reduce size

---

## Order Placement Strategy

### Strategic Entry: BUY STOP (Not Market Orders)

**Why BUY STOP instead of MARKET:**

**Market Order (what we DON'T do):**
```
Signal: Iceberg at 43200, score 72
→ IMMEDIATELY EXECUTE MARKET BUY
→ Filled at 43201 (slippage +1)
→ RISK: False breakout, trapped
```

**Buy Stop Order (what we DO):**
```
Signal: Iceberg at 43200, score 72
→ Search memory: "78% win when entered above resistance"
→ PLACE BUY STOP @ 43250 (above resistance)
→ WAIT for market to prove direction
→ Fill only if bid reaches 43250
→ REWARD: Confirmed breakout, higher probability
```

### Memory-Driven SL/TP

**Not Fixed - Based on History:**

```java
// Search memory for optimal levels
List<HistoricalSetup> similar = memoryService.search(...);

// Find what worked best
int optimalStopTicks = analyzeStops(similar);
// Result: "30 tick stop = 78% win rate"
// Result: "15 tick stop = 52% win rate (too tight)"

double avgRiskReward = analyzeRR(similar);
// Result: "Average winning R:R = 2.3:1"

// Calculate levels
entry = 43250;
stopLoss = entry - (optimalStopTicks * tickSize);
takeProfit = entry + (optimalStopTicks * avgRiskReward * tickSize);
```

---

## Key Files & Components

### Main Strategy

**File:** `OrderFlowStrategyEnhanced.java`

**Key Classes:**
```java
public class OrderFlowStrategyEnhanced implements CustomModule {
    // Pattern Detection
    private void onIcebergDetected(boolean isBid, int price, int totalSize)
    private void onSpoofingDetected(boolean isBid, int price, int size)
    private void onAbsorptionDetected(boolean isBid, int price, int size)

    // Scoring
    private int calculateConfluenceScore(boolean isBid, int price, int totalSize)
    private SignalData createSignalData(boolean isBid, int price, int totalSize)

    // Indicators
    private CVDCalculator cvdCalculator
    private VWAPCalculator vwapCalculator
    private EMA ema9, ema21, ema50
    private VolumeProfileCalculator volumeProfile
    private ATRCalculator atrCalculator

    // AI Integration (NEW)
    private TradingMemoryService memoryService
    private AIInvestmentStrategist aiStrategist
    private TranscriptWriter transcriptWriter
}
```

### AI Services

**File:** `AIThresholdService.java` (existing)

**Purpose:** Claude API integration for threshold optimization and chat interface

**Features:**
- Chat with AI about trading
- Optimize thresholds based on market conditions
- Free-form questions to Claude

**File:** `TradingMemoryService.java` (NEW - Phase 1)

**Purpose:** Memory search and management

**Features:**
- Search memory for similar setups
- Index trading patterns
- Semantic search with embeddings

**File:** `AIInvestmentStrategist.java` (NEW - Phase 3)

**Purpose:** Make intelligent trading decisions

**Features:**
- Evaluate setups based on memory
- Generate strategic trade plans
- TAKE/SKIP decisions with reasoning

---

## Usage Examples

### Example 1: Analyzing a Signal

**User:** "I got a signal: Iceberg at 43200, score 72/135, CVD +1250, above VWAP. Should I take it?"

**Qid (AI):**
```
Let me search memory for similar setups...

Found: 12 similar setups (bullish iceberg + VWAP support)
Historical win rate: 78%
Last occurrence: Feb 8, entry 43250, won in 45 minutes

Analysis:
✅ Strong historical performance (78% win rate)
✅ Good confluence (CVD confirms, VWAP support)
✅ Time of day excellent (10:15 AM - prime window)

Recommendation: TAKE
Strategy: BUY STOP @ 43250 (above resistance)
Stop Loss: 42950 (below swing low, 30 ticks)
Take Profit: 44050 (based on 2.3:1 historical R:R)

Confidence: HIGH
Reasoning: Similar setups have won 78% of the time with this strategy
```

### Example 2: Learning from a Loss

**User:** "I took the signal but it hit stop loss for -$300"

**Qid (AI):**
```
Analyzing loss...

Setup: Bullish iceberg at 43200
Entry: 43250, Exit: 42950
Loss: -$300 (30 ticks)

Possible causes:
1. Volatility too high (ATR was 2.5x average)
2. Stop too tight (15 ticks would have been better)
3. Premature entry (should have waited for more confirmation)

Memory update:
⚠️ Adding lesson: "When ATR > 2x average, use wider stop (40+ ticks)"
📝 Similar setups after 2 PM have lower win rate (60% vs 78%)
🎯 Consider: Skip afternoon signals unless score > 85

Updated trading-memory/lessons/2025/02/2025-02-11-loss-analysis.md
```

### Example 3: Daily Review

**User:** "How did we do today?"

**Qid (AI):**
```
SESSION SUMMARY - February 11, 2025

Setups Detected: 8
Qualified (score ≥ 60): 5
Taken: 3
Skipped: 2

Performance:
- Wins: 2
- Losses: 1
- Win Rate: 67%
- Total P&L: +$950

Best Trade:
- Bullish Breakout (10:15 AM): +$800
- Score: 78/135, Historical win rate: 85%

Worst Trade:
- Pullback to POC (1:30 PM): -$150
- Score: 62/135, Historical win rate: 55%

Key Insights:
1. Bullish iceberg setups: 100% win rate (3/3) today
2. Afternoon setups weak: Only 1/2 won
3. Tight stops (15 ticks) failed in volatile conditions

Recommendations for Tomorrow:
- ✅ Focus on bullish iceberg setups 10-11 AM
- ✅ Use wider SL (30+ ticks) when ATR > 2x average
- ⚠️ Skip pullback setups unless score > 70
- ⚠️ Avoid afternoon signals unless exceptional

Lessons Learned:
- Entry above resistance working perfectly (3/3 wins)
- Strategic patience (wait for breakout) beats chasing
```

---

## Development & Integration

### Build System

**File:** `build.gradle`

**Requirements:**
```gradle
sourceCompatibility = 17
targetCompatibility = 17

dependencies {
    // Bookmap API
    implementation 'com.velox:border:7.6.0.20'

    // JSON processing
    implementation 'com.google.code.gson:gson:2.8.9'

    // Existing AI service
    implementation files('libs/claude-agent-sdk.jar')

    // Phase 2: Database (for memory index)
    // implementation 'org.xerial:sqlite-jdbc:3.42.0.0'
    // OR
    // implementation 'com.h2database:h2:2.2.224'
}
```

### Compilation

```bash
cd Strategies
./gradlew build

# Output: build/libs/Strategies.jar
# Load in Bookmap via Settings → API Plugins
```

### Configuration Parameters

**AI Settings:**
- `aiAuthToken`: Claude API token (z.ai)
- `aiChatEnabled`: Enable/disable AI chat interface
- `memoryEnabled`: Enable/disable memory search

**Trading Settings:**
- `confluenceThreshold`: Minimum score for signals (default: 60)
- `icebergMinOrders`: Minimum iceberg orders (default: 5)
- `icebergMinSize`: Minimum iceberg size (default: 20)

**Risk Management:**
- `accountSize`: Account size in dollars (default: $10,000)
- `riskPerTrade`: Risk percentage (default: 1.0)
- `stopLossTicks`: Stop loss distance (default: 3 ticks)
- `takeProfitTicks`: Take profit distance (default: 6 ticks)

---

## Key Concepts

### Investment Strategizer vs Speed Daemon

| Aspect | Speed Daemon (HFT) | Investment Strategist (Qid) |
|--------|-------------------|----------------------------|
| Trigger | Signal fires → React | Analyze → Find setup → Plan |
| Speed | Microseconds matter | Intelligence matters |
| Entry | Market orders (chase) | Buy stops (patience) |
| SL/TP | Fixed ratios (1:2) | Memory-driven (adaptive) |
| Learning | None/Minimal | Continuous from outcomes |
| Memory | Not used | Essential |

### The Memory Advantage

**Without Memory:**
```
Signal: Iceberg, score 72
→ Trade immediately (blind to history)
→ Hope it works
```

**With Memory (Qid):**
```
Signal: Iceberg, score 72
→ Search: "What happened last time?"
→ Found: 12 similar setups, 78% win rate
→ Decision: Take it, but use specific entry/exit
→ Confidence: High (data-backed)
→ Learn: Update memory with outcome
```

---

## Troubleshooting

### Common Issues

**1. "Memory search returns no results"**
- Cause: No historical data yet
- Solution: Run for 1-2 weeks to build memory
- Alternative: Use confluence score only (existing system)

**2. "AI decisions are slow"**
- Cause: Embedding API call latency
- Solution: Implement caching (already in code)
- Target: < 500ms per search

**3. "Skip rate is too high"**
- Cause: Search query too specific
- Solution: Broaden query terms
- Adjust: Lower win rate threshold to 60%

**4. "Lesson files not being created"**
- Cause: Directory permissions
- Solution: Check trading-memory/lessons/ is writable
- Verify: Files.createDirectories() succeeded

---

## Best Practices

### For Trading

1. **Start in SIM mode** - Test thoroughly before live trading
2. **Build memory first** - Run 2-4 weeks to establish baseline
3. **Review sessions daily** - Check lessons learned
4. **Update memory manually** - Add pattern observations
5. **Track win rates** - By setup type, time of day, market conditions

### For Development

1. **Non-invasive integration** - Add AI layer, don't replace existing
2. **Test incrementally** - Phase 1 → Phase 2 → Phase 3 → Phase 4
3. **Log everything** - Transcripts essential for learning
4. **Monitor performance** - Search latency, memory growth
5. **Backup regularly** - Session transcripts, lesson files

---

## Future Enhancements

### Phase 5+: Advanced Features

1. **Database Backend** - SQLite with vector search (sqlite-vec)
2. **Multi-Instrument Memory** - Share patterns across correlated assets
3. **Backtesting Integration** - Test strategies against historical data
4. **Pattern Performance Dashboard** - Visual analytics
5. **Real-Time Collaboration** - Share memory across instances

---

## References

### Documentation

- `MEMORY_SESSIONS_PATTERN_SPEC.md` - Memory architecture
- `AI_INVESTMENT_STRATEGIST_SPEC.md` - AI strategist design
- `PHASED_IMPLEMENTATION_PLAN.md` - 4-week implementation plan
- `TRADING_STRATEGY_PLAN.md` - Phase 2 order management
- `ORDER_FLOW_TRADING_PLAN.md` - Order flow concepts

### Code

- `OrderFlowStrategyEnhanced.java` - Main strategy
- `AIThresholdService.java` - Claude API integration
- `TradingMemoryService.java` - Memory search (Phase 1+)
- `TranscriptWriter.java` - Session logging (Phase 4)

### External

- [Bookmap L1 API Documentation](https://bookmap.com/docs/)
- [Claude API Reference](https://z.ai/docs)
- [Anthropic Agent Skills](https://github.com/anthropics/skills)

---

## Summary

Qid is an **AI-driven investment strategist** that:
- ✅ Leverages existing order flow detection (13-factor scoring)
- ✅ Searches memory for similar historical setups
- ✅ Makes intelligent TAKE/SKIP decisions
- ✅ Places strategic orders (BUY STOP, not market)
- ✅ Learns continuously from every outcome
- ✅ Uses intelligence over speed (investment approach, not HFT)

**Key Innovation:** Memory-driven decision making - searches history before acting, learns from every trade, improves over time.

---

**End of Skill Definition**

For questions about implementation, refer to:
- Implementation Plan: `PHASED_IMPLEMENTATION_PLAN.md`
- Architecture: `AI_INVESTMENT_STRATEGIST_SPEC.md`
- Memory Pattern: `MEMORY_SESSIONS_PATTERN_SPEC.md`
