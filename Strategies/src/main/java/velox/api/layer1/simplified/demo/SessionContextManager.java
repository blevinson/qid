package velox.api.layer1.simplified.demo;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Session Context Manager
 *
 * Manages a single rolling session context file per trading day.
 * Prevents memory bloat by keeping one entry per day that gets updated
 * as phases transition throughout the session.
 *
 * Key features:
 * - One file per day (prevents old analyses from cluttering searches)
 * - Append-only updates (each phase adds to the same file)
 * - Easy retrieval for AI (getTodaysContext returns ONE concise entry)
 * - Automatic cleanup of old context files (optional)
 */
public class SessionContextManager {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File memoryDir;
    private final String instrument;
    private Consumer<String> logger;

    // Current date for tracking day changes
    private String currentDate;

    /**
     * Create a new SessionContextManager
     *
     * @param memoryDir Base memory directory
     * @param instrument Trading instrument symbol
     */
    public SessionContextManager(File memoryDir, String instrument) {
        this.memoryDir = memoryDir;
        this.instrument = instrument;
        this.currentDate = LocalDate.now(ET_ZONE).format(DATE_FMT);
    }

    /**
     * Set logger for debug output
     */
    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Get today's session context file
     */
    private File getTodaysContextFile() {
        File sessionDir = new File(memoryDir, "session-context");
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        String dateStr = LocalDate.now(ET_ZONE).format(DATE_FMT);
        String filename = instrument + "-" + dateStr + ".md";
        return new File(sessionDir, filename);
    }

    /**
     * Check if today's context exists
     */
    public boolean hasTodaysContext() {
        return getTodaysContextFile().exists();
    }

    /**
     * Get today's full session context
     * Returns null if no context exists for today
     */
    public String getTodaysContext() {
        File file = getTodaysContextFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            log("Error reading today's context: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get a summary of today's context (for AI decision-making)
     * Returns a condensed version with key points
     */
    public String getTodaysContextSummary() {
        String fullContext = getTodaysContext();
        if (fullContext == null) {
            return "No session context available for today.";
        }

        // Extract key sections for summary
        StringBuilder summary = new StringBuilder();
        summary.append("=== TODAY'S SESSION CONTEXT ===\n\n");

        // Find and extract key sections
        String[] sections = fullContext.split("## ");
        for (String section : sections) {
            if (section.startsWith("PRE-MARKET ANALYSIS") ||
                section.startsWith("TRADE PLAN") ||
                section.startsWith("KEY LEVELS") ||
                section.startsWith("CURRENT BIAS")) {
                // Include important sections
                summary.append("## ").append(section.substring(0, Math.min(section.length(), 500)));
                if (section.length() > 500) {
                    summary.append("...\n\n");
                }
            }
        }

        // Find most recent phase update
        int lastPhaseIdx = fullContext.lastIndexOf("### Phase Update:");
        if (lastPhaseIdx > 0) {
            String lastPhase = fullContext.substring(lastPhaseIdx);
            // Take first few lines
            int newlineCount = 0;
            int endIdx = 0;
            for (int i = 0; i < lastPhase.length() && newlineCount < 10; i++) {
                if (lastPhase.charAt(i) == '\n') newlineCount++;
                endIdx = i;
            }
            summary.append("\n### MOST RECENT PHASE:\n");
            summary.append(lastPhase.substring(0, Math.min(endIdx, 300))).append("\n");
        }

        return summary.toString();
    }

    /**
     * Initialize today's session context with pre-market analysis
     * Creates the file if it doesn't exist
     */
    public void initializeTodaysContext(String preMarketAnalysis,
                                         double price, double vwap, long cvd,
                                         double ema9, double ema21, double ema50) {
        File file = getTodaysContextFile();

        // Don't overwrite if already exists
        if (file.exists()) {
            log("Today's context already exists, appending pre-market analysis");
            appendSection("PRE-MARKET ANALYSIS (RE-ANALYSIS)", preMarketAnalysis);
            return;
        }

        try {
            String dateStr = LocalDate.now(ET_ZONE).format(DATE_FMT);
            String timeStr = LocalDateTime.now(ET_ZONE).format(DATETIME_FMT);

            StringBuilder content = new StringBuilder();
            content.append("# Trading Session Context\n\n");
            content.append("**Date:** ").append(dateStr).append("\n");
            content.append("**Instrument:** ").append(instrument).append("\n");
            content.append("**Created:** ").append(timeStr).append(" ET\n\n");

            content.append("---\n\n");
            content.append("## MARKET SNAPSHOT AT CREATION\n\n");
            content.append("| Metric | Value |\n");
            content.append("|--------|-------|\n");
            content.append(String.format("| Price | %.2f |\n", price));
            content.append(String.format("| VWAP | %.2f |\n", vwap));
            content.append(String.format("| CVD | %d |\n", cvd));
            content.append(String.format("| EMA9 | %.2f |\n", ema9));
            content.append(String.format("| EMA21 | %.2f |\n", ema21));
            content.append(String.format("| EMA50 | %.2f |\n", ema50));
            content.append("\n---\n\n");

            content.append("## PRE-MARKET ANALYSIS\n\n");
            content.append("**Time:** ").append(LocalDateTime.now(ET_ZONE).format(TIME_FMT)).append(" ET\n\n");
            content.append(preMarketAnalysis);
            content.append("\n\n---\n\n");

            content.append("## PHASE UPDATES\n\n");
            content.append("*(Phase updates will be appended here throughout the session)*\n\n");

            Files.writeString(file.toPath(), content.toString());
            log("Created session context: " + file.getAbsolutePath());

        } catch (IOException e) {
            log("Error creating session context: " + e.getMessage());
        }
    }

    /**
     * Append a phase update to today's context
     */
    public void appendPhaseUpdate(SessionPhaseTracker.SessionPhase phase,
                                   String update,
                                   double price, double vwap, long cvd) {
        String phaseTitle = phase.getDisplayName().toUpperCase().replace(" ", "_");
        appendSection("Phase Update: " + phaseTitle + " (" +
            LocalDateTime.now(ET_ZONE).format(TIME_FMT) + " ET)",
            String.format("Price: %.2f | VWAP: %.2f | CVD: %d\n\n%s",
                price, vwap, cvd, update));
    }

    /**
     * Append a trade outcome to today's context
     */
    public void appendTradeOutcome(String tradeId, boolean isLong, int entryPrice,
                                    int exitPrice, double pnl, String reason) {
        String direction = isLong ? "LONG" : "SHORT";
        String outcome = pnl >= 0 ? "WIN" : "LOSS";
        String emoji = pnl >= 0 ? "✅" : "❌";

        String content = String.format(
            "%s %s %s @ %d → %d | P&L: $%.2f | Reason: %s",
            emoji, direction, outcome, entryPrice, exitPrice, pnl, reason
        );

        appendSection("Trade: " + tradeId.substring(0, Math.min(8, tradeId.length())), content);
    }

    /**
     * Append a general section to today's context
     */
    public void appendSection(String title, String content) {
        File file = getTodaysContextFile();

        try {
            // Create file if it doesn't exist
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), "# Trading Session Context\n\n");
            }

            // Append section
            StringBuilder section = new StringBuilder();
            section.append("\n### ").append(title).append("\n\n");
            section.append("**Time:** ").append(LocalDateTime.now(ET_ZONE).format(DATETIME_FMT)).append(" ET\n\n");
            section.append(content).append("\n\n");

            Files.writeString(file.toPath(), section.toString(), StandardOpenOption.APPEND);
            log("Appended section to session context: " + title);

        } catch (IOException e) {
            log("Error appending to session context: " + e.getMessage());
        }
    }

    /**
     * Update the current bias in the context
     */
    public void updateBias(String newBias, String reasoning) {
        appendSection("BIAS UPDATE",
            String.format("**New Bias:** %s\n\n**Reasoning:** %s", newBias, reasoning));
    }

    /**
     * Clean up old session context files (older than specified days)
     */
    public void cleanupOldContexts(int daysToKeep) {
        File sessionDir = new File(memoryDir, "session-context");
        if (!sessionDir.exists()) return;

        LocalDate cutoff = LocalDate.now(ET_ZONE).minusDays(daysToKeep);

        File[] files = sessionDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) return;

        int deleted = 0;
        for (File file : files) {
            try {
                // Extract date from filename (format: INSTRUMENT-YYYY-MM-DD.md)
                String filename = file.getName();
                int dateStart = filename.lastIndexOf('-');
                if (dateStart > 0) {
                    String dateStr = filename.substring(dateStart + 1, dateStart + 11);
                    LocalDate fileDate = LocalDate.parse(dateStr);
                    if (fileDate.isBefore(cutoff)) {
                        if (file.delete()) {
                            deleted++;
                        }
                    }
                }
            } catch (Exception e) {
                // Skip files with malformed names
            }
        }

        if (deleted > 0) {
            log("Cleaned up " + deleted + " old session context files");
        }
    }

    /**
     * Get context file path for AI retrieval
     */
    public String getContextFilePath() {
        return getTodaysContextFile().getAbsolutePath();
    }

    /**
     * Check if a new trading day has started (for resetting context)
     */
    public boolean isNewTradingDay() {
        String today = LocalDate.now(ET_ZONE).format(DATE_FMT);
        if (!today.equals(currentDate)) {
            currentDate = today;
            return true;
        }
        return false;
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept("[SessionContextManager] " + message);
        }
    }
}
