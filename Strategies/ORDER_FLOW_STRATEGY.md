# Order Flow Strategy - Project Documentation

## Project Goal

Create an automated **Order Flow Trading Strategy** for Bookmap that detects and trades based on order flow concepts from this YouTube video: [Order Flow Trading with DOM and Heat Maps](https://www.youtube.com/watch?v=7facFfjQ0UE&list=PLWQioWs8oOiFKQnwIIYbw8N7ks7d-UAPQ&index=8)

## What the Strategy Should Do

### Core Concepts (from video)
1. **Absorption Detection** - Large orders absorbing aggressive volume (walls preventing price movement)
2. **Follow the Big Fish** - Track large liquidity providers (5x+ average size)
3. **Retail Trap Detection** - Price moves one way but delta moves opposite (potential reversal)
4. **Delta Analysis** - Aggressive buyers vs sellers imbalance

### Signal Logic
- **Bullish Signal**: Large bid wall absorbing asks + positive delta = potential breakout up
- **Bearish Signal**: Large ask wall absorbing bids + negative delta = potential breakout down
- **Retail Trap**: Price rising but delta negative (or vice versa) = potential reversal

### Strategy Components
1. **Signal Detection** (Phase 1 - Working on this now)
   - Absorption detection using dual moving averages
   - Delta tracking (aggressive buying/selling pressure)
   - Big player activity detection
   - Retail trap detection via delta divergence

2. **Order Execution** (Phase 2 - Not yet implemented)
   - Marketable limit orders (2-tick cap)
   - Automatic bracket orders (take-profit + stop-loss)
   - Breakeven stops after 5 ticks profit
   - SIM-only mode by default (safety)

3. **Safety Framework**
   - SIM_ONLY mode (default: true) - prevents real money trading
   - Kill switch - instant stop all trading
   - Max position size = 1 contract
   - Max orders per minute = 4
   - Cooldown after stop loss = 30 seconds

## Current Status

### What Works
- ✅ **16 other indicators** in QID plugin load and function correctly
- ✅ **ATR Trailing Stop** works (the only custom strategy that works)
- ✅ Signal detection code is written and compiles

### The Blocking Issue
❌ **`initialize()` method NEVER gets called**

All custom strategies we create:
- Load successfully into Bookmap
- Show as "enabled" in the UI
- Bookmap reports "started" and "complete"
- **BUT the `initialize()` method never executes**

This means:
- No indicators get registered
- No startup messages appear
- Strategy appears in UI but does nothing

## What We've Tried

### Code Patterns Attempted
1. Simple pattern (CustomModule + BarDataListener) - Failed
2. ATR Trailing Stop pattern (extended base class) - Failed
3. DemoStrategies pattern (TradeDataListener + HistoricalDataListener) - Failed
4. Exact copy of AbsorptionIndicator - Failed
5. Exact copy of ATR Trailing Stop - Failed

### API Versions
1. API v1 (Layer1ApiVersionValue.VERSION1) - Failed
2. API v2 (Layer1ApiVersionValue.VERSION2) - Failed

### Java Bytecode Versions
1. Java 11 (major version 55) - Failed
2. Java 17 (major version 61) - Failed

### Build Methods
1. Direct javac compilation - Failed
2. Gradle build - Failed
3. Different jar naming conventions - Failed

### Environment Details
- **System Java**: OpenJDK 25.0.2
- **Bookmap Java**: OpenJDK 21.0.3 (bundled)
- **Bookmap Version**: 7.x
- **Plugin Location**: `/Users/brant/bl-projects/DemoStrategies/build/libs/`

### Log Pattern
Every attempt shows same pattern:
```
Loading strategy/indicator ... addToLayers: true
Strategy was loaded aware of the API agreement
Enabled for ESH6.CME@BMD true - started (Simplified L1)
Enabled for ESH6.CME@BMD true - complete (Simplified L1)
```

But `initialize()` never executes, so no System.err.println messages appear.

## Technical Requirements

### Current Working Code Structure
The strategy needs to implement these interfaces:
```java
public class OrderFlowStrategy implements CustomModule,
    BarDataListener, DepthDataListener, SnapshotEndListener {

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        // THIS METHOD NEVER GETS CALLED
    }

    @Override
    public void onBar(OrderBook orderBook, Bar bar) {
        // Process bars and detect signals
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        // Track order book changes
    }
}
```

### Key Files
- **Project**: `/Users/brant/bl-projects/DemoStrategies`
### Dependencies (from build.gradle)
```gradle
dependencies {
    compileOnly group: 'com.bookmap.api', name: 'api-core', version: '7.0.0.72';
    compileOnly group: 'com.bookmap.api', name: 'api-simplified', version: '7.0.0.72';
    compileOnly group: 'org.apache.commons', name: 'commons-lang3', version: '3.4'
}
```

## What We Need

### Immediate Fix
**Get `initialize()` to actually execute** so the strategy can:
1. Register indicators
2. Start tracking order flow
3. Detect trading signals
4. Show that it's working

### For Reference
**ATR Trailing Stop** works perfectly and has a more complex structure:
- Extends `AtrTrailingStopSettings` (abstract base class)
- Implements 6 different interfaces
- Has custom settings panels
- Uses multiple bar types and intervals

But simple strategies that copy its pattern still don't work.

## Additional Context

- User has "simple-demo" project renamed to "qid"
- User wants to add order flow automation to existing working indicators
- All other indicators (Absorption, LiquidityTracker, QuotesDelta, etc.) from simple-demo load but don't show in UI (possibly same issue?)
- Only ATR Trailing Stop works and shows indicators

## Next Steps Needed

1. **Debug why `initialize()` is not being called**
2. **Get ANY simple custom strategy to initialize() properly**
3. **Once initialization works, add the full order flow logic**
4. **Add order execution (Phase 2)** after signal detection works

## Contact/Context
- This is for Bookmap trading platform
- Using Simplified L1 API
- Goal: Algorithmic order flow trading
- Current blocker: No custom strategies will initialize
