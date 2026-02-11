# Memory & Sessions Pattern - Phased Implementation Plan

**Document Version:** 1.0
**Date:** 2025-02-11
**Status:** Implementation Plan
**Branch:** feature/memory-sessions-pattern

---

## Overview

This document breaks down the implementation of the **Memory & Sessions Pattern** and **AI Investment Strategizer** into manageable phases. Each phase builds on the previous one, with clear deliverables and success criteria.

**Total Timeline:** 4 weeks
**Approach:** Incremental, testable, non-invasive
**Integration:** Adds intelligence layer to existing OrderFlowStrategyEnhanced

---

## Phase 1: Memory Infrastructure Foundation (Week 1)

**Goal:** Build core memory structures and file I/O

### Deliverables

#### 1.1 Create Package Structure
```
src/main/java/velox/api/layer1/simplified/demo/
├── memory/
│   ├── MemoryFileEntry.java
│   ├── MemoryChunk.java
│   ├── MemorySearchResult.java
│   └── HashUtils.java
├── storage/
│   ├── SessionEntry.java
│   ├── TranscriptWriter.java
│   └── TradingMemoryService.java (stub)
└── embeddings/
    └── EmbeddingProvider.java (interface)
```

#### 1.2 Implement Core Data Structures

**File:** `memory/MemoryFileEntry.java`
```java
package velox.api.layer1.simplified.demo.memory;

public class MemoryFileEntry {
    private String path;           // Relative path
    private String absPath;        // Absolute filesystem path
    private long mtimeMs;          // Last modified timestamp
    private long size;             // File size in bytes
    private String hash;           // SHA-256 hash

    // Constructor, getters, setters
    public MemoryFileEntry(String path, String absPath, long mtimeMs, long size, String hash) {
        this.path = path;
        this.absPath = absPath;
        this.mtimeMs = mtimeMs;
        this.size = size;
        this.hash = hash;
    }

    // Getters and setters...
}
```

**File:** `memory/MemoryChunk.java`
```java
package velox.api.layer1.simplified.demo.memory;

public class MemoryChunk {
    private int startLine;         // 0-based line number
    private int endLine;           // Inclusive
    private String text;           // Chunk content
    private String hash;           // SHA-256 for caching

    // Constructor, getters, setters...
}
```

**File:** `memory/MemorySearchResult.java`
```java
package velox.api.layer1.simplified.demo.memory;

public class MemorySearchResult {
    private String path;           // Source file path
    private int startLine;         // Line number
    private int endLine;           // Line number
    private double score;          // Relevance score (0.0 to 1.0)
    private String snippet;        // Text snippet (max 700 chars)
    private String source;         // "memory" or "sessions"

    // Constructor, getters, setters...
}
```

**File:** `memory/HashUtils.java`
```java
package velox.api.layer1.simplified.demo.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtils {
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
```

#### 1.3 Implement File System Utilities

**File:** `storage/MemoryFiles.java`
```java
package velox.api.layer1.simplified.demo.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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

    public static MemoryFileEntry buildFileEntry(Path absPath, Path workspaceDir) throws IOException {
        String path = workspaceDir.relativize(absPath).toString().replace("\\", "/");
        long mtimeMs = Files.getLastModifiedTime(absPath).toMillis();
        long size = Files.size(absPath);
        String content = Files.readString(absPath);
        String hash = HashUtils.sha256(content);

        return new MemoryFileEntry(path, absPath.toString(), mtimeMs, size, hash);
    }
}
```

#### 1.4 Implement Markdown Chunker

**File:** `storage/MarkdownChunker.java`
```java
package velox.api.layer1.simplified.demo.storage;

import java.util.ArrayList;
import java.util.List;

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
            int lineChars = line.length() + 1;

            if (currentChars + lineChars > maxChars && !current.isEmpty()) {
                flushChunk(chunks, current, i - current.size());
                carryOverlap(current, currentChars);
                currentChars = current.stream().mapToInt(s -> s.length() + 1).sum();
            }

            current.add(line);
            currentChars += lineChars;
        }

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

#### 1.5 Create Trading Memory Directory Structure

**Create directories:**
```bash
cd Strategies
mkdir -p trading-memory/setups
mkdir -p trading-memory/lessons/2025/02
mkdir -p sessions
```

**Create initial memory file:**
```markdown
# Trading Memory

## Getting Started

This file contains trading patterns, lessons learned, and best practices.

### Pattern Documentation

See `setups/` directory for detailed pattern documentation.

### Lessons Learned

See `lessons/` directory for lessons learned from trading sessions.

---
Last updated: 2025-02-11
```

**File:** `trading-memory/setups/bullish-breakout-iceberg.md`
```markdown
# Bullish Breakout with Iceberg Orders

## Pattern Definition
- Iceberg orders on BID (5+ orders at same price)
- CVD trending up (> +1000)
- Price above VWAP
- At least 2/3 EMAs bullish (9, 21, 50)

## Historical Performance
- Total occurrences: 0 (first documented 2025-02-11)
- Win rate: TBD
- Average win: TBD
- Average loss: TBD

## Notes
This pattern is being tracked. Document outcomes here as we trade it.
```

#### 1.6 Integrate with OrderFlowStrategyEnhanced

**Add fields:**
```java
// In OrderFlowStrategyEnhanced.java
private Path memoryDir;
private Path sessionsDir;
private TradingMemoryService memoryService;
```

**Initialize in initialize():**
```java
@Override
public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
    // ... existing code ...

    // Initialize memory directories
    Path strategiesDir = Paths.get(System.getProperty("user.dir"));
    this.memoryDir = strategiesDir.resolve("trading-memory");
    this.sessionsDir = strategiesDir.resolve("sessions");

    try {
        Files.createDirectories(memoryDir);
        Files.createDirectories(sessionsDir);
        log("📁 Memory directories created");
    } catch (IOException e) {
        log("❌ Failed to create memory directories: " + e.getMessage());
    }

    // Create memory service (stub for now)
    this.memoryService = new TradingMemoryService(memoryDir, sessionsDir);
    log("💾 Memory Service initialized (Phase 1 - stub only)");
}
```

### Success Criteria

- ✅ All data structures compile
- ✅ Can read and hash memory files
- ✅ Can chunk markdown content
- ✅ Directory structure created
- ✅ Integration with OrderFlowStrategyEnhanced compiles

### Testing

```bash
# Build
cd Strategies
./gradlew build

# Verify directories created
ls -la trading-memory/
ls -la trading-memory/setups/
ls -la trading-memory/lessons/2025/02/
ls -la sessions/

# Check logs for "Memory Service initialized"
```

---

## Phase 2: Memory Search & Embeddings Integration (Week 2)

**Goal:** Add semantic search using vector embeddings

### Prerequisites

- Phase 1 complete
- Claude API token available (already in AIThresholdService)

### Deliverables

#### 2.1 Implement Embedding Provider

**File:** `embeddings/ClaudeEmbeddingProvider.java`
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

    // LRU cache for embeddings
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

        // Call Claude API for embeddings
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "glm-4.7");
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

#### 2.2 Implement In-Memory Search (Phase 2 - Simplified)

**File:** `storage/TradingMemoryService.java`
```java
package velox.api.layer1.simplified.demo.storage;

import velox.api.layer1.simplified.demo.memory.*;
import velox.api.layer1.simplified.demo.embeddings.ClaudeEmbeddingProvider;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class TradingMemoryService {
    private final Path memoryDir;
    private final Path sessionsDir;
    private final ClaudeEmbeddingProvider embeddingProvider;
    private final MarkdownChunker chunker;

    // In-memory storage for Phase 2 (will upgrade to DB in Phase 4)
    private final List<MemoryChunk> indexedChunks = new ArrayList<>();
    private final Map<String, MemoryFileEntry> indexedFiles = new HashMap<>();

    public TradingMemoryService(Path memoryDir, Path sessionsDir) {
        this.memoryDir = memoryDir;
        this.sessionsDir = sessionsDir;
        this.embeddingProvider = new ClaudeEmbeddingProvider(getApiKey());
        this.chunker = new MarkdownChunker(500, 50);
    }

    private String getApiKey() {
        // Use existing API token from AIThresholdService
        return "8a4f5b950ea142c98746d5a320666414.Yf1MQwtkwfuDbyHw";
    }

    public void sync() throws Exception {
        log("🔄 Syncing memory files...");

        // Find all memory files
        List<String> files = MemoryFiles.listMemoryFiles(memoryDir);
        log("   Found " + files.size() + " memory files");

        for (String filePath : files) {
            Path absPath = Path.of(filePath);
            MemoryFileEntry entry = MemoryFiles.buildFileEntry(absPath, memoryDir);

            // Check if file needs reindexing
            if (shouldReindex(entry)) {
                log("   Indexing: " + entry.getPath());
                indexFile(entry);
            }
        }

        log("✅ Sync complete: " + indexedChunks.size() + " chunks indexed");
    }

    private boolean shouldReindex(MemoryFileEntry entry) {
        MemoryFileEntry existing = indexedFiles.get(entry.getPath());
        return existing == null || !existing.getHash().equals(entry.getHash());
    }

    private void indexFile(MemoryFileEntry entry) throws Exception {
        // Read content
        String content = Files.readString(Path.of(entry.getAbsPath()));

        // Chunk it
        List<MemoryChunk> chunks = chunker.chunkMarkdown(content);

        // Add to index
        for (MemoryChunk chunk : chunks) {
            indexedChunks.add(chunk);
        }

        indexedFiles.put(entry.getPath(), entry);
    }

    public List<MemorySearchResult> search(String query, int maxResults) throws Exception {
        log("🔍 Searching memory: " + query);

        // Generate query embedding
        float[] queryEmbedding = embeddingProvider.embedQuery(query);

        // Simple similarity search (cosine similarity)
        List<MemorySearchResult> results = new ArrayList<>();
        for (MemoryChunk chunk : indexedChunks) {
            float[] chunkEmbedding = embeddingProvider.embedQuery(chunk.getText());
            double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);

            if (similarity > 0.5) {  // Threshold
                MemorySearchResult result = new MemorySearchResult();
                result.setScore(similarity);
                result.setSnippet(chunk.getText().substring(0, Math.min(700, chunk.getText().length())));
                result.setSource("memory");
                results.add(result);
            }
        }

        // Sort by score and limit
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.stream().limit(maxResults).collect(Collectors.toList());
    }

    private double cosineSimilarity(float[] vec1, float[] vec2) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private void log(String message) {
        System.out.println("[TradingMemoryService] " + message);
    }
}
```

#### 2.3 Add Search to OrderFlowStrategyEnhanced

**Update onIcebergDetected():**
```java
private void onIcebergDetected(boolean isBid, int price, int totalSize) {
    int score = calculateConfluenceScore(isBid, price, totalSize);

    if (score >= confluenceThreshold) {
        SignalData signal = createSignalData(isBid, price, totalSize);

        // Phase 2: Search memory before acting
        if (memoryService != null) {
            try {
                String query = String.format(
                    "Bullish iceberg VWAP support %s %s CVD %s",
                    alias, timeOfDay, cvdCalculator.getCVDTrend()
                );

                List<MemorySearchResult> results = memoryService.search(query, 5);

                if (!results.isEmpty()) {
                    log("🧠 Memory search: Found %d similar setups", results.size());
                    for (MemorySearchResult result : results) {
                        log("   Score: %.2f, Snippet: %s",
                            result.getScore(),
                            result.getSnippet().substring(0, Math.min(100, result.getSnippet().length()))
                        );
                    }
                } else {
                    log("🧠 Memory search: No similar setups found");
                }
            } catch (Exception e) {
                log("❌ Memory search failed: " + e.getMessage());
            }
        }

        // ... existing code ...
    }
}
```

### Success Criteria

- ✅ Can generate embeddings for text
- ✅ Can search memory semantically
- ✅ Returns relevant historical context
- ✅ Integration with OrderFlowStrategyEnhanced works

### Testing

```bash
# Build
./gradlew build

# Load in Bookmap
# Trigger iceberg signal
# Check logs for memory search results
# Expected: "🧠 Memory search: Found X similar setups"
```

---

## Phase 3: Strategic Order Placement with AI (Week 3)

**Goal:** Place strategic BUY STOP orders based on memory

### Prerequisites

- Phase 2 complete (memory search working)
- Phase 2 order manager ready (ORDER_FLOW_STRATEGY_README.md)

### Deliverables

#### 3.1 Implement AI Investment Strategist

**File:** `AIInvestmentStrategist.java`
```java
package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.*;
import velox.api.layer1.simplified.demo.memory.*;
import java.util.*;

public class AIInvestmentStrategist {

    private final TradingMemoryService memoryService;
    private final TranscriptWriter transcriptWriter;

    public AIInvestmentStrategist(TradingMemoryService memoryService,
                                 TranscriptWriter transcriptWriter) {
        this.memoryService = memoryService;
        this.transcriptWriter = transcriptWriter;
    }

    public void evaluateSetup(SignalData signal, AIStrategistCallback callback) {
        // Search memory for similar setups
        String query = buildSearchQuery(signal);
        List<MemorySearchResult> results;

        try {
            results = memoryService.search(query, 10);
        } catch (Exception e) {
            callback.onError("Memory search failed: " + e.getMessage());
            return;
        }

        // Analyze results
        AIDecision decision = makeDecision(signal, results);
        callback.onDecision(decision);

        // Log to transcript
        try {
            transcriptWriter.logSetup(
                signal.getDirection(),
                signal.getScore(),
                results.size(),
                decision.confidence,
                decision.reasoning
            );
        } catch (Exception e) {
            // Log error but continue
        }
    }

    private String buildSearchQuery(SignalData signal) {
        return String.format(
            "%s iceberg %s VWAP %s CVD %s",
            signal.getDirection(),
            signal.getMarket().getTrend(),
            signal.getMarket().getPriceVsVwap(),
            signal.getMarket().getCvdTrend()
        );
    }

    private AIDecision makeDecision(SignalData signal, List<MemorySearchResult> results) {
        AIDecision decision = new AIDecision();

        if (results.isEmpty()) {
            decision.shouldTake = false;
            decision.confidence = "LOW";
            decision.reasoning = "No similar setups found in memory";
            return decision;
        }

        // Calculate average score
        double avgScore = results.stream()
            .mapToDouble(r -> r.getScore())
            .average()
            .orElse(0.0);

        // High confidence?
        if (avgScore >= 0.70 && results.size() >= 5) {
            decision.shouldTake = true;
            decision.confidence = "HIGH";
            decision.reasoning = String.format(
                "Found %d similar setups with high relevance (%.2f avg score)",
                results.size(), avgScore
            );

            // Generate trade plan
            decision.plan = generateTradePlan(signal, results);
        } else if (avgScore >= 0.60) {
            decision.shouldTake = true;
            decision.confidence = "MEDIUM";
            decision.reasoning = String.format(
                "Moderate relevance (%.2f avg score), %d setups",
                avgScore, results.size()
            );
            decision.plan = generateTradePlan(signal, results);
        } else {
            decision.shouldTake = false;
            decision.confidence = "LOW";
            decision.reasoning = String.format(
                "Low relevance (%.2f avg score), skipping setup",
                avgScore
            );
        }

        return decision;
    }

    private TradePlan generateTradePlan(SignalData signal, List<MemorySearchResult> results) {
        TradePlan plan = new TradePlan();

        // Strategic entry (BUY STOP, not market)
        double price = signal.getPrice();
        plan.entryPrice = price + 50;  // Above resistance
        plan.orderType = OrderType.BUY_STOP;

        // Calculate SL/TP from historical data (simplified)
        plan.stopLossTicks = 30;  // Will be calculated from memory in Phase 4
        plan.takeProfitTicks = 70; // ~2.3:1 R:R
        plan.stopLossPrice = price - plan.stopLossTicks;
        plan.takeProfitPrice = price + plan.takeProfitTicks;

        plan.contracts = 1;
        plan.reasoning = "Based on " + results.size() + " similar setups";

        return plan;
    }
}

// Supporting classes
class AIDecision {
    boolean shouldTake;
    String confidence;  // HIGH, MEDIUM, LOW
    String reasoning;
    TradePlan plan;
}

interface AIStrategistCallback {
    void onDecision(AIDecision decision);
    void onError(String error);
}
```

#### 3.2 Update OrderFlowStrategyEnhanced

**Add AI field:**
```java
private AIInvestmentStrategist aiStrategist;
```

**Initialize in initialize():**
```java
// After memoryService initialization
this.transcriptWriter = new TranscriptWriter(sessionsDir, alias);
this.aiStrategist = new AIInvestmentStrategist(memoryService, transcriptWriter);
log("🤖 AI Investment Strategist initialized");
```

**Update onIcebergDetected():**
```java
// Phase 3: Ask AI before trading
if (aiStrategist != null) {
    aiStrategist.evaluateSetup(signal, new AIStrategistCallback() {
        @Override
        public void onDecision(AIDecision decision) {
            if (decision.shouldTake) {
                log("✅ AI: TAKE THIS SETUP - %s", decision.reasoning);
                log("   Confidence: %s", decision.confidence);

                // Place strategic order
                if (decision.plan != null) {
                    placeStrategicOrder(decision.plan);
                }
            } else {
                log("⛔ AI: SKIP - %s", decision.reasoning);
            }
        }

        @Override
        public void onError(String error) {
            log("❌ AI Error: %s", error);
        }
    });
}
```

### Success Criteria

- ✅ AI evaluates setups before trading
- ✅ Returns TAKE/SKIP decisions with reasoning
- ✅ Places strategic BUY STOP orders
- ✅ Logs all decisions to transcripts

### Testing

```bash
# Build
./gradlew build

# Load in Bookmap
# Trigger signals
# Verify AI decisions in logs
# Expected: "✅ AI: TAKE THIS SETUP" or "⛔ AI: SKIP"
```

---

## Phase 4: Continuous Learning & Transcripts (Week 4)

**Goal:** Learn from outcomes and improve future decisions

### Prerequisites

- Phase 3 complete (AI making decisions)
- At least 5 trades completed

### Deliverables

#### 4.1 Implement Complete Transcript Writer

**File:** `storage/TranscriptWriter.java`
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

        String date = LocalDate.now().toString();
        String safeInstrument = instrument.replace("/", "_");
        this.sessionFile = sessionsDir.resolve(date + "-" + safeInstrument + ".jsonl").toString();
    }

    public void initializeSession() throws IOException {
        Files.createDirectories(sessionsDir);

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

    public void logSetup(String direction, int score, int similarSetups,
                        double confidence, String reasoning) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        data.addProperty("score", score);
        data.addProperty("similarSetups", similarSetups);
        data.addProperty("confidence", confidence);
        data.addProperty("reasoning", reasoning);

        writeMessage("system", "Setup detected: " + data.toString());
    }

    public void logOrderPlaced(String orderId, double entry, double sl, double tp,
                               String reasoning) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("orderId", orderId);
        data.addProperty("entry", entry);
        data.addProperty("stopLoss", sl);
        data.addProperty("takeProfit", tp);
        data.addProperty("reasoning", reasoning);

        writeMessage("system", "Order placed: " + data.toString());
    }

    public void logOutcome(String orderId, boolean won, double pnl) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("orderId", orderId);
        data.addProperty("won", won);
        data.addProperty("pnl", pnl);

        writeMessage("system", "Outcome: " + data.toString());

        // Learn from outcome
        String lesson = String.format(
            "Setup %s. P&L: $%.2f. %s",
            won ? "WON" : "LOST",
            pnl,
            won ? "Entry strategy working well." : "Consider adjusting entry parameters."
        );

        writeMessage("lesson", lesson);
    }

    private void writeMessage(String role, String text) throws IOException {
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

#### 4.2 Add Learning to OrderFlowStrategyEnhanced

**Track trades:**
```java
private final Map<String, TradeContext> activeTrades = new ConcurrentHashMap<>();
```

**On order filled:**
```java
private void onOrderFilled(String orderId, double fillPrice) {
    TradeContext ctx = new TradeContext();
    ctx.orderId = orderId;
    ctx.entryPrice = fillPrice;
    ctx.entryTime = System.currentTimeMillis();
    activeTrades.put(orderId, ctx);

    log("📊 Position opened: %s @ %.2f", orderId, fillPrice);
}
```

**On position closed:**
```java
private void onPositionClosed(String orderId, double exitPrice, boolean won) {
    TradeContext ctx = activeTrades.get(orderId);
    if (ctx == null) return;

    double pnl = won ?
        (exitPrice - ctx.entryPrice) * 100 :
        (ctx.entryPrice - exitPrice) * 100;

    log("📊 Position closed: %s, P&L: $%.2f", orderId, pnl);

    // Log to transcript
    if (transcriptWriter != null) {
        try {
            transcriptWriter.logOutcome(orderId, won, pnl);
            log("📝 Outcome logged to transcript");
        } catch (Exception e) {
            log("❌ Failed to log outcome: " + e.getMessage());
        }
    }

    // Learn from outcome
    if (memoryService != null) {
        learnFromOutcome(ctx, won, pnl);
    }

    activeTrades.remove(orderId);
}

private void learnFromOutcome(TradeContext ctx, boolean won, double pnl) {
    String lesson;
    if (won) {
        lesson = String.format(
            "✅ Setup WON. Entry strategy: BUY STOP above resistance. " +
            "Duration: %d min. P&L: $%.2f. " +
            "Key insight: Strategic entry (wait for confirmation) working well.",
            (System.currentTimeMillis() - ctx.entryTime) / 60000,
            pnl
        );
    } else {
        lesson = String.format(
            "❌ Setup LOST. Entry: %.2f, P&L: -$%.2f. " +
            "Possible causes: Premature entry, SL too tight, or wrong market conditions. " +
            "Consider: Wider SL or wait for stronger confirmation.",
            ctx.entryPrice,
            Math.abs(pnl)
        );
    }

    // Add to memory (Phase 4: write to lessons file)
    log("🧠 LESSON: " + lesson);

    try {
        // Write to lessons directory
        String date = LocalDate.now().toString();
        Path lessonFile = memoryDir.resolve("lessons/2025/02/" +
            date + "-" + ctx.orderId.substring(0, 8) + ".md");

        String lessonContent = String.format(
            "# Trading Lesson - %s\n\n" +
            "## Setup\n" +
            "- Order ID: %s\n" +
            "- Direction: %s\n" +
            "- Entry: %.2f\n\n" +
            "## Outcome\n" +
            "- Result: %s\n" +
            "- P&L: $%.2f\n\n" +
            "## Lesson\n\n%s\n\n" +
            "---\n" +
            "*Generated: %s*",
            date,
            ctx.orderId,
            "LONG", // or get from context
            ctx.entryPrice,
            won ? "WON ✅" : "LOST ❌",
            pnl,
            lesson,
            LocalDateTime.now()
        );

        Files.writeString(lessonFile, lessonContent);
        log("📝 Lesson saved: " + lessonFile);
    } catch (Exception e) {
        log("❌ Failed to save lesson: " + e.getMessage());
    }
}
```

### Success Criteria

- ✅ All trading events logged to transcripts
- ✅ Lessons learned automatically written to memory
- ✅ Memory updated with outcomes
- ✅ Can review session history

### Testing

```bash
# Build
./gradlew build

# Load in Bookmap
# Complete 5+ trades
# Check sessions/ directory for JSONL files
# Check trading-memory/lessons/ for markdown lessons
# Verify lessons contain learned insights
```

---

## Summary: Phased Rollout

### Week 1: Foundation
**Deliverable:** Can read, hash, and chunk memory files
**Integration:** Non-invasive (new package only)

### Week 2: Search
**Deliverable:** Semantic search with embeddings
**Integration:** Adds memory search to existing signals

### Week 3: AI Decisions
**Deliverable:** AI evaluates setups and places strategic orders
**Integration:** Replaces immediate execution with intelligent decisions

### Week 4: Learning
**Deliverable:** Continuous learning from outcomes
**Integration:** Adds lesson generation and memory updates

---

## Next Steps After Phase 4

### Future Enhancements
1. **Database Backend** (SQLite with vector search)
2. **Advanced Analytics** (pattern performance dashboard)
3. **Multi-Instrument Memory** (share learning across symbols)
4. **Backtesting Integration** (test against historical data)
5. **Real-Time Collaboration** (share memory across instances)

### Success Metrics
- **Search Latency:** < 500ms
- **Index Updates:** < 5 seconds
- **Storage Growth:** < 100MB/month
- **Win Rate Improvement:** > 10% after 4 weeks

---

**Status:** Ready to begin Phase 1 implementation
**Branch:** feature/memory-sessions-pattern
**Dependencies:** Java 17, Gradle 8.14, Claude API (z.ai)
