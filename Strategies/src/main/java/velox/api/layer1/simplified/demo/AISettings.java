package velox.api.layer1.simplified.demo;

/**
 * Settings for AI provider configuration.
 * Stored in OrderFlowStrategyEnhanced settings.
 */
public class AISettings {

    // Provider preference
    public AIProviderPreference providerPreference = AIProviderPreference.AUTO;

    /**
     * Enum for AI provider selection preferences.
     */
    public enum AIProviderPreference {
        CLAUDE_ONLY,      // Only use Claude
        GROQ_ONLY,       // Only use Groq
        AUTO,             // Automatically select based on signal type
        PREFER_CLAUDE,   // Prefer Claude, fallback to Groq
        PREFER_GROQ      // Prefer Groq, fallback to Claude
    }

    // Fallback settings
    public boolean enableFallback = true;     // Enable secondary provider
    public long fallbackTimeoutMs = 30000;  // 30s timeout
    public int maxRetries = 2;              // Max retries with primary

    // Display settings
    public boolean showLatencyMetrics = true;
    public boolean showCostMetrics = true;
    public boolean showDecisionQuality = true;

    public AISettings() {
        // Default to auto selection with fallback enabled
        this.providerPreference = AIProviderPreference.AUTO;
        this.enableFallback = true;
        this.fallbackTimeoutMs = 30000;
        this.maxRetries = 2;
        this.showLatencyMetrics = true;
        this.showCostMetrics = true;
        this.showDecisionQuality = true;
    }

    /**
     * Checks if fallback is enabled.
     */
    public boolean isFallbackEnabled() {
        return enableFallback && providerPreference != AIProviderPreference.CLAUDE_ONLY
                && providerPreference != AIProviderPreference.GROQ_ONLY;
    }
}
