package velox.api.layer1.simplified.demo;

import velox.api.layer1.messages.Type;

/**
 * Interface for AI providers (Claude, Groq, etc.)
 * Enables dual provider support with automatic fallback.
 */
public interface AIProvider {

    /**
     * Returns the name of this provider (e.g., "claude", "groq")
     */
    String getName();

    /**
     * Analyzes a market signal and returns a trading decision.
     *
     * @param signal The market signal detected
     * @param context Current market context (price, momentum, etc.)
     * @return AI decision with action, strategy, confidence, etc.
     * @throws AIProviderException if the request fails
     */
    AIResponse analyzeSignal(Signal signal, MarketContext context) throws AIProviderException;

    /**
     * Checks if this provider is healthy and responsive.
     * Returns true if the provider is operational.
     */
    boolean isHealthy();

    /**
     * Returns the last measured latency in milliseconds.
     * Used for monitoring and provider selection.
     */
    int getLatency();
}
