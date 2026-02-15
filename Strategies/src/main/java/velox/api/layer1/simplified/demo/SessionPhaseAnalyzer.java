package velox.api.layer1.simplified.demo;

import velox.api.layer1.simplified.demo.storage.TradingMemoryService;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Session Phase Analyzer
 *
 * Analyzes market conditions at each trading phase and maintains
 * a rolling session context that AI can reference.
 *
 * Phases:
 * - PRE_MARKET: Full analysis, set bias, define key levels
 * - OPENING_RANGE: Quick check on open reaction
 * - MORNING_SESSION: Trend assessment
 * - LUNCH: Caution warnings
 * - AFTERNOON: Trend resumption/reversal check
 * - CLOSE: Late-day positioning notes
 *
 * Key design: Single rolling context per day prevents AI bloat.
 */
public class SessionPhaseAnalyzer {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String API_URL = "https://zai.cloudtorch.ai/v1/messages";
    private static final String MODEL = "glm-5";
    private static final String LOG_FILE = System.getProperty("user.home") + "/session-phase.log";

    private final String apiToken;
    private final HttpClient httpClient;
    private final Gson gson;
    private final SessionContextManager contextManager;
    private final TradingMemoryService memoryService;
    private Consumer<String> logger;

    // Data suppliers
    private Supplier<Double> priceSupplier;
    private Supplier<Double> vwapSupplier;
    private Supplier<Long> cvdSupplier;
    private Supplier<double[]> emaSupplier;
    private Supplier<Double> pocSupplier;
    private Supplier<double[]> valueAreaSupplier;
    private Supplier<double[]> domSupplier;
    private Supplier<String> instrumentSupplier;
    private Supplier<Double> pipsSupplier;

    // Callback interface
    public interface AnalysisCallback {
        void onComplete(String analysis, boolean success);
    }

    public SessionPhaseAnalyzer(String apiToken, File memoryDir, TradingMemoryService memoryService) {
        this.apiToken = apiToken;
        this.memoryService = memoryService;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

        // Will be initialized with instrument later
        this.contextManager = new SessionContextManager(memoryDir, "ES");
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
        this.contextManager.setLogger(logger);
    }

    // Data supplier setters
    public void setPriceSupplier(Supplier<Double> supplier) { this.priceSupplier = supplier; }
    public void setVwapSupplier(Supplier<Double> supplier) { this.vwapSupplier = supplier; }
    public void setCvdSupplier(Supplier<Long> supplier) { this.cvdSupplier = supplier; }
    public void setEmaSupplier(Supplier<double[]> supplier) { this.emaSupplier = supplier; }
    public void setPocSupplier(Supplier<Double> supplier) { this.pocSupplier = supplier; }
    public void setValueAreaSupplier(Supplier<double[]> supplier) { this.valueAreaSupplier = supplier; }
    public void setDomSupplier(Supplier<double[]> supplier) { this.domSupplier = supplier; }
    public void setInstrumentSupplier(Supplier<String> supplier) {
        this.instrumentSupplier = supplier;
        // Update context manager with actual instrument
        if (supplier != null && supplier.get() != null) {
            // Context manager instrument will be set when analysis runs
        }
    }
    public void setPipsSupplier(Supplier<Double> supplier) { this.pipsSupplier = supplier; }

    /**
     * Run full pre-market analysis (start of day)
     */
    public void runPreMarketAnalysis(AnalysisCallback callback) {
        log("📊 Running PRE-MARKET analysis...");

        MarketSnapshot snapshot = captureSnapshot();
        String prompt = buildPreMarketPrompt(snapshot);

        callAI(prompt, (response, success) -> {
            if (success) {
                // Initialize today's context
                contextManager.initializeTodaysContext(
                    response,
                    snapshot.price, snapshot.vwap, snapshot.cvd,
                    snapshot.ema9, snapshot.ema21, snapshot.ema50
                );

                // Re-sync memory so AI can search for it
                if (memoryService != null) {
                    memoryService.sync();
                }

                log("✅ Pre-market analysis saved to session context");
            }
            callback.onComplete(response, success);
        });
    }

    /**
     * Run a phase-specific update (quick check)
     */
    public void runPhaseUpdate(SessionPhaseTracker.SessionPhase phase, AnalysisCallback callback) {
        log("📊 Running phase update: " + phase.getDisplayName());

        // Skip if no context for today
        if (!contextManager.hasTodaysContext()) {
            log("⚠️ No session context for today, skipping phase update");
            callback.onComplete("No session context available", false);
            return;
        }

        MarketSnapshot snapshot = captureSnapshot();
        String prompt = buildPhaseUpdatePrompt(phase, snapshot, contextManager.getTodaysContextSummary());

        callAI(prompt, (response, success) -> {
            if (success) {
                // Append to today's context
                contextManager.appendPhaseUpdate(phase, response, snapshot.price, snapshot.vwap, snapshot.cvd);

                // Re-sync memory
                if (memoryService != null) {
                    memoryService.sync();
                }

                log("✅ Phase update saved: " + phase.getDisplayName());
            }
            callback.onComplete(response, success);
        });
    }

    /**
     * Capture current market snapshot from suppliers
     */
    private MarketSnapshot captureSnapshot() {
        MarketSnapshot snapshot = new MarketSnapshot();

        snapshot.instrument = instrumentSupplier != null ? instrumentSupplier.get() : "UNKNOWN";
        snapshot.price = priceSupplier != null ? priceSupplier.get() : 0.0;
        snapshot.vwap = vwapSupplier != null ? vwapSupplier.get() : 0.0;
        snapshot.cvd = cvdSupplier != null ? cvdSupplier.get() : 0L;
        snapshot.pips = pipsSupplier != null ? pipsSupplier.get() : 0.25;

        double[] emas = emaSupplier != null ? emaSupplier.get() : new double[3];
        snapshot.ema9 = emas.length > 0 ? emas[0] : 0.0;
        snapshot.ema21 = emas.length > 1 ? emas[1] : 0.0;
        snapshot.ema50 = emas.length > 2 ? emas[2] : 0.0;

        snapshot.poc = pocSupplier != null ? pocSupplier.get() : 0.0;

        double[] va = valueAreaSupplier != null ? valueAreaSupplier.get() : new double[2];
        snapshot.vaLow = va.length > 0 ? va[0] : 0.0;
        snapshot.vaHigh = va.length > 1 ? va[1] : 0.0;

        double[] dom = domSupplier != null ? domSupplier.get() : new double[4];
        snapshot.domSupport = dom.length > 0 ? dom[0] : 0.0;
        snapshot.domSupportVol = dom.length > 1 ? (int) dom[1] : 0;
        snapshot.domResistance = dom.length > 2 ? dom[2] : 0.0;
        snapshot.domResistanceVol = dom.length > 3 ? (int) dom[3] : 0;

        return snapshot;
    }

    /**
     * Build pre-market analysis prompt (full analysis)
     */
    private String buildPreMarketPrompt(MarketSnapshot snapshot) {
        // Determine EMA alignment
        String emaTrend = "NEUTRAL";
        if (snapshot.ema9 > 0 && snapshot.ema21 > 0 && snapshot.ema50 > 0 && snapshot.price > 0) {
            if (snapshot.price > snapshot.ema9 && snapshot.ema9 > snapshot.ema21 && snapshot.ema21 > snapshot.ema50) {
                emaTrend = "BULLISH ALIGNMENT";
            } else if (snapshot.price < snapshot.ema9 && snapshot.ema9 < snapshot.ema21 && snapshot.ema21 < snapshot.ema50) {
                emaTrend = "BEARISH ALIGNMENT";
            }
        }

        String cvdTrend = snapshot.cvd > 500 ? "STRONG BUYING" :
                         snapshot.cvd > 0 ? "SLIGHT BUYING" :
                         snapshot.cvd < -500 ? "STRONG SELLING" :
                         snapshot.cvd < 0 ? "SLIGHT SELLING" : "NEUTRAL";

        return String.format("""
            You are Qid, an AI trading strategist. Perform a comprehensive pre-market analysis.

            CURRENT MARKET DATA:
            - Instrument: %s
            - Price: %.2f
            - VWAP: %.2f
            - CVD: %d (%s)
            - EMA9: %.2f | EMA21: %.2f | EMA50: %.2f
            - EMA Trend: %s
            - Volume Profile POC: %.2f
            - Value Area: %.2f - %.2f
            - DOM Support: %.2f (%d contracts)
            - DOM Resistance: %.2f (%d contracts)

            Provide a structured analysis with:

            ## MARKET SENTIMENT
            [Bullish/Bearish/Neutral and why]

            ## KEY LEVELS
            - Support levels (3 levels)
            - Resistance levels (3 levels)
            - Critical pivot points

            ## TRADE BIAS
            [Preferred direction for today with reasoning]

            ## SCENARIOS
            1. Bullish scenario: [conditions and targets]
            2. Bearish scenario: [conditions and targets]
            3. Neutral/range scenario: [conditions and boundaries]

            ## RISK FACTORS
            [What could invalidate the bias]

            ## SESSION PLAN
            - Preferred direction: [LONG/SHORT/NEUTRAL]
            - Entry criteria: [what signals to look for]
            - Avoid: [what to avoid]
            - Risk management: [position sizing, stop rules]

            Keep each section concise (2-3 sentences max).
            """,
            snapshot.instrument,
            snapshot.price,
            snapshot.vwap,
            snapshot.cvd, cvdTrend,
            snapshot.ema9, snapshot.ema21, snapshot.ema50,
            emaTrend,
            snapshot.poc,
            snapshot.vaLow, snapshot.vaHigh,
            snapshot.domSupport, snapshot.domSupportVol,
            snapshot.domResistance, snapshot.domResistanceVol
        );
    }

    /**
     * Build phase update prompt (quick check)
     */
    private String buildPhaseUpdatePrompt(SessionPhaseTracker.SessionPhase phase,
                                           MarketSnapshot snapshot,
                                           String todaysContext) {
        String phaseContext = getPhaseContext(phase);

        return String.format("""
            You are Qid, an AI trading strategist. Provide a BRIEF phase update.

            CURRENT PHASE: %s
            %s

            CURRENT DATA:
            - Price: %.2f | VWAP: %.2f | CVD: %d

            TODAY'S PLAN (for reference):
            %s

            Provide a BRIEF update (2-3 sentences each):

            ## PHASE STATUS
            [Are we following the plan? Any deviations?]

            ## BIAS CHECK
            [Is the bias still valid? Any change needed?]

            ## ACTION ITEMS
            [1-2 specific things to watch or do this phase]

            Keep it concise - this is a quick update, not a full analysis.
            """,
            phase.getDisplayName(),
            phaseContext,
            snapshot.price, snapshot.vwap, snapshot.cvd,
            todaysContext != null ? todaysContext.substring(0, Math.min(todaysContext.length(), 1000)) : "No context"
        );
    }

    /**
     * Get phase-specific context for prompts
     */
    private String getPhaseContext(SessionPhaseTracker.SessionPhase phase) {
        return switch (phase) {
            case OPENING_RANGE -> """
                High volatility period. Wait for direction to establish.
                Be cautious of false moves. Higher thresholds recommended.
                """;
            case MORNING_SESSION -> """
                Prime trading hours. Look for trend-following setups.
                Higher conviction trades appropriate.
                """;
            case LUNCH -> """
                Low volume period. Expect choppy conditions.
                REDUCE SIZE. RAISE THRESHOLDS. Avoid range trades.
                """;
            case AFTERNOON -> """
                Volume returning. Look for trend resumption or reversal.
                Normal trading conditions resume.
                """;
            case CLOSE -> """
                Final hour. MOC flows may cause moves.
                Avoid new positions. Manage existing trades.
                """;
            default -> "Standard trading conditions.";
        };
    }

    /**
     * Call AI API
     */
    private void callAI(String prompt, AnalysisCallback callback) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return callAISync(prompt);
            } catch (Exception e) {
                log("AI call failed: " + e.getMessage());
                return "Error: " + e.getMessage();
            }
        }).thenAccept(response -> {
            boolean success = !response.startsWith("Error:");
            callback.onComplete(response, success);
        });
    }

    private String callAISync(String prompt) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);
        requestBody.addProperty("max_tokens", 1024);

        String systemPrompt = """
            You are Qid, an AI trading strategist.
            Provide clear, actionable analysis in markdown format.
            Be concise and specific. Avoid vague statements.
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
     * Get the session context manager (for external access)
     */
    public SessionContextManager getContextManager() {
        return contextManager;
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String logLine = timestamp + " | " + message;
        System.out.println(logLine);

        if (logger != null) {
            logger.accept(logLine);
        }

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
        String instrument;
        double price;
        double vwap;
        long cvd;
        double pips;
        double ema9, ema21, ema50;
        double poc;
        double vaLow, vaHigh;
        double domSupport, domResistance;
        int domSupportVol, domResistanceVol;
    }

    // Supplier interface (avoid java.util.function import issues)
    @FunctionalInterface
    public interface Supplier<T> {
        T get();
    }
}
