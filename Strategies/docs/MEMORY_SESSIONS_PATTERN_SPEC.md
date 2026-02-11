# Memory & Sessions Pattern Specification for Bookmap Addon

**Document Version:** 1.0
**Date:** 2025-02-11
**Status:** Design Specification
**Branch:** feature/memory-sessions-pattern

---

## Executive Summary

This document specifies how to adapt OpenClaw's memory and session patterns for use in the Bookmap OrderFlow trading strategy addon. The pattern enables persistent AI memory, trading session transcripts, and intelligent context retrieval for enhanced trading decision support.

**Key Benefits:**
- **Persistent Memory**: Trading insights, patterns, and lessons learned are retained across sessions
- **Session Transcripts**: Complete trading conversation history for analysis and learning
- **Semantic Search**: AI-powered retrieval of relevant historical trading context
- **Cross-Session Learning**: AI can reference previous trades, signals, and outcomes

---

## Table of Contents

1. [Pattern Overview](#pattern-overview)
2. [OpenClaw Pattern Analysis](#openclaw-pattern-analysis)
3. [Bookmap Adaptation](#bookmap-adaptation)
4. [Architecture Design](#architecture-design)
5. [Data Structures](#data-structures)
6. [Implementation Plan](#implementation-plan)
7. [Code Examples](#code-examples)
8. [Migration Path](#migration-path)

---

## Pattern Overview

### OpenClaw's Core Pattern

OpenClaw implements a sophisticated memory and session system with these key components:

```
┌─────────────────────────────────────────────────────────────┐
│                     Memory Index Manager                     │
│  - Vector embeddings (sqlite-vec)                            │
│  - Full-text search (FTS5)                                   │
│  - Hybrid search (vector + keyword)                          │
│  - Incremental updates                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                ┌─────────────┴─────────────┐
                │                           │
        ┌───────▼────────┐          ┌──────▼──────┐
        │  Memory Files  │          │   Sessions   │
        │  (Markdown)    │          │  (JSONL)     │
        │  - MEMORY.md   │          │  - Transcripts
        │  - memory/*.md │          │  - Conversations
        └────────────────┘          └─────────────┘
```

### Key Patterns

1. **Dual Source Memory**
   - **Memory Files**: Persistent markdown documentation (MEMORY.md, memory/*.md)
   - **Session Files**: JSONL transcripts of conversations with AI

2. **Indexing Strategy**
   - Chunking with configurable token/overlap
   - Vector embeddings for semantic search
   - FTS for keyword matching
   - Hybrid scoring combines both

3. **Change Detection**
   - File hashing (SHA-256)
   - Watch-based updates (chokidar)
   - Delta-based session updates

4. **Caching**
   - Embedding cache to avoid recomputation
   - LRU eviction when cache exceeds max entries

---

## OpenClaw Pattern Analysis

### 1. Memory Manager Architecture

**File:** `/Users/brant/Projects/openclaw/src/memory/manager.ts`

```typescript
class MemoryIndexManager {
  // Core components
  private db: DatabaseSync;              // SQLite database
  private provider: EmbeddingProvider;   // OpenAI/Gemini/Local
  private vector: {                      // Vector search
    enabled: boolean;
    available: boolean;
    extensionPath?: string;
    dims?: number;
  };
  private fts: {                          // Full-text search
    enabled: boolean;
    available: boolean;
  };

  // State tracking
  private dirty: boolean;                 // Memory files changed
  private sessionsDirty: boolean;         // Session files changed
  private sessionDeltas: Map<string, {    // Session change tracking
    lastSize: number;
    pendingBytes: number;
    pendingMessages: number;
  }>;

  // Main operations
  async search(query: string, opts?): Promise<MemorySearchResult[]>;
  async sync(params?): Promise<void>;
  status(): MemoryIndexStatus;
}
```

**Key Insights:**
- **Dual sources**: "memory" (markdown docs) and "sessions" (JSONL transcripts)
- **Lazy reindex**: Only reindex changed files via hash comparison
- **Safe reindexing**: Uses temporary database + atomic swap
- **Watch-based**: Chokidar for memory files, event listeners for sessions
- **Progress reporting**: Callbacks during sync operations

### 2. Session File Format

**File:** `/Users/brant/Projects/openclaw/src/memory/session-files.ts`

```typescript
// Session file structure (JSONL - one JSON object per line)
{"type": "session", "version": 1, "id": "uuid", "timestamp": "2025-02-11T...", "cwd": "/path"}
{"type": "message", "message": {"role": "user", "content": [{"type": "text", "text": "..."}]}}
{"type": "message", "message": {"role": "assistant", "content": [{"type": "text", "text": "..."}]}}
```

**Key Features:**
- Header row with session metadata
- Message rows with role (user/assistant) and content
- Content supports text + media (images, files)
- Append-only (no mutation of existing lines)

### 3. Memory File Entry Structure

**File:** `/Users/brant/Projects/openclaw/src/memory/internal.ts`

```typescript
type MemoryFileEntry = {
  path: string;        // Relative path (e.g., "MEMORY.md", "memory/patterns.md")
  absPath: string;     // Absolute filesystem path
  mtimeMs: number;     // Last modified timestamp
  size: number;        // File size in bytes
  hash: string;        // SHA-256 hash of content
};

type MemoryChunk = {
  startLine: number;   // 0-based line number
  endLine: number;     // Inclusive
  text: string;        // Chunk content
  hash: string;        // SHA-256 for caching
};
```

### 4. Search Results

**File:** `/Users/brant/Projects/openclaw/src/memory/manager.ts`

```typescript
type MemorySearchResult = {
  path: string;        // Source file path
  startLine: number;   // Line number
  endLine: number;     // Line number
  score: number;       // Relevance score (0-1)
  snippet: string;     // Text snippet (700 chars max)
  source: "memory" | "sessions";
};
```

### 5. Hybrid Search Strategy

**File:** `/Users/brant/Projects/openclaw/src/memory/hybrid.ts`

```typescript
// Combines vector and keyword search
function mergeHybridResults(params: {
  vector: SearchResult[];      // Vector similarity results
  keyword: SearchResult[];     // BM25 keyword results
  vectorWeight: number;        // Typically 0.7
  textWeight: number;          // Typically 0.3
}): SearchResult[] {
  // Reciprocal Rank Fusion (RRF) algorithm
  // Combines rankings from both sources
}
```

**Key Insight:** Hybrid search provides both semantic understanding (vector) and exact matching (keyword).

---

## Bookmap Adaptation

### Environment Mapping

| OpenClaw | Bookmap Addon |
|----------|---------------|
| Node.js/TypeScript | Java 17 |
| SQLite (node:sqlite) | SQLite via JDBC or H2 Database |
| chokidar (file watcher) | Java WatchService |
| OpenAI/Gemini APIs | Already using Claude/z.ai |
| Markdown files | Can use JSON or Markdown |
| Agent-based sessions | Trading strategy sessions |
| Workspace directory | Bookmap strategies directory |

### Use Case Adaptation

**OpenClaw Use Case:**
- Developer asks: "How did we fix the authentication bug last week?"
- System searches MEMORY.md and past conversation transcripts
- Returns relevant code snippets and discussion

**Bookmap Use Case:**
- Trader asks: "What happened the last time BTC showed this pattern?"
- System searches trading memory and session transcripts
- Returns similar historical signals, outcomes, and AI analysis

### Concept Mapping

| OpenClaw Concept | Bookmap Equivalent |
|------------------|-------------------|
| Code workspace | Trading instrument/workspace |
| Memory files (MEMORY.md) | Trading memory file (TRADING_MEMORY.md) |
| Session transcripts (conversations) | Trading session transcripts (AI interactions) |
| Code patterns | Trading patterns (iceberg, spoof, absorption) |
| Function calls | Trading signals |
| Error messages | Signal outcomes (win/loss) |
| Documentation | Trading rules and lessons learned |

---

## Architecture Design

### System Components

```
┌──────────────────────────────────────────────────────────────────┐
│                     Bookmap Strategy Layer                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │           OrderFlowStrategyEnhanced                        │ │
│  │  - onTrade(), onLevel2Data(), etc.                         │ │
│  │  - Signal generation                                       │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Memory & Services Layer                      │
│  ┌──────────────────────┐  ┌────────────────────────────────┐  │
│  │ TradingMemoryService │  │  AIThresholdService (existing)  │  │
│  │  - search()          │  │  - Chat interface               │  │
│  │  - sync()            │  │  - Threshold optimization       │  │
│  │  - getHistory()      │  │  - Already has Claude API       │  │
│  └──────────────────────┘  └────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Storage Layer                               │
│  ┌─────────────────────────┐  ┌──────────────────────────────┐ │
│  │   Trading Index DB      │  │   Session Files              │ │
│  │   (SQLite/H2)           │  │   (JSONL)                    │ │
│  │  - chunks              │  │  - 2025-02-11-BTC_USDT.jsonl │ │
│  │  - embeddings          │  │  - 2025-02-11-ETH_USDT.jsonl  │ │
│  │  - metadata            │  │  - ...                        │ │
│  └─────────────────────────┘  └──────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │   Memory Files (Markdown/JSON)                             │ │
│  │  - TRADING_MEMORY.md                                       │ │
│  │  - lessons/iceberg-detection.md                            │ │
│  │  - patterns/spoof-2025-01-15.md                            │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Directory Structure

```
Strategies/
├── src/main/java/velox/api/layer1/simplified/demo/
│   ├── OrderFlowStrategyEnhanced.java          # Main strategy
│   ├── AIThresholdService.java                 # Existing AI service
│   ├── TradingMemoryService.java               # NEW: Memory manager
│   ├── TradingSessionManager.java              # NEW: Session transcripts
│   ├── embeddings/
│   │   └── EmbeddingProvider.java              # NEW: Embeddings API
│   └── storage/
│       ├── MemoryIndexManager.java             # NEW: Index/sync
│       └── TranscriptWriter.java               # NEW: Session logging
├── trading-memory/                             # NEW: Memory files
│   ├── TRADING_MEMORY.md                       # Main trading knowledge
│   ├── patterns/                               # Pattern library
│   │   ├── iceberg-orders.md
│   │   ├── spoof-detection.md
│   │   └── absorption.md
│   └── lessons/                                # Lessons learned
│       └── 2025/
│           └── 02/
│               └── btc-volatile-signal.md
├── sessions/                                   # NEW: Session transcripts
│   ├── 2025-02-11-BTC_USDT.jsonl
│   └── 2025-02-11-ETH_USDT.jsonl
└── db/                                         # NEW: Index database
    └── trading_memory.db
```

---

## Data Structures

### 1. Memory File Entry

```java
package velox.api.layer1.simplified.demo.storage;

public class MemoryFileEntry {
    private String path;           // Relative: "TRADING_MEMORY.md", "patterns/iceberg.md"
    private String absPath;        // Absolute filesystem path
    private long mtimeMs;          // Last modified timestamp
    private long size;             // File size in bytes
    private String hash;           // SHA-256 hex hash

    // Constructors, getters, setters
}
```

### 2. Memory Chunk

```java
package velox.api.layer1.simplified.demo.storage;

public class MemoryChunk {
    private int startLine;         // 0-based line number
    private int endLine;           // Inclusive line number
    private String text;           // Chunk content
    private String hash;           // SHA-256 for caching

    // Constructors, getters, setters
}
```

### 3. Session Transcript Entry

```java
package velox.api.layer1.simplified.demo.storage;

public class SessionEntry {
    private String type;           // "session", "message", "signal", "outcome"
    private long timestamp;

    // For type="session"
    private String sessionId;
    private String instrument;
    private String version = "1.0";

    // For type="message" or type="signal"
    private String role;           // "user", "assistant", "system"
    private MessageContent content;

    // For type="outcome"
    private SignalOutcome outcome;

    // Nested classes
    public static class MessageContent {
        private String type;       // "text"
        private String text;
    }

    public static class SignalOutcome {
        private String signalId;
        private boolean won;
        private double entryPrice;
        private double exitPrice;
        private double profitLoss;
        private int durationSeconds;
        private String notes;
    }
}
```

### 4. Search Result

```java
package velox.api.layer1.simplified.demo.storage;

public class MemorySearchResult {
    private String path;           // Source file path
    private int startLine;         // Line number
    private int endLine;           // Line number
    private double score;          // Relevance score (0.0 to 1.0)
    private String snippet;        // Text snippet (max 700 chars)
    private String source;         // "memory" or "sessions"

    // Constructors, getters, setters
}
```

### 5. Trading Context (for AI)

```java
package velox.api.layer1.simplified.demo;

public class TradingContext {
    // Instrument info
    private String instrument;
    private double currentPrice;
    private long totalVolume;

    // Pattern detection
    private int icebergOrders;
    private int spoofAttempts;
    private int absorptionEvents;

    // Indicators
    private double cvd;
    private String trend;          // "bullish", "bearish", "neutral"
    private int emaAlignment;      // 0-3 EMAs aligned
    private boolean nearVwap;

    // Recent performance
    private int totalSignals;
    private int winningSignals;
    private double winRate;

    // Session info
    private String sessionDate;
    private long sessionStartTime;

    // Constructors, getters, setters
}
```

---

## Implementation Plan

### Phase 1: Foundation (Week 1)

**Goal:** Basic memory infrastructure

1. **Create Java equivalents of core structures**
   - [ ] `MemoryFileEntry`, `MemoryChunk`, `MemorySearchResult`
   - [ ] `SessionEntry` with JSON serialization
   - [ ] `TradingContext` for AI queries

2. **File system utilities**
   - [ ] `MemoryFiles.listMemoryFiles()` - Find memory files
   - [ ] `MemoryFiles.buildFileEntry()` - Read and hash files
   - [ ] `MemoryFiles.chunkMarkdown()` - Split content into chunks
   - [ ] `HashUtils.sha256()` - Content hashing

3. **Database schema**
   - [ ] Design SQLite/H2 schema
   - [ ] Create `MemoryIndexDatabase` class
   - [ ] Implement connection pooling

**Deliverable:** Can read memory files, chunk them, and store in database

### Phase 2: Embeddings & Search (Week 2)

**Goal:** Semantic search capability

1. **Embedding provider**
   - [ ] Create `EmbeddingProvider` interface
   - [ ] Implement `ClaudeEmbeddingProvider` (using z.ai API)
   - [ ] Add retry logic and caching

2. **Search functionality**
   - [ ] Implement `MemorySearchService.search()`
   - [ ] Add keyword search (using Lucene or H2 FTS)
   - [ ] Hybrid scoring (vector + keyword)

3. **Integration with AIThresholdService**
   - [ ] Enhance AI prompts with memory search results
   - [ ] Add "search memory before answering" mode

**Deliverable:** Can search trading memory semantically

### Phase 3: Session Transcripts (Week 3)

**Goal:** Persistent conversation history

1. **Transcript writer**
   - [ ] `TranscriptWriter` for JSONL output
   - [ ] Log AI chat interactions
   - [ ] Log trading signals
   - [ ] Log signal outcomes

2. **Session management**
   - [ ] `TradingSessionManager` lifecycle
   - [ ] Session start/end events
   - [ ] Per-instrument sessions

3. **Session search**
   - [ ] Index session files in memory database
   - [ ] Search across conversations

**Deliverable:** All AI interactions and signals logged to JSONL

### Phase 4: Enhanced AI Integration (Week 4)

**Goal:** AI uses memory to improve decisions

1. **Context enrichment**
   - [ ] Before generating signals, search for similar patterns
   - [ ] Include historical outcomes in AI prompt
   - [ ] Learn from past mistakes

2. **Memory updates**
   - [ ] Auto-generate memory entries from signal outcomes
   - [ ] "What did we learn?" feature after each session
   - [ ] Manual memory entry via UI

3. **UI enhancements**
   - [ ] Memory search panel
   - [ ] Session history viewer
   - [ ] Memory editor (add/edit patterns)

**Deliverable:** AI learns from past trading sessions

---

## Code Examples

### Example 1: Reading Memory Files

```java
package velox.api.layer1.simplified.demo.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class MemoryFiles {

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

    public static MemoryFileEntry buildFileEntry(Path absPath, Path workspaceDir)
            throws IOException {
        String path = workspaceDir.relativize(absPath).toString()
                                  .replace("\\", "/");
        long mtimeMs = Files.getLastModifiedTime(absPath).toMillis();
        long size = Files.size(absPath);
        String content = Files.readString(absPath);
        String hash = HashUtils.sha256(content);

        return new MemoryFileEntry(path, absPath.toString(), mtimeMs, size, hash);
    }
}
```

### Example 2: Chunking Markdown

```java
package velox.api.layer1.simplified.demo.storage;

import java.util.*;

public class MarkdownChunker {

    private final int maxTokens;
    private final int overlapTokens;
    private final int maxChars;
    private final int overlapChars;

    public MarkdownChunker(int maxTokens, int overlapTokens) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.maxChars = Math.max(32, maxTokens * 4);
        this.overlapChars = Math.max(0, overlapTokens * 4);
    }

    public List<MemoryChunk> chunkMarkdown(String content) {
        String[] lines = content.split("\n");
        if (lines.length == 0) return List.of();

        List<MemoryChunk> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentChars = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineChars = line.length() + 1; // +1 for newline

            if (currentChars + lineChars > maxChars && !current.isEmpty()) {
                // Flush current chunk
                flushChunk(chunks, current, i - current.size());
                carryOverlap(current, currentChars);
                currentChars = current.stream().mapToInt(s -> s.length() + 1).sum();
            }

            current.add(line);
            currentChars += lineChars;
        }

        // Flush final chunk
        if (!current.isEmpty()) {
            flushChunk(chunks, current, lines.length - current.size());
        }

        return chunks;
    }

    private void flushChunk(List<MemoryChunk> chunks, List<String> lines, int startLine) {
        if (lines.isEmpty()) return;
        String text = String.join("\n", lines);
        int endLine = startLine + lines.size() - 1;
        String hash = HashUtils.sha256(text);
        chunks.add(new MemoryChunk(startLine, endLine, text, hash));
    }

    private void carryOverlap(List<String> current, int currentChars) {
        if (overlapChars <= 0 || current.isEmpty()) {
            current.clear();
            return;
        }

        List<String> kept = new ArrayList<>();
        int acc = 0;
        for (int i = current.size() - 1; i >= 0; i--) {
            String line = current.get(i);
            acc += line.length() + 1;
            kept.add(0, line);
            if (acc >= overlapChars) break;
        }
        current.clear();
        current.addAll(kept);
    }
}
```

### Example 3: Embedding Provider

```java
package velox.api.layer1.simplified.demo.embeddings;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class ClaudeEmbeddingProvider {

    private final String apiKey;
    private final String apiUrl;
    private final HttpClient httpClient;
    private final Gson gson;

    // Simple LRU cache for embeddings
    private final Map<String, float[]> cache;
    private static final int MAX_CACHE_SIZE = 1000;

    public ClaudeEmbeddingProvider(String apiKey) {
        this.apiKey = apiKey;
        this.apiUrl = "https://api.z.ai/v1/embeddings"; // Update with actual endpoint
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
        this.cache = new LinkedHashMap<String, float[]>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }

    public float[] embedQuery(String text) throws Exception {
        String hash = HashUtils.sha256(text);
        float[] cached = cache.get(hash);
        if (cached != null) return cached;

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "glm-4.7"); // Or appropriate embedding model
        requestBody.addProperty("input", text);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Embedding request failed: " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        JsonArray data = jsonResponse.getAsJsonArray("data");
        JsonObject embeddingObj = data.get(0).getAsJsonObject();
        JsonArray embeddingArray = embeddingObj.getAsJsonArray("embedding");

        float[] embedding = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            embedding[i] = embeddingArray.get(i).getAsFloat();
        }

        cache.put(hash, embedding);
        return embedding;
    }

    public List<float[]> embedBatch(List<String> texts) throws Exception {
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embedQuery(text));
        }
        return embeddings;
    }
}
```

### Example 4: Writing Session Transcripts

```java
package velox.api.layer1.simplified.demo.storage;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class TranscriptWriter {

    private final Path sessionsDir;
    private final String instrument;
    private final String sessionFile;
    private final Gson gson;
    private final Object lock = new Object();

    public TranscriptWriter(Path sessionsDir, String instrument) {
        this.sessionsDir = sessionsDir;
        this.instrument = instrument;
        this.gson = new Gson();

        // Create session file: YYYY-MM-DD-INSTITUTE.jsonl
        String date = LocalDate.now().toString();
        String safeInstrument = instrument.replace("/", "_");
        this.sessionFile = sessionsDir.resolve(date + "-" + safeInstrument + ".jsonl").toString();
    }

    public void initializeSession() throws IOException {
        Files.createDirectories(sessionsDir);

        // Write session header if file doesn't exist
        if (!Files.exists(Path.of(sessionFile))) {
            SessionEntry header = new SessionEntry();
            header.setType("session");
            header.setTimestamp(System.currentTimeMillis());
            header.setSessionId(UUID.randomUUID().toString());
            header.setInstrument(instrument);
            header.setVersion("1.0");

            writeEntry(header);
        }
    }

    public void logMessage(String role, String text) throws IOException {
        SessionEntry entry = new SessionEntry();
        entry.setType("message");
        entry.setTimestamp(System.currentTimeMillis());
        entry.setRole(role);

        SessionEntry.MessageContent content = new SessionEntry.MessageContent();
        content.setType("text");
        content.setText(text);
        entry.setContent(content);

        writeEntry(entry);
    }

    public void logSignal(String signalId, String signalType, Map<String, Object> details)
            throws IOException {
        // Create a message with signal details
        JsonObject signalJson = new JsonObject();
        signalJson.addProperty("signalId", signalId);
        signalJson.addProperty("type", signalType);
        details.forEach(signalJson::addProperty);

        SessionEntry entry = new SessionEntry();
        entry.setType("signal");
        entry.setTimestamp(System.currentTimeMillis());
        entry.setRole("system");

        SessionEntry.MessageContent content = new SessionEntry.MessageContent();
        content.setType("text");
        content.setText(signalJson.toString());
        entry.setContent(content);

        writeEntry(entry);
    }

    public void logOutcome(String signalId, boolean won, double entryPrice,
                          double exitPrice, double profitLoss, String notes)
            throws IOException {
        SessionEntry entry = new SessionEntry();
        entry.setType("outcome");
        entry.setTimestamp(System.currentTimeMillis());

        SessionEntry.SignalOutcome outcome = new SessionEntry.SignalOutcome();
        outcome.setSignalId(signalId);
        outcome.setWon(won);
        outcome.setEntryPrice(entryPrice);
        outcome.setExitPrice(exitPrice);
        outcome.setProfitLoss(profitLoss);
        outcome.setNotes(notes);
        entry.setOutcome(outcome);

        writeEntry(entry);
    }

    private void writeEntry(SessionEntry entry) throws IOException {
        synchronized (lock) {
            try (FileWriter writer = new FileWriter(sessionFile, true)) {
                String json = gson.toJson(entry);
                writer.write(json + "\n");
            }
        }
    }
}
```

### Example 5: Memory Search Service

```java
package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.*;
import java.util.*;

public class TradingMemoryService {

    private final EmbeddingProvider embeddingProvider;
    private final MemoryIndexDatabase database;
    private final Path memoryDir;

    public TradingMemoryService(EmbeddingProvider embeddingProvider,
                               MemoryIndexDatabase database,
                               Path memoryDir) {
        this.embeddingProvider = embeddingProvider;
        this.database = database;
        this.memoryDir = memoryDir;
    }

    public List<MemorySearchResult> search(String query, int maxResults, double minScore)
            throws Exception {
        // 1. Generate query embedding
        float[] queryEmbedding = embeddingProvider.embedQuery(query);

        // 2. Vector search
        List<MemorySearchResult> vectorResults =
            database.searchVector(queryEmbedding, maxResults * 2);

        // 3. Keyword search (optional, for hybrid)
        List<MemorySearchResult> keywordResults =
            database.searchKeyword(query, maxResults * 2);

        // 4. Merge results (simplified - can use RRF)
        Map<String, MemorySearchResult> merged = new LinkedHashMap<>();
        for (MemorySearchResult result : vectorResults) {
            String key = result.getPath() + ":" + result.getStartLine();
            result.setScore(result.getScore() * 0.7); // Vector weight
            merged.put(key, result);
        }
        for (MemorySearchResult result : keywordResults) {
            String key = result.getPath() + ":" + result.getStartLine();
            MemorySearchResult existing = merged.get(key);
            if (existing != null) {
                // Combine scores
                existing.setScore(existing.getScore() + result.getScore() * 0.3);
            } else {
                result.setScore(result.getScore() * 0.3);
                merged.put(key, result);
            }
        }

        // 5. Filter by minScore and limit
        return merged.values().stream()
            .filter(r -> r.getScore() >= minScore)
            .sorted(Comparator.comparingDouble(MemorySearchResult::getScore).reversed())
            .limit(maxResults)
            .toList();
    }

    public void sync() throws Exception {
        // Find all memory files
        List<String> files = MemoryFiles.listMemoryFiles(memoryDir);

        for (String filePath : files) {
            Path absPath = Path.of(filePath);
            MemoryFileEntry entry = MemoryFiles.buildFileEntry(absPath, memoryDir);

            // Check if file needs reindexing
            if (database.shouldReindex(entry)) {
                // Read content
                String content = Files.readString(absPath);

                // Chunk it
                MarkdownChunker chunker = new MarkdownChunker(500, 50);
                List<MemoryChunk> chunks = chunker.chunkMarkdown(content);

                // Generate embeddings
                List<String> texts = chunks.stream()
                    .map(MemoryChunk::getText)
                    .toList();
                List<float[]> embeddings = embeddingProvider.embedBatch(texts);

                // Store in database
                database.indexFile(entry, chunks, embeddings);
            }
        }
    }
}
```

### Example 6: Enhanced AI Integration

```java
package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.*;
import java.util.*;

public class AIThresholdService {

    private TradingMemoryService memoryService;
    private TranscriptWriter transcriptWriter;

    // Existing method - enhance with memory search
    public ThresholdRecommendation calculateThresholds(MarketContext context,
                                                       String customPrompt) {
        try {
            // 1. Search for similar historical patterns
            String searchQuery = buildSearchQuery(context);
            List<MemorySearchResult> relevantMemory =
                memoryService.search(searchQuery, 5, 0.6);

            // 2. Build prompt with memory context
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("Analyze these market conditions:\n\n");
            promptBuilder.append(formatMarketContext(context));

            if (!relevantMemory.isEmpty()) {
                promptBuilder.append("\n\n**Relevant Historical Patterns:**\n");
                for (MemorySearchResult result : relevantMemory) {
                    promptBuilder.append(String.format(
                        "- From %s (relevance %.2f):\n%s\n\n",
                        result.getPath(),
                        result.getScore(),
                        result.getSnippet()
                    ));
                }
            }

            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                promptBuilder.append("\n**User Request:**\n").append(customPrompt);
            }

            // 3. Log to transcript
            if (transcriptWriter != null) {
                transcriptWriter.logMessage("user", promptBuilder.toString());
            }

            // 4. Call AI API (existing code)
            ThresholdRecommendation recommendation =
                callAIForRecommendation(promptBuilder.toString());

            // 5. Log AI response to transcript
            if (transcriptWriter != null) {
                transcriptWriter.logMessage("assistant",
                    "Recommendation: " + recommendation.toString());
            }

            return recommendation;

        } catch (Exception e) {
            log("Error calculating thresholds with memory: " + e.getMessage());
            // Fall back to non-memory version
            return calculateThresholdsSync(context, customPrompt);
        }
    }

    private String buildSearchQuery(MarketContext context) {
        return String.format(
            "Trading signals %s trend %s CVD %.0f EMA %d/3",
            context.instrument,
            context.trend,
            context.cvd,
            context.emaAlignment
        );
    }
}
```

---

## Migration Path

### Step 1: Add Dependencies (build.gradle)

```gradle
dependencies {
    // Existing dependencies...

    // Database
    implementation 'org.xerial:sqlite-jdbc:3.42.0.0'
    // OR
    implementation 'com.h2database:h2:2.2.224'

    // JSON processing (already have Gson)
    implementation 'com.google.code.gson:gson:2.8.9'

    // WatchService (built into Java 17)
    // No additional dependency needed
}
```

### Step 2: Create Base Structures

Run these tasks in order:
1. Create storage package with data structures
2. Create embeddings package with EmbeddingProvider
3. Create MemoryIndexDatabase with schema
4. Create TradingMemoryService
5. Create TranscriptWriter

### Step 3: Integrate with Existing Code

1. **OrderFlowStrategyEnhanced.java**
   - Add `TradingMemoryService` as a field
   - Initialize in `initialize()` method
   - Call `memoryService.search()` before generating signals
   - Use `TranscriptWriter` to log signals

2. **AIThresholdService.java**
   - Add memory search to `calculateThresholds()`
   - Log all AI interactions to transcripts
   - Add "learn from outcomes" method

### Step 4: Create Initial Memory Files

```
trading-memory/
└── TRADING_MEMORY.md
```

**Example TRADING_MEMORY.md:**

```markdown
# Trading Memory

## Order Flow Patterns

### Iceberg Orders
- **Definition**: Large hidden orders revealed through smaller executions
- **Detection**: 2+ orders at same price within short window
- **Success Rate**: 65% when confirmed by CVD

### Spoof Detection
- **Definition**: Large orders placed but canceled before execution
- **Detection**: Order size > threshold, then canceled
- **Strategy**: Trade in direction of cancellation (momentum)

### Absorption
- **Definition**: Large orders absorbing supply/demand at key levels
- **Detection**: High volume at price without price movement
- **Strategy**: Fade the level (expect reversal)

## Indicator Weights

- **CVD**: 25 points (strong confirmation)
- **Volume Profile**: 20 points (support/resistance)
- **EMA Alignment**: 15 points (trend confirmation)
- **VWAP**: 10 points (mean reversion)

## Lessons Learned

### 2025-02-10: BTC Volatility
- **Issue**: False signals during high volatility
- **Fix**: Increase min confluence score to 80 when volatility > 2x average
- **Result**: Win rate improved from 42% to 68%

### 2025-02-08: ETH Fakeout
- **Issue**: Spoof detection triggered on legitimate institutional flow
- **Fix**: Require spoof size > 3x average order size
- **Result**: Reduced false positives by 40%
```

---

## Future Enhancements

### Phase 5+: Advanced Features

1. **Automatic Learning**
   - Extract patterns from winning trades automatically
   - Generate memory entries from signal outcomes
   - Detect and document recurring patterns

2. **Multi-Instrument Memory**
   - Share patterns across correlated instruments
   - Learn from BTC to trade ETH
   - Cross-instrument pattern recognition

3. **Backtesting Integration**
   - Search memory for similar historical conditions
   - Compare current setup to past outcomes
   - Calculate statistical edge for current signal

4. **Real-Time Collaboration**
   - Share memory across multiple trading instances
   - Collaborative pattern library
   - Community learning (optional)

5. **Advanced Analytics**
   - Pattern performance dashboard
   - Win rate by pattern type
   - Time-of-day analysis
   - Market condition filtering

---

## Conclusion

This specification provides a comprehensive roadmap for implementing OpenClaw's memory and session patterns in the Bookmap trading addon. The key innovation is adapting developer-focused memory tools for trading pattern recognition and learning.

**Key Success Metrics:**
- **Search Latency**: < 500ms for memory queries
- **Index Update Time**: < 5 seconds for incremental updates
- **Storage Growth**: < 100MB per month of active trading
- **AI Improvement**: Measurable increase in win rate after 2+ weeks

**Next Steps:**
1. Review and approve specification
2. Set up development branch
3. Begin Phase 1 implementation
4. Weekly progress reviews

---

## References

- **OpenClaw Source**: `/Users/brant/Projects/openclaw/src/`
- **Bookmap API Docs**: [Bookmap L1 API Documentation](https://bookmap.com/docs/)
- **Claude API**: [z.ai API Reference](https://z.ai/docs)
- **SQLite**: [SQLite JDBC Driver](https://github.com/xerial/sqlite-jdbc)
- **Vector Search**: [sqlite-vec](https://github.com/asg017/sqlite-vec)

---

**Document Status:** Ready for Review
**Next Review Date:** 2025-02-18
