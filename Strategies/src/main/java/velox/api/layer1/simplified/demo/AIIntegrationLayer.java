package velox.api.layer1.simplified.demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI Integration Layer for LLM Communication
 * Connects to Anthropic API via z.ai
 */
public class AIIntegrationLayer {
    private static final String BASE_URL = "https://zai.cloudtorch.ai/v1/messages";
    private static final String MODEL = "glm-5";
    private static final int TIMEOUT_MS = 120000; // 2 minutes
    private static final int MAX_RETRIES = 3;

    private final String authToken;
    private final ExecutorService executorService;
    private final AIStrategyLogger logger;

    // Trading context that AI should know about
    private String currentSessionPlan;
    private Map<String, Object> tradingRules;

    public AIIntegrationLayer(String authToken, AIStrategyLogger logger) {
        this.authToken = authToken;
        this.logger = logger;
        this.executorService = Executors.newSingleThreadExecutor();
        this.tradingRules = new HashMap<>();
        initializeDefaultRules();
    }

    private void initializeDefaultRules() {
        tradingRules.put("max_trades_per_day", 5);
        tradingRules.put("skip_before_10am", true);
        tradingRules.put("max_spread_ticks", 2);
        tradingRules.put("min_recent_win_rate", 40);
        tradingRules.put("daily_loss_limit", 500);
    }

    /**
     * Set the trading session plan
     */
    public void setSessionPlan(String plan) {
        this.currentSessionPlan = plan;
    }

    /**
     * Evaluate a signal asynchronously
     */
    public CompletableFuture<AIDecision> evaluateSignalAsync(SignalData signal) {
        return CompletableFuture.supplyAsync(() -> evaluateSignal(signal), executorService);
    }

    /**
     * Evaluate a signal synchronously
     */
    public AIDecision evaluateSignal(SignalData signal) {
        try {
            logger.log("🤖 AI evaluating signal: %s @ %d (Score: %d)",
                signal.direction, signal.price, signal.score);

            // Build the prompt
            String prompt = buildPrompt(signal);

            // Call the API
            String response = callAnthropicAPI(prompt);

            // Parse the response
            AIDecision decision = parseDecision(response, signal);

            logger.log("🤖 AI Decision: %s - %s",
                decision.action, decision.reasoning);

            return decision;

        } catch (Exception e) {
            logger.log("❌ AI evaluation failed: %s", e.getMessage());

            // Fallback: Return a conservative SKIP decision
            AIDecision fallback = new AIDecision();
            fallback.action = "SKIP";
            fallback.reasoning = "AI evaluation failed: " + e.getMessage();
            fallback.confidence = 0;
            return fallback;
        }
    }

    /**
     * Build the prompt for the AI
     */
    private String buildPrompt(SignalData signal) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an AI trading assistant. You need to decide whether to TAKE or SKIP a trading signal.\n\n");

        sb.append("CURRENT SIGNAL:\n");
        sb.append(signal.toAIString());
        sb.append("\n");

        sb.append("TRADING PLAN:\n");
        if (currentSessionPlan != null) {
            sb.append(currentSessionPlan).append("\n");
        } else {
            sb.append("- Max 5 trades per day\n");
            sb.append("- Focus on quality over quantity\n");
            sb.append("- Risk 1% per trade, 1:2 R:R ratio\n");
        }

        sb.append("\nYOUR TASK:\n");
        sb.append("Analyze this signal and decide: TAKE or SKIP\n\n");
        sb.append("Consider:\n");
        sb.append("1. Signal quality (score and breakdown)\n");
        sb.append("2. Market conditions (trend, volatility, spread)\n");
        sb.append("3. Pattern performance (historical win rates)\n");
        sb.append("4. Account context (daily P&L, trades today)\n");
        sb.append("5. Risk management (position size, stop loss)\n");
        sb.append("6. Recent performance (momentum, streak)\n\n");

        sb.append("RESPONSE FORMAT (must follow exactly):\n");
        sb.append("ACTION: [TAKE or SKIP]\n");
        sb.append("CONFIDENCE: [0-100]\n");
        sb.append("REASONING: [Your detailed reasoning in 1-2 sentences]\n");

        return sb.toString();
    }

    /**
     * Call the Anthropic API
     */
    private String callAnthropicAPI(String prompt) throws Exception {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRIES) {
            try {
                // Create the request body
                String requestBody = String.format(
                    "{\"model\":\"%s\",\"max_tokens\":500,\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}",
                    MODEL,
                    escapeJson(prompt)
                );

                // Create connection
                URL url = new URL(BASE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", authToken);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                conn.setDoOutput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);

                // Send request
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                // Read response
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                        return response.toString();
                    }
                } else {
                    // Error response
                    String error = "HTTP " + responseCode;
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        throw new Exception(error + " - " + errorResponse.toString());
                    }
                }

            } catch (Exception e) {
                lastException = e;
                retryCount++;
                if (retryCount < MAX_RETRIES) {
                    logger.log("⚠️ API call failed (attempt %d/%d): %s",
                        retryCount, MAX_RETRIES, e.getMessage());
                    Thread.sleep(1000 * retryCount); // Exponential backoff
                }
            }
        }

        throw new Exception("Failed after " + MAX_RETRIES + " retries: " + lastException.getMessage());
    }

    /**
     * Parse the AI decision from the response
     */
    private AIDecision parseDecision(String response, SignalData signal) {
        AIDecision decision = new AIDecision();

        try {
            // Extract the content from the API response
            // The response format is: {"content":[{"text":"..."}]}
            String content = extractContent(response);

            // Parse the decision
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("ACTION:")) {
                    decision.action = line.substring("ACTION:".length()).trim().toUpperCase();
                } else if (line.startsWith("CONFIDENCE:")) {
                    try {
                        decision.confidence = Integer.parseInt(
                            line.substring("CONFIDENCE:".length()).trim());
                    } catch (NumberFormatException e) {
                        decision.confidence = 50; // Default
                    }
                } else if (line.startsWith("REASONING:")) {
                    decision.reasoning = line.substring("REASONING:".length()).trim();
                }
            }

            // Validate action
            if (!decision.action.equals("TAKE") && !decision.action.equals("SKIP")) {
                decision.action = "SKIP"; // Default to safe option
                decision.reasoning = "Invalid action from AI, defaulting to SKIP";
            }

            // Set additional fields if taking the trade
            if (decision.action.equals("TAKE") && signal.risk != null) {
                decision.isLong = "LONG".equals(signal.direction);
                decision.stopLoss = signal.risk.stopLossPrice;
                decision.takeProfit = signal.risk.takeProfitPrice;
                decision.breakEven = signal.risk.breakEvenPrice;
                decision.direction = signal.direction;
            }

        } catch (Exception e) {
            logger.log("⚠️ Failed to parse AI response: %s", e.getMessage());
            decision.action = "SKIP";
            decision.reasoning = "Failed to parse AI response: " + e.getMessage();
            decision.confidence = 0;
        }

        return decision;
    }

    /**
     * Extract content from Anthropic API response
     */
    private String extractContent(String response) {
        try {
            // Simple JSON parsing to extract text content
            int contentStart = response.indexOf("\"content\":");
            if (contentStart == -1) return response;

            int arrayStart = response.indexOf("[", contentStart);
            if (arrayStart == -1) return response;

            int textStart = response.indexOf("\"text\":", arrayStart);
            if (textStart == -1) return response;

            int valueStart = response.indexOf("\"", textStart + 8) + 1;
            if (valueStart == 0) return response;

            int valueEnd = response.indexOf("\"", valueStart);
            if (valueEnd == -1) return response;

            return response.substring(valueStart, valueEnd).replace("\\n", "\n");

        } catch (Exception e) {
            logger.log("⚠️ JSON parsing error: %s", e.getMessage());
            return response;
        }
    }

    /**
     * Escape JSON string
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * AI Decision result
     */
    public static class AIDecision {
        public String action;  // "TAKE" or "SKIP"
        public int confidence;  // 0-100
        public String reasoning;

        // For TAKE actions
        public boolean isLong;
        public String direction;
        public int stopLoss;
        public int takeProfit;
        public int breakEven;

        // NEW: Order execution type (MARKET, STOP_MARKET, LIMIT)
        public String executionType = "MARKET";  // Default to MARKET
        public Integer triggerPrice;  // Price for STOP_MARKET or LIMIT orders (in ticks)
        public String executionReasoning;  // AI's reasoning for order type choice
    }

    /**
     * Logger interface
     */
    public interface AIStrategyLogger {
        void log(String message, Object... args);
    }
}
