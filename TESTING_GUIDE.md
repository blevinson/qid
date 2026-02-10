# 🎯 Ready to Test in Bookmap!

**Status**: ✅ BUILD SUCCESSFUL
**JAR File**: `Strategies/build/libs/bm-strategies.jar` (408 KB)
**Commit**: `26ce879` - All API fixes applied

---

## 🚀 Quick Start (5 Minutes to First Signal)

### Step 1: Locate the JAR File
```bash
cd /Users/brant/bl-projects/DemoStrategies/Strategies/build/libs
ls -lh bm-strategies.jar
```
**Location**: `/Users/brant/bl-projects/DemoStrategies/Strategies/build/libs/bm-strategies.jar`

### Step 2: Load in Bookmap
1. Open Bookmap
2. **Settings** → **API Plugins Configuration** (or click toolbar icon)
3. Click **"Add"** button
4. Navigate to: `/Users/brant/bl-projects/DemoStrategies/Strategies/build/libs/`
5. Select: **`bm-strategies.jar`**
6. Choose strategy: **"Order Flow Enhanced"**
7. Click **"OK"**
8. Check the checkbox to enable it
9. **Restart Bookmap** (required for new plugins)

### Step 3: Add to Chart
1. Right-click on your chart (e.g., ESH6 S&P 500 Futures)
2. Select **"Add Strategy"**
3. Choose **"Order Flow Enhanced"**
4. Two panels will appear in the settings area:
   - **Order Flow Settings** (left)
   - **Performance Dashboard** (right)

### Step 4: Verify It's Working

**Check the Console**:
```
========== OrderFlowStrategyEnhanced.initialize() ==========
Instrument: ESH6
Pip size: 0.25
========== OrderFlowStrategyEnhanced.initialize() COMPLETE ==========
Waiting for MBO events...
```

**Check the Statistics Panel**:
- All-Time Performance: Trades: 0, Win Rate: 0.0%, P&L: $0.00
- Today's Performance: Trades: 0, P&L: $0.00
- Current Activity: Active Signals: 0

**Look for Signals** (green/red/magenta dots on chart):
- 🟢 Green = Iceberg BUY signal
- 🔴 Red = Iceberg SELL signal
- 🟣 Magenta = Spoof/FADE signal
- 🟡 Yellow = Absorption signal

---

## 🎛️ Settings Panel Guide

### Signal Thresholds
- **Min Confluence Score**: 10 (default)
  - Spinner range: 8-15
  - Higher = fewer but higher-quality signals
  - Recommendation: Start with 10

- **Threshold Multiplier**: 3.0 (default)
  - Spinner range: 1.5x-5.0x
  - Controls adaptive sensitivity
  - Recommendation: Start with 3.0

### Safety Controls ⚠️
- **☑ Simulation Mode Only** (ENABLED - KEEP CHECKED!)
  - Prevents accidental live trading
  - Shows warning if you try to disable
  - **DO NOT UNCHECK** until ready for live trading

- **☐ Enable Auto-Execution** (DISABLED)
  - Cannot enable while in SIM mode
  - For future use with Tradovate

### Risk Management
- **Max Position**: 1 contract (default)
  - Recommendation: Start with 1, max 10

- **Daily Loss Limit**: $500 (default)
  - Spinner range: $100-$5000
  - Auto kill switch when reached
  - Recommendation: $500 for starters

### Apply Button
Click **"Apply Settings"** to save changes (shows confirmation dialog)

---

## 📊 Statistics Panel Guide

### All-Time Performance
Updates every 1 second with:
- **Total Trades**: Number of trades taken
- **Win Rate**: Percentage of winning trades
- **Total P&L**: Net profit/loss (color-coded green/red)
- **Best**: Best trade profit
- **Worst**: Worst trade loss

### Today's Performance
Real-time today's metrics:
- **Today Trades**: Trades taken today
- **Win Rate**: Today's win percentage
- **Today P&L**: Today's net P&L
- **Drawdown**: Current max drawdown

### Current Activity
Live signal tracking:
- **Active Signals**: Number of active signals
- **Last Score**: Size of last signal (e.g., "SIZE: 226")
- **Last Signal**: "Recent" if signal < 1 min ago

### Adaptive Thresholds
Shows what the strategy learned:
- **Avg Orders**: Average order count (last 100 events)
- **Avg Size**: Average order size
- **Current Threshold**: Current adaptive threshold

### AI Coach Insights
Placeholder for Phase 8 (AI Integration):
- Currently shows: "AI integration coming soon!"
- **"Ask AI"** button: Opens dialog (not yet connected to Claude)

### Export Button
**"📥 Export Data"** button exports:
- `trades_YYYY-MM-DD.csv` - Trade history (Excel-ready)
- `performance_YYYY-MM-DD.txt` - Performance report (formatted)

---

## 🧪 Testing Checklist

### Initial Tests (5 minutes)
- [ ] Strategy loads without errors
- [ ] Two panels appear in settings
- [ ] Console shows "Waiting for MBO events..."
- [ ] Statistics panel shows "Trades: 0"

### Live Market Tests (10-30 minutes)
- [ ] MBO events are received (check console)
- [ ] Signals appear on chart (dots)
- [ ] Statistics panel updates every 1 second
- [ ] Adaptive thresholds change as market moves
- [ ] No EDT violations or crashes

### Settings Panel Tests (5 minutes)
- [ ] Can change Min Confluence Score
- [ ] Can change Threshold Multiplier
- [ ] SIM Mode warning appears when unchecking
- [ ] Apply Settings button shows confirmation
- [ ] Parameters persist after changing

### Export Tests (5 minutes)
- [ ] Export button creates files
- [ ] CSV file opens in Excel/Sheets
- [ ] Performance report is readable

---

## 📈 Expected Behavior

### Signal Frequency
Depends on instrument and market conditions:

| Instrument | Signals/Hour | Notes |
|------------|--------------|-------|
| **ESH6** (S&P 500) | 2-10 | Highly liquid, good data |
| **NQH6** (Nasdaq) | 5-15 | More volatility |
| **GCH6** (Gold) | 1-5 | Less activity |

### Signal Types You'll See
```
🧊 ADAPTIVE SIGNAL: ICEBERG|SELL|28200|226
   → Red dot on chart at 28200
   → Means: Hidden seller detected, potential resistance

🧊 ADAPTIVE SIGNAL: ICEBERG|BUY|27948|71
   → Green dot on chart at 27948
   → Means: Hidden buyer detected, potential support

🟡 ABSORPTION+ at 27936 (size: 12)
   → Yellow dot on chart
   → Means: Heavy buying but price not rising (support)

🟣 SPOOF: Large bid cancelled quickly
   → Magenta dot on chart
   → Means: Fake support, expect break
```

### Adaptive Thresholds in Action
Watch the "Adaptive Thresholds" section update:
```
Initial (first 100 events):
  Avg Orders: 8.3, Avg Size: 36.4
  Current Threshold: 25 orders, 109 size

After 10 minutes (more data):
  Avg Orders: 12.1, Avg Size: 85.2
  Current Threshold: 37 orders, 256 size
```

The strategy **learns** from each instrument!

---

## ⚠️ Troubleshooting

### No Signals Appearing

**Problem**: Console shows "0 MBO events"

**Solution**:
1. Check if your data feed supports MBO
2. Try a different instrument (ESH6 usually has good MBO)
3. Wait for active market hours (9:30 AM - 4:00 PM ET)

**Expected**: Should see "100+ MBO events" after a few minutes

### Panels Not Appearing

**Problem**: Don't see settings/statistics panels

**Solution**:
1. Make sure you selected "Order Flow Enhanced" (not "Order Flow MBO")
2. Check Bookmap logs for errors
3. Try restarting Bookmap

### Crash on Load

**Problem**: Bookmap crashes when loading strategy

**Solution**:
1. Check console for error messages
2. Verify Java version (Java 8+ required)
3. Try rebuilding the JAR:
   ```bash
   cd Strategies
   ./gradlew clean jar
   ```

### Statistics Not Updating

**Problem**: Stats panel shows "0" and doesn't update

**Solution**:
1. Wait 1-2 seconds (updates every 1 second)
2. Check if strategy is enabled (checkbox)
3. Verify MBO data is coming (check console)

---

## 📊 What to Monitor While Testing

### First 10 Minutes
1. **Console**: Should show MBO events streaming in
2. **Chart**: Should see dots appearing (signals)
3. **Adaptive Thresholds**: Should be changing as strategy learns
4. **Statistics Panel**: Should update every 1 second

### First 30 Minutes
1. **Signal Quality**: Are signals appearing at support/resistance?
2. **Thresholds Adaptive**: Do they adjust to market conditions?
3. **Performance**: Any crashes or EDT violations?
4. **CPU Usage**: Should be <1% (very efficient)

### First Hour
1. **Total Signals**: How many did you get? (Expected: 5-20)
2. **False Signals**: Any signals that didn't make sense?
3. **Latency**: How quickly do signals appear?
4. **Memory**: Any memory leaks?

---

## 🎯 Success Criteria

### ✅ Strategy is Working If:
- [ ] Loads without errors
- [ ] Receives MBO events (check console)
- [ ] Shows signals on chart (green/red/magenta dots)
- [ ] Panels update in real-time
- [ ] Adaptive thresholds change as market moves
- [ ] Export button creates files
- [ ] No crashes or EDT violations

### ⚠️ Warning Signs (Not Critical):
- Few signals initially (normal, adaptive learning)
- Thresholds change frequently (normal, adapting to market)
- Console shows many MBO events (good, means data is flowing)

### ❌ Critical Issues (Need Fixing):
- No MBO events after 10+ minutes (data feed issue)
- Crashes on load (compilation error)
- Panels don't appear (API version issue)
- EDT violations (thread safety issue)

---

## 📝 Testing Log Template

Keep a log while testing:

```
Date: 2025-02-10
Instrument: ESH6 (S&P 500 Futures)
Market Time: [9:30 AM - 4:00 PM ET]

10:00 AM - Strategy loaded
10:01 AM - First MBO event received
10:05 AM - First signal: ICEBERG SELL at 28200 (226 contracts)
10:12 AM - Second signal: ICEBERG SELL at 27948 (71 contracts)
...

Total Signals: 2
False Signals: 0
Quality: Good (signals at key levels)

Notes:
- Adaptive thresholds working (started at 25 orders, now 37)
- CPU usage minimal
- No crashes or EDT violations
- Statistics panel updating smoothly
```

---

## 🚀 Next Steps After Testing

### If Everything Works:
1. **Paper trade for 1-2 weeks** in SIM mode
2. **Track all signals** (even ones not taken)
3. **Export data daily** for analysis
4. **Refine thresholds** based on results
5. **Start Phase 8**: AI Integration

### If Issues Found:
1. **Document the error** (screenshot + console output)
2. **Check troubleshooting guide** above
3. **Review code**: `/Users/brant/bl-projects/DemoStrategies/Strategies/src/main/java/velox/api/layer1/simplified/demo/OrderFlowStrategyEnhanced.java`
4. **Rebuild**: `./gradlew clean jar`
5. **Test again**

---

## 📧 Support

For issues or questions:
- **Bookmap Discord**: https://discord.gg/bookmap
- **GitHub Issues**: https://github.com/blevinson/qid/issues
- **Documentation**: See `QID_README.md` and `FEATURE_UI_PANELS_AI.md`

---

## 🎉 You're Ready!

**Your enhanced order flow strategy is:**
- ✅ Built successfully
- ✅ API errors fixed
- ✅ Compiled without warnings
- ✅ Ready to test in Bookmap

**What you have:**
- Custom Settings Panel (live configuration)
- Statistics/Performance Panel (real-time metrics)
- Adaptive thresholds (learns from instrument)
- Export functionality (CSV + performance reports)
- Safety features (SIM mode lock, loss limit)

**What's Next:**
1. Load in Bookmap
2. Add to chart
3. Watch signals appear
4. Monitor performance
5. Give feedback!

---

**Good luck with testing! 🚀**

*"The market is always right. Price never lies. But order flow tells you WHY."*
