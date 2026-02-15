package velox.api.layer1.simplified.demo;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Signal Snapshot - Captures EVERYTHING used to generate a signal
 *
 * This is a comprehensive data dump for post-analysis, debugging,
 * and AI learning. It captures the complete state of the market
 * and strategy at the moment a signal is generated.
 *
 * Saved to: trading-memory/signal-snapshots/{date}/{signalId}.json
 */
public class SignalSnapshot {

    // ========== METADATA ==========
    public String signalId;
    public long timestamp;           // Unix timestamp (from Bookmap data time)
    public String formattedTime;     // Human-readable ET time
    public String instrument;
    public double pips;

    // ========== BASIC SIGNAL INFO ==========
    public String direction;         // "LONG" or "SHORT"
    public int priceTicks;           // Signal price in ticks
    public double priceActual;       // Signal price in actual units
    public int confluenceScore;
    public int confluenceThreshold;

    // ========== ICEBERG DETECTION ==========
    public IcebergData iceberg;

    public static class IcebergData {
        public int orderCount;              // Number of orders at price level
        public int totalSize;               // Sum of all order sizes
        public int adaptiveOrderThreshold;  // Threshold used
        public int adaptiveSizeThreshold;   // Threshold used
        public List<OrderDetail> orders;    // Individual orders at level

        // Price level history (how orders accumulated)
        public List<PriceLevelEvent> accumulationHistory;
    }

    public static class OrderDetail {
        public String orderId;
        public int size;
        public boolean isBid;
        public long timestamp;
    }

    public static class PriceLevelEvent {
        public long timestamp;
        public String action;  // "ADD" or "REMOVE"
        public int orderCount;
        public int totalSize;
    }

    // ========== MARKET CONTEXT ==========
    public MarketContext market;

    public static class MarketContext {
        // Current prices
        public int bidTicks;
        public int askTicks;
        public int midTicks;
        public int bidSize;
        public int askSize;

        // CVD
        public long cvd;
        public long cvdAtSignalPrice;
        public String cvdTrend;
        public double cvdStrength;
        public double cvdBuySellRatio;
        public String cvdDivergence;

        // EMAs
        public double ema9;
        public double ema21;
        public double ema50;
        public String emaAlignment;  // "BULLISH", "BEARISH", "NEUTRAL"
        public int ema9_ticks;
        public int ema21_ticks;
        public int ema50_ticks;

        // VWAP
        public double vwap;
        public int vwapTicks;
        public String priceVsVwap;  // "ABOVE", "BELOW", "AT"
        public double vwapDistance;

        // Volume Profile
        public int pocTicks;
        public double pocActual;
        public int vaHighTicks;
        public int vaLowTicks;
        public double volumeAtSignalPrice;
        public double volumeRatioAtSignal;
        public String volumeImbalance;

        // DOM / Liquidity
        public List<DomLevel> domBidLevels;
        public List<DomLevel> domAskLevels;
    }

    public static class DomLevel {
        public int priceTicks;
        public long size;
        public int orderCount;
    }

    // ========== CONFLUENCE BREAKDOWN ==========
    public ConfluenceBreakdown confluence;

    public static class ConfluenceBreakdown {
        // Iceberg
        public int icebergPoints;
        public String icebergDetails;
        public int icebergCount;
        public int totalSize;

        // CVD
        public int cvdPoints;
        public String cvdDetails;
        public int cvdDivergencePoints;

        // EMA
        public int emaPoints;
        public String emaDetails;

        // VWAP
        public int vwapPoints;
        public String vwapDetails;

        // Volume Profile
        public int volumeProfilePoints;
        public String volumeProfileDetails;

        // Session/Phase
        public int sessionPoints;
        public String sessionDetails;

        // R:R Quality
        public int rrPoints;
        public String rrDetails;

        // Total
        public int totalPoints;
        public int maxPossiblePoints;
    }

    // ========== RISK CALCULATIONS ==========
    public RiskData risk;

    public static class RiskData {
        public int stopLossTicks;
        public int takeProfitTicks;
        public double stopLossActual;
        public double takeProfitActual;
        public int slDistanceTicks;
        public int tpDistanceTicks;
        public double rrRatio;
        public String rrQuality;       // "EXCELLENT", "GOOD", "FAIR", "POOR"

        public String slReasoning;
        public String tpReasoning;

        // Risk amount calculations
        public double tickValue;
        public double slDollarRisk;
        public double tpDollarTarget;

        // ATR used
        public double atrValue;
        public int atrTicks;
    }

    // ========== SESSION CONTEXT ==========
    public SessionData session;

    public static class SessionData {
        public String phase;               // "MORNING_SESSION", "LUNCH", etc.
        public String phaseDescription;
        public int minutesIntoSession;
        public int signalsThisSession;
        public int tradesThisSession;
        public double sessionPnl;
        public double dailyPnl;
    }

    // ========== RECENT ACTIVITY (LEADING UP TO SIGNAL) ==========
    public RecentActivity recentActivity;

    public static class RecentActivity {
        public List<TradeEvent> last20Trades;
        public List<BboEvent> last20Bbo;
        public List<SignalEvent> recentSignals;  // Other signals in last 5 min
    }

    public static class TradeEvent {
        public long timestamp;
        public int priceTicks;
        public int size;
        public boolean isBuy;
    }

    public static class BboEvent {
        public long timestamp;
        public int bidTicks;
        public int askTicks;
        public int bidSize;
        public int askSize;
    }

    public static class SignalEvent {
        public long timestamp;
        public int priceTicks;
        public String direction;
        public int score;
        public String outcome;  // "PENDING", "WIN", "LOSS", "SKIPPED"
    }

    // ========== ORDER FLOW STATE ==========
    public OrderFlowState orderFlow;

    public static class OrderFlowState {
        public int totalIcebergSignalsToday;
        public int totalAbsorptionSignalsToday;
        public int totalSpoofSignalsToday;
        public int activePositions;

        // Liquidity tracker state
        public Map<Integer, Integer> bidLiquidityByLevel;
        public Map<Integer, Integer> askLiquidityByLevel;
    }

    // ========== WEIGHTS USED ==========
    public WeightsData weights;

    public static class WeightsData {
        public int icebergMax;
        public int cvdAlignMax;
        public int cvdDivergePenalty;
        public int emaAlignMax;
        public int vwapAlign;
        public int vwapDiverge;
    }

    // ========== RAW DATA (for deep debugging) ==========
    public Map<String, Object> rawData;

    // =====================================================
    // METHODS
    // =====================================================

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create();

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z");

    /**
     * Save snapshot to JSON file
     * Path: {memoryDir}/signal-snapshots/{date}/{signalId}.json
     */
    public void save(File memoryDir) {
        try {
            // Create directory structure
            ZonedDateTime etTime = Instant.ofEpochMilli(timestamp).atZone(ET_ZONE);
            String dateStr = etTime.format(DATE_FMT);

            File snapshotDir = new File(memoryDir, "signal-snapshots/" + dateStr);
            if (!snapshotDir.exists()) {
                snapshotDir.mkdirs();
            }

            // Create filename with time and price for easy identification
            String timeStr = etTime.format(DateTimeFormatter.ofPattern("HHmmss"));
            String filename = String.format("%s_%s_%d_%s.json",
                timeStr,
                direction,
                priceTicks,
                signalId.substring(0, Math.min(8, signalId.length()))
            );

            File snapshotFile = new File(snapshotDir, filename);

            // Write JSON
            String json = GSON.toJson(this);
            Files.writeString(snapshotFile.toPath(), json);

            // Also write a summary line to index file
            File indexFile = new File(snapshotDir, "_index.txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(indexFile, true))) {
                pw.println(String.format("%s | %s @ %d | Score: %d | File: %s",
                    etTime.format(DATETIME_FMT),
                    direction,
                    priceTicks,
                    confluenceScore,
                    filename
                ));
            }

            System.out.println("📸 Signal snapshot saved: " + snapshotFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Failed to save signal snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get a summary string for logging
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("📸 SIGNAL SNAPSHOT\n");
        sb.append("═".repeat(50)).append("\n");
        sb.append(String.format("Signal: %s @ %d ticks ($%.2f)\n", direction, priceTicks, priceActual));
        sb.append(String.format("Time: %s (ET)\n", formattedTime));
        sb.append(String.format("Score: %d / %d threshold\n", confluenceScore, confluenceThreshold));
        sb.append("\n");

        if (iceberg != null) {
            sb.append("🧊 ICEBERG DETECTION:\n");
            sb.append(String.format("   Orders: %d (threshold: %d)\n", iceberg.orderCount, iceberg.adaptiveOrderThreshold));
            sb.append(String.format("   Total Size: %d (threshold: %d)\n", iceberg.totalSize, iceberg.adaptiveSizeThreshold));
            if (iceberg.orders != null && !iceberg.orders.isEmpty()) {
                sb.append("   Order Details:\n");
                for (OrderDetail od : iceberg.orders.subList(0, Math.min(5, iceberg.orders.size()))) {
                    sb.append(String.format("     - %s: size=%d\n", od.orderId.substring(0, 8), od.size));
                }
                if (iceberg.orders.size() > 5) {
                    sb.append(String.format("     ... and %d more orders\n", iceberg.orders.size() - 5));
                }
            }
        }

        if (market != null) {
            sb.append("\n📊 MARKET CONTEXT:\n");
            sb.append(String.format("   Bid/Ask: %d / %d (sizes: %d / %d)\n",
                market.bidTicks, market.askTicks, market.bidSize, market.askSize));
            sb.append(String.format("   CVD: %d (%s, strength: %.1f%%)\n",
                market.cvd, market.cvdTrend, market.cvdStrength));
            sb.append(String.format("   EMAs: 9=%d, 21=%d, 50=%d (%s)\n",
                market.ema9_ticks, market.ema21_ticks, market.ema50_ticks, market.emaAlignment));
            sb.append(String.format("   VWAP: %d (%s, dist: %.1f ticks)\n",
                market.vwapTicks, market.priceVsVwap, market.vwapDistance));
            sb.append(String.format("   POC: %d, VA: %d-%d\n",
                market.pocTicks, market.vaLowTicks, market.vaHighTicks));
        }

        if (confluence != null) {
            sb.append("\n🎯 CONFLUENCE BREAKDOWN:\n");
            sb.append(String.format("   Iceberg: +%d (%s)\n", confluence.icebergPoints, confluence.icebergDetails));
            sb.append(String.format("   CVD: %+d (%s)\n", confluence.cvdPoints, confluence.cvdDetails));
            sb.append(String.format("   EMA: %+d (%s)\n", confluence.emaPoints, confluence.emaDetails));
            sb.append(String.format("   VWAP: %+d (%s)\n", confluence.vwapPoints, confluence.vwapDetails));
            sb.append(String.format("   VolProfile: %+d\n", confluence.volumeProfilePoints));
            sb.append(String.format("   R:R: %+d (%s)\n", confluence.rrPoints, confluence.rrDetails));
            sb.append(String.format("   TOTAL: %d points\n", confluence.totalPoints));
        }

        if (risk != null) {
            sb.append("\n⚖️ RISK MANAGEMENT:\n");
            sb.append(String.format("   SL: %d ticks ($%.2f) - %s\n", risk.slDistanceTicks, risk.stopLossActual, risk.slReasoning));
            sb.append(String.format("   TP: %d ticks ($%.2f) - %s\n", risk.tpDistanceTicks, risk.takeProfitActual, risk.tpReasoning));
            sb.append(String.format("   R:R: 1:%.1f (%s)\n", risk.rrRatio, risk.rrQuality));
            sb.append(String.format("   Risk: $%.0f | Target: $%.0f\n", risk.slDollarRisk, risk.tpDollarTarget));
        }

        if (session != null) {
            sb.append("\n📅 SESSION:\n");
            sb.append(String.format("   Phase: %s (%d min in)\n", session.phase, session.minutesIntoSession));
            sb.append(String.format("   Today: %d signals, %d trades, P&L: $%.2f\n",
                session.signalsThisSession, session.tradesThisSession, session.dailyPnl));
        }

        return sb.toString();
    }

    /**
     * Create a builder for constructing snapshots
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SignalSnapshot snapshot = new SignalSnapshot();

        public Builder signalId(String id) { snapshot.signalId = id; return this; }
        public Builder timestamp(long ts) { snapshot.timestamp = ts; return this; }
        public Builder instrument(String inst) { snapshot.instrument = inst; return this; }
        public Builder pips(double p) { snapshot.pips = p; return this; }
        public Builder direction(String dir) { snapshot.direction = dir; return this; }
        public Builder priceTicks(int p) { snapshot.priceTicks = p; return this; }
        public Builder confluenceScore(int s) { snapshot.confluenceScore = s; return this; }
        public Builder confluenceThreshold(int t) { snapshot.confluenceThreshold = t; return this; }
        public Builder iceberg(IcebergData i) { snapshot.iceberg = i; return this; }
        public Builder market(MarketContext m) { snapshot.market = m; return this; }
        public Builder confluence(ConfluenceBreakdown c) { snapshot.confluence = c; return this; }
        public Builder risk(RiskData r) { snapshot.risk = r; return this; }
        public Builder session(SessionData s) { snapshot.session = s; return this; }
        public Builder recentActivity(RecentActivity r) { snapshot.recentActivity = r; return this; }
        public Builder orderFlow(OrderFlowState o) { snapshot.orderFlow = o; return this; }
        public Builder weights(WeightsData w) { snapshot.weights = w; return this; }
        public Builder rawData(Map<String, Object> r) { snapshot.rawData = r; return this; }

        public SignalSnapshot build() {
            // Auto-calculate derived fields
            snapshot.priceActual = snapshot.priceTicks * snapshot.pips;
            snapshot.formattedTime = Instant.ofEpochMilli(snapshot.timestamp)
                .atZone(ET_ZONE)
                .format(DATETIME_FMT);
            return snapshot;
        }
    }
}
