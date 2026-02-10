# SSP Trade Signal Visualization

## Overview

**SSP (Screen Space Painter)** allows you to draw custom graphics on Bookmap charts - perfect for displaying **trade setups with buy/sell icons, take profit (TP), and stop loss (SL) levels**.

## What This Does

The `OrderFlowStrategyWithSSP` strategy:

1. **Detects order flow signals** (absorption, big player activity, retail traps)
2. **Calculates TP/SL levels** based on:
   - ATR (Average True Range) for volatility-based stops
   - Swing highs/lows for structure-based stops
   - Risk/Reward ratio (default 2:1)
3. **Draws visual trade setups** on the chart:
   - Green triangle ↑ for BUY signals
   - Red triangle ↓ for SELL signals
   - Green line + "TP" label at take profit
   - Red line + "SL" label at stop loss

## Visual Representation

```
     TP ←─────────┐  Green line + label
                 │
                 │
     Entry ──────┼─► Green triangle (BUY) or Red triangle (SELL)
                 │
                 │
     SL ←─────────┘  Red line + label
```

## TP/SL Calculation

### For LONG positions (BUY signals):
- **Entry**: Signal price
- **Stop Loss**: Entry - (ATR × 1.5) OR nearest swing low
- **Take Profit**: Entry + (ATR × 1.5 × Risk/Reward)
- **Risk/Reward**: Default 2:1 (can be adjusted)

### For SHORT positions (SELL signals):
- **Entry**: Signal price
- **Stop Loss**: Entry + (ATR × 1.5) OR nearest swing high
- **Take Profit**: Entry - (ATR × 1.5 × Risk/Reward)
- **Risk/Reward**: Default 2:1 (can be adjusted)

## Code Structure

### 1. Implements ScreenSpacePainterFactory

```java
public class OrderFlowStrategyWithSSP implements ScreenSpacePainterFactory {

    @Override
    public ScreenSpacePainter createScreenSpacePainter(
        String indicatorName,
        String indicatorAlias,
        ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        // Create canvas for drawing
        ScreenSpaceCanvas heatmapCanvas = screenSpaceCanvasFactory
            .createCanvas(ScreenSpaceCanvasType.HEATMAP);

        return new ScreenSpacePainterAdapter() {
            // Painting logic here
        };
    }
}
```

### 2. TradeSignal Data Structure

```java
private class TradeSignal {
    long timestamp;      // When signal was generated
    double entryPrice;   // Entry price
    double takeProfit;   // TP price
    double stopLoss;     // SL price
    boolean isLong;      // true = buy, false = sell
    String signalType;   // "ABSORPTION", "BIG_PLAYER", "RETAIL_TRAP"
}
```

### 3. Drawing Icons

**Buy Icon (Green Triangle Up):**
```java
private PreparedImage createBuyIcon(Color color) {
    BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    Polygon triangle = new Polygon();
    triangle.addPoint(10, 2);   // top
    triangle.addPoint(2, 18);   // bottom left
    triangle.addPoint(18, 18);  // bottom right
    g.fill(triangle);
    g.dispose();
    return new PreparedImage(image);
}
```

**Sell Icon (Red Triangle Down):**
```java
private PreparedImage createSellIcon(Color color) {
    BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    Polygon triangle = new Polygon();
    triangle.addPoint(10, 18);  // bottom
    triangle.addPoint(2, 2);    // top left
    triangle.addPoint(18, 2);   // top right
    g.fill(triangle);
    g.dispose();
    return new PreparedImage(image);
}
```

### 4. Drawing Trade Setup

```java
private List<CanvasIcon> drawTradeSignal(ScreenSpaceCanvas canvas, TradeSignal signal) {
    List<CanvasIcon> icons = new ArrayList<>();

    // 1. Draw entry icon at signal price
    HorizontalCoordinate x = new RelativeDataHorizontalCoordinate(
        RelativeDataHorizontalCoordinate.HORIZONTAL_DATA_ZERO, signal.timestamp);
    VerticalCoordinate yEntry = new RelativeDataVerticalCoordinate(
        RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.entryPrice);

    // 2. Draw line to TP
    VerticalCoordinate yTP = new RelativeDataVerticalCoordinate(
        RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.takeProfit);
    CanvasIcon tpLine = createLineIcon(x, yEntry, x, yTP, Color.GREEN, 2);
    canvas.addShape(tpLine);
    icons.add(tpLine);

    // 3. Draw line to SL
    VerticalCoordinate ySL = new RelativeDataVerticalCoordinate(
        RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.stopLoss);
    CanvasIcon slLine = createLineIcon(x, yEntry, x, ySL, Color.RED, 2);
    canvas.addShape(slLine);
    icons.add(slLine);

    // 4. Add labels
    // ...

    return icons;
}
```

### 5. ATR Calculation (for volatility-based stops)

```java
private void updateATR(double currentPrice) {
    if (priceHistory.size() < 2) return;

    double prevPrice = priceHistory.get(0);
    double trueRange = Math.abs(currentPrice - prevPrice);

    trueRanges.addLast(trueRange);
    if (trueRanges.size() > atrPeriod) {
        trueRanges.removeFirst();
    }

    // Calculate average
    double sum = 0;
    for (Double tr : trueRanges) {
        sum += tr;
    }
    atr = sum / trueRanges.size();
}
```

### 6. Swing High/Low Detection (for structure-based stops)

```java
private void updateSwingPoints(double price) {
    if (priceHistory.size() < SWING_PERIOD * 2 + 1) return;

    // Check for swing high (price higher than neighbors)
    boolean isSwingHigh = true;
    for (int i = 1; i <= SWING_PERIOD; i++) {
        Double left = priceHistory.get(priceHistory.size() - SWING_PERIOD - i);
        Double right = priceHistory.get(priceHistory.size() - i);
        if (price <= left || price <= right) {
            isSwingHigh = false;
            break;
        }
    }

    if (isSwingHigh) {
        swingHighs.addLast(price);
    }

    // Similar logic for swing lows...
}
```

### 7. Generating Trade Signals

```java
private void generateTradeSignal(double entryPrice, boolean isLong, String signalType) {
    double atrValue = atr > 0 ? atr : entryPrice * 0.002;

    double stopLoss, takeProfit;

    if (isLong) {
        // LONG position
        stopLoss = entryPrice - (atrValue * atrMultiplier);

        // Adjust to nearest swing low if available
        if (!swingLows.isEmpty()) {
            double nearestSwingLow = swingLows.getLast();
            if (nearestSwingLow < entryPrice && nearestSwingLow > stopLoss) {
                stopLoss = nearestSwingLow;
            }
        }

        takeProfit = entryPrice + (atrValue * atrMultiplier * riskRewardRatio);
    } else {
        // SHORT position
        stopLoss = entryPrice + (atrValue * atrMultiplier);

        // Adjust to nearest swing high if available
        if (!swingHighs.isEmpty()) {
            double nearestSwingHigh = swingHighs.getLast();
            if (nearestSwingHigh > entryPrice && nearestSwingHigh < stopLoss) {
                stopLoss = nearestSwingHigh;
            }
        }

        takeProfit = entryPrice - (atrValue * atrMultiplier * riskRewardRatio);
    }

    // Create and store signal
    TradeSignal signal = new TradeSignal(
        System.nanoTime(),
        entryPrice,
        takeProfit,
        stopLoss,
        isLong,
        signalType
    );

    tradeSignals.addLast(signal);
    if (tradeSignals.size() > MAX_TRADE_SIGNALS) {
        tradeSignals.removeFirst();
    }
}
```

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `Absorption Threshold` | 50 | Minimum size for absorption detection |
| `Big Player Multiplier` | 5.0 | Multiplier for average delta (big player threshold) |
| `Risk/Reward Ratio` | 2.0 | Target R:R for TP calculation |
| `ATR Period` | 14 | Period for ATR calculation |
| `ATR Multiplier for SL` | 1.5 | ATR multiplier for stop loss distance |

## Signal Types

### 1. ABSORPTION
- Large bid/ask walls absorbing aggressive volume
- Indicates potential reversal (support/resistance)

### 2. BIG_PLAYER
- Aggressive buying/selling from large participants
- Strong directional move likely

### 3. RETAIL_TRAP
- Price moves one way but delta moves opposite
- Potential reversal (retail traders trapped)

## Coordinate Systems

SSP uses two coordinate systems:

### 1. DATA Coordinates (fixed to chart)
```java
// Fixed at specific time/price
HorizontalCoordinate x = new RelativeDataHorizontalCoordinate(
    RelativeDataHorizontalCoordinate.HORIZONTAL_DATA_ZERO, timestamp);

VerticalCoordinate y = new RelativeDataVerticalCoordinate(
    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, price);
```

### 2. PIXEL Coordinates (relative to data)
```java
// Offset from data coordinate in pixels
HorizontalCoordinate x1 = new RelativeDataHorizontalCoordinate(x, -10); // 10px left
VerticalCoordinate y1 = new RelativeDataVerticalCoordinate(y, -10);      // 10px up
```

## Best Practices

1. **Limit signal count**: Keep only recent signals (MAX_TRADE_SIGNALS = 20)
2. **Cache icons**: Reuse PreparedImage objects to avoid memory issues
3. **Track displayed icons**: Remove old icons before adding new ones
4. **Use ATR for volatility**: Adapts stop distance to market conditions
5. **Combine with structure**: Use swing highs/lows for confluence

## Building and Loading

```bash
cd /Users/brant/bl-projects/DemoStrategies/Strategies
./gradlew clean jar
```

1. Load in Bookmap: Settings → API plugins → Add → `build/libs/bm-strategies.jar`
2. Enable "OF Strategy with Trade Signals"
3. Trade signals will appear with icons and TP/SL lines

## Example Output

```
TRADE SIGNAL: BUY ABSORPTION @ 27935.00 | TP: 27965.00 | SL: 27920.00 | R/R: 2.0
TRADE SIGNAL: SELL BIG_PLAYER @ 27940.00 | TP: 27910.00 | SL: 27955.00 | R/R: 2.0
TRADE SIGNAL: BUY RETAIL_TRAP @ 27925.00 | TP: 27955.00 | SL: 27915.00 | R/R: 2.0
```

## Resources

- **Example**: `OrderFlowStrategyWithSSP.java`
- **Reference**: `Layer1OrdersOverlayDemo.java` (fake orders demo)
- **API**: `velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter`
- **Canvas**: `velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas`

## Key Differences from Indicators

| Feature | Indicators | SSP |
|---------|-----------|-----|
| **Use case** | Plotting data points | Drawing custom graphics |
| **Visualization** | Dots, lines, bubbles | Icons, images, shapes |
| **Coordinates** | Price only | Time + Price |
| **Flexibility** | Limited | Full graphics control |
| **Best for** | Time series, levels | Trade setups, annotations |

## Summary

This SSP approach provides **complete trade visualization**:
- ✅ Clear entry points (buy/sell icons)
- ✅ Calculated take profit levels (green lines)
- ✅ Calculated stop loss levels (red lines)
- ✅ ATR-based volatility adjustment
- ✅ Structure-based swing levels
- ✅ Proper risk/reward ratios
- ✅ Professional trade setup display
