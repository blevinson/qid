package velox.api.layer1.simplified.demo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * Session Context for Trading
 * Tracks the current trading session state for AI context
 *
 * Per MEMORY_SESSIONS_PATTERN_SPEC.md:
 * - AI needs to know if this is a new session or continuation
 * - Indicators (CVD, VWAP) reset at session start
 * - Memory should be filtered by session relevance
 */
public class SessionContext {

    // ========== MARKET SNAPSHOT ==========
    /**
     * A point-in-time market snapshot for AI context accumulation.
     * Snapshots build a narrative of session conditions over time.
     */
    public static class MarketSnapshot {
        public final long timestamp;
        public final double price;
        public final double cvd;
        public final double vwap;
        public final int emaAlignment;  // -3 to +3
        public final String trend;
        public final SessionPhase phase;
        public final int recentSignals;
        public final double sessionPnl;
        public final int tradesThisSession;
        public final double sessionWinRate;

        public MarketSnapshot(long timestamp, double price, double cvd, double vwap,
                              int emaAlignment, String trend, SessionPhase phase,
                              int recentSignals, double sessionPnl,
                              int tradesThisSession, double sessionWinRate) {
            this.timestamp = timestamp;
            this.price = price;
            this.cvd = cvd;
            this.vwap = vwap;
            this.emaAlignment = emaAlignment;
            this.trend = trend;
            this.phase = phase;
            this.recentSignals = recentSignals;
            this.sessionPnl = sessionPnl;
            this.tradesThisSession = tradesThisSession;
            this.sessionWinRate = sessionWinRate;
        }

        /**
         * Format for AI context / transcript
         */
        public String toAIString() {
            java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
            java.time.ZonedDateTime dataTime = java.time.Instant.ofEpochMilli(timestamp).atZone(etZone);
            String timeStr = dataTime.format(DateTimeFormatter.ofPattern("HH:mm"));

            return String.format("[%s] Price: %.2f | CVD: %+.0f | VWAP: %.2f | EMA: %+d | Trend: %s | Phase: %s | Signals: %d | P&L: $%.2f",
                timeStr, price, cvd, vwap, emaAlignment, trend, phase, recentSignals, sessionPnl);
        }

        /**
         * Brief format for compact display
         */
        public String toBriefString() {
            java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
            java.time.ZonedDateTime dataTime = java.time.Instant.ofEpochMilli(timestamp).atZone(etZone);
            String timeStr = dataTime.format(DateTimeFormatter.ofPattern("HH:mm"));

            return String.format("[%s] %.2f | CVD %+d | EMA %+d | %s",
                timeStr, price, (int)cvd, emaAlignment, trend);
        }
    }

    // Snapshot storage - accumulates throughout session
    private final List<MarketSnapshot> snapshots = new ArrayList<>();
    private static final int MAX_SNAPSHOTS = 100;  // Keep last 100 (~8+ hours at 5min intervals)

    // ========== WARM-UP CONFIGURATION ==========
    // Signals are suppressed until warm-up is complete
    // This allows indicators (CVD, VWAP, EMAs, Volume Profile) to accumulate data
    public static final int WARMUP_MINUTES = 5;        // Wait at least 5 minutes
    public static final int WARMUP_TRADES = 100;       // Or 100 trades processed
    public static final int WARMUP_PRICE_TICKS = 50;   // Or 50 price updates

    // Session identification
    private String sessionId;
    private LocalDate sessionDate;
    private long sessionStartTimeMs;

    // Session state
    private boolean isNewSession;
    private int minutesIntoSession;

    // Warm-up tracking
    private int tradesProcessed;
    private int priceUpdatesProcessed;
    private boolean warmupComplete;

    // Session performance
    private int tradesThisSession;
    private int winsThisSession;
    private int lossesThisSession;
    private double sessionPnl;

    // Session phases (typical US trading hours)
    public enum SessionPhase {
        PRE_MARKET,      // Before 09:30 ET
        OPENING_BELL,    // 09:30 - 10:00 ET (high volatility)
        MORNING_SESSION, // 10:00 - 12:00 ET
        LUNCH_HOUR,      // 12:00 - 14:00 ET (low volume)
        AFTERNOON,       // 14:00 - 15:30 ET
        CLOSING_BELL,    // 15:30 - 16:00 ET (high volatility)
        AFTER_HOURS      // After 16:00 ET
    }
    private SessionPhase currentPhase;

    // Replay mode settings
    private int replayStartHour = 9;    // Default: 9:30 AM market open
    private int replayStartMinute = 30;

    // Session flags
    private boolean cvdReset;
    private boolean vwapReset;
    private boolean firstSignalProcessed;

    // Market context
    private String instrument;
    private double sessionOpenPrice;
    private double sessionHigh;
    private double sessionLow;
    private double currentPrice;

    // Time formatters
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SessionContext() {
        this.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.sessionDate = LocalDate.now();
        this.sessionStartTimeMs = System.currentTimeMillis();
        this.isNewSession = true;
        this.minutesIntoSession = 0;
        this.tradesProcessed = 0;
        this.priceUpdatesProcessed = 0;
        this.warmupComplete = false;
        this.tradesThisSession = 0;
        this.winsThisSession = 0;
        this.lossesThisSession = 0;
        this.sessionPnl = 0.0;
        this.cvdReset = true;
        this.vwapReset = true;
        this.firstSignalProcessed = false;
        this.currentPhase = SessionPhase.PRE_MARKET;
        updatePhase();
    }

    /**
     * Initialize a new trading session
     * Call this at strategy start or when a new trading day begins
     */
    public void startNewSession(String instrument, double openPrice) {
        startNewSession(instrument, openPrice, System.currentTimeMillis());
    }

    /**
     * Initialize a new trading session with explicit start time (for replay mode)
     * @param instrument Trading instrument
     * @param openPrice Session opening price
     * @param startTimeMs Session start time in milliseconds (use data timestamp for replay)
     */
    public void startNewSession(String instrument, double openPrice, long startTimeMs) {
        this.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.sessionDate = LocalDate.now();
        this.sessionStartTimeMs = startTimeMs;
        this.isNewSession = true;
        this.minutesIntoSession = 0;
        this.tradesProcessed = 0;
        this.priceUpdatesProcessed = 0;
        this.warmupComplete = false;
        this.tradesThisSession = 0;
        this.winsThisSession = 0;
        this.lossesThisSession = 0;
        this.sessionPnl = 0.0;
        this.cvdReset = true;
        this.vwapReset = true;
        this.firstSignalProcessed = false;
        this.instrument = instrument;
        this.sessionOpenPrice = openPrice;
        this.sessionHigh = openPrice;
        this.sessionLow = openPrice;
        this.currentPrice = openPrice;
        updatePhase(startTimeMs);
    }

    /**
     * Adjust session start time based on first data timestamp (for replay mode)
     * This is called once when we receive the first valid data timestamp
     */
    public void adjustSessionStart(long dataTimestampMs) {
        // Only adjust if we haven't processed much data yet
        if (priceUpdatesProcessed < 10) {
            this.sessionStartTimeMs = dataTimestampMs;
            this.minutesIntoSession = 0;
            updatePhase(dataTimestampMs);
        }
    }

    /**
     * Update session state - call periodically (e.g., on each price update)
     */
    public void update(double currentPrice) {
        update(currentPrice, System.currentTimeMillis());
    }

    /**
     * Update session state with explicit timestamp (for replay mode)
     */
    public void update(double currentPrice, long timestamp) {
        this.currentPrice = currentPrice;
        this.sessionHigh = Math.max(sessionHigh, currentPrice);
        this.sessionLow = Math.min(sessionLow, currentPrice);
        this.priceUpdatesProcessed++;

        // Update minutes into session
        this.minutesIntoSession = (int) ((timestamp - sessionStartTimeMs) / 60000);

        // Check if warm-up is complete
        checkWarmup();

        // Update phase using the data timestamp (important for replay!)
        updatePhase(timestamp);

        // Session is no longer "new" based on actual market phase, not replay time
        // If we're past OPENING_BELL phase, it's not a "new session" for indicator purposes
        if (currentPhase != SessionPhase.PRE_MARKET && currentPhase != SessionPhase.OPENING_BELL) {
            this.isNewSession = false;
        }
    }

    /**
     * Record a processed trade (for CVD/tracking) - call on each trade
     */
    public void recordProcessedTrade() {
        this.tradesProcessed++;
        checkWarmup();
    }

    /**
     * Check if warm-up period is complete
     * Warm-up ends when ANY of these conditions are met:
     * - 5 minutes have passed
     * - 100 trades have been processed
     * - 50 price updates have occurred
     */
    private void checkWarmup() {
        if (warmupComplete) return;

        warmupComplete = (minutesIntoSession >= WARMUP_MINUTES) ||
                         (tradesProcessed >= WARMUP_TRADES) ||
                         (priceUpdatesProcessed >= WARMUP_PRICE_TICKS);

        // Clear reset flags once warm-up completes - indicators have data now
        if (warmupComplete) {
            this.cvdReset = false;
            this.vwapReset = false;
        }
    }

    /**
     * Is the warm-up period complete?
     * Signals should be suppressed until this returns true
     */
    public boolean isWarmupComplete() {
        return warmupComplete;
    }

    /**
     * Get warm-up progress description
     */
    public String getWarmupStatus() {
        if (warmupComplete) {
            return "COMPLETE";
        }

        String timeStatus = minutesIntoSession + "/" + WARMUP_MINUTES + " min";
        String tradeStatus = tradesProcessed + "/" + WARMUP_TRADES + " trades";
        String priceStatus = priceUpdatesProcessed + "/" + WARMUP_PRICE_TICKS + " prices";

        return String.format("WARMUP (%s | %s | %s)", timeStatus, tradeStatus, priceStatus);
    }

    /**
     * Update session phase based on current time
     */
    private void updatePhase() {
        updatePhase(System.currentTimeMillis());
    }

    /**
     * Update session phase based on timestamp (for replay mode)
     * Uses actual time of day from the data timestamp to determine phase
     */
    private void updatePhase(long timestamp) {
        // Convert timestamp to LocalTime in Eastern Time (US market timezone)
        // Bookmap timestamps are typically in UTC, so we convert to ET
        java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
        java.time.ZonedDateTime dataTime = java.time.Instant.ofEpochMilli(timestamp).atZone(etZone);
        LocalTime timeOfDay = dataTime.toLocalTime();

        int hour = timeOfDay.getHour();
        int minute = timeOfDay.getMinute();
        int totalMinutes = hour * 60 + minute;

        // Map actual time of day to trading session phases (US Eastern Time)
        // PRE_MARKET: before 09:30 (570 min)
        // OPENING_BELL: 09:30 - 10:00 (570-600 min)
        // MORNING_SESSION: 10:00 - 12:00 (600-720 min)
        // LUNCH_HOUR: 12:00 - 14:00 (720-840 min)
        // AFTERNOON: 14:00 - 15:30 (840-930 min)
        // CLOSING_BELL: 15:30 - 16:00 (930-960 min)
        // AFTER_HOURS: after 16:00 (960 min)

        if (totalMinutes < 570) {           // Before 9:30
            currentPhase = SessionPhase.PRE_MARKET;
        } else if (totalMinutes < 600) {    // 9:30 - 10:00
            currentPhase = SessionPhase.OPENING_BELL;
        } else if (totalMinutes < 720) {    // 10:00 - 12:00
            currentPhase = SessionPhase.MORNING_SESSION;
        } else if (totalMinutes < 840) {    // 12:00 - 14:00
            currentPhase = SessionPhase.LUNCH_HOUR;
        } else if (totalMinutes < 930) {    // 14:00 - 15:30
            currentPhase = SessionPhase.AFTERNOON;
        } else if (totalMinutes < 960) {    // 15:30 - 16:00
            currentPhase = SessionPhase.CLOSING_BELL;
        } else {
            currentPhase = SessionPhase.AFTER_HOURS;
        }
    }

    /**
     * Record a trade outcome
     */
    public void recordTrade(boolean isWin, double pnl) {
        tradesThisSession++;
        if (isWin) {
            winsThisSession++;
        } else {
            lossesThisSession++;
        }
        sessionPnl += pnl;
    }

    /**
     * Record an entry attempt (before outcome is known)
     * Call this when AI decides to TAKE a signal
     */
    public void recordEntryAttempt() {
        tradesThisSession++;
    }

    /**
     * Mark first signal as processed (indicators now have data)
     */
    public void markFirstSignalProcessed() {
        this.firstSignalProcessed = true;
    }

    /**
     * Mark CVD as reset (after session start)
     */
    public void markCvdReset() {
        this.cvdReset = true;
    }

    /**
     * Mark VWAP as reset (after session start)
     */
    public void markVwapReset() {
        this.vwapReset = true;
    }

    /**
     * Get session win rate
     */
    public double getSessionWinRate() {
        if (tradesThisSession == 0) return 0.0;
        return (winsThisSession * 100.0) / tradesThisSession;
    }

    /**
     * Get session range in ticks
     */
    public double getSessionRangeTicks(double pips) {
        return (sessionHigh - sessionLow) / pips;
    }

    /**
     * Format session info for AI prompt
     */
    public String toAIString() {
        StringBuilder sb = new StringBuilder();

        sb.append("═══ SESSION CONTEXT ═══\n");
        sb.append(String.format("Session ID: %s | Date: %s\n", sessionId, sessionDate));
        sb.append(String.format("Market Phase: %s\n", currentPhase));

        // Warm-up status
        sb.append(String.format("Warm-up: %s\n", getWarmupStatus()));

        // Indicator reset status - only show "new session" warning for actual market open
        sb.append("Indicator Status:\n");
        if (currentPhase == SessionPhase.OPENING_BELL) {
            sb.append("  ⚠️ OPENING BELL - Indicators may be less reliable\n");
            sb.append(String.format("  CVD: %s\n", cvdReset ? "Just reset" : "Accumulating"));
            sb.append(String.format("  VWAP: %s\n", vwapReset ? "Just reset" : "Rolling"));
        } else {
            sb.append(String.format("  CVD: %s\n", cvdReset ? "Just reset" : "Accumulating"));
            sb.append(String.format("  VWAP: %s\n", vwapReset ? "Just reset" : "Rolling"));
        }

        // Session performance
        sb.append("Session Performance:\n");
        sb.append(String.format("  Trades: %d (W: %d, L: %d) | Win Rate: %.0f%%\n",
            tradesThisSession, winsThisSession, lossesThisSession, getSessionWinRate()));
        sb.append(String.format("  P&L: $%.2f\n", sessionPnl));

        // Session range
        sb.append(String.format("  Range: %.1f - %.1f (%.0f ticks)\n",
            sessionLow, sessionHigh, getSessionRangeTicks(1.0)));

        // Important context notes
        if (!warmupComplete) {
            sb.append("\n⚠️ WARM-UP PERIOD ACTIVE:\n");
            sb.append("- Indicators are still accumulating data\n");
            sb.append("- Signals should be suppressed until warm-up completes\n");
            sb.append("- Wait for: 5 min OR 100 trades OR 50 price updates\n");
        }

        if (currentPhase == SessionPhase.OPENING_BELL || currentPhase == SessionPhase.CLOSING_BELL) {
            sb.append(String.format("\n⚠️ %s PHASE:\n", currentPhase));
            sb.append("- Expect higher volatility\n");
            sb.append("- Wider stops may be needed\n");
            sb.append("- Stronger confluence required\n");
        }

        if (currentPhase == SessionPhase.LUNCH_HOUR) {
            sb.append("\n⚠️ LUNCH HOUR:\n");
            sb.append("- Lower volume expected\n");
            sb.append("- May see choppy/range-bound conditions\n");
            sb.append("- Consider smaller position sizes\n");
        }

        sb.append("═══════════════════════\n");

        return sb.toString();
    }

    /**
     * Brief summary for logging
     */
    public String toSummary() {
        String warmupStr = warmupComplete ? "" : " | " + getWarmupStatus();
        return String.format("Session %s | %s | %d min | Trades: %d | P&L: $%.2f%s",
            sessionId, currentPhase, minutesIntoSession, tradesThisSession, sessionPnl, warmupStr);
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public LocalDate getSessionDate() { return sessionDate; }
    public long getSessionStartTimeMs() { return sessionStartTimeMs; }
    public boolean isNewSession() { return isNewSession; }
    public int getMinutesIntoSession() { return minutesIntoSession; }
    public int getTradesThisSession() { return tradesThisSession; }
    public int getWinsThisSession() { return winsThisSession; }
    public int getLossesThisSession() { return lossesThisSession; }
    public double getSessionPnl() { return sessionPnl; }
    public SessionPhase getCurrentPhase() { return currentPhase; }
    public boolean isCvdReset() { return cvdReset; }
    public boolean isVwapReset() { return vwapReset; }
    public boolean isFirstSignalProcessed() { return firstSignalProcessed; }
    public String getInstrument() { return instrument; }
    public double getSessionOpenPrice() { return sessionOpenPrice; }
    public double getSessionHigh() { return sessionHigh; }
    public double getSessionLow() { return sessionLow; }
    public double getCurrentPrice() { return currentPrice; }
    public int getTradesProcessed() { return tradesProcessed; }
    public int getPriceUpdatesProcessed() { return priceUpdatesProcessed; }

    // ========== SNAPSHOT METHODS ==========

    /**
     * Add a market snapshot to the session history
     */
    public void addSnapshot(MarketSnapshot snapshot) {
        snapshots.add(snapshot);
        // Keep only the most recent snapshots
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.remove(0);
        }
    }

    /**
     * Get all snapshots (read-only)
     */
    public List<MarketSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Get recent snapshots (last N)
     */
    public List<MarketSnapshot> getRecentSnapshots(int count) {
        int start = Math.max(0, snapshots.size() - count);
        return new ArrayList<>(snapshots.subList(start, snapshots.size()));
    }

    /**
     * Get snapshot count
     */
    public int getSnapshotCount() {
        return snapshots.size();
    }

    /**
     * Format snapshot history for AI context
     * Shows a compact timeline of market conditions
     */
    public String getSnapshotsAIString() {
        if (snapshots.isEmpty()) {
            return "No snapshots recorded yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("═══ MARKET SNAPSHOT HISTORY (%d snapshots) ═══\n", snapshots.size()));

        // Show last 10 snapshots in detail
        int detailCount = Math.min(10, snapshots.size());
        List<MarketSnapshot> recent = getRecentSnapshots(detailCount);

        sb.append("Recent snapshots (last ").append(detailCount).append("):\n");
        for (MarketSnapshot snap : recent) {
            sb.append("  ").append(snap.toAIString()).append("\n");
        }

        // Show summary stats
        if (snapshots.size() > 1) {
            MarketSnapshot first = snapshots.get(0);
            MarketSnapshot last = snapshots.get(snapshots.size() - 1);

            double priceChange = last.price - first.price;
            double cvdChange = last.cvd - first.cvd;
            String priceTrend = priceChange > 0 ? "↑" : (priceChange < 0 ? "↓" : "→");
            String cvdTrend = cvdChange > 0 ? "↑" : (cvdChange < 0 ? "↓" : "→");

            sb.append(String.format("\nSession Trend: Price %s %.2f | CVD %s %+.0f\n",
                priceTrend, priceChange, cvdTrend, cvdChange));
        }

        sb.append("═══════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Get compact snapshot timeline for brief context
     */
    public String getSnapshotsBrief() {
        if (snapshots.isEmpty()) {
            return "No snapshots yet";
        }

        StringBuilder sb = new StringBuilder();
        int count = Math.min(5, snapshots.size());
        List<MarketSnapshot> recent = getRecentSnapshots(count);

        for (MarketSnapshot snap : recent) {
            sb.append(snap.toBriefString()).append("\n");
        }
        return sb.toString();
    }
}
