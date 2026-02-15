package velox.api.layer1.simplified.demo.storage;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Trade Logger
 * Logs all closed trades to CSV format for historical analysis
 *
 * Location: trading-memory/trade-history.csv
 */
public class TradeLogger {
    private final Path logPath;
    private PrintWriter writer;
    private final Object lock = new Object();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Tick value for P&L calculation (default ES)
    private double tickValue = 12.50;

    public TradeLogger(Path tradingMemoryDir) {
        this.logPath = tradingMemoryDir.resolve("trade-history.csv");
        initialize();
    }

    /**
     * Set tick value for P&L calculations
     */
    public void setTickValue(double tickValue) {
        this.tickValue = tickValue;
    }

    private void initialize() {
        try {
            boolean exists = logPath.toFile().exists();
            boolean shouldWriteHeader = !exists || logPath.toFile().length() == 0;

            // Ensure parent directory exists
            logPath.getParent().toFile().mkdirs();

            writer = new PrintWriter(new FileWriter(logPath.toFile(), true));

            if (shouldWriteHeader) {
                writer.println(String.join(",",
                    "timestamp",
                    "trade_id",
                    "symbol",
                    "direction",
                    "entry_price",
                    "exit_price",
                    "quantity",
                    "stop_loss",
                    "take_profit",
                    "pnl_ticks",
                    "pnl_dollars",
                    "outcome",
                    "duration_seconds",
                    "signal_score",
                    "entry_slippage",
                    "exit_reason",
                    "sl_ticks",
                    "tp_ticks",
                    "rr_ratio",
                    "mfe_ticks",
                    "mae_ticks",
                    "ai_confidence"
                ));
            }
            writer.flush();
            log("📝 Trade logger initialized: " + logPath);
        } catch (IOException e) {
            System.err.println("Failed to initialize trade logger: " + e.getMessage());
        }
    }

    /**
     * Log a closed trade
     */
    public void logTrade(TradeRecord record) {
        if (writer == null) {
            System.err.println("TradeLogger not initialized");
            return;
        }

        synchronized (lock) {
            try {
                String line = String.join(",",
                    escape(record.timestamp),
                    escape(record.tradeId),
                    escape(record.symbol),
                    escape(record.direction),
                    String.valueOf(record.entryPrice),
                    String.valueOf(record.exitPrice),
                    String.valueOf(record.quantity),
                    String.valueOf(record.stopLoss),
                    String.valueOf(record.takeProfit),
                    String.valueOf(record.pnlTicks),
                    String.format("%.2f", record.pnlDollars),
                    escape(record.outcome),
                    String.valueOf(record.durationSeconds),
                    String.valueOf(record.signalScore),
                    String.valueOf(record.entrySlippage),
                    escape(record.exitReason),
                    String.valueOf(record.slTicks),
                    String.valueOf(record.tpTicks),
                    String.format("%.2f", record.rrRatio),
                    String.valueOf(record.mfeTicks),
                    String.valueOf(record.maeTicks),
                    String.format("%.2f", record.aiConfidence)
                );

                writer.println(line);
                writer.flush();

                log("📊 Trade logged: " + record.tradeId.substring(0, Math.min(8, record.tradeId.length())) +
                    " | " + record.direction + " | P&L: " + String.format("$%.2f", record.pnlDollars));

            } catch (Exception e) {
                System.err.println("Failed to log trade: " + e.getMessage());
            }
        }
    }

    /**
     * Create a trade record from ActivePosition
     */
    public TradeRecord createRecord(
            String tradeId,
            String symbol,
            boolean isLong,
            int entryPrice,
            int exitPrice,
            int quantity,
            int stopLoss,
            int takeProfit,
            int durationSeconds,
            int signalScore,
            int entrySlippage,
            String exitReason,
            int maxFavorableExcursion,
            int maxAdverseExcursion,
            double aiConfidence) {

        TradeRecord record = new TradeRecord();
        record.timestamp = Instant.now().toString();
        record.tradeId = tradeId;
        record.symbol = symbol;
        record.direction = isLong ? "LONG" : "SHORT";
        record.entryPrice = entryPrice;
        record.exitPrice = exitPrice;
        record.quantity = quantity;
        record.stopLoss = stopLoss;
        record.takeProfit = takeProfit;

        // Calculate P&L in ticks
        record.pnlTicks = isLong ?
            (exitPrice - entryPrice) :
            (entryPrice - exitPrice);

        // Calculate P&L in dollars
        record.pnlDollars = record.pnlTicks * tickValue * quantity;

        // Determine outcome
        record.outcome = record.pnlDollars >= 0 ? "WIN" : "LOSS";

        record.durationSeconds = durationSeconds;
        record.signalScore = signalScore;
        record.entrySlippage = entrySlippage;
        record.exitReason = exitReason;

        // Risk metrics
        record.slTicks = Math.abs(stopLoss - entryPrice);
        record.tpTicks = Math.abs(takeProfit - entryPrice);
        record.rrRatio = record.slTicks > 0 ? (double) record.tpTicks / record.slTicks : 0;

        // MFE/MAE in ticks
        record.mfeTicks = maxFavorableExcursion;
        record.maeTicks = maxAdverseExcursion;

        record.aiConfidence = aiConfidence;

        return record;
    }

    private String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Close the logger
     */
    public void close() {
        synchronized (lock) {
            if (writer != null) {
                writer.close();
                writer = null;
                log("📝 Trade logger closed");
            }
        }
    }

    private void log(String message) {
        System.out.println(message);
    }

    /**
     * Trade record data class
     */
    public static class TradeRecord {
        public String timestamp;
        public String tradeId;
        public String symbol;
        public String direction;
        public int entryPrice;
        public int exitPrice;
        public int quantity;
        public int stopLoss;
        public int takeProfit;
        public int pnlTicks;
        public double pnlDollars;
        public String outcome;
        public int durationSeconds;
        public int signalScore;
        public int entrySlippage;
        public String exitReason;
        public int slTicks;
        public int tpTicks;
        public double rrRatio;
        public int mfeTicks;
        public int maeTicks;
        public double aiConfidence;
    }
}
