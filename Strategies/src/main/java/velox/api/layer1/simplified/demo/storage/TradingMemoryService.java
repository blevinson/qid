package velox.api.layer1.simplified.demo.storage;

import velox.api.layer1.simplified.demo.memory.MemoryChunk;
import velox.api.layer1.simplified.demo.memory.MemoryFileEntry;
import velox.api.layer1.simplified.demo.memory.MemorySearchResult;
import velox.api.layer1.simplified.demo.memory.embeddings.ClaudeEmbeddingProvider;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trading Memory Service
 * Manages memory files, embeddings, and semantic search
 */
public class TradingMemoryService {
    private final File memoryDir;
    private final ClaudeEmbeddingProvider embeddingProvider;

    // In-memory storage (Phase 2)
    private final List<MemoryChunk> indexedChunks;
    private final Map<String, MemoryFileEntry> indexedFiles;

    // Workspace directory for calculating relative paths
    private final Path workspaceDir;

    /**
     * Constructor for TradingMemoryService
     *
     * @param memoryDir Path to the memory directory
     * @param apiToken API token for Claude embedding service
     * @param workspaceDir Workspace directory for calculating relative paths
     */
    public TradingMemoryService(File memoryDir, String apiToken, Path workspaceDir) {
        this.memoryDir = memoryDir;
        this.embeddingProvider = new ClaudeEmbeddingProvider(apiToken);
        this.indexedChunks = new ArrayList<>();
        this.indexedFiles = new HashMap<>();
        this.workspaceDir = workspaceDir;
    }

    /**
     * Sync memory from disk
     * - Scan memoryDir for .md files
     * - Chunk them with MarkdownChunker
     * - Generate embeddings for each chunk
     * - Store in indexedChunks
     */
    public void sync() {
        log("🔄 Syncing trading memory...");

        // Clear existing index
        indexedChunks.clear();
        indexedFiles.clear();

        try {
            // Scan memory directory
            List<String> filePaths = MemoryFiles.listMemoryFiles(memoryDir.toPath());

            for (String filePath : filePaths) {
                try {
                    Path path = Path.of(filePath);

                    // Build file entry with metadata
                    MemoryFileEntry fileEntry = MemoryFiles.buildFileEntry(path, workspaceDir);

                    // Skip if already indexed
                    if (indexedFiles.containsKey(fileEntry.getHash())) {
                        continue;
                    }

                    // Read file content
                    String content = Files.readString(path);

                    // Chunk content with default parameters (500 tokens, 50 overlap)
                    MarkdownChunker chunker = new MarkdownChunker(500, 50);
                    List<MemoryChunk> chunks = chunker.chunkMarkdown(content);

                    // Store chunks with embeddings generated later during search
                    indexedChunks.addAll(chunks);
                    indexedFiles.put(fileEntry.getHash(), fileEntry);

                    log("   ✅ Indexed: " + fileEntry.getPath() + " (" + chunks.size() + " chunks)");

                } catch (Exception e) {
                    log("   ❌ Error indexing " + filePath + ": " + e.getMessage());
                }
            }

            log("✅ Memory sync complete: " + indexedChunks.size() + " chunks from " + indexedFiles.size() + " files");

        } catch (Exception e) {
            log("   ❌ Error during memory sync: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Semantic search over indexed memory
     * @param query Search query
     * @param maxResults Maximum results to return
     * @return List of MemorySearchResult sorted by relevance
     */
    public List<MemorySearchResult> search(String query, int maxResults) {
        log("🔍 Searching memory for: " + query);

        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingProvider.embedQuery(query);

            // Calculate similarity for each chunk
            List<MemorySearchResult> results = new ArrayList<>();

            for (MemoryChunk chunk : indexedChunks) {
                // Generate chunk embedding on-the-fly (or cache in Phase 3)
                float[] chunkEmbedding = embeddingProvider.embedQuery(chunk.getText());

                // Calculate cosine similarity
                double similarity = embeddingProvider.cosineSimilarity(queryEmbedding, chunkEmbedding);

                // Create result
                MemorySearchResult result = new MemorySearchResult();

                // Generate snippet (max 200 chars for preview, but MemorySearchResult supports up to 700)
                String snippet = chunk.getText();
                if (snippet.length() > 700) {
                    snippet = snippet.substring(0, 700);
                }

                result.setScore(similarity);
                result.setSnippet(snippet);
                result.setStartLine(chunk.getStartLine());
                result.setEndLine(chunk.getEndLine());
                result.setSource("MEMORY");

                // Find source file - use the first indexed file for simplicity
                // In Phase 3, we'll track chunk-to-file mapping more precisely
                if (!indexedFiles.isEmpty()) {
                    MemoryFileEntry file = indexedFiles.values().iterator().next();
                    result.setPath(file.getPath());
                }

                results.add(result);
            }

            // Sort by score (descending)
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            // Return top N
            int resultCount = Math.min(maxResults, results.size());
            List<MemorySearchResult> topResults = results.subList(0, resultCount);

            log("   Found " + topResults.size() + " results");

            return topResults;

        } catch (Exception e) {
            log("   ❌ Error during search: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Clear all indexed memory
     */
    public void clear() {
        indexedChunks.clear();
        indexedFiles.clear();
        embeddingProvider.clearCache();
        log("🗑️ Memory cleared");
    }

    private void log(String message) {
        System.out.println(message);
    }

    // Getters

    /**
     * Get the number of indexed chunks
     * @return Number of chunks currently indexed
     */
    public int getIndexedChunkCount() {
        return indexedChunks.size();
    }

    /**
     * Get the number of indexed files
     * @return Number of files currently indexed
     */
    public int getIndexedFileCount() {
        return indexedFiles.size();
    }

    /**
     * Get the embedding cache size
     * @return Number of embeddings currently cached
     */
    public int getCacheSize() {
        return embeddingProvider.getCacheSize();
    }

    /**
     * Get the memory directory
     * @return Memory directory path
     */
    public File getMemoryDir() {
        return memoryDir;
    }

    /**
     * Get indexed chunks (for testing/debugging)
     * @return List of indexed chunks
     */
    public List<MemoryChunk> getIndexedChunks() {
        return new ArrayList<>(indexedChunks);
    }

    /**
     * Get indexed files (for testing/debugging)
     * @return Map of indexed files by hash
     */
    public Map<String, MemoryFileEntry> getIndexedFiles() {
        return new HashMap<>(indexedFiles);
    }
}
