package velox.api.layer1.simplified.demo;

import java.util.*;
import java.util.function.Supplier;

/**
 * AI Tools Provider
 * Provides tools that the AI can call to get real-time trading data
 *
 * This implements function calling so AI can:
 * - Get current market data
 * - Get order book levels
 * - Get recent signals
 * - Adjust thresholds (with confirmation)
 */
public class AIToolsProvider {

    // Suppliers for real-time data (set by the strategy)
    private Supplier<Double> priceSupplier;
    private Supplier<Long> cvdSupplier;
    private Supplier<Double> vwapSupplier;
    private Supplier<double[]> emaSupplier;
    private Supplier<Map<String, Object>> domSupplier;
    private Supplier<List<Map<String, Object>>> signalsSupplier;
    private Supplier<Map<String, Object>> sessionSupplier;
    private Supplier<Map<String, Integer>> thresholdsSupplier;

    // Tool definitions for Claude API
    public static final String[] TOOL_DEFINITIONS = {
        """
        {
            "name": "get_current_price",
            "description": "Get the current market price. Use this to know what price the instrument is trading at.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_cvd",
            "description": "Get the Cumulative Volume Delta (CVD) which shows buying vs selling pressure. Positive = more buying, Negative = more selling.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_vwap",
            "description": "Get the Volume Weighted Average Price. Price above VWAP is bullish, below is bearish.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_emas",
            "description": "Get the EMA (Exponential Moving Average) values for trend analysis. Returns EMA9, EMA21, EMA50.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_order_book",
            "description": "Get the current order book (DOM) showing support and resistance levels from limit orders.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_recent_signals",
            "description": "Get the most recent trading signals that were detected. Shows direction, price, score, and outcome.",
            "input_schema": {
                "type": "object",
                "properties": {
                    "count": {
                        "type": "integer",
                        "description": "Number of recent signals to return (default 5, max 10)",
                        "default": 5
                    }
                },
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_session_stats",
            "description": "Get the current trading session statistics including win rate, P&L, and phase.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_thresholds",
            "description": "Get the current signal detection thresholds like minConfluenceScore, icebergMinOrders, etc.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "get_full_snapshot",
            "description": "Get a complete snapshot of all market data, session stats, and thresholds. Use this for a comprehensive overview.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """
    };

    // ========== SETTERS FOR DATA SUPPLIERS ==========

    public void setPriceSupplier(Supplier<Double> supplier) { this.priceSupplier = supplier; }
    public void setCvdSupplier(Supplier<Long> supplier) { this.cvdSupplier = supplier; }
    public void setVwapSupplier(Supplier<Double> supplier) { this.vwapSupplier = supplier; }
    public void setEmaSupplier(Supplier<double[]> supplier) { this.emaSupplier = supplier; }
    public void setDomSupplier(Supplier<Map<String, Object>> supplier) { this.domSupplier = supplier; }
    public void setSignalsSupplier(Supplier<List<Map<String, Object>>> supplier) { this.signalsSupplier = supplier; }
    public void setSessionSupplier(Supplier<Map<String, Object>> supplier) { this.sessionSupplier = supplier; }
    public void setThresholdsSupplier(Supplier<Map<String, Integer>> supplier) { this.thresholdsSupplier = supplier; }

    // ========== TOOL EXECUTION ==========

    /**
     * Execute a tool call and return the result
     * @param toolName Name of the tool to execute
     * @param arguments JSON arguments (as Map)
     * @return Tool result as String
     */
    public String executeTool(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case "get_current_price" -> getCurrentPrice();
                case "get_cvd" -> getCVD();
                case "get_vwap" -> getVWAP();
                case "get_emas" -> getEMAs();
                case "get_order_book" -> getOrderBook();
                case "get_recent_signals" -> getRecentSignals(arguments);
                case "get_session_stats" -> getSessionStats();
                case "get_thresholds" -> getThresholds();
                case "get_full_snapshot" -> getFullSnapshot();
                default -> "{\"error\": \"Unknown tool: " + toolName + "\"}";
            };
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // ========== TOOL IMPLEMENTATIONS ==========

    private String getCurrentPrice() {
        if (priceSupplier == null) return "{\"price\": 0, \"error\": \"Price data not available\"}";
        double price = priceSupplier.get();
        return String.format("{\"price\": %.2f}", price);
    }

    private String getCVD() {
        if (cvdSupplier == null) return "{\"cvd\": 0, \"trend\": \"UNKNOWN\", \"error\": \"CVD data not available\"}";
        long cvd = cvdSupplier.get();
        String trend = cvd > 0 ? "BULLISH" : cvd < 0 ? "BEARISH" : "NEUTRAL";
        return String.format("{\"cvd\": %d, \"trend\": \"%s\"}", cvd, trend);
    }

    private String getVWAP() {
        if (vwapSupplier == null) return "{\"vwap\": 0, \"error\": \"VWAP data not available\"}";
        double vwap = vwapSupplier.get();
        double price = priceSupplier != null ? priceSupplier.get() : 0;
        String position = price > vwap ? "ABOVE" : price < vwap ? "BELOW" : "AT";
        return String.format("{\"vwap\": %.2f, \"price_position\": \"%s\"}", vwap, position);
    }

    private String getEMAs() {
        if (emaSupplier == null) return "{\"emas\": {}, \"error\": \"EMA data not available\"}";
        double[] emas = emaSupplier.get();
        double price = priceSupplier != null ? priceSupplier.get() : 0;
        return String.format("{\"ema9\": %.2f, \"ema21\": %.2f, \"ema50\": %.2f, \"price\": %.2f}",
            emas[0], emas[1], emas[2], price);
    }

    private String getOrderBook() {
        if (domSupplier == null) return "{\"error\": \"Order book data not available\"}";
        Map<String, Object> dom = domSupplier.get();
        if (dom == null || dom.isEmpty()) {
            return "{\"error\": \"No order book data yet\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        if (dom.containsKey("supportPrice")) {
            sb.append(String.format("\"support\": {\"price\": %s, \"volume\": %s},",
                dom.get("supportPrice"), dom.get("supportVolume")));
        }
        if (dom.containsKey("resistancePrice")) {
            sb.append(String.format("\"resistance\": {\"price\": %s, \"volume\": %s},",
                dom.get("resistancePrice"), dom.get("resistanceVolume")));
        }
        if (dom.containsKey("imbalanceRatio")) {
            sb.append(String.format("\"imbalance\": {\"ratio\": %s, \"sentiment\": \"%s\"}",
                dom.get("imbalanceRatio"), dom.get("imbalanceSentiment")));
        }

        sb.append("}");
        return sb.toString();
    }

    private String getRecentSignals(Map<String, Object> arguments) {
        if (signalsSupplier == null) return "{\"signals\": [], \"error\": \"Signal data not available\"}";

        int count = 5;
        if (arguments != null && arguments.containsKey("count")) {
            count = Math.min(10, ((Number) arguments.get("count")).intValue());
        }

        List<Map<String, Object>> signals = signalsSupplier.get();
        if (signals == null || signals.isEmpty()) {
            return "{\"signals\": [], \"message\": \"No signals yet this session\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"signals\": [");

        int added = 0;
        for (int i = signals.size() - 1; i >= 0 && added < count; i--, added++) {
            Map<String, Object> sig = signals.get(i);
            if (added > 0) sb.append(",");
            sb.append(String.format(
                "{\"direction\": \"%s\", \"price\": %s, \"score\": %s, \"time\": \"%s\"}",
                sig.getOrDefault("direction", "?"),
                sig.getOrDefault("price", 0),
                sig.getOrDefault("score", 0),
                sig.getOrDefault("time", "")
            ));
        }

        sb.append("], \"count\": ").append(added).append("}");
        return sb.toString();
    }

    private String getSessionStats() {
        if (sessionSupplier == null) return "{\"error\": \"Session data not available\"}";
        Map<String, Object> session = sessionSupplier.get();
        if (session == null) {
            return "{\"error\": \"Session not initialized\"}";
        }

        return String.format(
            "{\"session_id\": \"%s\", \"phase\": \"%s\", \"trades\": %s, \"wins\": %s, \"losses\": %s, \"pnl\": %s, \"warmup_complete\": %s}",
            session.getOrDefault("sessionId", ""),
            session.getOrDefault("phase", ""),
            session.getOrDefault("trades", 0),
            session.getOrDefault("wins", 0),
            session.getOrDefault("losses", 0),
            session.getOrDefault("pnl", 0),
            session.getOrDefault("warmupComplete", false)
        );
    }

    private String getThresholds() {
        if (thresholdsSupplier == null) return "{\"error\": \"Threshold data not available\"}";
        Map<String, Integer> thresholds = thresholdsSupplier.get();
        if (thresholds == null) {
            return "{\"error\": \"Thresholds not available\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(String.format("\"minConfluenceScore\": %d, ", thresholds.getOrDefault("minConfluenceScore", 0)));
        sb.append(String.format("\"confluenceThreshold\": %d, ", thresholds.getOrDefault("confluenceThreshold", 0)));
        sb.append(String.format("\"icebergMinOrders\": %d, ", thresholds.getOrDefault("icebergMinOrders", 0)));
        sb.append(String.format("\"spoofMinSize\": %d, ", thresholds.getOrDefault("spoofMinSize", 0)));
        sb.append(String.format("\"absorptionMinSize\": %d, ", thresholds.getOrDefault("absorptionMinSize", 0)));
        sb.append(String.format("\"useAIAdaptive\": %s", thresholds.getOrDefault("useAIAdaptive", 0) == 1));
        sb.append("}");
        return sb.toString();
    }

    private String getFullSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"price\": ").append(getCurrentPrice()).append(",");
        sb.append("\"cvd\": ").append(getCVD()).append(",");
        sb.append("\"vwap\": ").append(getVWAP()).append(",");
        sb.append("\"emas\": ").append(getEMAs()).append(",");
        sb.append("\"order_book\": ").append(getOrderBook()).append(",");
        sb.append("\"session\": ").append(getSessionStats()).append(",");
        sb.append("\"thresholds\": ").append(getThresholds());
        sb.append("}");
        return sb.toString();
    }

    /**
     * Get the tools array for Claude API request
     */
    public static String getToolsJsonArray() {
        return "[" + String.join(",", TOOL_DEFINITIONS) + "]";
    }
}
