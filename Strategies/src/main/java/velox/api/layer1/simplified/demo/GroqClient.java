package velox.api.layer1.simplified.demo;

import velox.api.layer1.messages.Type;
import velox.api.layer1.simplified.demo.AIDecision;
import velox.api.layer1.simplified.demo.AIThresholdService;
import velox.api.layer1.simplified.demo.AIProvider;
import velox.api.layer1.simplified.demo.AIProviderException;
import velox.api.layer1.simplified.demo.AISettings;
import velox.api.layer1.simplified.demo.AIResponse;
import velox.api.layer1.simplified.demo.SignalData;

import com.github.frankleyrocha.groqapi.GroqClient;
import com.github.frankleyrocha.groqapi.GroqCompletion;
import com.github.frankleyrocha.groqapi.GroqMessage;
import com.github.frankleyrocha.groqapi.MessageContent;
import com.github.frankleyrocha.groqapi.GroqMessage.Role;
import com.github.frankleyrocha.groqapi.GroqMessage;
import velox.api.layer1.data.InstrumentInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Groq client wrapper implementing AIProvider interface.
 * Uses groqapi Maven library for Groq API integration.
 * Provides ultra-low latency (~10-50ms) and cost-effective AI inference.
 */
public class GroqClientWrapper implements AIProvider {

    private final GroqClient client;
    private final String modelName;
    private final int timeoutSeconds;

    // Latency tracking (EMA with 0.9 weight)
    private final AtomicLong lastLatency = new AtomicLong(0);

    public GroqClientWrapper(String apiKey, String modelName, AISettings settings) {
        // Initialize Groq client with API key
        this.client = new GroqClient(apiKey);

        // Set model and timeout from settings
        this.modelName = modelName;
        this.timeoutSeconds = settings.fallbackTimeoutMs / 1000;
    }

    @Override
    public String getName() {
        return "groq";
    }

    @Override
    public AIResponse analyzeSignal(Signal signal, MarketContext context) throws AIProviderException {
        long startTime = System.currentTimeMillis();

        try {
            // Build Groq request
            GroqCompletion request = buildRequest(signal, context);

            // Send request
            GroqCompletion response = client.createChatCompletion(request, timeoutSeconds);

            // Extract AI decision
            AIResponse aiResponse = parseResponse(response);

            long latency = (int)(System.currentTimeMillis() - startTime);
            updateLatency(latency);

            log.info("[Groq] Response in " + latency + "ms: action=" + aiResponse.decision.action);
            return aiResponse;

        } catch (IOException e) {
            log.error("[Groq] API error", e);
            throw new AIProviderException("Groq request failed", e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            // Simple health check - minimal request
            GroqMessage healthCheck = GroqMessage.ofSystem("Health check");
            GroqCompletion request = GroqCompletion.builder()
                    .messages(List.of(healthCheck))
                    .model(modelName)
                    .temperature(0.0)
                    .build();

            client.createChatCompletion(request, timeoutSeconds);
            return true;

        } catch (Exception e) {
            log.error("[Groq] Health check failed", e);
            return false;
        }
    }

    @Override
    public int getLatency() {
        long latency = lastLatency.get();
        return latency > 0 ? (int)latency : 0;
    }

    /**
     * Builds a Groq API request for chat completions.
     */
    private GroqCompletion buildRequest(Signal signal, MarketContext context) {
        // Build messages list
        List<GroqMessage> messages = new ArrayList<>();

        // System prompt - provides context for AI Strategist Mode
        String systemPrompt = buildSystemPrompt(signal, context);

        // System message with AI Strategist Mode instructions
        GroqMessage systemMessage = GroqMessage.ofSystem(systemPrompt);
        messages.add(systemMessage);

        // Note: User prompt would go here if needed
        // messages.add(GroqMessage.ofUser(buildUserPrompt(signal, context)));

        return GroqCompletion.builder()
                .messages(messages)
                .model(modelName)
                .temperature(0.1) // Low temp for deterministic decisions
                .maxTokens(2000)
                .build();
    }

    /**
     * Builds system prompt for AI Strategist Mode.
     * Simplified version for initial implementation.
     */
    private String buildSystemPrompt(Signal signal, MarketContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a trading strategist. A market signal has been detected.\n\n");

        prompt.append("📊 SIGNAL INTEL:\n");
        prompt.append("- Type: ").append(signal.type).append("\n");
        prompt.append("- Price: ").append(signal.price).append("\n");
        prompt.append("- Score: ").append(signal.score).append("/100\n");
        prompt.append("- Direction: ").append(signal.direction).append("\n");

        prompt.append("📈 MARKET CONTEXT:\n");
        prompt.append("- Current Price: ").append(context.currentPrice).append("\n");
        prompt.append("- Trend: ").append(context.trend).append("\n");
        prompt.append("- CVD: ").append(context.cvdValue).append(" (").append(context.cvdTrend).append(")\n");
        prompt.append("- Momentum: ").append(String.format("%.2f", context.momentum)).append("\n");
        prompt.append("- Time Since Last: ").append(context.timeSinceLastSignal / 60000).append("s\n\n");

        prompt.append("🎯 YOUR ROLE: Analyze this market intelligence and decide:\n\n");
        prompt.append("1. Is this a trading opportunity? (TRADE/WAIT/PASS)\n");
        prompt.append("2. If TRADE, what's the optimal entry strategy?\n");
        prompt.append("3. Provide confidence (0.0-1.0)\n");
        prompt.append("4. Provide reasoning\n");
        prompt.append("5. Return JSON response matching AI Strategist Mode schema.\n\n");

        prompt.append("⚠️  Important: You are in AI Strategist Mode:\n");
        prompt.append("- Use TRADE/WAIT/PASS for action\n");
        prompt.append("- Use entryIntent (PULLBACK/BREAKOUT/MOMENTUM/FADE)\n");
        prompt.append("- Use order.type (MARKET/LIMIT/STOP_MARKET)\n");
        prompt.append("- Prices are in ticks, not points\n");
        prompt.append("- Use tick-based offsets for stopLoss/takeProfit\n");
        prompt.append("- Provide constraintsUsed envelope\n");
        prompt.append("- For WAIT, provide monitorPlan with conditions\n\n");

        return prompt.toString();
    }

    /**
     * Builds user prompt for AI interaction.
     */
    private String buildUserPrompt(Signal signal, MarketContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this signal and provide trading decision.");
        return prompt.toString();
    }

    /**
     * Parses Groq completion response to AIResponse.
     */
    private AIResponse parseResponse(GroqCompletion response) {
        // Get first (and only) message from AI
        GroqMessage message = response.getChoices().get(0);

        if (message == null) {
            log.warn("[Groq] No message in response");
            throw new AIProviderException("No valid AI decision in response");
        }

        String content = message.getContent();

        // Parse JSON content
        // Note: For now, simplified parsing - will need full JSON library
        AIResponse aiResponse = new AIResponse();
        aiResponse.decision = parseAction(content);
        aiResponse.confidence = parseConfidence(content);
        aiResponse.reasoning = parseReasoning(content);

        return aiResponse;
    }

    private AIDecision.StrategistAction parseAction(String content) {
        // Look for "action": "TRADE" or "WAIT" or "PASS"
        if (content.contains("\"action\"")) {
            int idx = content.indexOf("\"action\"") + 10; // Skip "action": and space
            int end = content.indexOf("\"", idx + 1);
            if (end > 0 && end < content.indexOf("\"", idx + 9)) {
                String action = content.substring(idx + 1, end).trim();
                return AIDecision.StrategistAction.fromString(action);
            }
        }

        // Default to PASS if not found
        return AIDecision.StrategistAction.PASS;
    }

    private double parseConfidence(String content) {
        try {
            // Look for "confidence": 0.XX
            int idx = content.indexOf("confidence") + 13; // Skip "confidence": and space
            int start = idx + 14; // After quote
            if (start < content.length() && start > 0) {
                int end = content.indexOf("\"", start);
                if (end > 0 && end < content.length()) {
                    String numStr = content.substring(start, end).trim();
                    return Double.parseDouble(numStr);
                }
            }
        } catch (Exception e) {
            log.error("[Groq] Failed to extract confidence: " + e.getMessage());
            return 0.5; // Default
        }
    }

    private String parseReasoning(String content) {
        try {
            // Look for "reasoning": "text"
            int idx = content.indexOf("reasoning") + 13; // Skip "reasoning": and space
            int start = idx + 14; // After quote
            if (start < content.length() && start > 0) {
                int end = content.indexOf("\"", start);
                if (end > 0 && end < content.length()) {
                    return content.substring(start + 1, end - 1).trim();
                }
            }
        } catch (Exception e) {
            log.error("[Groq] Failed to extract reasoning: " + e.getMessage());
            return "Unable to extract reasoning";
        }
    }
}
