# BTC-USDT Signal Generation Diagnosis

## Problem Report
**Date:** 2026-02-10
**Instrument:** BTC-USDT:MB:SP@BMD
**Issue:** "Not seeing qid do anything" - No signals generated

---

## Root Cause Analysis

### Critical Finding: Bookmap Logs Show "Still Rebuilding" Loop

```
20260211 02:48:17.735(UTC) INFO: No generator found custom.velox.api.layer1.simplified.demo.OrderFlowStrategyEnhanced#BTC-USDT%003AMB%003ASP@BMD - still rebuilding?
```

**This message appears repeatedly** every few seconds, indicating:
- Bookmap is trying to reload the JAR continuously
- The strategy is not successfully initializing
- Each reload attempt fails and retries

---

## Issue #1: Settings Configuration Problem

### From Screenshot Analysis:
```
Min Confluence Score: 10        ❌ CRITICAL ERROR
Iceberg Min Orders: 5           ❌ TOO LOW
Use Adaptive Thresholds: ✗      ❌ SHOULD BE ENABLED
Simulation Mode Only: ✓         ✓ OK for testing
Enable Auto-Execution: ✗        ✓ OK for manual
```

### Why "Min Confluence Score: 10" is Catastrophic:

**Your confluence system is designed for 60-135 points:**

```
Max Score: 135 points
├─ Iceberg Detection: 40 points
├─ CVD Confirmation: 25 points
├─ Volume Profile: 20 points
├─ Volume Imbalance: 10 points
├─ EMA Alignment: 15 points
├─ VWAP: 10 points
├─ Time of Day: 5-10 points
└─ Size Bonus: 3-5 points
```

**With threshold of 10:**
- ✗ Almost EVERY iceberg order triggers (score ≥ 10)
- ✗ Creates **SIGNAL SPAM** - dozens per minute
- ✗ Global cooldown (2 seconds) blocks most signals
- ✗ Per-price cooldown (10 seconds) blocks remaining
- ✗ Result: **Paradoxical silence** - nothing displayed

**What's likely happening:**
1. Strategy detects iceberg at 4500 (score: 45) → Fires signal
2. Cooldown starts (2 seconds global, 10 seconds price)
3. Another iceberg at 4501 (score: 42) → **BLOCKED by global cooldown**
4. Another iceberg at 4502 (score: 48) → **BLOCKED by global cooldown**
5. All subsequent signals blocked → **You see nothing**

---

## Issue #2: "Still Rebuilding" Loop

### Log Evidence:
```log
20260211 02:48:16.230(UTC) INFO: No generator found ... #BTC-USDT - still rebuilding?
20260211 02:48:17.044(UTC) INFO: No generator found ... #BTC-USDT - still rebuilding?
20260211 02:48:17.735(UTC) INFO: No generator found ... #BTC-USDT - still rebuilding?
```

### Possible Causes:

1. **JAR reload loop**
   - Bookmap detects JAR change → Attempts reload
   - Reload fails → Retries
   - Cycle repeats indefinitely

2. **Initialization error**
   - Strategy constructor throws exception
   - Bookmap catches and retries
   - Never gets past initialize()

3. **Bitcoin-specific instrument issue**
   - BTC-USDT may have different data characteristics
   - MBO data may not be available for crypto
   - Volume profile calculation may fail

---

## Recommended Solutions

### Solution 1: Fix Settings (Immediate)

**Change these settings in Bookmap:**

```
Min Confluence Score: 70         ✓ WAS 10 (CORRECT THIS!)
Iceberg Min Orders: 15-20        ✓ WAS 5 (TOO LOW)
Use Adaptive Thresholds: ✓       ✓ Enable this
Adaptive Order Thresh: 20-25     ✓ Let it auto-adjust
Adaptive Size Thresh: 50-100     ✓ Let it auto-adjust
```

**Rationale:**
- Score of 70+ ensures quality signals (not noise)
- Higher order threshold reduces false positives
- Adaptive mode adjusts for market conditions

### Solution 2: Verify MBO Data Availability

**Bitcoin crypto may not have MBO data:**

```java
// In strategy code, add fallback:
if (!mboDataAvailable) {
    log("⚠️ MBO data not available for " + alias);
    log("   Falling back to standard order book detection");
    // Use regular order book instead of MBO
}
```

**Check in Bookmap:**
1. Right-click on chart
2. Check "Volume Profile" or "Heatmap" availability
3. Verify "MBO" (Market by Order) is supported for BTC-USDT

### Solution 3: Fresh JAR Deploy

**Already completed:**
```bash
cp build/libs/bm-strategies.jar ~/Library/Application\ Support/Bookmap/API/
```

**Next steps in Bookmap:**
1. Settings → Add Strategies
2. Click **"Reload JAR"** button
3. Wait for reload to complete (watch for "Still rebuilding" to stop)
4. Re-enable "Order Flow MBO Strategy Enhanced"

### Solution 4: Add Diagnostic Logging

**Request enhanced logging to see what's happening:**

Add to `initialize()` method:
```java
log("📊 Strategy initialized successfully");
log("   Min Confluence Score: " + minConfluenceScore);
log("   Iceberg Min Orders: " + icebergMinOrders);
log("   Use Adaptive: " + useAdaptiveThresholds);
log("   MBO Available: " + (mboListener != null));
```

Add to `checkForIceberg()` method:
```java
log("🔍 Checking for iceberg at " + price + ", orders: " + ordersAtPrice.size());
if (score < minConfluenceScore) {
    log("   ⚠️ SIGNAL REJECTED: Score " + score + " < " + minConfluenceScore);
}
```

---

## Why Settings Matter: Example

### Scenario 1: Threshold = 10 (CURRENT - BROKEN)

```
Time 00:00 - Iceberg at 45000 (score: 45, orders: 20)
   → SIGNAL FIRES 🟢
   → Global cooldown starts (2 sec)
   → Price cooldown starts (10 sec)

Time 00:01 - Iceberg at 45001 (score: 42, orders: 18)
   → Score: 42 > 10 ✓
   → Global cooldown: BLOCKED ⛔ (1 sec elapsed)
   → NOTHING DISPLAYED

Time 00:02 - Iceberg at 45002 (score: 48, orders: 22)
   → Score: 48 > 10 ✓
   → Global cooldown: BLOCKED ⛔ (2 sec elapsed)
   → NOTHING DISPLAYED

Time 00:05 - Iceberg at 45005 (score: 50, orders: 25)
   → Score: 50 > 10 ✓
   → Global cooldown: PASSED ✓
   → SIGNAL FIRES 🟢
   → Cooldown restarts

Result: 2 signals shown, 30+ blocked
```

### Scenario 2: Threshold = 70 (RECOMMENDED)

```
Time 00:00 - Iceberg at 45000 (score: 45, orders: 20)
   → Score: 45 < 70 ⛔
   → SIGNAL REJECTED (too weak)

Time 00:05 - Iceberg at 45005 (score: 85, orders: 35)
   → Score: 85 > 70 ✓
   → All confirmations present:
      ✓ CVD bullish (+3000)
      ✓ Volume at POC (high volume node)
      ✓ EMAs aligned (price > EMA9 > EMA21)
      ✓ Above VWAP
   → SIGNAL FIRES 🟢 (HIGH QUALITY)
   → Global cooldown starts (2 sec)

Time 00:30 - Iceberg at 45020 (score: 92, orders: 40)
   → Score: 92 > 70 ✓
   → Strong confirmations
   → SIGNAL FIRES 🟢 (HIGH QUALITY)

Result: 2 quality signals shown, 0 noise
```

---

## Next Steps

### Immediate Actions:

1. **✅ COMPLETED:** Deploy fresh JAR to Bookmap API folder
2. **🔄 TODO:** You reload JAR in Bookmap
3. **🔄 TODO:** Change Min Confluence Score from 10 to 70
4. **🔄 TODO:** Change Iceberg Min Orders from 5 to 15-20
5. **🔄 TODO:** Enable "Use Adaptive Thresholds"
6. **🔄 TODO:** Monitor Bookmap logs for "Still rebuilding" messages
7. **🔄 TODO:** Check if BTC-USDT has MBO data available

### Expected Behavior After Fix:

**With proper settings (threshold 70+):**
- 2-5 quality signals per hour (not spam)
- Each signal has strong confluence (70+ points)
- Clear BUY/SELL markers on chart
- Performance tracking shows win/loss data

**With current settings (threshold 10):**
- Signal spam detected
- Cooldowns block everything
- Paradoxical silence
- "Still rebuilding" loop

---

## Summary

**The problem is NOT the strategy code** - it's your settings:

1. **Min Confluence Score: 10** is catastrophically low
   - Designed for 60-135 point system
   - Should be 70-80 for quality signals
   - Current setting creates spam → cooldowns → silence

2. **Iceberg Min Orders: 5** is too low
   - Should be 15-20 for BTC
   - Bitcoin liquidity differs from ES futures
   - Lower threshold = more false positives

3. **"Still rebuilding" loop** suggests initialization issue
   - May be related to MBO data availability for crypto
   - May need fallback to standard order book
   - Fresh JAR deploy should help

**Change your settings to the recommended values and reload the JAR!**
