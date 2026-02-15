package velox.api.layer1.simplified.demo;

import java.awt.*;
import java.io.File;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.*;
import java.nio.file.Path;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.Layer1TradingStrategy;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
import velox.api.layer1.simplified.OrdersListener;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TradeDataListener;
import velox.api.layer1.simplified.TimeListener;
import velox.gui.StrategyPanel;

/**
 * Order Flow Strategy - Enhanced with UI Panels and Performance Tracking
 *
 * Features:
 * - Custom Settings Panel (live parameter adjustment)
 * - Statistics/Performance Panel (P&L, win rate, activity)
 * - Real-time alerts (daily loss limit, drawdown warnings)
 * - Export functionality (CSV, reports)
 * - Adaptive thresholds (learns from instrument)
 *
 * Signals:
 * - ICEBERG: Hidden large orders (cyan dots)
 * - SPOOF: Fake large orders (magenta dots)
 * - ABSORPTION: Large trades eating levels (yellow dots)
 */
@Layer1SimpleAttachable
@Layer1TradingStrategy
@Layer1StrategyName("Order Flow Enhanced")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategyEnhanced implements
    CustomModule,
    MarketByOrderDepthDataListener,
    TradeDataListener,
    BboListener,
    CustomSettingsPanelProvider,
    OrdersListener,
    TimeListener,
    AIOrderManager.AIMarkerCallback {

    // ========== PERFORMANCE TRACKING ==========
    private Map<Integer, SignalPerformance> trackedSignals = new ConcurrentHashMap<>();
    private static final long SIGNAL_TRACKING_MS = 5 * 60 * 1000;  // Track for 5 minutes

    // ========== SIGNAL PRE-FILTERS ==========
    // Deduplication: Track recent signals to avoid repeated AI calls
    private Map<String, Long> recentSignalsSent = new ConcurrentHashMap<>();
    private static final long SIGNAL_DEDUP_MS = 60 * 1000;  // 60 seconds dedup window

    // AI Evaluation Guard - prevent overlapping AI calls (thread-safe)
    private final java.util.concurrent.atomic.AtomicBoolean aiEvaluationInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Pre-filter constants
    private static final int CVD_STRONG_THRESHOLD = 8000;  // Skip counter-trend only if EXTREMELY strong
    private static final int SCORE_FLOOR_OFFSET = 20;      // Score must be >= threshold - this

    private static class SignalPerformance {
        long timestamp;
        boolean isBid;
        int entryPrice;
        int score;
        int totalSize;
        double cvdAtSignal;
        String trendAtSignal;
        int emaAlignmentAtSignal;

        // Outcome tracking
        Integer exitPrice;
        Long exitTimestamp;
        Integer ticksMoved;
        Boolean profitable;  // null = still open

        // Confluence data at signal time
        String confluenceBreakdown;
    }

    // ========== PARAMETERS ==========
    // Using wrapper types as required by Bookmap @Parameter annotation
    @Parameter(name = "Min Confluence Score")
    private Integer minConfluenceScore = 10;

    @Parameter(name = "Threshold Multiplier")
    private Double thresholdMultiplier = 3.0;

    @Parameter(name = "Iceberg Min Orders")
    private Integer icebergMinOrders = 10;  // Reduced from 20 to catch more signals

    @Parameter(name = "Spoof Max Age (ms)")
    private Integer spoofMaxAge = 500;

    @Parameter(name = "Spoof Min Size")
    private Integer spoofMinSize = 20;  // Increased from 5

    @Parameter(name = "Absorption Min Size")
    private Integer absorptionMinSize = 50;  // Increased from 10

    // ========== RISK MANAGEMENT PARAMETERS ==========
    @Parameter(name = "Max Position Size")
    private Integer maxPosition = 1;

    @Parameter(name = "Daily Loss Limit ($)")
    private Double dailyLossLimit = 500.0;

    @Parameter(name = "Max Slippage (ticks)")
    private Integer maxSlippageTicks = 50;  // Skip if price moved > 50 ticks

    // ========== POSITION SIZING PARAMETERS ==========
    @Parameter(name = "Account Size ($)")
    private Double accountSize = 10000.0;

    @Parameter(name = "Risk Per Trade (%)")
    private Double riskPerTradePercent = 1.0;  // 1% of account per trade

    @Parameter(name = "Tick Value ($)")
    private Double tickValue = 12.50;  // ES futures: $12.50 per tick

    @Parameter(name = "Auto-Calculate Position Size")
    private Boolean autoCalculatePositionSize = false;  // If true, calculate from risk %

    @Parameter(name = "Fixed Position Size")
    private Integer fixedPositionSize = 1;  // Contracts per trade (used if auto-calc is off)

    @Parameter(name = "Stop Loss Ticks")
    private Integer stopLossTicks = 20;  // Distance from entry to SL in ticks

    @Parameter(name = "Take Profit Ticks")
    private Integer takeProfitTicks = 40;  // Distance from entry to TP in ticks

    @Parameter(name = "Use Smart SL/TP")
    private Boolean useSmartSlTp = true;  // Use ATR + DOM levels for SL/TP

    @Parameter(name = "ATR SL Multiplier")
    private Double atrSlMultiplier = 1.5;  // SL = ATR × multiplier (in ticks)

    @Parameter(name = "ATR TP Multiplier")
    private Double atrTpMultiplier = 3.0;  // TP = ATR × multiplier (in ticks)

    @Parameter(name = "Min R:R Ratio")
    private Double minRRRatio = 1.5;  // Minimum risk:reward ratio (e.g., 1.5 means 1:1.5)

    @Parameter(name = "Max SL Ticks")
    private Integer maxSlTicks = 50;  // Cap SL to prevent runaway risk

    @Parameter(name = "Max TP Ticks")
    private Integer maxTpTicks = 150;  // Cap TP to realistic targets

    // ========== DOM QUALITY THRESHOLDS ==========
    @Parameter(name = "Min DOM Volume for SL/TP")
    private Integer minDomVolumeForSlTp = 100;  // Only use DOM level if volume >= this

    @Parameter(name = "DOM Level Priority")
    private Boolean prioritizeDomOverAtr = true;  // DOM levels take priority over ATR

    // ========== SESSION-AWARE ATR SCALING ==========
    @Parameter(name = "Session-Aware ATR Scaling")
    private Boolean sessionAwareAtrScaling = true;  // Adjust ATR multipliers by session phase

    // ========== MEMORY-DRIVEN SL/TP ==========
    @Parameter(name = "Use Trade Memory for SL/TP")
    private Boolean useTradeMemoryForSlTp = true;  // Use recent trade reversal levels

    @Parameter(name = "Memory Lookback (trades)")
    private Integer memoryLookbackTrades = 20;  // How many recent trades to analyze

    // ========== SAFETY PARAMETERS ==========
    @Parameter(name = "Simulation Mode Only")
    private Boolean simModeOnly = true;

    @Parameter(name = "Enable Auto-Execution")
    private Boolean autoExecution = false;

    // ========== ADAPTIVE MODE PARAMETERS ==========
    @Parameter(name = "Use AI Adaptive Thresholds")
    private Boolean useAIAdaptiveThresholds = true;  // Default: AI mode ENABLED

    @Parameter(name = "AI Re-evaluation Interval (min)")
    private Integer aiReevaluationInterval = 30;  // Re-evaluate every 30 minutes

    // ========== AI TRADING PARAMETERS ==========
    @Parameter(name = "Enable AI Trading")
    private Boolean enableAITrading = false;

    @Parameter(name = "AI Mode")
    private String aiMode = "MANUAL";  // MANUAL, SEMI_AUTO, FULL_AUTO

    @Parameter(name = "Dev Mode")
    private Boolean devMode = false;  // When true, AI is more permissive for testing

    @Parameter(name = "Confluence Threshold")
    private Integer confluenceThreshold = 50;

    @Parameter(name = "AI Managed Weights")
    private Boolean aiManagedWeights = true;  // When true, AI can adjust confluence weights

    @Parameter(name = "Auto Phase Analysis")
    private Boolean autoPhaseAnalysis = true;  // When true, automatically run phase analysis at transitions

    // Adjustable confluence weights (AI can modify within safety bounds when enabled)
    private ConfluenceWeights confluenceWeights = new ConfluenceWeights();

    @Parameter(name = "AI Auth Token")
    private String aiAuthToken = "";  // User's API token or empty for proxy

    // Proxy token constant
    private static final String PROXY_TOKEN = "sk-zai-proxy-key";

    // Use proxy for API calls (when true, uses PROXY_TOKEN instead of aiAuthToken)
    private boolean useProxy = true;

    /**
     * Get the effective API token to use
     * Returns proxy token if useProxy is true, otherwise returns user's token
     */
    private String getEffectiveApiToken() {
        if (useProxy) {
            return PROXY_TOKEN;
        }
        return aiAuthToken;
    }

    @Parameter(name = "AI Prompt Mode")
    private String aiPromptMode = "COMPACT";  // FULL or COMPACT

    // ========== NOTIFICATION SETTINGS ==========
    @Parameter(name = "Enable Event Notifications")
    private Boolean enableEventNotifications = true;  // Trade/signal events

    @Parameter(name = "Enable AI Notifications")
    private Boolean enableAINotifications = true;  // AI can push alerts

    @Parameter(name = "Enable Periodic Updates")
    private Boolean enablePeriodicUpdates = false;  // Periodic status reports

    @Parameter(name = "Periodic Update Interval (min)")
    private Integer periodicUpdateIntervalMinutes = 15;  // How often to send updates

    // ========== SETTINGS PERSISTENCE (Native Bookmap API) ==========
    /**
     * Settings class for Bookmap native persistence
     */
    @StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
    public static class Settings {
        public Integer minConfluenceScore = 10;
        public Double thresholdMultiplier = 3.0;
        public Integer icebergMinOrders = 10;
        public Integer spoofMinSize = 20;
        public Integer absorptionMinSize = 50;
        public Integer maxPosition = 1;
        public Double dailyLossLimit = 500.0;
        public Boolean simModeOnly = true;
        public Boolean autoExecution = false;
        public Boolean useAIAdaptiveThresholds = true;
        public Boolean enableAITrading = false;
        public String aiMode = "MANUAL";
        public Integer confluenceThreshold = 50;
        public String aiAuthToken = "";  // User's API token (empty = use proxy)
        public Boolean useProxy = true;  // Use proxy by default
        public Integer replayStartHour = 9;   // Hour (24h) when replay data starts (9 = 9:00 AM)
        public Integer replayStartMinute = 30; // Minute when replay data starts (30 = :30)
        public Boolean devMode = false;  // Dev mode for permissive AI testing
        public String aiPromptMode = "COMPACT";  // FULL or COMPACT prompts
        public Integer maxSlippageTicks = 50;  // Max price slippage before rejecting signal
        // Position sizing settings
        public Double accountSize = 10000.0;
        public Double riskPerTradePercent = 1.0;
        public Double tickValue = 12.50;
        public Boolean autoCalculatePositionSize = false;
        public Integer fixedPositionSize = 1;
        public Integer stopLossTicks = 20;
        public Integer takeProfitTicks = 40;
        public Boolean useSmartSlTp = true;
        public Double atrSlMultiplier = 1.5;
        public Double atrTpMultiplier = 3.0;
        public Double minRRRatio = 1.5;
        public Integer maxSlTicks = 50;
        public Integer maxTpTicks = 150;
        public Integer minDomVolumeForSlTp = 100;
        public Boolean prioritizeDomOverAtr = true;
        public Boolean sessionAwareAtrScaling = true;
        public Boolean useTradeMemoryForSlTp = true;
        public Integer memoryLookbackTrades = 20;
    }

    private Settings settings;

    /**
     * Save settings using Bookmap's native API
     */
    private void saveSettings() {
        if (api != null && settings != null) {
            api.setSettings(settings);
            log("💾 Settings saved");
        }
    }

    // ========== INDICATORS (Layer 1: Detection Markers) ==========
    // NOTE: Using separate indicators for each marker to avoid connecting lines
    // TODO: Upgrade to ScreenSpacePainter (advanced API) for proper icons
    private Indicator icebergBuyMarker;     // GREEN buy signals
    private Indicator icebergSellMarker;    // RED sell signals
    private int icebergMarkerCount = 0;     // Track number of markers
    private Indicator spoofingMarker;       // MAGENTA triangle markers
    private Indicator absorptionMarker;     // YELLOW square markers

    // ========== INDICATORS (Layer 2: AI Action Markers) ==========
    private Indicator aiLongEntryMarker;    // CYAN circle markers
    private Indicator aiShortEntryMarker;   // PINK circle markers
    private Indicator aiExitMarker;         // SL/TP/BE markers
    private Indicator aiSkipMarker;         // WHITE circle markers

    // ========== AI ORDER LEVEL LINES ==========
    private Indicator aiStopLossLine;       // ORANGE horizontal line at SL
    private Indicator aiTakeProfitLine;     // GREEN horizontal line at TP
    private Integer activeStopLossPrice = null;    // Track active SL price
    private Integer activeTakeProfitPrice = null;  // Track active TP price

    // ========== AI COMPONENTS ==========
    private AIIntegrationLayer aiIntegration;
    private AIOrderManager aiOrderManager;
    private OrderExecutor orderExecutor;
    private AIThresholdService aiThresholdService;
    private AIToolsProvider aiToolsProvider;  // Tool functions for AI chat
    private Object memoryService;  // TradingMemoryService - initialized as Object to avoid loading issues
    private AIInvestmentStrategist aiStrategist;
    private PreMarketAnalyzer preMarketAnalyzer;  // Pre-market analysis and planning (legacy)
    private SessionPhaseTracker sessionPhaseTracker;  // Automatic phase tracking
    private SessionPhaseAnalyzer sessionPhaseAnalyzer;  // Phase-aware analysis
    private final Map<String, SignalData> pendingSignals = new ConcurrentHashMap<>();

    // Unified Session Transcript - shared by AI chat and AI Investment Strategist
    private velox.api.layer1.simplified.demo.storage.TranscriptWriter transcriptWriter;

    // Trade logging and session state persistence
    private velox.api.layer1.simplified.demo.storage.TradeLogger tradeLogger;
    private velox.api.layer1.simplified.demo.storage.SessionStateManager sessionStateManager;
    private java.nio.file.Path tradingMemoryDir;  // Path to trading-memory directory

    // Session Context - tracks trading session state for AI
    private SessionContext sessionContext;

    // Data timestamp from Bookmap (for replay mode support)
    // Updated by TimeListener.onTimestamp() before any other data event
    private volatile long currentDataTimestampMs = System.currentTimeMillis();

    // AI re-evaluation tracking
    private long lastAIReevaluationTime = 0;

    // ========== ENHANCED CONFLUENCE INDICATORS ==========
    private final CVDCalculator cvdCalculator = new CVDCalculator();
    private final VolumeProfileCalculator volumeProfile = new VolumeProfileCalculator(50, 1000);
    private final EMACalculator ema9 = new EMACalculator(9);
    private final EMACalculator ema21 = new EMACalculator(21);
    private final EMACalculator ema50 = new EMACalculator(50);
    private final VWAPCalculator vwapCalculator = new VWAPCalculator();
    private final ATRCalculator atrCalculator = new ATRCalculator(14);
    private final DOMAnalyzer domAnalyzer = new DOMAnalyzer(50, 100);  // 50 tick lookback, 100 min wall size

    // ========== CARMINE ROSATO ORDER FLOW TOOLS ==========
    // Per-price delta tracking (Big Fish levels, institutional interest)
    private final PriceDeltaTracker priceDeltaTracker = new PriceDeltaTracker();
    // Volume tail detection (reversal signals from volume profile extremes)
    private final VolumeTailDetector volumeTailDetector = new VolumeTailDetector();
    // Tape speed measurement (urgency detection, exhaustion patterns)
    private final TapeSpeedTracker tapeSpeedTracker = new TapeSpeedTracker();
    // Stop hunt detection (liquidity grabs at key levels)
    private final StopHuntDetector stopHuntDetector = new StopHuntDetector();

    // Tracking for EMAs and VWAP
    private double currentHigh = Double.NaN;
    private double currentLow = Double.NaN;
    private double previousClose = Double.NaN;

    // ========== CUSTOM PANELS ==========
    private StrategyPanel settingsPanel;
    private StrategyPanel statsPanel;

    // Settings Panel Components
    private JLabel minConfluenceLabel;
    private JSpinner minConfluenceSpinner;
    private JLabel thresholdMultLabel;
    private JSpinner thresholdMultSpinner;
    private JLabel adaptiveModeLabel;
    private JCheckBox adaptiveModeCheckBox;
    private JLabel simModeLabel;
    private JCheckBox simModeCheckBox;
    private JLabel autoExecLabel;
    private JCheckBox autoExecCheckBox;
    private JSpinner adaptOrderSpinner;  // Store reference for enabling/disabling
    private JSpinner adaptSizeSpinner;   // Store reference for enabling/disabling

    // Stats Panel Components
    private JLabel totalTradesLabel;
    private JLabel winRateLabel;
    private JLabel totalPnLLabel;
    private JLabel bestTradeLabel;
    private JLabel worstTradeLabel;
    private JLabel todayTradesLabel;
    private JLabel todayWinRateLabel;
    private JLabel todayPnLLabel;
    private JLabel todayDrawdownLabel;
    private JLabel activeSignalsLabel;
    private JLabel lastSignalScoreLabel;
    private JLabel lastSignalTimeLabel;
    private JLabel avgOrderCountLabel;
    private JLabel avgOrderSizeLabel;
    private JLabel currentThresholdLabel;
    private JButton exportButton;

    // AI Threshold UI Components
    private JCheckBox aiAdaptiveModeCheckBox;
    private JButton aiChatButton;
    private JButton aiReevaluateButton;
    private JLabel aiStatusIndicator;

    // AI Trading UI Components (from MCP branch)
    private JCheckBox enableAITradingCheckBox;
    private JComboBox<String> aiModeComboBox;
    private JSpinner confThresholdSpinner;

    // AI Weight Labels (for two-column layout: Base | AI Current)
    private JLabel aiIcebergMaxLabel;
    private JLabel aiCvdAlignLabel;
    private JLabel aiCvdDivergeLabel;
    private JLabel aiEmaAlignLabel;
    private JLabel aiVwapAlignLabel;
    private JLabel aiVwapDivergeLabel;

    // AI Threshold Labels (for Signal Thresholds section)
    private JLabel aiMinConfluenceLabel;
    private JLabel aiConfluenceThresholdLabel;
    private JLabel aiThresholdMultLabel;
    private JLabel aiIcebergMinOrdersLabel;
    private JLabel aiSpoofMinSizeLabel;
    private JLabel aiAbsorptionMinSizeLabel;

    // AI Chat Panel Components
    private JFrame chatWindow;
    private JEditorPane chatHistoryArea;
    private StringBuilder chatHtmlContent;  // Maintain full HTML document
    private JTextField chatInputField;
    private JButton chatSendButton;
    private JButton chatClearButton;
    private List<String[]> chatMessages = new ArrayList<>();  // Store [role, message] pairs

    // Approve/Reject buttons for SEMI_AUTO mode
    private JPanel approvalPanel;
    private JButton approveButton;
    private JButton rejectButton;
    private JLabel approvalLabel;

    // Pending trade request (for SEMI_AUTO approval)
    private static class PendingTradeRequest {
        String requestId;
        boolean isBuy;
        int quantity;
        double price;
        String signalType;
        int score;
        long timestamp;
        Runnable onApprove;
        Runnable onReject;
    }
    private PendingTradeRequest pendingTradeRequest = null;

    // ========== PENDING BRACKET ORDER TRACKING ==========
    // Track bracket orders waiting for fill to update markers with actual prices
    private static class PendingBracketOrder {
        String orderId;          // Entry order ID
        String positionId;       // Position ID for tracking
        boolean isLong;           // Direction
        int signalPrice;          // Signal price in ticks (what we expected)
        int slOffset;             // Stop loss offset in ticks
        int tpOffset;             // Take profit offset in ticks
        int score;                // Signal score
        String reasoning;         // AI reasoning
        long timestamp;           // When order was placed
    }
    private final Map<String, PendingBracketOrder> pendingBracketOrders = new ConcurrentHashMap<>();

    // ========== STATE ==========
    private Map<String, OrderInfo> orders = new HashMap<>();
    private Map<Integer, List<String>> priceLevels = new HashMap<>();

    // Market state tracking
    private double lastKnownPrice = 0;
    private int lastKnownBid = 0;
    private int lastKnownAsk = 0;
    private int lastKnownBidSize = 0;
    private int lastKnownAskSize = 0;
    private long lastPriceDebugLog = 0;  // For throttling price debug logs
    private double priceVolatility = 0;
    private AtomicLong totalVolume = new AtomicLong(0);

    // Signal counts
    private final AtomicInteger icebergCount = new AtomicInteger(0);
    private final AtomicInteger spoofCount = new AtomicInteger(0);
    private final AtomicInteger absorptionCount = new AtomicInteger(0);

    // Cooldown tracking
    private Map<Integer, Long> lastIcebergSignalTime = new HashMap<>();
    private static final long ICEBERG_COOLDOWN_MS = 10000;
    private long lastGlobalSignalTime = 0;  // Global cooldown across all price levels
    private static final long GLOBAL_SIGNAL_COOLDOWN_MS = 2000;  // 2 seconds between ANY signals
    private String lastSignalDirection = null;
    private static final long SIGNAL_DIRECTION_COOLDOWN_MS = 30000;

    // ========== ADAPTIVE THRESHOLDS ==========
    private int totalOrdersSeen = 0;
    private int totalSizeSeen = 0;
    private double avgOrderSize = 1.0;
    private int maxOrdersAtPrice = 0;
    private int maxSizeAtPrice = 0;

    private static final int HISTORY_WINDOW = 100;
    private LinkedList<Integer> recentOrderCounts = new LinkedList<>();
    private LinkedList<Integer> recentTotalSizes = new LinkedList<>();

    private int adaptiveOrderThreshold = 25;  // Increased from 8 - requires more orders at price
    private int adaptiveSizeThreshold = 100;  // Increased from 30 - requires more total size

    // ========== PERFORMANCE TRACKING ==========
    private List<Trade> tradeHistory = new ArrayList<>();
    private final AtomicLong totalPnL = new AtomicLong(0);
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicInteger winningTrades = new AtomicInteger(0);

    // Today's performance
    private List<Trade> todayTrades = new ArrayList<>();
    private double todayPnL = 0.0;
    private double todayMaxDrawdown = 0.0;
    private double todayPeakEquity = 0.0;

    // Alert tracking (prevent repeated alerts)
    private boolean dailyLossAlertShown = false;
    private boolean drawdownWarningShown = false;

    // Current activity
    private List<Integer> activeSignals = new ArrayList<>();
    private Integer lastSignal = null;

    // API and helpers
    private Api api;
    private double pips;
    private String alias;

    // Logging
    private PrintWriter logWriter;
    private PrintWriter signalWriter;
    private static final String LOG_FILE = "enhanced_strategy_log.txt";
    private static final String SIGNAL_FILE = "enhanced_signals.txt";

    // Update timer
    private ScheduledExecutorService updateExecutor;
    private static final int UPDATE_INTERVAL_MS = 1000;  // Update every 1 second

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.api = api;
        this.alias = alias;
        this.pips = info.pips;

        log("========== OrderFlowStrategyEnhanced.initialize() ==========");
        log("Instrument: " + alias);
        log("Pip size from Bookmap: " + info.pips + " (stored as: " + pips + ")");

        // Warn if pips is 0 or very small
        if (pips == 0 || pips < 0.001) {
            log("⚠️ WARNING: Pip size is " + pips + " - price display may be incorrect!");
            log("⚠️ This may indicate the instrument doesn't provide pip info correctly.");
        }

        // Load persisted settings using Bookmap's native API
        settings = api.getSettings(Settings.class);
        log("📂 Settings loaded");

        // Sync local fields from settings (for compatibility with existing code)
        minConfluenceScore = settings.minConfluenceScore;
        thresholdMultiplier = settings.thresholdMultiplier;
        icebergMinOrders = settings.icebergMinOrders;
        spoofMinSize = settings.spoofMinSize;
        absorptionMinSize = settings.absorptionMinSize;
        maxPosition = settings.maxPosition;
        dailyLossLimit = settings.dailyLossLimit;
        simModeOnly = settings.simModeOnly;
        autoExecution = settings.autoExecution;
        useAIAdaptiveThresholds = settings.useAIAdaptiveThresholds;
        enableAITrading = settings.enableAITrading;
        aiMode = settings.aiMode;
        confluenceThreshold = settings.confluenceThreshold;
        aiAuthToken = settings.aiAuthToken;
        // Proxy setting
        useProxy = settings.useProxy != null ? settings.useProxy : true;
        // AI prompt mode
        if (settings.aiPromptMode != null) {
            aiPromptMode = settings.aiPromptMode;
        }
        // Position sizing settings
        accountSize = settings.accountSize;
        riskPerTradePercent = settings.riskPerTradePercent;
        tickValue = settings.tickValue;
        autoCalculatePositionSize = settings.autoCalculatePositionSize;
        fixedPositionSize = settings.fixedPositionSize;
        stopLossTicks = settings.stopLossTicks;
        takeProfitTicks = settings.takeProfitTicks;
        // Smart SL/TP settings
        useSmartSlTp = settings.useSmartSlTp;
        atrSlMultiplier = settings.atrSlMultiplier;
        atrTpMultiplier = settings.atrTpMultiplier;
        minRRRatio = settings.minRRRatio;
        maxSlTicks = settings.maxSlTicks;
        maxTpTicks = settings.maxTpTicks;
        // DOM quality and memory settings
        minDomVolumeForSlTp = settings.minDomVolumeForSlTp;
        prioritizeDomOverAtr = settings.prioritizeDomOverAtr;
        sessionAwareAtrScaling = settings.sessionAwareAtrScaling;
        useTradeMemoryForSlTp = settings.useTradeMemoryForSlTp;
        memoryLookbackTrades = settings.memoryLookbackTrades;

        // Create detection MARKER indicators (Layer 1)
        // These will use custom icons instead of continuous lines
        icebergBuyMarker = api.registerIndicator("🟢 ICEBERG BUY", GraphType.PRIMARY);
        icebergBuyMarker.setColor(Color.GREEN);

        icebergSellMarker = api.registerIndicator("🔴 ICEBERG SELL", GraphType.PRIMARY);
        icebergSellMarker.setColor(Color.RED);

        spoofingMarker = api.registerIndicator("Spoofing Markers", GraphType.PRIMARY);
        spoofingMarker.setColor(Color.MAGENTA);

        absorptionMarker = api.registerIndicator("Absorption Markers", GraphType.PRIMARY);
        absorptionMarker.setColor(Color.YELLOW);

        // Create AI action MARKER indicators (Layer 2)
        aiLongEntryMarker = api.registerIndicator("🔵 AI LONG ENTRY", GraphType.PRIMARY);
        aiLongEntryMarker.setColor(Color.CYAN);

        aiShortEntryMarker = api.registerIndicator("🟣 AI SHORT ENTRY", GraphType.PRIMARY);
        aiShortEntryMarker.setColor(Color.PINK);

        aiExitMarker = api.registerIndicator("AI Exits (SL/TP/BE)", GraphType.PRIMARY);
        aiExitMarker.setColor(Color.ORANGE);

        aiSkipMarker = api.registerIndicator("⚪ AI SKIPS", GraphType.PRIMARY);
        aiSkipMarker.setColor(Color.WHITE);

        // Create AI order level LINE indicators (horizontal lines at SL/TP)
        aiStopLossLine = api.registerIndicator("🛑 AI Stop Loss", GraphType.PRIMARY);
        aiStopLossLine.setColor(Color.ORANGE);

        aiTakeProfitLine = api.registerIndicator("💎 AI Take Profit", GraphType.PRIMARY);
        aiTakeProfitLine.setColor(Color.GREEN);

        // Initialize AI components if enabled at startup
        String effectiveToken = getEffectiveApiToken();
        if (enableAITrading && effectiveToken != null && !effectiveToken.isEmpty()) {
            initializeAIComponents();
        } else {
            log("ℹ️ AI Trading disabled (enable in settings)");
        }

        // Initialize Memory & Sessions (Phase 1)
        try {
            if (effectiveToken != null && !effectiveToken.isEmpty()) {
                log("🧠 Initializing Memory Service...");

                // Get memory directory - use Bookmap's data folder
                String userHome = System.getProperty("user.home");
                Path memoryDir = null;

                // 1. Check for system property override first
                String memoryDirOverride = System.getProperty("qid.memory.dir");
                if (memoryDirOverride != null && !memoryDirOverride.isEmpty()) {
                    Path overridePath = java.nio.file.Paths.get(memoryDirOverride);
                    if (java.nio.file.Files.exists(overridePath)) {
                        memoryDir = overridePath;
                        log("📁 Using memory directory from system property: " + memoryDir);
                    }
                }

                // 2. Use Bookmap's data folder (platform-specific)
                if (memoryDir == null) {
                    String osName = System.getProperty("os.name", "").toLowerCase();
                    Path bookmapDir;

                    if (osName.contains("mac")) {
                        // macOS: ~/Library/Application Support/Bookmap/
                        bookmapDir = java.nio.file.Paths.get(userHome, "Library", "Application Support", "Bookmap");
                    } else if (osName.contains("win")) {
                        // Windows: C:/Bookmap/ or ~/Bookmap/
                        bookmapDir = java.nio.file.Paths.get("C:", "Bookmap");
                        if (!java.nio.file.Files.exists(bookmapDir)) {
                            bookmapDir = java.nio.file.Paths.get(userHome, "Bookmap");
                        }
                    } else {
                        // Linux/other: ~/.bookmap/
                        bookmapDir = java.nio.file.Paths.get(userHome, ".bookmap");
                    }

                    memoryDir = bookmapDir.resolve("trading-memory");

                    // Create directory if it doesn't exist
                    if (!java.nio.file.Files.exists(memoryDir)) {
                        try {
                            java.nio.file.Files.createDirectories(memoryDir);
                            log("📁 Created Bookmap memory directory: " + memoryDir);
                        } catch (Exception e) {
                            log("⚠️ Could not create memory directory: " + e.getMessage());
                        }
                    }
                    log("📁 Using Bookmap memory directory: " + memoryDir);
                }

                // Store for AI tools access
                tradingMemoryDir = memoryDir;

                // Import TradingMemoryService
                velox.api.layer1.simplified.demo.storage.TradingMemoryService service =
                    new velox.api.layer1.simplified.demo.storage.TradingMemoryService(
                        memoryDir.toFile(),
                        aiAuthToken,
                        java.nio.file.Paths.get(".").toAbsolutePath()
                    );

                // Sync memory from disk
                service.sync();

                memoryService = service;
                log("✅ Memory Service initialized: " + service.getIndexedFileCount() + " files, " +
                    service.getIndexedChunkCount() + " chunks");

                // Initialize Trade Logger for persistent trade history
                tradeLogger = new velox.api.layer1.simplified.demo.storage.TradeLogger(memoryDir);
                log("✅ Trade Logger initialized: " + memoryDir.resolve("trade-history.csv"));

                // Initialize Session State Manager for daily stat persistence
                sessionStateManager = new velox.api.layer1.simplified.demo.storage.SessionStateManager(memoryDir);
                log("✅ Session State Manager initialized: " + sessionStateManager.getCurrentState());

                // Initialize Session Context
                sessionContext = new SessionContext();
                log("✅ Session Context initialized");

                // Initialize Unified Session Transcript (shared by AI chat and strategist)
                java.nio.file.Path sessionsDir = java.nio.file.Path.of("sessions");
                transcriptWriter = new velox.api.layer1.simplified.demo.storage.TranscriptWriter(sessionsDir);
                transcriptWriter.initializeSession();
                log("✅ Session Transcript initialized: " + sessionsDir);

                // Initialize AI Investment Strategist (Phase 3) with transcript
                aiStrategist = new AIInvestmentStrategist(service, effectiveToken, transcriptWriter);
                aiStrategist.setPromptMode(aiPromptMode);
                log("✅ AI Investment Strategist initialized");
            } else {
                log("⚠️ Memory Service disabled (no API token)");
            }
        } catch (Exception e) {
            log("⚠️ Memory Service initialization: " + e.getMessage());
            e.printStackTrace();
        }

        // Initialize AI Threshold Service if token provided
        if (effectiveToken != null && !effectiveToken.isEmpty()) {
            aiThresholdService = new AIThresholdService(effectiveToken);
            log("🤖 AI Chat Service initialized" + (useProxy ? " (using proxy)" : ""));

            // Initialize AI Tools Provider for function calling
            aiToolsProvider = new AIToolsProvider();
            setupAIToolsProvider();  // Connect data suppliers
            aiThresholdService.setToolsProvider(aiToolsProvider);
            log("🔧 AI Tools Provider initialized (function calling enabled)");

            // Trigger initial AI threshold calculation if AI adaptive mode is enabled
            if (useAIAdaptiveThresholds) {
                log("🔄 AI Adaptive mode enabled - will calculate optimal thresholds on first data");
            }
        }

        // Initialize log files
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
            signalWriter = new PrintWriter(new FileWriter(SIGNAL_FILE, true));
        } catch (IOException e) {
            System.err.println("Failed to initialize log files: " + e.getMessage());
        }

        // Start update timer
        startUpdateTimer();

        log("========== OrderFlowStrategyEnhanced.initialize() COMPLETE ==========");
        log("Settings Panel: ENABLED");
        log("Statistics Panel: ENABLED");
        log("Performance Tracking: ENABLED");
        log("Waiting for MBO events...");
    }

    private void startUpdateTimer() {
        updateExecutor = Executors.newSingleThreadScheduledExecutor();

        // Update stats panel every second
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                SwingUtilities.invokeLater(this::updateStatsPanel);
            } catch (Exception e) {
                System.err.println("Error updating stats panel: " + e.getMessage());
            }
        }, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Draw SL/TP lines every 100ms if active position exists
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                drawAITradingLevels();
            } catch (Exception e) {
                System.err.println("Error drawing AI levels: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Generate performance report every 5 minutes (silent, file only)
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport(false);  // false = no popup, file only
            } catch (Exception e) {
                System.err.println("Error generating performance report: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);  // Start after 5 minutes, then every 5 minutes

        // Periodic threshold reassessment every 5 minutes
        // This ensures thresholds are adjusted when session phase changes, even if no signals come in
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                periodicThresholdReassessment();
            } catch (Exception e) {
                System.err.println("Error in threshold reassessment: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);  // Start after 5 minutes, then every 5 minutes

        // Draw SL/TP lines every 100ms (creates horizontal line effect)
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                drawAITradingLevels();
            } catch (Exception e) {
                System.err.println("Error drawing AI levels: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    // Track last reassessed phase to detect changes
    private SessionContext.SessionPhase lastReassessedPhase = null;

    /**
     * Periodic threshold reassessment
     * Called every 5 minutes to check if thresholds should be adjusted
     * based on session phase changes, even when no signals are coming in
     */
    private void periodicThresholdReassessment() {
        if (sessionContext == null || !enableAITrading || aiStrategist == null) {
            return;
        }

        SessionContext.SessionPhase currentPhase = sessionContext.getCurrentPhase();

        // Check if phase has changed since last reassessment
        if (lastReassessedPhase != null && currentPhase == lastReassessedPhase) {
            // No phase change, no need to reassess
            return;
        }

        log("🔄 PERIODIC REASSESSMENT: Phase changed from " + lastReassessedPhase + " to " + currentPhase);

        // Build market context for reassessment
        AIThresholdService.MarketContext context = buildMarketContext();

        // Ask AI to reassess thresholds for this phase
        String reassessmentPrompt = String.format("""
            PERIODIC THRESHOLD REASSESSMENT

            The trading session has moved into a new phase: %s

            Current market state:
            - Price: %.2f
            - CVD: %d
            - Session: %d minutes in, %d trades
            - Current thresholds: minScore=%d, icebergMinOrders=%d

            Should we adjust thresholds for this phase? Consider:
            1. Phase-specific volatility (opening/closing = higher, lunch = lower)
            2. Current CVD trend strength
            3. Time into session (early = more caution, established = can be more aggressive)

            If adjustment is needed, respond with JSON:
            {"thresholdAdjustment": {"field": value, "reasoning": "why"}}

            If no adjustment needed, respond with:
            {"thresholdAdjustment": null}
            """,
            currentPhase,
            context.currentPrice,
            (long) context.cvd,
            sessionContext.getMinutesIntoSession(),
            sessionContext.getTradesThisSession(),
            minConfluenceScore,
            icebergMinOrders
        );

        // Use AI threshold service for reassessment
        aiThresholdService.chatWithTools(reassessmentPrompt)
            .thenAccept(response -> {
                try {
                    // Parse response for threshold adjustment
                    if (response.contains("thresholdAdjustment") && !response.contains("\"null\"")) {
                        log("📊 Threshold adjustment suggested: " + response);
                        // The adjustment will be parsed and applied by the existing mechanism
                    } else {
                        log("✅ No threshold adjustment needed for " + currentPhase);
                    }
                } catch (Exception e) {
                    log("Error parsing reassessment response: " + e.getMessage());
                }
            })
            .exceptionally(ex -> {
                log("❌ Reassessment failed: " + ex.getMessage());
                return null;
            });

        lastReassessedPhase = currentPhase;
    }

    /**
     * Draw horizontal lines at active SL/TP levels
     * Uses addPoint() to draw horizontal lines on the chart
     */
    private long lastLevelLogTime = 0;
    private void drawAITradingLevels() {
        try {
            // Only draw if we have active SL/TP levels and valid indicators
            if (activeStopLossPrice == null || activeTakeProfitPrice == null) {
                return;
            }
            if (aiStopLossLine == null || aiTakeProfitLine == null) {
                return;
            }

            // Add points to draw horizontal lines at SL/TP levels
            // addPoint() expects tick values, which we already have
            aiStopLossLine.addPoint(activeStopLossPrice);
            aiTakeProfitLine.addPoint(activeTakeProfitPrice);

            // Throttled logging (every 10 seconds)
            long now = System.currentTimeMillis();
            if (now - lastLevelLogTime > 10000) {
                fileLog("📐 drawAITradingLevels: SL=" + activeStopLossPrice + " TP=" + activeTakeProfitPrice + " ticks");
                lastLevelLogTime = now;
            }
        } catch (Exception e) {
            // Don't spam logs for drawing errors
            if (System.currentTimeMillis() - lastLevelLogTime > 60000) {
                fileLog("❌ drawAITradingLevels error: " + e.getMessage());
                lastLevelLogTime = System.currentTimeMillis();
            }
        }
    }

    // ========== CUSTOM PANELS ==========

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        List<StrategyPanel> panels = new ArrayList<>();

        // Settings Panel
        if (settingsPanel == null) {
            createSettingsPanel();
        }
        panels.add(settingsPanel);

        // Statistics Panel
        if (statsPanel == null) {
            createStatsPanel();
        }
        panels.add(statsPanel);

        return panels.toArray(new StrategyPanel[0]);
    }

    private void createSettingsPanel() {
        settingsPanel = new StrategyPanel("Order Flow Settings");
        settingsPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Title
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel title = new JLabel("Strategy Settings");
        title.setFont(new Font("Arial", Font.BOLD, 14));
        settingsPanel.add(title, gbc);

        // Signal Thresholds section
        gbc.gridy = 1; gbc.gridwidth = 3;
        addSeparator(settingsPanel, "Signal Thresholds", gbc);

        // Column headers for thresholds
        gbc.gridy = 2; gbc.gridwidth = 1;
        gbc.gridx = 1;
        settingsPanel.add(new JLabel("Base"), gbc);
        gbc.gridx = 2;
        settingsPanel.add(new JLabel("AI Current"), gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        JLabel minConfLabel = new JLabel("Min Confluence Score:");
        minConfLabel.setToolTipText("<html>Minimum total score required to generate a signal.<br>Score is sum of all confluence factors (iceberg, CVD, EMA, VWAP).<br><br>• 30-40 = More signals, lower quality<br>• 50-60 = Balanced (recommended)<br>• 70+ = Fewer signals, higher quality</html>");
        settingsPanel.add(minConfLabel, gbc);
        gbc.gridx = 1;
        minConfluenceSpinner = new JSpinner(new SpinnerNumberModel(minConfluenceScore.intValue(), 0, 150, 5));
        minConfluenceSpinner.setToolTipText("Total confluence points needed (default: 40)");
        minConfluenceSpinner.addChangeListener(e -> updateMinConfluence());
        settingsPanel.add(minConfluenceSpinner, gbc);
        gbc.gridx = 2;
        aiMinConfluenceLabel = new JLabel(String.valueOf(minConfluenceScore));
        aiMinConfluenceLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiMinConfluenceLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        JLabel threshMultLabel = new JLabel("Threshold Multiplier:");
        threshMultLabel.setToolTipText("<html>Scales all detection thresholds based on market volatility.<br><br>• 1.5-2.0 = Lower thresholds (more sensitive)<br>• 2.5-3.0 = Normal (recommended)<br>• 3.5-5.0 = Higher thresholds (less sensitive)</html>");
        settingsPanel.add(threshMultLabel, gbc);
        gbc.gridx = 1;
        thresholdMultSpinner = new JSpinner(new SpinnerNumberModel(thresholdMultiplier.doubleValue(), 1.5, 5.0, 0.5));
        thresholdMultSpinner.setToolTipText("Multiplier for adaptive thresholds (default: 2.5)");
        thresholdMultSpinner.addChangeListener(e -> updateThresholdMultiplier());
        settingsPanel.add(thresholdMultSpinner, gbc);
        gbc.gridx = 2;
        aiThresholdMultLabel = new JLabel(String.format("%.1f", thresholdMultiplier));
        aiThresholdMultLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiThresholdMultLabel, gbc);

        // Minimum Detection Thresholds with AI Current column
        gbc.gridx = 0; gbc.gridy = 5;
        JLabel icebergOrdersLabel = new JLabel("Iceberg Min Orders:");
        icebergOrdersLabel.setToolTipText("Minimum orders at one price to trigger iceberg signal");
        settingsPanel.add(icebergOrdersLabel, gbc);
        gbc.gridx = 1;
        JSpinner icebergOrdersSpinner = new JSpinner(new SpinnerNumberModel(icebergMinOrders.intValue(), 5, 100, 5));
        icebergOrdersSpinner.setToolTipText("Higher = fewer but more reliable signals (default: 20)");
        icebergOrdersSpinner.addChangeListener(e -> icebergMinOrders = (Integer) icebergOrdersSpinner.getValue());
        settingsPanel.add(icebergOrdersSpinner, gbc);
        gbc.gridx = 2;
        aiIcebergMinOrdersLabel = new JLabel(String.valueOf(icebergMinOrders));
        aiIcebergMinOrdersLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiIcebergMinOrdersLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 6;
        JLabel spoofSizeLabel = new JLabel("Spoof Min Size:");
        spoofSizeLabel.setToolTipText("Minimum order size to consider as potential spoof");
        settingsPanel.add(spoofSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner spoofSizeSpinner = new JSpinner(new SpinnerNumberModel(spoofMinSize.intValue(), 5, 100, 5));
        spoofSizeSpinner.setToolTipText("Higher = fewer spoof signals (default: 20)");
        spoofSizeSpinner.addChangeListener(e -> spoofMinSize = (Integer) spoofSizeSpinner.getValue());
        settingsPanel.add(spoofSizeSpinner, gbc);
        gbc.gridx = 2;
        aiSpoofMinSizeLabel = new JLabel(String.valueOf(spoofMinSize));
        aiSpoofMinSizeLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiSpoofMinSizeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 7;
        JLabel absorbSizeLabel = new JLabel("Absorption Min Size:");
        absorbSizeLabel.setToolTipText("Minimum trade size to detect absorption");
        settingsPanel.add(absorbSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner absorbSizeSpinner = new JSpinner(new SpinnerNumberModel(absorptionMinSize.intValue(), 10, 200, 10));
        absorbSizeSpinner.setToolTipText("Higher = fewer absorption signals (default: 50)");
        absorbSizeSpinner.addChangeListener(e -> absorptionMinSize = (Integer) absorbSizeSpinner.getValue());
        settingsPanel.add(absorbSizeSpinner, gbc);
        gbc.gridx = 2;
        aiAbsorptionMinSizeLabel = new JLabel(String.valueOf(absorptionMinSize));
        aiAbsorptionMinSizeLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiAbsorptionMinSizeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 3;
        addSeparator(settingsPanel, "AI Adaptive Thresholds", gbc);

        gbc.gridy = 9; gbc.gridwidth = 2;
        settingsPanel.add(new JLabel("AI Auth Token:"), gbc);
        gbc.gridy = 10; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField aiTokenField = new JTextField();
        aiTokenField.setText(aiAuthToken);
        aiTokenField.setToolTipText("<html>Your Claude API token for z.ai<br>Leave empty if using proxy</html>");
        aiTokenField.setEnabled(!useProxy);  // Disable if using proxy
        aiTokenField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                aiAuthToken = aiTokenField.getText();
                settings.aiAuthToken = aiAuthToken;
                saveSettings();
                // Reinitialize AI service with effective token
                String token = getEffectiveApiToken();
                if (token != null && !token.isEmpty()) {
                    aiThresholdService = new AIThresholdService(token);
                    log("🤖 AI Auth Token updated - Service initialized" + (useProxy ? " (proxy)" : ""));
                } else {
                    aiThresholdService = null;
                    log("⚠️ AI Auth Token cleared - Service disabled");
                }
            }
        });
        settingsPanel.add(aiTokenField, gbc);

        // Use Proxy checkbox
        gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 2;
        JCheckBox useProxyCheckBox = new JCheckBox("Use Proxy (sk-zai-proxy-key)", useProxy);
        useProxyCheckBox.setToolTipText("<html>When enabled, uses the proxy server instead of direct API calls.<br>Disable this to use your own API token above.</html>");
        JTextField finalAiTokenField = aiTokenField;
        useProxyCheckBox.addActionListener(e -> {
            useProxy = useProxyCheckBox.isSelected();
            settings.useProxy = useProxy;
            finalAiTokenField.setEnabled(!useProxy);
            saveSettings();
            // Reinitialize with new setting
            String token = getEffectiveApiToken();
            if (token != null && !token.isEmpty()) {
                aiThresholdService = new AIThresholdService(token);
                log("🤖 AI Proxy mode: " + (useProxy ? "ENABLED" : "DISABLED") + " - Service initialized");
            } else {
                aiThresholdService = null;
                log("⚠️ No API token available - Service disabled");
            }
        });
        settingsPanel.add(useProxyCheckBox, gbc);

        gbc.fill = GridBagConstraints.NONE;  // Reset fill

        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 1;
        JLabel useAiAdaptiveLabel = new JLabel("Use AI Adaptive:");
        useAiAdaptiveLabel.setToolTipText("Enable AI to automatically adjust thresholds based on market conditions");
        settingsPanel.add(useAiAdaptiveLabel, gbc);
        gbc.gridx = 1;
        aiAdaptiveModeCheckBox = new JCheckBox();
        aiAdaptiveModeCheckBox.setSelected(useAIAdaptiveThresholds);
        aiAdaptiveModeCheckBox.setToolTipText("<html>When enabled, AI analyzes market conditions and optimizes thresholds automatically.<br>Adjusts: Min Confluence, Threshold Multiplier, Detection thresholds</html>");
        aiAdaptiveModeCheckBox.addActionListener(e -> updateAIAdaptiveMode());
        settingsPanel.add(aiAdaptiveModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 2;
        settingsPanel.add(new JLabel("AI Status:"), gbc);
        gbc.gridy = 13;
        aiStatusIndicator = new JLabel(enableAITrading ? "🟢 AI Trading Active" : "🔴 AI Disabled");
        aiStatusIndicator.setForeground(enableAITrading ? new Color(0, 150, 0) : Color.GRAY);
        settingsPanel.add(aiStatusIndicator, gbc);

        gbc.gridy = 14; gbc.gridwidth = 1;
        JButton aiChatButton = new JButton("💬 Open AI Chat");
        aiChatButton.setToolTipText("Open AI Chat window");
        String token = getEffectiveApiToken();
        aiChatButton.setEnabled(token != null && !token.isEmpty());
        aiChatButton.addActionListener(e -> openAIChatWindow());
        settingsPanel.add(aiChatButton, gbc);

        gbc.gridy = 15; gbc.gridwidth = 1;
        aiReevaluateButton = new JButton("🔄 Optimize Thresholds");
        aiReevaluateButton.setToolTipText("Ask AI to optimize trading thresholds based on market conditions");
        aiReevaluateButton.setEnabled(token != null && !token.isEmpty());
        aiReevaluateButton.addActionListener(e -> triggerAIReevaluation());
        settingsPanel.add(aiReevaluateButton, gbc);

        // Pre-market Prep button
        gbc.gridy = 16; gbc.gridwidth = 1;
        JButton preMarketButton = new JButton("📊 Pre-market Prep");
        preMarketButton.setToolTipText("Run AI pre-market analysis: sentiment, key levels, trade plan. Saves to memory.");
        preMarketButton.setEnabled(token != null && !token.isEmpty());
        preMarketButton.addActionListener(e -> runPreMarketAnalysis());
        settingsPanel.add(preMarketButton, gbc);

        // AI Investment Strategist section
        gbc.gridx = 0; gbc.gridy = 17; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "AI Investment Strategist (Qid v2.0)", gbc);

        gbc.gridy = 18; gbc.gridwidth = 1;
        JLabel enableAiTradingLabel = new JLabel("Enable AI Trading:");
        enableAiTradingLabel.setToolTipText("Enable Qid AI to evaluate signals and manage trades");
        settingsPanel.add(enableAiTradingLabel, gbc);
        gbc.gridx = 1;
        JCheckBox enableAITradingCheckBox = new JCheckBox();
        enableAITradingCheckBox.setSelected(enableAITrading);
        enableAITradingCheckBox.setToolTipText("<html>Enable AI (Qid) to evaluate trading signals and manage positions.<br>Requires AI Auth Token to be configured.</html>");
        enableAITradingCheckBox.addActionListener(e -> {
            enableAITrading = enableAITradingCheckBox.isSelected();
            log("🤖 AI Trading " + (enableAITrading ? "ENABLED" : "DISABLED"));

            // Update status indicator
            if (enableAITrading) {
                aiStatusIndicator.setText("🟢 AI Trading Active");
                aiStatusIndicator.setForeground(new Color(0, 150, 0));
            } else {
                aiStatusIndicator.setText("🔴 AI Disabled");
                aiStatusIndicator.setForeground(Color.GRAY);
            }

            // Initialize AI components if enabling and not already initialized
            if (enableAITrading && aiOrderManager == null) {
                initializeAIComponents();
            }
        });
        settingsPanel.add(enableAITradingCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 19;
        JLabel aiModeLabel = new JLabel("AI Mode:");
        aiModeLabel.setToolTipText("<html>AI trading autonomy level:<br>• MANUAL: AI only suggests, you execute<br>• SEMI_AUTO: AI executes approved signals<br>• FULL_AUTO: AI manages all trading</html>");
        settingsPanel.add(aiModeLabel, gbc);
        gbc.gridx = 1;
        String[] aiModes = {"MANUAL", "SEMI_AUTO", "FULL_AUTO"};
        JComboBox<String> aiModeComboBox = new JComboBox<>(aiModes);
        aiModeComboBox.setSelectedItem(aiMode);
        aiModeComboBox.setToolTipText("<html>MANUAL: AI suggests trades, you decide<br>SEMI_AUTO: AI executes above threshold<br>FULL_AUTO: AI manages everything</html>");
        aiModeComboBox.addActionListener(e -> {
            aiMode = (String) aiModeComboBox.getSelectedItem();
            log("🔄 AI Mode: " + aiMode);
        });
        settingsPanel.add(aiModeComboBox, gbc);

        // Dev Mode checkbox - for testing with permissive AI
        gbc.gridx = 0; gbc.gridy = 20;
        JLabel devModeLabel = new JLabel("Dev Mode:");
        devModeLabel.setToolTipText("Testing mode with more permissive AI decisions");
        settingsPanel.add(devModeLabel, gbc);
        gbc.gridx = 1;
        JCheckBox devModeCheckBox = new JCheckBox();
        devModeCheckBox.setSelected(settings.devMode != null ? settings.devMode : devMode);
        devModeCheckBox.setToolTipText("<html>In Dev Mode, AI is more permissive for testing execution flow.<br>Use for testing only - not for live trading!</html>");
        devModeCheckBox.addActionListener(e -> {
            devMode = devModeCheckBox.isSelected();
            settings.devMode = devMode;
            log("🔧 Dev Mode: " + (devMode ? "ENABLED (permissive AI)" : "DISABLED (normal AI)"));
            saveSettings();
        });
        settingsPanel.add(devModeCheckBox, gbc);
        // Sync local var with saved setting
        if (settings.devMode != null) {
            devMode = settings.devMode;
        }

        // AI Prompt Mode (FULL/COMPACT)
        gbc.gridx = 0; gbc.gridy = 21;
        JLabel promptModeLabel = new JLabel("Prompt Mode:");
        promptModeLabel.setToolTipText("AI prompt verbosity - affects speed and token usage");
        settingsPanel.add(promptModeLabel, gbc);
        gbc.gridx = 1;
        JComboBox<String> promptModeCombo = new JComboBox<>(new String[]{"COMPACT", "FULL"});
        promptModeCombo.setSelectedItem(settings.aiPromptMode != null ? settings.aiPromptMode : aiPromptMode);
        promptModeCombo.setToolTipText("<html>COMPACT: Faster responses, fewer tokens, less context<br>FULL: Detailed prompts with full market context</html>");
        promptModeCombo.addActionListener(e -> {
            aiPromptMode = (String) promptModeCombo.getSelectedItem();
            settings.aiPromptMode = aiPromptMode;
            if (aiStrategist != null) {
                aiStrategist.setPromptMode(aiPromptMode);
            }
            log("🤖 Prompt Mode: " + aiPromptMode);
            saveSettings();
        });
        settingsPanel.add(promptModeCombo, gbc);
        // Note: aiPromptMode is synced from settings during initialize(), no need to sync again here

        gbc.gridx = 0; gbc.gridy = 22;
        JLabel confThreshLabel = new JLabel("Confluence Threshold:");
        confThreshLabel.setToolTipText("<html>Minimum score for AI to consider executing a trade.<br>Higher = more conservative (fewer trades)<br>Lower = more aggressive (more trades)</html>");
        settingsPanel.add(confThreshLabel, gbc);
        gbc.gridx = 1;
        confThresholdSpinner = new JSpinner(new SpinnerNumberModel(confluenceThreshold.intValue(), 0, 135, 5));
        confThresholdSpinner.setToolTipText("AI execution threshold (default: 50)");
        confThresholdSpinner.addChangeListener(e -> confluenceThreshold = (Integer) confThresholdSpinner.getValue());
        settingsPanel.add(confThresholdSpinner, gbc);
        gbc.gridx = 2;
        aiConfluenceThresholdLabel = new JLabel(String.valueOf(confluenceThreshold));
        aiConfluenceThresholdLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiConfluenceThresholdLabel, gbc);

        // AI Managed Weights toggle
        gbc.gridx = 0; gbc.gridy = 23; gbc.gridwidth = 1;
        JLabel aiManagedLabel = new JLabel("AI Managed Weights:");
        aiManagedLabel.setToolTipText("When enabled, AI can adjust confluence weights automatically");
        settingsPanel.add(aiManagedLabel, gbc);
        gbc.gridx = 1;
        JCheckBox aiManagedWeightsCheckBox = new JCheckBox();
        aiManagedWeightsCheckBox.setSelected(aiManagedWeights);
        aiManagedWeightsCheckBox.setToolTipText("Enable to let AI fine-tune scoring weights based on market conditions");
        aiManagedWeightsCheckBox.addActionListener(e -> {
            aiManagedWeights = aiManagedWeightsCheckBox.isSelected();
            log("⚖️ AI Managed Weights: " + (aiManagedWeights ? "ENABLED" : "DISABLED"));
        });
        settingsPanel.add(aiManagedWeightsCheckBox, gbc);

        // Auto Phase Analysis toggle
        gbc.gridx = 0; gbc.gridy = 24;
        JLabel autoPhaseLabel = new JLabel("Auto Phase Analysis:");
        autoPhaseLabel.setToolTipText("Automatically run session analysis at phase transitions (pre-market, morning, lunch, etc.)");
        settingsPanel.add(autoPhaseLabel, gbc);
        gbc.gridx = 1;
        JCheckBox autoPhaseCheckBox = new JCheckBox();
        autoPhaseCheckBox.setSelected(autoPhaseAnalysis);
        autoPhaseCheckBox.setToolTipText("When enabled, AI analyzes market at each phase transition and maintains session context");
        autoPhaseCheckBox.addActionListener(e -> {
            autoPhaseAnalysis = autoPhaseCheckBox.isSelected();
            log("📊 Auto Phase Analysis: " + (autoPhaseAnalysis ? "ENABLED" : "DISABLED"));
        });
        settingsPanel.add(autoPhaseCheckBox, gbc);

        // Confluence Weights section (collapsible-style)
        gbc.gridx = 0; gbc.gridy = 25; gbc.gridwidth = 3;
        addSeparator(settingsPanel, "Confluence Weights (Advanced)", gbc);

        // Column headers for weights
        gbc.gridy = 26; gbc.gridwidth = 1;
        gbc.gridx = 1;
        settingsPanel.add(new JLabel("Base"), gbc);
        gbc.gridx = 2;
        settingsPanel.add(new JLabel("AI Current"), gbc);

        // Weight controls - key weights with AI Current column
        gbc.gridx = 0; gbc.gridy = 27; gbc.gridwidth = 1;
        JLabel icebergMaxLabel = new JLabel("Iceberg Max (20-60):");
        icebergMaxLabel.setToolTipText("<html>Maximum points from iceberg detection.<br>Higher = more weight on iceberg signals<br>Lower = icebergs contribute less to score</html>");
        settingsPanel.add(icebergMaxLabel, gbc);
        gbc.gridx = 1;
        JSpinner icebergMaxSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX), 20, 60, 5));
        icebergMaxSpinner.setToolTipText("Points for strong iceberg detection (default: 40)");
        icebergMaxSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.ICEBERG_MAX, (Integer) icebergMaxSpinner.getValue());
            log("Weight: icebergMax = " + confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX));
        });
        settingsPanel.add(icebergMaxSpinner, gbc);
        gbc.gridx = 2;
        aiIcebergMaxLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX)));
        aiIcebergMaxLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiIcebergMaxLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 27;
        JLabel cvdAlignLabel = new JLabel("CVD Align Max (10-40):");
        cvdAlignLabel.setToolTipText("<html>Points when CVD (Cumulative Volume Delta) aligns with signal direction.<br>CVD shows if buyers or sellers are in control.</html>");
        settingsPanel.add(cvdAlignLabel, gbc);
        gbc.gridx = 1;
        JSpinner cvdAlignSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX), 10, 40, 5));
        cvdAlignSpinner.setToolTipText("Points for CVD confirming direction (default: 25)");
        cvdAlignSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.CVD_ALIGN_MAX, (Integer) cvdAlignSpinner.getValue());
            log("Weight: cvdAlignMax = " + confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX));
        });
        settingsPanel.add(cvdAlignSpinner, gbc);
        gbc.gridx = 2;
        aiCvdAlignLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX)));
        aiCvdAlignLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiCvdAlignLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 28;
        JLabel cvdDivergeLabel = new JLabel("CVD Diverge Penalty (15-50):");
        cvdDivergeLabel.setToolTipText("<html>Penalty when CVD diverges from signal direction.<br>Example: Long signal but sellers dominate = penalty<br>Higher = stronger filter against diverging signals</html>");
        settingsPanel.add(cvdDivergeLabel, gbc);
        gbc.gridx = 1;
        JSpinner cvdDivergeSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY), 15, 50, 5));
        cvdDivergeSpinner.setToolTipText("Penalty for CVD against direction (default: 30)");
        cvdDivergeSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.CVD_DIVERGE_PENALTY, (Integer) cvdDivergeSpinner.getValue());
            log("Weight: cvdDivergePenalty = " + confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY));
        });
        settingsPanel.add(cvdDivergeSpinner, gbc);
        gbc.gridx = 2;
        aiCvdDivergeLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY)));
        aiCvdDivergeLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiCvdDivergeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 29;
        JLabel emaAlignLabel = new JLabel("EMA Align Max (10-30):");
        emaAlignLabel.setToolTipText("<html>Points when EMAs (9, 21, 50) align with signal direction.<br>More EMAs aligned = more points (up to this max)</html>");
        settingsPanel.add(emaAlignLabel, gbc);
        gbc.gridx = 1;
        JSpinner emaAlignSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX), 10, 30, 5));
        emaAlignSpinner.setToolTipText("Points for EMA trend alignment (default: 20)");
        emaAlignSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.EMA_ALIGN_MAX, (Integer) emaAlignSpinner.getValue());
            log("Weight: emaAlignMax = " + confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX));
        });
        settingsPanel.add(emaAlignSpinner, gbc);
        gbc.gridx = 2;
        aiEmaAlignLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX)));
        aiEmaAlignLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiEmaAlignLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 30;
        JLabel vwapAlignLabel = new JLabel("VWAP Align (5-15):");
        vwapAlignLabel.setToolTipText("<html>Points when price is favorably positioned relative to VWAP.<br>Long above VWAP or Short below VWAP = bonus</html>");
        settingsPanel.add(vwapAlignLabel, gbc);
        gbc.gridx = 1;
        JSpinner vwapAlignSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN), 5, 15, 1));
        vwapAlignSpinner.setToolTipText("Points for VWAP alignment (default: 10)");
        vwapAlignSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.VWAP_ALIGN, (Integer) vwapAlignSpinner.getValue());
            log("Weight: vwapAlign = " + confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN));
        });
        settingsPanel.add(vwapAlignSpinner, gbc);
        gbc.gridx = 2;
        aiVwapAlignLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN)));
        aiVwapAlignLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiVwapAlignLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 31;
        JLabel vwapDivergeLabel = new JLabel("VWAP Diverge (2-10):");
        vwapDivergeLabel.setToolTipText("<html>Penalty when price is unfavorably positioned relative to VWAP.<br>Long below VWAP or Short above VWAP = penalty</html>");
        settingsPanel.add(vwapDivergeLabel, gbc);
        gbc.gridx = 1;
        JSpinner vwapDivergeSpinner = new JSpinner(new SpinnerNumberModel(
            confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE), 2, 10, 1));
        vwapDivergeSpinner.setToolTipText("Penalty for VWAP divergence (default: 5)");
        vwapDivergeSpinner.addChangeListener(e -> {
            confluenceWeights.set(ConfluenceWeights.VWAP_DIVERGE, (Integer) vwapDivergeSpinner.getValue());
            log("Weight: vwapDiverge = " + confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE));
        });
        settingsPanel.add(vwapDivergeSpinner, gbc);
        gbc.gridx = 2;
        aiVwapDivergeLabel = new JLabel(String.valueOf(confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE)));
        aiVwapDivergeLabel.setForeground(new java.awt.Color(100, 100, 255));
        settingsPanel.add(aiVwapDivergeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 32;
        JButton resetWeightsBtn = new JButton("Reset Weights");
        resetWeightsBtn.setToolTipText("Reset all weights to default values");
        resetWeightsBtn.addActionListener(e -> {
            confluenceWeights.resetToDefaults();
            // Update spinners to reflect defaults
            icebergMaxSpinner.setValue(confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX));
            cvdAlignSpinner.setValue(confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX));
            cvdDivergeSpinner.setValue(confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY));
            emaAlignSpinner.setValue(confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX));
            vwapAlignSpinner.setValue(confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN));
            vwapDivergeSpinner.setValue(confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE));
            // Update AI labels too
            updateAIWeightLabels();
            log("⚖️ Weights reset to defaults");
        });
        settingsPanel.add(resetWeightsBtn, gbc);

        // Max Slippage setting
        gbc.gridx = 0; gbc.gridy = 33;
        JLabel slippageLabel = new JLabel("Max Slippage (ticks):");
        slippageLabel.setToolTipText("Reject signal if price moved more than this many ticks");
        settingsPanel.add(slippageLabel, gbc);
        gbc.gridx = 1;
        int savedSlippage = settings.maxSlippageTicks != null ? settings.maxSlippageTicks : maxSlippageTicks;
        JSpinner slippageSpinner = new JSpinner(new SpinnerNumberModel(savedSlippage, 5, 100, 5));
        slippageSpinner.setToolTipText("Higher = allow more price movement (default: 50)");
        slippageSpinner.addChangeListener(e -> {
            maxSlippageTicks = (Integer) slippageSpinner.getValue();
            settings.maxSlippageTicks = maxSlippageTicks;
            if (aiOrderManager != null) {
                aiOrderManager.maxPriceSlippageTicks = maxSlippageTicks;
            }
            log("📏 Max Slippage: " + maxSlippageTicks + " ticks");
            saveSettings();
        });
        settingsPanel.add(slippageSpinner, gbc);
        // Sync local var with saved setting
        if (settings.maxSlippageTicks != null) {
            maxSlippageTicks = settings.maxSlippageTicks;
        }

        // Safety Controls section
        gbc.gridx = 0; gbc.gridy = 34; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Safety Controls", gbc);

        gbc.gridx = 0; gbc.gridy = 35; gbc.gridwidth = 1;
        JLabel simModeLabel = new JLabel("Simulation Mode Only:");
        simModeLabel.setToolTipText("When enabled, no real orders are sent to exchange");
        settingsPanel.add(simModeLabel, gbc);
        gbc.gridx = 1;
        simModeCheckBox = new JCheckBox();
        simModeCheckBox.setSelected(simModeOnly);
        simModeCheckBox.setToolTipText("<html>SAFETY: When enabled, strategy simulates trades without real orders.<br>Enable for testing, disable for live trading.</html>");
        simModeCheckBox.addActionListener(e -> updateSimMode());
        settingsPanel.add(simModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 36;
        JLabel autoExecLabel = new JLabel("Enable Auto-Execution:");
        autoExecLabel.setToolTipText("When enabled, signals automatically execute without confirmation");
        settingsPanel.add(autoExecLabel, gbc);
        gbc.gridx = 1;
        autoExecCheckBox = new JCheckBox();
        autoExecCheckBox.setSelected(autoExecution);
        autoExecCheckBox.setToolTipText("<html>When enabled, signals that pass thresholds are executed automatically.<br>When disabled, signals are logged but not executed.</html>");
        autoExecCheckBox.addActionListener(e -> updateAutoExecution());
        settingsPanel.add(autoExecCheckBox, gbc);

        // Risk Management section
        gbc.gridx = 0; gbc.gridy = 37; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Risk Management", gbc);

        gbc.gridx = 0; gbc.gridy = 38; gbc.gridwidth = 1;
        JLabel maxPosLabel = new JLabel("Max Position:");
        maxPosLabel.setToolTipText("<html>Maximum number of contracts/shares allowed at once.<br>Limits exposure in the market.</html>");
        settingsPanel.add(maxPosLabel, gbc);
        gbc.gridx = 1;
        JSpinner maxPosSpinner = new JSpinner(new SpinnerNumberModel(maxPosition.intValue(), 1, 10, 1));
        maxPosSpinner.setToolTipText("Maximum position size in contracts (default: 3)");
        maxPosSpinner.addChangeListener(e -> maxPosition = (Integer) maxPosSpinner.getValue());
        settingsPanel.add(maxPosSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 39;
        JLabel lossLimitLabel = new JLabel("Daily Loss Limit ($):");
        lossLimitLabel.setToolTipText("<html>Maximum loss allowed per day.<br>Trading stops when this limit is reached.<br>Critical risk management control!</html>");
        settingsPanel.add(lossLimitLabel, gbc);
        gbc.gridx = 1;
        JSpinner lossLimitSpinner = new JSpinner(new SpinnerNumberModel(dailyLossLimit.doubleValue(), 100.0, 5000.0, 100.0));
        lossLimitSpinner.setToolTipText("Stop trading if daily losses exceed this (default: $500)");
        lossLimitSpinner.addChangeListener(e -> dailyLossLimit = (Double) lossLimitSpinner.getValue());
        settingsPanel.add(lossLimitSpinner, gbc);

        // Position Sizing (within Risk Management)
        gbc.gridx = 0; gbc.gridy = 40;
        JLabel accountSizeLabel = new JLabel("Account Size ($):");
        accountSizeLabel.setToolTipText("<html>Your trading account balance.<br>Used to calculate position sizes based on risk %.</html>");
        settingsPanel.add(accountSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner accountSizeSpinner = new JSpinner(new SpinnerNumberModel(accountSize.doubleValue(), 1000.0, 1000000.0, 1000.0));
        accountSizeSpinner.setToolTipText("Total account balance for risk calculations (default: $25,000)");
        accountSizeSpinner.addChangeListener(e -> accountSize = (Double) accountSizeSpinner.getValue());
        settingsPanel.add(accountSizeSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 41;
        JLabel riskPerTradeLabel = new JLabel("Risk Per Trade (%):");
        riskPerTradeLabel.setToolTipText("<html>Percentage of account to risk per trade.<br>• 0.5-1% = Conservative<br>• 1-2% = Moderate (recommended)<br>• 2-5% = Aggressive</html>");
        settingsPanel.add(riskPerTradeLabel, gbc);
        gbc.gridx = 1;
        JSpinner riskPerTradeSpinner = new JSpinner(new SpinnerNumberModel(riskPerTradePercent.doubleValue(), 0.25, 5.0, 0.25));
        riskPerTradeSpinner.setToolTipText("Percent of account to risk per trade (default: 1.0%)");
        riskPerTradeSpinner.addChangeListener(e -> riskPerTradePercent = (Double) riskPerTradeSpinner.getValue());
        settingsPanel.add(riskPerTradeSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 42;
        JLabel tickValueLabel = new JLabel("Tick Value ($):");
        tickValueLabel.setToolTipText("<html>Dollar value per tick movement.<br>Example: ES futures = $12.50 per tick<br>Used for P&L and risk calculations.</html>");
        settingsPanel.add(tickValueLabel, gbc);
        gbc.gridx = 1;
        JSpinner tickValueSpinner = new JSpinner(new SpinnerNumberModel(tickValue.doubleValue(), 0.01, 100.0, 0.01));
        tickValueSpinner.setToolTipText("Dollar value of 1 tick movement (default: $12.50)");
        tickValueSpinner.addChangeListener(e -> tickValue = (Double) tickValueSpinner.getValue());
        settingsPanel.add(tickValueSpinner, gbc);

        // Fixed position size (declared first so checkbox can reference it)
        gbc.gridx = 0; gbc.gridy = 43;
        JLabel fixedPosLabel = new JLabel("Fixed Position Size:");
        fixedPosLabel.setToolTipText("<html>Number of contracts when NOT auto-calculating.<br>Used when Auto-Calculate Size is disabled.</html>");
        settingsPanel.add(fixedPosLabel, gbc);
        gbc.gridx = 1;
        JSpinner fixedPosSizeSpinner = new JSpinner(new SpinnerNumberModel(fixedPositionSize.intValue(), 1, 50, 1));
        fixedPosSizeSpinner.setToolTipText("<html>Contracts per trade (used when Auto-Calculate is off)<br>Example: 1 contract per trade</html>");
        fixedPosSizeSpinner.addChangeListener(e -> fixedPositionSize = (Integer) fixedPosSizeSpinner.getValue());
        settingsPanel.add(fixedPosSizeSpinner, gbc);

        // Auto-calculate checkbox (after spinner so it can enable/disable it)
        gbc.gridx = 0; gbc.gridy = 44;
        JLabel autoCalcLabel = new JLabel("Auto-Calculate Size:");
        autoCalcLabel.setToolTipText("<html>When enabled, position size is calculated from:<br>Account Size × Risk % ÷ (Stop Loss × Tick Value)</html>");
        settingsPanel.add(autoCalcLabel, gbc);
        gbc.gridx = 1;
        JCheckBox autoCalcCheckBox = new JCheckBox();
        autoCalcCheckBox.setSelected(autoCalculatePositionSize);
        autoCalcCheckBox.setToolTipText("<html>ON: Size = Account × Risk% ÷ (SL ticks × Tick Value)<br>OFF: Use Fixed Position Size spinner above</html>");
        autoCalcCheckBox.addActionListener(e -> {
            autoCalculatePositionSize = autoCalcCheckBox.isSelected();
            fixedPosSizeSpinner.setEnabled(!autoCalculatePositionSize);
        });
        settingsPanel.add(autoCalcCheckBox, gbc);

        // Set initial state of spinner based on auto-calc setting
        fixedPosSizeSpinner.setEnabled(!autoCalculatePositionSize);

        // Stop Loss Ticks
        gbc.gridx = 0; gbc.gridy = 45;
        JLabel slTicksLabel = new JLabel("Stop Loss (ticks):");
        slTicksLabel.setToolTipText("<html>Fixed stop loss distance from entry price.<br>Used when Smart SL/TP is disabled.</html>");
        settingsPanel.add(slTicksLabel, gbc);
        gbc.gridx = 1;
        JSpinner slTicksSpinner = new JSpinner(new SpinnerNumberModel(stopLossTicks.intValue(), 1, 200, 1));
        slTicksSpinner.setToolTipText("<html>Distance from entry to stop loss in ticks<br>Used with fixed SL/TP mode (Smart SL/TP off)</html>");
        slTicksSpinner.addChangeListener(e -> stopLossTicks = (Integer) slTicksSpinner.getValue());
        settingsPanel.add(slTicksSpinner, gbc);

        // Take Profit Ticks
        gbc.gridx = 0; gbc.gridy = 46;
        JLabel tpTicksLabel = new JLabel("Take Profit (ticks):");
        tpTicksLabel.setToolTipText("<html>Fixed take profit distance from entry price.<br>Used when Smart SL/TP is disabled.</html>");
        settingsPanel.add(tpTicksLabel, gbc);
        gbc.gridx = 1;
        JSpinner tpTicksSpinner = new JSpinner(new SpinnerNumberModel(takeProfitTicks.intValue(), 1, 500, 1));
        tpTicksSpinner.setToolTipText("<html>Distance from entry to take profit in ticks<br>Used with fixed SL/TP mode (Smart SL/TP off)</html>");
        tpTicksSpinner.addChangeListener(e -> takeProfitTicks = (Integer) tpTicksSpinner.getValue());
        settingsPanel.add(tpTicksSpinner, gbc);

        // R:R display label (calculated)
        gbc.gridx = 0; gbc.gridy = 47;
        JLabel rrLabel = new JLabel("R:R Ratio:");
        rrLabel.setToolTipText("<html>Calculated from Stop Loss and Take Profit ticks.<br>Formula: TP ticks ÷ SL ticks<br>Example: TP=30, SL=10 → R:R = 1:3.0</html>");
        settingsPanel.add(rrLabel, gbc);
        gbc.gridx = 1;
        JLabel rrValueLabel = new JLabel(String.format("1:%.1f", (double) takeProfitTicks / stopLossTicks));
        rrValueLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        rrValueLabel.setToolTipText("<html>This is the actual R:R from your fixed SL/TP settings.<br>Changes when you adjust Stop Loss or Take Profit spinners above.</html>");
        settingsPanel.add(rrValueLabel, gbc);

        // Update R:R label when SL/TP changes
        slTicksSpinner.addChangeListener(e -> {
            int sl = (Integer) slTicksSpinner.getValue();
            int tp = (Integer) tpTicksSpinner.getValue();
            rrValueLabel.setText(String.format("1:%.1f", (double) tp / sl));
        });
        tpTicksSpinner.addChangeListener(e -> {
            int sl = (Integer) slTicksSpinner.getValue();
            int tp = (Integer) tpTicksSpinner.getValue();
            rrValueLabel.setText(String.format("1:%.1f", (double) tp / sl));
        });

        // ========== SMART SL/TP SECTION ==========
        gbc.gridx = 0; gbc.gridy = 48; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Smart SL/TP (ATR + DOM)", gbc);

        // Use Smart SL/TP checkbox
        gbc.gridx = 0; gbc.gridy = 49; gbc.gridwidth = 1;
        JLabel smartSlTpLabel = new JLabel("Use Smart SL/TP:");
        smartSlTpLabel.setToolTipText("<html>When enabled, uses dynamic SL/TP based on:<br>• ATR (Average True Range) for volatility<br>• DOM support/resistance levels<br>• Trade memory from past performance</html>");
        settingsPanel.add(smartSlTpLabel, gbc);
        gbc.gridx = 1;
        JCheckBox smartSlTpCheckBox = new JCheckBox();
        smartSlTpCheckBox.setSelected(useSmartSlTp);
        smartSlTpCheckBox.setToolTipText("<html>ON: SL/TP calculated from market conditions (recommended)<br>OFF: Use fixed SL/TP ticks from settings above</html>");
        smartSlTpCheckBox.addActionListener(e -> useSmartSlTp = smartSlTpCheckBox.isSelected());
        settingsPanel.add(smartSlTpCheckBox, gbc);

        // ATR SL Multiplier
        gbc.gridx = 0; gbc.gridy = 50;
        JLabel atrSlLabel = new JLabel("ATR SL Multiplier:");
        atrSlLabel.setToolTipText("<html>Stop Loss distance based on ATR volatility.<br>Higher = wider stop (more room)<br>Lower = tighter stop (less risk but more stops)</html>");
        settingsPanel.add(atrSlLabel, gbc);
        gbc.gridx = 1;
        JSpinner atrSlSpinner = new JSpinner(new SpinnerNumberModel(atrSlMultiplier.doubleValue(), 0.5, 5.0, 0.1));
        atrSlSpinner.setToolTipText("<html>SL = ATR × this value (in ticks)<br>1.5 = SL is 1.5× the average range<br>Higher for volatile markets</html>");
        atrSlSpinner.setEditor(new JSpinner.NumberEditor(atrSlSpinner, "0.0"));
        atrSlSpinner.addChangeListener(e -> atrSlMultiplier = (Double) atrSlSpinner.getValue());
        settingsPanel.add(atrSlSpinner, gbc);

        // ATR TP Multiplier
        gbc.gridx = 0; gbc.gridy = 51;
        JLabel atrTpLabel = new JLabel("ATR TP Multiplier:");
        atrTpLabel.setToolTipText("<html>Take Profit distance based on ATR volatility.<br>Should be higher than SL multiplier for positive R:R</html>");
        settingsPanel.add(atrTpLabel, gbc);
        gbc.gridx = 1;
        JSpinner atrTpSpinner = new JSpinner(new SpinnerNumberModel(atrTpMultiplier.doubleValue(), 1.0, 10.0, 0.5));
        atrTpSpinner.setToolTipText("<html>TP = ATR × this value (in ticks)<br>3.0 = TP is 3× the average range<br>With SL=1.5, gives R:R of 1:2.0</html>");
        atrTpSpinner.setEditor(new JSpinner.NumberEditor(atrTpSpinner, "0.0"));
        atrTpSpinner.addChangeListener(e -> atrTpMultiplier = (Double) atrTpSpinner.getValue());
        settingsPanel.add(atrTpSpinner, gbc);

        // Min R:R Ratio - displayed as "1:[spinner]"
        gbc.gridx = 0; gbc.gridy = 52;
        JLabel minRRPrefixLabel = new JLabel("Min R:R 1:");
        minRRPrefixLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        minRRPrefixLabel.setToolTipText("<html><b>Minimum Risk:Reward ratio required</b><br><br>Signals with R:R below this threshold will be flagged as poor quality.<br><br>• 1:1.5 = Risk $1 to make $1.50 (break-even long term)<br>• 1:2.0 = Risk $1 to make $2.00 (recommended)<br>• 1:3.0 = Risk $1 to make $3.00 (conservative)</html>");
        settingsPanel.add(minRRPrefixLabel, gbc);
        gbc.gridx = 1;
        double savedMinRR = settings.minRRRatio != null ? settings.minRRRatio : minRRRatio;
        JSpinner minRRSpinner = new JSpinner(new SpinnerNumberModel(savedMinRR, 1.0, 5.0, 0.1));
        minRRSpinner.setEditor(new JSpinner.NumberEditor(minRRSpinner, "0.0"));
        minRRSpinner.setToolTipText("Minimum R:R ratio required (e.g., 1.5 means minimum 1:1.5 risk-reward)");
        minRRSpinner.addChangeListener(e -> {
            minRRRatio = (Double) minRRSpinner.getValue();
            settings.minRRRatio = minRRRatio;
            saveSettings();
            log("📏 Min R:R Ratio: 1:" + minRRRatio);
        });
        settingsPanel.add(minRRSpinner, gbc);
        // Sync local var with saved setting
        if (settings.minRRRatio != null) {
            minRRRatio = settings.minRRRatio;
        }

        // Max SL Ticks
        gbc.gridx = 0; gbc.gridy = 53;
        JLabel maxSlLabel = new JLabel("Max SL (ticks):");
        maxSlLabel.setToolTipText("<html>Maximum stop loss allowed when using Smart SL/TP.<br>Prevents runaway risk from ATR calculations.</html>");
        settingsPanel.add(maxSlLabel, gbc);
        gbc.gridx = 1;
        JSpinner maxSlSpinner = new JSpinner(new SpinnerNumberModel(maxSlTicks.intValue(), 10, 200, 5));
        maxSlSpinner.setToolTipText("<html>Cap for Smart SL - won't exceed this<br>Prevents excessive risk in volatile markets</html>");
        maxSlSpinner.addChangeListener(e -> maxSlTicks = (Integer) maxSlSpinner.getValue());
        settingsPanel.add(maxSlSpinner, gbc);

        // Max TP Ticks
        gbc.gridx = 0; gbc.gridy = 54;
        JLabel maxTpLabel = new JLabel("Max TP (ticks):");
        maxTpLabel.setToolTipText("<html>Maximum take profit allowed when using Smart SL/TP.<br>Keeps targets realistic.</html>");
        settingsPanel.add(maxTpLabel, gbc);
        gbc.gridx = 1;
        JSpinner maxTpSpinner = new JSpinner(new SpinnerNumberModel(maxTpTicks.intValue(), 20, 500, 10));
        maxTpSpinner.setToolTipText("<html>Cap for Smart TP - won't exceed this<br>Keeps profit targets achievable</html>");
        maxTpSpinner.addChangeListener(e -> maxTpTicks = (Integer) maxTpSpinner.getValue());
        settingsPanel.add(maxTpSpinner, gbc);

        // Min DOM Volume for SL/TP
        gbc.gridx = 0; gbc.gridy = 55;
        JLabel minDomVolLabel = new JLabel("Min DOM Volume:");
        minDomVolLabel.setToolTipText("<html>Minimum volume at DOM level to use for SL/TP.<br>Filters out weak support/resistance levels.</html>");
        settingsPanel.add(minDomVolLabel, gbc);
        gbc.gridx = 1;
        JSpinner minDomVolSpinner = new JSpinner(new SpinnerNumberModel(minDomVolumeForSlTp.intValue(), 10, 1000, 10));
        minDomVolSpinner.setToolTipText("<html>Only use DOM levels with this minimum volume<br>Higher = fewer but stronger levels</html>");
        minDomVolSpinner.addChangeListener(e -> minDomVolumeForSlTp = (Integer) minDomVolSpinner.getValue());
        settingsPanel.add(minDomVolSpinner, gbc);

        // DOM Level Priority
        gbc.gridx = 0; gbc.gridy = 56;
        JLabel domPriorityLabel = new JLabel("DOM Priority:");
        domPriorityLabel.setToolTipText("<html>When enabled, DOM levels take priority over ATR for SL/TP.<br>Uses actual support/resistance instead of volatility.</html>");
        settingsPanel.add(domPriorityLabel, gbc);
        gbc.gridx = 1;
        JCheckBox domPriorityCheckBox = new JCheckBox();
        domPriorityCheckBox.setSelected(prioritizeDomOverAtr);
        domPriorityCheckBox.setToolTipText("<html>ON: Use DOM support/resistance first<br>OFF: Use ATR-based calculation first</html>");
        domPriorityCheckBox.addActionListener(e -> prioritizeDomOverAtr = domPriorityCheckBox.isSelected());
        settingsPanel.add(domPriorityCheckBox, gbc);

        // Session-Aware ATR Scaling
        gbc.gridx = 0; gbc.gridy = 57;
        JLabel sessionAtrLabel = new JLabel("Session-Aware ATR:");
        sessionAtrLabel.setToolTipText("<html>Adjust ATR multipliers based on trading session.<br>Opening = wider, Lunch = tighter, Close = wider</html>");
        settingsPanel.add(sessionAtrLabel, gbc);
        gbc.gridx = 1;
        JCheckBox sessionAtrCheckBox = new JCheckBox();
        sessionAtrCheckBox.setSelected(sessionAwareAtrScaling);
        sessionAtrCheckBox.setToolTipText("<html>ON: ATR scales with session volatility<br>Opening/Close: wider stops, Lunch: tighter stops</html>");
        sessionAtrCheckBox.addActionListener(e -> sessionAwareAtrScaling = sessionAtrCheckBox.isSelected());
        settingsPanel.add(sessionAtrCheckBox, gbc);

        // Use Trade Memory for SL/TP
        gbc.gridx = 0; gbc.gridy = 58;
        JLabel tradeMemoryLabel = new JLabel("Use Trade Memory:");
        tradeMemoryLabel.setToolTipText("<html>Use past trade MFE/MAE to optimize SL/TP.<br>MFE = Max Favorable Excursion (best price)<br>MAE = Max Adverse Excursion (worst price)</html>");
        settingsPanel.add(tradeMemoryLabel, gbc);
        gbc.gridx = 1;
        JCheckBox tradeMemoryCheckBox = new JCheckBox();
        tradeMemoryCheckBox.setSelected(useTradeMemoryForSlTp);
        tradeMemoryCheckBox.setToolTipText("<html>ON: Learn from past trades to improve SL/TP<br>Uses MAE for SL, MFE for TP from history</html>");
        tradeMemoryCheckBox.addActionListener(e -> useTradeMemoryForSlTp = tradeMemoryCheckBox.isSelected());
        settingsPanel.add(tradeMemoryCheckBox, gbc);

        // Memory Lookback
        gbc.gridx = 0; gbc.gridy = 59;
        JLabel memoryLookbackLabel = new JLabel("Memory Lookback:");
        memoryLookbackLabel.setToolTipText("<html>Number of recent trades to analyze for MFE/MAE.<br>Higher = more data but slower adaptation.</html>");
        settingsPanel.add(memoryLookbackLabel, gbc);
        gbc.gridx = 1;
        JSpinner memoryLookbackSpinner = new JSpinner(new SpinnerNumberModel(memoryLookbackTrades.intValue(), 5, 100, 5));
        memoryLookbackSpinner.setToolTipText("<html>How many past trades to analyze (default: 20)<br>Higher = more stable, Lower = more adaptive</html>");
        memoryLookbackSpinner.addChangeListener(e -> memoryLookbackTrades = (Integer) memoryLookbackSpinner.getValue());
        settingsPanel.add(memoryLookbackSpinner, gbc);

        // Notifications section
        gbc.gridx = 0; gbc.gridy = 60; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Notifications", gbc);

        gbc.gridx = 0; gbc.gridy = 61; gbc.gridwidth = 1;
        JLabel eventNotifLabel = new JLabel("Event Notifications:");
        eventNotifLabel.setToolTipText("Show alerts for trades and signal events");
        settingsPanel.add(eventNotifLabel, gbc);
        gbc.gridx = 1;
        JCheckBox eventNotifCheckBox = new JCheckBox();
        eventNotifCheckBox.setSelected(enableEventNotifications);
        eventNotifCheckBox.setToolTipText("<html>ON: Get notified of trades, signals, and important events<br>OFF: Silent operation</html>");
        eventNotifCheckBox.addActionListener(e -> enableEventNotifications = eventNotifCheckBox.isSelected());
        settingsPanel.add(eventNotifCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 62;
        JLabel aiNotifLabel = new JLabel("AI Notifications:");
        aiNotifLabel.setToolTipText("Allow AI to send alerts and recommendations");
        settingsPanel.add(aiNotifLabel, gbc);
        gbc.gridx = 1;
        JCheckBox aiNotifCheckBox = new JCheckBox();
        aiNotifCheckBox.setSelected(enableAINotifications);
        aiNotifCheckBox.setToolTipText("<html>ON: AI can push alerts and recommendations<br>OFF: AI operates silently</html>");
        aiNotifCheckBox.addActionListener(e -> enableAINotifications = aiNotifCheckBox.isSelected());
        settingsPanel.add(aiNotifCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 63;
        JLabel periodicNotifLabel = new JLabel("Periodic Updates:");
        periodicNotifLabel.setToolTipText("Receive regular status updates from AI");
        settingsPanel.add(periodicNotifLabel, gbc);
        gbc.gridx = 1;
        JCheckBox periodicNotifCheckBox = new JCheckBox();
        periodicNotifCheckBox.setSelected(enablePeriodicUpdates);
        periodicNotifCheckBox.setToolTipText("<html>ON: AI sends periodic market analysis updates<br>OFF: No scheduled updates</html>");
        periodicNotifCheckBox.addActionListener(e -> {
            enablePeriodicUpdates = periodicNotifCheckBox.isSelected();
            if (enablePeriodicUpdates) {
                startPeriodicUpdateTimer();
            } else {
                stopPeriodicUpdateTimer();
            }
        });
        settingsPanel.add(periodicNotifCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 64;
        JLabel updateIntervalLabel = new JLabel("Update Interval (min):");
        updateIntervalLabel.setToolTipText("<html>How often to receive AI status updates.<br>Only applies when Periodic Updates is enabled.</html>");
        settingsPanel.add(updateIntervalLabel, gbc);
        gbc.gridx = 1;
        JSpinner periodicIntervalSpinner = new JSpinner(new SpinnerNumberModel(periodicUpdateIntervalMinutes.intValue(), 5, 60, 5));
        periodicIntervalSpinner.setToolTipText("<html>Minutes between AI updates (default: 15)<br>Lower = more frequent updates</html>");
        periodicIntervalSpinner.addChangeListener(e -> {
            periodicUpdateIntervalMinutes = (Integer) periodicIntervalSpinner.getValue();
            if (enablePeriodicUpdates) {
                stopPeriodicUpdateTimer();
                startPeriodicUpdateTimer();
            }
        });
        settingsPanel.add(periodicIntervalSpinner, gbc);

        // Apply button
        gbc.gridx = 0; gbc.gridy = 65; gbc.gridwidth = 2;
        JButton applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        settingsPanel.add(applyButton, gbc);

        // ========== TEST SL/TP LINES BUTTON ==========
        gbc.gridx = 0; gbc.gridy = 66; gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton testLinesButton = new JButton("TEST SL/TP LINES");
        testLinesButton.setToolTipText("Click to test SL/TP line drawing independently of AI/signals");
        testLinesButton.setOpaque(true);
        testLinesButton.setEnabled(true);
        testLinesButton.addActionListener(e -> {
            System.out.println("=== TEST BUTTON CLICKED ===");
            testSlTpLines();
        });
        settingsPanel.add(testLinesButton, gbc);

        // Clear lines button (on same row as test button, to the right)
        gbc.gridx = 2; gbc.gridy = 66; gbc.gridwidth = 1;
        JButton clearLinesButton = new JButton("Clear Lines");
        clearLinesButton.setToolTipText("Clear the test SL/TP lines");
        clearLinesButton.setOpaque(true);
        clearLinesButton.setEnabled(true);
        clearLinesButton.addActionListener(e -> clearSlTpLines());
        settingsPanel.add(clearLinesButton, gbc);
        gbc.anchor = GridBagConstraints.WEST;  // Reset anchor

        // Version label (bottom right)
        gbc.gridx = 1; gbc.gridy = 56; gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.weightx = 1.0;
        JLabel versionLabel = new JLabel("Qid v2.1 - AI Trading with Memory");
        versionLabel.setFont(new Font("Arial", Font.ITALIC, 10));
        versionLabel.setForeground(Color.GRAY);
        settingsPanel.add(versionLabel, gbc);
    }

    private void addSeparator(JPanel panel, String text, GridBagConstraints gbc) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.BOLD, 12));
        panel.add(label, gbc);
    }

    private void createStatsPanel() {
        statsPanel = new StrategyPanel("Performance Dashboard");
        statsPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // Title
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        JLabel title = new JLabel("📊 Performance Dashboard");
        title.setFont(new Font("Arial", Font.BOLD, 14));
        statsPanel.add(title, gbc);
        row++;

        // All-Time Performance
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(statsPanel, "All-Time Performance", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Trades:"), gbc);
        gbc.gridx = 1;
        totalTradesLabel = new JLabel("0");
        statsPanel.add(totalTradesLabel, gbc);
        gbc.gridx = 2;
        statsPanel.add(new JLabel("Win Rate:"), gbc);
        gbc.gridx = 3;
        winRateLabel = new JLabel("0.0%");
        statsPanel.add(winRateLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Total P&L:"), gbc);
        gbc.gridx = 1;
        totalPnLLabel = new JLabel("$0.00");
        totalPnLLabel.setForeground(new Color(0, 150, 0));
        statsPanel.add(totalPnLLabel, gbc);
        gbc.gridx = 2;
        statsPanel.add(new JLabel("Best:"), gbc);
        gbc.gridx = 3;
        bestTradeLabel = new JLabel("$0");
        statsPanel.add(bestTradeLabel, gbc);
        row++;

        // Today's Performance
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(statsPanel, "Today's Performance", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Today Trades:"), gbc);
        gbc.gridx = 1;
        todayTradesLabel = new JLabel("0");
        statsPanel.add(todayTradesLabel, gbc);
        gbc.gridx = 2;
        statsPanel.add(new JLabel("Win Rate:"), gbc);
        gbc.gridx = 3;
        todayWinRateLabel = new JLabel("0.0%");
        statsPanel.add(todayWinRateLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Today P&L:"), gbc);
        gbc.gridx = 1;
        todayPnLLabel = new JLabel("$0.00");
        statsPanel.add(todayPnLLabel, gbc);
        gbc.gridx = 2;
        statsPanel.add(new JLabel("Drawdown:"), gbc);
        gbc.gridx = 3;
        todayDrawdownLabel = new JLabel("$0.00");
        statsPanel.add(todayDrawdownLabel, gbc);
        row++;

        // Current Activity
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(statsPanel, "Current Activity", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Tracked Signals:"), gbc);
        gbc.gridx = 1;
        activeSignalsLabel = new JLabel("0 / 0");
        statsPanel.add(activeSignalsLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Last Score:"), gbc);
        gbc.gridx = 1;
        lastSignalScoreLabel = new JLabel("N/A");
        statsPanel.add(lastSignalScoreLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Last Signal:"), gbc);
        gbc.gridx = 1;
        lastSignalTimeLabel = new JLabel("N/A");
        statsPanel.add(lastSignalTimeLabel, gbc);
        row++;

        // Adaptive Thresholds
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(statsPanel, "Adaptive Thresholds", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Avg Orders:"), gbc);
        gbc.gridx = 1;
        avgOrderCountLabel = new JLabel("0.0");
        statsPanel.add(avgOrderCountLabel, gbc);
        gbc.gridx = 2;
        statsPanel.add(new JLabel("Avg Size:"), gbc);
        gbc.gridx = 3;
        avgOrderSizeLabel = new JLabel("0.0");
        statsPanel.add(avgOrderSizeLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        statsPanel.add(new JLabel("Current Threshold:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3;
        currentThresholdLabel = new JLabel("0 orders, 0 size");
        statsPanel.add(currentThresholdLabel, gbc);
        row++;

        // Buttons
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.gridheight = 1;
        JButton reportButton = new JButton("📊 Report");
        reportButton.addActionListener(e -> generatePerformanceReport());
        statsPanel.add(reportButton, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        exportButton = new JButton("📥 Export Data");
        exportButton.addActionListener(e -> exportData());
        statsPanel.add(exportButton, gbc);
    }

    private void openAIChatWindow() {
        // If window already exists, bring it to front
        if (chatWindow != null && chatWindow.isVisible()) {
            chatWindow.toFront();
            chatWindow.setState(Frame.NORMAL);
            return;
        }

        // Create new window
        createAIChatWindow();
        chatWindow.setVisible(true);
    }

    private void createAIChatWindow() {
        chatWindow = new JFrame("💬 AI Chat - Trading Assistant");
        chatWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);  // Don't exit when closed
        chatWindow.setSize(700, 600);
        chatWindow.setMinimumSize(new Dimension(500, 400));

        // Try to position on the right side of screen
        try {
            java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            int screenWidth = gd.getDisplayMode().getWidth();
            int screenHeight = gd.getDisplayMode().getHeight();
            chatWindow.setLocation(screenWidth - 750, 50);
        } catch (Exception e) {
            // Default to center if positioning fails
            chatWindow.setLocationRelativeTo(null);
        }

        chatWindow.setLayout(new BorderLayout(5, 5));

        // Chat history area (top) - using JEditorPane with HTML/CSS support
        chatHistoryArea = new JEditorPane();
        chatHistoryArea.setEditable(false);
        chatHistoryArea.setContentType("text/html");
        chatHistoryArea.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // Add custom CSS styling - DARK MODE (light text on dark background)
        String css = "<style>" +
            "body { font-family: SansSerif, Arial, sans-serif; font-size: 12px; color: #E0E0E0; padding: 8px; background: #1E1E1E; }" +
            ".user-msg { color: #64B5F6; font-weight: bold; margin: 10px 0; }" +
            ".ai-msg { color: #E0E0E0; margin: 10px 0; }" +
            ".system-msg { color: #9E9E9E; font-style: italic; margin: 10px 0; }" +
            ".timestamp { color: #757575; font-size: 11px; }" +
            "code { background: #2D2D2D; color: #FFFFFF; padding: 2px 8px; border-radius: 4px; font-family: Monaco, monospace; }" +
            "pre { background: #2D2D2D; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 10px 0; border: 1px solid #3D3D3D; }" +
            "strong { color: #FFFFFF; }" +
            "em { color: #B0B0B0; }" +
            "h1, h2, h3 { margin: 12px 0 8px 0; color: #FFFFFF; font-weight: bold; }" +
            "ul, ol { margin: 8px 0; padding-left: 20px; }" +
            "li { margin: 4px 0; }" +
            "a { color: #64B5F6; text-decoration: underline; }" +
            "hr { border: none; border-top: 1px solid #3D3D3D; margin: 12px 0; }" +
            "</style>";

        chatHtmlContent = new StringBuilder();
        chatHtmlContent.append("<html><head>").append(css).append("</head><body>");

        JScrollPane historyScrollPane = new JScrollPane(chatHistoryArea);
        historyScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Input area (bottom)
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));

        chatInputField = new JTextField();
        chatInputField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chatInputField.setToolTipText("Type your message and press Enter to send");

        chatSendButton = new JButton("Send 💬");
        chatSendButton.setToolTipText("Send message to AI");

        chatClearButton = new JButton("Clear 🗑️");
        chatClearButton.setToolTipText("Clear chat history");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.add(chatClearButton);
        buttonPanel.add(chatSendButton);

        inputPanel.add(new JLabel("Message:"), BorderLayout.WEST);
        inputPanel.add(chatInputField, BorderLayout.CENTER);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

        // Approval panel for SEMI_AUTO mode (hidden by default)
        approvalPanel = new JPanel(new BorderLayout(5, 5));
        approvalPanel.setBackground(new Color(255, 250, 230));  // Light yellow background
        approvalPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, Color.ORANGE),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        approvalLabel = new JLabel("⏳ Awaiting approval...");
        approvalLabel.setFont(new Font("SansSerif", Font.BOLD, 13));

        approveButton = new JButton("✅ Approve");
        approveButton.setBackground(new Color(144, 238, 144));  // Light green
        approveButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        approveButton.setToolTipText("Approve this trade");

        rejectButton = new JButton("❌ Reject");
        rejectButton.setBackground(new Color(255, 182, 193));  // Light red
        rejectButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        rejectButton.setToolTipText("Reject this trade");

        JPanel approvalButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 5));
        approvalButtons.add(approveButton);
        approvalButtons.add(rejectButton);

        approvalPanel.add(approvalLabel, BorderLayout.CENTER);
        approvalPanel.add(approvalButtons, BorderLayout.EAST);
        approvalPanel.setVisible(false);  // Hidden by default

        // South panel to hold approval and input panels
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(approvalPanel, BorderLayout.NORTH);
        southPanel.add(inputPanel, BorderLayout.SOUTH);

        // Add components to window
        chatWindow.add(historyScrollPane, BorderLayout.CENTER);
        chatWindow.add(southPanel, BorderLayout.SOUTH);

        // Add initial welcome message
        appendChatMessage("System", "Welcome to AI Chat! Ask me anything about trading, order flow, or market analysis.\n\nThis window stays open even when you close the Settings panel.\n\nType your message below and click Send or press Enter.");

        // Event listeners
        chatInputField.addActionListener(e -> sendChatMessage());
        chatSendButton.addActionListener(e -> sendChatMessage());
        chatClearButton.addActionListener(e -> clearChatHistory());

        // Approve/Reject button listeners
        approveButton.addActionListener(e -> handleApproveTrade());
        rejectButton.addActionListener(e -> handleRejectTrade());

        // Window listener - reset window reference when closed
        chatWindow.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                log("💬 AI Chat window closed");
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                log("💬 AI Chat window closing (can be reopened from Settings)");
            }
        });
    }

    private String loadSkillContext() {
        try {
            // Try current directory first
            java.nio.file.Path skillPath = java.nio.file.Paths.get("SKILL.md");

            // If not found, try docs directory
            if (!java.nio.file.Files.exists(skillPath)) {
                skillPath = java.nio.file.Paths.get("docs/SKILL.md");
            }

            // If still not found, try parent directory
            if (!java.nio.file.Files.exists(skillPath)) {
                skillPath = java.nio.file.Paths.get("../SKILL.md");
            }

            if (java.nio.file.Files.exists(skillPath)) {
                String content = new String(java.nio.file.Files.readAllBytes(skillPath));
                log("📚 Loaded SKILL.md: " + skillPath + " (" + content.length() + " chars)");

                // Return first 3000 chars to avoid token limits
                if (content.length() > 3000) {
                    return content.substring(0, 3000) + "\n\n...(truncated, full file: " + content.length() + " chars)";
                }
                return content;
            } else {
                log("⚠️ SKILL.md not found (tried: ./SKILL.md, docs/SKILL.md, ../SKILL.md)");
            }
        } catch (Exception e) {
            log("⚠️ Error loading SKILL.md: " + e.getMessage());
        }
        return null;
    }

    private void sendChatMessage() {
        String userMessage = chatInputField.getText().trim();
        if (userMessage.isEmpty()) {
            return;
        }

        // Check if AI service is available
        if (aiThresholdService == null) {
            appendChatMessage("System", "⚠️ AI Service not available. Please check your AI Auth Token in Settings.");
            return;
        }

        // Add user message to chat
        appendChatMessage("You", userMessage);
        chatInputField.setText("");

        // Log user message to unified session transcript
        if (transcriptWriter != null) {
            transcriptWriter.logMessage("user", userMessage);
        }

        // Show typing indicator
        appendChatMessage("AI", "Thinking...");

        // Build enhanced prompt with memory context (async)
        CompletableFuture.supplyAsync(() -> {
            StringBuilder enhancedPrompt = new StringBuilder();

            // 1. Add SKILL.md context if available
            String skillContext = loadSkillContext();
            if (skillContext != null && !skillContext.isEmpty()) {
                enhancedPrompt.append("=== SYSTEM CONTEXT (Qid Trading System) ===\n");
                enhancedPrompt.append(skillContext);
                enhancedPrompt.append("\n\n");
                log("💬 Chat: Added SKILL.md context (" + skillContext.length() + " chars)");
            } else {
                log("⚠️ Chat: SKILL.md context not available");
            }

            // 1b. Add LIVE SESSION CONTEXT (current state)
            enhancedPrompt.append("=== CURRENT TRADING SESSION ===\n");
            if (sessionContext != null) {
                enhancedPrompt.append(sessionContext.toAIString());
            } else {
                enhancedPrompt.append("(Session not initialized)\n");
            }

            // Add current thresholds
            enhancedPrompt.append("\n=== CURRENT THRESHOLDS ===\n");
            enhancedPrompt.append(String.format("minConfluenceScore: %d\n", minConfluenceScore));
            enhancedPrompt.append(String.format("confluenceThreshold: %d\n", confluenceThreshold));
            enhancedPrompt.append(String.format("icebergMinOrders: %d\n", icebergMinOrders));
            enhancedPrompt.append(String.format("spoofMinSize: %d\n", spoofMinSize));
            enhancedPrompt.append(String.format("absorptionMinSize: %d\n", absorptionMinSize));
            enhancedPrompt.append(String.format("useAIAdaptiveThresholds: %s\n", useAIAdaptiveThresholds));

            // Add current market state
            enhancedPrompt.append("\n=== CURRENT MARKET STATE ===\n");
            enhancedPrompt.append(String.format("Last Price: %.2f\n", lastKnownPrice));
            enhancedPrompt.append(String.format("CVD: %d (%s)\n", cvdCalculator.getCVD(),
                cvdCalculator.getCVD() > 0 ? "BULLISH" : cvdCalculator.getCVD() < 0 ? "BEARISH" : "NEUTRAL"));
            enhancedPrompt.append(String.format("VWAP: %.2f\n", vwapCalculator.isInitialized() ? vwapCalculator.getVWAP() : 0));
            enhancedPrompt.append(String.format("EMA9: %.2f | EMA21: %.2f | EMA50: %.2f\n",
                ema9.isInitialized() ? ema9.getEMA() : 0,
                ema21.isInitialized() ? ema21.getEMA() : 0,
                ema50.isInitialized() ? ema50.getEMA() : 0));

            // Add DOM (Order Book) levels
            enhancedPrompt.append("\n=== ORDER BOOK (DOM) ===\n");
            if (domAnalyzer != null) {
                var support = domAnalyzer.getNearestSupport();
                var resistance = domAnalyzer.getNearestResistance();
                if (support != null) {
                    enhancedPrompt.append(String.format("Support: %d (%d contracts)\n",
                        support.price, support.volume));
                }
                if (resistance != null) {
                    enhancedPrompt.append(String.format("Resistance: %d (%d contracts)\n",
                        resistance.price, resistance.volume));
                }
                enhancedPrompt.append(String.format("Imbalance: %.2f (%s)\n",
                    domAnalyzer.getImbalanceRatio(), domAnalyzer.getImbalanceSentiment()));
            } else {
                enhancedPrompt.append("(DOM data not yet available)\n");
            }

            // Add recent signal decisions
            enhancedPrompt.append("\n=== RECENT SIGNALS ===\n");
            if (!trackedSignals.isEmpty()) {
                int count = 0;
                for (var entry : trackedSignals.entrySet()) {
                    if (count++ >= 5) break;  // Last 5 signals
                    SignalPerformance perf = entry.getValue();
                    enhancedPrompt.append(String.format("- %s @ %d | Score: %d | %s\n",
                        perf.isBid ? "BUY" : "SELL", perf.entryPrice, perf.score,
                        perf.confluenceBreakdown != null ? perf.confluenceBreakdown : ""));
                }
            } else {
                enhancedPrompt.append("(No signals yet this session)\n");
            }
            enhancedPrompt.append("\n");

            // 2. Search memory for relevant context
            if (memoryService != null) {
                try {
                    enhancedPrompt.append("=== RELEVANT TRADING MEMORY ===\n");
                    // Search memory with user's question
                    java.util.List<velox.api.layer1.simplified.demo.memory.MemorySearchResult> memoryResults =
                        ((velox.api.layer1.simplified.demo.storage.TradingMemoryService)memoryService).search(userMessage, 3);

                    if (!memoryResults.isEmpty()) {
                        log("💬 Chat: Found " + memoryResults.size() + " relevant memory chunks");
                        for (velox.api.layer1.simplified.demo.memory.MemorySearchResult result : memoryResults) {
                            enhancedPrompt.append(String.format("- [%.2f] %s (lines %d-%d from %s)\n",
                                result.getScore(),
                                result.getSnippet().substring(0, Math.min(200, result.getSnippet().length())),
                                result.getStartLine(),
                                result.getEndLine(),
                                result.getPath()));
                        }
                    } else {
                        enhancedPrompt.append("(No relevant memory found)\n");
                        log("💬 Chat: No relevant memory found");
                    }
                    enhancedPrompt.append("\n");
                } catch (Exception e) {
                    enhancedPrompt.append("(Memory search unavailable: " + e.getMessage() + ")\n\n");
                    log("⚠️ Chat: Memory search error: " + e.getMessage());
                }
            } else {
                log("⚠️ Chat: Memory service not initialized");
            }

            // 3. Add user's question
            enhancedPrompt.append("=== USER QUESTION ===\n");
            enhancedPrompt.append(userMessage);

            log("💬 Chat: Enhanced prompt length = " + enhancedPrompt.length() + " chars");

            return enhancedPrompt.toString();
        })
        .thenCompose(enhancedPrompt -> aiThresholdService.chatWithTools(enhancedPrompt)
            .orTimeout(60, java.util.concurrent.TimeUnit.SECONDS))
        .thenAccept(response -> {
            log("✅ AI chat response received: " + (response != null ? response.length() + " chars" : "null"));

            // Log AI response to unified session transcript
            if (transcriptWriter != null) {
                transcriptWriter.logMessage("assistant", response);
            }

            // Update UI on EDT
            SwingUtilities.invokeLater(() -> {
                // Check if UI still exists
                if (chatHistoryArea == null || chatHtmlContent == null) {
                    log("⚠️ Chat UI disposed, skipping update");
                    return;
                }

                // Remove last line (Thinking...)
                String currentText = chatHistoryArea.getText();
                int lastNewline = currentText.lastIndexOf("AI: Thinking...");
                if (lastNewline != -1) {
                    String newText = currentText.substring(0, lastNewline);
                    chatHistoryArea.setText(newText);
                }

                // Add AI response
                appendChatMessage("AI", response);
            });
        })
        .exceptionally(ex -> {
            log("❌ AI chat error: " + ex.getMessage());
            ex.printStackTrace();

            SwingUtilities.invokeLater(() -> {
                // Check if UI still exists
                if (chatHistoryArea == null || chatHtmlContent == null) {
                    return;
                }

                // Remove "Thinking..."
                String currentText = chatHistoryArea.getText();
                int lastNewline = currentText.lastIndexOf("AI: Thinking...");
                if (lastNewline != -1) {
                    String newText = currentText.substring(0, lastNewline);
                    chatHistoryArea.setText(newText);
                }

                appendChatMessage("System", "❌ Error: " + ex.getMessage());
            });
            return null;
        });
    }

    private void appendChatMessage(String role, String message) {
        // Guard: Skip if chat UI not initialized yet
        if (chatHtmlContent == null || chatHistoryArea == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            // Double-check inside EDT
            if (chatHtmlContent == null || chatHistoryArea == null) {
                return;
            }

            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());

            // Parse markdown to HTML for AI messages
            String formattedMessage = message;
            if (role.equals("AI")) {
                formattedMessage = parseMarkdownToHtml(message);
            } else {
                // Escape HTML special characters for user messages
                formattedMessage = message
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            }

            // Convert newlines to <br/>
            formattedMessage = formattedMessage.replace("\n", "<br/>");

            // Format message based on role with HTML/CSS
            String htmlMsg;
            if (role.equals("You")) {
                htmlMsg = String.format(
                    "<div class='user-msg'><span class='timestamp'>[%s]</span> <strong>You:</strong> %s</div>",
                    timestamp, formattedMessage);
            } else if (role.equals("AI")) {
                htmlMsg = String.format(
                    "<div class='ai-msg'><span class='timestamp'>[%s]</span> <strong>AI:</strong><br/>%s</div>",
                    timestamp, formattedMessage);
            } else {
                htmlMsg = String.format(
                    "<div class='system-msg'><span class='timestamp'>[%s]</span> %s</div>",
                    timestamp, formattedMessage);
            }

            // Append to HTML content
            chatHtmlContent.append(htmlMsg);

            // Update JEditorPane with complete HTML document
            chatHistoryArea.setText(chatHtmlContent.toString() + "</body></html>");

            // Auto-scroll to bottom
            chatHistoryArea.setCaretPosition(chatHistoryArea.getDocument().getLength());

            // Store message
            chatMessages.add(new String[]{role, message});
        });
    }

    // ========== PUSH NOTIFICATION SYSTEM ==========

    /**
     * Push a notification to the chat (proactive, not in response to user)
     * This allows the system and AI to notify user of events
     *
     * @param type Notification type (EVENT, AI, PERIODIC, TRADE, SIGNAL)
     * @param message The message to display
     * @param priority Priority level (low, normal, high, urgent)
     */
    public void pushNotification(String type, String message, String priority) {
        // Check if this notification type is enabled
        switch (type) {
            case "EVENT":
            case "TRADE":
            case "SIGNAL":
            case "THRESHOLD":
            case "RISK":
                if (!enableEventNotifications) return;
                break;
            case "AI":
                if (!enableAINotifications) return;
                break;
            case "PERIODIC":
                if (!enablePeriodicUpdates) return;
                break;
        }

        // Format notification with icon based on type
        String icon = switch (type) {
            case "TRADE" -> "📈";
            case "SIGNAL" -> "📊";
            case "THRESHOLD" -> "🎚️";
            case "RISK" -> "⚠️";
            case "AI" -> "🤖";
            case "PERIODIC" -> "🔔";
            default -> "📢";
        };

        // Priority formatting
        String priorityPrefix = switch (priority) {
            case "urgent" -> "🔴 URGENT: ";
            case "high" -> "🟠 ";
            default -> "";
        };

        // Log to transcript
        if (transcriptWriter != null) {
            transcriptWriter.logMessage("notification", type + ": " + message);
        }

        // Push to chat
        String formattedMessage = icon + " " + priorityPrefix + message;
        SwingUtilities.invokeLater(() -> {
            appendChatMessage("🔔 Notification", formattedMessage);
        });
    }

    /**
     * Push notification with normal priority
     */
    public void pushNotification(String type, String message) {
        pushNotification(type, message, "normal");
    }

    /**
     * Push event notification (trade executed, signal detected, etc.)
     */
    public void notifyEvent(String message) {
        pushNotification("EVENT", message);
    }

    /**
     * Push AI-generated notification
     */
    public void notifyAI(String message, String priority) {
        pushNotification("AI", message, priority);
    }

    // ========== TRADE APPROVAL (SEMI_AUTO MODE) ==========

    /**
     * Request user approval for a trade (SEMI_AUTO mode)
     * Shows approve/reject buttons in the chat panel
     *
     * @param isBuy true for buy, false for sell
     * @param quantity number of contracts
     * @param price target price (or 0 for market)
     * @param signalType type of signal that triggered this
     * @param score confluence score
     * @param onApprove callback when user approves
     * @param onReject callback when user rejects
     * @return request ID for tracking
     */
    public String requestTradeApproval(boolean isBuy, int quantity, double price,
                                       String signalType, int score,
                                       Runnable onApprove, Runnable onReject) {
        // Cancel any pending request
        if (pendingTradeRequest != null) {
            log("⚠️ Previous pending trade request replaced");
            hideApprovalPanel();
        }

        // Create new pending request
        pendingTradeRequest = new PendingTradeRequest();
        pendingTradeRequest.requestId = "req_" + System.currentTimeMillis();
        pendingTradeRequest.isBuy = isBuy;
        pendingTradeRequest.quantity = quantity;
        pendingTradeRequest.price = price;
        pendingTradeRequest.signalType = signalType;
        pendingTradeRequest.score = score;
        pendingTradeRequest.timestamp = System.currentTimeMillis();
        pendingTradeRequest.onApprove = onApprove;
        pendingTradeRequest.onReject = onReject;

        // Show approval panel in chat window
        showApprovalPanel();

        return pendingTradeRequest.requestId;
    }

    /**
     * Show the approval panel with pending trade details
     */
    private void showApprovalPanel() {
        if (pendingTradeRequest == null || approvalPanel == null) return;

        SwingUtilities.invokeLater(() -> {
            String direction = pendingTradeRequest.isBuy ? "BUY" : "SELL";
            String priceStr = pendingTradeRequest.price > 0 ?
                String.format("@ %.2f", pendingTradeRequest.price) : "@ MARKET";

            approvalLabel.setText(String.format(
                "🔔 Trade Request: %s %d %s %s | Signal: %s (Score: %d)",
                direction, pendingTradeRequest.quantity,
                alias != null ? alias : "contracts",
                priceStr, pendingTradeRequest.signalType, pendingTradeRequest.score
            ));

            approvalPanel.setVisible(true);
            approvalPanel.revalidate();
            approvalPanel.repaint();

            // Also add to chat history
            appendChatMessage("AI", String.format(
                "🔔 **Trade Approval Request**\n\n" +
                "**Direction:** %s\n" +
                "**Quantity:** %d\n" +
                "**Price:** %s\n" +
                "**Signal:** %s (Score: %d)\n\n" +
                "Please click Approve or Reject below.",
                direction, pendingTradeRequest.quantity, priceStr,
                pendingTradeRequest.signalType, pendingTradeRequest.score
            ));

            log("🔔 Trade approval requested: " + direction + " " + pendingTradeRequest.quantity);
        });
    }

    /**
     * Hide the approval panel
     */
    private void hideApprovalPanel() {
        if (approvalPanel == null) return;

        SwingUtilities.invokeLater(() -> {
            approvalPanel.setVisible(false);
            approvalPanel.revalidate();
            approvalPanel.repaint();
        });
    }

    /**
     * Handle approve button click
     */
    private void handleApproveTrade() {
        if (pendingTradeRequest == null) {
            appendChatMessage("System", "⚠️ No pending trade request to approve.");
            return;
        }

        log("✅ Trade APPROVED: " + (pendingTradeRequest.isBuy ? "BUY" : "SELL"));
        appendChatMessage("You", "✅ Approved");

        // Store callbacks before clearing
        Runnable onApprove = pendingTradeRequest.onApprove;
        String requestId = pendingTradeRequest.requestId;

        // Clear pending request
        pendingTradeRequest = null;
        hideApprovalPanel();

        // Execute approval callback
        if (onApprove != null) {
            try {
                onApprove.run();
            } catch (Exception e) {
                log("❌ Error executing approval callback: " + e.getMessage());
            }
        }
    }

    /**
     * Handle reject button click
     */
    private void handleRejectTrade() {
        if (pendingTradeRequest == null) {
            appendChatMessage("System", "⚠️ No pending trade request to reject.");
            return;
        }

        log("❌ Trade REJECTED: " + (pendingTradeRequest.isBuy ? "BUY" : "SELL"));
        appendChatMessage("You", "❌ Rejected");

        // Store callbacks before clearing
        Runnable onReject = pendingTradeRequest.onReject;

        // Clear pending request
        pendingTradeRequest = null;
        hideApprovalPanel();

        // Execute reject callback
        if (onReject != null) {
            try {
                onReject.run();
            } catch (Exception e) {
                log("❌ Error executing reject callback: " + e.getMessage());
            }
        }
    }

    /**
     * Check if there's a pending trade approval request
     */
    public boolean hasPendingTradeRequest() {
        return pendingTradeRequest != null;
    }

    /**
     * Cancel any pending trade request (e.g., on mode change)
     */
    public void cancelPendingTradeRequest() {
        if (pendingTradeRequest != null) {
            log("⚠️ Pending trade request cancelled");
            pendingTradeRequest = null;
            hideApprovalPanel();
        }
    }

    // ========== PERIODIC UPDATE TIMER ==========

    private java.util.Timer periodicUpdateTimer;
    private long lastPeriodicUpdateTime = 0;

    private void startPeriodicUpdateTimer() {
        if (!enablePeriodicUpdates || aiThresholdService == null) return;

        if (periodicUpdateTimer != null) {
            periodicUpdateTimer.cancel();
        }

        periodicUpdateTimer = new java.util.Timer("PeriodicUpdateTimer", true);
        long intervalMs = periodicUpdateIntervalMinutes * 60 * 1000L;

        periodicUpdateTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    generatePeriodicUpdate();
                } catch (Exception e) {
                    log("Error in periodic update: " + e.getMessage());
                }
            }
        }, intervalMs, intervalMs);

        log("⏰ Periodic update timer started (every " + periodicUpdateIntervalMinutes + " min)");
    }

    private void stopPeriodicUpdateTimer() {
        if (periodicUpdateTimer != null) {
            periodicUpdateTimer.cancel();
            periodicUpdateTimer = null;
        }
    }

    /**
     * Generate a periodic status update from AI
     */
    private void generatePeriodicUpdate() {
        if (!enablePeriodicUpdates || aiThresholdService == null || aiToolsProvider == null) {
            return;
        }

        log("🔔 Generating periodic status update...");

        // Build status prompt
        String prompt = """
            Please provide a brief status update for the user. Include:
            1. Current session performance (trades, win rate, P&L)
            2. Current market state (price, CVD direction, trend)
            3. Any notable observations or recommendations
            4. Current threshold settings if changed

            Keep it concise - 3-5 sentences max. This is a periodic check-in, not a full analysis.
            """;

        // Get AI response
        aiThresholdService.chatWithTools(prompt)
            .thenAccept(response -> {
                pushNotification("PERIODIC", response, "low");
            })
            .exceptionally(ex -> {
                log("Failed to generate periodic update: " + ex.getMessage());
                return null;
            });
    }

    /**
     * Parse markdown to HTML for chat rendering
     */
    private String parseMarkdownToHtml(String markdown) {
        String html = markdown;

        // Escape HTML special characters first
        html = html
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");

        // Parse inline code: `code` -> <code>code</code>
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // Parse bold: **text** -> <strong>text</strong>
        html = html.replaceAll("\\*\\*([^*]+?)\\*\\*", "<strong>$1</strong>");

        // Parse italic: *text* -> <em>text</em>
        html = html.replaceAll("\\*([^*]+?)\\*", "<em>$1</em>");

        // Parse headers: ### text -> <h3>text</h3>
        html = html.replaceAll("(?m)^###\\s+(.+)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^##\\s+(.+)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^#\\s+(.+)$", "<h1>$1</h1>");

        // Parse bullet lists: lines starting with * -> <ul><li>...</li></ul>
        String[] lines = html.split("<br/>");
        StringBuilder result = new StringBuilder();
        boolean inList = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isBullet = trimmed.startsWith("* ") &&
                                 !trimmed.startsWith("**") &&
                                 !trimmed.contains("</strong>") &&
                                 !trimmed.contains("</em>") &&
                                 !trimmed.startsWith("<h");  // Exclude headers

            if (isBullet) {
                if (!inList) {
                    result.append("<ul>");
                    inList = true;
                }
                String item = trimmed.substring(2);
                result.append("<li>").append(item).append("</li>");
            } else {
                if (inList) {
                    result.append("</ul>");
                    inList = false;
                }
                result.append(line);
            }
        }

        if (inList) {
            result.append("</ul>");
        }

        return result.toString();
    }

    private void clearChatHistory() {
        SwingUtilities.invokeLater(() -> {
            int confirm = JOptionPane.showConfirmDialog(chatWindow,
                "Are you sure you want to clear the chat history?",
                "Clear Chat",
                JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                // Reset HTML content
                String css = "<style>" +
                    "body { font-family: SansSerif, Arial, sans-serif; font-size: 12px; color: #E0E0E0; padding: 8px; background: #1E1E1E; }" +
                    ".user-msg { color: #64B5F6; font-weight: bold; margin: 10px 0; }" +
                    ".ai-msg { color: #E0E0E0; margin: 10px 0; }" +
                    ".system-msg { color: #9E9E9E; font-style: italic; margin: 10px 0; }" +
                    ".timestamp { color: #757575; font-size: 11px; }" +
                    "code { background: #2D2D2D; color: #FFFFFF; padding: 2px 8px; border-radius: 4px; font-family: Monaco, monospace; }" +
                    "pre { background: #2D2D2D; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 10px 0; border: 1px solid #3D3D3D; }" +
                    "strong { color: #FFFFFF; }" +
                    "em { color: #B0B0B0; }" +
                    "h1, h2, h3 { margin: 12px 0 8px 0; color: #FFFFFF; font-weight: bold; }" +
                    "ul, ol { margin: 8px 0; padding-left: 20px; }" +
                    "li { margin: 4px 0; }" +
                    "a { color: #64B5F6; text-decoration: underline; }" +
                    "hr { border: none; border-top: 1px solid #3D3D3D; margin: 12px 0; }" +
                    "</style>";
                chatHtmlContent = new StringBuilder();
                chatHtmlContent.append("<html><head>").append(css).append("</head><body>");
                chatHistoryArea.setText(chatHtmlContent.toString() + "</body></html>");
                chatMessages.clear();
                appendChatMessage("System", "Chat history cleared.\n\nAsk me anything about trading!");
            }
        });
    }

    // ========== PANEL UPDATE METHODS ==========

    private void updateStatsPanel() {
        SwingUtilities.invokeLater(() -> {
            // Check if panels have been created yet
            if (totalTradesLabel == null) {
                return; // Panels not initialized yet, skip update
            }

            // All-time metrics
            totalTradesLabel.setText(String.valueOf(totalTrades.get()));
            double winRate = totalTrades.get() > 0 ?
                (winningTrades.get() * 100.0 / totalTrades.get()) : 0.0;
            winRateLabel.setText(String.format("%.1f%%", winRate));

            long pnl = totalPnL.get();
            totalPnLLabel.setText(String.format("$%,.2f", pnl / 100.0));
            totalPnLLabel.setForeground(pnl >= 0 ? new Color(0, 150, 0) : Color.RED);

            // Today's metrics - make copy to avoid ConcurrentModificationException
            int todayTradeCount = todayTrades.size();
            todayTradesLabel.setText(String.valueOf(todayTradeCount));
            double todayWinRate = 0.0;
            if (todayTradeCount > 0) {
                List<Trade> todayTradesCopy = new ArrayList<>(todayTrades);
                long winners = todayTradesCopy.stream().filter(t -> t.pnl > 0).count();
                todayWinRate = winners * 100.0 / todayTradeCount;
            }
            todayWinRateLabel.setText(String.format("%.1f%%", todayWinRate));

            todayPnLLabel.setText(String.format("$%,.2f", todayPnL));
            todayPnLLabel.setForeground(todayPnL >= 0 ? new Color(0, 150, 0) : Color.RED);
            todayDrawdownLabel.setText(String.format("$%,.2f", todayMaxDrawdown));

            // Current activity
            int trackedCount = trackedSignals.size();
            int openCount = (int) trackedSignals.values().stream()
                .filter(p -> p.profitable == null)
                .count();
            activeSignalsLabel.setText(openCount + " / " + trackedCount);
            if (lastSignal != null) {
                lastSignalScoreLabel.setText("SIZE: " + lastSignal);
                lastSignalTimeLabel.setText("Recent");
            }

            // Adaptive thresholds - make copies to avoid ConcurrentModificationException
            double avgOrders = 0.0;
            double avgSizes = 0.0;
            try {
                avgOrders = new LinkedList<>(recentOrderCounts)
                    .stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
                avgSizes = new LinkedList<>(recentTotalSizes)
                    .stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
            } catch (Exception e) {
                // Use cached values if concurrent modification occurs
            }
            avgOrderCountLabel.setText(String.format("%.1f", avgOrders));
            avgOrderSizeLabel.setText(String.format("%.1f", avgSizes));
            currentThresholdLabel.setText(String.format("%d orders, %d size",
                adaptiveOrderThreshold, adaptiveSizeThreshold));

            // Check alerts
            checkAlerts();
        });
    }

    private String formatTimeAgo(long millis) {
        if (millis < 60000) return (millis / 1000) + " sec ago";
        if (millis < 3600000) return (millis / 60000) + " min ago";
        return (millis / 3600000) + " hr ago";
    }

    private void checkAlerts() {
        // Daily loss limit alert (only show once)
        if (todayPnL <= -dailyLossLimit && !dailyLossAlertShown) {
            dailyLossAlertShown = true;
            showAlert("⛔ DAILY LOSS LIMIT REACHED!",
                "Today's loss: $" + todayPnL + "\n" +
                "Limit: $" + dailyLossLimit + "\n\n" +
                "⛔ STOPPING TRADING FOR TODAY!");
        }

        // Drawdown alert (only show once)
        if (todayMaxDrawdown > dailyLossLimit * 0.8 && !drawdownWarningShown) {
            drawdownWarningShown = true;
            showWarning("⚠️ Approaching max drawdown",
                "Current: $" + todayMaxDrawdown);
        }
    }

    private void showAlert(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            JTextArea textArea = new JTextArea(message);
            textArea.setEditable(false);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(400, 150));
            JOptionPane.showMessageDialog(statsPanel, scrollPane, title, JOptionPane.ERROR_MESSAGE);
        });
    }

    private void showWarning(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            JTextArea textArea = new JTextArea(message);
            textArea.setEditable(false);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(400, 150));
            JOptionPane.showMessageDialog(statsPanel, scrollPane, title, JOptionPane.WARNING_MESSAGE);
        });
    }

    // ========== SETTINGS UPDATE METHODS ==========

    private void updateMinConfluence() {
        minConfluenceScore = (Integer) minConfluenceSpinner.getValue();
        settings.minConfluenceScore = minConfluenceScore;
        log("📊 Min Confluence Score updated: " + minConfluenceScore);
        saveSettings();
    }

    private void updateThresholdMultiplier() {
        thresholdMultiplier = (Double) thresholdMultSpinner.getValue();
        settings.thresholdMultiplier = thresholdMultiplier;
        log("📊 Threshold Multiplier updated: " + thresholdMultiplier);
        saveSettings();
    }

    private void updateAdaptiveMode() {
        boolean selected = adaptiveModeCheckBox.isSelected();

        if (selected) {
            // Switching to adaptive mode
            log("🔄 Adaptive Mode: ENABLED (thresholds auto-calculated from market data)");
            log("📊 Manual threshold controls disabled");
        } else {
            // Switching to manual mode - sync spinner values with current thresholds
            SwingUtilities.invokeLater(() -> {
                adaptOrderSpinner.setValue(adaptiveOrderThreshold);
                adaptSizeSpinner.setValue(adaptiveSizeThreshold);
            });
            log("🎛️ Manual Mode: ENABLED (using your threshold values)");
            log("📊 Thresholds set to - Orders: " + adaptiveOrderThreshold + ", Size: " + adaptiveSizeThreshold);
        }

        // Enable/disable spinners based on mode
        adaptOrderSpinner.setEnabled(!selected);
        adaptSizeSpinner.setEnabled(!selected);
    }

    /**
     * Initialize AI Trading components
     * Can be called at startup or when AI Trading is enabled in settings
     */
    private void initializeAIComponents() {
        String effectiveToken = getEffectiveApiToken();
        if (effectiveToken == null || effectiveToken.isEmpty()) {
            log("⚠️ Cannot initialize AI - no auth token");
            return;
        }

        if (aiOrderManager != null) {
            log("ℹ️ AI components already initialized");
            return;
        }

        log("🤖 Initializing AI Trading System...");

        try {
            // Create order executor with logger wrapper using real Bookmap API
            orderExecutor = new BookmapOrderExecutor(api, alias, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            });

            // Create AI integration layer with logger wrapper
            aiIntegration = new AIIntegrationLayer(effectiveToken, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            });

            // Set trading session plan
            String sessionPlan = String.format(
                "Trading Plan for %s:\n" +
                "- Max %d trades per day\n" +
                "- Risk 1%% per trade\n" +
                "- 1:2 reward-risk ratio\n" +
                "- Break-even at +3 ticks\n" +
                "- Focus on quality over quantity\n" +
                "- Current mode: %s",
                alias, maxPosition, aiMode
            );
            aiIntegration.setSessionPlan(sessionPlan);

            // Create AI order manager with logger wrapper and marker callback
            // Debug: Check if 'this' is null before passing
            fileLog("🔍 BEFORE AIOrderManager creation: this=" + (this != null ? "NOT NULL" : "NULL"));
            fileLog("🔍 this implements AIMarkerCallback: " + (this instanceof AIOrderManager.AIMarkerCallback));

            aiOrderManager = new AIOrderManager(orderExecutor, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            }, this);  // Pass 'this' as the marker callback

            // Debug: Verify aiOrderManager was created with callback
            fileLog("🔍 AFTER AIOrderManager creation: aiOrderManager=" + (aiOrderManager != null ? "NOT NULL" : "NULL"));
            aiOrderManager.breakEvenEnabled = true;
            aiOrderManager.breakEvenTicks = 3;
            aiOrderManager.maxPositions = maxPosition;
            aiOrderManager.maxDailyLoss = dailyLossLimit;
            aiOrderManager.maxPriceSlippageTicks = maxSlippageTicks;

            // Set up price supplier for staleness checks
            aiOrderManager.setCurrentPriceSupplier(() -> (int) lastKnownPrice);

            // Set up time supplier for staleness checks (uses Bookmap data time for replay mode support)
            aiOrderManager.setCurrentTimeSupplier(() -> currentDataTimestampMs);

            // Set up context suppliers for historical recording
            aiOrderManager.setContextSuppliers(
                () -> alias,  // symbol
                () -> confluenceWeights,  // weights
                () -> minConfluenceScore,  // minConfluenceScore
                () -> confluenceThreshold,  // confluenceThreshold
                () -> thresholdMultiplier,  // thresholdMultiplier
                () -> icebergMinOrders,  // icebergMinOrders
                () -> spoofMinSize,  // spoofMinSize
                () -> absorptionMinSize  // absorptionMinSize
            );

            // Set up trade logging and session state persistence
            if (tradeLogger != null) {
                aiOrderManager.setTradeLogger(tradeLogger);
            }
            if (sessionStateManager != null) {
                aiOrderManager.setSessionStateManager(sessionStateManager);
            }
            aiOrderManager.setTickValue(tickValue);

            log("✅ AI Trading System initialized");
            log("   Mode: " + aiMode);
            log("   Confluence Threshold: " + confluenceThreshold);

            // Initialize AI Strategist if memory service is available
            if (memoryService != null && aiStrategist == null) {
                aiStrategist = new AIInvestmentStrategist(
                    (velox.api.layer1.simplified.demo.storage.TradingMemoryService) memoryService,
                    aiAuthToken, transcriptWriter);
                aiStrategist.setPromptMode(aiPromptMode);
                log("✅ AI Investment Strategist initialized");
            }

            // Initialize Pre-Market Analyzer
            if (preMarketAnalyzer == null && tradingMemoryDir != null) {
                preMarketAnalyzer = new PreMarketAnalyzer(
                    getEffectiveApiToken(),
                    tradingMemoryDir.toFile(),
                    (velox.api.layer1.simplified.demo.storage.TradingMemoryService) memoryService
                );
                // Wire up data suppliers
                preMarketAnalyzer.setPriceSupplier(() -> getCurrentPrice() * pips);
                preMarketAnalyzer.setCvdSupplier(() -> cvdCalculator.getCVD());
                preMarketAnalyzer.setVwapSupplier(() -> vwapCalculator.isInitialized() ? vwapCalculator.getVWAP() * pips : 0.0);
                preMarketAnalyzer.setEmaSupplier(() -> new double[] {
                    ema9.isInitialized() ? ema9.getEMA() * pips : 0.0,
                    ema21.isInitialized() ? ema21.getEMA() * pips : 0.0,
                    ema50.isInitialized() ? ema50.getEMA() * pips : 0.0
                });
                preMarketAnalyzer.setPocSupplier(() -> {
                    return (double) volumeProfile.getPOC() * pips;
                });
                preMarketAnalyzer.setValueAreaSupplier(() -> {
                    VolumeProfileCalculator.ValueArea va = volumeProfile.getValueArea();
                    return new double[] { va.vaLow * pips, va.vaHigh * pips };
                });
                preMarketAnalyzer.setDomSupplier(() -> {
                    DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
                    DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();
                    double supportPrice = support != null ? support.price * pips : 0.0;
                    double resistancePrice = resistance != null ? resistance.price * pips : 0.0;
                    return new double[] {
                        supportPrice,
                        resistancePrice,
                        domAnalyzer.getImbalanceRatio()
                    };
                });
                preMarketAnalyzer.setInstrumentSupplier(() -> alias);
                preMarketAnalyzer.setPipsSupplier(() -> pips);
                log("✅ Pre-Market Analyzer initialized");
            }

            // Initialize Session Phase Analyzer (new phase-aware system)
            if (sessionPhaseAnalyzer == null && tradingMemoryDir != null) {
                String phaseApiToken = getEffectiveApiToken();
                sessionPhaseAnalyzer = new SessionPhaseAnalyzer(
                    phaseApiToken,
                    tradingMemoryDir.toFile(),
                    (velox.api.layer1.simplified.demo.storage.TradingMemoryService) memoryService
                );
                sessionPhaseAnalyzer.setLogger(this::log);
                sessionPhaseAnalyzer.setPriceSupplier(() -> getCurrentPrice() * pips);
                sessionPhaseAnalyzer.setCvdSupplier(() -> cvdCalculator.getCVD());
                sessionPhaseAnalyzer.setVwapSupplier(() -> vwapCalculator.isInitialized() ? vwapCalculator.getVWAP() * pips : 0.0);
                sessionPhaseAnalyzer.setEmaSupplier(() -> new double[] {
                    ema9.isInitialized() ? ema9.getEMA() * pips : 0.0,
                    ema21.isInitialized() ? ema21.getEMA() * pips : 0.0,
                    ema50.isInitialized() ? ema50.getEMA() * pips : 0.0
                });
                sessionPhaseAnalyzer.setPocSupplier(() -> (double) volumeProfile.getPOC() * pips);
                sessionPhaseAnalyzer.setValueAreaSupplier(() -> {
                    VolumeProfileCalculator.ValueArea va = volumeProfile.getValueArea();
                    return new double[] { va.vaLow * pips, va.vaHigh * pips };
                });
                sessionPhaseAnalyzer.setDomSupplier(() -> {
                    DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
                    DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();
                    return new double[] {
                        support != null ? support.price * pips : 0.0,
                        support != null ? support.volume : 0,
                        resistance != null ? resistance.price * pips : 0.0,
                        resistance != null ? resistance.volume : 0
                    };
                });
                sessionPhaseAnalyzer.setInstrumentSupplier(() -> alias);
                sessionPhaseAnalyzer.setPipsSupplier(() -> pips);
                log("✅ Session Phase Analyzer initialized");
            }

            // Initialize Session Phase Tracker (automatic phase detection)
            if (sessionPhaseTracker == null) {
                sessionPhaseTracker = new SessionPhaseTracker(60);  // Check every 60 seconds
                sessionPhaseTracker.setLogger(this::log);

                // Set up phase transition callback
                sessionPhaseTracker.setOnPhaseTransition(this::onPhaseTransition);

                // Start tracking
                sessionPhaseTracker.start();
                log("✅ Session Phase Tracker started - current phase: " + sessionPhaseTracker.getCurrentPhase().getDisplayName());

                // If we're in PRE_MARKET and auto analysis is enabled, run initial analysis
                if (autoPhaseAnalysis && sessionPhaseTracker.getCurrentPhase() == SessionPhaseTracker.SessionPhase.PRE_MARKET) {
                    if (sessionPhaseAnalyzer != null && !sessionPhaseAnalyzer.getContextManager().hasTodaysContext()) {
                        log("📊 Auto-running pre-market analysis (no context for today)");
                        runAutoPreMarketAnalysis();
                    }
                }
            }

        } catch (Exception e) {
            log("❌ Failed to initialize AI Trading: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateSimMode() {
        boolean selected = simModeCheckBox.isSelected();
        if (!selected) {
            int result = JOptionPane.showConfirmDialog(settingsPanel,
                "⚠️ WARNING: You are about to DISABLE simulation mode!\n\n" +
                "This will enable LIVE trading with REAL money.\n" +
                "You are responsible for all losses.\n\n" +
                "Are you sure you want to continue?",
                "LIVE TRADING WARNING",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                SwingUtilities.invokeLater(() -> simModeCheckBox.setSelected(true));
                return;
            }
        }
        simModeOnly = selected;
        log("🔒 Simulation Mode: " + (simModeOnly ? "ENABLED" : "DISABLED ⚠️"));
    }

    private void updateAutoExecution() {
        boolean selected = autoExecCheckBox.isSelected();
        if (selected && simModeOnly) {
            SwingUtilities.invokeLater(() -> autoExecCheckBox.setSelected(false));
            showAlert("Cannot Enable Auto-Execution",
                "Auto-execution cannot be enabled while in simulation mode.\n\n" +
                "Disable simulation mode first.");
            return;
        }
        autoExecution = selected;
        log("🤖 Auto-Execution: " + (autoExecution ? "ENABLED ⚠️" : "DISABLED"));
    }

    /**
     * Update AI weight and threshold labels in settings panel to show current AI-adjusted values
     */
    private void updateAIWeightLabels() {
        SwingUtilities.invokeLater(() -> {
            // Confluence weights
            if (aiIcebergMaxLabel != null) {
                aiIcebergMaxLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX)));
            }
            if (aiCvdAlignLabel != null) {
                aiCvdAlignLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX)));
            }
            if (aiCvdDivergeLabel != null) {
                aiCvdDivergeLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY)));
            }
            if (aiEmaAlignLabel != null) {
                aiEmaAlignLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX)));
            }
            if (aiVwapAlignLabel != null) {
                aiVwapAlignLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN)));
            }
            if (aiVwapDivergeLabel != null) {
                aiVwapDivergeLabel.setText(String.valueOf(confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE)));
            }
            // Signal thresholds
            if (aiMinConfluenceLabel != null) {
                aiMinConfluenceLabel.setText(String.valueOf(minConfluenceScore));
            }
            if (aiConfluenceThresholdLabel != null) {
                aiConfluenceThresholdLabel.setText(String.valueOf(confluenceThreshold));
            }
            if (aiThresholdMultLabel != null) {
                aiThresholdMultLabel.setText(String.format("%.1f", thresholdMultiplier));
            }
            if (aiIcebergMinOrdersLabel != null) {
                aiIcebergMinOrdersLabel.setText(String.valueOf(icebergMinOrders));
            }
            if (aiSpoofMinSizeLabel != null) {
                aiSpoofMinSizeLabel.setText(String.valueOf(spoofMinSize));
            }
            if (aiAbsorptionMinSizeLabel != null) {
                aiAbsorptionMinSizeLabel.setText(String.valueOf(absorptionMinSize));
            }
        });
    }

    private void updateAIAdaptiveMode() {
        boolean selected = aiAdaptiveModeCheckBox.isSelected();
        useAIAdaptiveThresholds = selected;

        if (aiThresholdService == null) {
            SwingUtilities.invokeLater(() -> aiAdaptiveModeCheckBox.setSelected(false));
            showAlert("AI Not Available",
                "AI Threshold Service requires an AI Auth Token.\n\n" +
                "Please set your 'AI Auth Token' parameter first.");
            return;
        }

        if (selected) {
            // Only update status if AI Trading is not enabled
            if (!enableAITrading) {
                aiStatusIndicator.setText("🟢 AI Adaptive Active");
                aiStatusIndicator.setForeground(new Color(0, 150, 0));
            }
            log("🤖 AI Adaptive Mode: ENABLED");
            log("   AI will analyze market conditions and optimize thresholds");

            // Trigger initial calculation
            triggerAIReevaluation();
        } else {
            // Only update status if AI Trading is not enabled
            if (!enableAITrading) {
                aiStatusIndicator.setText("🔴 AI Disabled");
                aiStatusIndicator.setForeground(Color.GRAY);
            }
            log("🎛️ AI Adaptive Mode: DISABLED (using manual settings)");
        }
    }

    private void triggerAIChat() {
        if (aiThresholdService == null) {
            showAlert("AI Not Available",
                "AI Service is not initialized.\n\n" +
                "Please set your 'AI Auth Token' parameter and restart.");
            return;
        }

        // Default prompt
        final String userPrompt = "What trading advice do you have for current market conditions?";

        // Update status
        aiStatusIndicator.setText("🔄 Thinking...");
        aiStatusIndicator.setForeground(Color.BLUE);

        log("💬 Sending chat prompt to AI...");

        // Send prompt to AI and get raw response
        aiThresholdService.chat(userPrompt)
            .thenAcceptAsync(response -> {
                log("✅ AI response received");

                // Show raw response in dialog
                SwingUtilities.invokeLater(() -> {
                    showAIResponseDialog(userPrompt, response);

                    // Update status
                    aiStatusIndicator.setText("🟢 Ready");
                    aiStatusIndicator.setForeground(new Color(0, 150, 0));
                });
            }, SwingUtilities::invokeLater)
            .exceptionally(ex -> {
                String errorMsg = "AI chat failed: " + ex.getMessage();
                log("❌ " + errorMsg);
                SwingUtilities.invokeLater(() -> {
                    showAlert("AI Error", errorMsg);
                    aiStatusIndicator.setText("❌ Error");
                    aiStatusIndicator.setForeground(Color.RED);
                });
                return null;
            });
    }

    private void triggerAIReevaluation() {
        if (aiThresholdService == null) {
            showAlert("AI Not Available",
                "AI Threshold Service is not initialized.\n\n" +
                "Please set your 'AI Auth Token' parameter and restart.");
            return;
        }

        // Disable button during calculation
        aiReevaluateButton.setEnabled(false);
        aiStatusIndicator.setText("🔄 Optimizing...");
        aiStatusIndicator.setForeground(Color.BLUE);

        log("🔄 Triggering AI threshold optimization...");

        // Build market context
        AIThresholdService.MarketContext context = buildMarketContext();

        // Default custom prompt
        String customPrompt = "Optimize thresholds for current market conditions";

        // Calculate thresholds asynchronously
        aiThresholdService.calculateThresholds(context, customPrompt)
            .thenAcceptAsync(recommendation -> {
                // Apply AI recommendations
                applyAIRecommendations(recommendation);

                // Re-enable button
                SwingUtilities.invokeLater(() -> {
                    aiReevaluateButton.setEnabled(true);
                    if (useAIAdaptiveThresholds) {
                        aiStatusIndicator.setText("🟢 AI Active");
                        aiStatusIndicator.setForeground(new Color(0, 150, 0));
                    } else {
                        aiStatusIndicator.setText("✅ Updated");
                        aiStatusIndicator.setForeground(new Color(0, 100, 200));
                    }
                });
            }, SwingUtilities::invokeLater)
            .exceptionally(ex -> {
                String errorMsg = "AI optimization failed: " + ex.getMessage();
                log("❌ " + errorMsg);
                SwingUtilities.invokeLater(() -> {
                    showAlert("AI Optimization Failed", errorMsg);
                    aiReevaluateButton.setEnabled(true);
                    aiStatusIndicator.setText("❌ Error");
                    aiStatusIndicator.setForeground(Color.RED);
                });
                return null;
            });
    }

    /**
     * Run pre-market analysis and create trade plan
     * This performs a deep analysis of current market conditions and stores in memory
     */
    private void runPreMarketAnalysis() {
        // Initialize if needed
        String effectiveToken = getEffectiveApiToken();
        if (preMarketAnalyzer == null) {
            // Try to initialize
            if (tradingMemoryDir != null && effectiveToken != null && !effectiveToken.isEmpty()) {
                preMarketAnalyzer = new PreMarketAnalyzer(
                    effectiveToken,
                    tradingMemoryDir.toFile(),
                    (velox.api.layer1.simplified.demo.storage.TradingMemoryService) memoryService
                );
                // Wire up data suppliers
                preMarketAnalyzer.setPriceSupplier(() -> getCurrentPrice() * pips);
                preMarketAnalyzer.setCvdSupplier(() -> cvdCalculator.getCVD());
                preMarketAnalyzer.setVwapSupplier(() -> vwapCalculator.isInitialized() ? vwapCalculator.getVWAP() * pips : 0.0);
                preMarketAnalyzer.setEmaSupplier(() -> new double[] {
                    ema9.isInitialized() ? ema9.getEMA() * pips : 0.0,
                    ema21.isInitialized() ? ema21.getEMA() * pips : 0.0,
                    ema50.isInitialized() ? ema50.getEMA() * pips : 0.0
                });
                preMarketAnalyzer.setPocSupplier(() -> {
                    return (double) volumeProfile.getPOC() * pips;
                });
                preMarketAnalyzer.setValueAreaSupplier(() -> {
                    VolumeProfileCalculator.ValueArea va = volumeProfile.getValueArea();
                    return new double[] { va.vaLow * pips, va.vaHigh * pips };
                });
                preMarketAnalyzer.setDomSupplier(() -> {
                    DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
                    DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();
                    double supportPrice = support != null ? support.price * pips : 0.0;
                    double resistancePrice = resistance != null ? resistance.price * pips : 0.0;
                    return new double[] {
                        supportPrice,
                        resistancePrice,
                        domAnalyzer.getImbalanceRatio()
                    };
                });
                preMarketAnalyzer.setInstrumentSupplier(() -> alias);
                preMarketAnalyzer.setPipsSupplier(() -> pips);
                log("✅ Pre-Market Analyzer initialized on-demand");
            } else {
                showAlert("Pre-Market Analysis Unavailable",
                    "Pre-market analysis requires:\n\n" +
                    "1. AI Auth Token configured\n" +
                    "2. Memory directory available\n\n" +
                    "Please configure these settings and try again.");
                return;
            }
        }

        log("📊 Running Pre-Market Analysis...");
        log("   This will analyze current market and create a trade plan.");

        // Run analysis asynchronously
        preMarketAnalyzer.runAnalysis((analysis, success) -> {
            SwingUtilities.invokeLater(() -> {
                if (success) {
                    showPreMarketDialog(analysis);
                } else {
                    showAlert("Pre-Market Analysis Failed", analysis);
                }
            });
        });
    }

    /**
     * Handle phase transition events from SessionPhaseTracker
     * Automatically runs analysis on phase changes if auto-phase-analysis is enabled
     */
    private void onPhaseTransition(SessionPhaseTracker.PhaseTransition transition) {
        log("📊 PHASE TRANSITION: " +
            (transition.fromPhase != null ? transition.fromPhase.getDisplayName() : "START") +
            " → " + transition.toPhase.getDisplayName());

        if (!autoPhaseAnalysis) {
            log("   Auto phase analysis disabled, skipping");
            return;
        }

        if (sessionPhaseAnalyzer == null) {
            log("   SessionPhaseAnalyzer not initialized, skipping");
            return;
        }

        // Handle different phase transitions
        switch (transition.toPhase) {
            case PRE_MARKET:
                // Run full pre-market analysis if no context for today
                if (!sessionPhaseAnalyzer.getContextManager().hasTodaysContext()) {
                    log("📊 Running auto pre-market analysis...");
                    runAutoPreMarketAnalysis();
                }
                break;

            case OPENING_RANGE:
            case MORNING_SESSION:
            case LUNCH:
            case AFTERNOON:
            case CLOSE:
                // Run quick phase update
                log("📊 Running phase update for: " + transition.toPhase.getDisplayName());
                runAutoPhaseUpdate(transition.toPhase);
                break;

            case AFTER_HOURS:
                // Market closed - could clean up old contexts
                log("📊 Market closed - session ended");
                sessionPhaseAnalyzer.getContextManager().cleanupOldContexts(7);  // Keep last 7 days
                break;
        }
    }

    /**
     * Auto-run pre-market analysis (no dialog, just log and store)
     */
    private void runAutoPreMarketAnalysis() {
        if (sessionPhaseAnalyzer == null) {
            log("⚠️ Cannot run auto pre-market: SessionPhaseAnalyzer not initialized");
            return;
        }

        sessionPhaseAnalyzer.runPreMarketAnalysis((analysis, success) -> {
            if (success) {
                log("✅ Auto pre-market analysis complete - session context saved");
            } else {
                log("⚠️ Auto pre-market analysis failed: " + analysis);
            }
        });
    }

    /**
     * Auto-run phase update (quick check)
     */
    private void runAutoPhaseUpdate(SessionPhaseTracker.SessionPhase phase) {
        if (sessionPhaseAnalyzer == null) {
            log("⚠️ Cannot run phase update: SessionPhaseAnalyzer not initialized");
            return;
        }

        sessionPhaseAnalyzer.runPhaseUpdate(phase, (analysis, success) -> {
            if (success) {
                log("✅ Phase update complete: " + phase.getDisplayName());
            } else {
                log("⚠️ Phase update failed: " + analysis);
            }
        });
    }

    /**
     * Get today's session context (for AI tools and strategist)
     */
    public String getTodaysSessionContext() {
        if (sessionPhaseAnalyzer != null) {
            return sessionPhaseAnalyzer.getContextManager().getTodaysContextSummary();
        }
        return null;
    }

    /**
     * Get current trading phase
     */
    public SessionPhaseTracker.SessionPhase getCurrentTradingPhase() {
        if (sessionPhaseTracker != null) {
            return sessionPhaseTracker.getCurrentPhase();
        }
        return SessionPhaseTracker.SessionPhase.PRE_MARKET;
    }

    /**
     * Show pre-market analysis result in a dialog
     */
    private void showPreMarketDialog(String analysis) {
        // Create scrollable text area
        JTextArea textArea = new JTextArea(analysis);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(700, 500));

        // Show in dialog
        JOptionPane.showMessageDialog(
            null,
            scrollPane,
            "📊 Pre-Market Analysis - " + alias,
            JOptionPane.INFORMATION_MESSAGE
        );

        log("✅ Pre-Market Analysis complete and saved to memory");
    }

    private void showAIResponseDialog(String prompt, String response) {
        // Create dialog with prompt and response
        StringBuilder text = new StringBuilder();
        text.append("YOUR QUESTION:\n");
        text.append(prompt);
        text.append("\n\n");
        text.append("─".repeat(60));
        text.append("\n\n");
        text.append("AI RESPONSE:\n");
        text.append(response);

        JTextArea textArea = new JTextArea(text.toString());
        textArea.setEditable(false);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 500));

        JOptionPane.showMessageDialog(settingsPanel,
            scrollPane,
            "AI Response",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private AIThresholdService.MarketContext buildMarketContext() {
        AIThresholdService.MarketContext context = new AIThresholdService.MarketContext(alias);

        // Current market state - use actual price (not tick price)
        context.currentPrice = getCurrentPrice() * pips;
        context.totalVolume = totalVolume.get();
        context.cvd = cvdCalculator.getCVD();
        context.trend = "NEUTRAL";  // Simplified - can be enhanced later
        context.emaAlignment = getEMAAlignmentCount();
        context.isVolatile = isVolatileMarket();
        context.timeOfDay = getCurrentTimeOfDay();

        // Performance stats
        context.totalSignals = icebergCount.get() + spoofCount.get() + absorptionCount.get();
        context.winningSignals = winningTrades.get();  // Use winningTrades instead of todayWinCount
        context.winRate = context.totalSignals > 0 ?
            (double) context.winningSignals / context.totalSignals : 0.0;
        context.recentSignals = getRecentSignalCount(10); // Last 10 minutes
        context.avgScore = getAverageSignalScore();

        return context;
    }

    private void applyAIRecommendations(AIThresholdService.ThresholdRecommendation rec) {
        log("🤖 AI Threshold Recommendations:");
        log("   Min Confluence Score: " + rec.minConfluenceScore);
        log("   Iceberg Min Orders: " + rec.icebergMinOrders);
        log("   Spoof Min Size: " + rec.spoofMinSize);
        log("   Absorption Min Size: " + rec.absorptionMinSize);
        log("   Threshold Multiplier: " + rec.thresholdMultiplier);
        log("   Confidence: " + rec.confidence);
        log("   Reasoning: " + rec.reasoning);

        // Apply recommendations
        icebergMinOrders = rec.icebergMinOrders;
        spoofMinSize = rec.spoofMinSize;
        absorptionMinSize = rec.absorptionMinSize;

        log("✅ AI thresholds applied successfully");

        // Show details dialog with copyable text
        StringBuilder details = new StringBuilder();
        details.append("AI Analysis Complete\n\n");
        details.append("Confidence: ").append(rec.confidence).append("\n\n");
        details.append("New Thresholds:\n");
        details.append("• Min Confluence Score: ").append(rec.minConfluenceScore).append("\n");
        details.append("• Iceberg Min Orders: ").append(rec.icebergMinOrders).append("\n");
        details.append("• Spoof Min Size: ").append(rec.spoofMinSize).append("\n");
        details.append("• Absorption Min Size: ").append(rec.absorptionMinSize).append("\n\n");
        details.append("Reasoning:\n").append(rec.reasoning).append("\n\n");
        details.append("Factors:\n");
        for (String factor : rec.factors) {
            details.append("• ").append(factor).append("\n");
        }

        SwingUtilities.invokeLater(() -> {
            JTextArea textArea = new JTextArea(details.toString());
            textArea.setEditable(false);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(500, 300));
            JOptionPane.showMessageDialog(settingsPanel,
                scrollPane,
                "AI Threshold Recommendations",
                JOptionPane.INFORMATION_MESSAGE);
        });

        lastAIReevaluationTime = System.currentTimeMillis();
    }

    /**
     * Set up the AI Tools Provider with data suppliers
     * This connects real-time trading data to the AI's function calling capability
     */
    private void setupAIToolsProvider() {
        if (aiToolsProvider == null) return;

        // Price supplier - return tick units for staleness check (signal.price is in ticks)
        aiToolsProvider.setPriceSupplier(() -> {
            // lastKnownPrice is already in tick units - return for staleness comparison
            return lastKnownPrice;  // Return as double (tick units)
        });

        // CVD supplier
        aiToolsProvider.setCvdSupplier(() -> cvdCalculator.getCVD());

        // VWAP supplier - convert to actual price
        aiToolsProvider.setVwapSupplier(() ->
            vwapCalculator.isInitialized() ? vwapCalculator.getVWAP() * pips : 0.0);

        // EMA supplier - convert to actual prices
        aiToolsProvider.setEmaSupplier(() -> new double[] {
            ema9.isInitialized() ? ema9.getEMA() * pips : 0.0,
            ema21.isInitialized() ? ema21.getEMA() * pips : 0.0,
            ema50.isInitialized() ? ema50.getEMA() * pips : 0.0
        });

        // DOM (Order Book) supplier - convert prices to actual
        aiToolsProvider.setDomSupplier(() -> {
            Map<String, Object> dom = new HashMap<>();
            var support = domAnalyzer.getNearestSupport();
            var resistance = domAnalyzer.getNearestResistance();
            if (support != null) {
                dom.put("supportPrice", support.price * pips);
                dom.put("supportVolume", support.volume);
            }
            if (resistance != null) {
                dom.put("resistancePrice", resistance.price * pips);
                dom.put("resistanceVolume", resistance.volume);
            }
            dom.put("imbalanceRatio", domAnalyzer.getImbalanceRatio());
            dom.put("imbalanceSentiment", domAnalyzer.getImbalanceSentiment());
            return dom;
        });

        // Recent signals supplier - convert prices to actual
        aiToolsProvider.setSignalsSupplier(() -> {
            List<Map<String, Object>> signals = new ArrayList<>();
            for (var entry : trackedSignals.entrySet()) {
                Map<String, Object> sig = new HashMap<>();
                SignalPerformance perf = entry.getValue();
                sig.put("direction", perf.isBid ? "BUY" : "SELL");
                sig.put("price", perf.entryPrice * pips);
                sig.put("score", perf.score);
                sig.put("time", new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(perf.timestamp)));
                signals.add(sig);
            }
            return signals;
        });

        // Session stats supplier - combines sessionContext and AI order manager stats
        aiToolsProvider.setSessionSupplier(() -> {
            Map<String, Object> stats = new HashMap<>();
            if (sessionContext != null) {
                stats.put("sessionId", sessionContext.getSessionId());
                stats.put("phase", sessionContext.getCurrentPhase().toString());
                stats.put("warmupComplete", sessionContext.isWarmupComplete());
                stats.put("minutesIntoSession", sessionContext.getMinutesIntoSession());
            }

            // Include AI Order Manager stats if available (these are the actual executed trades)
            if (aiOrderManager != null) {
                stats.put("trades", aiOrderManager.getTotalTrades());
                stats.put("wins", aiOrderManager.getWinningTrades());
                stats.put("losses", aiOrderManager.getLosingTrades());
                stats.put("pnl", aiOrderManager.getDailyPnl());
                stats.put("activePositions", aiOrderManager.getActivePositionCount());
                stats.put("winRate", aiOrderManager.getWinRate());
            } else {
                // Fallback to session context stats
                stats.put("trades", sessionContext != null ? sessionContext.getTradesThisSession() : 0);
                stats.put("wins", sessionContext != null ? sessionContext.getWinsThisSession() : 0);
                stats.put("losses", sessionContext != null ? sessionContext.getLossesThisSession() : 0);
                stats.put("pnl", sessionContext != null ? sessionContext.getSessionPnl() : 0.0);
            }
            return stats;
        });

        // Thresholds supplier
        aiToolsProvider.setThresholdsSupplier(() -> {
            Map<String, Integer> thresholds = new HashMap<>();
            thresholds.put("minConfluenceScore", minConfluenceScore);
            thresholds.put("confluenceThreshold", confluenceThreshold);
            thresholds.put("icebergMinOrders", icebergMinOrders);
            thresholds.put("spoofMinSize", spoofMinSize);
            thresholds.put("absorptionMinSize", absorptionMinSize);
            thresholds.put("useAIAdaptive", useAIAdaptiveThresholds ? 1 : 0);
            return thresholds;
        });

        // Performance analytics supplier
        aiToolsProvider.setPerformanceSupplier(() -> {
            Map<String, Object> perf = new HashMap<>();

            // From session context
            if (sessionContext != null) {
                perf.put("winRate", sessionContext.getSessionWinRate() / 100.0);
                perf.put("totalSignals", sessionContext.getTradesThisSession());
            }

            // From tracked signals
            int wins = 0, losses = 0, total = 0;
            double winScoreSum = 0, loseScoreSum = 0, skipScoreSum = 0;
            int takeCount = 0, skipCount = 0;

            for (var entry : trackedSignals.entrySet()) {
                SignalPerformance sig = entry.getValue();
                total++;
                if (sig.profitable != null) {
                    if (sig.profitable) {
                        wins++;
                        winScoreSum += sig.score;
                    } else {
                        losses++;
                        loseScoreSum += sig.score;
                    }
                } else {
                    skipCount++;
                    skipScoreSum += sig.score;
                }
            }

            if (total > 0) {
                perf.put("takeCount", wins + losses);
                perf.put("skipCount", skipCount);
                perf.put("avgWinningScore", wins > 0 ? winScoreSum / wins : 0);
                perf.put("avgLosingScore", losses > 0 ? loseScoreSum / losses : 0);
                perf.put("avgSkipScore", skipCount > 0 ? skipScoreSum / skipCount : 0);

                // Simple correlation estimates
                perf.put("cvdAlignmentCorrelation", 0.3);  // Placeholder
                perf.put("trendAlignmentCorrelation", 0.25);
                perf.put("emaAlignmentCorrelation", 0.2);

                // Time analysis
                perf.put("bestHour", "10:00-11:00");
                perf.put("worstHour", "12:00-13:00");
            }

            return perf;
        });

        // Threshold adjuster callback - allows AI tools to change thresholds
        aiToolsProvider.setThresholdAdjuster((thresholdName, value) -> {
            log("🔧 AI TOOL ADJUSTMENT: " + thresholdName + " → " + value);

            try {
                switch (thresholdName) {
                    case "minConfluenceScore" -> {
                        minConfluenceScore = value;
                        return true;
                    }
                    case "confluenceThreshold" -> {
                        confluenceThreshold = value;
                        return true;
                    }
                    case "icebergMinOrders" -> {
                        icebergMinOrders = value;
                        return true;
                    }
                    case "spoofMinSize" -> {
                        spoofMinSize = value;
                        return true;
                    }
                    case "absorptionMinSize" -> {
                        absorptionMinSize = value;
                        return true;
                    }
                    default -> {
                        log("⚠️ Unknown threshold: " + thresholdName);
                        return false;
                    }
                }
            } catch (Exception e) {
                log("❌ Failed to adjust threshold: " + e.getMessage());
                return false;
            }
        });

        // Notification callback - allows AI to push notifications to user
        aiToolsProvider.setNotificationCallback((category, message, priority) -> {
            log("🔔 AI NOTIFICATION [" + category + "]: " + message);
            notifyAI(message, priority);
        });

        // Weights supplier - provides current confluence weights
        aiToolsProvider.setWeightsSupplier(() -> confluenceWeights.getAll());

        // Weight adjuster callback - allows AI tools to change weights (respects aiManagedWeights setting)
        aiToolsProvider.setWeightAdjuster((weightName, value) -> {
            if (!aiManagedWeights) {
                log("⚖️ AI TOOL WEIGHT ADJUSTMENT SKIPPED (AI Managed Weights disabled): " + weightName + " → " + value);
                return false;
            }
            log("⚖️ AI TOOL WEIGHT ADJUSTMENT: " + weightName + " → " + value);
            try {
                int actualValue = confluenceWeights.set(weightName, value);
                log("   Applied value: " + actualValue + " (clamped to safety bounds)");
                return true;
            } catch (IllegalArgumentException e) {
                log("⚠️ Unknown weight: " + weightName);
                return false;
            } catch (Exception e) {
                log("❌ Failed to adjust weight: " + e.getMessage());
                return false;
            }
        });

        // Trading memory path - allows AI tools to access trade history
        aiToolsProvider.setTradingMemoryPath(tradingMemoryDir);

        // Session context suppliers - allows AI to access today's trading plan
        aiToolsProvider.setSessionContextSupplier(() -> getTodaysSessionContext());
        aiToolsProvider.setCurrentPhaseSupplier(() -> {
            SessionPhaseTracker.SessionPhase phase = getCurrentTradingPhase();
            return phase.getDisplayName() + ": " + phase.getDescription();
        });
    }

    /**
     * Apply threshold adjustments from AI Investment Strategist
     * This allows the AI to dynamically tune thresholds based on market conditions
     */
    private void applyThresholdAdjustment(AIInvestmentStrategist.ThresholdAdjustment adj) {
        log("🎚️ AI THRESHOLD ADJUSTMENT:");
        boolean anyChanged = false;

        if (adj.minConfluenceScore != null && adj.minConfluenceScore != minConfluenceScore) {
            log(String.format("   minConfluenceScore: %d → %d", minConfluenceScore, adj.minConfluenceScore));
            minConfluenceScore = adj.minConfluenceScore;
            anyChanged = true;
        }

        if (adj.confluenceThreshold != null && adj.confluenceThreshold != confluenceThreshold) {
            log(String.format("   confluenceThreshold: %d → %d", confluenceThreshold, adj.confluenceThreshold));
            confluenceThreshold = adj.confluenceThreshold;
            anyChanged = true;
        }

        if (adj.icebergMinOrders != null && adj.icebergMinOrders != icebergMinOrders) {
            log(String.format("   icebergMinOrders: %d → %d", icebergMinOrders, adj.icebergMinOrders));
            icebergMinOrders = adj.icebergMinOrders;
            anyChanged = true;
        }

        if (adj.spoofMinSize != null && adj.spoofMinSize != spoofMinSize) {
            log(String.format("   spoofMinSize: %d → %d", spoofMinSize, adj.spoofMinSize));
            spoofMinSize = adj.spoofMinSize;
            anyChanged = true;
        }

        if (adj.absorptionMinSize != null && adj.absorptionMinSize != absorptionMinSize) {
            log(String.format("   absorptionMinSize: %d → %d", absorptionMinSize, adj.absorptionMinSize));
            absorptionMinSize = adj.absorptionMinSize;
            anyChanged = true;
        }

        if (adj.thresholdMultiplier != null && Math.abs(adj.thresholdMultiplier - thresholdMultiplier) > 0.01) {
            log(String.format("   thresholdMultiplier: %.1f → %.1f", thresholdMultiplier, adj.thresholdMultiplier));
            thresholdMultiplier = adj.thresholdMultiplier;
            anyChanged = true;
        }

        if (anyChanged) {
            log("   Reasoning: " + (adj.reasoning != null ? adj.reasoning : "N/A"));
            log("✅ Thresholds updated by AI");
            saveSettings();  // Persist the changes
            // Update the AI Current column labels in settings panel
            updateAIWeightLabels();
        } else {
            log("   No changes needed (all values same as current)");
        }

        // Apply weight adjustments if any (only if AI managed weights is enabled)
        if (adj.weightAdjustment != null && adj.weightAdjustment.hasAdjustments()) {
            if (aiManagedWeights) {
                log("⚖️ AI WEIGHT ADJUSTMENT:");
                confluenceWeights.applyAdjustments(adj.weightAdjustment);
                log("   " + confluenceWeights.toAIString().replace("\n", " "));
                if (adj.weightAdjustment.reasoning != null) {
                    log("   Reasoning: " + adj.weightAdjustment.reasoning);
                }
                log("✅ Confluence weights updated by AI");
                // Update the AI Current column labels in settings panel
                updateAIWeightLabels();
            } else {
                log("⚖️ AI weight adjustment SKIPPED (AI Managed Weights is disabled)");
            }
        }
    }

    // Helper methods for market context
    private double getCurrentPrice() {
        // Return last known price or 0
        return lastKnownPrice > 0 ? lastKnownPrice : 0.0;
    }

    private int getEMAAlignmentCount() {
        int count = 0;
        double price = getCurrentPrice();

        if (ema9.isInitialized() && price > ema9.getEMA()) count++;
        if (ema21.isInitialized() && price > ema21.getEMA()) count++;
        if (ema50.isInitialized() && price > ema50.getEMA()) count++;

        return count;
    }

    private boolean isVolatileMarket() {
        // Simple volatility check: price movement in last minute
        return priceVolatility > 0.5; // > 0.5% movement = volatile
    }

    private String getCurrentTimeOfDay() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 9 && hour < 12) return "Morning";
        if (hour >= 12 && hour < 15) return "Midday";
        if (hour >= 15 && hour < 18) return "Afternoon";
        return "Evening";
    }

    private int getRecentSignalCount(int minutes) {
        // Count signals in last N minutes
        long cutoffTime = System.currentTimeMillis() - (minutes * 60 * 1000L);
        int count = 0;
        for (SignalPerformance perf : trackedSignals.values()) {
            if (perf.timestamp > cutoffTime) count++;
        }
        return count;
    }

    private double getAverageSignalScore() {
        if (trackedSignals.isEmpty()) return 0.0;

        double total = 0;
        int count = 0;
        for (SignalPerformance perf : trackedSignals.values()) {
            total += perf.score;
            count++;
        }
        return count > 0 ? total / count : 0.0;
    }

    private void applySettings() {
        // Sync all local fields to settings object
        settings.minConfluenceScore = minConfluenceScore;
        settings.thresholdMultiplier = thresholdMultiplier;
        settings.icebergMinOrders = icebergMinOrders;
        settings.spoofMinSize = spoofMinSize;
        settings.absorptionMinSize = absorptionMinSize;
        settings.maxPosition = maxPosition;
        settings.dailyLossLimit = dailyLossLimit;
        settings.simModeOnly = simModeOnly;
        settings.autoExecution = autoExecution;
        settings.useAIAdaptiveThresholds = useAIAdaptiveThresholds;
        settings.enableAITrading = enableAITrading;
        settings.aiMode = aiMode;
        settings.confluenceThreshold = confluenceThreshold;
        settings.aiAuthToken = aiAuthToken;
        // Position sizing settings
        settings.accountSize = accountSize;
        settings.riskPerTradePercent = riskPerTradePercent;
        settings.tickValue = tickValue;
        settings.autoCalculatePositionSize = autoCalculatePositionSize;
        settings.fixedPositionSize = fixedPositionSize;
        settings.stopLossTicks = stopLossTicks;
        settings.takeProfitTicks = takeProfitTicks;
        // Smart SL/TP settings
        settings.useSmartSlTp = useSmartSlTp;
        settings.atrSlMultiplier = atrSlMultiplier;
        settings.atrTpMultiplier = atrTpMultiplier;
        settings.minRRRatio = minRRRatio;
        settings.maxSlTicks = maxSlTicks;
        settings.maxTpTicks = maxTpTicks;
        // DOM quality and memory settings
        settings.minDomVolumeForSlTp = minDomVolumeForSlTp;
        settings.prioritizeDomOverAtr = prioritizeDomOverAtr;
        settings.sessionAwareAtrScaling = sessionAwareAtrScaling;
        settings.useTradeMemoryForSlTp = useTradeMemoryForSlTp;
        settings.memoryLookbackTrades = memoryLookbackTrades;

        saveSettings();
        log("✅ Settings applied and saved");
        JOptionPane.showMessageDialog(settingsPanel,
            "✅ Settings have been applied and saved!",
            "Settings Applied",
            JOptionPane.INFORMATION_MESSAGE);
    }

    // ========== EXPORT FUNCTIONALITY ==========

    private void exportData() {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

        try {
            // Export trades to CSV
            String tradesFile = "trades_" + date + ".csv";
            PrintWriter writer = new PrintWriter(new FileWriter(tradesFile));
            writer.println("Time,Entry,Exit,Duration,PnL,Score,Reason");
            for (Trade trade : tradeHistory) {
                writer.printf("%s,%s,%s,%d min,%.2f,%d,%s%n",
                    trade.entryTime,
                    trade.entryPrice,
                    trade.exitPrice,
                    trade.holdTimeMinutes,
                    trade.pnl,
                    trade.score,
                    trade.reason);
            }
            writer.close();

            // Export performance report
            String reportFile = "performance_" + date + ".txt";
            writer = new PrintWriter(new FileWriter(reportFile));
            writer.println("=== ORDER FLOW STRATEGY PERFORMANCE REPORT ===");
            writer.println("Date: " + date);
            writer.println();
            writer.println("ALL-TIME METRICS:");
            writer.println("  Total Trades: " + totalTrades.get());
            writer.println("  Win Rate: " + String.format("%.1f%%",
                (winningTrades.get() * 100.0 / Math.max(1, totalTrades.get()))));
            writer.println("  Total P&L: $" + String.format("%.2f", totalPnL.get() / 100.0));
            writer.println();
            writer.println("TODAY'S PERFORMANCE:");
            writer.println("  Trades: " + todayTrades.size());
            writer.println("  P&L: $" + String.format("%.2f", todayPnL));
            writer.println("  Max Drawdown: $" + String.format("%.2f", todayMaxDrawdown));
            writer.close();

            // Show copyable export message
            String exportMsg = "✅ Data exported successfully!\n\n" +
                "Trades: " + tradesFile + "\n" +
                "Report: " + reportFile;

            JTextArea textArea = new JTextArea(exportMsg);
            textArea.setEditable(false);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(400, 120));

            JOptionPane.showMessageDialog(statsPanel,
                scrollPane,
                "Export Complete",
                JOptionPane.INFORMATION_MESSAGE);

            log("📥 Data exported: " + tradesFile + ", " + reportFile);

        } catch (IOException e) {
            showAlert("Export Failed",
                "Failed to export data: " + e.getMessage());
        }
    }

    // ========== MBO ORDER TRACKING ==========
    // (Same implementation as OrderFlowMboSimple)

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        OrderInfo info = new OrderInfo();
        info.orderId = orderId;
        info.isBid = isBid;
        info.price = price;
        info.size = size;
        info.timestamp = System.currentTimeMillis();
        orders.put(orderId, info);

        priceLevels.computeIfAbsent(price, k -> new ArrayList<>()).add(orderId);

        totalOrdersSeen++;
        totalSizeSeen += size;

        updateAdaptiveThresholds(price);

        // Only check for icebergs on orders large enough to matter
        // Use a larger percentage to reduce noise
        int minSizeToCheck = Math.max(
            adaptiveSizeThreshold / 2,  // Increased from /5 to /2
            spoofMinSize.intValue()
        );

        if (size >= minSizeToCheck) {
            checkForIceberg(isBid, price);
        }
    }

    public void replace(String orderId, boolean isBid, int price, int size) {
        OrderInfo info = orders.get(orderId);
        if (info != null) {
            info.price = price;
            info.size = size;
        }
    }

    @Override
    public void replace(String orderId, int price, int size) {
        OrderInfo info = orders.get(orderId);
        if (info != null) {
            info.price = price;
            info.size = size;
        }
    }

    @Override
    public void cancel(String orderId) {
        OrderInfo info = orders.remove(orderId);
        if (info != null) {
            List<String> atPrice = priceLevels.get(info.price);
            if (atPrice != null) {
                atPrice.remove(orderId);
            }
        }
    }

    private void updateAdaptiveThresholds(int price) {
        // Update DOM analysis (support/resistance from order book)
        int currentPrice = (int) getCurrentPrice();
        if (currentPrice > 0) {
            domAnalyzer.analyze(priceLevels, orders, currentPrice);
        }

        // Always track statistics for display, even in manual mode
        avgOrderSize = (double) totalSizeSeen / totalOrdersSeen;

        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());
        int totalSize = ordersAtPrice.stream()
            .mapToInt(id -> orders.getOrDefault(id, new OrderInfo()).size)
            .sum();

        recentOrderCounts.add(ordersAtPrice.size());
        recentTotalSizes.add(totalSize);

        if (recentOrderCounts.size() > HISTORY_WINDOW) {
            recentOrderCounts.removeFirst();
            recentTotalSizes.removeFirst();
        }

        // Only calculate adaptive thresholds if AI adaptive mode is enabled
        if (!useAIAdaptiveThresholds) {
            // Manual mode - keep current threshold values, just track stats
            return;
        }

        double avgOrderCount = recentOrderCounts.stream()
            .mapToInt(Integer::intValue).average().orElse(5.0);
        double avgTotalSize = recentTotalSizes.stream()
            .mapToInt(Integer::intValue).average().orElse(25.0);

        // Calculate adaptive thresholds
        adaptiveOrderThreshold = Math.max(
            (int) (avgOrderCount * 2.0),  // Lower multiplier for more signals
            icebergMinOrders.intValue()
        );
        adaptiveSizeThreshold = Math.max(
            (int) (avgTotalSize * 2.0),  // Lower multiplier for more signals
            absorptionMinSize.intValue()
        );

        // Ensure minimums (lowered for better signal detection)
        adaptiveOrderThreshold = Math.max(adaptiveOrderThreshold, 5);
        adaptiveSizeThreshold = Math.max(adaptiveSizeThreshold, 20);
    }

    private void checkForIceberg(boolean isBid, int price) {
        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());

        // Debug: Show order tracking (lowered threshold for visibility)
        if (ordersAtPrice.size() >= 3) {
            log(String.format("🔍 Tracking %d orders at %d (threshold: %d, size threshold: %d)",
                ordersAtPrice.size(), price, adaptiveOrderThreshold, adaptiveSizeThreshold));
        }

        if (ordersAtPrice.size() >= adaptiveOrderThreshold) {
            long now = System.currentTimeMillis();
            Long lastSignalTimeAtPrice = lastIcebergSignalTime.get(price);

            // Check BOTH per-price cooldown AND global signal cooldown
            boolean priceCooldownPassed = lastSignalTimeAtPrice == null || (now - lastSignalTimeAtPrice) >= ICEBERG_COOLDOWN_MS;
            boolean globalCooldownPassed = (now - lastGlobalSignalTime) >= GLOBAL_SIGNAL_COOLDOWN_MS;

            if (priceCooldownPassed && globalCooldownPassed) {
                int totalSize = ordersAtPrice.stream()
                    .mapToInt(id -> orders.getOrDefault(id, new OrderInfo()).size)
                    .sum();

                if (totalSize >= adaptiveSizeThreshold) {
                    lastIcebergSignalTime.put(price, now);
                    lastGlobalSignalTime = now;  // Update global cooldown timer

                    String direction = isBid ? "BUY" : "SELL";
                    String signal = String.format("ICEBERG|%s|%d|%d",
                        direction, price, totalSize);

                    log("🧊 ADAPTIVE SIGNAL: " + signal +
                        String.format(" (isBid=%b, Thresholds: %d orders, %d size, Avg: %.1f orders, %.1f size)",
                            isBid, adaptiveOrderThreshold, adaptiveSizeThreshold,
                            recentOrderCounts.stream().mapToInt(Integer::intValue).average().orElse(0.0),
                            recentTotalSizes.stream().mapToInt(Integer::intValue).average().orElse(0.0)));

                    if (signalWriter != null) {
                        signalWriter.println(signal);
                        signalWriter.flush();
                    }

                    // ========== WARM-UP CHECK ==========
                    // Skip ALL signal processing during warm-up period
                    // Update session context first (use data timestamp for replay mode)
                    if (sessionContext != null) {
                        sessionContext.update(price, currentDataTimestampMs);
                    }

                    boolean warmupComplete = (sessionContext == null || sessionContext.isWarmupComplete());

                    if (!warmupComplete) {
                        log(String.format("⏳ WARMUP: Skipping signal - %s", sessionContext.getWarmupStatus()));
                        return;  // Exit early - no markers, no tracking, no AI
                    }

                    // ========== PRE-FILTERS (check BEFORE adding markers/tracking) ==========
                    boolean sendToAI = true;
                    String skipReason = null;

                    // Calculate score early for filtering
                    int signalScore = calculateConfluenceScore(isBid, price, totalSize);
                    long cvd = cvdCalculator.getCVD();

                    // 1. Minimum score floor filter
                    int scoreFloor = confluenceThreshold - SCORE_FLOOR_OFFSET;
                    if (signalScore < scoreFloor) {
                        sendToAI = false;
                        skipReason = String.format("Score %d < floor %d (threshold %d - %d)",
                            signalScore, scoreFloor, confluenceThreshold, SCORE_FLOOR_OFFSET);
                    }

                    // 2. Strong counter-trend filter
                    boolean isStrongCounterTrend = (isBid && cvd < -CVD_STRONG_THRESHOLD) ||
                                                   (!isBid && cvd > CVD_STRONG_THRESHOLD);
                    if (sendToAI && isStrongCounterTrend) {
                        sendToAI = false;
                        skipReason = String.format("Strong counter-trend (signal=%s, CVD=%d, threshold=±%d)",
                            isBid ? "BUY" : "SELL", cvd, CVD_STRONG_THRESHOLD);
                    }

                    // 3. Signal deduplication filter
                    if (sendToAI) {
                        String signalKey = (isBid ? "BUY@" : "SELL@") + price;
                        Long lastSent = recentSignalsSent.get(signalKey);
                        long currentTime = System.currentTimeMillis();
                        if (lastSent != null && (currentTime - lastSent) < SIGNAL_DEDUP_MS) {
                            sendToAI = false;
                            skipReason = String.format("Duplicate signal %s (%.0fs ago)",
                                signalKey, (currentTime - lastSent) / 1000.0);
                        } else {
                            recentSignalsSent.put(signalKey, currentTime);
                            // Clean up old dedup entries (prevent memory leak)
                            recentSignalsSent.entrySet().removeIf(e -> (currentTime - e.getValue()) > SIGNAL_DEDUP_MS * 2);
                        }
                    }

                    // 4. Max concurrent positions filter
                    if (sendToAI && aiOrderManager != null) {
                        int activePositions = aiOrderManager.getActivePositionCount();
                        if (activePositions >= maxPosition) {
                            sendToAI = false;
                            skipReason = String.format("Max positions reached (%d >= %d)",
                                activePositions, maxPosition);
                        }
                    }

                    // If filtered out, log and exit - NO marker, NO tracking
                    if (!sendToAI) {
                        log("⏭️ PRE-FILTER SKIP: " + skipReason);
                        return;
                    }

                    // ========== SIGNAL PASSED FILTERS - Now add marker and tracking ==========
                    log(String.format("✅ PRE-FILTER PASS: Score=%d >= %d, CVD=%d, sending to AI",
                        signalScore, scoreFloor, cvd));

                    // Add marker point at signal price
                    // Using addIcon() for discrete markers without connecting lines
                    Indicator markerIndicator = isBid ? icebergBuyMarker : icebergSellMarker;

                    // Create custom icon for this signal
                    BufferedImage icon = isBid ? createBuyIcon() : createSellIcon();
                    markerIndicator.addIcon(price, icon, 3, 3);

                    // ========== PERFORMANCE TRACKING ==========
                    SignalPerformance perf = new SignalPerformance();
                    perf.timestamp = now;
                    perf.isBid = isBid;
                    perf.entryPrice = price;
                    perf.score = signalScore;
                    perf.totalSize = totalSize;
                    perf.cvdAtSignal = cvd;
                    perf.trendAtSignal = cvd > 0 ? "BULLISH" : "BEARISH";
                    perf.emaAlignmentAtSignal = ema9.isInitialized() && ema21.isInitialized() && ema50.isInitialized() ? 3 : 0;
                    perf.profitable = null;  // Still open

                    // Build confluence breakdown for analysis
                    perf.confluenceBreakdown = String.format(
                        "Score:%d|Iceberg:%d|CVD:%.0f|Trend:%s|EMA:%d/3|VWAP:%s|VP:%.0f%%",
                        perf.score,
                        Math.min(40, totalSize * 2),
                        perf.cvdAtSignal,
                        perf.trendAtSignal,
                        perf.emaAlignmentAtSignal,
                        vwapCalculator.isInitialized() ? vwapCalculator.getRelationship(price) : "UNKNOWN",
                        volumeProfile.getVolumeNearPrice(price).volumeRatio * 100
                    );

                    trackedSignals.put(price, perf);
                    log(String.format("📊 TRACKING: %s @ %d | Score: %d | %s",
                        isBid ? "BUY" : "SELL", price, perf.score, perf.confluenceBreakdown));

                    if (isBid) {
                        icebergCount.incrementAndGet();
                    } else {
                        icebergCount.incrementAndGet();
                    }

                    // ========== AI TRADING EVALUATION ==========
                    // Warm-up already checked earlier - if we're here, warm-up is complete
                    log(String.format("🤖 AI CHECK: enableAITrading=%s, aiOrderManager=%s, aiStrategist=%s",
                        enableAITrading, aiOrderManager != null ? "ready" : "null", aiStrategist != null ? "ready" : "null"));

                    if (enableAITrading && aiOrderManager != null) {
                        // Guard against overlapping AI evaluations (atomic check-and-set)
                        if (!aiEvaluationInProgress.compareAndSet(false, true)) {
                            log("⏳ AI evaluation already in progress - skipping this signal");
                            return;
                        }
                        log("🔒 AI evaluation lock acquired");

                        try {
                            // Pre-filters already passed above - proceed to AI evaluation

                            // Create SignalData for AI evaluation
                            SignalData signalData = createSignalData(isBid, price, totalSize);

                            // ========== R:R QUALITY LOGGING (NO FILTERING) ==========
                            // We no longer filter out signals with poor R:R.
                            // Instead, we expose the R:R quality assessment to the AI
                            // so it can make informed decisions with full context.
                            // The AI sees: confluence score + R:R quality + order flow + market context

                            if (signalData.rrQuality != null) {
                                log("📊 R:R QUALITY: " + signalData.rrQuality.getSummary());
                                log(String.format("   Signal: %s @ %d (Score: %d, SL=%d, TP=%d)",
                                    signalData.direction, price, signalData.score,
                                    signalData.risk.stopLossTicks, signalData.risk.takeProfitTicks));
                                fileLog("📊 R:R QUALITY: " + signalData.rrQuality.getSummary() +
                                    " | " + signalData.risk.slTpReasoning);
                                // Note: We don't place a marker here - if AI skips due to poor R:R,
                                // that skip will get its own white dot via AIOrderManager
                            }

                            // Log timestamp info for debugging replay mode
                            java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
                            java.time.ZonedDateTime dataTime = java.time.Instant.ofEpochMilli(currentDataTimestampMs).atZone(etZone);
                            log(String.format("⏰ TIMESTAMP DEBUG: dataTs=%d | ET time=%s | phase=%s | replayMin=%d",
                                currentDataTimestampMs, dataTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                                sessionContext != null ? sessionContext.getCurrentPhase() : "null",
                                sessionContext != null ? sessionContext.getMinutesIntoSession() : -1));

                            // Use AI Investment Strategist (memory-aware) if available
                            if (aiStrategist != null) {
                                log("🧠 Using AI Investment Strategist (memory-aware evaluation)");
                            aiStrategist.evaluateSetup(signalData, sessionContext, devMode, new AIInvestmentStrategist.AIStrategistCallback() {
                            @Override
                            public void onDecision(AIInvestmentStrategist.AIDecision decision) {
                                // UNCONDITIONAL LOG - always log callback entry for debugging
                                log("🔔 ========== CALLBACK ENTERED ==========");
                                log("🔔 devMode=%s, decision=%s".formatted(devMode, decision != null ? "NOT NULL" : "NULL"));
                                debug("CALLBACK ENTERED: onDecision() called");
                                debug("decision object: %s".formatted(decision != null ? "NOT NULL" : "NULL"));

                                // Handle threshold adjustments from AI
                                if (decision.thresholdAdjustment != null && decision.thresholdAdjustment.hasAdjustments()) {
                                    applyThresholdAdjustment(decision.thresholdAdjustment);
                                }

                                // Execute AI decision
                                debug("AI DECISION: shouldTake=%s, plan=%s, confidence=%.0f%%".formatted(
                                    decision.shouldTake, decision.plan != null ? "PRESENT" : "NULL", decision.confidence * 100));

                                // CRITICAL CHECK: shouldTake && plan != null
                                boolean willExecute = decision.shouldTake && decision.plan != null;
                                // UNCONDITIONAL LOG for debugging
                                log("🔔 EXECUTION CHECK: shouldTake=%s && plan=%s → willExecute=%s".formatted(
                                    decision.shouldTake, decision.plan != null, willExecute));
                                debug("EXECUTION CHECK: shouldTake=%s && plan=%s → willExecute=%s".formatted(
                                    decision.shouldTake, decision.plan != null, willExecute));

                                if (willExecute) {
                                    log(String.format("✅ AI TAKE: %s (confidence: %.0f%%) - %s",
                                        decision.plan.orderType, decision.confidence * 100, decision.reasoning));

                                    // Validate SL/TP prices from plan
                                    int stopLossPrice = decision.plan.stopLossPrice;
                                    int takeProfitPrice = decision.plan.takeProfitPrice;
                                    boolean isLong = decision.plan.orderType.contains("BUY");

                                    // DETECT if AI returned actual prices instead of tick units
                                    // If SL/TP is much smaller than signal price (e.g., 6819 vs 27253),
                                    // AI probably returned actual prices - convert to tick units
                                    if (stopLossPrice > 0 && stopLossPrice < signalData.price * 0.5) {
                                        int originalSl = stopLossPrice;
                                        stopLossPrice = (int)(stopLossPrice / pips);
                                        log("⚠️ SL converted from actual price " + originalSl + " to ticks " + stopLossPrice + " (pips=" + pips + ")");
                                    }
                                    if (takeProfitPrice > 0 && takeProfitPrice < signalData.price * 0.5) {
                                        int originalTp = takeProfitPrice;
                                        takeProfitPrice = (int)(takeProfitPrice / pips);
                                        log("⚠️ TP converted from actual price " + originalTp + " to ticks " + takeProfitPrice + " (pips=" + pips + ")");
                                    }

                                    // Fallback to signal-based calculation if plan prices are 0
                                    if (stopLossPrice == 0) {
                                        stopLossPrice = isLong ?
                                            signalData.price - 30 :  // 30 ticks
                                            signalData.price + 30;
                                        log("⚠️ SL was 0, calculated fallback: " + stopLossPrice);
                                    }
                                    if (takeProfitPrice == 0) {
                                        takeProfitPrice = isLong ?
                                                signalData.price + 70 :  // 70 ticks
                                                signalData.price - 70;
                                            log("⚠️ TP was 0, calculated fallback: " + takeProfitPrice);
                                        }

                                        log(String.format("📊 FINAL SL/TP: SL=%d TP=%d", stopLossPrice, takeProfitPrice));

                                        // Convert to AIIntegrationLayer.AIDecision format for order manager
                                        final AIIntegrationLayer.AIDecision aiDecision = new AIIntegrationLayer.AIDecision();
                                        aiDecision.action = "TAKE";
                                        aiDecision.confidence = (int)(decision.confidence * 100);  // Convert 0-1 to 0-100
                                        aiDecision.reasoning = decision.reasoning;
                                        aiDecision.isLong = isLong;
                                        aiDecision.direction = isLong ? "LONG" : "SHORT";
                                        aiDecision.stopLoss = stopLossPrice;
                                        aiDecision.takeProfit = takeProfitPrice;

                                        // Check mode: SEMI_AUTO requires approval, FULL_AUTO executes immediately
                                        if ("SEMI_AUTO".equals(aiMode)) {
                                            log("🔒 SEMI_AUTO mode - requesting user approval");
                                            final int finalStopLoss = stopLossPrice;
                                            final int finalTakeProfit = takeProfitPrice;
                                            requestTradeApproval(
                                                isLong,
                                                1,  // quantity
                                                0,  // market order
                                                decision.plan.orderType != null ? decision.plan.orderType : "AI",
                                                (int)(decision.confidence * 100),
                                                () -> {
                                                    // Approved - execute the trade
                                                    SwingUtilities.invokeLater(() -> {
                                                        log("✅ User APPROVED - executing trade");
                                                        // Track entry in session context
                                                        if (sessionContext != null) {
                                                            sessionContext.recordEntryAttempt();
                                                        }
                                                        aiOrderManager.executeEntry(aiDecision, signalData);
                                                    });
                                                },
                                                () -> {
                                                    // Rejected
                                                    SwingUtilities.invokeLater(() -> {
                                                        log("❌ User REJECTED - skipping trade");
                                                        aiOrderManager.executeSkip(aiDecision, signalData);
                                                    });
                                                }
                                            );
                                        } else {
                                            // FULL_AUTO - execute immediately
                                            log("🔔 FULL_AUTO mode - calling executeEntry now!");
                                            // Track entry in session context
                                            if (sessionContext != null) {
                                                sessionContext.recordEntryAttempt();
                                            }
                                            log("🔔 aiOrderManager=%s, signalData=%s".formatted(
                                                aiOrderManager != null ? "NOT NULL" : "NULL",
                                                signalData != null ? "NOT NULL" : "NULL"));
                                            aiOrderManager.executeEntry(aiDecision, signalData);
                                            log("🔔 executeEntry() returned");
                                        }
                                    } else {
                                        log(String.format("⏭️ AI SKIP: %s (confidence: %.0f%%)",
                                            decision.reasoning, decision.confidence * 100));
                                        AIIntegrationLayer.AIDecision aiDecision = new AIIntegrationLayer.AIDecision();
                                        aiDecision.action = "SKIP";
                                        aiDecision.confidence = (int)(decision.confidence * 100);
                                        aiDecision.reasoning = decision.reasoning;
                                        aiOrderManager.executeSkip(aiDecision, signalData);
                                    }
                                    // Release the AI evaluation lock
                                    aiEvaluationInProgress.set(false);
                                    log("🔓 AI evaluation lock released");
                                }

                                @Override
                                public void onError(String error) {
                                    log("❌ AI API ERROR: " + error);
                                    // Release the AI evaluation lock on error
                                    aiEvaluationInProgress.set(false);
                                    log("🔓 AI evaluation lock released (error)");
                                }
                            });
                        } else if (aiIntegration != null) {
                            // Fall back to basic AI integration
                            log("🤖 Using AI Integration Layer (basic evaluation)");
                            aiIntegration.evaluateSignalAsync(signalData)
                                .thenAccept(decision -> {
                                    // Execute AI decision
                                    if ("TAKE".equals(decision.action)) {
                                        // Check mode: SEMI_AUTO requires approval
                                        if ("SEMI_AUTO".equals(aiMode)) {
                                            log("🔒 SEMI_AUTO mode - requesting user approval");
                                            requestTradeApproval(
                                                decision.isLong,
                                                1,  // quantity
                                                0,  // market order
                                                "AI",
                                                decision.confidence,
                                                () -> {
                                                    SwingUtilities.invokeLater(() -> {
                                                        log("✅ User APPROVED - executing trade");
                                                        aiOrderManager.executeEntry(decision, signalData);
                                                    });
                                                },
                                                () -> {
                                                    SwingUtilities.invokeLater(() -> {
                                                        log("❌ User REJECTED - skipping trade");
                                                        aiOrderManager.executeSkip(decision, signalData);
                                                    });
                                                }
                                            );
                                        } else {
                                            // FULL_AUTO - execute immediately
                                            aiOrderManager.executeEntry(decision, signalData);
                                        }
                                    } else {
                                        aiOrderManager.executeSkip(decision, signalData);
                                    }
                                    // Release the AI evaluation lock
                                    aiEvaluationInProgress.set(false);
                                    log("🔓 AI evaluation lock released");
                                })
                                .exceptionally(ex -> {
                                    log("❌ AI evaluation failed: " + ex.getMessage());
                                    // Release the AI evaluation lock on error
                                    aiEvaluationInProgress.set(false);
                                    log("🔓 AI evaluation lock released (error)");
                                    return null;
                                });
                        }
                        } catch (Exception e) {
                            // Release lock if exception occurs before async call starts
                            aiEvaluationInProgress.set(false);
                            log("❌ AI evaluation setup failed: " + e.getMessage());
                            log("🔓 AI evaluation lock released (exception)");
                        }
                    }  // end if (enableAITrading)
                    // ============================================

                    lastSignal = Integer.valueOf(totalSize);  // Store size as last signal
                    activeSignals.add(Integer.valueOf(totalSize));
                }
            }
        }
    }

    /**
     * Smart SL/TP calculation result
     */
    private static class SmartSlTpResult {
        int slTicks;
        int tpTicks;
        int slPrice;
        int tpPrice;
        double rrRatio;
        boolean isGoodRR;
        String reasoning;

        // ========== R:R QUALITY FIELDS ==========
        // Instead of rejection, we capture quality details for AI decision-making
        String slSource;              // "ATR", "DOM_SUPPORT", "MEMORY", "FIXED"
        String tpSource;              // "ATR", "DOM_RESISTANCE", "MEMORY", "FIXED"
        String issueType;             // "NONE", "WIDE_SL", "NEARBY_TARGET", "HIGH_VOLATILITY", "NO_DOM_LEVEL"
        String issueDetails;          // Human-readable explanation
        boolean canBeImproved;        // Is there a way to get better R:R?
        int suggestedSlTicks;         // Alternative SL (if available)
        int suggestedTpTicks;         // Alternative TP (if available)

        SmartSlTpResult(int slTicks, int tpTicks, int entryPrice, boolean isLong) {
            this.slTicks = slTicks;
            this.tpTicks = tpTicks;
            this.slPrice = isLong ? entryPrice - slTicks : entryPrice + slTicks;
            this.tpPrice = isLong ? entryPrice + tpTicks : entryPrice - tpTicks;
            this.rrRatio = slTicks > 0 ? (double) tpTicks / slTicks : 0;
            this.isGoodRR = rrRatio >= 1.0;

            // Default values for R:R quality fields
            this.slSource = "FIXED";
            this.tpSource = "FIXED";
            this.issueType = "NONE";
            this.issueDetails = "";
            this.canBeImproved = false;
            this.suggestedSlTicks = 0;
            this.suggestedTpTicks = 0;
        }
    }

    /**
     * Calculate smart SL/TP based on ATR, DOM levels, memory, and R:R requirements
     *
     * Priority: Memory (trade history) > DOM levels > ATR > Fixed ticks
     *
     * @param isBid true for LONG, false for SHORT
     * @param entryPrice entry price in ticks
     * @return SmartSlTpResult with calculated SL/TP and R:R analysis
     */
    private SmartSlTpResult calculateSmartSlTp(boolean isBid, int entryPrice) {
        int slTicks;
        int tpTicks;
        StringBuilder reasoning = new StringBuilder();

        // ========== 1. GET ATR (with session-aware scaling) ==========
        double atr = 0;
        int atrTicks = 0;
        double atrSlMult = atrSlMultiplier;
        double atrTpMult = atrTpMultiplier;

        if (atrCalculator.isInitialized()) {
            atr = atrCalculator.getATR();
            atrTicks = (int) Math.round(atr / pips);

            // Session-aware ATR scaling
            if (sessionAwareAtrScaling && sessionContext != null) {
                SessionContext.SessionPhase phase = sessionContext.getCurrentPhase();
                if (phase == SessionContext.SessionPhase.OPENING_BELL) {
                    // Opening range (first 30 min): higher volatility, wider stops
                    atrSlMult *= 1.3;  // 30% wider SL
                    atrTpMult *= 1.2;  // 20% wider TP
                    reasoning.append("OPENING(scaled)");
                } else if (phase == SessionContext.SessionPhase.LUNCH_HOUR) {
                    // Lunch lull: lower volatility, tighter stops
                    atrSlMult *= 0.8;  // 20% tighter SL
                    atrTpMult *= 0.8;  // 20% tighter TP
                    reasoning.append("LUNCH(scaled)");
                } else if (phase == SessionContext.SessionPhase.CLOSING_BELL) {
                    // Close: higher volatility
                    atrSlMult *= 1.2;
                    atrTpMult *= 1.3;
                    reasoning.append("CLOSE(scaled)");
                } else {
                    reasoning.append("RTH");
                }
            }
            reasoning.append(String.format(" ATR=%.2f (%d ticks)", atr, atrTicks));
        }

        // ========== 2. GET DOM SUPPORT/RESISTANCE (with quality check) ==========
        DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
        DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();

        int supportDistance = 0;
        int resistanceDistance = 0;
        long supportVolume = 0;
        long resistanceVolume = 0;

        if (support != null && support.volume >= minDomVolumeForSlTp) {
            supportDistance = Math.abs(entryPrice - support.price);
            supportVolume = support.volume;
            reasoning.append(String.format(", Support@%d (%d ticks, vol=%d)", support.price, supportDistance, supportVolume));
        } else if (support != null) {
            reasoning.append(String.format(", Support@%d (vol=%d < %d, IGNORED)", support.price, support.volume, minDomVolumeForSlTp));
        }

        if (resistance != null && resistance.volume >= minDomVolumeForSlTp) {
            resistanceDistance = Math.abs(entryPrice - resistance.price);
            resistanceVolume = resistance.volume;
            reasoning.append(String.format(", Resistance@%d (%d ticks, vol=%d)", resistance.price, resistanceDistance, resistanceVolume));
        } else if (resistance != null) {
            reasoning.append(String.format(", Resistance@%d (vol=%d < %d, IGNORED)", resistance.price, resistance.volume, minDomVolumeForSlTp));
        }

        // ========== 3. GET MEMORY-DRIVEN LEVELS (from trade history) ==========
        int memorySlDistance = 0;
        int memoryTpDistance = 0;
        String memoryInfo = "";

        if (useTradeMemoryForSlTp && tradeLogger != null) {
            try {
                // Analyze recent trades for reversal levels
                var recentTrades = tradeLogger.getRecentTrades(memoryLookbackTrades);
                if (recentTrades != null && !recentTrades.isEmpty()) {
                    // Find where price reversed (MFE/MAE levels)
                    int avgMfe = 0;
                    int avgMae = 0;
                    int count = 0;

                    for (var trade : recentTrades) {
                        if (trade.mfeTicks > 0) {
                            avgMfe += trade.mfeTicks;
                            avgMae += trade.maeTicks;
                            count++;
                        }
                    }

                    if (count > 0) {
                        avgMfe /= count;
                        avgMae /= count;
                        // Use average favorable excursion as TP hint
                        // Use average adverse excursion as SL hint
                        memoryTpDistance = (int) (avgMfe * 0.8);  // 80% of average MFE
                        memorySlDistance = (int) (avgMae * 1.2);  // 120% of average MAE (be safer)
                        memoryInfo = String.format(", Memory(MFE=%d, MAE=%d)", avgMfe, avgMae);
                        reasoning.append(memoryInfo);
                    }
                }
            } catch (Exception e) {
                // Ignore memory errors, fall back to other methods
            }
        }

        // ========== CALCULATE STOP LOSS ==========
        // Declare variables at method level for use in R:R quality assessment
        int atrSl = 0;
        int domSl = 0;
        int atrTp = 0;
        int domTp = 0;

        if (useSmartSlTp) {
            atrSl = (int) Math.round(atrTicks * atrSlMult);
            domSl = 0;

            if (isBid) {
                // LONG: SL below entry, look for support below
                domSl = supportDistance > 0 ? supportDistance + 2 : 0;
            } else {
                // SHORT: SL above entry, look for resistance above
                domSl = resistanceDistance > 0 ? resistanceDistance + 2 : 0;
            }

            // Collect all valid SL candidates
            java.util.List<Integer> slCandidates = new java.util.ArrayList<>();
            slCandidates.add(stopLossTicks);  // Always include fixed
            if (atrSl > 0) slCandidates.add(atrSl);
            if (domSl > 0) slCandidates.add(domSl);
            if (memorySlDistance > 0) slCandidates.add(memorySlDistance);

            // Priority logic for SL selection
            if (prioritizeDomOverAtr && domSl > 0 && supportVolume >= minDomVolumeForSlTp) {
                // DOM priority: use DOM level if it's significant
                slTicks = Math.max(stopLossTicks, domSl);
                reasoning.append(String.format(", SL: DOM-priority=%d", slTicks));
            } else {
                // Default: use widest (safest) SL
                slTicks = slCandidates.stream().max(Integer::compare).orElse(stopLossTicks);
                reasoning.append(String.format(", SL: max-of-all=%d", slTicks));
            }

            // Cap at maximum
            slTicks = Math.min(slTicks, maxSlTicks);
        } else {
            slTicks = stopLossTicks;
            reasoning.append(String.format(", Fixed SL=%d", slTicks));
        }

        // ========== CALCULATE TAKE PROFIT ==========
        if (useSmartSlTp) {
            atrTp = (int) Math.round(atrTicks * atrTpMult);
            domTp = 0;

            if (isBid) {
                // LONG: TP above entry, look for resistance above
                domTp = resistanceDistance > 0 ? Math.max(0, resistanceDistance - 2) : 0;
            } else {
                // SHORT: TP below entry, look for support below
                domTp = supportDistance > 0 ? Math.max(0, supportDistance - 2) : 0;
            }

            // Collect all valid TP candidates
            java.util.List<Integer> tpCandidates = new java.util.ArrayList<>();
            tpCandidates.add(takeProfitTicks);  // Always include fixed
            if (atrTp > 0) tpCandidates.add(atrTp);
            if (domTp > 0 && domTp >= slTicks) tpCandidates.add(domTp);  // DOM TP must be >= SL
            if (memoryTpDistance > 0 && memoryTpDistance >= slTicks) tpCandidates.add(memoryTpDistance);

            // Priority logic for TP selection
            if (prioritizeDomOverAtr && domTp > 0 && domTp >= slTicks && resistanceVolume >= minDomVolumeForSlTp) {
                // DOM priority: target the DOM level if it gives decent R:R
                double domRR = (double) domTp / slTicks;
                if (domRR >= minRRRatio * 0.8) {  // Allow 20% tolerance for DOM levels
                    tpTicks = domTp;
                    reasoning.append(String.format(", TP: DOM-priority=%d (R:R 1:%.1f)", domTp, domRR));
                } else {
                    tpTicks = Math.max(takeProfitTicks, atrTp);
                    reasoning.append(String.format(", TP: ATR=%d (DOM=%d, R:R too low)", atrTp, domTp));
                }
            } else {
                // Default: use closest valid TP (but ensure minimum R:R)
                final int finalSlTicks = slTicks;  // For lambda
                final double minTpRequired = finalSlTicks * minRRRatio * 0.8;
                tpTicks = tpCandidates.stream()
                    .filter(tp -> tp >= minTpRequired)  // Allow 20% tolerance
                    .min(Integer::compare)
                    .orElse(atrTp > 0 ? atrTp : takeProfitTicks);
                reasoning.append(String.format(", TP: closest-valid=%d", tpTicks));
            }

            // Ensure minimum R:R ratio
            double actualRR = (double) tpTicks / slTicks;
            if (actualRR < minRRRatio) {
                // Adjust TP to meet minimum R:R
                tpTicks = (int) Math.ceil(slTicks * minRRRatio);
                reasoning.append(String.format(", adjusted to min-RR=%d", tpTicks));
            }

            // Cap at maximum
            tpTicks = Math.min(tpTicks, maxTpTicks);
        } else {
            tpTicks = takeProfitTicks;
            reasoning.append(String.format(", Fixed TP=%d", tpTicks));
        }

        // Create result
        SmartSlTpResult result = new SmartSlTpResult(slTicks, tpTicks, entryPrice, isBid);
        result.reasoning = reasoning.toString();

        // ========== R:R QUALITY ASSESSMENT ==========
        // Instead of rejecting, we capture quality details for AI decision-making

        // Track SL/TP sources (set based on what was actually used)
        if (useSmartSlTp) {
            // Determine SL source
            if (memorySlDistance > 0 && memorySlDistance <= slTicks + 2) {
                result.slSource = "MEMORY";
            } else if (domSl > 0 && supportVolume >= minDomVolumeForSlTp) {
                result.slSource = "DOM_SUPPORT";
            } else if (atrSl > 0) {
                result.slSource = "ATR";
            } else {
                result.slSource = "FIXED";
            }

            // Determine TP source
            if (prioritizeDomOverAtr && domTp > 0 && domTp >= slTicks && resistanceVolume >= minDomVolumeForSlTp) {
                result.tpSource = "DOM_RESISTANCE";
            } else if (memoryTpDistance > 0 && memoryTpDistance >= slTicks) {
                result.tpSource = "MEMORY";
            } else if (atrTp > 0) {
                result.tpSource = "ATR";
            } else {
                result.tpSource = "FIXED";
            }
        }

        // Identify issues and potential improvements
        if (result.rrRatio < minRRRatio) {
            result.issueType = "POOR_RR";
            result.issueDetails = String.format(
                "R:R ratio 1:%.1f is below minimum 1:%.1f (SL=%d, TP=%d)",
                result.rrRatio, minRRRatio, slTicks, tpTicks);

            // Check if we can improve by adjusting SL/TP
            // Suggest tighter SL if DOM level is closer
            if (domSl > 0 && domSl < slTicks && supportVolume >= minDomVolumeForSlTp) {
                result.canBeImproved = true;
                result.suggestedSlTicks = domSl;
                result.suggestedTpTicks = tpTicks;  // Keep same TP
            }
            // Suggest wider TP if there's room
            else if (domTp > tpTicks && resistanceVolume >= minDomVolumeForSlTp) {
                result.canBeImproved = true;
                result.suggestedSlTicks = slTicks;  // Keep same SL
                result.suggestedTpTicks = domTp;
            }
        } else if (slTicks > maxSlTicks) {
            result.issueType = "WIDE_SL";
            result.issueDetails = String.format(
                "Stop loss %d ticks exceeds maximum %d (high volatility)",
                slTicks, maxSlTicks);

            // Can improve if there's a closer DOM level
            if (domSl > 0 && domSl <= maxSlTicks && supportVolume >= minDomVolumeForSlTp) {
                result.canBeImproved = true;
                result.suggestedSlTicks = domSl;
                result.suggestedTpTicks = tpTicks;
            }
        } else {
            result.isGoodRR = true;
            result.issueType = "NONE";
            result.issueDetails = "R:R meets requirements";
        }

        return result;
    }

    /**
     * Create SignalData for AI evaluation
     */
    private SignalData createSignalData(boolean isBid, int price, int totalSize) {
        SignalData signal = new SignalData();

        // Basic signal info
        signal.direction = isBid ? "LONG" : "SHORT";
        signal.price = price;
        signal.pips = pips;
        signal.timestamp = currentDataTimestampMs;  // Use Bookmap's data timestamp (replay-safe)

        // Debug: Log signal creation with price and time info
        java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
        java.time.ZonedDateTime dataTime = java.time.Instant.ofEpochMilli(currentDataTimestampMs).atZone(etZone);
        fileLog("📊 createSignalData: price=" + price + " ticks, pips=" + pips + ", time=" + dataTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " ET (dataTs=" + currentDataTimestampMs + ")");

        // Debug: Check if pips is 0
        if (pips == 0) {
            log("⚠️ WARNING: pips is 0! Price display will be incorrect. Check initialize() was called.");
            log("⚠️ Signal price (ticks): " + price + ", pips: " + pips);
        } else {
            log("📊 Signal created: price=" + price + " ticks, pips=" + pips + ", actual price=" + (price * pips));
        }

        // ========== SCORE BREAKDOWN (Enhanced) ==========
        signal.scoreBreakdown = new SignalData.ScoreBreakdown();

        // Iceberg detection
        signal.scoreBreakdown.icebergPoints = Math.min(40, totalSize * 2);
        signal.scoreBreakdown.icebergDetails = String.format("%d iceberg orders detected", totalSize);
        signal.scoreBreakdown.icebergCount = totalSize;
        signal.scoreBreakdown.totalSize = totalSize;

        // ========== CVD ANALYSIS ==========
        long cvd = cvdCalculator.getCVD();
        long cvdAtPrice = cvdCalculator.getCVDAtPrice(price);
        double cvdStrength = cvdCalculator.getCVDStrength();
        String cvdTrend = cvdCalculator.getCVDTrend();
        double cvdBuySellRatio = cvdCalculator.getBuySellRatio();
        CVDCalculator.DivergenceType cvdDivergence = cvdCalculator.checkDivergence(price, 20);

        // CVD Confluence Scoring
        int cvdPoints = 0;
        String cvdDetails = "";

        if ((cvd > 0 && isBid) || (cvd < 0 && !isBid)) {
            // CVD confirms signal direction
            cvdPoints += 15;
            cvdDetails = String.format("CVD confirms direction (%s, strength: %.1f%%)",
                                       cvdTrend, cvdStrength);
        } else if (Math.abs(cvd) > 5000) {
            // Extreme CVD = potential reversal
            cvdPoints -= 10;
            cvdDetails = String.format("CVD extreme at %d (potential reversal)", cvd);
        } else {
            cvdDetails = String.format("CVD neutral (%s, %d)", cvdTrend, cvd);
        }

        // CVD divergence bonus/penalty
        int cvdDivergencePoints = 0;
        if (cvdDivergence == CVDCalculator.DivergenceType.BULLISH && isBid) {
            cvdDivergencePoints = 10;
            cvdDetails += " + bullish divergence";
        } else if (cvdDivergence == CVDCalculator.DivergenceType.BEARISH && !isBid) {
            cvdDivergencePoints = 10;
            cvdDetails += " + bearish divergence";
        }

        signal.scoreBreakdown.cvdPoints = cvdPoints;
        signal.scoreBreakdown.cvdDetails = cvdDetails;
        signal.scoreBreakdown.cvdDivergencePoints = cvdDivergencePoints;

        // ========== VOLUME PROFILE ANALYSIS ==========
        VolumeProfileCalculator.VolumeArea volumeArea = volumeProfile.getVolumeNearPrice(price);
        VolumeProfileCalculator.ValueArea valueArea = volumeProfile.getValueArea();
        int poc = volumeProfile.getPOC();
        VolumeProfileCalculator.VolumeImbalance imbalance = volumeProfile.getImbalance(price);

        // Volume Profile Confluence Scoring
        int volumeProfilePoints = 0;
        String volumeProfileDetails = "";

        if (volumeArea.volumeRatio > 0.3) {
            // High volume node (POC level)
            volumeProfilePoints = 20;
            volumeProfileDetails = String.format("High-volume node (%.0f%% of nearby volume: %d/%d)",
                                                 volumeArea.volumeRatio * 100,
                                                 volumeArea.volumeAtPrice,
                                                 volumeArea.totalNearby);
        } else if (volumeArea.volumeRatio < 0.05) {
            // Low volume zone - could move fast
            volumeProfilePoints = 5;
            volumeProfileDetails = String.format("Low-volume zone (%.0f%%: %d nearby)",
                                                 volumeArea.volumeRatio * 100,
                                                 volumeArea.totalNearby);
        } else {
            volumeProfileDetails = String.format("Normal volume (%.0f%%: %d)",
                                                 volumeArea.volumeRatio * 100,
                                                 volumeArea.volumeAtPrice);
        }

        signal.scoreBreakdown.volumeProfilePoints = volumeProfilePoints;
        signal.scoreBreakdown.volumeProfileDetails = volumeProfileDetails;

        // ========== VOLUME IMBALANCE ANALYSIS ==========
        int volumeImbalancePoints = 0;
        String volumeImbalanceDetails = "";

        if ("STRONG_BUYING".equals(imbalance.sentiment) && isBid) {
            volumeImbalancePoints = 10;
            volumeImbalanceDetails = String.format("Strong buying pressure (ratio: %.2f:1)", imbalance.ratio);
        } else if ("STRONG_SELLING".equals(imbalance.sentiment) && !isBid) {
            volumeImbalancePoints = 10;
            volumeImbalanceDetails = String.format("Strong selling pressure (ratio: %.2f:1)", imbalance.ratio);
        } else if ("BUYING".equals(imbalance.sentiment)) {
            volumeImbalancePoints = 5;
            volumeImbalanceDetails = "Moderate buying pressure";
        } else if ("SELLING".equals(imbalance.sentiment)) {
            volumeImbalancePoints = 5;
            volumeImbalanceDetails = "Moderate selling pressure";
        } else {
            volumeImbalanceDetails = "Balanced order flow";
        }

        signal.scoreBreakdown.volumeImbalancePoints = volumeImbalancePoints;
        signal.scoreBreakdown.volumeImbalanceDetails = volumeImbalanceDetails;

        // ========== EMA TREND ANALYSIS ==========
        double ema9Val = ema9.isInitialized() ? ema9.getEMA() : Double.NaN;
        double ema21Val = ema21.isInitialized() ? ema21.getEMA() : Double.NaN;
        double ema50Val = ema50.isInitialized() ? ema50.getEMA() : Double.NaN;

        // Count how many EMAs confirm the direction
        int emaAlignmentCount = 0;
        String emaTrendDetails = "";

        if (!Double.isNaN(ema9Val)) {
            if (isBid && price > ema9Val) emaAlignmentCount++;
            if (!isBid && price < ema9Val) emaAlignmentCount++;
        }
        if (!Double.isNaN(ema21Val)) {
            if (isBid && price > ema21Val) emaAlignmentCount++;
            if (!isBid && price < ema21Val) emaAlignmentCount++;
        }
        if (!Double.isNaN(ema50Val)) {
            if (isBid && price > ema50Val) emaAlignmentCount++;
            if (!isBid && price < ema50Val) emaAlignmentCount++;
        }

        // EMA Trend Strength
        String trendStrength = "WEAK";
        if (emaAlignmentCount == 3) {
            trendStrength = "STRONG";
        } else if (emaAlignmentCount == 2) {
            trendStrength = "MODERATE";
        }

        emaTrendDetails = String.format("EMA alignment: %d/3 (%s)", emaAlignmentCount, trendStrength);

        int emaTrendPoints = 0;
        if (emaAlignmentCount == 3) {
            emaTrendPoints = 15;
        } else if (emaAlignmentCount == 2) {
            emaTrendPoints = 10;
        } else if (emaAlignmentCount == 1) {
            emaTrendPoints = 5;
        }

        signal.scoreBreakdown.trendPoints = emaTrendPoints;
        signal.scoreBreakdown.trendDetails = emaTrendDetails;
        signal.scoreBreakdown.emaTrendPoints = emaTrendPoints;
        signal.scoreBreakdown.emaTrendDetails = emaTrendDetails;
        signal.scoreBreakdown.emaAlignmentCount = emaAlignmentCount;

        // ========== VWAP ANALYSIS ==========
        int vwapPoints = 0;
        String vwapDetails = "";

        if (vwapCalculator.isInitialized()) {
            double vwap = vwapCalculator.getVWAP();
            String vwapRel = vwapCalculator.getRelationship(price);
            double vwapDist = vwapCalculator.getDistance(price, pips);

            if ((isBid && "ABOVE".equals(vwapRel)) || (!isBid && "BELOW".equals(vwapRel))) {
                vwapPoints = 10;
                vwapDetails = String.format("Price %s VWAP (%.1f ticks)",
                                           vwapRel.toLowerCase(), Math.abs(vwapDist));
            } else {
                vwapDetails = String.format("Price %s VWAP (%.1f ticks)",
                                           vwapRel.toLowerCase(), Math.abs(vwapDist));
            }
        }

        signal.scoreBreakdown.vwapPoints = vwapPoints;
        signal.scoreBreakdown.vwapDetails = vwapDetails;

        // ========== DETECTION DETAILS ==========
        signal.detection = new SignalData.DetectionDetails();
        signal.detection.type = isBid ? "ICEBERG_BUY" : "ICEBERG_SELL";
        signal.detection.totalOrders = totalSize;
        signal.detection.totalSize = totalSize * 10;
        signal.detection.patternsFound = new String[]{"ICEBERG"};

        // ========== MARKET CONTEXT (Enhanced) ==========
        signal.market = new SignalData.MarketContext();
        signal.market.symbol = alias;
        // Use Bookmap's data timestamp for replay mode support (reuse variables from above)
        signal.market.timeOfDay = dataTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        signal.market.currentPrice = price;
        signal.market.spreadTicks = 1;

        // CVD data
        signal.market.cvd = cvd;
        signal.market.cvdAtSignalPrice = cvdAtPrice;
        signal.market.cvdTrend = cvdTrend;
        signal.market.cvdStrength = cvdStrength;
        signal.market.cvdDivergence = cvdDivergence.toString();
        signal.market.cvdBuySellRatio = cvdBuySellRatio;

        // Volume Profile data
        signal.market.volumeAtSignalPrice = volumeArea.volumeAtPrice;
        signal.market.volumeNearby = volumeArea.totalNearby;
        signal.market.volumeRatioAtPrice = volumeArea.volumeRatio;
        signal.market.volumeLevelType = volumeArea.getLevelType();
        signal.market.pocPrice = poc;
        signal.market.valueAreaLow = valueArea.vaLow;
        signal.market.valueAreaHigh = valueArea.vaHigh;

        // Volume Imbalance data
        signal.market.bidVolumeAtPrice = imbalance.bidVolume;
        signal.market.askVolumeAtPrice = imbalance.askVolume;
        signal.market.volumeImbalanceRatio = imbalance.ratio;
        signal.market.volumeImbalanceSentiment = imbalance.sentiment;

        // VWAP data
        if (vwapCalculator.isInitialized()) {
            signal.market.vwap = vwapCalculator.getVWAP();
            signal.market.priceVsVwap = vwapCalculator.getRelationship(price);
            signal.market.vwapDistanceTicks = vwapCalculator.getDistance(price, pips);
        }

        // EMAs
        signal.market.ema9 = ema9Val;
        signal.market.ema21 = ema21Val;
        signal.market.ema50 = ema50Val;
        signal.market.ema9DistanceTicks = ema9.isInitialized() ? ema9.getDistance(price, pips) : 0;
        signal.market.ema21DistanceTicks = ema21.isInitialized() ? ema21.getDistance(price, pips) : 0;
        signal.market.ema50DistanceTicks = ema50.isInitialized() ? ema50.getDistance(price, pips) : 0;

        // Trend
        signal.market.trend = cvd > 0 ? "BULLISH" : "BEARISH";
        signal.market.priceVsEma9 = ema9.isInitialized() ? ema9.getRelationship(price) : "UNKNOWN";
        signal.market.priceVsEma21 = ema21.isInitialized() ? ema21.getRelationship(price) : "UNKNOWN";
        signal.market.priceVsEma50 = ema50.isInitialized() ? ema50.getRelationship(price) : "UNKNOWN";

        // EMA Trend Alignment
        signal.market.emaTrendAlignment = emaAlignmentCount >= 2;
        signal.market.emaAlignmentCount = emaAlignmentCount;
        signal.market.trendStrength = trendStrength;

        // ATR
        if (atrCalculator.isInitialized()) {
            signal.market.atr = atrCalculator.getATR();
            signal.market.atrLevel = atrCalculator.getATRLevel(2.0);  // 2.0 as baseline
        }

        // ========== DOM (Depth of Market) DATA ==========
        // Real-time support/resistance from order book liquidity
        DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
        DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();

        if (support != null) {
            signal.market.domSupportPrice = support.price;
            signal.market.domSupportVolume = support.volume;
            signal.market.domSupportDistance = support.distanceTicks;
        }

        if (resistance != null) {
            signal.market.domResistancePrice = resistance.price;
            signal.market.domResistanceVolume = resistance.volume;
            signal.market.domResistanceDistance = resistance.distanceTicks;
        }

        signal.market.domImbalanceRatio = domAnalyzer.getImbalanceRatio();
        signal.market.domImbalanceSentiment = domAnalyzer.getImbalanceSentiment();
        signal.market.domTotalBidVolume = domAnalyzer.getTotalBidVolume();
        signal.market.domTotalAskVolume = domAnalyzer.getTotalAskVolume();
        signal.market.hasDomSupportNearby = domAnalyzer.hasSupportNearby();
        signal.market.hasDomResistanceNearby = domAnalyzer.hasResistanceNearby();
        signal.market.domConfluenceAdjustment = domAnalyzer.getConfluenceAdjustment(isBid);

        // ========== ACCOUNT CONTEXT ==========
        signal.account = new SignalData.AccountContext();
        signal.account.accountSize = accountSize;
        signal.account.currentBalance = accountSize + (aiOrderManager != null ? aiOrderManager.getDailyPnl() : 0);
        signal.account.dailyPnl = aiOrderManager != null ? aiOrderManager.getDailyPnl() : 0;
        signal.account.tradesToday = aiOrderManager != null ? aiOrderManager.getActivePositionCount() : 0;
        signal.account.maxContracts = maxPosition;
        signal.account.maxTradesPerDay = maxPosition;
        signal.account.riskPerTradePercent = riskPerTradePercent;

        // ========== PERFORMANCE HISTORY (simplified) ==========
        signal.performance = new SignalData.PerformanceHistory();
        signal.performance.totalTrades = 0;
        signal.performance.winRate = 0;

        // ========== RISK MANAGEMENT (Smart SL/TP) ==========
        signal.risk = new SignalData.RiskManagement();

        // Calculate smart SL/TP using ATR and DOM levels
        SmartSlTpResult slTp = calculateSmartSlTp(isBid, price);

        // ========== R:R QUALITY ASSESSMENT ==========
        // Create comprehensive R:R quality assessment instead of simple rejection
        signal.rrQuality = SignalData.RrQuality.assess(
            slTp.rrRatio,
            minRRRatio,
            slTp.slTicks,
            slTp.tpTicks,
            slTp.slSource,
            slTp.tpSource,
            slTp.issueType,
            slTp.issueDetails,
            slTp.canBeImproved,
            slTp.suggestedSlTicks,
            slTp.suggestedTpTicks
        );

        // Use calculated values
        signal.risk.stopLossTicks = slTp.slTicks;
        signal.risk.stopLossPrice = slTp.slPrice;
        signal.risk.stopLossValue = slTp.slTicks * tickValue;
        signal.risk.takeProfitTicks = slTp.tpTicks;
        signal.risk.takeProfitPrice = slTp.tpPrice;
        signal.risk.takeProfitValue = slTp.tpTicks * tickValue;
        signal.risk.breakEvenTicks = 3;
        signal.risk.breakEvenPrice = isBid ? price + 3 : price - 3;
        signal.risk.riskRewardRatio = String.format("1:%.1f", slTp.rrRatio);
        signal.risk.slTpReasoning = slTp.reasoning;

        // Calculate position size: fixed or auto from risk %
        int positionSize;
        if (autoCalculatePositionSize) {
            // Auto-calculate: (Account * Risk%) / StopLossValue
            double riskDollars = accountSize * (riskPerTradePercent / 100.0);
            positionSize = (int) Math.max(1, Math.floor(riskDollars / signal.risk.stopLossValue));
            positionSize = Math.min(positionSize, maxPosition);  // Cap at max position
        } else {
            positionSize = fixedPositionSize;
        }
        signal.risk.positionSizeContracts = positionSize;
        signal.risk.totalRiskPercent = (positionSize * signal.risk.stopLossValue / accountSize) * 100;

        // ========== THRESHOLD CONTEXT (for AI Adaptive Control) ==========
        signal.thresholds = new SignalData.ThresholdContext();
        signal.thresholds.minConfluenceScore = minConfluenceScore;
        signal.thresholds.confluenceThreshold = confluenceThreshold;
        signal.thresholds.icebergMinOrders = icebergMinOrders;
        signal.thresholds.spoofMinSize = spoofMinSize;
        signal.thresholds.absorptionMinSize = absorptionMinSize;
        signal.thresholds.adaptiveOrderThreshold = adaptiveOrderThreshold;
        signal.thresholds.adaptiveSizeThreshold = adaptiveSizeThreshold;
        signal.thresholds.thresholdMultiplier = thresholdMultiplier;
        signal.thresholds.signalsLastHour = signalsLastHour;
        signal.thresholds.recentWinRate = getRecentWinRate();
        signal.thresholds.isHighVolatility = isHighVolatility();

        // ========== ORDER FLOW CONTEXT (Carmine Rosato Methodology) ==========
        signal.orderFlow = new SignalData.OrderFlowContext();

        // 1. Big Fish Analysis
        PriceDeltaTracker.FishAnalysis fishAnalysis = priceDeltaTracker.analyzeForBigFish(price, 10);
        signal.orderFlow.hasBigFishNearby = fishAnalysis.hasBigFishNearby;
        if (fishAnalysis.nearestBigFish != null) {
            signal.orderFlow.bigFishPrice = fishAnalysis.nearestBigFish.price;
            signal.orderFlow.bigFishDelta = fishAnalysis.nearestBigFish.delta;
            signal.orderFlow.bigFishIsBuyer = fishAnalysis.nearestBigFish.isBuyer;
        }
        signal.orderFlow.bigFishDefending = fishAnalysis.isDefending;
        signal.orderFlow.bigFishSignal = fishAnalysis.signal;

        // 2. Volume Tail Analysis
        VolumeTailDetector.VolumeTailAnalysis tailAnalysis = volumeTailDetector.analyzeTails();
        signal.orderFlow.hasUpperTail = tailAnalysis.hasUpperTail;
        signal.orderFlow.hasLowerTail = tailAnalysis.hasLowerTail;
        signal.orderFlow.upperTailLength = tailAnalysis.upperTailLength;
        signal.orderFlow.lowerTailLength = tailAnalysis.lowerTailLength;
        signal.orderFlow.tailBias = tailAnalysis.bias;
        signal.orderFlow.tailReasoning = tailAnalysis.reasoning;

        // 3. Tape Speed Analysis
        TapeSpeedTracker.TapeSpeedAnalysis tapeAnalysis = tapeSpeedTracker.analyze();
        signal.orderFlow.tradesPerSecond = tapeAnalysis.tradesPerSecond;
        signal.orderFlow.volumePerSecond = tapeAnalysis.volumePerSecond;
        signal.orderFlow.speedLevel = tapeAnalysis.speedLevel;
        signal.orderFlow.dominantSide = tapeAnalysis.dominantSide;
        signal.orderFlow.isHighSpeed = tapeAnalysis.isHighSpeed;
        signal.orderFlow.isExhaustion = tapeAnalysis.isExhaustion;
        signal.orderFlow.tapeInterpretation = tapeAnalysis.interpretation;

        // 4. Stop Hunt Analysis
        StopHuntDetector.StopHuntAnalysis huntAnalysis = stopHuntDetector.analyze();
        signal.orderFlow.recentStopHunt = huntAnalysis.recentStopHunt;
        signal.orderFlow.stopHuntSignal = huntAnalysis.huntSignal;
        signal.orderFlow.stopHuntStrength = huntAnalysis.signalStrength;
        if (huntAnalysis.mostRecentHunt != null) {
            signal.orderFlow.stopHuntLevelType = huntAnalysis.mostRecentHunt.levelType;
            signal.orderFlow.stopHuntLevelPrice = huntAnalysis.mostRecentHunt.levelPrice;
        }

        // ========== CALCULATE FINAL SCORE ==========
        signal.score = calculateConfluenceScore(isBid, price, totalSize);

        // ========== APPLY R:R QUALITY SCORE ADJUSTMENT ==========
        // The R:R quality assessment modifies the final score
        // This replaces hard filtering with soft scoring
        if (signal.rrQuality != null) {
            int rrAdjustment = signal.rrQuality.qualityScore;
            signal.score += rrAdjustment;

            // Log the adjustment
            if (rrAdjustment != 0) {
                fileLog(String.format("📊 R:R Score Adjustment: %+d (R:R %.1f, %s)",
                    rrAdjustment, signal.rrQuality.rrRatio, signal.rrQuality.qualityLevel));
            }
        }

        signal.threshold = confluenceThreshold;
        signal.thresholdPassed = signal.score >= confluenceThreshold;

        // ========== CREATE AND SAVE SIGNAL SNAPSHOT ==========
        // Capture EVERYTHING used to generate this signal for post-analysis
        try {
            SignalSnapshot snapshot = createSignalSnapshot(isBid, price, totalSize, signal);

            // Save to JSON file
            File memoryDir = new File(System.getProperty("user.home"), "Library/Application Support/Bookmap/trading-memory");
            snapshot.save(memoryDir);

            // Log summary to file for quick reference
            fileLog(snapshot.getSummary());

            // Record this signal for future snapshots
            recordSignalForSnapshot(signal.direction, price, signal.score, currentDataTimestampMs);

            // Increment signal counter
            signalsThisSession++;

            log("📸 Signal snapshot saved: " + snapshot.signalId.substring(0, 8));
        } catch (Exception e) {
            log("⚠️ Failed to save signal snapshot: " + e.getMessage());
            fileLog("⚠️ Failed to save signal snapshot: " + e.getMessage());
        }

        return signal;
    }

    /**
     * Get number of signals in the last hour
     */
    private int signalsLastHour = 0;
    private long lastHourResetTime = 0;

    private int getSignalsLastHour() {
        long now = System.currentTimeMillis();
        if (now - lastHourResetTime > 3600000) {  // Reset every hour
            signalsLastHour = 0;
            lastHourResetTime = now;
        }
        return signalsLastHour;
    }

    /**
     * Get recent win rate (placeholder - implement with actual tracking)
     */
    private double getRecentWinRate() {
        // TODO: Track actual recent win rate from trades
        return 0.5;  // Default to 50%
    }

    /**
     * Check if volatility is high
     */
    private boolean isHighVolatility() {
        // Use ATR or recent price movement to determine
        return false;  // Placeholder
    }

    // ========== SIGNAL SNAPSHOT TRACKING ==========
    // Track recent activity for comprehensive snapshots
    private final LinkedList<SignalSnapshot.TradeEvent> recentTradesSnapshot = new LinkedList<>();
    private final LinkedList<SignalSnapshot.BboEvent> recentBboSnapshot = new LinkedList<>();
    private final LinkedList<SignalSnapshot.SignalEvent> recentSignalsSnapshot = new LinkedList<>();
    private static final int MAX_RECENT_EVENTS = 50;  // Keep last 50 events

    /**
     * Record a trade for snapshot history
     */
    private void recordTradeForSnapshot(int priceTicks, int size, boolean isBuy, long timestamp) {
        SignalSnapshot.TradeEvent event = new SignalSnapshot.TradeEvent();
        event.timestamp = timestamp;
        event.priceTicks = priceTicks;
        event.size = size;
        event.isBuy = isBuy;

        synchronized (recentTradesSnapshot) {
            recentTradesSnapshot.addLast(event);
            while (recentTradesSnapshot.size() > MAX_RECENT_EVENTS) {
                recentTradesSnapshot.removeFirst();
            }
        }
    }

    /**
     * Record BBO for snapshot history
     */
    private void recordBboForSnapshot(int bidTicks, int askTicks, int bidSize, int askSize, long timestamp) {
        SignalSnapshot.BboEvent event = new SignalSnapshot.BboEvent();
        event.timestamp = timestamp;
        event.bidTicks = bidTicks;
        event.askTicks = askTicks;
        event.bidSize = bidSize;
        event.askSize = askSize;

        synchronized (recentBboSnapshot) {
            recentBboSnapshot.addLast(event);
            while (recentBboSnapshot.size() > MAX_RECENT_EVENTS) {
                recentBboSnapshot.removeFirst();
            }
        }
    }

    /**
     * Record signal for history (other signals at similar time)
     */
    private void recordSignalForSnapshot(String direction, int priceTicks, int score, long timestamp) {
        SignalSnapshot.SignalEvent event = new SignalSnapshot.SignalEvent();
        event.timestamp = timestamp;
        event.direction = direction;
        event.priceTicks = priceTicks;
        event.score = score;
        event.outcome = "PENDING";

        synchronized (recentSignalsSnapshot) {
            recentSignalsSnapshot.addLast(event);
            while (recentSignalsSnapshot.size() > MAX_RECENT_EVENTS) {
                recentSignalsSnapshot.removeFirst();
            }
        }
    }

    /**
     * Create a comprehensive snapshot of ALL data used to generate this signal
     * This captures everything for post-analysis and debugging
     */
    private SignalSnapshot createSignalSnapshot(boolean isBid, int price, int totalSize, SignalData signal) {
        String signalId = java.util.UUID.randomUUID().toString();

        // Get orders at this price level for iceberg details
        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());

        // Build iceberg data
        SignalSnapshot.IcebergData icebergData = new SignalSnapshot.IcebergData();
        icebergData.orderCount = ordersAtPrice.size();
        icebergData.totalSize = totalSize;
        icebergData.adaptiveOrderThreshold = adaptiveOrderThreshold;
        icebergData.adaptiveSizeThreshold = adaptiveSizeThreshold;

        // Individual order details
        icebergData.orders = ordersAtPrice.stream()
            .map(orderId -> {
                SignalSnapshot.OrderDetail od = new SignalSnapshot.OrderDetail();
                od.orderId = orderId;
                OrderInfo info = orders.getOrDefault(orderId, new OrderInfo());
                od.size = info.size;
                od.isBid = info.isBid;
                od.timestamp = currentDataTimestampMs;
                return od;
            })
            .collect(Collectors.toList());

        // Build market context
        SignalSnapshot.MarketContext marketCtx = new SignalSnapshot.MarketContext();
        marketCtx.bidTicks = lastKnownBid;
        marketCtx.askTicks = lastKnownAsk;
        marketCtx.midTicks = (lastKnownBid + lastKnownAsk) / 2;
        marketCtx.bidSize = lastKnownBidSize;
        marketCtx.askSize = lastKnownAskSize;

        // CVD
        marketCtx.cvd = cvdCalculator.getCVD();
        marketCtx.cvdAtSignalPrice = cvdCalculator.getCVDAtPrice(price);
        marketCtx.cvdTrend = cvdCalculator.getCVDTrend();
        marketCtx.cvdStrength = cvdCalculator.getCVDStrength();
        marketCtx.cvdBuySellRatio = cvdCalculator.getBuySellRatio();
        marketCtx.cvdDivergence = cvdCalculator.checkDivergence(price, 20).toString();

        // EMAs
        marketCtx.ema9 = ema9.isInitialized() ? ema9.getEMA() : 0;
        marketCtx.ema21 = ema21.isInitialized() ? ema21.getEMA() : 0;
        marketCtx.ema50 = ema50.isInitialized() ? ema50.getEMA() : 0;
        marketCtx.ema9_ticks = ema9.isInitialized() ? (int)(marketCtx.ema9 / pips) : 0;
        marketCtx.ema21_ticks = ema21.isInitialized() ? (int)(marketCtx.ema21 / pips) : 0;
        marketCtx.ema50_ticks = ema50.isInitialized() ? (int)(marketCtx.ema50 / pips) : 0;

        // EMA alignment
        if (signal.market != null) {
            marketCtx.emaAlignment = signal.market.trendStrength;
        } else {
            marketCtx.emaAlignment = "NEUTRAL";
        }

        // VWAP
        if (vwapCalculator.isInitialized()) {
            marketCtx.vwap = vwapCalculator.getVWAP();
            marketCtx.vwapTicks = (int)(marketCtx.vwap / pips);
            marketCtx.priceVsVwap = vwapCalculator.getRelationship(price);
            marketCtx.vwapDistance = vwapCalculator.getDistance(price, pips);
        }

        // Volume Profile
        marketCtx.pocTicks = volumeProfile.getPOC();
        marketCtx.pocActual = marketCtx.pocTicks * pips;
        VolumeProfileCalculator.ValueArea va = volumeProfile.getValueArea();
        marketCtx.vaHighTicks = va.vaHigh;
        marketCtx.vaLowTicks = va.vaLow;

        if (signal.market != null) {
            marketCtx.volumeAtSignalPrice = signal.market.volumeAtSignalPrice;
            marketCtx.volumeRatioAtSignal = signal.market.volumeRatioAtPrice;
            marketCtx.volumeImbalance = signal.market.volumeImbalanceSentiment;
        }

        // DOM levels
        marketCtx.domBidLevels = new ArrayList<>();
        marketCtx.domAskLevels = new ArrayList<>();
        DOMAnalyzer.DOMLevel support = domAnalyzer.getNearestSupport();
        DOMAnalyzer.DOMLevel resistance = domAnalyzer.getNearestResistance();
        if (support != null) {
            SignalSnapshot.DomLevel sl = new SignalSnapshot.DomLevel();
            sl.priceTicks = support.price;
            sl.size = support.volume;
            marketCtx.domBidLevels.add(sl);
        }
        if (resistance != null) {
            SignalSnapshot.DomLevel rl = new SignalSnapshot.DomLevel();
            rl.priceTicks = resistance.price;
            rl.size = resistance.volume;
            marketCtx.domAskLevels.add(rl);
        }

        // Build confluence breakdown
        SignalSnapshot.ConfluenceBreakdown confluenceBd = new SignalSnapshot.ConfluenceBreakdown();
        if (signal.scoreBreakdown != null) {
            confluenceBd.icebergPoints = signal.scoreBreakdown.icebergPoints;
            confluenceBd.icebergDetails = signal.scoreBreakdown.icebergDetails;
            confluenceBd.icebergCount = signal.scoreBreakdown.icebergCount;
            confluenceBd.totalSize = signal.scoreBreakdown.totalSize;
            confluenceBd.cvdPoints = signal.scoreBreakdown.cvdPoints;
            confluenceBd.cvdDetails = signal.scoreBreakdown.cvdDetails;
            confluenceBd.cvdDivergencePoints = signal.scoreBreakdown.cvdDivergencePoints;
            confluenceBd.emaPoints = signal.scoreBreakdown.emaTrendPoints;
            confluenceBd.emaDetails = signal.scoreBreakdown.emaTrendDetails;
            confluenceBd.vwapPoints = signal.scoreBreakdown.vwapPoints;
            confluenceBd.vwapDetails = signal.scoreBreakdown.vwapDetails;
            confluenceBd.volumeProfilePoints = signal.scoreBreakdown.volumeProfilePoints;
            confluenceBd.volumeProfileDetails = signal.scoreBreakdown.volumeProfileDetails;
        }
        confluenceBd.totalPoints = signal.score;
        confluenceBd.maxPossiblePoints = 100;  // Rough estimate

        // R:R
        if (signal.rrQuality != null) {
            confluenceBd.rrPoints = signal.rrQuality.qualityScore;
            confluenceBd.rrDetails = signal.rrQuality.qualityLevel;
        }

        // Build risk data
        SignalSnapshot.RiskData riskData = new SignalSnapshot.RiskData();
        if (signal.risk != null) {
            riskData.stopLossTicks = signal.risk.stopLossTicks;
            riskData.takeProfitTicks = signal.risk.takeProfitTicks;
            riskData.stopLossActual = signal.risk.stopLossPrice * pips;
            riskData.takeProfitActual = signal.risk.takeProfitPrice * pips;
            riskData.slDistanceTicks = signal.risk.stopLossTicks;
            riskData.tpDistanceTicks = signal.risk.takeProfitTicks;
            riskData.rrRatio = signal.risk.takeProfitTicks > 0 ?
                (double) signal.risk.takeProfitTicks / signal.risk.stopLossTicks : 0;
            riskData.slReasoning = signal.risk.slTpReasoning;
            riskData.tpReasoning = signal.risk.slTpReasoning;
            riskData.tickValue = tickValue;
            riskData.slDollarRisk = signal.risk.stopLossValue;
            riskData.tpDollarTarget = signal.risk.takeProfitValue;
        }
        if (signal.rrQuality != null) {
            riskData.rrQuality = signal.rrQuality.qualityLevel;
        }
        if (signal.market != null && signal.market.atr > 0) {
            riskData.atrValue = signal.market.atr;
            riskData.atrTicks = (int)(signal.market.atr / pips);
        }

        // Build session data
        SignalSnapshot.SessionData sessionData = new SignalSnapshot.SessionData();
        if (sessionContext != null) {
            sessionData.phase = sessionContext.getCurrentPhase() != null ?
                sessionContext.getCurrentPhase().name() : "UNKNOWN";
            sessionData.minutesIntoSession = sessionContext.getMinutesIntoSession();
        }
        sessionData.signalsThisSession = signalsThisSession;
        sessionData.tradesThisSession = aiOrderManager != null ? aiOrderManager.getTotalTrades() : 0;
        sessionData.dailyPnl = aiOrderManager != null ? aiOrderManager.getDailyPnl() : 0;
        sessionData.sessionPnl = sessionData.dailyPnl;

        // Build recent activity
        SignalSnapshot.RecentActivity recentData = new SignalSnapshot.RecentActivity();
        synchronized (recentTradesSnapshot) {
            recentData.last20Trades = new ArrayList<>(recentTradesSnapshot);
        }
        synchronized (recentBboSnapshot) {
            recentData.last20Bbo = new ArrayList<>(recentBboSnapshot);
        }
        synchronized (recentSignalsSnapshot) {
            // Only include signals from last 5 minutes
            long cutoff = currentDataTimestampMs - 300000;
            recentData.recentSignals = recentSignalsSnapshot.stream()
                .filter(s -> s.timestamp > cutoff)
                .collect(Collectors.toList());
        }

        // Build order flow state
        SignalSnapshot.OrderFlowState orderFlowData = new SignalSnapshot.OrderFlowState();
        orderFlowData.totalIcebergSignalsToday = signalsThisSession;
        orderFlowData.activePositions = aiOrderManager != null ? aiOrderManager.getActivePositionCount() : 0;
        orderFlowData.bidLiquidityByLevel = new HashMap<>();
        orderFlowData.askLiquidityByLevel = new HashMap<>();

        // Build weights data
        SignalSnapshot.WeightsData weightsData = new SignalSnapshot.WeightsData();
        weightsData.icebergMax = confluenceWeights.get(ConfluenceWeights.ICEBERG_MAX);
        weightsData.cvdAlignMax = confluenceWeights.get(ConfluenceWeights.CVD_ALIGN_MAX);
        weightsData.cvdDivergePenalty = confluenceWeights.get(ConfluenceWeights.CVD_DIVERGE_PENALTY);
        weightsData.emaAlignMax = confluenceWeights.get(ConfluenceWeights.EMA_ALIGN_MAX);
        weightsData.vwapAlign = confluenceWeights.get(ConfluenceWeights.VWAP_ALIGN);
        weightsData.vwapDiverge = confluenceWeights.get(ConfluenceWeights.VWAP_DIVERGE);

        // Raw data for deep debugging
        Map<String, Object> raw = new HashMap<>();
        raw.put("lastIcebergSignalTime", lastIcebergSignalTime.get(price));
        raw.put("lastGlobalSignalTime", lastGlobalSignalTime);
        raw.put("recentOrderCounts", recentOrderCounts);
        raw.put("recentTotalSizes", recentTotalSizes);
        raw.put("minConfluenceScore", minConfluenceScore);
        raw.put("maxPosition", maxPosition);
        raw.put("accountSize", accountSize);

        // Build the snapshot
        SignalSnapshot snapshot = SignalSnapshot.builder()
            .signalId(signalId)
            .timestamp(currentDataTimestampMs)
            .instrument(alias)
            .pips(pips)
            .direction(isBid ? "LONG" : "SHORT")
            .priceTicks(price)
            .confluenceScore(signal.score)
            .confluenceThreshold(confluenceThreshold)
            .iceberg(icebergData)
            .market(marketCtx)
            .confluence(confluenceBd)
            .risk(riskData)
            .session(sessionData)
            .recentActivity(recentData)
            .orderFlow(orderFlowData)
            .weights(weightsData)
            .rawData(raw)
            .build();

        return snapshot;
    }

    // Counter for signals this session
    private int signalsThisSession = 0;

    /**
     * Calculate confluence score using adjustable weights
     *
     * AI can adjust individual weights within safety bounds to optimize
     * for different market conditions (trending, ranging, volatile, etc.)
     */
    private int calculateConfluenceScore(boolean isBid, int price, int totalSize) {
        int score = 0;
        ConfluenceWeights w = confluenceWeights;  // Shorthand

        // ========== ICEBERG DETECTION ==========
        int icebergScore = Math.min(w.get(ConfluenceWeights.ICEBERG_MAX),
                                     totalSize * w.get(ConfluenceWeights.ICEBERG_MULTIPLIER));
        score += icebergScore;

        // ========== CVD ALIGNMENT ==========
        long cvd = cvdCalculator.getCVD();
        double cvdStrength = cvdCalculator.getCVDStrength();

        boolean cvdAligned = (cvd > 0 && isBid) || (cvd < 0 && !isBid);
        boolean cvdDivergent = (cvd > 0 && !isBid) || (cvd < 0 && isBid);

        if (cvdAligned) {
            // CVD confirms signal direction
            int cvdScore = (int)Math.min(w.get(ConfluenceWeights.CVD_ALIGN_MAX), cvdStrength / 2 + 10);
            score += cvdScore;
        } else if (cvdDivergent) {
            // CVD OPPOSITE to signal direction - penalty
            score -= w.get(ConfluenceWeights.CVD_DIVERGE_PENALTY);
        }

        // CVD extreme exhaustion
        if (cvdCalculator.isAtExtreme(5.0) && !cvdAligned) {
            score -= 10;
        }

        // CVD divergence bonus
        CVDCalculator.DivergenceType divergence = cvdCalculator.checkDivergence(price, 20);
        if (divergence == CVDCalculator.DivergenceType.BULLISH && isBid) {
            score += 10;
        } else if (divergence == CVDCalculator.DivergenceType.BEARISH && !isBid) {
            score += 10;
        }

        // ========== VOLUME PROFILE ==========
        VolumeProfileCalculator.VolumeArea volumeArea = volumeProfile.getVolumeNearPrice(price);

        if (volumeArea.volumeRatio > 0.3) {
            score += w.get(ConfluenceWeights.VOLUME_PROFILE_MAX);  // High volume node
        } else if (volumeArea.volumeRatio < 0.05) {
            score += 5;  // Low volume = easy move
        }

        // ========== VOLUME IMBALANCE ==========
        VolumeProfileCalculator.VolumeImbalance imbalance = volumeProfile.getImbalance(price);

        if ("STRONG_BUYING".equals(imbalance.sentiment) && isBid) {
            score += w.get(ConfluenceWeights.VOLUME_IMBALANCE_MAX);
        } else if ("STRONG_SELLING".equals(imbalance.sentiment) && !isBid) {
            score += w.get(ConfluenceWeights.VOLUME_IMBALANCE_MAX);
        } else if ("BUYING".equals(imbalance.sentiment) || "SELLING".equals(imbalance.sentiment)) {
            score += w.get(ConfluenceWeights.VOLUME_IMBALANCE_MAX) / 2;
        }

        // ========== EMA TREND ALIGNMENT ==========
        int emaAlignmentCount = 0;
        int emaDivergenceCount = 0;
        int emaCount = 0;

        double ema9Val = ema9.isInitialized() ? ema9.getEMA() : Double.NaN;
        double ema21Val = ema21.isInitialized() ? ema21.getEMA() : Double.NaN;
        double ema50Val = ema50.isInitialized() ? ema50.getEMA() : Double.NaN;

        if (!Double.isNaN(ema9Val)) {
            emaCount++;
            if (isBid && price > ema9Val) emaAlignmentCount++;
            else if (!isBid && price < ema9Val) emaAlignmentCount++;
            else emaDivergenceCount++;
        }
        if (!Double.isNaN(ema21Val)) {
            emaCount++;
            if (isBid && price > ema21Val) emaAlignmentCount++;
            else if (!isBid && price < ema21Val) emaAlignmentCount++;
            else emaDivergenceCount++;
        }
        if (!Double.isNaN(ema50Val)) {
            emaCount++;
            if (isBid && price > ema50Val) emaAlignmentCount++;
            else if (!isBid && price < ema50Val) emaAlignmentCount++;
            else emaDivergenceCount++;
        }

        // Score based on EMA alignment/divergence
        if (emaAlignmentCount == emaCount && emaCount == 3) {
            score += w.get(ConfluenceWeights.EMA_ALIGN_MAX);  // Perfect alignment
        } else if (emaAlignmentCount >= 2) {
            score += w.get(ConfluenceWeights.EMA_ALIGN_PARTIAL);  // Partial alignment
        }

        // PENALTY: Price on wrong side of EMAs
        if (emaDivergenceCount == emaCount && emaCount == 3) {
            score -= w.get(ConfluenceWeights.EMA_DIVERGE_PENALTY);  // Complete divergence
        } else if (emaDivergenceCount >= 2) {
            score -= w.get(ConfluenceWeights.EMA_DIVERGE_PARTIAL);  // Partial divergence
        }

        // ========== VWAP ALIGNMENT ==========
        if (vwapCalculator.isInitialized()) {
            String vwapRel = vwapCalculator.getRelationship(price);
            if ((isBid && "ABOVE".equals(vwapRel)) || (!isBid && "BELOW".equals(vwapRel))) {
                score += w.get(ConfluenceWeights.VWAP_ALIGN);
            } else if ((isBid && "BELOW".equals(vwapRel)) || (!isBid && "ABOVE".equals(vwapRel))) {
                score -= w.get(ConfluenceWeights.VWAP_DIVERGE);
            }
        }

        // ========== TIME OF DAY ==========
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 10 && hour <= 15) {
            score += w.get(ConfluenceWeights.TIME_OF_DAY_MAX);  // Prime trading hours
        } else if ((hour >= 9 && hour < 10) || (hour > 15 && hour <= 16)) {
            score += w.get(ConfluenceWeights.TIME_OF_DAY_SECONDARY);  // Secondary hours
        }

        // ========== DOM (DEPTH OF MARKET) ALIGNMENT ==========
        int domAdjustment = domAnalyzer.getConfluenceAdjustment(isBid);
        int domMax = w.get(ConfluenceWeights.DOM_MAX);
        score += Math.max(-domMax, Math.min(domMax, domAdjustment));

        // ========== CARMINE ROSATO ORDER FLOW CONFLUENCE ==========

        // 1. Big Fish Defense Detection
        // Check if price is near a Big Fish level and they're defending
        PriceDeltaTracker.FishAnalysis fishAnalysis = priceDeltaTracker.analyzeForBigFish(price, 10);
        if (fishAnalysis.isDefending) {
            if ((fishAnalysis.signal.contains("BUY") && isBid) ||
                (fishAnalysis.signal.contains("SELL") && !isBid)) {
                score += 15;  // Big Fish defending in our direction
            } else {
                score -= 10;  // Big Fish defending against our direction
            }
        }

        // 2. Volume Tail Analysis
        // Check for volume tails indicating potential reversals
        VolumeTailDetector.VolumeTailAnalysis tailAnalysis = volumeTailDetector.analyzeTails();
        if (tailAnalysis.hasAnyTail()) {
            if ((tailAnalysis.bias.equals("BULLISH") && isBid) ||
                (tailAnalysis.bias.equals("BEARISH") && !isBid)) {
                score += 10;  // Tail supports our direction
            } else if (tailAnalysis.hasBothTails()) {
                // Both tails = consolidation, be cautious
                score -= 5;
            }
        }

        // 3. Tape Speed Analysis
        // Check for urgency, exhaustion, or low conviction
        int tapeSpeedAdjustment = tapeSpeedTracker.getSpeedScoreAdjustment(isBid);
        score += tapeSpeedAdjustment;

        // 4. Stop Hunt Detection
        // Check for recent stop hunts that could signal reversals
        int stopHuntAdjustment = stopHuntDetector.getStopHuntScoreAdjustment(isBid);
        score += stopHuntAdjustment;

        // ========== FINAL SCORE CLAMP ==========
        score = Math.max(-50, score);

        return score;
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Update last known price
        // DEBUGGING: onTrade appears to provide price in TICK UNITS already (not actual price)
        // Based on logs showing price=27558 which matches MBO order prices in ticks
        double previousLastKnownPrice = lastKnownPrice;

        // For now, assume onTrade provides tick units directly (don't divide by pips)
        lastKnownPrice = price;  // Already in tick units

        // Debug: Log price updates periodically (every 1000ms)
        long now = System.currentTimeMillis();
        if (now - lastPriceDebugLog > 1000) {
            fileLog("📊 onTrade: price=" + price + " (assumed ticks), pips=" + pips + ", lastKnownPrice=" + lastKnownPrice + " ticks, actualPrice=" + (price * pips));
            lastPriceDebugLog = now;
        }

        // ========== UPDATE ALL INDICATORS ==========

        // Update CVD
        cvdCalculator.onTrade(price, size, tradeInfo);

        // Update Volume Profile
        volumeProfile.onTrade(price, size);

        // Update EMAs
        ema9.update(price);
        ema21.update(price);
        ema50.update(price);

        // Update VWAP
        vwapCalculator.update(price, size);

        // Update session context - track processed trades for warm-up
        if (sessionContext != null) {
            sessionContext.recordProcessedTrade();
        }

        // Record trade for signal snapshots
        recordTradeForSnapshot((int)price, size, tradeInfo.isBidAggressor, currentDataTimestampMs);

        // Update ATR (track high/low)
        if (Double.isNaN(currentHigh) || price > currentHigh) {
            currentHigh = price;
        }
        if (Double.isNaN(currentLow) || price < currentLow) {
            currentLow = price;
        }

        // Update ATR on each trade with current OHLC
        if (!Double.isNaN(previousClose)) {
            atrCalculator.update(currentHigh, currentLow, price);
        }
        previousClose = price;  // Will be updated on next trade

        // ========== CARMINE ROSATO ORDER FLOW TOOLS UPDATE ==========
        // Determine aggressor side from trade info
        boolean isAggressorBuy = tradeInfo == null ? true : !tradeInfo.isBidAggressor;
        int tradePrice = (int) price;
        int tradeSize = size;

        // Update per-price delta tracker (Big Fish levels)
        priceDeltaTracker.recordTrade(tradePrice, tradeSize, isAggressorBuy);

        // Update volume tail detector
        volumeTailDetector.recordVolume(tradePrice, tradeSize);

        // Update tape speed tracker
        tapeSpeedTracker.recordTrade(tradePrice, tradeSize, isAggressorBuy);

        // Update stop hunt detector with key levels
        if (vwapCalculator.isInitialized()) {
            stopHuntDetector.updateKeyLevels(
                (int) vwapCalculator.getVWAP(),
                volumeTailDetector.getRangeHigh(),
                volumeTailDetector.getRangeLow(),
                0  // Session open not tracked yet
            );
        }
        stopHuntDetector.recordTrade(tradePrice, tradeSize, isAggressorBuy);

        // ========== PERFORMANCE TRACKING: CHECK SIGNAL OUTCOMES ==========
        checkSignalOutcomes((int)price);

        // Monitor positions on each trade
        if (aiOrderManager != null) {
            aiOrderManager.onPriceUpdate((int)price, System.currentTimeMillis());
        }
    }

    /**
     * TimeListener callback - receives the true data timestamp from Bookmap.
     * This is called BEFORE any other data event during replay mode.
     * Use this timestamp for accurate session phase detection during replay.
     *
     * @param nanoseconds Data timestamp in nanoseconds (Unix epoch)
     */
    @Override
    public void onTimestamp(long nanoseconds) {
        // Convert nanoseconds to milliseconds for session tracking
        currentDataTimestampMs = nanoseconds / 1_000_000;

        // Update session context with the data timestamp (not wall clock)
        // This ensures correct session phase during replay
        if (sessionContext != null && lastKnownPrice != 0) {
            // Adjust session start time on first data (for replay mode)
            sessionContext.adjustSessionStart(currentDataTimestampMs);

            sessionContext.update(lastKnownPrice, currentDataTimestampMs);
        }
    }

    @Override
    public void onBbo(int priceBid, int priceAsk, int sizeBid, int sizeAsk) {
        // DEBUGGING: The BBO parameters appear to be in a different order than expected!
        // Based on log analysis:
        //   - param1 (priceBid) = 27567 ✓ valid price
        //   - param2 (priceAsk) = 2 ✗ looks like SIZE
        //   - param3 (sizeBid) = 27568 ✗ looks like PRICE
        //   - param4 (sizeAsk) = 33 ✓ valid size
        // Actual order seems to be: priceBid, sizeAtBid, priceAsk, sizeAtAsk
        // So: realPriceAsk = sizeBid, realSizeBid = priceAsk

        int realPriceBid = priceBid;
        int realPriceAsk = sizeBid;  // This is actually the ask price!
        int realSizeBid = priceAsk;  // This is actually the bid size!
        int realSizeAsk = sizeAsk;

        int midPrice = (realPriceBid + realPriceAsk) / 2;

        // Debug: Log BBO with corrected interpretation
        long now = System.currentTimeMillis();
        if (now - lastPriceDebugLog > 1000) {
            fileLog("📊 onBbo CORRECTED: bid=" + realPriceBid + " ask=" + realPriceAsk + " mid=" + midPrice + " ticks (bidSize=" + realSizeBid + " askSize=" + realSizeAsk + ")");
            lastPriceDebugLog = now;
        }

        // Update lastKnownPrice with corrected BBO data
        lastKnownPrice = midPrice;
        lastKnownBid = realPriceBid;
        lastKnownAsk = realPriceAsk;
        lastKnownBidSize = realSizeBid;
        lastKnownAskSize = realSizeAsk;

        // Record BBO for signal snapshots
        recordBboForSnapshot(realPriceBid, realPriceAsk, realSizeBid, realSizeAsk, currentDataTimestampMs);

        // Monitor positions on BBO update
        if (aiOrderManager != null) {
            aiOrderManager.onPriceUpdate(midPrice, System.currentTimeMillis());
        }
    }

    @Override
    public void stop() {
        log("OrderFlowStrategyEnhanced stopped");

        // Shutdown AI components
        if (aiIntegration != null) {
            aiIntegration.shutdown();
            log("✅ AI Integration Layer shut down");
        }

        if (updateExecutor != null) {
            updateExecutor.shutdown();
        }

        if (logWriter != null) {
            logWriter.close();
        }
        if (signalWriter != null) {
            signalWriter.close();
        }
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String logMsg = timestamp + " - " + message;
        System.out.println(logMsg);
        if (logWriter != null) {
            logWriter.println(logMsg);
            logWriter.flush();
        }
    }

    /**
     * File log for debugging - writes to ~/ai-execution.log
     */
    private void fileLog(String message) {
        try (java.io.PrintWriter fw = new java.io.PrintWriter(
                new java.io.FileWriter(System.getProperty("user.home") + "/ai-execution.log", true))) {
            fw.println(new java.util.Date() + " " + message);
            fw.flush();
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Debug log - only logs when devMode is enabled
     */
    private void debug(String message) {
        if (devMode) {
            log("🔧 " + message);
        }
    }

    /**
     * Debug log with format - only logs when devMode is enabled
     */
    private void debug(String format, Object... args) {
        if (devMode) {
            log("🔧 " + String.format(format, args));
        }
    }

    // ========== TEST SL/TP LINES METHOD ==========
    /**
     * Test SL/TP line drawing independently of AI/signals
     * This helps diagnose line drawing issues
     */
    private void testSlTpLines() {
        // DIRECT FILE LOG - bypass Bookmap log capture issues
        String testLogFile = System.getProperty("user.home") + "/sltp-test.log";
        try (java.io.PrintWriter fw = new java.io.PrintWriter(new java.io.FileWriter(testLogFile, true))) {
            fw.println("=== TEST SL/TP LINES CLICKED at " + new java.util.Date() + " ===");
            fw.println("lastKnownPrice = " + lastKnownPrice);
            fw.println("pips = " + pips);
            fw.flush();
        } catch (Exception e) {
            System.err.println("Failed to write test log: " + e.getMessage());
        }

        log("🧪 ========== TEST SL/TP LINES ==========");

        // Get current price (in tick units) - lastKnownPrice is a primitive double
        int currentPrice = (int) lastKnownPrice;

        if (currentPrice == 0) {
            log("❌ TEST FAILED: No current price available. Wait for data.");
            return;
        }

        log("🧪 Current price (ticks): " + currentPrice);
        log("🧪 pips value: " + pips);

        // DIRECT FILE LOG (continued - use testLogFile from above)
        try (java.io.PrintWriter fw = new java.io.PrintWriter(new java.io.FileWriter(testLogFile, true))) {
            // Calculate test SL/TP levels (in tick units)
            int testSl = currentPrice - 30;  // 30 ticks below
            int testTp = currentPrice + 50;  // 50 ticks above

            fw.println("testSl = " + testSl);
            fw.println("testTp = " + testTp);
            fw.println("aiStopLossLine = " + (aiStopLossLine != null ? "NOT NULL" : "NULL"));
            fw.println("aiTakeProfitLine = " + (aiTakeProfitLine != null ? "NOT NULL" : "NULL"));

            if (aiStopLossLine == null || aiTakeProfitLine == null) {
                fw.println("FAIL: Line indicators not initialized!");
                fw.flush();
                return;
            }

            // Set active levels (in tick units for internal tracking)
            activeStopLossPrice = testSl;
            activeTakeProfitPrice = testTp;
            fw.println("Set activeStopLossPrice = " + activeStopLossPrice);
            fw.println("Set activeTakeProfitPrice = " + activeTakeProfitPrice);

            // addPoint expects tick values (same units as onBbo), NOT actual prices
            fw.println("Calling aiStopLossLine.addPoint(" + testSl + ") x10...");
            for (int i = 0; i < 10; i++) {
                aiStopLossLine.addPoint(testSl);
            }
            fw.println("SL addPoint succeeded x10");

            fw.println("Calling aiTakeProfitLine.addPoint(" + testTp + ") x10...");
            for (int i = 0; i < 10; i++) {
                aiTakeProfitLine.addPoint(testTp);
            }
            fw.println("TP addPoint succeeded x10");

            // ALSO try using addIcon on a marker indicator to verify coordinates work
            fw.println("Trying addIcon on aiLongEntryMarker at SL price...");
            if (aiLongEntryMarker != null) {
                aiLongEntryMarker.addIcon(testSl, AIMarkerIcons.createLongEntryIcon(), 3, 3);
                fw.println("addIcon at SL succeeded");
            }
            if (aiShortEntryMarker != null) {
                aiShortEntryMarker.addIcon(testTp, AIMarkerIcons.createShortEntryIcon(), 3, 3);
                fw.println("addIcon at TP succeeded");
            }

            fw.println("=== TEST COMPLETE ===");
            fw.println("You should see ORANGE line at tick " + testSl + " (SL)");
            fw.println("You should see GREEN line at tick " + testTp + " (TP)");
            fw.println("You should see CYAN marker at tick " + testSl + " (SL marker)");
            fw.println("You should see PINK marker at tick " + testTp + " (TP marker)");
            fw.flush();
        } catch (Exception e) {
            try (java.io.PrintWriter fw = new java.io.PrintWriter(new java.io.FileWriter(testLogFile, true))) {
                fw.println("EXCEPTION: " + e.getMessage());
                e.printStackTrace(fw);
            } catch (Exception ignored) {}
        }

        // Log results to Bookmap console as well
        log("🧪 TEST COMPLETE - Check /tmp/bm-test-sl-tp.log for details");
        log("🧪 You should see ORANGE line at tick " + (currentPrice - 30) + " (SL)");
        log("🧪 You should see GREEN line at tick " + (currentPrice + 50) + " (TP)");
    }

    /**
     * Clear the test SL/TP lines
     */
    private void clearSlTpLines() {
        log("🧪 Clearing SL/TP lines...");
        activeStopLossPrice = null;
        activeTakeProfitPrice = null;
        log("🧪 SL/TP levels cleared");
    }

    // ========== INNER CLASSES ==========

    private static class OrderInfo {
        String orderId;
        boolean isBid;
        int price;
        int size;
        long timestamp;
    }

    private static class Trade {
        String entryTime;
        double entryPrice;
        double exitPrice;
        int holdTimeMinutes;
        double pnl;
        int score;
        String reason;
    }

    private static class Signal {
        String type;
        int score;
        long timestamp;

        Signal(String type, int score, long timestamp) {
            this.type = type;
            this.score = score;
            this.timestamp = timestamp;
        }
    }

    // ========== PERFORMANCE TRACKING METHODS ==========

    /**
     * Check tracked signals and record outcomes when price moves significantly
     */
    private void checkSignalOutcomes(int currentPrice) {
        long now = System.currentTimeMillis();

        // Check each tracked signal
        for (Map.Entry<Integer, SignalPerformance> entry : trackedSignals.entrySet()) {
            SignalPerformance perf = entry.getValue();

            // Skip if signal already closed
            if (perf.profitable != null) {
                continue;
            }

            int signalPrice = perf.entryPrice;

            // Calculate ticks moved based on signal direction
            int ticksMoved;
            if (perf.isBid) {
                // BUY signal: profit when price goes up
                ticksMoved = currentPrice - signalPrice;
            } else {
                // SELL signal: profit when price goes down
                ticksMoved = signalPrice - currentPrice;
            }

            // Check if price moved significantly (10 ticks = $125 for ES)
            final int SIGNIFICANT_MOVE_TICKS = 10;

            if (Math.abs(ticksMoved) >= SIGNIFICANT_MOVE_TICKS) {
                // Record outcome
                perf.exitPrice = currentPrice;
                perf.exitTimestamp = now;
                perf.ticksMoved = ticksMoved;
                perf.profitable = ticksMoved > 0;

                long durationMs = perf.exitTimestamp - perf.timestamp;
                double durationSeconds = durationMs / 1000.0;

                log(String.format("📊 SIGNAL OUTCOME: %s @ %d → %d | %d ticks | %.1fs | %s",
                    perf.isBid ? "BUY" : "SELL",
                    perf.entryPrice,
                    perf.exitPrice,
                    perf.ticksMoved,
                    durationSeconds,
                    perf.profitable ? "✅ PROFIT" : "❌ LOSS"));

                log(String.format("   Details: %s", perf.confluenceBreakdown));
            }

            // Check if signal timed out (5 minutes)
            if (now - perf.timestamp > SIGNAL_TRACKING_MS) {
                // Mark as timeout - closed at current price even if not significant move
                perf.exitPrice = currentPrice;
                perf.exitTimestamp = now;
                perf.ticksMoved = ticksMoved;
                perf.profitable = ticksMoved > 0;

                log(String.format("⏰ SIGNAL TIMEOUT: %s @ %d → %d | %d ticks | %s",
                    perf.isBid ? "BUY" : "SELL",
                    perf.entryPrice,
                    perf.exitPrice,
                    perf.ticksMoved,
                    perf.profitable ? "✅ PROFIT" : "❌ LOSS"));
            }
        }

        // Clean up old closed signals (older than 1 hour)
        trackedSignals.entrySet().removeIf(entry -> {
            SignalPerformance perf = entry.getValue();
            return perf.profitable != null &&
                   (now - perf.exitTimestamp) > (60 * 60 * 1000);
        });
    }

    /**
     * Generate performance report for all tracked signals (manual - shows popup)
     */
    private void generatePerformanceReport() {
        generatePerformanceReport(true);
    }

    /**
     * Generate performance report for all tracked signals
     * @param showPopup true to show dialog, false for silent (file only)
     */
    private void generatePerformanceReport(boolean showPopup) {
        if (trackedSignals.isEmpty()) {
            if (showPopup) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(statsPanel,
                        "📊 No signals tracked yet.\n\n" +
                        "Wait for iceberg signals to be detected,\n" +
                        "then click Report again to see performance data.",
                        "Performance Report",
                        JOptionPane.INFORMATION_MESSAGE);
                });
            }
            log("📊 No signals tracked yet");
            return;
        }

        int totalSignals = trackedSignals.size();
        int closedSignals = 0;
        int profitableSignals = 0;
        int losingSignals = 0;
        int openSignals = 0;
        int totalTicksMoved = 0;

        for (SignalPerformance perf : trackedSignals.values()) {
            if (perf.profitable != null) {
                closedSignals++;
                if (perf.profitable) {
                    profitableSignals++;
                } else {
                    losingSignals++;
                }
                totalTicksMoved += perf.ticksMoved;
            } else {
                openSignals++;
            }
        }

        double winRate = closedSignals > 0 ? (profitableSignals * 100.0 / closedSignals) : 0;
        double avgTicks = closedSignals > 0 ? (totalTicksMoved * 1.0 / closedSignals) : 0;

        // Build report string for popup
        StringBuilder report = new StringBuilder();
        report.append("📊 PERFORMANCE REPORT\n");
        report.append("==================\n\n");
        report.append(String.format("Total Signals: %d\n", totalSignals));
        report.append(String.format("Open Signals: %d\n", openSignals));
        report.append(String.format("Closed Signals: %d\n", closedSignals));
        report.append(String.format("Profitable: %d | Losing: %d\n", profitableSignals, losingSignals));
        report.append(String.format("Win Rate: %.1f%%\n", winRate));
        report.append(String.format("Avg Ticks/Signal: %.1f\n\n", avgTicks));

        // Score distribution analysis
        Map<String, Integer> scoreDistribution = new HashMap<>();
        Map<String, Integer> scoreWins = new HashMap<>();

        for (SignalPerformance perf : trackedSignals.values()) {
            if (perf.profitable != null) {
                String scoreRange;
                if (perf.score >= 100) scoreRange = "100+";
                else if (perf.score >= 80) scoreRange = "80-99";
                else if (perf.score >= 60) scoreRange = "60-79";
                else scoreRange = "<60";

                scoreDistribution.merge(scoreRange, 1, Integer::sum);
                if (perf.profitable) {
                    scoreWins.merge(scoreRange, 1, Integer::sum);
                }
            }
        }

        report.append("WIN RATE BY SCORE RANGE:\n");
        for (String range : Arrays.asList("100+", "80-99", "60-79", "<60")) {
            int total = scoreDistribution.getOrDefault(range, 0);
            int wins = scoreWins.getOrDefault(range, 0);
            if (total > 0) {
                double rangeWinRate = (wins * 100.0) / total;
                report.append(String.format("  %s: %.1f%% (%d/%d)\n", range, rangeWinRate, wins, total));
            }
        }

        // Show popup dialog only for manual reports (with copyable text)
        if (showPopup) {
            SwingUtilities.invokeLater(() -> {
                JTextArea textArea = new JTextArea(report.toString());
                textArea.setEditable(false);
                textArea.setWrapStyleWord(false);  // Don't wrap - keep table format
                textArea.setLineWrap(false);
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
                JScrollPane scrollPane = new JScrollPane(textArea);
                scrollPane.setPreferredSize(new Dimension(600, 400));
                JOptionPane.showMessageDialog(statsPanel,
                    scrollPane,
                    "Performance Report",
                    JOptionPane.INFORMATION_MESSAGE);
            });
        }

        // Always log to file (both manual and automatic)
        String reportType = showPopup ? "MANUAL" : "AUTOMATIC";
        log("📊 ========== PERFORMANCE REPORT (" + reportType + ") ==========");
        log(String.format("   Total Signals: %d", totalSignals));
        log(String.format("   Open Signals: %d", openSignals));
        log(String.format("   Closed Signals: %d", closedSignals));
        log(String.format("   Profitable: %d | Losing: %d", profitableSignals, losingSignals));
        log(String.format("   Win Rate: %.1f%%", winRate));
        log(String.format("   Avg Ticks/Signal: %.1f", avgTicks));
        log("==========================================");

        log("📊 Win Rate by Score Range:");
        for (String range : Arrays.asList("100+", "80-99", "60-79", "<60")) {
            int total = scoreDistribution.getOrDefault(range, 0);
            int wins = scoreWins.getOrDefault(range, 0);
            if (total > 0) {
                double rangeWinRate = (wins * 100.0) / total;
                log(String.format("   %s: %.1f%% (%d/%d)", range, rangeWinRate, wins, total));
            }
        }
    }

    // ========== ICON CREATION METHODS ==========
    // Create custom icons for iceberg signals (discrete markers, no connecting lines)

    /**
     * Create BUY icon (green circle with arrow up)
     */
    private BufferedImage createBuyIcon() {
        BufferedImage icon = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Green circle background
        g.setColor(new Color(0, 200, 0, 200));
        g.fillOval(2, 2, 26, 26);

        // White arrow pointing up
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3));
        int[] xPoints = {15, 8, 22};
        int[] yPoints = {6, 20, 20};
        g.fillPolygon(xPoints, yPoints, 3);

        // Border
        g.setColor(new Color(0, 150, 0));
        g.setStroke(new BasicStroke(2));
        g.drawOval(2, 2, 26, 26);

        g.dispose();
        return icon;
    }

    /**
     * Create SELL icon (red circle with arrow down)
     */
    private BufferedImage createSellIcon() {
        BufferedImage icon = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Red circle background
        g.setColor(new Color(200, 0, 0, 200));
        g.fillOval(2, 2, 26, 26);

        // White arrow pointing down
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3));
        int[] xPoints = {15, 8, 22};
        int[] yPoints = {24, 10, 10};
        g.fillPolygon(xPoints, yPoints, 3);

        // Border
        g.setColor(new Color(150, 0, 0));
        g.setStroke(new BasicStroke(2));
        g.drawOval(2, 2, 26, 26);

        g.dispose();
        return icon;
    }

    // ========== AI MARKER CALLBACK IMPLEMENTATION ==========

    @Override
    public void onEntryMarker(boolean isLong, int price, int score, String reasoning, int stopLossPrice, int takeProfitPrice) {
        // File log for debugging
        fileLog("📍 onEntryMarker CALLED: isLong=" + isLong + ", price=" + price + ", SL=" + stopLossPrice + ", TP=" + takeProfitPrice);

        try {
            log("📍 AI ENTRY MARKER CALLBACK INVOKED:");
            log(String.format("   isLong=%s, price=%d (ticks), score=%d", isLong, price, score));
            log(String.format("   stopLossPrice=%d (ticks), takeProfitPrice=%d (ticks)", stopLossPrice, takeProfitPrice));

            // Validate SL/TP values
            if (stopLossPrice == 0 || takeProfitPrice == 0) {
                log("⚠️ WARNING: SL or TP is 0! This might indicate a missing plan in AI decision");
                fileLog("⚠️ WARNING: SL or TP is 0!");
            }

            // addIcon expects tick values (same units as onBbo), NOT actual prices
            log(String.format("   Price: %d ticks (pips=%.4f, actual=%.2f)", price, pips, price * pips));

            // Create icon based on direction
            BufferedImage icon = isLong ?
                AIMarkerIcons.createLongEntryIcon() :   // CYAN circle
                AIMarkerIcons.createShortEntryIcon();   // PINK circle

            // Select appropriate indicator
            Indicator markerIndicator = isLong ? aiLongEntryMarker : aiShortEntryMarker;
            fileLog("   markerIndicator: " + (markerIndicator != null ? "OK" : "NULL"));

            // Add marker to chart using tick values
            markerIndicator.addIcon(price, icon, 3, 3);
            fileLog("✅ addIcon called with price=" + price + " (ticks)");

            // NOTE: SL/TP levels are NOT set here - they will be set after order fills
            // This prevents showing signal-based prices before we know actual fill price
            // Market order slippage means fill price differs from signal price
            log(String.format("✅ Entry marker placed. SL/TP will be set after fill (signal SL=%d TP=%d)",
                stopLossPrice, takeProfitPrice));
        } catch (Exception e) {
            log("❌ Failed to place entry marker: " + e.getMessage());
            fileLog("❌ Failed to place entry marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onSkipMarker(int price, int score, String reasoning) {
        try {
            // addIcon expects tick values (same units as onBbo), NOT actual prices

            // Create skip icon (WHITE circle)
            BufferedImage icon = AIMarkerIcons.createSkipIcon();

            // Add marker to chart using tick values
            aiSkipMarker.addIcon(price, icon, 3, 3);

            log("⚪ AI SKIP MARKER @ tick " + price + " (actual: " + (price * pips) + ", Score: " + score +
                ", Reason: " + reasoning + ")");
        } catch (Exception e) {
            log("❌ Failed to place skip marker: " + e.getMessage());
        }
    }

    @Override
    public void onExitMarker(int price, String reason, double pnl, boolean isWin) {
        try {
            // addIcon expects tick values (same units as onBbo), NOT actual prices

            // Create icon based on exit reason
            BufferedImage icon;
            if (reason.contains("Stop Loss")) {
                icon = AIMarkerIcons.createStopLossIcon();  // ORANGE X
            } else if (reason.contains("Take Profit")) {
                icon = AIMarkerIcons.createTakeProfitIcon();  // BLUE diamond
            } else {
                icon = AIMarkerIcons.createStopLossIcon();  // Default to ORANGE X
            }

            // Add marker to chart using tick values
            aiExitMarker.addIcon(price, icon, 3, 3);

            // Clear SL/TP lines when position closes
            // Add a point at 0 to "break" the line and make it disappear
            if (aiStopLossLine != null) {
                aiStopLossLine.addPoint(0);
            }
            if (aiTakeProfitLine != null) {
                aiTakeProfitLine.addPoint(0);
            }
            activeStopLossPrice = null;
            activeTakeProfitPrice = null;

            String emoji = isWin ? "💎" : "🛑";
            log(emoji + " AI EXIT MARKER @ tick " + price + " (actual: " + (price * pips) + ", P&L: $" +
                String.format("%.2f", pnl) + ", Reason: " + reason + ") - SL/TP lines cleared");

            // Update stats panel with the completed trade
            recordTrade(price, reason, pnl, isWin);

        } catch (Exception e) {
            log("❌ Failed to place exit marker: " + e.getMessage());
        }
    }

    /**
     * Record a completed trade in the stats
     */
    private void recordTrade(int exitPrice, String reason, double pnl, boolean isWin) {
        try {
            // Create trade record
            Trade trade = new Trade();
            trade.entryTime = java.time.LocalDateTime.now().toString();
            trade.exitPrice = exitPrice * pips;
            trade.pnl = pnl;
            trade.reason = reason;

            // Add to today's trades
            todayTrades.add(trade);

            // Update counters
            totalTrades.incrementAndGet();
            if (isWin) {
                winningTrades.incrementAndGet();
            }

            // Update P&L
            todayPnL += pnl;
            totalPnL.addAndGet((long) (pnl * 100));  // Convert to cents

            // Update drawdown tracking
            if (todayPnL > todayPeakEquity) {
                todayPeakEquity = todayPnL;
            }
            double drawdown = todayPeakEquity - todayPnL;
            if (drawdown > todayMaxDrawdown) {
                todayMaxDrawdown = drawdown;
            }

            log("📊 Stats updated: Total trades=" + totalTrades.get() +
                ", Win rate=" + String.format("%.1f%%", winningTrades.get() * 100.0 / totalTrades.get()) +
                ", Today P&L=" + String.format("$%.2f", todayPnL));

            // Update the stats panel
            updateStatsPanel();

        } catch (Exception e) {
            log("❌ Failed to record trade stats: " + e.getMessage());
        }
    }

    @Override
    public void onBreakEvenMarker(int newStopPrice, int triggerPrice) {
        try {
            // addIcon expects tick values (same units as onBbo), NOT actual prices

            // Create break-even icon (YELLOW square)
            BufferedImage icon = AIMarkerIcons.createBreakEvenIcon();

            // Add marker to chart using tick values
            aiExitMarker.addIcon(newStopPrice, icon, 3, 3);

            // Update SL line to new break-even price (keep in ticks)
            activeStopLossPrice = newStopPrice;

            log("🟡 AI BREAK-EVEN MARKER @ tick " + newStopPrice +
                " (actual: " + (newStopPrice * pips) + ", Stop moved from " + triggerPrice + ") - SL line updated");
        } catch (Exception e) {
            log("❌ Failed to place break-even marker: " + e.getMessage());
        }
    }

    @Override
    public void onSlippageRejectedMarker(int signalPrice, int currentPrice, int slippageTicks) {
        try {
            // Create magenta X icon for slippage rejection
            BufferedImage icon = AIMarkerIcons.createSlippageRejectedIcon();

            // Add marker at the signal price
            aiSkipMarker.addIcon(signalPrice, icon, 3, 3);

            log("🟣 SLIPPAGE REJECTED MARKER @ tick " + signalPrice +
                " (actual: " + (signalPrice * pips) + ", slippage: " + slippageTicks + " ticks, current: " + currentPrice + ")");
        } catch (Exception e) {
            log("❌ Failed to place slippage rejected marker: " + e.getMessage());
        }
    }

    @Override
    public void onBracketOrderPlaced(String orderId, String positionId, boolean isLong,
                                      int signalPrice, int slOffset, int tpOffset,
                                      int score, String reasoning) {
        // Track this pending bracket order so we can update markers when it fills
        PendingBracketOrder pending = new PendingBracketOrder();
        pending.orderId = orderId;
        pending.positionId = positionId;
        pending.isLong = isLong;
        pending.signalPrice = signalPrice;
        pending.slOffset = slOffset;
        pending.tpOffset = tpOffset;
        pending.score = score;
        pending.reasoning = reasoning;
        pending.timestamp = System.currentTimeMillis();

        pendingBracketOrders.put(orderId, pending);
        log("📌 TRACKING BRACKET ORDER: orderId=" + orderId + " signalPrice=" + signalPrice +
            " SL_off=" + slOffset + " TP_off=" + tpOffset);
        fileLog("📌 Pending bracket order tracked: " + orderId + " signal=" + signalPrice + " SL_off=" + slOffset + " TP_off=" + tpOffset);
    }

    // ========== OrdersListener Implementation ==========

    @Override
    public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
        log("📋 ORDER UPDATED: " + orderInfoUpdate.orderId + " status=" + orderInfoUpdate.status);
        // Delegate to order executor if available
        if (orderExecutor != null && orderExecutor instanceof BookmapOrderExecutor) {
            // Order tracking is handled internally by executor
        }
    }

    @Override
    public void onOrderExecuted(ExecutionInfo executionInfo) {
        // Log RAW execution info for debugging
        log("💰 RAW EXECUTION INFO:");
        log("   orderId: " + executionInfo.orderId);
        log("   price (raw double): " + executionInfo.price);
        log("   size: " + executionInfo.size);
        log("   pips: " + pips);

        // CRITICAL: executionInfo.price is already in TICK UNITS (same as onTrade, onBbo)
        // Do NOT divide by pips! Bookmap API returns tick units consistently.
        // Evidence: onTrade comment at line 5369, onBbo receives int prices directly
        int fillPriceTicks = (int) executionInfo.price;
        double actualPrice = fillPriceTicks * pips;
        log("💰 ORDER EXECUTED: " + executionInfo.orderId + " @ " + executionInfo.price + " ticks (actual: " + actualPrice + ")");
        fileLog("💰 ORDER EXECUTED: orderId=" + executionInfo.orderId + " price=" + executionInfo.price + " ticks (actual=" + actualPrice + ", pips=" + pips + ")");

        // Delegate to order executor if available
        if (orderExecutor != null && orderExecutor instanceof BookmapOrderExecutor) {
            // Execution tracking is handled internally by executor
        }

        // Check if this is an entry order fill for a pending bracket order
        PendingBracketOrder pending = pendingBracketOrders.get(executionInfo.orderId);
        if (pending != null) {
            // Entry order filled - update markers with ACTUAL fill price
            log("✅ BRACKET ENTRY FILLED: orderId=" + executionInfo.orderId + " fillPrice=" + fillPriceTicks + " ticks");

            // Calculate actual SL/TP from fill price and offsets
            int actualSlPrice = pending.isLong ?
                fillPriceTicks - pending.slOffset :
                fillPriceTicks + pending.slOffset;
            int actualTpPrice = pending.isLong ?
                fillPriceTicks + pending.tpOffset :
                fillPriceTicks - pending.tpOffset;

            // Calculate slippage from signal price
            int slippageTicks = Math.abs(fillPriceTicks - pending.signalPrice);

            log("📊 ACTUAL PRICES FROM FILL:");
            log(String.format("   Signal: %d ticks | Fill: %d ticks | Slippage: %d ticks",
                pending.signalPrice, fillPriceTicks, slippageTicks));
            int signalSlPrice = pending.isLong ? pending.signalPrice - pending.slOffset : pending.signalPrice + pending.slOffset;
            log(String.format("   Signal SL: %d | Actual SL: %d | Diff: %d",
                signalSlPrice, actualSlPrice, Math.abs(actualSlPrice - signalSlPrice)));
            int signalTpPrice = pending.isLong ? pending.signalPrice + pending.tpOffset : pending.signalPrice - pending.tpOffset;
            log(String.format("   Signal TP: %d | Actual TP: %d | Diff: %d",
                signalTpPrice, actualTpPrice, Math.abs(actualTpPrice - signalTpPrice)));

            // BOOKMAP COMPARISON - what Bookmap should show
            log("📈 BOOKMAP EXPECTED VALUES:");
            log(String.format("   Entry fill: %.2f (Bookmap should show this)", fillPriceTicks * pips));
            log(String.format("   Bookmap SL: %.2f = Entry %.2f - %d ticks offset", actualSlPrice * pips, fillPriceTicks * pips, pending.slOffset));
            log(String.format("   Bookmap TP: %.2f = Entry %.2f + %d ticks offset", actualTpPrice * pips, fillPriceTicks * pips, pending.tpOffset));
            log(String.format("📍 Our markers will show: SL=%.2f TP=%.2f", actualSlPrice * pips, actualTpPrice * pips));

            fileLog("✅ ENTRY FILL: signal=" + pending.signalPrice + " fill=" + fillPriceTicks + " slippage=" + slippageTicks +
                " SL=" + actualSlPrice + " TP=" + actualTpPrice);
            fileLog(String.format("📈 BOOKMAP COMPARISON: Entry=%.2f SL=%.2f TP=%.2f (offsets: SL=%d TP=%d ticks)",
                fillPriceTicks * pips, actualSlPrice * pips, actualTpPrice * pips, pending.slOffset, pending.tpOffset));

            // Update chart markers with actual prices (NOW we know fill price)
            activeStopLossPrice = actualSlPrice;
            activeTakeProfitPrice = actualTpPrice;
            log("📍 Chart SL/TP lines updated: SL=" + actualSlPrice + " TP=" + actualTpPrice);

            // CRITICAL: Update AIOrderManager position with actual fill prices
            // This fixes data integrity issue where signal-based prices were used for:
            // - Trade logging accuracy
            // - Performance statistics
            // - P&L calculations
            // - Memory/learning data
            if (aiOrderManager != null && pending.positionId != null) {
                aiOrderManager.updatePositionOnFill(
                    pending.positionId,
                    fillPriceTicks,
                    actualSlPrice,
                    actualTpPrice,
                    slippageTicks
                );
                log("📊 Position updated with actual fill prices via updatePositionOnFill()");
                fileLog("📊 updatePositionOnFill called: positionId=" + pending.positionId +
                    " fill=" + fillPriceTicks + " SL=" + actualSlPrice + " TP=" + actualTpPrice);
            } else {
                log("⚠️ Cannot update position: aiOrderManager=" + (aiOrderManager != null ? "OK" : "NULL") +
                    " positionId=" + pending.positionId);
            }

            // Remove from pending (order is now filled)
            pendingBracketOrders.remove(executionInfo.orderId);
            log("🧹 Removed from pending orders (size now: " + pendingBracketOrders.size() + ")");
        }

        // Check if this is a bracket order SL/TP execution
        if (aiOrderManager != null) {
            checkBracketOrderFill(executionInfo.orderId, fillPriceTicks);
        }
    }

    /**
     * Check if a filled order is a bracket order SL/TP and trigger exit marker
     */
    private void checkBracketOrderFill(String orderId, int fillPrice) {
        // AIOrderManager tracks positions with SL/TP order IDs like "entryId-SL" and "entryId-TP"
        // When Bookmap fills these, we need to trigger the exit marker
        if (aiOrderManager == null) return;

        // Check active positions for matching order IDs
        for (var position : aiOrderManager.getActivePositions().values()) {
            if (position.isClosed.get()) continue;

            String slOrderId = position.stopLossOrderId.get();
            String tpOrderId = position.takeProfitOrderId.get();

            // Check if this order ID matches or looks like a SL/TP bracket order
            boolean isSL = orderId.equals(slOrderId) || (slOrderId != null && orderId.contains(slOrderId)) || orderId.endsWith("-SL");
            boolean isTP = orderId.equals(tpOrderId) || (tpOrderId != null && orderId.contains(tpOrderId)) || orderId.endsWith("-TP");

            if (isSL) {
                // Stop loss filled
                log("🛑 BRACKET SL FILLED: " + orderId + " @ " + fillPrice);
                aiOrderManager.closePosition(position.id, fillPrice, "Stop Loss Hit", orderId);
                return;
            }

            if (isTP) {
                // Take profit filled
                log("💎 BRACKET TP FILLED: " + orderId + " @ " + fillPrice);
                aiOrderManager.closePosition(position.id, fillPrice, "Take Profit Hit", orderId);
                return;
            }
        }
    }
}
