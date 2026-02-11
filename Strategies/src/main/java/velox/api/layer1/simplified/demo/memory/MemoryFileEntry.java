package velox.api.layer1.simplified.demo.memory;

/**
 * Represents a memory file with metadata for caching and change detection.
 *
 * Fields:
 * - path: Relative path from memory directory
 * - absPath: Absolute filesystem path
 * - mtimeMs: Last modified timestamp in milliseconds
 * - size: File size in bytes
 * - hash: SHA-256 hash of file content for change detection
 */
public class MemoryFileEntry {
    private String path;           // Relative path
    private String absPath;        // Absolute filesystem path
    private long mtimeMs;          // Last modified timestamp
    private long size;             // File size in bytes
    private String hash;           // SHA-256 hash

    /**
     * Constructor for MemoryFileEntry
     *
     * @param path Relative path from memory directory
     * @param absPath Absolute filesystem path
     * @param mtimeMs Last modified timestamp in milliseconds
     * @param size File size in bytes
     * @param hash SHA-256 hash of file content
     */
    public MemoryFileEntry(String path, String absPath, long mtimeMs, long size, String hash) {
        this.path = path;
        this.absPath = absPath;
        this.mtimeMs = mtimeMs;
        this.size = size;
        this.hash = hash;
    }

    /**
     * Default constructor
     */
    public MemoryFileEntry() {
    }

    // Getters

    public String getPath() {
        return path;
    }

    public String getAbsPath() {
        return absPath;
    }

    public long getMtimeMs() {
        return mtimeMs;
    }

    public long getSize() {
        return size;
    }

    public String getHash() {
        return hash;
    }

    // Setters

    public void setPath(String path) {
        this.path = path;
    }

    public void setAbsPath(String absPath) {
        this.absPath = absPath;
    }

    public void setMtimeMs(long mtimeMs) {
        this.mtimeMs = mtimeMs;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    @Override
    public String toString() {
        return "MemoryFileEntry{" +
                "path='" + path + '\'' +
                ", absPath='" + absPath + '\'' +
                ", mtimeMs=" + mtimeMs +
                ", size=" + size +
                ", hash='" + hash + '\'' +
                '}';
    }
}
