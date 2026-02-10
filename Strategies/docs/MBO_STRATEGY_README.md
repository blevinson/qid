# Order Flow MBO Strategy - Complete Guide

## Overview

**Strategy Name:** "Order Flow MBO Strategy"

This strategy uses **Market By Order (MBO)** data to track individual limit orders and detect real order flow patterns:

### Signals Detected:

1. **ICEBERG** - Hidden large orders showing as repeated small orders at same price
2. **SPOOF** - Fake large orders cancelled quickly to manipulate market
3. **ABSORPTION** - Large orders absorbing aggressive volume
4. **SMART MONEY** - Orders repositioning with market flow

## How MBO Data Works

### Individual Order Tracking

Every limit order gets a **unique ID** and is tracked through its lifecycle:

```
Order Lifecycle:
1. send()    → NEW order placed
2. replace() → Order modified (price/size changed)
3. cancel()  → Order cancelled
```

### What We Track:

```java
OrderInfo {
    String orderId;      // Unique ID: "abc123"
    boolean isBid;       // true=buy, false=sell
    int price;           // Limit price
    int size;            // Order size
    long creationTime;   // When placed
    long modificationTime; // Last modified
    boolean wasCancelled; // Cancelled?
    boolean wasReplaced;  // Modified?
}
```

## Signal Detection Logic

### 1. ICEBERG ORDERS

**Pattern:**
```
Many small orders at SAME price:
  send order_1: BID 6998 size=50
  send order_2: BID 6998 size=50
  send order_3: BID 6998 size=50
  send order_4: BID 6998 size=50
  send order_5: BID 6998 size=50

→ ICEBERG! Large hidden order (250+) showing only 50 at a time
```

**Detection:**
```java
if (ordersAtPrice.size() >= ICEBERG_MIN_ORDERS) {
    // Signal: Iceberg detected
    // BID iceberg = support (long entry)
    // ASK iceberg = resistance (short entry)
}
```

**Parameters:**
- `ICEBERG_MIN_ORDERS = 5` - Minimum orders at same price
- `ICEBERG_PRICE_TOLERANCE = 0` - Exact price match required

**Trading:**
- **Bid iceberg** → GO LONG (support holding)
- **Ask iceberg** → GO SHORT (resistance holding)

---

### 2. SPOOFING (Fake Liquidity)

**Pattern:**
```
Large order appears then QUICKLY cancelled:
  send order_xyz: BID 6998 size=500  ← Big bid!
  ... 200ms passes ...
  cancel order_xyz                     ← Gone!

→ SPOOFING! Fake bid to trick market into thinking there's demand
```

**Detection:**
```java
if (order.size >= SPOOF_MIN_SIZE && order.age < SPOOF_MAX_AGE_MS) {
    // Signal: Spoofing detected
    // Fake BID = actually bearish (they removed real buying)
    // Fake ASK = actually bullish (they removed real selling)
}
```

**Parameters:**
- `SPOOF_MIN_SIZE = 200` - Minimum size to qualify
- `SPOOF_MAX_AGE_MS = 500` - Max age (500ms)

**Trading:**
- **Bid spoof cancelled** → GO SHORT (fake buying pressure)
- **Ask spoof cancelled** → GO LONG (fake selling pressure)

---

### 3. ABSORPTION

**Pattern:**
```
Large limit order eating aggressive market orders:
  Level: ASK 7000 size=500  ← Big ask wall
  Trade: BUY 7000 size=50   ← Gets eaten
  Trade: BUY 7000 size=30   ← Gets eaten
  Trade: BUY 7000 size=70   ← Gets eaten

→ ABSORPTION! Wall preventing price from rising
```

**Detection:**
```java
if (tradeSize >= ABSORPTION_MIN_SIZE) {
    // Check if trade hit a tracked large order
    if (hitLargeOrder) {
        // Signal: Absorption
    }
}
```

**Parameters:**
- `ABSORPTION_MIN_SIZE = 100` - Minimum trade size

**Trading:**
- **Ask absorption** → GO SHORT (sellers in control)
- **Bid absorption** → GO LONG (buyers in control)

---

### 4. SMART MONEY (Order Repositioning)

**Pattern:**
```
Orders following market movement:
  Order: BID 6998 → replaced to 6999 → replaced to 7000
  (Price moving UP, bid orders following)

→ SMART MONEY positioning with trend
```

**Detection:**
```java
if (isBid && priceIncreased || !isBid && priceDecreased) {
    // Order repositioning with market
    // Could indicate institutional positioning
}
```

**Trading:**
- Currently logs but doesn't generate signals (can be enabled)

---

## TP/SL Calculation

All signals use **ATR-based stops** with **2:1 risk/reward**:

```java
double atrValue = calculateATR();
double atrMultiplier = 1.5;
double riskRewardRatio = 2.0;

// For LONG:
stopLoss = entry - (atr * atrMultiplier);
takeProfit = entry + (atr * atrMultiplier * riskRewardRatio);

// For SHORT:
stopLoss = entry + (atr * atrMultiplier);
takeProfit = entry - (atr * atrMultiplier * riskRewardRatio);
```

**Example:**
```
BTC-USDT price: 70,000
ATR: 100

Entry: 70,000
Stop Loss: 69,850  (70,000 - 150)
Take Profit: 70,300  (70,000 + 300)

Risk: 150 ticks | Reward: 300 ticks | R:R = 2:1
```

---

## Visual Output

### On Chart:

**Each signal shows:**
```
     TP (green line + label)
      │
      │
     Entry (triangle icon)
      │
      │
     SL (red line + label)
```

**Icon colors:**
- 🟢 Green triangle ↑ = BUY signal
- 🔴 Red triangle ↓ = SELL signal

**Log output:**
```
ICEBERG DETECTED: BID at 6998 Orders=5 TotalSize=250
SIGNAL #1: ICEBERG BUY @ 6998.0 | TP: 7048.0 | SL: 6973.5 | Reason: Iceberg order: 5 orders

SPOOFING DETECTED: ASK Size=300 cancelled after 150ms
SIGNAL #2: SPOOF BUY @ 7000.0 | TP: 7050.0 | SL: 6975.0 | Reason: Spoof detected: fake ask

ABSORPTION: Large trade size=150 hitting level at 6999
SIGNAL #3: ABSORPTION SELL @ 6999.0 | TP: 6949.0 | SL: 7024.0 | Reason: Large trade absorbed
```

---

## Configuration

### Parameters (adjustable in code):

```java
// Detection thresholds
private static final int ABSORPTION_MIN_SIZE = 100;     // Min size for absorption
private static final int SPOOF_MAX_AGE_MS = 500;        // Max age for spoof (ms)
private static final int SPOOF_MIN_SIZE = 200;          // Min size for spoof
private static final int ICEBERG_MIN_ORDERS = 5;        // Min orders for iceberg
private static final int ICEBERG_PRICE_TOLERANCE = 0;   // Price tolerance

// TP/SL calculation
private static final double ATR_MULTIPLIER = 1.5;       // ATR multiplier for stops
private static final double RISK_REWARD_RATIO = 2.0;    // Risk/reward ratio
```

### Tuning Guide:

**More signals (less sensitive):**
- Increase `ABSORPTION_MIN_SIZE` to 200
- Increase `SPOOF_MAX_AGE_MS` to 1000
- Increase `ICEBERG_MIN_ORDERS` to 10

**Fewer signals (more sensitive):**
- Decrease `ABSORPTION_MIN_SIZE` to 50
- Decrease `SPOOF_MAX_AGE_MS` to 200
- Decrease `ICEBERG_MIN_ORDERS` to 3

---

## File Structure

**Main Strategy:**
- `OrderFlowMboStrategy.java` - Core API + MBO + SSP

**Logs:**
- `/Users/brant/bl-projects/DemoStrategies/mbo_strategy_log.txt`

**Bookmap display:**
- Settings → API plugins → "Order Flow MBO Strategy"

---

## Building and Loading

```bash
cd /Users/brant/bl-projects/DemoStrategies/Strategies
./gradlew clean jar
```

**In Bookmap:**
1. Settings → API plugins configuration
2. Add → Select: `build/libs/bm-strategies.jar`
3. Enable "Order Flow MBO Strategy"
4. Connect to BTC-USDT or any instrument with MBO data

---

## Expected Behavior

### On Active Market:

1. **Order placements tracked:**
   ```
   MBO SEND: BID order_123 Price=6998 Size=50
   MBO SEND: ASK order_124 Price=6999 Size=75
   ```

2. **Order modifications:**
   ```
   MBO REPLACE: order_123 Price: 6998->6999 Size: 50->60
   SMART MONEY: Order order_123 repositioning with market
   ```

3. **Order cancellations:**
   ```
   MBO CANCEL: order_124 Price=6999 Size=75 Age=120ms
   ```

4. **Signal generation:**
   ```
   ICEBERG DETECTED: ASK at 7000 Orders=6 TotalSize=300
   SIGNAL #5: ICEBERG SELL @ 7000.0 | TP: 6950.0 | SL: 7025.0
   ```

### On Chart:

Every signal displays:
- Entry icon (triangle)
- TP line (green)
- SL line (red)
- Labels ("TP", "SL")
- Auto-removes after 20 signals

---

## Key Advantages of MBO

| Feature | Regular Depth | MBO Strategy |
|---------|--------------|--------------|
| See individual orders | ❌ | ✅ |
| Track order lifecycle | ❌ | ✅ |
| Detect spoofing | ❌ | ✅ |
| Detect icebergs | ❌ | ✅ |
| See order modifications | ❌ | ✅ |
| Track order age | ❌ | ✅ |
| Real absorption detection | ❌ | ✅ |

---

## Troubleshooting

**No signals appearing?**
1. Check logs: `tail -f mbo_strategy_log.txt`
2. Verify MBO data is available (instrument supports MBO)
3. Lower thresholds for testing
4. Verify data feed is active

**Too many signals?**
1. Increase detection thresholds
2. Increase `ICEBERG_MIN_ORDERS`
3. Increase `SPOOF_MIN_SIZE`
4. Increase `ABSORPTION_MIN_SIZE`

**Signals not visible on chart?**
1. Check Bookmap logs for errors
2. Verify instrument is connected
3. Zoom in/out to see different price levels
4. Check if signals are being generated in log file

---

## Performance

**Memory:**
- Orders tracked per instrument
- Auto-removes cancelled orders
- Max 20 signals stored

**CPU:**
- Event-driven (only processes MBO events)
- Efficient HashMap lookups
- O(1) order tracking

**Network:**
- MBO data can be heavy on high-volume instruments
- Consider filtering if needed

---

## Next Steps

**Enhancements to consider:**

1. **Volume Profile** - Track cumulative volume at each level
2. **Order Flow Imbalance** - Bid vs ask order flow
3. **Passive vs Aggressive** - Split market orders vs limit orders
4. **Time & Sales** - Trade analysis with MBO context
5. **Market Depth Heatmap** - Visualize order book changes

---

*Built with: Bookmap Simplified L1 API v7.6.0.20 + Core API for SSP + MBO*
*Tested on: BTC-USDT.Binance*
*Last Updated: 2025-02-09*
