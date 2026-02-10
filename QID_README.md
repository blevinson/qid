# QID - Order Flow Trading Strategy

**Bookmap Strategy with Custom UI Panels, Performance Tracking, and AI Integration**

[![Bookmap API](https://img.shields.io/badge/Bookmap%20API-7.6.0.20-blue)](https://bookmap.com)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://openjdk.org/)
[![Phase](https://img.shields.io/badge/Phase-7%20UI%20Panels-green)]()

---

## 🎯 Overview

QID (Order Flow Intelligence & Discovery) is a comprehensive order flow trading strategy for Bookmap featuring:

- ✅ **Real-time signal detection** (Iceberg orders, spoofing, absorption)
- ✅ **Adaptive thresholds** (learns from each instrument)
- ✅ **Custom UI Panels** (Settings + Performance Dashboard)
- ✅ **Performance tracking** (P&L, win rate, drawdown)
- ✅ **Alert system** (daily loss limit, warnings)
- ✅ **Export functionality** (CSV, performance reports)
- ⏳ **AI Integration** (Phase 8 - coming soon)

---

## 🚀 Quick Start

### 1. Build the Strategy

```bash
cd Strategies
./gradlew build
```

### 2. Load in Bookmap

1. Open Bookmap
2. Settings → API Plugins → Add
3. Select JAR: `Strategies/build/libs/Strategies.jar`
4. Choose **"Order Flow Enhanced"**
5. Restart Bookmap

### 3. Add to Chart

1. Right-click chart → Add Strategy
2. Select **"Order Flow Enhanced"**
3. Two panels will appear in settings area:
   - **Settings Panel** - Configure parameters
   - **Statistics Panel** - Monitor performance

---

## 📊 Features

### 🎛️ Custom Settings Panel

Real-time configuration without recompiling:

- **Signal Thresholds**
  - Min Confluence Score (8-15, default: 10)
  - Threshold Multiplier (1.5x-5.0x, default: 3.0x)

- **Safety Controls** ⚠️
  - ☑ Simulation Mode Only (default: ENABLED)
  - ☐ Enable Auto-Execution (prevented in SIM mode)

- **Risk Management**
  - Max Position (1-10 contracts, default: 1)
  - Daily Loss Limit ($100-$5000, default: $500)

### 📈 Statistics / Performance Panel

Real-time visibility into strategy performance (updates every 1 second):

- **All-Time Metrics**
  - Total trades, Win rate %, Total P&L (color-coded)
  - Best/worst trades

- **Today's Performance**
  - Today's trades, P&L, Max drawdown

- **Current Activity**
  - Active signals, Last score (e.g., "12/10")
  - Last signal time (e.g., "2 min ago")

- **Adaptive Thresholds** (Instrument Learning)
  - Shows what the strategy learned about the instrument
  - Average order count, Average order size
  - Current threshold (updates in real-time)

- **Export Functionality**
  - Trade history → CSV (Excel-ready)
  - Performance report → TXT (formatted)

---

## 🔬 Signal Types

### 🧊 Iceberg Orders

**What**: Hidden large orders showing as repeated small orders at the same price

**Detection**:
- 3x normal order activity at same price
- 3x normal total size
- Uses rolling window of last 100 events

**Visual**: Green/red dots on chart

**Trading**: Fade the direction (iceberg on ask = look to buy)

### 🎭 Spoof Orders

**What**: Fake large orders cancelled quickly

**Detection**:
- Large orders (5+ contracts)
- Cancelled within 500ms
- Never filled

**Visual**: Magenta dots on chart

**Trading**: Fade the direction (spoof bid = look to sell)

### 🟡 Absorption

**What**: Large trades eating levels without moving price

**Detection**:
- Large aggressive trades (10+ contracts)
- Price doesn't move
- Shows hidden interest

**Visual**: Yellow dots on chart

**Trading**: Trade with absorption (absorption+ = buy, absorption- = sell)

---

## 📈 Performance Tracking

### Real-Time Metrics

- Total trades (all-time)
- Win rate percentage
- Total P&L (color-coded green/red)
- Best and worst trades
- Today's P&L and drawdown
- Current equity curve

### Alert System 🚨

- ⛔ **Daily Loss Limit Reached** → Auto kill switch
- ⚠️ **Approaching Max Drawdown** → Warning at 80%
- 📉 **Win Rate Declining** → Monitors last 10 trades

---

## 🧪 Testing

### Expected Signal Frequency

- **ESH6** (S&P 500): 2-10 signals per hour
- **NQH6** (Nasdaq): 5-15 signals per hour
- **GCH6** (Gold): 1-5 signals per hour

### Manual Testing

1. Load strategy on chart
2. Wait for MBO events (check console)
3. Verify signals appear on chart
4. Check statistics panel updates
5. Test settings panel adjustments
6. Export data and verify files

---

## 📚 Documentation

- **[ORDER_FLOW_TRADING_PLAN.md](ORDER_FLOW_TRADING_PLAN.md)** - Comprehensive trading plan (2000+ lines)
  - All data sources (MBO, DOM, CVD, Volume Profile)
  - Confluence scoring system (13 factors)
  - Risk management (ATR-based stops)
  - Trade examples with full analysis
  - Implementation roadmap

- **[FEATURE_UI_PANELS_AI.md](FEATURE_UI_PANELS_AI.md)** - UI Panels feature documentation
  - Screenshots and layouts
  - Usage instructions
  - Testing checklist
  - Troubleshooting guide

---

## 🛠️ Architecture

### Class Structure

```
OrderFlowStrategyEnhanced.java (800+ lines)
├── Custom Settings Panel
│   ├── Signal thresholds (spinners)
│   ├── Safety controls (checkboxes)
│   └── Risk management (spinners)
│
├── Statistics Panel
│   ├── Performance metrics (real-time)
│   ├── Adaptive thresholds (instrument learning)
│   ├── AI insights area (placeholder for Phase 8)
│   └── Export functionality
│
├── Signal Detection
│   ├── Iceberg detector (adaptive)
│   ├── Spoof detector (time-based)
│   └── Absorption detector (volume-based)
│
└── Performance Tracking
    ├── Trade history (ArrayList)
    ├── P&L calculation (AtomicLong)
    └── Alert system (kill switch)
```

### Technologies

- **Bookmap API** v7.6.0.20
- **Java** 8+
- **Swing** (UI panels)
- **ScheduledExecutorService** (1-second updates)

### Thread Safety

- `SwingUtilities.invokeLater()` for all UI updates
- `AtomicInteger` / `AtomicLong` for counters
- No race conditions
- Proper resource cleanup

---

## 🗺️ Roadmap

### ✅ Completed

- **Phase 1**: Data Collection (MBO, Trades, DOM)
- **Phase 2**: Confluence Scoring (13-factor system)
- **Phase 3**: Historical Analysis (support/resistance)
- **Phase 4**: Risk Management (ATR-based stops)
- **Phase 5-6**: Paper Trading (SIM mode)
- **Phase 7**: Custom UI Panels ⭐ **THIS VERSION**

### ⏳ In Progress

- **Phase 8**: AI Integration (Week 9-10)
  - [ ] Claude SDK integration
  - [ ] Market regime analysis (60-second updates)
  - [ ] Performance coaching (daily analysis)
  - [ ] Parameter optimization (weekly)
  - [ ] "Ask AI" button functionality

### 📋 Planned

- **Phase 9**: Paper Trading with AI (Week 11-12)
- **Phase 10**: Go Live (Week 13+)

---

## 🔒 Safety Features

### SIM Mode Lock

- Defaults to ENABLED
- Warning dialog when disabling
- Cannot enable auto-execution while in SIM mode
- **Defense in depth**

### Daily Loss Limit

- Enforced at $500 default
- Auto kill switch when reached
- Critical alert dialog
- **Prevents catastrophic losses**

### Thread Safety

- All UI updates on EDT
- AtomicXXX for counters
- Proper resource cleanup
- No memory leaks

---

## 📊 Expected Performance

### Win Rate Targets

- **Required**: 40%+ (with 2R targets, 40% = breakeven)
- **Good**: 50%+
- **Excellent**: 60%+

### Risk:Reward

- **Target**: 2R minimum per trade
- **Average**: 2.5R
- **Max Risk**: 1% of account per trade

### Monthly Goals

- **Trades**: 20-30 quality setups (1-2 per day)
- **Win Rate**: 45-55%
- **Expected Monthly**: 20 trades × 45% win × 2.5R = **22.5R profit**

---

## ⚠️ Disclaimer

**THIS IS NOT FINANCIAL ADVICE**

- Trading futures carries substantial risk
- Past performance does not guarantee future results
- Only trade with money you can afford to lose
- Always paper trade first (SIM mode enforced by default)
- You are responsible for your trading decisions

---

## 📞 Support

For issues or questions:

- **Bookmap Discord**: https://discord.gg/bookmap
- **Bookmap Documentation**: https://bookmap.com/docs
- **GitHub Issues**: https://github.com/blevinson/qid/issues

---

## 📜 License

This project is for personal use. Based on Bookmap API examples.

---

## 🙏 Acknowledgments

- **Bookmap** for the excellent platform and API
- **Anthropic** for Claude AI assistance
- **Trading community** for order flow insights

---

## 📦 Repository Contents

```
qid/
├── ORDER_FLOW_TRADING_PLAN.md     (Comprehensive 2000+ line plan)
├── FEATURE_UI_PANELS_AI.md        (UI Panels documentation)
├── QID_README.md                  (This file)
│
└── Strategies/src/main/java/velox/api/layer1/simplified/demo/
    ├── OrderFlowMboSimple.java          (Original adaptive MBO)
    └── OrderFlowStrategyEnhanced.java   (NEW - UI Panels + Performance)
```

---

**"The market is always right. Price never lies. But order flow tells you WHY."**

*Created with ❤️ by Brant Levinson*
*AI-Assisted Development using Claude*
*Phase 7: UI Panels & Performance Tracking*
