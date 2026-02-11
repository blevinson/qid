package velox.api.layer1.simplified.demo.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for generating SHA-256 hashes.
 *
 * Used for:
 * - File content hashing (for change detection)
 * - Chunk hashing (for caching and deduplication)
 * - Query hashing (for embedding cache keys)
 */
public class HashUtils {

    /**
     * Generates a SHA-256 hash of the input string.
     *
     * @param input String to hash
     * @return Hexadecimal string representing the SHA-256 hash
     * @throws RuntimeException if SHA-256 algorithm is not available
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes Byte array to convert
     * @return Hexadecimal string (lowercase)
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private HashUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
