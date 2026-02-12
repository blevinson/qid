package velox.api.layer1.simplified.demo;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.BiFunction;

/**
 * AI Tools Provider
 * Provides tools that the AI can call to get real-time trading data
 * and make adjustments to the trading system
 *
 * This implements function calling so AI can:
 * - Get current market data
 * - Get order book levels
 * - Get recent signals
 * - Get signal performance analytics
 * - Adjust thresholds dynamically
 * - Recommend improvements
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

    // Performance analytics supplier
    private Supplier<Map<String, Object>> performanceSupplier;

    // Threshold adjustment callback (strategy handles actual adjustment)
    private BiFunction<String, Integer, Boolean> thresholdAdjuster;

    // Recent adjustments for feedback
    private final List<Map<String, Object>> recentAdjustments = new ArrayList<>();

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
        """,
        """
        {
            "name": "get_signal_performance",
            "description": "Get analytics on signal performance. Shows win rates, score distributions, and which factors correlate with wins. Use this to understand what's working.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
        """,
        """
        {
            "name": "adjust_threshold",
            "description": "Adjust a signal detection threshold. Use this to optimize the trading system based on performance data. Changes are logged and can be reviewed.",
            "input_schema": {
                "type": "object",
                "properties": {
                    "threshold_name": {
                        "type": "string",
                        "enum": ["minConfluenceScore", "confluenceThreshold", "icebergMinOrders", "spoofMinSize", "absorptionMinSize"],
                        "description": "The threshold to adjust"
                    },
                    "value": {
                        "type": "integer",
                        "description": "The new value for the threshold"
                    },
                    "reason": {
                        "type": "string",
                        "description": "Why this adjustment is being made (for logging)"
                    }
                },
                "required": ["threshold_name", "value", "reason"]
            }
        }
        """,
        """
        {
            "name": "get_recent_adjustments",
            "description": "Get a list of recent threshold adjustments made by the AI. Use this to review what changes have been made and their reasoning.",
            "input_schema": {
                "type": "object",
                "properties": {
                    "count": {
                        "type": "integer",
                        "description": "Number of recent adjustments to return (default 5)",
                        "default": 5
                    }
                },
                "required": []
            }
        }
        """,
        """
        {
            "name": "recommend_improvements",
            "description": "Analyze current performance and recommend improvements. This examines signal patterns and suggests threshold changes.",
            "input_schema": {
                "type": "object",
                "properties": {
                    "focus_area": {
                        "type": "string",
                        "enum": ["win_rate", "signal_quality", "risk_management", "all"],
                        "description": "What area to focus recommendations on",
                        "default": "all"
                    }
                },
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
    public void setPerformanceSupplier(Supplier<Map<String, Object>> supplier) { this.performanceSupplier = supplier; }
    public void setThresholdAdjuster(BiFunction<String, Integer, Boolean> adjuster) { this.thresholdAdjuster = adjuster; }

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
                case "get_signal_performance" -> getSignalPerformance();
                case "adjust_threshold" -> adjustThreshold(arguments);
                case "get_recent_adjustments" -> getRecentAdjustments(arguments);
                case "recommend_improvements" -> recommendImprovements(arguments);
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

    // ========== NEW FEEDBACK LOOP TOOLS ==========

    /**
     * Get signal performance analytics
     * Shows win rates, score distributions, and factor correlations
     */
    private String getSignalPerformance() {
        if (performanceSupplier == null) {
            return "{\"error\": \"Performance analytics not available\"}";
        }

        Map<String, Object> perf = performanceSupplier.get();
        if (perf == null || perf.isEmpty()) {
            return "{\"message\": \"Not enough data for performance analysis yet\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Win rate
        sb.append(String.format("\"win_rate\": %.1f,", perf.getOrDefault("winRate", 0.0)));
        sb.append(String.format("\"total_signals\": %d,", perf.getOrDefault("totalSignals", 0)));
        sb.append(String.format("\"take_count\": %d,", perf.getOrDefault("takeCount", 0)));
        sb.append(String.format("\"skip_count\": %d,", perf.getOrDefault("skipCount", 0)));

        // Score analysis
        sb.append("\"score_analysis\": {");
        sb.append(String.format("\"avg_winning_score\": %.1f,", perf.getOrDefault("avgWinningScore", 0.0)));
        sb.append(String.format("\"avg_losing_score\": %.1f,", perf.getOrDefault("avgLosingScore", 0.0)));
        sb.append(String.format("\"avg_skip_score\": %.1f", perf.getOrDefault("avgSkipScore", 0.0)));
        sb.append("},");

        // Factor correlations
        sb.append("\"factor_correlations\": {");
        sb.append(String.format("\"cvd_alignment\": %.2f,", perf.getOrDefault("cvdAlignmentCorrelation", 0.0)));
        sb.append(String.format("\"trend_alignment\": %.2f,", perf.getOrDefault("trendAlignmentCorrelation", 0.0)));
        sb.append(String.format("\"ema_alignment\": %.2f", perf.getOrDefault("emaAlignmentCorrelation", 0.0)));
        sb.append("},");

        // Time analysis
        sb.append("\"time_analysis\": {");
        sb.append(String.format("\"best_hour\": %s,", perf.getOrDefault("bestHour", "N/A")));
        sb.append(String.format("\"worst_hour\": %s", perf.getOrDefault("worstHour", "N/A")));
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Adjust a threshold setting
     * This allows AI to optimize the trading system
     */
    private String adjustThreshold(Map<String, Object> arguments) {
        if (arguments == null) {
            return "{\"success\": false, \"error\": \"Missing arguments\"}";
        }

        String thresholdName = (String) arguments.get("threshold_name");
        Integer value = arguments.get("value") instanceof Number ?
            ((Number) arguments.get("value")).intValue() : null;
        String reason = (String) arguments.get("reason");

        if (thresholdName == null || value == null) {
            return "{\"success\": false, \"error\": \"Missing threshold_name or value\"}";
        }

        // Validate threshold name
        List<String> validThresholds = Arrays.asList(
            "minConfluenceScore", "confluenceThreshold", "icebergMinOrders",
            "spoofMinSize", "absorptionMinSize"
        );

        if (!validThresholds.contains(thresholdName)) {
            return "{\"success\": false, \"error\": \"Invalid threshold name: " + thresholdName + "\"}";
        }

        // Validate value ranges
        int min = 0, max = 200;
        switch (thresholdName) {
            case "minConfluenceScore" -> { min = 0; max = 150; }
            case "confluenceThreshold" -> { min = 0; max = 150; }
            case "icebergMinOrders" -> { min = 1; max = 100; }
            case "spoofMinSize" -> { min = 5; max = 200; }
            case "absorptionMinSize" -> { min = 10; max = 500; }
        }

        if (value < min || value > max) {
            return String.format("{\"success\": false, \"error\": \"Value %d out of range [%d, %d]\"}",
                value, min, max);
        }

        // Get current value
        int oldValue = 0;
        if (thresholdsSupplier != null) {
            Map<String, Integer> thresholds = thresholdsSupplier.get();
            if (thresholds != null) {
                oldValue = thresholds.getOrDefault(thresholdName, 0);
            }
        }

        // Apply adjustment via callback
        boolean success = false;
        if (thresholdAdjuster != null) {
            success = thresholdAdjuster.apply(thresholdName, value);
        }

        if (success) {
            // Log the adjustment
            Map<String, Object> adjustment = new HashMap<>();
            adjustment.put("threshold", thresholdName);
            adjustment.put("oldValue", oldValue);
            adjustment.put("newValue", value);
            adjustment.put("reason", reason != null ? reason : "AI adjustment");
            adjustment.put("timestamp", System.currentTimeMillis());
            recentAdjustments.add(adjustment);

            // Keep only last 20 adjustments
            while (recentAdjustments.size() > 20) {
                recentAdjustments.remove(0);
            }

            return String.format("{\"success\": true, \"threshold\": \"%s\", \"old_value\": %d, \"new_value\": %d, \"reason\": \"%s\"}",
                thresholdName, oldValue, value, reason != null ? reason.replace("\"", "\\\"") : "");
        } else {
            return "{\"success\": false, \"error\": \"Failed to apply adjustment\"}";
        }
    }

    /**
     * Get recent threshold adjustments
     */
    private String getRecentAdjustments(Map<String, Object> arguments) {
        int count = 5;
        if (arguments != null && arguments.containsKey("count")) {
            count = Math.min(20, ((Number) arguments.get("count")).intValue());
        }

        if (recentAdjustments.isEmpty()) {
            return "{\"adjustments\": [], \"message\": \"No adjustments made yet\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"adjustments\": [");

        int start = Math.max(0, recentAdjustments.size() - count);
        boolean first = true;
        for (int i = recentAdjustments.size() - 1; i >= start; i--) {
            Map<String, Object> adj = recentAdjustments.get(i);
            if (!first) sb.append(",");
            first = false;

            String time = new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date((Long) adj.get("timestamp")));

            sb.append(String.format(
                "{\"threshold\": \"%s\", \"old\": %s, \"new\": %s, \"reason\": \"%s\", \"time\": \"%s\"}",
                adj.get("threshold"),
                adj.get("oldValue"),
                adj.get("newValue"),
                adj.get("reason").toString().replace("\"", "\\\""),
                time
            ));
        }

        sb.append("], \"count\": ").append(Math.min(count, recentAdjustments.size())).append("}");
        return sb.toString();
    }

    /**
     * Recommend improvements based on performance analysis
     */
    private String recommendImprovements(Map<String, Object> arguments) {
        String focusArea = arguments != null ?
            (String) arguments.getOrDefault("focus_area", "all") : "all";

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"focus_area\": \"").append(focusArea).append("\",");

        // Get current performance
        Map<String, Object> perf = performanceSupplier != null ? performanceSupplier.get() : null;
        Map<String, Integer> thresholds = thresholdsSupplier != null ? thresholdsSupplier.get() : null;

        sb.append("\"recommendations\": [");

        List<String> recommendations = new ArrayList<>();

        if (perf != null) {
            double winRate = ((Number) perf.getOrDefault("winRate", 0.0)).doubleValue();
            double avgWinScore = ((Number) perf.getOrDefault("avgWinningScore", 0.0)).doubleValue();
            double avgLoseScore = ((Number) perf.getOrDefault("avgLosingScore", 0.0)).doubleValue();
            int totalSignals = ((Number) perf.getOrDefault("totalSignals", 0)).intValue();

            // Win rate recommendations
            if (winRate < 0.4 && totalSignals > 5) {
                int currentMin = thresholds != null ? thresholds.getOrDefault("minConfluenceScore", 40) : 40;
                int suggestedMin = Math.min(150, currentMin + 10);
                recommendations.add(String.format(
                    "{\"action\": \"increase_min_score\", \"reason\": \"Win rate %.0f%% is low\", \"suggested_value\": %d}",
                    winRate * 100, suggestedMin
                ));
            }

            if (winRate > 0.7 && totalSignals > 5) {
                int currentMin = thresholds != null ? thresholds.getOrDefault("minConfluenceScore", 40) : 40;
                if (currentMin > 20) {
                    int suggestedMin = Math.max(10, currentMin - 5);
                    recommendations.add(String.format(
                        "{\"action\": \"decrease_min_score\", \"reason\": \"High win rate %.0f%%, may be too conservative\", \"suggested_value\": %d}",
                        winRate * 100, suggestedMin
                    ));
                }
            }

            // Score analysis
            if (avgLoseScore > avgWinScore && totalSignals > 5) {
                recommendations.add(String.format(
                    "{\"action\": \"review_scoring\", \"reason\": \"Losing signals have higher avg score (%.0f) than winners (%.0f)\"}",
                    avgLoseScore, avgWinScore
                ));
            }
        }

        // Default recommendation if no data
        if (recommendations.isEmpty()) {
            recommendations.add("{\"action\": \"collect_data\", \"reason\": \"Need more signal data to make recommendations\"}");
        }

        for (int i = 0; i < recommendations.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(recommendations.get(i));
        }

        sb.append("]");
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
