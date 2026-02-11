package velox.api.layer1.simplified.demo.storage;

import velox.api.layer1.simplified.demo.memory.HashUtils;
import velox.api.layer1.simplified.demo.memory.MemoryFileEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for file system operations related to trading memory.
 *
 * Provides methods for:
 * - Scanning memory directories for markdown files
 * - Building MemoryFileEntry objects with metadata and hashes
 *
 * Expected directory structure:
 * trading-memory/
 * ├── TRADING_MEMORY.md        (main memory file)
 * ├── patterns/                (pattern documentation)
 * │   └── *.md
 * └── lessons/                 (lessons learned)
 *     └── *.md
 */
public class MemoryFiles {

    /**
     * Scans the memory directory for markdown files.
     *
     * Searches in the following locations:
     * 1. Main memory file: TRADING_MEMORY.md
     * 2. Patterns directory: trading-memory/patterns/
     * 3. Lessons directory: trading-memory/lessons/
     *
     * @param memoryDir Path to the memory directory
     * @return List of absolute file paths (as strings)
     * @throws IOException if file system operations fail
     */
    public static List<String> listMemoryFiles(Path memoryDir) throws IOException {
        List<String> files = new ArrayList<>();

        // Main memory file
        Path mainMemory = memoryDir.resolve("TRADING_MEMORY.md");
        if (Files.exists(mainMemory)) {
            files.add(mainMemory.toString());
        }

        // Pattern files
        Path patternsDir = memoryDir.resolve("patterns");
        if (Files.exists(patternsDir)) {
            try (var stream = Files.walk(patternsDir)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> files.add(p.toString()));
            }
        }

        // Lessons
        Path lessonsDir = memoryDir.resolve("lessons");
        if (Files.exists(lessonsDir)) {
            try (var stream = Files.walk(lessonsDir)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> files.add(p.toString()));
            }
        }

        return files;
    }

    /**
     * Builds a MemoryFileEntry from a file path.
     *
     * Extracts metadata and generates SHA-256 hash for change detection.
     * The relative path is calculated from the workspace directory.
     *
     * @param absPath Absolute path to the file
     * @param workspaceDir Workspace directory (for calculating relative paths)
     * @return MemoryFileEntry with metadata and hash
     * @throws IOException if file cannot be read
     */
    public static MemoryFileEntry buildFileEntry(Path absPath, Path workspaceDir) throws IOException {
        // Calculate relative path and normalize separators
        String path = workspaceDir.relativize(absPath).toString().replace("\\", "/");

        // Extract file metadata
        long mtimeMs = Files.getLastModifiedTime(absPath).toMillis();
        long size = Files.size(absPath);

        // Read content and generate hash
        String content = Files.readString(absPath);
        String hash = HashUtils.sha256(content);

        return new MemoryFileEntry(path, absPath.toString(), mtimeMs, size, hash);
    }

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private MemoryFiles() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
