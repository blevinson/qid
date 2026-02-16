package velox.api.layer1.simplified.demo;

/**
 * Exception thrown when AI provider request fails.
 */
public class AIProviderException extends Exception {

    public AIProviderException(String message) {
        super(message);
    }

    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public AIProviderException(String message, boolean isTimeout) {
        super(message);
    }

    public AIProviderException(String providerName, String reason, Throwable cause) {
        super("[" + providerName + "] " + reason, cause);
    }
}
