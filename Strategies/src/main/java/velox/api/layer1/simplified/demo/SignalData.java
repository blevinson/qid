package velox.api.layer1.simplified.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * Complete signal data structure with scoring breakdown
 * This is what the AI will see when evaluating a signal
 */
public class SignalData {
    // Basic signal info
    public String direction;  // "LONG" or "SHORT"
    public int price;
    public int score;
    public int threshold;
    public boolean thresholdPassed;
    public long timestamp;
    public int pips;  // Minimum price increment (pip size)

    // Score breakdown
    public ScoreBreakdown scoreBreakdown;

    // Detection details
    public DetectionDetails detection;

    // Market context
    public MarketContext market;

    // Account context
    public AccountContext account;

    // Performance history
    public PerformanceHistory performance;

    // Risk management
    public RiskManagement risk;

    public static class ScoreBreakdown {
        // Iceberg detection
        public int icebergPoints;
        public String icebergDetails;
        public int icebergCount;
        public int totalSize;

        // Trend (now with real EMAs)
        public int trendPoints;
        public String trendDetails;
        public int ema9;
        public int ema21;
        public int ema50;
        public int emaAlignmentCount;  // How many EMAs confirm (0-3)

        // VWAP
        public int vwapPoints;
        public String vwapDetails;
        public int vwap;
        public String priceVsVwap;

        // Volume (CVD + Volume Profile)
        public int volumePoints;
        public String volumeDetails;
        public int volumeAtLevel;

        // ========== NEW CONFLUENCE FACTORS ==========
        public int cvdPoints;  // CVD confirmation points
        public String cvdDetails;  // CVD explanation
        public int cvdDivergencePoints;  // CVD divergence bonus/penalty

        public int volumeProfilePoints;  // Volume profile confirmation
        public String volumeProfileDetails;  // Volume profile explanation

        public int volumeImbalancePoints;  // Volume imbalance confirmation
        public String volumeImbalanceDetails;  // Volume imbalance explanation

        public int emaTrendPoints;  // EMA trend alignment
        public String emaTrendDetails;  // EMA trend explanation
    }

    public static class DetectionDetails {
        public String type;  // "ICEBERG_BUY", "ICEBERG_SELL", "SPOOFING", "ABSORPTION"
        public long timeSpanMs;
        public int totalOrders;
        public int totalSize;
        public double averageSize;
        public String[] patternsFound;
    }

    public static class MarketContext {
        public String symbol;
        public String timeOfDay;
        public String tradingSession;

        public int currentPrice;
        public int bid;
        public int ask;
        public int spreadTicks;

        public String trend;  // "BULLISH", "BEARISH", "NEUTRAL"
        public String priceVsEma9;
        public String priceVsEma21;
        public String priceVsEma50;

        public double atr;
        public String atrLevel;  // "LOW", "MODERATE", "HIGH"

        public long currentVolume;
        public long avgVolumeAtTime;
        public double volumeRatio;

        // ========== ENHANCED CONFLUENCE DATA ==========

        // CVD (Cumulative Volume Delta)
        public long cvd;  // Current CVD value
        public long cvdAtSignalPrice;  // CVD at the signal price level
        public String cvdTrend;  // "BULLISH", "BEARISH", "NEUTRAL"
        public double cvdStrength;  // CVD as percentage of total volume
        public String cvdDivergence;  // "NONE", "BULLISH_DIVERGENCE", "BEARISH_DIVERGENCE"
        public double cvdBuySellRatio;  // Buy volume / Sell volume ratio

        // Volume Profile
        public long volumeAtSignalPrice;  // Volume at exact signal price
        public long volumeNearby;  // Total volume in nearby range
        public double volumeRatioAtPrice;  // Signal volume / nearby volume (0.0 to 1.0)
        public String volumeLevelType;  // "HIGH_VOLUME_NODE", "LOW_VOLUME_NODE", "NORMAL"
        public int pocPrice;  // Point of Control price
        public int valueAreaLow;  // Value Area Low
        public int valueAreaHigh;  // Value Area High

        // Volume Imbalance
        public long bidVolumeAtPrice;  // Bid volume at signal price
        public long askVolumeAtPrice;  // Ask volume at signal price
        public double volumeImbalanceRatio;  // Bid/Ask ratio
        public String volumeImbalanceSentiment;  // "STRONG_BUYING", "BUYING", "BALANCED", "SELLING", "STRONG_SELLING"

        // VWAP
        public double vwap;  // Current VWAP
        public String priceVsVwap;  // "ABOVE", "BELOW", "NEAR"
        public double vwapDistanceTicks;  // Distance from VWAP in ticks

        // EMAs
        public double ema9;
        public double ema21;
        public double ema50;
        public double ema9DistanceTicks;  // Distance from EMA9
        public double ema21DistanceTicks;  // Distance from EMA21
        public double ema50DistanceTicks;  // Distance from EMA50

        // Trend Confirmation
        public boolean emaTrendAlignment;  // Are EMAs aligned with signal direction?
        public int emaAlignmentCount;  // How many EMAs confirm the direction (0-3)
        public String trendStrength;  // "WEAK", "MODERATE", "STRONG"
    }

    public static class AccountContext {
        public double accountSize;
        public double currentBalance;
        public double dailyPnl;
        public double dailyPnlPercent;

        public int tradesToday;
        public int winsToday;
        public int lossesToday;
        public double winRateToday;

        public double dailyLossLimit;
        public double dailyLossRemaining;
        public double maxDrawdownPercent;
        public double currentDrawdownPercent;
        public double riskPerTradePercent;

        public int maxContracts;
        public int maxTradesPerDay;
        public int tradesRemainingToday;
    }

    public static class PerformanceHistory {
        public int totalTrades;
        public double winRate;
        public double avgWin;
        public double avgLoss;
        public double profitFactor;

        public double icebergLongWinRate;
        public int icebergLongTotal;
        public double icebergLongAvgPnl;

        public double icebergShortWinRate;
        public int icebergShortTotal;
        public double icebergShortAvgPnl;

        public Map<String, Double> timeOfDayWinRates;
        public double last7DaysWinRate;
        public String[] lastThreeTrades;  // "WIN", "LOSS", "WIN"
        public int currentStreak;
    }

    public static class RiskManagement {
        public int stopLossTicks;
        public int stopLossPrice;
        public double stopLossValue;

        public int takeProfitTicks;
        public int takeProfitPrice;
        public double takeProfitValue;

        public int breakEvenTicks;
        public int breakEvenPrice;

        public String riskRewardRatio;
        public int positionSizeContracts;
        public double totalRiskPercent;
    }

    /**
     * Create a formatted string representation for AI
     */
    public String toAIString() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append(String.format("SIGNAL: %s @ %d\n", direction, price));
        sb.append(String.format("SCORE: %d/100 (%s threshold of %d)\n",
            score, thresholdPassed ? "✅ Above" : "❌ Below", threshold));

        sb.append("\n📊 SCORE BREAKDOWN:\n");
        if (scoreBreakdown != null) {
            sb.append(String.format("├─ Iceberg Orders: +%d points (%s)\n",
                scoreBreakdown.icebergPoints, scoreBreakdown.icebergDetails));
            sb.append(String.format("├─ Trend Alignment: +%d points (%s)\n",
                scoreBreakdown.trendPoints, scoreBreakdown.trendDetails));
            sb.append(String.format("├─ VWAP Alignment: +%d points (%s)\n",
                scoreBreakdown.vwapPoints, scoreBreakdown.vwapDetails));
            sb.append(String.format("└─ Volume Profile: +%d points (%s)\n",
                scoreBreakdown.volumePoints, scoreBreakdown.volumeDetails));
        }

        sb.append("\n🔍 DETECTION DETAILS:\n");
        if (detection != null) {
            sb.append(String.format("├─ Type: %s\n", detection.type));
            sb.append(String.format("├─ Orders: %d (avg size: %.1f, total: %d)\n",
                detection.totalOrders, detection.averageSize, detection.totalSize));
            sb.append(String.format("├─ Time span: %.1f seconds\n", detection.timeSpanMs / 1000.0));
            if (detection.patternsFound != null && detection.patternsFound.length > 0) {
                sb.append("└─ Patterns: ");
                for (int i = 0; i < detection.patternsFound.length; i++) {
                    if (i > 0) sb.append(" + ");
                    sb.append(detection.patternsFound[i]);
                }
                sb.append("\n");
            }
        }

        sb.append("\n📈 MARKET CONTEXT:\n");
        if (market != null) {
            sb.append(String.format("├─ Time: %s\n", market.timeOfDay));
            sb.append(String.format("├─ Trend: %s\n", market.trend));
            sb.append(String.format("├─ Volatility: %s (ATR %.2f)\n", market.atrLevel, market.atr));
            sb.append(String.format("├─ Spread: %d ticks\n", market.spreadTicks));
            sb.append(String.format("└─ Volume: %.0f%% above average\n",
                (market.volumeRatio - 1.0) * 100));
        }

        sb.append("\n💰 ACCOUNT STATUS:\n");
        if (account != null) {
            sb.append(String.format("├─ Balance: $%.2f ($%+.2f today, %+.1f%%)\n",
                account.currentBalance, account.dailyPnl, account.dailyPnlPercent));
            sb.append(String.format("├─ Trades today: %d/%d (%d wins, %d losses)\n",
                account.tradesToday, account.maxTradesPerDay, account.winsToday, account.lossesToday));
            sb.append(String.format("├─ Win rate today: %.0f%%\n", account.winRateToday));
            sb.append(String.format("└─ Risk per trade: %.1f%%\n", account.riskPerTradePercent));
        }

        sb.append("\n📊 PATTERN PERFORMANCE:\n");
        if (performance != null) {
            if ("LONG".equals(direction)) {
                sb.append(String.format("├─ Iceberg LONG signals: %.0f%% win rate (%d/%d)\n",
                    performance.icebergLongWinRate,
                    (int)(performance.icebergLongTotal * performance.icebergLongWinRate / 100),
                    performance.icebergLongTotal));
            } else {
                sb.append(String.format("├─ Iceberg SHORT signals: %.0f%% win rate (%d/%d)\n",
                    performance.icebergShortWinRate,
                    (int)(performance.icebergShortTotal * performance.icebergShortWinRate / 100),
                    performance.icebergShortTotal));
            }
            sb.append(String.format("├─ Last 7 days: %.0f%% win rate\n", performance.last7DaysWinRate));
            if (performance.lastThreeTrades != null && performance.lastThreeTrades.length > 0) {
                sb.append("└─ Last 3 trades: ");
                for (int i = 0; i < performance.lastThreeTrades.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(performance.lastThreeTrades[i]);
                }
                sb.append("\n");
            }
        }

        sb.append("\n⚙️ RISK PARAMETERS:\n");
        if (risk != null) {
            sb.append(String.format("├─ Stop: %d (-%d ticks = -$%.0f)\n",
                risk.stopLossPrice, risk.stopLossTicks, risk.stopLossValue));
            sb.append(String.format("├─ Target: %d (+%d ticks = +$%.0f)\n",
                risk.takeProfitPrice, risk.takeProfitTicks, risk.takeProfitValue));
            sb.append(String.format("├─ Break-even: %d (+%d ticks)\n",
                risk.breakEvenPrice, risk.breakEvenTicks));
            sb.append(String.format("└─ R:R Ratio: %s\n", risk.riskRewardRatio));
        }

        sb.append("═══════════════════════════════════════════════════════════════\n");

        return sb.toString();
    }
}
