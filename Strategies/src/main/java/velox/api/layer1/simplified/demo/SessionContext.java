package velox.api.layer1.simplified.demo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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

    // Session identification
    private String sessionId;
    private LocalDate sessionDate;
    private long sessionStartTimeMs;

    // Session state
    private boolean isNewSession;
    private int minutesIntoSession;

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
        this.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.sessionDate = LocalDate.now();
        this.sessionStartTimeMs = System.currentTimeMillis();
        this.isNewSession = true;
        this.minutesIntoSession = 0;
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
        updatePhase();
    }

    /**
     * Update session state - call periodically (e.g., on each price update)
     */
    public void update(double currentPrice) {
        this.currentPrice = currentPrice;
        this.sessionHigh = Math.max(sessionHigh, currentPrice);
        this.sessionLow = Math.min(sessionLow, currentPrice);

        // Update minutes into session
        this.minutesIntoSession = (int) ((System.currentTimeMillis() - sessionStartTimeMs) / 60000);

        // Session is no longer "new" after 30 minutes
        if (minutesIntoSession > 30) {
            this.isNewSession = false;
        }

        // Update phase
        updatePhase();
    }

    /**
     * Update session phase based on current time
     */
    private void updatePhase() {
        LocalTime now = LocalTime.now();

        if (now.isBefore(LocalTime.of(9, 30))) {
            currentPhase = SessionPhase.PRE_MARKET;
        } else if (now.isBefore(LocalTime.of(10, 0))) {
            currentPhase = SessionPhase.OPENING_BELL;
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            currentPhase = SessionPhase.MORNING_SESSION;
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            currentPhase = SessionPhase.LUNCH_HOUR;
        } else if (now.isBefore(LocalTime.of(15, 30))) {
            currentPhase = SessionPhase.AFTERNOON;
        } else if (now.isBefore(LocalTime.of(16, 0))) {
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
        sb.append(String.format("Session Started: %s (%d minutes ago)\n",
            LocalTime.now().minusMinutes(minutesIntoSession).format(TIME_FMT),
            minutesIntoSession));

        // Session phase
        sb.append(String.format("Phase: %s", currentPhase));
        if (isNewSession) {
            sb.append(" ⚠️ NEW SESSION");
        }
        sb.append("\n");

        // Indicator reset status
        sb.append("Indicator Status:\n");
        sb.append(String.format("  CVD: %s\n", cvdReset ? "Just reset (session start)" : "Accumulating"));
        sb.append(String.format("  VWAP: %s\n", vwapReset ? "Just reset (session start)" : "Rolling"));

        // Session performance
        sb.append("Session Performance:\n");
        sb.append(String.format("  Trades: %d (W: %d, L: %d) | Win Rate: %.0f%%\n",
            tradesThisSession, winsThisSession, lossesThisSession, getSessionWinRate()));
        sb.append(String.format("  P&L: $%.2f\n", sessionPnl));

        // Session range
        sb.append(String.format("  Range: %.1f - %.1f (%.0f ticks)\n",
            sessionLow, sessionHigh, getSessionRangeTicks(1.0)));

        // Important context notes
        if (isNewSession) {
            sb.append("\n⚠️ NEW SESSION NOTES:\n");
            sb.append("- CVD and VWAP have just started accumulating\n");
            sb.append("- No historical context from today yet\n");
            sb.append("- Be cautious with first signals of the day\n");
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
        return String.format("Session %s | %s | %d min | Trades: %d | P&L: $%.2f",
            sessionId, currentPhase, minutesIntoSession, tradesThisSession, sessionPnl);
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
}
