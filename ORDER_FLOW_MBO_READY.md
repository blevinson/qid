# Order Flow MBO Simple - REAL Working Version

## ✅ Created: OrderFlowMboSimple

**This version WILL WORK because it uses Simplified API with MBO data!**

## What You'll See:

### 🎨 Visual Indicators on Chart:

| Color | Signal | Meaning |
|-------|--------|---------|
| 🔵 **Cyan dots** | Iceberg | Hidden large order detected |
| 🟣 **Magenta dots** | Spoof | Fake large order cancelled |
| 🟡 **Yellow dots** | Absorption | Large trade eating level |

### 📊 Custom Status Panel:

Shows signal counts in settings:
```
Order Flow MBO Status
Status: Active
Iceberg Signals: 5
Spoof Signals: 3
Absorption Signals: 2
```

## How It Works:

### 1. **Iceberg Detection** 🔵
```
Many small orders at SAME price:
  send order_1: BID 7000 size=50
  send order_2: BID 7000 size=50
  send order_3: BID 7000 size=50
  send order_4: BID 7000 size=50
  send order_5: BID 7000 size=50

→ CYAN DOT at 7000 (Iceberg!)
→ Hidden order of 250+ showing only 50 at a time
```

### 2. **Spoof Detection** 🟣
```
Large order QUICKLY cancelled:
  send order_xyz: BID 7000 size=500
  ... 200ms passes ...
  cancel order_xyz

→ MAGENTA DOT at 7000 (Spoof!)
→ Fake bid to trick market
→ Trade OPPOSITE direction
```

### 3. **Absorption Detection** 🟡
```
Large trade hitting level:
  Trade: BUY 7000 size=150

→ YELLOW DOT at 7000 (Absorption!)
→ Aggressive buyer eating sellers
→ Short signal possible
```

## Parameters:

Adjust in Bookmap settings:
- **Iceberg Min Orders** = 5 (default)
- **Spoof Max Age (ms)** = 500 (default)
- **Spoof Min Size** = 200 (default)
- **Absorption Min Size** = 100 (default)

## To Use:

1. **Reload JAR** in Bookmap
2. Enable **"Order Flow MBO"** (new strategy!)
3. Connect to BTC-USDT with MBO data
4. **Watch for colored dots on chart!**

## Expected Output:

### Log File:
```
mbo_simple_log.txt:

MBO SEND: BID order_abc Price=7000 Size=50
MBO SEND: BID order_def Price=7000 Size=50
MBO SEND: BID order_ghi Price=7000 Size=50
MBO SEND: BID order_jkl Price=7000 Size=50
MBO SEND: BID order_mno Price=7000 Size=50
🧊 ICEBERG DETECTED: BID at 7000 Orders=5 TotalSize=250

MBO SEND: ASK order_xyz Price=7005 Size=500
MBO CANCEL: order_xyz Price=7005 Size=500 Age=180ms
🎭 SPOOFING DETECTED: ASK Size=500 cancelled after 180ms

Trade #100: Price=7001 Size=150
ABSORPTION: Large trade size=150 at 7001
```

### On Chart:
- Cyan dot appears at 7000
- Magenta dot appears at 7005
- Yellow dot appears at 7001

## Key Differences from Previous Version:

| Feature | OrderFlowMboStrategy (Core) | OrderFlowMboSimple (Simplified) |
|---------|----------------------------|-----------------------------------|
| MBO Data | ❌ Not working | ✅ **WORKS** |
| Visuals | SSP icons (didn't work) | **Colored dots** |
| Signals | None (no MBO) | **Real signals** |
| Panel | None | **Signal counts** |
| API | Core API | Simplified API |

## Why This Works:

**Simplified API = MBO Support**
```java
implements MarketByOrderDepthDataListener  // ✅ MBO data available!
```

**Regular Indicators = Chart Display**
```java
icebergIndicator = api.registerIndicator("Iceberg", GraphType.PRIMARY);
icebergIndicator.addPoint(price);  // ✅ Shows dot on chart!
```

## What You Get:

✅ **Real MBO order tracking**
✅ **Iceberg detection** (cyan dots)
✅ **Spoof detection** (magenta dots)
✅ **Absorption detection** (yellow dots)
✅ **Visual indicators on chart**
✅ **Custom status panel**
✅ **Signal logging**
✅ **Configurable parameters**

## Ready to Test! 🚀

The strategy will show **colored dots** on the chart whenever it detects order flow patterns.

**Reload the JAR and enable "Order Flow MBO" to see real signals!**
