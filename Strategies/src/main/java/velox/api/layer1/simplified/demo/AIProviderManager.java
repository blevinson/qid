package velox.api.layer1.simplified.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages multiple AI providers with automatic fallback.
 * Handles provider selection, retries, and latency tracking.
 */
public class AIProviderManager {

    private final AIProvider claudeProvider;
    private final AIProvider groqProvider;
    private final AISettings settings;
    private final Map<String, Long> latencyHistory = new ConcurrentHashMap<>();

    public AIProviderManager(AISettings settings, AIProvider claudeProvider, AIProvider groqProvider) {
        this.settings = settings;
        this.claudeProvider = claudeProvider;
        this.groqProvider = groqProvider;
    }

    /**
     * Analyzes a signal with automatic fallback.
     * Tries primary provider, falls back to secondary if needed.
     */
    public AIResponse analyzeWithFallback(Signal signal, MarketContext context) throws AIProviderException {
        String primaryProvider = determineProvider(signal, context);
        AIProvider provider = selectProvider(primaryProvider);

        try {
            long startTime = System.currentTimeMillis();
            AIResponse response = provider.analyzeSignal(signal, context);
            long latency = (int)(System.currentTimeMillis() - startTime);

            recordLatency(provider.getName(), latency);
            log.info("[AI] Response from " + provider.getName() + " in " + latency + "ms");

            return response;

        } catch (AIProviderException e) {
            log.warn("[AI] Primary provider " + provider.getName() + " failed: " + e.getMessage());

            if (settings.enableFallback) {
                AIProvider fallbackProvider = selectProvider(settings.fallbackProvider);
                try {
                    log.info("[AI] Falling back to " + fallbackProvider.getName());
                    long startTime = System.currentTimeMillis();
                    AIResponse response = fallbackProvider.analyzeSignal(signal, context);
                    long latency = (int)(System.currentTimeMillis() - startTime);

                    recordLatency(fallbackProvider.getName(), latency);
                    log.info("[AI] Fallback response from " + fallbackProvider.getName() + " in " + latency + "ms");

                    return response;

                } catch (AIProviderException fallbackEx) {
                    log.error("[AI] Fallback also failed: " + fallbackEx.getMessage());
                    throw new AIProviderException("All AI providers failed", fallbackEx);
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * Determines which provider to use based on settings and signal characteristics.
     */
    private String determineProvider(Signal signal, MarketContext context) {
        // User preference wins
        if (isUserConfigured()) {
            return settings.providerPreference.name().toLowerCase();
        }

        // Time-sensitive → Groq (for speed)
        if (isTimeSensitive(signal, context)) {
            log.info("[AI] Time-sensitive signal → Groq");
            return "groq";
        }

        // Complex scenarios → Claude (for reasoning)
        if (isComplexScenario(signal, context)) {
            log.info("[AI] Complex scenario → Claude");
            return "claude";
        }

        // Default: user preference
        return settings.providerPreference.name().toLowerCase();
    }

    /**
     * Selects the AI provider based on the provider name.
     */
    private AIProvider selectProvider(String providerName) {
        if ("claude".equalsIgnoreCase(providerName)) {
            return claudeProvider;
        } else if ("groq".equalsIgnoreCase(providerName)) {
            return groqProvider;
        } else {
            log.warn("[AI] Unknown provider: " + providerName + ", using Claude");
            return claudeProvider;
        }
    }

    /**
     * Checks if user has explicitly configured a preference.
     */
    private boolean isUserConfigured() {
        return settings.providerPreference != AISettings.AIProviderPreference.AUTO;
    }

    /**
     * Returns true if the signal requires fast decision making.
     */
    private boolean isTimeSensitive(Signal signal, MarketContext context) {
        // Absorption completion = time critical
        // Breakout confirmation = time critical
        // High momentum = time critical
        return signal.type.equals("ABSORPTION_COMPLETION")
                || signal.type.equals("BREAKOUT_CONFIRMATION")
                || context.momentum > 0.8;
    }

    /**
     * Returns true if the scenario requires deep reasoning.
     */
    private boolean isComplexScenario(Signal signal, MarketContext context) {
        // Confluence analysis requires deep reasoning
        // Multiple conflicting signals require nuance
        // Regime change requires analysis
        return signal.type.equals("CONFLUENCE_ANALYSIS")
                || context.conflictingSignals > 2
                || context.regimeChange;
    }

    /**
     * Records latency for a provider (exponential moving average with alpha=0.9).
     */
    private void recordLatency(String provider, int latencyMs) {
        latencyHistory.compute(provider, (old, newVal) ->
                (old == 0) ? newVal : (long)(old * 0.9 + newVal * 0.1)
        );

        if (latencyHistory.get(provider) != null && latencyHistory.get(provider).size() >= 10) {
            int avgLatency = latencyHistory.get(provider).intValue();
            log.info("[AI] " + provider + " avg latency: " + avgLatency + "ms");
        }
    }

    /**
     * Returns the average latency for a provider.
     */
    public int getAverageLatency(String provider) {
        Long avg = latencyHistory.get(provider);
        return avg != null ? avg.intValue() : 0;
    }

    /**
     * Returns the last provider used.
     */
    public String getLastProvider() {
        // This would need to track which provider was used
        // For now, return null
        return null;
    }
}
