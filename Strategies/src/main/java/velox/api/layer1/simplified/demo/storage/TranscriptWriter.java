package velox.api.layer1.simplified.demo.storage;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Session Transcript Writer
 * Logs all trading events to JSONL format for learning
 */
public class TranscriptWriter {
    private final Path sessionsDir;
    private String currentSessionId;
    private PrintWriter transcriptWriter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public TranscriptWriter(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        if (!sessionsDir.toFile().exists()) {
            sessionsDir.toFile().mkdirs();
        }
    }

    /**
     * Initialize a new trading session
     */
    public void initializeSession() {
        currentSessionId = generateSessionId();

        try {
            Path transcriptPath = sessionsDir.resolve(currentSessionId + ".jsonl");
            transcriptWriter = new PrintWriter(new FileWriter(transcriptPath.toFile(), true));

            log("📝 Session transcript: " + transcriptPath);

            // Write session start event
            writeEvent("SESSION_START", Map.of(
                "sessionId", currentSessionId,
                "timestamp", dateFormat.format(new Date())
            ));

        } catch (IOException e) {
            System.err.println("Failed to create transcript: " + e.getMessage());
        }
    }

    /**
     * Log a setup detection event
     */
    public void logSetup(String direction, int price, int score, String confluence) {
        if (transcriptWriter == null) return;

        writeEvent("SETUP_DETECTED", Map.of(
            "direction", direction,
            "price", String.valueOf(price),
            "score", String.valueOf(score),
            "confluence", confluence,
            "timestamp", dateFormat.format(new Date())
        ));
    }

    /**
     * Log order placement event
     */
    public void logOrderPlaced(String orderId, String orderType, int entryPrice, int stopLoss, int takeProfit, String reasoning) {
        if (transcriptWriter == null) return;

        writeEvent("ORDER_PLACED", Map.of(
            "orderId", orderId,
            "orderType", orderType,
            "entryPrice", String.valueOf(entryPrice),
            "stopLoss", String.valueOf(stopLoss),
            "takeProfit", String.valueOf(takeProfit),
            "reasoning", reasoning,
            "timestamp", dateFormat.format(new Date())
        ));
    }

    /**
     * Log trade outcome event
     */
    public void logOutcome(String orderId, boolean won, double pnl, String lesson) {
        if (transcriptWriter == null) return;

        writeEvent("TRADE_OUTCOME", Map.of(
            "orderId", orderId,
            "won", String.valueOf(won),
            "pnl", String.valueOf(pnl),
            "lesson", lesson,
            "timestamp", dateFormat.format(new Date())
        ));
    }

    /**
     * Write event to transcript file
     * Format: JSONL (one JSON object per line)
     */
    private synchronized void writeEvent(String eventType, Map<String, String> data) {
        if (transcriptWriter == null) return;

        try {
            // Build JSON object
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"event\":\"").append(eventType).append("\"");

            for (Map.Entry<String, String> entry : data.entrySet()) {
                json.append(",\"").append(entry.getKey()).append("\":\"");
                json.append(escapeJson(entry.getValue())).append("\"");
            }

            json.append("}");

            // Write to file
            transcriptWriter.println(json.toString());
            transcriptWriter.flush();

        } catch (Exception e) {
            System.err.println("Failed to write event: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String generateSessionId() {
        return "session_" + System.currentTimeMillis();
    }

    private void log(String message) {
        System.out.println(message);
    }

    /**
     * Close current session transcript
     */
    public void closeSession() {
        if (transcriptWriter != null) {
            writeEvent("SESSION_END", Map.of(
                "sessionId", currentSessionId,
                "timestamp", dateFormat.format(new Date())
            ));

            transcriptWriter.close();
            transcriptWriter = null;
        }
    }
}
