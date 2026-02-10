# Custom Status Panels in Bookmap Strategies

## Overview

Bookmap allows strategies to add custom UI panels that display real-time metrics, signal counts, and strategy status. These panels appear in the settings dialog for the strategy.

## Key Components

### 1. CustomSettingsPanelProvider Interface

Implement this interface to enable custom panels:

```java
public class MyStrategy implements CustomModule, CustomSettingsPanelProvider {
    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        return new StrategyPanel[] { myPanel };
    }
}
```

### 2. StrategyPanel Class

From `velox.gui.StrategyPanel` - use standard Swing components:

```java
StrategyPanel panel = new StrategyPanel("Panel Title");
panel.setLayout(new GridLayout(rows, cols, hgap, vgap));
panel.add(new JLabel("Label:"));
panel.add(valueLabel);
```

### 3. Thread-Safe Updates

Always use SwingUtilities.invokeLater() for UI updates:

```java
@Override
public void onTrade(double price, int size, TradeInfo tradeInfo) {
    currentDelta += size;

    SwingUtilities.invokeLater(() -> {
        deltaLabel.setText(String.valueOf(currentDelta));
    });
}
```

## Complete Example

See: `OrderFlowStrategyWithPanel.java`

**Features:**
- Real-time delta display
- Cumulative delta tracking
- Signal counts (Absorption, Big Player, Retail Traps)
- Strategy status indicator

**Location:** Settings dialog → Strategy name → Custom panel tab

## Building and Loading

1. Build: `./gradlew clean jar`
2. Load in Bookmap: Settings → API plugins configuration → Add → Select JAR
3. View panel: Settings → API plugins → Select strategy → View custom panel

## Best Practices

1. **Update frequency**: Don't update UI on every event - use periodic updates (e.g., every 5-10 trades)
2. **Thread safety**: Always use SwingUtilities.invokeLater() for UI updates
3. **Simple layouts**: Use GridLayout for clean, organized metric displays
4. **Clear labels**: Use descriptive labels for all metrics
5. **Status indication**: Show strategy state (Active, Stopped, Error)

## Comparison: Indicators vs Panels

| Feature | Indicators | Custom Panels |
|---------|-----------|---------------|
| **Purpose** | Visual signals on chart | Real-time metrics display |
| **Location** | Main heatmap or bottom panel | Settings dialog |
| **Updates** | Plot points on chart | Update text/UI components |
| **Best For** | Price levels, signals, bubbles | Counts, values, status |
| **Thread Safety** | Built-in (addPoint is thread-safe) | Manual (SwingUtilities.invokeLater) |

## Common Use Cases

**Status Panels:**
- Current delta value
- Cumulative delta
- Signal counts
- Active/inactive status
- Parameter values

**Control Panels:**
- Parameter adjustment (sliders, spinners)
- Enable/disable signal types
- Reset buttons
- Threshold adjustments

## Resources

- Example: `OrderFlowStrategyWithPanel.java`
- Reference: `SettingsAndUiDemo.java`
- API: `velox.api.layer1.simplified.CustomSettingsPanelProvider`
- UI: `velox.gui.StrategyPanel`
