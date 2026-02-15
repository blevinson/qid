package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradingMemoryService;
import velox.api.layer1.simplified.demo.storage.TranscriptWriter;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Qid - AI Investment Strategist (Decision Mode)
 *
 * Part of the unified Qid AI system. This class handles TAKE/SKIP decisions
 * using memory search and AI analysis.
 *
 * Philosophy: "Intelligence over speed" - NOT an HFT speed daemon
 * Key Innovation: Search memory BEFORE acting, learn from EVERY outcome
 *
 * This class evaluates trading setups by searching memory for similar historical patterns
 * and using AI to make strategic decisions about whether to take or skip a setup.
 *
 * Unified Session: All decisions are logged to TranscriptWriter for shared context with AI chat.
 *
 * @see QidIdentity for unified identity shared with AI Chat
 */
public class AIInvestmentStrategist {
    private final TradingMemoryService memoryService;
    private final String apiToken;
    private final HttpClient httpClient;
    private final Gson gson;
    private final TranscriptWriter transcriptWriter;  // Unified session transcript
    private static final String API_URL = "https://zai.cloudtorch.ai/v1/messages";
    private static final String MODEL = "glm-5";

    // File logging
    private static final String LOG_FILE = System.getProperty("user.home") + "/ai-strategist.log";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Prompt mode: "FULL" or "COMPACT"
    private String promptMode = "COMPACT";

    // ========== LATENCY TRACKING ==========
    // Rolling average of API response times (last 20 calls)
    private final java.util.Queue<Long> latencyHistory = new java.util.LinkedList<>();
    private static final int LATENCY_HISTORY_SIZE = 20;
    private long averageLatencyMs = 5000;  // Default to 5 seconds
    private int latencyCallCount = 0;

    /**
     * Record API latency and update rolling average
     */
    private void recordLatency(long latencyMs) {
        latencyHistory.offer(latencyMs);
        if (latencyHistory.size() > LATENCY_HISTORY_SIZE) {
            latencyHistory.poll();
        }
        // Calculate rolling average
        long sum = 0;
        for (Long l : latencyHistory) {
            sum += l;
        }
        averageLatencyMs = sum / latencyHistory.size();
        latencyCallCount++;
        log("⏱️ API latency: %dms | Rolling avg: %dms (n=%d)".formatted(latencyMs, averageLatencyMs, latencyHistory.size()));
    }

    /**
     * Get estimated API latency in milliseconds
     */
    public long getEstimatedLatencyMs() {
        return averageLatencyMs;
    }

    /**
     * Get latency statistics
     */
    public String getLatencyStats() {
        if (latencyHistory.isEmpty()) {
            return "No data yet";
        }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Long l : latencyHistory) {
            min = Math.min(min, l);
            max = Math.max(max, l);
        }
        return "avg=%dms, min=%dms, max=%dms, n=%d".formatted(averageLatencyMs, min, max, latencyCallCount);
    }

    /**
     * Constructor for AIInvestmentStrategist
     *
     * @param memoryService TradingMemoryService for searching historical patterns
     * @param apiToken API token for Claude API calls
     * @param transcriptWriter Unified session transcript (shared with AI chat)
     */
    public AIInvestmentStrategist(TradingMemoryService memoryService, String apiToken, TranscriptWriter transcriptWriter) {
        this.memoryService = memoryService;
        this.apiToken = apiToken;
        this.transcriptWriter = transcriptWriter;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

        log("========== AI Investment Strategist Initialized ==========");
        if (transcriptWriter != null) {
            log("📝 Session transcript logging: ENABLED");
        }
    }

    /**
     * Set prompt mode: "FULL" or "COMPACT"
     */
    public void setPromptMode(String mode) {
        this.promptMode = "FULL".equalsIgnoreCase(mode) ? "FULL" : "COMPACT";
        log("📊 Prompt mode set to: " + this.promptMode);
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
     * @param devMode If true, AI is more permissive for testing
     * @param callback AIStrategistCallback for async decision
     */
    public void evaluateSetup(SignalData signal, SessionContext sessionContext, boolean devMode, AIStrategistCallback callback) {
        evaluateSetup(signal, sessionContext, null, devMode, callback);
    }

    /**
     * Evaluate a trading signal using AI with memory context and session analysis
     *
     * @param signal SignalData with market context
     * @param sessionContext Session state (can be null)
     * @param sessionAnalysisReport Today's pre-market/phase analysis report (can be null)
     * @param devMode If true, AI should be permissive for testing
     * @param callback AIStrategistCallback for async decision
     */
    public void evaluateSetup(SignalData signal, SessionContext sessionContext, String sessionAnalysisReport, boolean devMode, AIStrategistCallback callback) {
        // Validate input
        if (signal == null || callback == null) {
            if (callback != null) {
                callback.onError("Signal and callback cannot be null");
            }
            return;
        }

        log("========== AI STRATEGIST EVALUATION ==========");
        if (devMode) {
            log("🔧 DEV MODE ENABLED - AI will be permissive for testing");
        }
        double actualPrice = signal.price * signal.pips;  // Convert ticks to actual price
        log("⏰ TIME: " + signal.market.timeOfDay + " ET");  // Bookmap data timestamp (replay-safe)
        log("SIGNAL: " + signal.direction + " @ " + String.format("%.2f", actualPrice) + " | Score: " + signal.score + "/" + signal.threshold);
        log("CVD: " + signal.market.cvd + " (" + signal.market.cvdTrend + ") | Trend: " + signal.market.trend);

        // Log session context
        if (sessionContext != null) {
            log("SESSION: " + sessionContext.toSummary());
        }

        // Log session analysis report inclusion
        if (sessionAnalysisReport != null && !sessionAnalysisReport.isEmpty()) {
            int reportLen = sessionAnalysisReport.length();
            log("📋 SESSION ANALYSIS REPORT: INCLUDED (" + reportLen + " chars)");
            // Log a preview (first 200 chars)
            String preview = sessionAnalysisReport.substring(0, Math.min(200, reportLen));
            log("📋 REPORT PREVIEW: " + preview.replace("\n", " ").trim() + "...");
        } else {
            log("📋 SESSION ANALYSIS REPORT: NOT AVAILABLE");
        }

        // Log key levels for debugging (convert to actual prices)
        log("KEY LEVELS:");
        if (!Double.isNaN(signal.market.vwap) && signal.market.vwap > 0) {
            log("  VWAP: " + String.format("%.2f", signal.market.vwap * signal.pips) + " (" + signal.market.priceVsVwap + ")");
        }
        if (signal.market.pocPrice > 0) {
            log("  POC: " + String.format("%.2f", signal.market.pocPrice * signal.pips));
        }
        if (signal.market.valueAreaLow > 0 && signal.market.valueAreaHigh > 0) {
            log("  Value Area: " + String.format("%.2f", signal.market.valueAreaLow * signal.pips) + " - " + String.format("%.2f", signal.market.valueAreaHigh * signal.pips));
        }
        if (signal.market.domSupportPrice > 0) {
            log("  DOM SUPPORT: " + String.format("%.2f", signal.market.domSupportPrice * signal.pips) + " (" + signal.market.domSupportVolume + " contracts)");
        }
        if (signal.market.domResistancePrice > 0) {
            log("  DOM RESISTANCE: " + String.format("%.2f", signal.market.domResistancePrice * signal.pips) + " (" + signal.market.domResistanceVolume + " contracts)");
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

        // Step 3: Ask AI to make decision (include session analysis report)
        log("📝 Building AI prompt...");
        String prompt = buildAIPrompt(signal, context, sessionContext, sessionAnalysisReport, devMode);
        log("📝 Prompt built (" + prompt.length() + " chars)");

        // Call Claude API
        log("🌐 Calling Claude API...");
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
     * Build AI prompt with signal data, memory context, session context, and session analysis
     * This prompt will be sent to Claude API for decision making
     *
     * @param signal SignalData with market context
     * @param memoryContext Formatted memory search results
     * @param sessionContext Session state (can be null)
     * @param sessionAnalysisReport Today's pre-market/phase analysis (can be null)
     * @param devMode If true, AI should be permissive for testing
     * @return AI prompt string
     */
    private String buildAIPrompt(SignalData signal, String memoryContext, SessionContext sessionContext, String sessionAnalysisReport, boolean devMode) {
        double pips = signal.pips;
        double actualPrice = signal.price * pips;

        // Build session analysis section (truncate if too long)
        String sessionAnalysisSection = "";
        if (sessionAnalysisReport != null && !sessionAnalysisReport.isEmpty()) {
            // Limit to ~2000 chars to avoid bloat
            String truncated = sessionAnalysisReport.length() > 2000
                ? sessionAnalysisReport.substring(0, 2000) + "\n... (truncated)"
                : sessionAnalysisReport;
            sessionAnalysisSection = """

                ═══ TODAY'S SESSION ANALYSIS ═══
                """ + truncated + """


                """;
        }

        // Build key levels (used in both modes)
        StringBuilder keyLevels = new StringBuilder();
        if (!Double.isNaN(signal.market.vwap) && signal.market.vwap > 0) {
            keyLevels.append(String.format("VWAP:%.2f(%s) ", signal.market.vwap * pips, signal.market.priceVsVwap));
        }
        if (signal.market.pocPrice > 0) {
            keyLevels.append(String.format("POC:%.2f ", signal.market.pocPrice * pips));
        }
        if (signal.market.domSupportPrice > 0) {
            keyLevels.append(String.format("Sup:%.2f ", signal.market.domSupportPrice * pips));
        }
        if (signal.market.domResistancePrice > 0) {
            keyLevels.append(String.format("Res:%.2f ", signal.market.domResistancePrice * pips));
        }
        String keyLevelsStr = keyLevels.length() > 0 ? keyLevels.toString().trim() : "N/A";

        // COMPACT mode - minimal prompt
        if ("COMPACT".equals(promptMode)) {
            String devNote = devMode ? " [DEV MODE: be permissive]" : "";
            return String.format("""
                %sSIGNAL: %s %s @ %.2f | Score: %d | CVD: %d (%s) | Trend: %s%s
                KEY LEVELS: %s
                %s
                Decide TAKE or SKIP. Respond with JSON only.
                {"action":"TAKE"|"SKIP","confidence":0.0-1.0,"reasoning":"brief","plan":{"orderType":"BUY"|"SELL","executionType":"MARKET"|"STOP_MARKET"|"LIMIT","entryPrice":%.2f,"triggerPrice":N,"stopLossPrice":N,"takeProfitPrice":N,"executionReasoning":"why this order type"}}
                """,
                sessionAnalysisSection,
                signal.direction, signal.detection.type, actualPrice, signal.score,
                signal.market.cvd, signal.market.cvdTrend, signal.market.trend,
                devNote, keyLevelsStr, memoryContext, actualPrice);
        }

        // FULL mode - detailed prompt
        StringBuilder fullKeyLevels = new StringBuilder();
        if (!Double.isNaN(signal.market.vwap) && signal.market.vwap > 0) {
            fullKeyLevels.append(String.format("- VWAP: %.2f (%s, %.1f ticks away)\n",
                signal.market.vwap * pips, signal.market.priceVsVwap, signal.market.vwapDistanceTicks));
        }
        if (signal.market.pocPrice > 0) {
            fullKeyLevels.append(String.format("- POC: %.2f\n", signal.market.pocPrice * pips));
        }
        if (signal.market.valueAreaLow > 0 && signal.market.valueAreaHigh > 0) {
            fullKeyLevels.append(String.format("- Value Area: %.2f - %.2f\n",
                signal.market.valueAreaLow * pips, signal.market.valueAreaHigh * pips));
        }
        if (signal.market.ema9 > 0) {
            fullKeyLevels.append(String.format("- EMA9: %.2f (%.1f ticks)\n",
                signal.market.ema9 * pips, signal.market.ema9DistanceTicks));
        }
        if (signal.market.ema21 > 0) {
            fullKeyLevels.append(String.format("- EMA21: %.2f (%.1f ticks)\n",
                signal.market.ema21 * pips, signal.market.ema21DistanceTicks));
        }
        if (signal.market.ema50 > 0) {
            fullKeyLevels.append(String.format("- EMA50: %.2f (%.1f ticks)\n",
                signal.market.ema50 * pips, signal.market.ema50DistanceTicks));
        }
        if (signal.market.domSupportPrice > 0) {
            fullKeyLevels.append(String.format("- DOM SUPPORT: %.2f (%d contracts)\n",
                signal.market.domSupportPrice * pips, signal.market.domSupportVolume));
        }
        if (signal.market.domResistancePrice > 0) {
            fullKeyLevels.append(String.format("- DOM RESISTANCE: %.2f (%d contracts)\n",
                signal.market.domResistancePrice * pips, signal.market.domResistanceVolume));
        }
        String fullKeyLevelsStr = fullKeyLevels.length() > 0 ? fullKeyLevels.toString() : "- (calculating...)\n";

        String sessionContextStr = sessionContext != null ? sessionContext.toAIString() : "";
        String thresholdContextStr = signal.thresholds != null ? signal.thresholds.toAIString() : "";

        String devModeContext = "";
        if (devMode) {
            devModeContext = "🔧 DEV MODE: Be permissive, take signals to test execution.\n\n";
        }

        return String.format("""
            %s%s%s

            SIGNAL: %s %s @ %.2f | Score: %d | CVD: %d (%s) | Trend: %s

            KEY LEVELS:
            %s
            %s
            %s

            JSON response: {"action":"TAKE"|"SKIP","confidence":0.0-1.0,"reasoning":"brief","plan":{"orderType":"BUY"|"SELL","executionType":"MARKET"|"STOP_MARKET"|"LIMIT","entryPrice":%.2f,"triggerPrice":N,"stopLossPrice":N,"takeProfitPrice":N,"executionReasoning":"why this order type"}}
            """,
            devModeContext, sessionContextStr,
            signal.direction, signal.detection.type, actualPrice, signal.score,
            signal.market.cvd, signal.market.cvdTrend, signal.market.trend,
            fullKeyLevelsStr, thresholdContextStr, memoryContext, actualPrice);
    }

    /**
     * Call Claude API to get AI decision
     *
     * @param prompt AI prompt string
     * @param callback Callback for decision
     * @param signal Original signal for price reference
     */
    private void callClaudeAPI(String prompt, AIStrategistCallback callback, SignalData signal) {
        log("🌐 Calling Claude API...");
        CompletableFuture.supplyAsync(() -> {
            return callClaudeAPIWithRetry(prompt, signal, 3);  // 3 retries
        }).thenAccept(decision -> {
            log("✅ Processing AI decision...");
            callback.onDecision(decision);
        }).exceptionally(e -> {
            log("❌ API call failed after retries: " + e.getMessage());
            callback.onError(e.getMessage());
            return null;
        });
    }

    /**
     * Call Claude API with retry logic
     */
    private AIDecision callClaudeAPIWithRetry(String prompt, SignalData signal, int maxRetries) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log("📤 API request attempt %d/%d...".formatted(attempt, maxRetries));
                AIDecision decision = callClaudeAPISync(prompt, signal);
                log("📥 API response received");
                return decision;
            } catch (java.net.http.HttpTimeoutException e) {
                log("⏱️ API timeout on attempt %d/%d".formatted(attempt, maxRetries));
                lastException = e;
            } catch (java.net.ConnectException e) {
                log("🔌 Connection failed on attempt %d/%d: %s".formatted(attempt, maxRetries, e.getMessage()));
                lastException = e;
            } catch (Exception e) {
                log("❌ API call exception on attempt %d/%d: %s".formatted(attempt, maxRetries, e.getMessage()));
                lastException = e;
            }

            // Wait before retry (exponential backoff)
            if (attempt < maxRetries) {
                try {
                    long backoffMs = (long) (1000 * Math.pow(2, attempt - 1));  // 1s, 2s, 4s...
                    log("⏳ Waiting %dms before retry...".formatted(backoffMs));
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw new RuntimeException("API call failed after " + maxRetries + " attempts: " +
            (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    /**
     * Synchronous Claude API call
     */
    private AIDecision callClaudeAPISync(String prompt, SignalData signal) throws Exception {
        long startTime = System.currentTimeMillis();

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);
        requestBody.addProperty("max_tokens", 1024);

        // Use signal's threshold in system prompt
        int threshold = signal.threshold;
        int strongThreshold = threshold + 30;
        int moderateThreshold = threshold + 10;

        // Build system prompt based on mode
        String systemPrompt;
        if ("FULL".equals(promptMode)) {
            systemPrompt = QidIdentity.DECISION_PROMPT + String.format("""

                DECISION FRAMEWORK FOR THIS SESSION:
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

                4. Order Execution Type Selection
                   Choose the best execution type based on market context:

                   MARKET - Use when:
                   - Strong momentum with trend confirmation
                   - Time-sensitive opportunity (absorption completing)
                   - Price already at optimal entry level

                   STOP_MARKET - Use when:
                   - Breakout setup near resistance/support
                   - Want confirmation of direction before entry
                   - Signal detected but price hasn't confirmed yet
                   - DOM shows significant level nearby to trigger above/below

                   LIMIT - Use when:
                   - Reversal signal, expecting pullback
                   - Price extended and likely to retrace
                   - Want better entry price than current

                   For STOP_MARKET: triggerPrice should be above current for LONG, below for SHORT
                   For LIMIT: triggerPrice should be below current for LONG, above for SHORT

                Respond ONLY with valid JSON:
                """,
                threshold,
                strongThreshold,
                threshold, strongThreshold,
                threshold);
        } else {
            // COMPACT mode - minimal system prompt with order type guidance
            systemPrompt = String.format("""
                You are Qid, an AI trading strategist. Decide TAKE or SKIP.
                Threshold: %d | Strong: %d+ | Moderate: %d-%d

                ORDER EXECUTION TYPES:
                - MARKET: Immediate execution (strong momentum, time-sensitive)
                - STOP_MARKET: Wait for breakout confirmation (near resistance/support)
                - LIMIT: Wait for better price (reversal, pullback expected)

                Respond ONLY with JSON:
                {"action":"TAKE"|"SKIP","confidence":0.0-1.0,"reasoning":"brief","plan":{"orderType":"BUY"|"SELL","executionType":"MARKET"|"STOP_MARKET"|"LIMIT","entryPrice":%.2f,"triggerPrice":N,"stopLossPrice":N,"takeProfitPrice":N,"executionReasoning":"why this order type"}}
                """,
                threshold, strongThreshold, threshold, strongThreshold, signal.price * signal.pips);
        }

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
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        long elapsed = System.currentTimeMillis() - startTime;
        recordLatency(elapsed);  // Track latency for drift prediction

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
            decision.reasoning = json.has("reasoning") && !json.get("reasoning").isJsonNull() ?
                json.get("reasoning").getAsString() : "No reasoning provided";

            // Parse trade plan
            if (json.has("plan") && !json.get("plan").isJsonNull() && decision.shouldTake) {
                JsonObject planJson = json.getAsJsonObject("plan");
                TradePlan plan = new TradePlan();

                plan.orderType = planJson.has("orderType") && !planJson.get("orderType").isJsonNull() ?
                    planJson.get("orderType").getAsString() : "BUY_STOP";
                plan.entryPrice = planJson.has("entryPrice") && !planJson.get("entryPrice").isJsonNull() ?
                    planJson.get("entryPrice").getAsInt() : signal.price;
                plan.stopLossPrice = planJson.has("stopLossPrice") && !planJson.get("stopLossPrice").isJsonNull() ?
                    planJson.get("stopLossPrice").getAsInt() : signal.price - 30;
                plan.takeProfitPrice = planJson.has("takeProfitPrice") && !planJson.get("takeProfitPrice").isJsonNull() ?
                    planJson.get("takeProfitPrice").getAsInt() : signal.price + 70;
                plan.contracts = planJson.has("contracts") && !planJson.get("contracts").isJsonNull() ?
                    planJson.get("contracts").getAsInt() : 1;

                // Parse NEW order execution type fields
                if (planJson.has("executionType") && !planJson.get("executionType").isJsonNull()) {
                    plan.executionType = OrderExecutionType.fromString(planJson.get("executionType").getAsString());
                }
                if (planJson.has("triggerPrice") && !planJson.get("triggerPrice").isJsonNull()) {
                    plan.triggerPrice = planJson.get("triggerPrice").getAsInt();
                }
                if (planJson.has("executionReasoning") && !planJson.get("executionReasoning").isJsonNull()) {
                    plan.executionReasoning = planJson.get("executionReasoning").getAsString();
                }
                // Also check for "notes" field as fallback for reasoning
                if (plan.executionReasoning == null && planJson.has("notes") && !planJson.get("notes").isJsonNull()) {
                    plan.executionReasoning = planJson.get("notes").getAsString();
                }

                decision.plan = plan;
            } else if (decision.shouldTake) {
                // Create default plan if taking but no plan provided
                decision.plan = createDefaultPlan(signal);
            }

            // Parse threshold adjustment (optional)
            if (json.has("thresholdAdjustment") && !json.get("thresholdAdjustment").isJsonNull()) {
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

                // Parse weight adjustments (optional - more granular control)
                if (adjJson.has("weightAdjustment") && !adjJson.get("weightAdjustment").isJsonNull()) {
                    JsonObject weightJson = adjJson.getAsJsonObject("weightAdjustment");
                    ConfluenceWeights.WeightAdjustment weightAdj = new ConfluenceWeights.WeightAdjustment();

                    if (weightJson.has("icebergMax")) weightAdj.icebergMax = weightJson.get("icebergMax").getAsInt();
                    if (weightJson.has("icebergMultiplier")) weightAdj.icebergMultiplier = weightJson.get("icebergMultiplier").getAsInt();
                    if (weightJson.has("cvdAlignMax")) weightAdj.cvdAlignMax = weightJson.get("cvdAlignMax").getAsInt();
                    if (weightJson.has("cvdDivergePenalty")) weightAdj.cvdDivergePenalty = weightJson.get("cvdDivergePenalty").getAsInt();
                    if (weightJson.has("volumeProfileMax")) weightAdj.volumeProfileMax = weightJson.get("volumeProfileMax").getAsInt();
                    if (weightJson.has("volumeImbalanceMax")) weightAdj.volumeImbalanceMax = weightJson.get("volumeImbalanceMax").getAsInt();
                    if (weightJson.has("emaAlignMax")) weightAdj.emaAlignMax = weightJson.get("emaAlignMax").getAsInt();
                    if (weightJson.has("emaAlignPartial")) weightAdj.emaAlignPartial = weightJson.get("emaAlignPartial").getAsInt();
                    if (weightJson.has("emaDivergePenalty")) weightAdj.emaDivergePenalty = weightJson.get("emaDivergePenalty").getAsInt();
                    if (weightJson.has("emaDivergePartial")) weightAdj.emaDivergePartial = weightJson.get("emaDivergePartial").getAsInt();
                    if (weightJson.has("vwapAlign")) weightAdj.vwapAlign = weightJson.get("vwapAlign").getAsInt();
                    if (weightJson.has("vwapDiverge")) weightAdj.vwapDiverge = weightJson.get("vwapDiverge").getAsInt();
                    if (weightJson.has("timeOfDayMax")) weightAdj.timeOfDayMax = weightJson.get("timeOfDayMax").getAsInt();
                    if (weightJson.has("timeOfDaySecondary")) weightAdj.timeOfDaySecondary = weightJson.get("timeOfDaySecondary").getAsInt();
                    if (weightJson.has("domMax")) weightAdj.domMax = weightJson.get("domMax").getAsInt();
                    if (weightJson.has("reasoning")) weightAdj.reasoning = weightJson.get("reasoning").getAsString();

                    adj.weightAdjustment = weightAdj;
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
            log("Plan: " + decision.plan.orderType + " | ExecType: " + decision.plan.executionType +
                " @ " + decision.plan.entryPrice +
                (decision.plan.triggerPrice != null ? " (trigger: " + decision.plan.triggerPrice + ")" : "") +
                " | SL: " + decision.plan.stopLossPrice + " | TP: " + decision.plan.takeProfitPrice);
            if (decision.plan.executionReasoning != null) {
                log("Exec Reasoning: " + decision.plan.executionReasoning);
            }
        }
        // Log threshold adjustments if any
        if (decision.thresholdAdjustment != null && decision.thresholdAdjustment.hasAdjustments()) {
            log("THRESHOLD ADJUSTMENT: " + decision.thresholdAdjustment.toString());
        }
        log("============================================================");

        // Log to unified session transcript (shared with AI chat)
        if (transcriptWriter != null) {
            String signalId = UUID.randomUUID().toString().substring(0, 8);
            transcriptWriter.logSignalDecision(
                signalId,
                signal.direction,
                signal.price,
                signal.score,
                decisionType,
                decision.confidence,
                decision.reasoning
            );
        }

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

        /** Adjust individual confluence weights (more granular control) */
        public ConfluenceWeights.WeightAdjustment weightAdjustment;

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
                   thresholdMultiplier != null ||
                   (weightAdjustment != null && weightAdjustment.hasAdjustments());
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
            if (weightAdjustment != null) sb.append("weightAdjustment=").append(weightAdjustment).append(" ");
            sb.append("reasoning='").append(reasoning).append("'}");
            return sb.toString();
        }
    }

    /**
     * Order Execution Type enum
     * Defines how an order should be executed
     */
    public enum OrderExecutionType {
        /** Execute immediately at market price */
        MARKET("MARKET", "Immediate execution at market price"),

        /** Wait for price to reach trigger, then execute as market order */
        STOP_MARKET("STOP_MARKET", "Triggers market order when price reaches trigger level"),

        /** Place limit order at trigger price, wait for fill */
        LIMIT("LIMIT", "Limit order at specified price, waits for better entry");

        private final String value;
        private final String description;

        OrderExecutionType(String value, String description) {
            this.value = value;
            this.description = description;
        }

        public String getValue() { return value; }
        public String getDescription() { return description; }

        public static OrderExecutionType fromString(String text) {
            if (text == null) return MARKET;  // Default
            for (OrderExecutionType type : values()) {
                if (type.value.equalsIgnoreCase(text)) {
                    return type;
                }
            }
            return MARKET;  // Default fallback
        }
    }

    /**
     * Trade Plan data class
     * Contains strategic order placement details
     */
    public static class TradePlan {
        /** Order type: "BUY_STOP", "SELL_STOP", "BUY_LIMIT", "SELL_LIMIT" (legacy - direction only) */
        public String orderType;

        /** Entry price for the order (in ticks) */
        public int entryPrice;

        /** Stop loss price (in ticks) */
        public int stopLossPrice;

        /** Take profit price (in ticks) */
        public int takeProfitPrice;

        /** Number of contracts (optional, defaults to 1) */
        public int contracts = 1;

        /** Additional reasoning or notes */
        public String notes;

        // ========== NEW FIELDS FOR ORDER TYPE SELECTION ==========

        /** How to execute the order: MARKET, STOP_MARKET, or LIMIT */
        public OrderExecutionType executionType = OrderExecutionType.MARKET;

        /** Trigger price for STOP_MARKET or LIMIT orders (in ticks) */
        public Integer triggerPrice;

        /** AI's reasoning for choosing this execution type */
        public String executionReasoning;

        /**
         * Get the effective trigger price (for STOP_MARKET and LIMIT orders)
         * Returns entryPrice if triggerPrice is not set
         */
        public int getEffectiveTriggerPrice() {
            return triggerPrice != null ? triggerPrice : entryPrice;
        }

        /**
         * Check if this plan uses a pending order type (requires wait for fill)
         */
        public boolean isPendingOrderType() {
            return executionType == OrderExecutionType.STOP_MARKET ||
                   executionType == OrderExecutionType.LIMIT;
        }

        @Override
        public String toString() {
            return String.format("TradePlan{type=%s, execType=%s, entry=%d, trigger=%s, sl=%d, tp=%d, contracts=%d, reason=%s}",
                orderType, executionType, entryPrice,
                triggerPrice != null ? triggerPrice : "N/A",
                stopLossPrice, takeProfitPrice, contracts,
                executionReasoning != null ? executionReasoning : notes);
        }
    }
}
