package velox.api.layer1.simplified.demo.memory;

/**
 * Represents a chunk of text from a memory file.
 *
 * Chunks are created by splitting large markdown files into smaller pieces
 * for embedding and semantic search.
 *
 * Fields:
 * - startLine: 0-based line number where chunk starts
 * - endLine: Inclusive line number where chunk ends
 * - text: Chunk content (markdown text)
 * - hash: SHA-256 hash for caching and deduplication
 */
public class MemoryChunk {
    private int startLine;         // 0-based line number
    private int endLine;           // Inclusive
    private String text;           // Chunk content
    private String hash;           // SHA-256 for caching

    /**
     * Constructor for MemoryChunk
     *
     * @param startLine 0-based line number where chunk starts
     * @param endLine Inclusive line number where chunk ends
     * @param text Chunk content (markdown text)
     * @param hash SHA-256 hash for caching
     */
    public MemoryChunk(int startLine, int endLine, String text, String hash) {
        this.startLine = startLine;
        this.endLine = endLine;
        this.text = text;
        this.hash = hash;
    }

    /**
     * Default constructor
     */
    public MemoryChunk() {
    }

    // Getters

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getText() {
        return text;
    }

    public String getHash() {
        return hash;
    }

    // Setters

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    @Override
    public String toString() {
        return "MemoryChunk{" +
                "startLine=" + startLine +
                ", endLine=" + endLine +
                ", textLength=" + (text != null ? text.length() : 0) +
                ", hash='" + hash + '\'' +
                '}';
    }
}
