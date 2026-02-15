package velox.api.layer1.simplified.demo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Qid - AI Chat Interface (Chat Mode)
 *
 * Part of the unified Qid AI system. This class handles interactive chat
 * with tool calling capabilities for real-time market data.
 *
 * Philosophy: "Intelligence over speed" - NOT an HFT speed daemon
 * Key Innovation: Search memory BEFORE acting, learn from EVERY outcome
 *
 * This service provides:
 * - Interactive chat with Qid
 * - Tool calling for real-time market data
 * - Threshold optimization based on market conditions
 *
 * @see QidIdentity for unified identity shared with AI Investment Strategist
 */
public class AIThresholdService {

    private final String apiKey;
    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiUrl = "https://zai.cloudtorch.ai/v1/messages";

    // AI response cache
    private String lastAnalysis;
    private long lastAnalysisTime;
    private static final long ANALYSIS_CACHE_MS = 5 * 60 * 1000;  // 5 minutes

    public interface ThresholdCallback {
        void onThresholdsCalculated(ThresholdRecommendation recommendation);
        void onError(String errorMessage);
    }

    public static class ThresholdRecommendation {
        public int minConfluenceScore;
        public int icebergMinOrders;
        public int spoofMinSize;
        public int absorptionMinSize;
        public double thresholdMultiplier;
        public String reasoning;
        public String confidence;
        public List<String> factors;

        @Override
        public String toString() {
            return String.format(
                "ThresholdRecommendation{\n" +
                "  minConfluenceScore=%d\n" +
                "  icebergMinOrders=%d\n" +
                "  spoofMinSize=%d\n" +
                "  absorptionMinSize=%d\n" +
                "  thresholdMultiplier=%.2f\n" +
                "  confidence=%s\n" +
                "  reasoning=%s\n" +
                "  factors=%s\n" +
                "}",
                minConfluenceScore, icebergMinOrders, spoofMinSize, absorptionMinSize,
                thresholdMultiplier, confidence, reasoning, factors
            );
        }
    }

    public static class MarketContext {
        public String instrument;
        public double currentPrice;
        public long totalVolume;
        public double cvd;
        public String trend;
        public int emaAlignment;
        public boolean isVolatile;
        public String timeOfDay;
        public int totalSignals;
        public int winningSignals;
        public double winRate;
        public int recentSignals; // Last 10 minutes
        public double avgScore;

        public MarketContext(String instrument) {
            this.instrument = instrument;
        }
    }

    public AIThresholdService(String apiKey) {
        this.apiKey = apiKey;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Calculate optimal thresholds using AI analysis
     */
    public CompletableFuture<ThresholdRecommendation> calculateThresholds(
        MarketContext context,
        String customPrompt
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return calculateThresholdsSync(context, customPrompt);
            } catch (Exception e) {
                throw new RuntimeException("Failed to calculate thresholds: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Synchronous threshold calculation
     */
    private ThresholdRecommendation calculateThresholdsSync(
        MarketContext context,
        String customPrompt
    ) throws Exception {
        // Check cache
        if (lastAnalysis != null && System.currentTimeMillis() - lastAnalysisTime < ANALYSIS_CACHE_MS) {
            return parseRecommendation(lastAnalysis);
        }

        // Build prompt
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, customPrompt);

        // Make API request
        String response = callClaudeAPI(systemPrompt, userPrompt);

        // Cache response
        lastAnalysis = response;
        lastAnalysisTime = System.currentTimeMillis();

        return parseRecommendation(response);
    }

    /**
     * Build system prompt for threshold optimization
     */
    private String buildSystemPrompt() {
        return "You are an expert trading strategy optimizer specializing in order flow analysis.\n" +
            "\n" +
            "Your task is to analyze market conditions and recommend optimal threshold settings for an iceberg/spoof/absorption detection strategy.\n" +
            "\n" +
            "**Confluence Scoring System (max 135 points):**\n" +
            "- Iceberg Detection: 40 points (2 points per order)\n" +
            "- CVD Confirmation: 25 points\n" +
            "- Volume Profile: 20 points\n" +
            "- Volume Imbalance: 10 points\n" +
            "- EMA Alignment: 15 points\n" +
            "- VWAP: 10 points\n" +
            "- Time of Day: 5-10 points\n" +
            "- Size Bonus: 3-5 points\n" +
            "\n" +
            "**Threshold Guidelines:**\n" +
            "1. Min Confluence Score: 60-100 (higher = fewer, higher-quality signals)\n" +
            "2. Iceberg Min Orders: 10-30 (depends on instrument liquidity)\n" +
            "3. Spoof Min Size: 15-50 (higher = fewer false positives)\n" +
            "4. Absorption Min Size: 40-100 (large trades only)\n" +
            "5. Threshold Multiplier: 2.0-4.0 (adjusts sensitivity)\n" +
            "\n" +
            "**Market Regime Considerations:**\n" +
            "- Trending markets: Use lower thresholds (60-70) to catch continuation\n" +
            "- Ranging markets: Use higher thresholds (80-90) for reversals\n" +
            "- Volatile markets: Use higher thresholds (85-100) to avoid noise\n" +
            "- Low volatility: Use moderate thresholds (70-80)\n" +
            "\n" +
            "**Always respond with valid JSON only:**";
    }

    /**
     * Build user prompt with current market context
     */
    private String buildUserPrompt(MarketContext context, String customPrompt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze these market conditions and recommend optimal threshold settings:\n\n");
        prompt.append("**Market Context:**\n");
        prompt.append(String.format("- Instrument: %s\n", context.instrument));
        prompt.append(String.format("- Current Price: %.2f\n", context.currentPrice));
        prompt.append(String.format("- Total Volume: %d\n", context.totalVolume));
        prompt.append(String.format("- CVD: %.0f (%s)\n", context.cvd, context.cvd > 0 ? "bullish" : "bearish"));
        prompt.append(String.format("- Trend: %s\n", context.trend));
        prompt.append(String.format("- EMA Alignment: %d/3\n", context.emaAlignment));
        prompt.append(String.format("- Volatility: %s\n", context.isVolatile ? "HIGH" : "normal"));
        prompt.append(String.format("- Time of Day: %s\n", context.timeOfDay));

        if (context.totalSignals > 0) {
            prompt.append(String.format("\n**Recent Performance:**\n"));
            prompt.append(String.format("- Total Signals: %d\n", context.totalSignals));
            prompt.append(String.format("- Winning Signals: %d\n", context.winningSignals));
            prompt.append(String.format("- Win Rate: %.1f%%\n", context.winRate * 100));
            prompt.append(String.format("- Recent Signals (10min): %d\n", context.recentSignals));
            prompt.append(String.format("- Avg Score: %.1f\n", context.avgScore));

            // Add performance analysis
            if (context.winRate < 0.4) {
                prompt.append("\n⚠️ Win rate is LOW - consider increasing thresholds significantly.\n");
            } else if (context.winRate > 0.7) {
                prompt.append("\n✓ Win rate is GOOD - current thresholds may be optimal or could be lowered slightly.\n");
            }

            if (context.recentSignals > 20) {
                prompt.append("\n⚠️ High signal frequency - may indicate threshold is too low.\n");
            } else if (context.recentSignals < 2) {
                prompt.append("\n⚠️ Low signal frequency - threshold may be too high.\n");
            }
        } else {
            prompt.append("\n**No performance data yet - using market conditions only.**\n");
        }

        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            prompt.append(String.format("\n**User Request:**\n%s\n", customPrompt));
        }

        prompt.append("\n**Provide your recommendation as JSON:**\n");
        prompt.append("""
{
  "minConfluenceScore": <integer 60-100>,
  "icebergMinOrders": <integer 10-30>,
  "spoofMinSize": <integer 15-50>,
  "absorptionMinSize": <integer 40-100>,
  "thresholdMultiplier": <float 2.0-4.0>,
  "confidence": "<HIGH|MEDIUM|LOW>",
  "reasoning": "<detailed explanation in 1-2 sentences>",
  "factors": [
    "<factor 1>",
    "<factor 2>",
    "<factor 3>"
  ]
}
""");

        return prompt.toString();
    }

    /**
     * Call Claude API
     */
    private String callClaudeAPI(String systemPrompt, String userPrompt) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "glm-5");
        requestBody.addProperty("max_tokens", 1024);
        requestBody.addProperty("system", systemPrompt);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", userPrompt);
        requestBody.add("messages", gson.toJsonTree(new Object[]{message}));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

        // Extract content from response
        return jsonResponse.getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
    }

    /**
     * Parse AI recommendation from response
     */
    private ThresholdRecommendation parseRecommendation(String response) {
        try {
            // Extract JSON from markdown code blocks if present
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
            ThresholdRecommendation rec = new ThresholdRecommendation();
            rec.minConfluenceScore = json.get("minConfluenceScore").getAsInt();
            rec.icebergMinOrders = json.get("icebergMinOrders").getAsInt();
            rec.spoofMinSize = json.get("spoofMinSize").getAsInt();
            rec.absorptionMinSize = json.get("absorptionMinSize").getAsInt();
            rec.thresholdMultiplier = json.get("thresholdMultiplier").getAsDouble();
            rec.confidence = json.get("confidence").getAsString();
            rec.reasoning = json.get("reasoning").getAsString();

            // Parse factors array
            rec.factors = new ArrayList<>();
            json.getAsJsonArray("factors").forEach(factor ->
                rec.factors.add(factor.getAsString())
            );

            return rec;
        } catch (Exception e) {
            // Fallback to default values if parsing fails
            ThresholdRecommendation rec = new ThresholdRecommendation();
            rec.minConfluenceScore = 70;
            rec.icebergMinOrders = 20;
            rec.spoofMinSize = 20;
            rec.absorptionMinSize = 50;
            rec.thresholdMultiplier = 3.0;
            rec.confidence = "LOW";
            rec.reasoning = "Failed to parse AI response: " + e.getMessage();
            rec.factors = Arrays.asList("Using default values");
            return rec;
        }
    }

    /**
     * Clear the analysis cache
     */
    public void clearCache() {
        lastAnalysis = null;
        lastAnalysisTime = 0;
    }

    /**
     * Simple chat interface - send a prompt and get raw AI response
     */
    public CompletableFuture<String> chat(String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use unified Qid identity
                String systemPrompt = QidIdentity.CHAT_PROMPT;

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "glm-5");
                requestBody.addProperty("max_tokens", 2048);
                requestBody.addProperty("system", systemPrompt);

                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", userPrompt);
                requestBody.add("messages", gson.toJsonTree(new Object[]{message}));

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new Exception("API call failed: " + response.statusCode() + " " + response.body());
                }

                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

                // Extract content from response
                return jsonResponse.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            } catch (Exception e) {
                throw new RuntimeException("Failed to get AI response: " + e.getMessage(), e);
            }
        });
    }

    // ========== TOOL-ENABLED CHAT ==========

    private AIToolsProvider toolsProvider;

    /**
     * Set the tools provider for function calling
     */
    public void setToolsProvider(AIToolsProvider provider) {
        this.toolsProvider = provider;
    }

    /**
     * Chat with tool support - AI can call functions to get real-time data
     * This implements the Anthropic tool use pattern:
     * 1. Send message with tools available
     * 2. If AI wants to use tools, execute them
     * 3. Send tool results back
     * 4. Repeat until AI gives text response
     */
    public CompletableFuture<String> chatWithTools(String userPrompt) {
        System.out.println("[AI Chat] chatWithTools called, prompt length: " +
            (userPrompt != null ? userPrompt.length() : "null"));

        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture("Error: API key is not configured. Please set AI Auth Token in Settings.");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[AI Chat] Starting chatWithTools...");
                List<JsonObject> conversation = new ArrayList<>();

                // Add user message
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", userPrompt);
                conversation.add(userMessage);

                // Tool call loop (max 3 iterations to prevent long waits)
                for (int iteration = 0; iteration < 3; iteration++) {
                    System.out.println("[AI Chat] Iteration " + iteration + ", calling API...");
                    long startTime = System.currentTimeMillis();

                    JsonObject response = callClaudeWithTools(conversation);

                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.println("[AI Chat] API response received in " + elapsed + "ms");

                    // First, check if there's a text response (prioritize text over more tool calls)
                    String textResponse = extractTextResponse(response);
                    if (textResponse != null && !textResponse.isEmpty() &&
                        !textResponse.equals("No text response from AI") &&
                        !textResponse.startsWith("Error:")) {
                        System.out.println("[AI Chat] Got text response (" + textResponse.length() + " chars), returning");
                        return textResponse;  // Return text if available
                    }

                    // Check if AI wants to use tools
                    if (response.has("tool_use") || hasToolUse(response)) {
                        System.out.println("[AI Chat] AI wants to use tools, executing...");
                        // Execute tools and add results to conversation
                        List<JsonObject> toolResults = executeToolCalls(response);
                        if (!toolResults.isEmpty()) {
                            conversation.add(response);
                            conversation.addAll(toolResults);
                            continue;
                        }
                    }

                    // No text and no tools - return whatever we have
                    System.out.println("[AI Chat] No text and no tools, returning: " +
                        (textResponse != null ? textResponse.substring(0, Math.min(50, textResponse.length())) : "null"));
                    return textResponse;
                }

                // If we hit max iterations, try one more call without tools to get a response
                System.out.println("[AI Chat] Max iterations reached, making final call without tools...");
                return "I've gathered the information but reached the tool call limit. Please ask a more specific question.";

            } catch (java.net.http.HttpTimeoutException e) {
                System.err.println("[AI Chat] HTTP timeout: " + e.getMessage());
                return "The request timed out. The AI service may be slow right now. Please try again with a simpler question.";
            } catch (Exception e) {
                System.err.println("[AI Chat] Exception: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to get AI response: " + e.getMessage(), e);
            }
        }).orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
          .exceptionally(e -> {
              System.err.println("[AI Chat] Overall timeout or error: " + e.getMessage());
              e.printStackTrace();

              // Provide more specific error message
              if (e instanceof java.util.concurrent.TimeoutException) {
                  return "The request timed out (90s limit). The AI service may be slow. Please try again.";
              } else if (e.getCause() != null) {
                  String causeMsg = e.getCause().getMessage();
                  if (causeMsg != null && causeMsg.contains("API call failed")) {
                      return "API Error: " + causeMsg;
                  }
                  return "Error: " + e.getCause().getMessage();
              } else {
                  return "Error: " + e.getMessage();
              }
          });
    }

    private boolean hasToolUse(JsonObject response) {
        if (!response.has("content")) return false;
        try {
            var content = response.getAsJsonArray("content");
            for (var item : content) {
                JsonObject block = item.getAsJsonObject();
                if (block.has("type") && "tool_use".equals(block.get("type").getAsString())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    private JsonObject callClaudeWithTools(List<JsonObject> conversation) throws Exception {
        // Use unified Qid identity (chat mode already includes tool info)
        String systemPrompt = QidIdentity.CHAT_PROMPT;

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "glm-5");
        requestBody.addProperty("max_tokens", 2048);
        requestBody.addProperty("system", systemPrompt);

        // Add tools if available
        if (toolsProvider != null) {
            requestBody.add("tools", gson.fromJson(AIToolsProvider.getToolsJsonArray(), com.google.gson.JsonArray.class));
        }

        // Add conversation
        requestBody.add("messages", gson.toJsonTree(conversation.toArray()));

        String requestBodyStr = requestBody.toString();
        System.out.println("[AI Chat] Request body length: " + requestBodyStr.length() + " chars");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
            .timeout(Duration.ofSeconds(120))  // 2 minutes for tool calls
            .build();

        System.out.println("[AI Chat] Sending API request to: " + apiUrl);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[AI Chat] API response status: " + response.statusCode());

        if (response.statusCode() != 200) {
            System.err.println("[AI Chat] API error response: " + response.body());
            throw new Exception("API call failed: " + response.statusCode() + " " + response.body());
        }

        return gson.fromJson(response.body(), JsonObject.class);
    }

    private List<JsonObject> executeToolCalls(JsonObject response) {
        List<JsonObject> results = new ArrayList<>();

        if (!response.has("content")) return results;

        try {
            var content = response.getAsJsonArray("content");
            for (var item : content) {
                JsonObject block = item.getAsJsonObject();
                if (block.has("type") && "tool_use".equals(block.get("type").getAsString())) {
                    String toolName = block.get("name").getAsString();
                    String toolId = block.has("id") ? block.get("id").getAsString() : "tool_" + System.currentTimeMillis();

                    System.out.println("[AI Chat] Executing tool: " + toolName);

                    Map<String, Object> arguments = new HashMap<>();
                    if (block.has("input")) {
                        JsonObject input = block.getAsJsonObject("input");
                        for (String key : input.keySet()) {
                            arguments.put(key, gson.fromJson(input.get(key), Object.class));
                        }
                    }

                    // Execute tool
                    long toolStart = System.currentTimeMillis();
                    String toolResult = toolsProvider != null ?
                        toolsProvider.executeTool(toolName, arguments) :
                        "{\"error\": \"Tools not configured\"}";
                    System.out.println("[AI Chat] Tool " + toolName + " completed in " + (System.currentTimeMillis() - toolStart) + "ms");

                    // Build tool result message
                    JsonObject toolResultMsg = new JsonObject();
                    toolResultMsg.addProperty("role", "user");

                    JsonObject toolResultContent = new JsonObject();
                    toolResultContent.addProperty("type", "tool_result");
                    toolResultContent.addProperty("tool_use_id", toolId);
                    toolResultContent.addProperty("content", toolResult);

                    toolResultMsg.add("content", gson.toJsonTree(new Object[]{toolResultContent}));
                    results.add(toolResultMsg);
                }
            }
        } catch (Exception e) {
            System.err.println("Error executing tool calls: " + e.getMessage());
        }

        return results;
    }

    private String extractTextResponse(JsonObject response) {
        if (!response.has("content")) {
            return "Error: No content in response";
        }

        try {
            var content = response.getAsJsonArray("content");
            StringBuilder text = new StringBuilder();

            for (var item : content) {
                JsonObject block = item.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString())) {
                    text.append(block.get("text").getAsString());
                }
            }

            return text.length() > 0 ? text.toString() : "No text response from AI";
        } catch (Exception e) {
            return "Error extracting response: " + e.getMessage();
        }
    }
}
