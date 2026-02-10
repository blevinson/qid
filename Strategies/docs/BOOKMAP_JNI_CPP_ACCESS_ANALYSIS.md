# Bookmap C++/JNI Access Analysis

## Executive Summary

After examining Bookmap's native libraries and JNI interfaces, the short answer is: **❌ No practical way to access built-in indicator data via C++/JNI**.

However, there are **theoretical approaches** (with significant limitations) discussed below.

---

## 🔍 Native Library Analysis

### Library Found

**File:** `/Applications/Bookmap.app/Contents/app/lib/mac/libpainter.dylib`
- **Size:** 909 KB
- **Type:** Mach-O 64-bit dynamically linked shared library
- **Purpose:** Core rendering and indicator calculation engine

### JNI Symbols Discovered

**380 JNI functions** total, including:

#### HeatMapLayer Functions
```c
Java_velox_opengl_painter_layers_HeatMapLayer_construct
Java_velox_opengl_painter_layers_HeatMapLayer_prepareData(int[][] data)  // ← Data IN
Java_velox_opengl_painter_layers_HeatMapLayer_update(int)
Java_velox_opengl_painter_layers_HeatMapLayer_clear()
Java_velox_opengl_painter_layers_HeatMapLayer_setBackgroundColor(int color)
Java_velox_opengl_painter_layers_HeatMapLayer_getRowHeight()           // ← Geometric query
Java_velox_opengl_painter_layers_HeatMapLayer_middleOfRow(int row)     // ← Geometric query
Java_velox_opengl_painter_layers_HeatMapLayer_rowAtY(int y)            // ← Geometric query
```

#### UserIndicatorLayer Functions
```c
Java_velox_opengl_painter_layers_UserIndicatorLayer_configureIndicator(...)
Java_velox_opengl_painter_layers_UserIndicatorLayer_setIndicatorPoints(int id, int[] timestamps, double[] values)  // ← Data IN
Java_velox_opengl_painter_layers_UserIndicatorLayer_resetIndicatorPoints()
Java_velox_opengl_painter_layers_UserIndicatorLayer_getDataInPixelScore(int x, int y)  // ← POTENTIAL DATA OUT!
Java_velox_opengl_painter_layers_UserIndicatorLayer_getLastHighScoredIndicatorId()     // ← Metadata
Java_velox_opengl_painter_layers_UserIndicatorLayer_getColorNative()                    // ← Metadata
```

---

## ❌ Why Native Access Is Not Practical

### 1. **One-Way Data Flow Design**

The JNI interface is designed for:
```
Java Layer          Native C++ Layer
     │                      │
     ├─ prepareData()  ────→│  Store/receive data
     ├─ update()       ────→│  Render/calculations
     │                      │
     │  ←───────────────────┤  Geometric queries only
     │   getRowHeight()
     │   rowAtY()
```

**No getter methods exist** for:
- ❌ Heatmap data values
- ❌ CVD values
- ❌ Volume profile data
- ❌ Calculated indicator values

### 2. **No Public Headers or SDK**

Bookmap does NOT provide:
- ❌ C++ header files (.h/.hpp)
- ❌ Native SDK documentation
- ❌ API for accessing internal data structures
- ❌ Symbols for data retrieval functions

### 3. **Obfuscated Internal Functions**

C++ symbols are name-mangled (not human-readable):
```c
__ZN12HeatMapLayer11PrepareDataEPPjii  // HeatMapLayer::PrepareData(int**, int, int)
__ZN18UserIndicatorLayer18setIndicatorPointsEiPiPdi  // UserIndicatorLayer::setIndicatorPoints(...)
```

These are internal implementation details, not public APIs.

### 4. **L0 API is One-Way**

The **Layer 0 API** (Connect API) is designed for:
- ✅ Feeding data INTO Bookmap
- ❌ NOT for retrieving data FROM Bookmap

---

## 🤔 Theoretical Approaches (Not Recommended)

### Approach 1: **Pixel Scraping** via `getDataInPixelScore`

**Concept:** Use `getDataInPixelScore(x, y)` to query indicator values at screen coordinates

**Problems:**
- Only returns "score" (likely opacity/intensity value)
- Requires knowing exact pixel coordinates
- Must iterate through thousands of pixels
- Only works for currently visible area
- Extremely slow and inefficient
- Returns rendered values, not raw data

**Example (NOT RECOMMENDED):**
```java
// Theoretical pixel-scraping approach
public int[] scrapeHeatmapData() {
    int[] scores = new int[width * height];
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            scores[y * width + x] = userIndicatorLayer.getDataInPixelScore(x, y);
        }
    }
    return scores;  // Useless - just opacity values, not actual CVD/volume data
}
}
```

**Verdict:** ❌ Not practical - doesn't give you the actual indicator data

---

### Approach 2: **Custom JNI Library Injection**

**Concept:** Create your own .dylib that hooks into Bookmap's process and intercepts function calls

**Tools Required:**
- DYLD_INTERPOSE or function hooking
- Reverse engineering of Bookmap's internal memory structures
- Knowledge of C++ memory layout

**Problems:**
- ⚠️ **Extremely complex** - requires advanced reverse engineering
- ⚠️ **Fragile** - will break on every Bookmap update
- ⚠️ **Unsupported** - no documentation of internal structures
- ⚠️ **May violate ToS** - could be considered hacking/cracking
- ⚠️ **Security risks** - bypasses normal API boundaries

**Verdict:** ❌ Not recommended - too complex, fragile, and potentially against ToS

---

### Approach 3: **Memory Inspection / Process Attachment**

**Concept:** Attach debugger/profiler to Bookmap process and read memory directly

**Tools:**
- Java agents
- JVMTI (JVM Tool Interface)
- Native profilers

**Problems:**
- Need to know exact memory addresses
- Data structures are complex and nested
- No documentation of memory layout
- Pointer chasing required
- Real-time synchronization challenges
- ⚠️ **May violate security/ToS**

**Verdict:** ❌ Not practical - like finding a needle in a haystack

---

### Approach 4: **L0 API Reverse Feed**

**Concept:** Create a custom L0 adapter that:
1. Receives market data (MBO, trades)
2. Calculates custom indicators (CVD, heatmap)
3. Feeds calculated values BACK into Bookmap via L0
4. Access the custom values via L1 API

**Architecture:**
```
           Market Data
                ↓
    ┌─────────────────────┐
    │  Your Custom L0     │
    │  Adapter (C++/Java) │
    └─────────────────────┘
                ↓
    ┌─────────────────────┐
    │   Calculate CVD     │
    │   Calculate Heatmap │
    └─────────────────────┘
                ↓
        Feed into Bookmap
                ↓
    ┌─────────────────────┐
    │  Access via L1 API  │  ← Now you can read it!
    └─────────────────────┘
```

**Pros:**
- ✅ Uses supported APIs
- ✅ You control the data
- ✅ Can access via L1 API

**Cons:**
- ⚠️ More complex setup
- ⚠️ Duplication of effort (Bookmap already calculates these)
- ⚠️ Performance overhead

**Verdict:** ✅ **Most viable approach** if you absolutely need native-like access

---

## 📊 JNI Function Signatures

### HeatMapLayer Key Methods

```c
// Data input (Java → Native)
JNIEXPORT void JNICALL Java_velox_opengl_painter_layers_HeatMapLayer_prepareData(
    JNIEnv *env,
    jobject obj,
    jintArray data  // int[][] of heatmap data
);

// Rendering update
JNIEXPORT void JNICALL Java_velox_opengl_painter_layers_HeatMapLayer_update(
    JNIEnv *env,
    jobject obj,
    jint param1
);

// Geometric queries (no data access)
JNIEXPORT jint JNICALL Java_velox_opengl_painter_layers_HeatMapLayer_getRowHeight(
    JNIEnv *env,
    jobject obj
);

JNIEXPORT jint JNICALL Java_velox_opengl_painter_layers_HeatMapLayer_rowAtY(
    JNIEnv *env,
    jobject obj,
    jint y
);
```

### UserIndicatorLayer Key Methods

```c
// Data input (Java → Native)
JNIEXPORT void JNICALL Java_velox_opengl_painter_layers_UserIndicatorLayer_setIndicatorPoints(
    JNIEnv *env,
    jobject obj,
    jint id,
    jintArray timestamps,
    jdoubleArray values
);

// Clear data
JNIEXPORT void JNICALL Java_velox_opengl_painter_layers_UserIndicatorLayer_resetIndicatorPoints(
    JNIEnv *env,
    jobject obj
);

// Pixel query (NOT data access)
JNIEXPORT jint JNICALL Java_velox_opengl_painter_layers_UserIndicatorLayer_getDataInPixelScore(
    JNIEnv *env,
    jobject obj,
    jint x,
    jint y
);
```

---

## 🎯 Recommended Solution

### **Recreate Indicators in Java**

Since native access is impractical, the **supported and recommended approach** is to recreate indicators from raw market data:

```java
public class CustomIndicators {
    // CVD Calculation
    private long cvd = 0;
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        cvd += tradeInfo.isBidAggressor ? size : -size;
    }
    public long getCVD() { return cvd; }

    // Volume Profile (Heatmap-like)
    private final Map<Integer, Long> volumeAtPrice = new ConcurrentHashMap<>();
    public void onTrade(double price, int size) {
        volumeAtPrice.merge((int)price, (long)size, Long::sum);
    }
    public long getVolumeAtPrice(int price) {
        return volumeAtPrice.getOrDefault(price, 0L);
    }

    // Volume Dots
    private final Map<Integer, List<Trade>> tradesAtPrice = new ConcurrentHashMap<>();
    public void addTrade(double price, int size) {
        tradesAtPrice.computeIfAbsent((int)price, k -> new ArrayList<>())
                     .add(new Trade(price, size));
    }
    public int getVolumeCountAtPrice(int price) {
        return tradesAtPrice.getOrDefault(price, Collections.emptyList())
                         .stream()
                         .mapToInt(t -> t.size)
                         .sum();
    }
}
```

**Integration with AI Strategy:**
```java
public class OrderFlowStrategyEnhanced implements CustomModuleAdapter {
    private final CustomIndicators indicators = new CustomIndicators();

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        indicators.onTrade(price, size, tradeInfo);

        // Now use for AI evaluation
        long cvd = indicators.getCVD();
        long volAtPrice = indicators.getVolumeAtPrice((int)price);

        // Add to SignalData...
    }
}
```

---

## 📋 Comparison Table

| Approach | Feasibility | Complexity | Performance | Support | Recommendation |
|----------|------------|------------|-------------|---------|----------------|
| **Recreate from Raw Data** | ✅ Easy | Low | High | ✅ Official | ✅ **RECOMMENDED** |
| **Pixel Scraping** | ⚠️ Possible | Very High | Very Low | ❌ Unsupported | ❌ Not practical |
| **Custom JNI Library** | ⚠️ Theoretical | Extreme | High | ❌ Unsupported | ❌ Not recommended |
| **Memory Inspection** | ⚠️ Theoretical | Extreme | Low | ❌ Unsupported | ❌ Not recommended |
| **L0 Reverse Feed** | ✅ Possible | Medium | Medium | ✅ Official | ⚠️ Viable but complex |

---

## 🔗 Resources

### Decompiled Files Location
- `~/bookmap-decompile/decompiled/` - Decompiled Java classes
- `/Applications/Bookmap.app/Contents/app/lib/mac/libpainter.dylib` - Native library

### JNI Tools
```bash
# Examine JNI symbols
nm -g libpainter.dylib | grep Java_

# Disassemble (if needed)
otool -tV libpainter.dylib > libpainter.asm

# Check dependencies
otool -L libpainter.dylib
```

### JNI Reference
- [JNI Specification](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/)
- [Java Native Interface](https://en.wikipedia.org/wiki/Java_Native_Interface)

---

## 💡 Conclusion

**Direct C++/JNI access to Bookmap's built-in indicators is not practical because:**

1. **No public API** - Native functions are internal implementation
2. **One-way data flow** - JNI only sends data TO native layer, not FROM
3. **No headers/SDK** - No documentation of internal structures
4. **Fragile** - Would break on every Bookmap update
5. **Potentially unsupported** - May violate ToS

**Recommended Approach:**

✅ **Recreate indicators from raw market data** using the Simplified API. This is:
- Officially supported
- Well-documented
- Performant
- Maintainable
- Safe (no ToS issues)

Would you like me to create complete custom indicator implementations for your AI strategy?
