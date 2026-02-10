# Feature: UI Panels & AI Integration

**Branch**: `feature/ui-panels-ai-integration`
**Status**: In Progress
**Author**: Claude Code
**Date**: 2025-02-10

---

## Overview

This feature adds comprehensive **Custom UI Panels** and **Performance Tracking** to the Order Flow Strategy, enabling real-time monitoring, live parameter adjustment, and foundation for AI integration.

---

## What's New

### 1. **Settings Panel** (NEW)

**File**: `OrderFlowStrategyEnhanced.java`

Real-time configuration without recompiling the strategy:

#### **Signal Thresholds**
- **Min Confluence Score**: 8-15 (default: 10)
  - Live spinner to adjust minimum confluence required for entry
  - Higher = fewer but higher-quality signals

- **Threshold Multiplier**: 1.5x-5.0x (default: 3.0x)
  - Controls adaptive threshold sensitivity
  - Lower = more signals, Higher = fewer signals

#### **Safety Controls**
- **☑ Simulation Mode Only** (default: ENABLED)
  - MUST stay enabled for paper trading
  - Disabling shows WARNING dialog
  - Prevents accidental live trading

- **☐ Enable Auto-Execution** (default: DISABLED)
  - Only available when SIM mode is OFF
  - For future auto-execution features
  - Safety-critical (cannot enable while in SIM mode)

#### **Risk Management**
- **Max Position**: 1-10 contracts (default: 1)
  - Start with 1 contract until profitable

- **Daily Loss Limit**: $100-$5000 (default: $500)
  - Stops all trading when limit reached
  - Kill switch activated automatically

#### **Apply Button**
- Applies all settings changes
- Shows confirmation dialog

---

### 2. **Statistics / Performance Panel** (NEW)

Real-time visibility into strategy performance and activity.

#### **All-Time Performance**
```
Trades: 156
Win Rate: 52.0%
Total P&L: $6,240.00 (green if positive, red if negative)
Best: $500
```

#### **Today's Performance**
```
Today Trades: 3
Win Rate: 67.0%
Today P&L: $280.00 (color-coded)
Drawdown: -$120.00
```

#### **Current Activity**
```
Active Signals: 2
Last Score: 12/10
Last Signal: 2 min ago
```

#### **Adaptive Thresholds** (Instrument-Specific Learning)
```
Avg Orders: 12.3
Avg Size: 85.4
Current Threshold: 37 orders, 256 size
```
- Shows what the strategy has learned about this instrument
- Updates in real-time as market conditions change
- Based on rolling window of last 100 events

#### **AI Coach Insights** (Future)
```
🤖 AI Coach:
✅ Market regime: BULLISH (trending up)
ℹ️  Recommendation: Focus on LONG setups
⚠️  Warning: Volatility elevated, widen stops
```
- Currently shows placeholder text
- Will connect to Claude SDK in Phase 8

#### **Buttons**
- **🤖 Ask AI**: Opens dialog to ask AI Coach questions
- **📥 Export Data**: Exports trades (CSV) and performance report (TXT)

---

### 3. **Performance Tracking** (NEW)

#### **Real-Time Metrics**
- Total trades (all-time)
- Win rate percentage
- Total P&L (in dollars)
- Best and worst trades
- Today's trades and P&L
- Maximum drawdown today
- Current equity curve

#### **Trade History**
Every trade tracked with:
- Entry time and price
- Exit time and price
- Hold duration (minutes)
- P&L (dollars and cents)
- Confluence score
- Entry reason

#### **Alert System**
Automatic alerts when:
- ⛔ **Daily Loss Limit Reached**
  - Stops trading immediately
  - Shows critical warning

- ⚠️ **Approaching Max Drawdown**
  - Warning at 80% of limit
  - Allows proactive risk management

- 📉 **Win Rate Declining**
  - Monitors last 10 trades
  - Suggests strategy review

---

### 4. **Export Functionality** (NEW)

#### **CSV Trade Export**
```csv
Time,Entry,Exit,Duration,PnL,Score,Reason
10:15:32,4498.00,4506.00,26,200.00,12,CVD divergence + Volume block support
```

#### **Performance Report**
```
=== ORDER FLOW STRATEGY PERFORMANCE REPORT ===
Date: 2025-02-10

ALL-TIME METRICS:
  Total Trades: 156
  Win Rate: 52.0%
  Total P&L: $6,240.00

TODAY'S PERFORMANCE:
  Trades: 3
  P&L: $280.00
  Max Drawdown: $120.00
```

Files saved as:
- `trades_YYYY-MM-DD.csv`
- `performance_YYYY-MM-DD.txt`

---

## Implementation Architecture

### **Class Structure**

```java
OrderFlowStrategyEnhanced implements
    CustomModule,                  // Bookmap lifecycle
    MarketByOrderDepthDataListener, // MBO data (icebergs, spoofing)
    TradeDataListener,             // Trade data (absorption)
    BboListener,                   // Best bid/offer
    CustomSettingsPanelProvider    // UI panels
```

### **UI Components**

**Settings Panel** (`StrategyPanel`):
- Uses `GridBagLayout` for precise control
- Live spinners for parameter adjustment
- Checkboxes for safety controls
- Apply button to commit changes

**Statistics Panel** (`StrategyPanel`):
- Real-time labels (updated every 1 second)
- Color-coded P&L (green/red)
- Scrollable text area for AI insights
- Export and Ask AI buttons

### **Thread Safety**

- **UI Updates**: `SwingUtilities.invokeLater()`
- **Counters**: `AtomicInteger`, `AtomicLong`
- **Timer**: `ScheduledExecutorService` (1-second updates)

### **State Management**

- **Adaptive Thresholds**: Rolling window of last 100 events
- **Trade History**: `ArrayList<Trade>` with full details
- **Today's Performance**: Separate tracking for daily metrics
- **Active Signals**: Current signal list with timestamps

---

## Usage

### **1. Launch the Strategy**

In Bookmap:
1. Load the strategy: `Order Flow Enhanced`
2. Add to your chart (e.g., ESH6)
3. Two panels will appear in settings area

### **2. Configure Settings**

**Settings Panel**:
- Adjust "Min Confluence Score" (try 10-12)
- Set "Threshold Multiplier" (try 2.5-3.5)
- Confirm "☑ Simulation Mode Only" is checked
- Set "Daily Loss Limit" to your comfort level ($500 recommended)

### **3. Monitor Performance**

**Statistics Panel** shows:
- Real-time updates every 1 second
- Current activity (signals, scores)
- Adaptive thresholds (instrument learning)
- Today's P&L and drawdown

### **4. Export Data**

Click **📥 Export Data** to save:
- Trade history (CSV)
- Performance report (TXT)

Use for analysis in Excel, Google Sheets, etc.

---

## File Structure

```
Strategies/src/main/java/velox/api/layer1/simplified/demo/
├── OrderFlowMboSimple.java              (Original - adaptive MBO only)
└── OrderFlowStrategyEnhanced.java       (NEW - UI panels + performance)
```

**New Files**:
- `OrderFlowStrategyEnhanced.java` (800+ lines)
  - Settings panel implementation
  - Statistics panel implementation
  - Performance tracking
  - Alert system
  - Export functionality
  - AI integration placeholders

---

## Dependencies

### **Required** (Already in Bookmap)
```gradle
implementation 'velox:layer1-api:7.6.0.20'
```

### **For AI Integration** (Phase 8 - Future)
```gradle
implementation 'com.anthropic:anthropic-sdk-java:0.5.0'
```

Will add when implementing AI features.

---

## Configuration

### **Environment Variables** (Future - Phase 8)

```bash
# For AI features
export ANTHROPIC_API_KEY="your-api-key-here"
```

### **Bookmap Settings**

No special configuration needed. Just:
1. Load strategy
2. Add to chart
3. Panels appear automatically

---

## Testing

### **Manual Testing Checklist**

- [ ] Settings panel appears in Bookmap
- [ ] All spinners update parameters live
- [ ] Simulation mode toggle shows warning when disabling
- [ ] Auto-execution cannot enable while in SIM mode
- [ ] Statistics panel updates every 1 second
- [ ] P&L labels turn green/red correctly
- [ ] Export button creates CSV and TXT files
- [ ] Alert system triggers at loss limit
- [ ] Adaptive thresholds update as signals come in
- [ ] Ask AI button shows placeholder dialog

### **Expected Behavior**

1. **On Load**: Two panels appear in settings
2. **On Signal**: Stats panel updates within 1 second
3. **On Loss Limit**: Alert dialog appears, trading stops
4. **On Export**: Files created in working directory

---

## Known Limitations

### **Current Implementation**

1. **AI Features**: Placeholder only (not yet connected to Claude)
   - "Ask AI" button shows dialog but no real AI
   - AI insights area shows static text
   - Will be implemented in Phase 8

2. **Auto-Execution**: Disabled by design
   - Safety-critical (cannot enable in SIM mode)
   - Future implementation will connect to Tradovate

3. **Charts**: Basic labels only (no equity curve chart yet)
   - Will add JFreeChart or similar in Phase 7

4. **Historical Data**: Session-only (no persistence across restarts)
   - Trade history saved to files but not reloaded on startup
   - Will add database in Phase 9

---

## Future Phases

### **Phase 8: AI Integration** (Week 9-10)

- [ ] Set up Claude SDK dependency
- [ ] Implement `AICoach` class
- [ ] Add market regime analyzer (60-second updates)
- [ ] Add performance analyzer (daily)
- [ ] Add trade coach (real-time validation)
- [ ] Connect "Ask AI" button to real AI
- [ ] Update AI insights area automatically

### **Phase 9: Paper Trading with AI** (Week 11-12)

- [ ] Connect to Tradovate paper trading
- [ ] Track AI recommendations vs actual results
- [ ] Validate AI improves win rate / R:R
- [ ] Fine-tune AI prompts and parameters
- [ ] Document AI insights and adjustments

### **Phase 10: Go Live** (Week 13+)

- [ ] Start with 1 contract (live)
- [ ] AI in advisory mode only (no auto-execution)
- [ ] Monitor AI suggestions vs human decisions
- [ ] Track performance improvements from AI
- [ ] Adjust AI prompts based on live results

---

## Troubleshooting

### **Panels Not Appearing**

**Symptoms**: No panels in Bookmap settings

**Solutions**:
1. Confirm strategy loaded: `Order Flow Enhanced`
2. Check Bookmap logs for errors
3. Verify Java version (Java 8+ required)
4. Try reloading Bookmap

### **Statistics Not Updating**

**Symptoms**: Stats panel shows "0" or outdated values

**Solutions**:
1. Wait 1-2 seconds (update timer)
2. Confirm strategy is running (check console)
3. Verify MBO data is coming (check logs)
4. Restart strategy

### **Export Fails**

**Symptoms**: "Export Failed" dialog

**Solutions**:
1. Check file write permissions
2. Verify disk space available
3. Check working directory is writable
4. Look at console error message

---

## Performance Impact

### **Memory**
- Trade history: ~1KB per trade
- 1000 trades = ~1MB
- Acceptable for months of trading

### **CPU**
- UI updates: Every 1 second
- MBO processing: Per event
- Adaptive threshold calculation: Per MBO event
- **Expected**: <1% CPU usage

### **Disk I/O**
- Logs: Appended continuously
- Exports: On-demand only
- Signals: Appended continuously
- **Expected**: Minimal impact

---

## Security Considerations

### **Simulation Mode Lock**

**Critical**: `simModeOnly` parameter defaults to `true`

**Why**: Prevents accidental live trading

**How**:
- Disabling shows WARNING dialog
- Requires explicit confirmation
- Auto-execution cannot enable while SIM mode is on
- Defense in depth

### **Daily Loss Limit**

**Critical**: `dailyLossLimit` parameter enforced at $500 default

**Why**: Prevents catastrophic losses

**How**:
- Monitors today's P&L in real-time
- Triggers kill switch when limit reached
- Shows critical alert dialog
- Stops all trading for the day

### **API Key Safety** (Future)

When AI is implemented:
- API key stored in environment variable
- Never committed to git
- `.gitignore` updated to exclude
- Rate limiting enforced ($1/day budget)

---

## Compliance

### **Bookmap API Compliance**

✅ Uses only Simplified API (no direct layer1 access)
✅ Properly implements all required interfaces
✅ Thread-safe UI updates (SwingUtilities)
✅ Resource cleanup (ExecutorService shutdown)

### **Trading Best Practices**

✅ Paper trading before live (SIM mode enforced)
✅ Risk management (daily loss limit, max position)
✅ Position sizing (start with 1 contract)
✅ Kill switch available
✅ Full audit trail (logs, exports)

---

## Glossary

- **MBO**: Market By Order - Individual limit order tracking
- **CVD**: Cumulative Volume Delta - Aggressive buyers vs sellers
- **POC**: Point of Control - Price with highest volume
- **VAH/VAL**: Value Area High/Low - 70% volume range
- **R:R**: Risk:Reward ratio (e.g., 2R = 2x reward vs risk)
- **ATR**: Average True Range - Volatility measure
- **Confluence**: Multiple factors aligning (score-based system)

---

## References

- **Trading Plan**: `ORDER_FLOW_TRADING_PLAN.md` (comprehensive 2000+ line plan)
- **Original Strategy**: `OrderFlowMboSimple.java` (adaptive MBO detection)
- **Bookmap API**: https://api.bookmap.com/docs
- **Claude SDK**: https://github.com/anthropics/anthropic-sdk-java

---

## Changelog

### **2025-02-10** (Initial Commit)

**Added**:
- Settings panel with live parameter adjustment
- Statistics panel with real-time performance tracking
- Adaptive thresholds display (instrument learning)
- Performance tracking (P&L, win rate, drawdown)
- Alert system (daily loss limit, drawdown warnings)
- Export functionality (CSV, performance reports)
- Thread-safe UI updates (1-second timer)
- Safety controls (SIM mode lock, auto-execution prevention)
- AI integration placeholders (for Phase 8)

**Changed**:
- Forked from `OrderFlowMboSimple.java`
- Renamed to `OrderFlowStrategyEnhanced.java`
- Added 400+ lines of UI code
- Added 200+ lines of performance tracking

**Fixed**:
- Thread safety (SwingUtilities.invokeLater)
- Memory leaks (ExecutorService cleanup)
- UI responsiveness (dedicated update timer)

---

## Contact & Support

**Issues**: Report via GitHub Issues or Bookmap Discord
**Questions**: Check trading plan documentation first
**Updates**: Check this branch for latest commits

---

*"The market is always right. Price never lies. But order flow tells you WHY."*
