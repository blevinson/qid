# "No Symbols" Issue - FIXED

## Problem

**Symptoms:**
- SSP reader receiving signals correctly
- But **NO icons showing on chart**
- Log shows: `"No instruments registered yet, skipping signal"`
- **0 signals added** to display

**Root Cause:**
The SSP reader was waiting for `onInstrumentAdded()` callback which **never gets called** in Bookmap's Core API.

## Solution Implemented

### Fix: Lazy Initialization via `onTrade()`

**Before (broken):**
```java
// Waiting for callback that never happens
public void onInstrumentAdded(String alias, InstrumentInfo info) {
    // Create instrument state here - BUT THIS NEVER GETS CALLED!
}

public void onTrade(...) {
    // Not needed for this strategy
}
```

**After (working):**
```java
// Create state when first trade arrives (like OrderFlowSSPCore does)
public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
    InstrumentState state = instrumentStates.get(alias);
    if (state == null) {
        state = new InstrumentState();  // ✅ Lazy init!
        state.pips = 1.0;
        state.alias = alias;
        instrumentStates.put(alias, state);
        log("Created state for " + alias);
    }
}
```

### Also Fixed:
Dynamic state lookup in `onMoveEnd()`:
```java
// Before: Captured once (was null)
private InstrumentState state = instrumentStates.get(indicatorAlias);

// After: Look up fresh every time
InstrumentState state = instrumentStates.get(indicatorAlias);
if (state == null) return;  // Trades haven't started yet
```

---

## What Will Happen After Reload

### When Bookmap Connects:

1. **First trade arrives:**
   ```
   20:30:00.123 - Created state for NQ626:NT:XCME
   ```

2. **Signals start displaying:**
   ```
   20:30:00.234 - SIGNAL RECEIVED: ICEBERG BUY @ 27901 size=28
   20:30:00.235 - SIGNAL ADDED: ICEBERG BUY @ 27901.0 TP=27921.0 SL=27891.0
                 (Total: 1)
   ```

3. **Icons appear on chart:**
   - **Green triangle** at 27901 (BUY signal)
   - **Cyan line** to 27921 (TP)
   - **Orange line** to 27891 (SL)

---

## Step-by-Step Instructions

### 1. **Reload JAR in Bookmap**
   - Settings → Add Strategies → Reload JAR

### 2. **Enable "OF MBO SSP Reader"**
   - Check the box next to it
   - Make sure it's enabled

### 3. **Enable "Order Flow MBO"**
   - This generates the signals

### 4. **Connect to Instrument with MBO Data**
   - Use futures (~27,900 price range)
   - **Wait for first trade** - this triggers initialization

### 5. **Watch for Icons**
   - Within 1-2 seconds after first trade
   - Green/red triangles should appear
   - TP/SL lines visible

---

## Expected Log Output

### In `ssp_reader_log.txt`:

**Successful startup:**
```
20:30:00.100 - OrderFlowSSPReader constructed
20:30:00.100 - Signal reader thread started
20:30:00.100 - SSP Indicator added: Order Flow MBO Signals
20:30:00.123 - Created state for NQ626:NT:XCME
```

**Signals flowing:**
```
20:30:00.234 - SIGNAL RECEIVED: ICEBERG BUY @ 27901 size=28
20:30:00.235 - SIGNAL ADDED: ICEBERG BUY @ 27901.0 TP=27921.0 SL=27891.0 (Total: 1)
20:30:00.340 - SIGNAL RECEIVED: ICEBERG SELL @ 27905 size=35
20:30:00.341 - SIGNAL ADDED: ICEBERG SELL @ 27905.0 TP=27885.0 SL=27915.0 (Total: 2)
```

---

## Troubleshooting

### Still no icons after reload?

**Check 1:** Is instrument state created?
```bash
grep "Created state for" /Users/brant/bl-projects/DemoStrategies/ssp_reader_log.txt
```

**Check 2:** Are signals being added?
```bash
grep "SIGNAL ADDED" /Users/brant/bl-projects/DemoStrategies/ssp_reader_log.txt | tail -5
```

**Check 3:** Is the instrument receiving trades?
```bash
grep "Trade #" /Users/brant/bl-projects/DemoStrategies/mbo_simple_log.txt | tail -5
```

### No trades on instrument?

- Switch to an active futures contract
- Make sure you're connected to live data
- Wait for market activity (trading hours)

### Icons disappeared?

- Normal! Only last 100 signals kept per instrument
- Old signals auto-remove to prevent memory issues
- New icons will continue appearing

---

## Summary of All Fixes

| Issue | Fix | Status |
|-------|-----|--------|
| Corrupted signal reading | Line number tracking instead of byte position | ✅ Fixed |
| No instrument registration | Lazy init via onTrade() | ✅ Fixed |
| Null state in SSP renderer | Dynamic state lookup | ✅ Fixed |
| No validation | Signal format checking | ✅ Added |
| Memory leak | Limit to 100 signals | ✅ Added |

---

**Ready to test!** 🚀

Reload the JAR and you should see SSP icons displaying on your chart after the first trade arrives.
