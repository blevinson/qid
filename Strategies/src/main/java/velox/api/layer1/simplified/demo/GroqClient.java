package velox.api.layer1.simplified.demo;

import velox.api.layer1.messages.L1ApiMessage;
import velox.api.layer1.data.InstrumentInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Groq AI client implementing OpenAI-style API.
 * Provides ultra-low latency (~10-50ms) and cost-effective AI inference.
 */
public class GroqClient implements AIProvider {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds = 30;

    // Latency tracking (EMA with 0.9 weight)
    private final AtomicLong lastLatency = new AtomicLong(0);

    public GroqClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String getName() {
        return "groq";
    }

    @Override
    public AIResponse analyzeSignal(Signal signal, MarketContext context) throws AIProviderException {
        long startTime = System.currentTimeMillis();

        try {
            // Build request
            Map<String, Object> requestBody = buildRequest(signal, context);

            // Send to Groq
            String jsonResponse = sendToGroq(requestBody);

            // Parse response
            AIResponse response = parseResponse(jsonResponse);

            long latency = (int)(System.currentTimeMillis() - startTime);
            updateLatency(latency);

            log.info("[Groq] Response in " + latency + "ms: action=" + response.decision.action);
            return response;

        } catch (IOException e) {
            log.error("[Groq] API error", e);
            throw new AIProviderException("Groq request failed", e);
        }
    }

    @Override
    public boolean isHealthy() {
        // Simple health check - try a minimal request
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(Map.of(
                "role", "system",
                "content", "Health check"
            )));

            sendToGroq(requestBody);
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

    private String sendToGroq(Map<String, Object> body) throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + CHAT_COMPLETIONS_ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = client.send(request, Duration.ofSeconds(timeoutSeconds + 5));

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new IOException("Groq API returned " + response.statusCode());
        }
    }

    private Map<String, Object> buildRequest(Signal signal, MarketContext context) {
        Map<String, Object> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", buildPrompt(signal, context)
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.1); // Low temp for deterministic decisions
        body.put("max_tokens", 2000);
        body.put("top_p", 1);

        return body;
    }

    private String buildPrompt(Signal signal, MarketContext context) {
        // Use AI Strategist Mode prompt
        // Note: This needs to be updated to use the full strategist prompt
        // For now, use a simplified version
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a trading strategist. A market signal has been detected.\n\n");

        prompt.append("Signal: ").append(signal.type)
                .append(" at ").append(signal.price)
                .append("\n");

        prompt.append("Market Context:\n");
        prompt.append("- Price: ").append(context.currentPrice).append("\n");
        prompt.append("- Trend: ").append(context.trend).append("\n");

        prompt.append("Decide:\n");
        prompt.append("1. Is this a trading opportunity? (TRADE/WAIT/PASS)\n");
        prompt.append("2. If TRADE, what's the optimal entry strategy?\n");
        prompt.append("3. Provide confidence (0.0-1.0)\n");
        prompt.append("4. Provide reasoning\n");

        prompt.append("Respond with JSON matching AI Strategist Mode schema.\n");

        return prompt.toString();
    }

    private AIResponse parseResponse(String jsonResponse) {
        // Simplified JSON parsing - for now just return a basic response
        // In production, use a proper JSON library

        // Parse response and extract action
        // For now, return a mock response to get the code compiling
        return AIResponse.success(createMockDecision(), 50, "groq");
    }

    private AIDecision createMockDecision() {
        // Mock decision - will be replaced with actual parsing
        AIDecision decision = new AIDecision();
        decision.action = AIDecision.StrategistAction.TRADE;
        decision.confidence = 0.85;
        decision.reasoning = "Mock decision - will be replaced with actual parsing";
        return decision;
    }

    private String writeJson(Map<String, Object> data) {
        // Simple JSON writer (no external deps)
        StringBuilder json = new StringBuilder();
        json.append("{");
        boolean first = true;

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;

            json.append("\"").append(entry.getKey()).append("\":\"");

            Object value = entry.getValue();
            if (value instanceof String) {
                json.append(escapeJson((String) value));
            } else if (value instanceof Integer || value instanceof Double) {
                json.append(value);
            } else if (value instanceof Map) {
                json.append("{");
                boolean mapFirst = true;
                for (Map.Entry<String, Object> mapEntry : ((Map<String, Object>) value).entrySet()) {
                    if (!mapFirst) {
                        json.append(",");
                    }
                    mapFirst = false;

                    Object mapValue = mapEntry.getValue();
                    if (mapValue instanceof String) {
                        json.append("\"").append(mapEntry.getKey()).append("\":\"").append(escapeJson((String) mapValue)).append("\"");
                    }
                }
                json.append("}");
            }

            json.append("}");
        }

        return json.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void updateLatency(long latencyMs) {
        // EMA with 0.9 weight
        long oldLatency = lastLatency.get();
        long newLatency = (oldLatency == 0)
                ? latencyMs
                : (long)(oldLatency * 0.9 + latencyMs * 0.1);
        lastLatency.set(newLatency);
    }
}
