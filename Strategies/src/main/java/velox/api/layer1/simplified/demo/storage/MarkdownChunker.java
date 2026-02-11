package velox.api.layer1.simplified.demo.storage;

import velox.api.layer1.simplified.demo.memory.HashUtils;
import velox.api.layer1.simplified.demo.memory.MemoryChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits markdown content into chunks for embedding and semantic search.
 *
 * Chunks are created with overlap to preserve context between chunks.
 * This is important for semantic search as concepts may span chunk boundaries.
 *
 * Algorithm:
 * 1. Split content into lines
 * 2. Accumulate lines until max token limit is reached
 * 3. When limit reached, create chunk and carry over overlap lines
 * 4. Repeat until all lines processed
 *
 * Token estimation: 1 token ≈ 4 characters (conservative estimate)
 */
public class MarkdownChunker {
    private final int maxTokens;
    private final int overlapTokens;
    private final int maxChars;
    private final int overlapChars;

    /**
     * Creates a new MarkdownChunker.
     *
     * @param maxTokens Maximum tokens per chunk (e.g., 500)
     * @param overlapTokens Number of tokens to overlap between chunks (e.g., 50)
     */
    public MarkdownChunker(int maxTokens, int overlapTokens) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        // Convert tokens to characters (1 token ≈ 4 characters)
        this.maxChars = Math.max(32, maxTokens * 4);
        this.overlapChars = Math.max(0, overlapTokens * 4);
    }

    /**
     * Splits markdown content into chunks with overlap.
     *
     * Process:
     * 1. Split content into lines
     * 2. Build chunks by accumulating lines
     * 3. When maxChars exceeded, flush chunk and carry over overlap
     * 4. Finalize remaining content as last chunk
     *
     * @param content Markdown content to chunk
     * @return List of MemoryChunk objects with line numbers and hashes
     */
    public List<MemoryChunk> chunkMarkdown(String content) {
        String[] lines = content.split("\n");
        if (lines.length == 0) {
            return List.of();
        }

        List<MemoryChunk> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentChars = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineChars = line.length() + 1; // +1 for newline

            // Check if adding this line would exceed maxChars
            if (currentChars + lineChars > maxChars && !current.isEmpty()) {
                // Flush current chunk
                flushChunk(chunks, current, i - current.size());

                // Carry over overlap for next chunk
                carryOverlap(current, currentChars);
                currentChars = current.stream().mapToInt(s -> s.length() + 1).sum();
            }

            current.add(line);
            currentChars += lineChars;
        }

        // Flush remaining content
        if (!current.isEmpty()) {
            flushChunk(chunks, current, lines.length - current.size());
        }

        return chunks;
    }

    /**
     * Creates a MemoryChunk from accumulated lines and adds to chunk list.
     *
     * @param chunks List to add the chunk to
     * @param lines Lines to include in chunk
     * @param startLine Starting line number (0-based)
     */
    private void flushChunk(List<MemoryChunk> chunks, List<String> lines, int startLine) {
        if (lines.isEmpty()) {
            return;
        }

        String text = String.join("\n", lines);
        int endLine = startLine + lines.size() - 1;
        String hash = HashUtils.sha256(text);

        chunks.add(new MemoryChunk(startLine, endLine, text, hash));
    }

    /**
     * Carries over overlap lines from current chunk to next chunk.
     *
     * Keeps the last N characters worth of lines for context preservation.
     * If no overlap configured, clears the list.
     *
     * @param current Current list of lines (will be modified in place)
     * @param currentChars Current character count (for reference, not modified)
     */
    private void carryOverlap(List<String> current, int currentChars) {
        if (overlapChars <= 0 || current.isEmpty()) {
            current.clear();
            return;
        }

        // Keep lines from the end until we reach overlapChars
        List<String> kept = new ArrayList<>();
        int acc = 0;

        for (int i = current.size() - 1; i >= 0; i--) {
            String line = current.get(i);
            acc += line.length() + 1; // +1 for newline
            kept.add(0, line);
            if (acc >= overlapChars) {
                break;
            }
        }

        current.clear();
        current.addAll(kept);
    }
}
