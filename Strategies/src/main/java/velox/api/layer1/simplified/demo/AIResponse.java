package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.AIDecision;

/**
 * Response from an AI provider containing trading decision.
 * Schema compatible with AI Strategist Mode.
 */
public class AIResponse {

    public AIDecision decision;
    public long latencyMs;
    public String providerName;
    public boolean fromFallback;

    public AIResponse(AIDecision decision, long latencyMs, String providerName, boolean fromFallback) {
        this.decision = decision;
        this.latencyMs = latencyMs;
        this.providerName = providerName;
        this.fromFallback = fromFallback;
    }

    public static AIResponse success(AIDecision decision, long latencyMs, String providerName) {
        return new AIResponse(decision, latencyMs, providerName, false);
    }

    public static AIResponse fromFallback(AIDecision decision, long latencyMs, String providerName) {
        return new AIResponse(decision, latencyMs, providerName, true);
    }
}
