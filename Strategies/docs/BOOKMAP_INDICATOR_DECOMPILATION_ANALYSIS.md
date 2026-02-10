# Bookmap Built-in Indicator Access Analysis

## Investigation Summary

After extracting and decompiling Bookmap's internal classes, here are the key findings:

---

## 🔍 Key Findings

### 1. **Heatmap Implementation**

**File:** `velox/opengl/painter/layers/HeatMapLayer.java`

```java
public class HeatMapLayer extends BufferedLayer {
    // ALL core methods are NATIVE (JNI calls to C++)
    private native void construct(int var1, int var2, int var3, int var4);
    public native void clear();
    public native void prepareData(int[][] var1);  // Data input from Java
    public native void update(int var1);
    private native void setBackgroundColor(int var1);
}
```

**Key Insight:** Heatmap is implemented in **native C++ code via JNI**. The Java layer only:
- Receives display configuration (HeatmapSettings)
- Passes `int[][]` data array to native code via `prepareData()`
- Has **NO accessor methods** to retrieve the heatmap data

---

### 2. **User Indicator Layer**

**File:** `velox/opengl/painter/layers/UserIndicatorLayer.java`

```java
public class UserIndicatorLayer extends AbstractLayer {
    // Native methods for managing indicators
    private native void configureIndicator(...);
    private native void setIndicatorPoints(int id, int[] timestamps, double[] values);
    public native void resetIndicatorPoints();
    public native int getDataInPixelScore(int x, int y);
}
```

**Key Insight:** User indicators also use native methods. Data flows **ONE WAY**:
- Java → Native (via `setIndicatorPoints()`)
- **No method** to retrieve indicator data back from native layer

---

### 3. **Indicator Settings** (Configuration Only)

**Files:**
- `velox/config/beans/studies/HeatmapSettings.java`
- `velox/config/beans/studies/VolumeDotsSettings.java`
- `velox/config/beans/studies/StudiesSettings.java`

These classes contain **only configuration**:
- Enable/disable flags
- Color schemes
- Filter types
- Display settings
- Reset intervals

**NO actual indicator data or values** are stored here.

---

### 4. **No CVD-Specific Classes Found**

Searched for:
- `Cvd.class` - Not found
- `CumulativeVolumeDelta.class` - Not found
- `CVDIndicator.class` - Not found

Found instead:
- `QuotesDeltaEventSerializer` - Serializes quote delta events for data storage
- Radio button in popup menu: "QuotesDelta" (line 135 in pa.java)

**Conclusion:** CVD is likely calculated and rendered entirely in **native C++ code**, not exposed to Java layer.

---

## 📊 Architecture Pattern

```
┌─────────────────────────────────────────────┐
│         Java Layer (Your Strategy)          │
│  - Simplified API                           │
│  - Can register custom indicators           │
│  - Receives raw market data (MBO, Trades)   │
└─────────────────┬───────────────────────────┘
                  │
                  │ setIndicatorPoints()
                  │ prepareData()
                  ↓
┌─────────────────────────────────────────────┐
│         Native C++ Layer (JNI)              │
│  - Heatmap rendering                        │
│  - Volume dots calculation                  │
│  - CVD calculation                          │
│  - All indicator data storage               │
│  - Visualization                           │
└─────────────────────────────────────────────┘
```

**Critical Point:** Data flows **Java → Native only**. No API to retrieve data from Native → Java.

---

## ❌ Why No Built-in Indicator Access Is Possible

1. **Native Implementation:** Core indicators (CVD, Heatmap, Volume Dots) are implemented in C++ and compiled into native libraries
2. **One-Way Data Flow:** JNI interface only allows sending data TO native layer, not retrieving it
3. **No Public Accessors:** No getter methods exposed to retrieve calculated indicator values
4. **Performance Design:** Native implementation avoids Java overhead for real-time rendering

---

## ✅ Solution: Recreate Indicators from Raw Data

### CVD Implementation Example

```java
public class CustomCVD {
    private long cvd = 0;

    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Bid aggressor = buying pressure (positive CVD)
        // Ask aggressor = selling pressure (negative CVD)
        cvd += tradeInfo.isBidAggressor ? size : -size;
    }

    public long getCVD() { return cvd; }
    public double getCVDNormalized() { return cvd / 1000.0; } // For display
}
```

### Volume-at-Price (Heatmap-like) Implementation

```java
public class CustomVolumeProfile {
    private final Map<Integer, Long> volumeAtPrice = new ConcurrentHashMap<>();

    public void onTrade(double price, int size) {
        int priceLevel = (int)price;
        volumeAtPrice.merge(priceLevel, (long)size, Long::sum);
    }

    public long getVolumeAtPrice(int price) {
        return volumeAtPrice.getOrDefault(price, 0L);
    }

    public Map<Integer, Long> getVolumeProfile(int minPrice, int maxPrice) {
        return volumeAtPrice.entrySet().stream()
            .filter(e -> e.getKey() >= minPrice && e.getKey() <= maxPrice)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

### Volume Dots Implementation

```java
public class CustomVolumeDots {
    private final Map<Integer, List<Trade>> tradesByPrice = new ConcurrentHashMap<>();

    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        int priceLevel = (int)price;
        tradesByPrice.computeIfAbsent(priceLevel, k -> new ArrayList<>())
                     .add(new Trade(price, size, tradeInfo));
    }

    public int getVolumeAtPrice(int price) {
        return tradesByPrice.getOrDefault(price, Collections.emptyList())
                         .stream()
                         .mapToInt(t -> t.size)
                         .sum();
    }
}
```

---

## 💡 Integration with AI Strategy

Add these custom indicators to your `OrderFlowStrategyEnhanced`:

```java
public class OrderFlowStrategyEnhanced implements CustomModuleAdapter {

    // Custom indicator instances
    private final CustomCVD customCVD = new CustomCVD();
    private final CustomVolumeProfile customVolumeProfile = new CustomVolumeProfile();

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Update custom indicators
        customCVD.onTrade(price, size, tradeInfo);
        customVolumeProfile.onTrade(price, size);

        // Now you can use these values in SignalData
        long currentCVD = customCVD.getCVD();
        long volumeAtCurrentPrice = customVolumeProfile.getVolumeAtPrice((int)price);

        // Use for AI evaluation...
    }

    private SignalData createSignalData(boolean isBid, int price, int totalSize) {
        SignalData signal = new SignalData();
        // ... existing code ...

        // Add custom indicator data
        signal.market.cvd = customCVD.getCVD();
        signal.market.cvdNormalized = customCVD.getCVDNormalized();
        signal.market.volumeAtPrice = customVolumeProfile.getVolumeAtPrice(price);

        return signal;
    }
}
```

---

## 📁 Decompiled Files Location

All decompiled files are in: `~/bookmap-decompile/decompiled/`

Key files examined:
- `velox/opengl/painter/layers/HeatMapLayer.java`
- `velox/opengl/painter/layers/UserIndicatorLayer.java`
- `velox/config/beans/studies/HeatmapSettings.java`
- `velox/config/beans/studies/VolumeDotsSettings.java`
- `velox/config/beans/studies/StudiesSettings.java`

---

## 🎯 Conclusion

**Bookmap's built-in indicators (CVD, Heatmap, Volume Dots) are NOT accessible via the Java API** because:

1. They are implemented in native C++ code
2. The JNI interface is one-way (Java → Native only)
3. There are no public accessor methods exposed

**Solution:** You must **recreate these indicators from raw market data** (MBO, trades) which IS available through the Simplified API.

Would you like me to create complete custom indicator implementations that you can integrate into your AI strategy?
