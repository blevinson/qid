package velox.api.layer1.simplified.demo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Session State Manager
 * Persists and restores daily trading statistics across restarts
 *
 * Location: trading-memory/session-state.json
 */
public class SessionStateManager {
    private final Path statePath;
    private final Gson gson;
    private SessionState currentState;

    public SessionStateManager(Path tradingMemoryDir) {
        this.statePath = tradingMemoryDir.resolve("session-state.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadSessionState();
    }

    /**
     * Load session state from file
     * If date doesn't match today, reset to fresh state
     */
    public SessionState loadSessionState() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        if (statePath.toFile().exists()) {
            try (Reader reader = new FileReader(statePath.toFile())) {
                currentState = gson.fromJson(reader, SessionState.class);

                // Check if this is today's state
                if (currentState != null && today.equals(currentState.date)) {
                    log("📊 Session state loaded: " + currentState);
                    return currentState;
                } else {
                    log("📅 New trading day - resetting session state");
                }
            } catch (Exception e) {
                log("⚠️ Failed to load session state: " + e.getMessage());
            }
        }

        // Create fresh state for today
        currentState = new SessionState();
        currentState.date = today;
        currentState.dailyPnl = 0.0;
        currentState.totalTrades = 0;
        currentState.winningTrades = 0;
        currentState.losingTrades = 0;
        currentState.maxDrawdown = 0.0;
        currentState.peakPnl = 0.0;

        log("📊 New session state created for " + today);
        return currentState;
    }

    /**
     * Save current session state to file
     */
    public void saveSessionState() {
        if (currentState == null) return;

        try {
            currentState.lastUpdated = java.time.Instant.now().toString();

            // Ensure directory exists
            statePath.getParent().toFile().mkdirs();

            try (Writer writer = new FileWriter(statePath.toFile())) {
                gson.toJson(currentState, writer);
            }

            log("💾 Session state saved: P&L=$" + String.format("%.2f", currentState.dailyPnl) +
                ", Trades=" + currentState.totalTrades +
                ", WinRate=" + String.format("%.1f%%", currentState.getWinRate()));

        } catch (Exception e) {
            log("⚠️ Failed to save session state: " + e.getMessage());
        }
    }

    /**
     * Update session state after a trade
     */
    public void updateAfterTrade(double pnlDollars, boolean isWin) {
        if (currentState == null) {
            loadSessionState();
        }

        currentState.totalTrades++;
        currentState.dailyPnl += pnlDollars;

        if (isWin) {
            currentState.winningTrades++;
        } else {
            currentState.losingTrades++;
        }

        // Track peak and drawdown
        if (currentState.dailyPnl > currentState.peakPnl) {
            currentState.peakPnl = currentState.dailyPnl;
        }

        double drawdown = currentState.peakPnl - currentState.dailyPnl;
        if (drawdown > currentState.maxDrawdown) {
            currentState.maxDrawdown = drawdown;
        }

        // Save after each trade
        saveSessionState();
    }

    /**
     * Get current session state
     */
    public SessionState getCurrentState() {
        if (currentState == null) {
            loadSessionState();
        }
        return currentState;
    }

    /**
     * Reset session state (manual reset)
     */
    public void resetSession() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        currentState = new SessionState();
        currentState.date = today;
        currentState.dailyPnl = 0.0;
        currentState.totalTrades = 0;
        currentState.winningTrades = 0;
        currentState.losingTrades = 0;
        currentState.maxDrawdown = 0.0;
        currentState.peakPnl = 0.0;
        saveSessionState();
        log("🔄 Session state reset");
    }

    /**
     * Check if daily loss limit reached
     */
    public boolean isDailyLossLimitReached(double maxDailyLoss) {
        if (currentState == null) return false;
        return currentState.dailyPnl <= -maxDailyLoss;
    }

    /**
     * Get daily P&L
     */
    public double getDailyPnl() {
        return currentState != null ? currentState.dailyPnl : 0.0;
    }

    /**
     * Get total trades
     */
    public int getTotalTrades() {
        return currentState != null ? currentState.totalTrades : 0;
    }

    /**
     * Get winning trades
     */
    public int getWinningTrades() {
        return currentState != null ? currentState.winningTrades : 0;
    }

    /**
     * Get losing trades
     */
    public int getLosingTrades() {
        return currentState != null ? currentState.losingTrades : 0;
    }

    /**
     * Get win rate
     */
    public double getWinRate() {
        return currentState != null ? currentState.getWinRate() : 0.0;
    }

    private void log(String message) {
        System.out.println(message);
    }

    /**
     * Session state data class
     */
    public static class SessionState {
        public String date;
        public double dailyPnl;
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double maxDrawdown;
        public double peakPnl;
        public String lastUpdated;

        public double getWinRate() {
            if (totalTrades == 0) return 0.0;
            return (winningTrades * 100.0) / totalTrades;
        }

        @Override
        public String toString() {
            return String.format("SessionState[date=%s, pnl=$%.2f, trades=%d, wins=%d, losses=%d, winRate=%.1f%%]",
                date, dailyPnl, totalTrades, winningTrades, losingTrades, getWinRate());
        }
    }
}
