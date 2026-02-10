# Order Flow Strategy - Implementation Guide

## Overview

The OrderFlowStrategy has been successfully created and compiled. It implements automated order flow trading concepts for Bookmap, detecting absorption, delta imbalances, and retail traps.

## Location

- **Source Code**: `/Users/brant/bl-projects/DemoStrategies/Strategies/src/main/java/velox/api/layer1/simplified/demo/OrderFlowStrategy.java`
- **Compiled JAR**: `/Users/brant/bl-projects/DemoStrategies/Strategies/build/libs/bm-strategies.jar`

## How to Use

### 1. Build the Strategy

```bash
cd /Users/brant/bl-projects/DemoStrategies/Strategies
./gradlew jar
```

### 2. Load into Bookmap

1. Open Bookmap
2. Go to **Settings → API plugins configuration**
3. Click **Add**
4. Select the JAR file: `/Users/brant/bl-projects/DemoStrategies/Strategies/build/libs/bm-strategies.jar`
5. Choose **"Order Flow Strategy"** from the popup window
6. Enable it using the checkbox on the left

### 3. Configure Parameters

The strategy has several configurable parameters (adjust in Bookmap's UI):

- **Absorption Threshold (contracts)**: Minimum size for large orders (default: 50)
- **Big Player Multiplier**: Multiplier for detecting large players (default: 5.0)
- **Delta Window Size (bars)**: Number of bars for delta average (default: 10)
- **Show Absorption Signals**: Toggle absorption detection (default: true)
- **Show Delta Signals**: Toggle delta signals (default: true)
- **Show Retail Trap Signals**: Toggle retail trap detection (default: true)

## What the Strategy Does

### 1. **Absorption Detection**
- **Bid Absorption**: Large bid walls that absorb selling pressure
- **Ask Absorption**: Large ask walls that absorb buying pressure
- **Method**: Uses dual moving averages (short=5, long=20) to detect size spikes

### 2. **Delta Analysis**
- Tracks aggressive buying vs selling pressure
- **Current Delta**: Individual trade direction
- **Cumulative Delta**: Running total of buy/sell pressure
- **Average Delta**: Baseline for detecting big player activity

### 3. **Retail Trap Detection**
- **Bullish Trap**: Price rising but delta negative (potential reversal down)
- **Bearish Trap**: Price falling but delta positive (potential reversal up)

### 4. **Signal Generation**
- **Bullish Signal**: Large bid wall + positive delta
- **Bearish Signal**: Large ask wall + negative delta
- **Retail Trap Signal**: Price/delta divergence

## Visual Indicators

The strategy displays the following indicators in Bookmap:

### Primary Panel (on Heatmap)
- **Absorption Bid** (Red): Large bid walls detected
- **Absorption Ask** (Green): Large ask walls detected
- **Bullish Signal** (Green): Bullish trading signals
- **Bearish Signal** (Red): Bearish trading signals
- **Retail Trap** (Orange): Potential reversal zones

### Bottom Panel
- **Bid Size** (Red): Current bid size
- **Ask Size** (Green): Current ask size
- **Delta** (Cyan): Current trade delta
- **Cumulative Delta** (Yellow): Running delta total

## Technical Implementation

### Interfaces Implemented
- `CustomModule`: Main module interface
- `TradeDataListener`: Receives trade data
- `DepthDataListener`: Receives order book depth updates
- `BboListener`: Receives best bid/offer updates
- `HistoricalDataListener`: Enables historical data processing

### Key Features
- ✅ Properly follows Bookmap Simplified API patterns
- ✅ Thread-safe order book updates
- ✅ Efficient moving average calculations
- ✅ Configurable parameters via UI
- ✅ Multiple visual indicators for analysis
- ✅ Real-time signal detection

## Architecture Notes

### Order Book Management
- Uses `OrderBook` utility class from Bookmap API
- Updates on both BBO changes and depth updates
- Thread-safe access patterns

### Signal Detection Logic
- **Absorption**: Compares short vs long MA of bid/ask sizes
- **Delta**: Tracks trade direction and magnitude
- **Retail Traps**: Compares price direction vs delta direction

### Performance Considerations
- Efficient deque-based delta history tracking
- Incremental moving average calculations
- Minimal object creation in hot paths

## Next Steps (Phase 2)

To extend this strategy with order execution:

1. **Add Order Execution Interface**
   ```java
   implements OrdersListener, PositionListener, BalanceListener
   ```

2. **Implement Trading Logic**
   - Marketable limit orders (2-tick cap)
   - Automatic bracket orders (take-profit + stop-loss)
   - Breakeven stops after 5 ticks profit

3. **Add Safety Framework**
   ```java
   private static final boolean SIM_ONLY = true;  // Safety first!
   private static final int MAX_POSITION_SIZE = 1;
   private static final int MAX_ORDERS_PER_MINUTE = 4;
   private static final long COOLDOWN_AFTER_STOP_LOSS_MS = 30_000;
   ```

4. **Add Order Management**
   - Position tracking
   - Order state management
   - Risk limit enforcement

## Troubleshooting

### Strategy doesn't appear in Bookmap
1. Verify JAR was built: `ls -lh build/libs/bm-strategies.jar`
2. Check Bookmap logs for errors
3. Ensure Java version compatibility (Java 17+)

### Indicators not showing
1. Verify strategy is enabled (checkbox checked)
2. Check if you're subscribed to a data feed
3. Try adjusting indicator visibility settings

### No signals appearing
1. Lower the **Absorption Threshold** parameter
2. Decrease the **Big Player Multiplier**
3. Verify you're receiving live market data

## Credits

Based on order flow trading concepts from:
- [Order Flow Trading with DOM and Heat Maps](https://www.youtube.com/watch?v=7facFfjQ0UE&list=PLWQioWs8oOiFKQnwIIYbw8N7ks7d-UAPQ&index=8)

Built with Bookmap Simplified L1 API v7.6.0.20

## Disclaimer

**This strategy is for educational purposes only.**

- Always test in simulation mode first
- Never risk real money until thoroughly validated
- Past performance does not guarantee future results
- You are responsible for your own trading decisions
- The authors accept no liability for trading losses
