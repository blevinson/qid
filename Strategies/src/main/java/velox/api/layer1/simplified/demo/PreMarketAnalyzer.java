package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradingMemoryService;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Pre-Market Analyzer
 *
 * Performs deep pre-market analysis and creates a trade plan for the session.
 * Analysis is stored in memory for reference during trading.
 *
 * Philosophy: "Prepare before you trade" - Set expectations, define levels,
 * understand market context before entering any positions.
 */
public class PreMarketAnalyzer {
    private final String apiToken;
    private final HttpClient httpClient;
    private final Gson gson;
    private final File memoryDir;
    private final TradingMemoryService memoryService;
    private static final String API_URL = "https://zai.cloudtorch.ai/v1/messages";
    private static final String MODEL = "glm-4.7";

    // Market data suppliers (set by parent strategy)
    private Supplier<Double> priceSupplier;
    private Supplier<Long> cvdSupplier;
    private Supplier<Double> vwapSupplier;
    private Supplier<double[]> emaSupplier;  // [ema9, ema21, ema50]
    private Supplier<Double> pocSupplier;
    private Supplier<double[]> valueAreaSupplier;  // [vaLow, vaHigh]
    private Supplier<double[]> domSupplier;  // [supportPrice, resistancePrice, imbalanceRatio]
    private Supplier<String> instrumentSupplier;
    private Supplier<Double> pipsSupplier;

    // File logging
    private static final String LOG_FILE = System.getProperty("user.home") + "/ai-premarket.log";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Callback interface
    public interface AnalysisCallback {
        void onComplete(String analysis, boolean success);
    }

    public PreMarketAnalyzer(String apiToken, File memoryDir, TradingMemoryService memoryService) {
        this.apiToken = apiToken;
        this.memoryDir = memoryDir;
        this.memoryService = memoryService;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

        log("========== Pre-Market Analyzer Initialized ==========");
    }

    // Setters for market data suppliers
    public void setPriceSupplier(Supplier<Double> supplier) { this.priceSupplier = supplier; }
    public void setCvdSupplier(Supplier<Long> supplier) { this.cvdSupplier = supplier; }
    public void setVwapSupplier(Supplier<Double> supplier) { this.vwapSupplier = supplier; }
    public void setEmaSupplier(Supplier<double[]> supplier) { this.emaSupplier = supplier; }
    public void setPocSupplier(Supplier<Double> supplier) { this.pocSupplier = supplier; }
    public void setValueAreaSupplier(Supplier<double[]> supplier) { this.valueAreaSupplier = supplier; }
    public void setDomSupplier(Supplier<double[]> supplier) { this.domSupplier = supplier; }
    public void setInstrumentSupplier(Supplier<String> supplier) { this.instrumentSupplier = supplier; }
    public void setPipsSupplier(Supplier<Double> supplier) { this.pipsSupplier = supplier; }

    /**
     * Run pre-market analysis and store in memory
     * @param callback Callback when analysis is complete
     */
    public void runAnalysis(AnalysisCallback callback) {
        log("========== PRE-MARKET ANALYSIS STARTED ==========");

        // Gather market data
        MarketSnapshot snapshot = gatherMarketData();

        if (snapshot.price == 0) {
            log("ERROR: No market data available. Is the market open?");
            callback.onComplete("Error: No market data available. Please wait for market data.", false);
            return;
        }

        log("Market Snapshot:");
        log("  Instrument: " + snapshot.instrument);
        log("  Price: " + String.format("%.2f", snapshot.price));
        log("  CVD: " + snapshot.cvd);
        log("  VWAP: " + String.format("%.2f", snapshot.vwap));
        log("  EMA9/21/50: " + String.format("%.2f / %.2f / %.2f", snapshot.ema9, snapshot.ema21, snapshot.ema50));
        log("  POC: " + String.format("%.2f", snapshot.poc));
        log("  Value Area: " + String.format("%.2f - %.2f", snapshot.vaLow, snapshot.vaHigh));

        // Build prompt
        String prompt = buildAnalysisPrompt(snapshot);

        // Call AI asynchronously
        callAI(prompt, snapshot, callback);
    }

    /**
     * Gather current market data
     */
    private MarketSnapshot gatherMarketData() {
        MarketSnapshot snapshot = new MarketSnapshot();

        try {
            if (priceSupplier != null) {
                Double price = priceSupplier.get();
                snapshot.price = price != null ? price : 0;
            }

            if (cvdSupplier != null) {
                Long cvd = cvdSupplier.get();
                snapshot.cvd = cvd != null ? cvd : 0;
            }

            if (vwapSupplier != null) {
                Double vwap = vwapSupplier.get();
                snapshot.vwap = vwap != null ? vwap : 0;
            }

            if (emaSupplier != null) {
                double[] emas = emaSupplier.get();
                if (emas != null && emas.length >= 3) {
                    snapshot.ema9 = emas[0];
                    snapshot.ema21 = emas[1];
                    snapshot.ema50 = emas[2];
                }
            }

            if (pocSupplier != null) {
                Double poc = pocSupplier.get();
                snapshot.poc = poc != null ? poc : 0;
            }

            if (valueAreaSupplier != null) {
                double[] va = valueAreaSupplier.get();
                if (va != null && va.length >= 2) {
                    snapshot.vaLow = va[0];
                    snapshot.vaHigh = va[1];
                }
            }

            if (domSupplier != null) {
                double[] dom = domSupplier.get();
                if (dom != null && dom.length >= 3) {
                    snapshot.domSupport = dom[0];
                    snapshot.domResistance = dom[1];
                    snapshot.domImbalance = dom[2];
                }
            }

            if (instrumentSupplier != null) {
                snapshot.instrument = instrumentSupplier.get();
            }

            if (pipsSupplier != null) {
                Double pips = pipsSupplier.get();
                snapshot.pips = pips != null ? pips : 1.0;
            }

        } catch (Exception e) {
            log("Error gathering market data: " + e.getMessage());
        }

        return snapshot;
    }

    /**
     * Build the pre-market analysis prompt
     */
    private String buildAnalysisPrompt(MarketSnapshot snapshot) {
        // Determine EMA alignment
        String emaTrend = "NEUTRAL";
        int emaCount = 0;
        if (snapshot.ema9 > 0 && snapshot.ema21 > 0 && snapshot.ema50 > 0 && snapshot.price > 0) {
            if (snapshot.price > snapshot.ema9) emaCount++;
            if (snapshot.price > snapshot.ema21) emaCount++;
            if (snapshot.price > snapshot.ema50) emaCount++;
            if (snapshot.ema9 > snapshot.ema21 && snapshot.ema21 > snapshot.ema50) {
                emaTrend = "BULLISH ALIGNMENT";
            } else if (snapshot.ema9 < snapshot.ema21 && snapshot.ema21 < snapshot.ema50) {
                emaTrend = "BEARISH ALIGNMENT";
            }
        }

        // Determine VWAP position
        String vwapPosition = "N/A";
        if (snapshot.vwap > 0 && snapshot.price > 0) {
            double diff = snapshot.price - snapshot.vwap;
            vwapPosition = diff > 0 ? "ABOVE VWAP (+$" + String.format("%.2f", diff) + ")" :
                                    "BELOW VWAP (-$" + String.format("%.2f", Math.abs(diff)) + ")";
        }

        // Determine CVD trend
        String cvdTrend = snapshot.cvd > 500 ? "STRONG BUYING" :
                         snapshot.cvd > 0 ? "SLIGHT BUYING" :
                         snapshot.cvd < -500 ? "STRONG SELLING" :
                         snapshot.cvd < 0 ? "SLIGHT SELLING" : "NEUTRAL";

        return String.format("""
            PRE-MARKET ANALYSIS REQUEST
            ===========================
            Instrument: %s
            Time: %s

            CURRENT MARKET DATA:
            - Price: %.2f
            - VWAP: %.2f (%s)
            - CVD: %d (%s)
            - EMA9: %.2f | EMA21: %.2f | EMA50: %.2f
            - EMA Trend: %s (%d/3 aligned)
            - Volume Profile POC: %.2f
            - Value Area: %.2f - %.2f
            - DOM Support: %.2f
            - DOM Resistance: %.2f
            - DOM Imbalance: %.2f

            ANALYSIS REQUIRED:
            1. MARKET SENTIMENT: What is the current market sentiment? Bullish/Bearish/Neutral? Why?
            2. KEY LEVELS: Identify the most important support and resistance levels for today.
            3. TRADE BIASES: Based on current data, should we prefer longs, shorts, or stay neutral?
            4. SCENARIOS: Outline 2-3 scenarios that could play out today and how to trade each.
            5. RISK FACTORS: What could invalidate our bias? What should we watch for?
            6. SESSION PLAN: Create a concise trade plan with:
               - Preferred direction
               - Entry criteria (what signals to look for)
               - Key levels to watch
               - Risk management rules

            IMPORTANT: Provide your analysis in a structured format that can be saved and referenced during trading.
            """,
            snapshot.instrument,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            snapshot.price,
            snapshot.vwap, vwapPosition,
            snapshot.cvd, cvdTrend,
            snapshot.ema9, snapshot.ema21, snapshot.ema50,
            emaTrend, emaCount,
            snapshot.poc,
            snapshot.vaLow, snapshot.vaHigh,
            snapshot.domSupport,
            snapshot.domResistance,
            snapshot.domImbalance
        );
    }

    /**
     * Call AI API for analysis
     */
    private void callAI(String prompt, MarketSnapshot snapshot, AnalysisCallback callback) {
        log("Calling AI for pre-market analysis...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return callAISync(prompt);
            } catch (Exception e) {
                log("AI call failed: " + e.getMessage());
                return "Error: " + e.getMessage();
            }
        }).thenAccept(response -> {
            boolean success = !response.startsWith("Error:");

            if (success) {
                // Save to memory
                saveToMemory(response, snapshot);
            }

            log("Pre-market analysis complete. Success: " + success);
            callback.onComplete(response, success);
        });
    }

    /**
     * Synchronous AI call
     */
    private String callAISync(String prompt) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);
        requestBody.addProperty("max_tokens", 4096);

        String systemPrompt = """
            You are Qid, an AI Investment Strategist performing pre-market analysis.

            Your role is to:
            1. Analyze current market conditions
            2. Identify key levels and sentiment
            3. Create a clear trade plan for the session
            4. Outline scenarios and risk factors

            Be specific, actionable, and reference actual price levels.
            Your analysis will be saved to memory and referenced during trading.

            Format your response as:
            # Pre-Market Analysis - [DATE]

            ## Market Sentiment
            [Your assessment]

            ## Key Levels
            [Support and resistance levels]

            ## Trade Plan
            [Direction, entry criteria, risk management]

            ## Scenarios
            [Bullish/Bearish/Neutral scenarios]

            ## Risk Factors
            [What to watch for]
            """;

        requestBody.addProperty("system", systemPrompt);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        requestBody.add("messages", gson.toJsonTree(new Object[]{message}));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("x-api-key", apiToken)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("API error: " + response.statusCode() + " - " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        return jsonResponse.getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
    }

    /**
     * Save analysis to memory
     */
    private void saveToMemory(String analysis, MarketSnapshot snapshot) {
        try {
            // Create pre-market directory if needed
            File premarketDir = new File(memoryDir, "pre-market");
            if (!premarketDir.exists()) {
                premarketDir.mkdirs();
            }

            // Create filename with date
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = snapshot.instrument + "-" + dateStr + ".md";
            File analysisFile = new File(premarketDir, filename);

            // Add header with metadata
            StringBuilder content = new StringBuilder();
            content.append("# Pre-Market Analysis - ").append(dateStr).append("\n\n");
            content.append("**Instrument:** ").append(snapshot.instrument).append("\n");
            content.append("**Time:** ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n");
            content.append("**Price:** ").append(String.format("%.2f", snapshot.price)).append("\n\n");
            content.append("---\n\n");
            content.append(analysis);

            // Write file
            try (PrintWriter writer = new PrintWriter(new FileWriter(analysisFile))) {
                writer.println(content.toString());
            }

            log("Analysis saved to: " + analysisFile.getAbsolutePath());

            // Re-sync memory service
            if (memoryService != null) {
                memoryService.sync();
                log("Memory re-synced with new analysis");
            }

        } catch (Exception e) {
            log("Error saving analysis to memory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Log message
     */
    private void log(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String logLine = timestamp + " | " + message;
        System.out.println(logLine);
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            writer.println(logLine);
        } catch (Exception e) {
            // Ignore file write errors
        }
    }

    /**
     * Market snapshot data class
     */
    private static class MarketSnapshot {
        String instrument = "UNKNOWN";
        double price = 0;
        long cvd = 0;
        double vwap = 0;
        double ema9 = 0;
        double ema21 = 0;
        double ema50 = 0;
        double poc = 0;
        double vaLow = 0;
        double vaHigh = 0;
        double domSupport = 0;
        double domResistance = 0;
        double domImbalance = 0;
        double pips = 1.0;
    }
}
