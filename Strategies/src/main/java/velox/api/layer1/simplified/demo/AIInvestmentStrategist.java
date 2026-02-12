package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradingMemoryService;
import velox.api.layer1.simplified.demo.memory.MemorySearchResult;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI Investment Strategist
 * Uses memory search to make intelligent trading decisions
 * Philosophy: "Investment strategist vs speed daemon"
 *
 * This class evaluates trading setups by searching memory for similar historical patterns
 * and using AI to make strategic decisions about whether to take or skip a setup.
 */
public class AIInvestmentStrategist {
    private final TradingMemoryService memoryService;
    private final String apiToken;
    private final HttpClient httpClient;
    private final Gson gson;
    private static final String API_URL = "https://api.z.ai/api/anthropic/v1/messages";
    private static final String MODEL = "glm-5";

    // File logging
    private static final String LOG_FILE = System.getProperty("user.home") + "/ai-strategist.log";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Constructor for AIInvestmentStrategist
     *
     * @param memoryService TradingMemoryService for searching historical patterns
     * @param apiToken API token for Claude API calls
     */
    public AIInvestmentStrategist(TradingMemoryService memoryService, String apiToken) {
        this.memoryService = memoryService;
        this.apiToken = apiToken;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        log("========== AI Investment Strategist Initialized ==========");
    }

    /**
     * Log to file (and console)
     */
    private void log(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String logLine = timestamp + " | " + message;
        System.out.println(logLine);
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            writer.println(logLine);
        } catch (Exception e) {
            // Ignore file write errors
        }
    }

    /**
     * Evaluate a trading setup using AI + Memory
     *
     * This method:
     * 1. Searches memory for similar historical setups
     * 2. Analyzes memory results to build context
     * 3. Asks AI to make a strategic decision
     * 4. Returns decision via callback
     *
     * @param signal SignalData with market context
     * @param sessionContext Session state (can be null)
     * @param callback AIStrategistCallback for async decision
     */
    public void evaluateSetup(SignalData signal, SessionContext sessionContext, AIStrategistCallback callback) {
        // Validate input
        if (signal == null || callback == null) {
            if (callback != null) {
                callback.onError("Signal and callback cannot be null");
            }
            return;
        }

        log("========== AI STRATEGIST EVALUATION ==========");
        log("SIGNAL: " + signal.direction + " @ " + signal.price + " | Score: " + signal.score + "/" + signal.threshold);
        log("CVD: " + signal.market.cvd + " (" + signal.market.cvdTrend + ") | Trend: " + signal.market.trend);

        // Log session context
        if (sessionContext != null) {
            log("SESSION: " + sessionContext.toSummary());
        }

        // Log key levels for debugging
        log("KEY LEVELS:");
        if (!Double.isNaN(signal.market.vwap) && signal.market.vwap > 0) {
            log("  VWAP: " + String.format("%.2f", signal.market.vwap) + " (" + signal.market.priceVsVwap + ")");
        }
        if (signal.market.pocPrice > 0) {
            log("  POC: " + signal.market.pocPrice);
        }
        if (signal.market.valueAreaLow > 0 && signal.market.valueAreaHigh > 0) {
            log("  Value Area: " + signal.market.valueAreaLow + " - " + signal.market.valueAreaHigh);
        }
        if (signal.market.domSupportPrice > 0) {
            log("  DOM SUPPORT: " + signal.market.domSupportPrice + " (" + signal.market.domSupportVolume + " contracts)");
        }
        if (signal.market.domResistancePrice > 0) {
            log("  DOM RESISTANCE: " + signal.market.domResistancePrice + " (" + signal.market.domResistanceVolume + " contracts)");
        }
        log("  DOM IMBALANCE: " + String.format("%.2f", signal.market.domImbalanceRatio) + " (" + signal.market.domImbalanceSentiment + ")");

        // Step 1: Search memory for similar historical setups
        String query = buildMemoryQuery(signal);
        log("Memory Query: " + query);
        List<MemorySearchResult> memoryResults = memoryService.search(query, 5);
        log("Memory Results: " + memoryResults.size() + " similar setups found");

        // Step 2: Analyze memory results
        String context = buildMemoryContext(memoryResults);
        if (memoryResults.isEmpty()) {
            log("WARNING: No memory context - AI will use general analysis");
        }

        // Step 3: Ask AI to make decision
        String prompt = buildAIPrompt(signal, context, sessionContext);

        // Call Claude API
        callClaudeAPI(prompt, callback, signal);
    }

    /**
     * Build a search query from signal data
     * This query will be used to search memory for similar setups
     *
     * @param signal SignalData with market context
     * @return Search query string
     */
    private String buildMemoryQuery(SignalData signal) {
        // Build query from signal data
        return String.format("%s %s iceberg CVD %d trend %s",
            signal.direction,
            signal.detection.type,
            (long)signal.market.cvd,
            signal.market.trend);
    }

    /**
     * Build context string from memory search results
     * Formats the results for inclusion in AI prompt
     *
     * @param results List of memory search results
     * @return Formatted context string
     */
    private String buildMemoryContext(List<MemorySearchResult> results) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("RELEVANT MEMORY:\n");
        for (MemorySearchResult result : results) {
            ctx.append(String.format("- [%.2f] %s (lines %d-%d)\n",
                result.getScore(),
                result.getSnippet(),
                result.getStartLine(),
                result.getEndLine()));
        }
        return ctx.toString();
    }

    /**
     * Build AI prompt with signal data, memory context, and session context
     * This prompt will be sent to Claude API for decision making
     *
     * @param signal SignalData with market context
     * @param memoryContext Formatted memory search results
     * @param sessionContext Session state (can be null)
     * @return AI prompt string
     */
    private String buildAIPrompt(SignalData signal, String memoryContext, SessionContext sessionContext) {
        // Build key levels section
        StringBuilder keyLevels = new StringBuilder();

        // VWAP
        if (!Double.isNaN(signal.market.vwap) && signal.market.vwap > 0) {
            keyLevels.append(String.format("- VWAP: %.2f (%s, %.1f ticks away)\n",
                signal.market.vwap,
                signal.market.priceVsVwap,
                signal.market.vwapDistanceTicks));
        }

        // Volume Profile levels (POC, Value Area)
        if (signal.market.pocPrice > 0) {
            keyLevels.append(String.format("- POC (Point of Control): %d\n", signal.market.pocPrice));
        }
        if (signal.market.valueAreaLow > 0 && signal.market.valueAreaHigh > 0) {
            keyLevels.append(String.format("- Value Area: %d - %d\n",
                signal.market.valueAreaLow, signal.market.valueAreaHigh));
        }

        // EMA levels
        if (signal.market.ema9 > 0) {
            keyLevels.append(String.format("- EMA9: %.2f (%.1f ticks)\n",
                signal.market.ema9, signal.market.ema9DistanceTicks));
        }
        if (signal.market.ema21 > 0) {
            keyLevels.append(String.format("- EMA21: %.2f (%.1f ticks)\n",
                signal.market.ema21, signal.market.ema21DistanceTicks));
        }
        if (signal.market.ema50 > 0) {
            keyLevels.append(String.format("- EMA50: %.2f (%.1f ticks)\n",
                signal.market.ema50, signal.market.ema50DistanceTicks));
        }

        // DOM (Order Book) levels - Real-time support/resistance from liquidity
        if (signal.market.domSupportPrice > 0) {
            keyLevels.append(String.format("- DOM SUPPORT: %d (%d contracts, %d ticks below)\n",
                signal.market.domSupportPrice,
                signal.market.domSupportVolume,
                signal.market.domSupportDistance));
        }
        if (signal.market.domResistancePrice > 0) {
            keyLevels.append(String.format("- DOM RESISTANCE: %d (%d contracts, %d ticks above)\n",
                signal.market.domResistancePrice,
                signal.market.domResistanceVolume,
                signal.market.domResistanceDistance));
        }
        if (signal.market.domImbalanceRatio != 0) {
            keyLevels.append(String.format("- DOM IMBALANCE: %.2f (%s)\n",
                signal.market.domImbalanceRatio,
                signal.market.domImbalanceSentiment));
        }

        // If no key levels available, add placeholder
        String keyLevelsStr = keyLevels.length() > 0 ? keyLevels.toString() : "- (calculating...)\n";

        // Build session context section
        String sessionContextStr = "";
        if (sessionContext != null) {
            sessionContextStr = sessionContext.toAIString();
        }

        // Build threshold context section
        String thresholdContextStr = "";
        if (signal.thresholds != null) {
            thresholdContextStr = signal.thresholds.toAIString();
        }

        return String.format("""
            You are an AI Investment Strategist analyzing a trading setup.

            %s

            SIGNAL:
            - Type: %s %s
            - Price: %d
            - Confluence Score: %d
            - CVD: %d (%s)
            - Trend: %s

            KEY LEVELS (use for SL/TP placement):
            %s

            %s

            %s

            Based on the signal strength, key levels, session context, historical memory, and current thresholds, should we TAKE or SKIP this setup?

            IMPORTANT CONSIDERATIONS:
            1. If this is a NEW SESSION, be more cautious - indicators are just starting to accumulate
            2. During OPENING/CLOSING BELL phases, expect higher volatility - require stronger confluence
            3. During LUNCH HOUR, expect lower volume - may see choppy conditions
            4. When placing SL/TP, use the KEY LEVELS above:
               - Place STOP LOSS beyond nearest support/resistance level
               - Place TAKE PROFIT at or before next resistance/support level
               - Consider VWAP and POC as key levels for mean reversion

            THRESHOLD ADJUSTMENT (Optional):
            You CAN recommend threshold adjustments based on market conditions:
            - If too many low-quality signals: INCREASE minConfluenceScore or confluenceThreshold
            - If missing good opportunities: DECREASE thresholds slightly
            - If high volatility: INCREASE detection thresholds (icebergMinOrders, etc.)
            - If low volatility: detection thresholds can be lower
            - Only include thresholdAdjustment if you have a specific recommendation

            Respond with JSON:
            {
              "action": "TAKE" | "SKIP",
              "confidence": 0.0-1.0,
              "reasoning": "brief explanation referencing key levels and session context",
              "plan": {
                "orderType": "BUY" | "SELL",
                "entryPrice": %d,
                "stopLossPrice": calculated based on key levels,
                "takeProfitPrice": calculated based on key levels
              },
              "thresholdAdjustment": {
                "minConfluenceScore": optional new value,
                "confluenceThreshold": optional new value,
                "icebergMinOrders": optional new value,
                "spoofMinSize": optional new value,
                "absorptionMinSize": optional new value,
                "thresholdMultiplier": optional new value,
                "reasoning": "why you're adjusting thresholds"
              }
            }
            """,
            sessionContextStr,
            signal.direction,
            signal.detection.type,
            signal.price,
            signal.score,
            signal.market.cvd,
            signal.market.cvdTrend,
            signal.market.trend,
            keyLevelsStr,
            thresholdContextStr,
            memoryContext,
            signal.price);
    }

    /**
     * Call Claude API to get AI decision
     *
     * @param prompt AI prompt string
     * @param callback Callback for decision
     * @param signal Original signal for price reference
     */
    private void callClaudeAPI(String prompt, AIStrategistCallback callback, SignalData signal) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return callClaudeAPISync(prompt, signal);
            } catch (Exception e) {
                throw new RuntimeException("API call failed: " + e.getMessage(), e);
            }
        }).thenAccept(decision -> {
            callback.onDecision(decision);
        }).exceptionally(e -> {
            callback.onError(e.getMessage());
            return null;
        });
    }

    /**
     * Synchronous Claude API call
     */
    private AIDecision callClaudeAPISync(String prompt, SignalData signal) throws Exception {
        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);
        requestBody.addProperty("max_tokens", 1024);

        // Use signal's threshold in system prompt
        int threshold = signal.threshold;
        int strongThreshold = threshold + 30;
        int moderateThreshold = threshold + 10;

        String systemPrompt = String.format("""
            You are an AI Investment Strategist specializing in order flow trading.

            PHILOSOPHY: "Investment strategist vs speed daemon"
            - Quality over quantity
            - High confluence = high probability
            - Wait for perfect setups

            DECISION FRAMEWORK:
            1. Signal Quality (score 0-135, threshold: %d)
               - %d+ = Strong signal (TAKE freely)
               - %d-%d = Moderate signal (TAKE if context supports)
               - Below %d = Weak signal, SKIP unless exceptional context

            2. Market Context
               - CVD direction alignment
               - Trend confirmation
               - Volume at price support

            3. Memory Context
               - Similar historical patterns
               - Win/loss outcomes
               - Lessons learned

            Respond ONLY with valid JSON:
            """,
            threshold,
            strongThreshold,
            threshold, strongThreshold,
            threshold);

        requestBody.addProperty("system", systemPrompt);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        requestBody.add("messages", gson.toJsonTree(new Object[]{message}));

        // Make HTTP request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("x-api-key", apiToken)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("API error: " + response.statusCode() + " - " + response.body());
        }

        // Parse response
        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        String content = jsonResponse.getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();

        return parseDecision(content, signal);
    }

    /**
     * Parse AI response into decision
     */
    private AIDecision parseDecision(String response, SignalData signal) {
        AIDecision decision = new AIDecision();

        try {
            // Extract JSON from markdown if present
            String jsonStr = response;
            if (response.contains("```json")) {
                int start = response.indexOf("```json") + 7;
                int end = response.indexOf("```", start);
                jsonStr = response.substring(start, end).trim();
            } else if (response.contains("```")) {
                int start = response.indexOf("```") + 3;
                int end = response.indexOf("```", start);
                jsonStr = response.substring(start, end).trim();
            }

            JsonObject json = gson.fromJson(jsonStr, JsonObject.class);

            // Parse action
            String action = json.has("action") ? json.get("action").getAsString() : "SKIP";
            decision.shouldTake = "TAKE".equalsIgnoreCase(action);

            // Parse confidence
            if (json.has("confidence")) {
                decision.confidence = json.get("confidence").getAsDouble();
            } else {
                decision.confidence = 0.5;
            }

            // Parse reasoning
            decision.reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "No reasoning provided";

            // Parse trade plan
            if (json.has("plan") && decision.shouldTake) {
                JsonObject planJson = json.getAsJsonObject("plan");
                TradePlan plan = new TradePlan();

                plan.orderType = planJson.has("orderType") ? planJson.get("orderType").getAsString() : "BUY_STOP";
                plan.entryPrice = planJson.has("entryPrice") ? planJson.get("entryPrice").getAsInt() : signal.price;
                plan.stopLossPrice = planJson.has("stopLossPrice") ? planJson.get("stopLossPrice").getAsInt() : signal.price - 30;
                plan.takeProfitPrice = planJson.has("takeProfitPrice") ? planJson.get("takeProfitPrice").getAsInt() : signal.price + 70;
                plan.contracts = planJson.has("contracts") ? planJson.get("contracts").getAsInt() : 1;

                decision.plan = plan;
            } else if (decision.shouldTake) {
                // Create default plan if taking but no plan provided
                decision.plan = createDefaultPlan(signal);
            }

            // Parse threshold adjustment (optional)
            if (json.has("thresholdAdjustment")) {
                JsonObject adjJson = json.getAsJsonObject("thresholdAdjustment");
                ThresholdAdjustment adj = new ThresholdAdjustment();

                if (adjJson.has("minConfluenceScore")) {
                    adj.minConfluenceScore = adjJson.get("minConfluenceScore").getAsInt();
                }
                if (adjJson.has("confluenceThreshold")) {
                    adj.confluenceThreshold = adjJson.get("confluenceThreshold").getAsInt();
                }
                if (adjJson.has("icebergMinOrders")) {
                    adj.icebergMinOrders = adjJson.get("icebergMinOrders").getAsInt();
                }
                if (adjJson.has("spoofMinSize")) {
                    adj.spoofMinSize = adjJson.get("spoofMinSize").getAsInt();
                }
                if (adjJson.has("absorptionMinSize")) {
                    adj.absorptionMinSize = adjJson.get("absorptionMinSize").getAsInt();
                }
                if (adjJson.has("thresholdMultiplier")) {
                    adj.thresholdMultiplier = adjJson.get("thresholdMultiplier").getAsDouble();
                }
                if (adjJson.has("reasoning")) {
                    adj.reasoning = adjJson.get("reasoning").getAsString();
                }

                decision.thresholdAdjustment = adj;
            }

        } catch (Exception e) {
            // Fallback on parse error
            decision.shouldTake = false;
            decision.confidence = 0.0;
            decision.reasoning = "Failed to parse AI response: " + e.getMessage();
            log("ERROR: Failed to parse AI response: " + e.getMessage());
        }

        // Log the final decision
        String decisionType = decision.shouldTake ? "TAKE" : "SKIP";
        log("AI DECISION: " + decisionType + " | Confidence: " + String.format("%.0f%%", decision.confidence * 100));
        log("Reasoning: " + decision.reasoning);
        if (decision.shouldTake && decision.plan != null) {
            log("Plan: " + decision.plan.orderType + " @ " + decision.plan.entryPrice +
                " | SL: " + decision.plan.stopLossPrice + " | TP: " + decision.plan.takeProfitPrice);
        }
        // Log threshold adjustments if any
        if (decision.thresholdAdjustment != null && decision.thresholdAdjustment.hasAdjustments()) {
            log("THRESHOLD ADJUSTMENT: " + decision.thresholdAdjustment.toString());
        }
        log("============================================================");

        return decision;
    }

    /**
     * Create default trade plan based on signal
     */
    private TradePlan createDefaultPlan(SignalData signal) {
        TradePlan plan = new TradePlan();
        boolean isLong = "LONG".equalsIgnoreCase(signal.direction);

        plan.orderType = isLong ? "BUY_STOP" : "SELL_STOP";
        plan.entryPrice = signal.price;

        // For LONG: SL below, TP above
        // For SHORT: SL above, TP below
        if (isLong) {
            plan.stopLossPrice = signal.price - 30;   // 30 tick SL
            plan.takeProfitPrice = signal.price + 70; // 70 tick TP (1:2 R:R)
        } else {
            plan.stopLossPrice = signal.price + 30;
            plan.takeProfitPrice = signal.price - 70;
        }

        plan.contracts = 1;
        plan.notes = "Default plan - AI did not provide specific plan";

        return plan;
    }

    /**
     * Callback interface for async AI decisions
     */
    public interface AIStrategistCallback {
        /**
         * Called when AI decision is ready
         *
         * @param decision AI decision with trade plan
         */
        void onDecision(AIDecision decision);

        /**
         * Called if an error occurs during evaluation
         *
         * @param error Error message
         */
        default void onError(String error) {
            System.err.println("AIInvestmentStrategist error: " + error);
        }
    }

    /**
     * AI Decision data class
     * Contains the decision and trade plan
     */
    public static class AIDecision {
        /** Whether to take the trade */
        public boolean shouldTake;

        /** Confidence level (0.0 to 1.0) */
        public double confidence;

        /** Reasoning for the decision */
        public String reasoning;

        /** Trade plan with entry, SL, TP */
        public TradePlan plan;

        /** Threshold adjustments (optional - AI can recommend changes) */
        public ThresholdAdjustment thresholdAdjustment;
    }

    /**
     * Threshold Adjustment data class
     * AI can recommend threshold changes based on market conditions
     */
    public static class ThresholdAdjustment {
        /** Adjust minimum confluence score */
        public Integer minConfluenceScore;

        /** Adjust confluence threshold */
        public Integer confluenceThreshold;

        /** Adjust iceberg minimum orders */
        public Integer icebergMinOrders;

        /** Adjust spoof minimum size */
        public Integer spoofMinSize;

        /** Adjust absorption minimum size */
        public Integer absorptionMinSize;

        /** Adjust threshold multiplier */
        public Double thresholdMultiplier;

        /** Reasoning for the threshold adjustment */
        public String reasoning;

        /**
         * Check if any adjustments are proposed
         */
        public boolean hasAdjustments() {
            return minConfluenceScore != null ||
                   confluenceThreshold != null ||
                   icebergMinOrders != null ||
                   spoofMinSize != null ||
                   absorptionMinSize != null ||
                   thresholdMultiplier != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ThresholdAdjustment{");
            if (minConfluenceScore != null) sb.append("minConfluenceScore=").append(minConfluenceScore).append(" ");
            if (confluenceThreshold != null) sb.append("confluenceThreshold=").append(confluenceThreshold).append(" ");
            if (icebergMinOrders != null) sb.append("icebergMinOrders=").append(icebergMinOrders).append(" ");
            if (spoofMinSize != null) sb.append("spoofMinSize=").append(spoofMinSize).append(" ");
            if (absorptionMinSize != null) sb.append("absorptionMinSize=").append(absorptionMinSize).append(" ");
            if (thresholdMultiplier != null) sb.append("thresholdMultiplier=").append(thresholdMultiplier).append(" ");
            sb.append("reasoning='").append(reasoning).append("'}");
            return sb.toString();
        }
    }

    /**
     * Trade Plan data class
     * Contains strategic order placement details
     */
    public static class TradePlan {
        /** Order type: "BUY_STOP", "SELL_STOP", "BUY_LIMIT", "SELL_LIMIT" */
        public String orderType;

        /** Entry price for the order */
        public int entryPrice;

        /** Stop loss price */
        public int stopLossPrice;

        /** Take profit price */
        public int takeProfitPrice;

        /** Number of contracts (optional, defaults to 1) */
        public int contracts = 1;

        /** Additional reasoning or notes */
        public String notes;
    }
}
