package velox.api.layer1.simplified.demo.memory;

/**
 * Represents a search result from memory or sessions.
 *
 * Returned by semantic search queries, containing relevance score
 * and text snippet for context.
 *
 * Fields:
 * - path: Source file path (relative or absolute)
 * - startLine: Line number where match starts
 * - endLine: Line number where match ends
 * - score: Relevance score (0.0 to 1.0, higher is better)
 * - snippet: Text snippet (max 700 chars)
 * - source: "memory" or "sessions"
 */
public class MemorySearchResult {
    private String path;           // Source file path
    private int startLine;         // Line number
    private int endLine;           // Line number
    private double score;          // Relevance score (0.0 to 1.0)
    private String snippet;        // Text snippet (max 700 chars)
    private String source;         // "memory" or "sessions"

    /**
     * Constructor for MemorySearchResult
     *
     * @param path Source file path
     * @param startLine Line number where match starts
     * @param endLine Line number where match ends
     * @param score Relevance score (0.0 to 1.0)
     * @param snippet Text snippet (max 700 chars)
     * @param source "memory" or "sessions"
     */
    public MemorySearchResult(String path, int startLine, int endLine, double score, String snippet, String source) {
        this.path = path;
        this.startLine = startLine;
        this.endLine = endLine;
        this.score = score;
        this.snippet = snippet;
        this.source = source;
    }

    /**
     * Default constructor
     */
    public MemorySearchResult() {
    }

    // Getters

    public String getPath() {
        return path;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public double getScore() {
        return score;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getSource() {
        return source;
    }

    // Setters

    public void setPath(String path) {
        this.path = path;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "MemorySearchResult{" +
                "path='" + path + '\'' +
                ", startLine=" + startLine +
                ", endLine=" + endLine +
                ", score=" + score +
                ", snippet='" + (snippet != null ? snippet.substring(0, Math.min(50, snippet.length())) : "null") + "..." + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}
