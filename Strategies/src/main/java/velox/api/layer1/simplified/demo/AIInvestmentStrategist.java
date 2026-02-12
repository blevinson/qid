package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradingMemoryService;
import velox.api.layer1.simplified.demo.memory.MemorySearchResult;

import java.util.List;

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

    /**
     * Constructor for AIInvestmentStrategist
     *
     * @param memoryService TradingMemoryService for searching historical patterns
     * @param apiToken API token for Claude API calls
     */
    public AIInvestmentStrategist(TradingMemoryService memoryService, String apiToken) {
        this.memoryService = memoryService;
        this.apiToken = apiToken;
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
     * @param callback AIStrategistCallback for async decision
     */
    public void evaluateSetup(SignalData signal, AIStrategistCallback callback) {
        // Validate input
        if (signal == null || callback == null) {
            if (callback != null) {
                callback.onError("Signal and callback cannot be null");
            }
            return;
        }

        // Step 1: Search memory for similar historical setups
        String query = buildMemoryQuery(signal);
        List<MemorySearchResult> memoryResults = memoryService.search(query, 5);

        // Step 2: Analyze memory results
        String context = buildMemoryContext(memoryResults);

        // Step 3: Ask AI to make decision
        String prompt = buildAIPrompt(signal, context);

        // Call Claude API (placeholder for now)
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
     * Build AI prompt with signal data and memory context
     * This prompt will be sent to Claude API for decision making
     *
     * @param signal SignalData with market context
     * @param memoryContext Formatted memory search results
     * @return AI prompt string
     */
    private String buildAIPrompt(SignalData signal, String memoryContext) {
        return String.format("""
            You are an AI Investment Strategist analyzing a trading setup.

            SIGNAL:
            - Type: %s %s
            - Price: %d
            - Confluence Score: %d
            - CVD: %d (%s)
            - Trend: %s

            %s

            Based on the signal strength and historical memory, should we TAKE or SKIP this setup?
            Respond with JSON:
            {
              "action": "TAKE" | "SKIP",
              "confidence": 0.0-1.0,
              "reasoning": "brief explanation",
              "plan": {
                "orderType": "BUY_STOP" | "SELL_STOP",
                "entryPrice": %d,
                "stopLossPrice": calculated,
                "takeProfitPrice": calculated
              }
            }
            """,
            signal.direction,
            signal.detection.type,
            signal.price,
            signal.score,
            signal.market.cvd,
            signal.market.cvdTrend,
            signal.market.trend,
            memoryContext,
            signal.price);
    }

    /**
     * Call Claude API to get AI decision
     * TODO: Implement actual Claude API call via z.ai
     * For now, returns a placeholder decision
     *
     * @param prompt AI prompt string
     * @param callback Callback for decision
     * @param signal Original signal for price reference
     */
    private void callClaudeAPI(String prompt, AIStrategistCallback callback, SignalData signal) {
        // TODO: Call Claude API via z.ai
        // For now, return placeholder decision

        AIDecision decision = new AIDecision();
        decision.shouldTake = true;
        decision.confidence = 0.75;
        decision.reasoning = "Strong confluence with memory support";

        TradePlan plan = new TradePlan();
        boolean isLong = "LONG".equalsIgnoreCase(signal.direction);

        plan.orderType = isLong ? "BUY_STOP" : "SELL_STOP";
        plan.entryPrice = signal.price;

        // For LONG: SL below, TP above
        // For SHORT: SL above, TP below
        if (isLong) {
            plan.stopLossPrice = signal.price - 30;   // Default 30 tick SL
            plan.takeProfitPrice = signal.price + 70; // Default 70 tick TP
        } else {
            plan.stopLossPrice = signal.price + 30;   // SL above for SHORT
            plan.takeProfitPrice = signal.price - 70; // TP below for SHORT
        }

        plan.contracts = 1;  // Default 1 contract
        decision.plan = plan;

        callback.onDecision(decision);
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
