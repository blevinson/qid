package velox.api.layer1.simplified.demo;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
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
    AIOrderManager.AIMarkerCallback {

    // ========== PERFORMANCE TRACKING ==========
    private Map<Integer, SignalPerformance> trackedSignals = new ConcurrentHashMap<>();
    private static final long SIGNAL_TRACKING_MS = 5 * 60 * 1000;  // Track for 5 minutes

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

    // ========== SAFETY PARAMETERS ==========
    @Parameter(name = "Simulation Mode Only")
    private Boolean simModeOnly = true;

    @Parameter(name = "Enable Auto-Execution")
    private Boolean autoExecution = false;

    // ========== ADAPTIVE MODE PARAMETERS ==========
    @Parameter(name = "Use AI Adaptive Thresholds")
    private Boolean useAIAdaptiveThresholds = false;  // Default: AI mode disabled

    @Parameter(name = "AI Re-evaluation Interval (min)")
    private Integer aiReevaluationInterval = 30;  // Re-evaluate every 30 minutes

    // ========== AI TRADING PARAMETERS ==========
    @Parameter(name = "Enable AI Trading")
    private Boolean enableAITrading = false;

    @Parameter(name = "AI Mode")
    private String aiMode = "MANUAL";  // MANUAL, SEMI_AUTO, FULL_AUTO

    @Parameter(name = "Confluence Threshold")
    private Integer confluenceThreshold = 50;

    @Parameter(name = "AI Auth Token")
    private String aiAuthToken = "8a4f5b950ea142c98746d5a320666414.Yf1MQwtkwfuDbyHw";

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
        public Boolean useAIAdaptiveThresholds = false;
        public Boolean enableAITrading = false;
        public String aiMode = "MANUAL";
        public Integer confluenceThreshold = 50;
        public String aiAuthToken = "8a4f5b950ea142c98746d5a320666414.Yf1MQwtkwfuDbyHw";
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
    private Object memoryService;  // TradingMemoryService - initialized as Object to avoid loading issues
    private AIInvestmentStrategist aiStrategist;
    private final Map<String, SignalData> pendingSignals = new ConcurrentHashMap<>();

    // Session Context - tracks trading session state for AI
    private SessionContext sessionContext;

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
    private JTextArea aiInsightsArea;
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

    // AI Chat Panel Components
    private JFrame chatWindow;
    private JEditorPane chatHistoryArea;
    private StringBuilder chatHtmlContent;  // Maintain full HTML document
    private JTextField chatInputField;
    private JButton chatSendButton;
    private JButton chatClearButton;
    private List<String[]> chatMessages = new ArrayList<>();  // Store [role, message] pairs

    // ========== STATE ==========
    private Map<String, OrderInfo> orders = new HashMap<>();
    private Map<Integer, List<String>> priceLevels = new HashMap<>();

    // Market state tracking
    private double lastKnownPrice = 0;
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

    // Current activity
    private List<Integer> activeSignals = new ArrayList<>();
    private Integer lastSignal = null;

    // API and helpers
    private Api api;
    private int pips;
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
        this.pips = (int) info.pips;

        log("========== OrderFlowStrategyEnhanced.initialize() ==========");
        log("Instrument: " + alias);
        log("Pip size: " + pips);

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

        // Initialize AI components if enabled
        if (enableAITrading && aiAuthToken != null && !aiAuthToken.isEmpty()) {
            log("🤖 Initializing AI Trading System...");

            // Create order executor with logger wrapper using real Bookmap API
            orderExecutor = new BookmapOrderExecutor(api, alias, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            });

            // Create AI integration layer with logger wrapper
            aiIntegration = new AIIntegrationLayer(aiAuthToken, new AIIntegrationLayer.AIStrategyLogger() {
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
            aiOrderManager = new AIOrderManager(orderExecutor, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            }, this);  // Pass 'this' as the marker callback
            aiOrderManager.breakEvenEnabled = true;
            aiOrderManager.breakEvenTicks = 3;
            aiOrderManager.maxPositions = maxPosition;
            aiOrderManager.maxDailyLoss = dailyLossLimit;

            log("✅ AI Trading System initialized");
            log("   Mode: " + aiMode);
            log("   Confluence Threshold: " + confluenceThreshold);
        } else {
            log("ℹ️ AI Trading disabled");
        }

        // Initialize Memory & Sessions (Phase 1)
        try {
            if (aiAuthToken != null && !aiAuthToken.isEmpty()) {
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

                // Initialize Session Context
                sessionContext = new SessionContext();
                log("✅ Session Context initialized");

                // Initialize AI Investment Strategist (Phase 3)
                aiStrategist = new AIInvestmentStrategist(service, aiAuthToken);
                log("✅ AI Investment Strategist initialized");
            } else {
                log("⚠️ Memory Service disabled (no API token)");
            }
        } catch (Exception e) {
            log("⚠️ Memory Service initialization: " + e.getMessage());
            e.printStackTrace();
        }

        // Initialize AI Threshold Service if token provided
        if (aiAuthToken != null && !aiAuthToken.isEmpty()) {
            aiThresholdService = new AIThresholdService(aiAuthToken);
            log("🤖 AI Chat Service initialized");

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
    }

    /**
     * Draw horizontal lines at active SL/TP levels
     */
    private long lastLevelLogTime = 0;
    private void drawAITradingLevels() {
        boolean hasStop = activeStopLossPrice != null && aiStopLossLine != null;
        boolean hasTp = activeTakeProfitPrice != null && aiTakeProfitLine != null;

        // Log every 5 seconds for debugging
        long now = System.currentTimeMillis();
        if (now - lastLevelLogTime > 5000) {
            lastLevelLogTime = now;
            if (hasStop || hasTp) {
                log(String.format("📊 AI LEVELS: SL=%s (%s) TP=%s (%s)",
                    activeStopLossPrice, hasStop ? "drawing" : "no indicator",
                    activeTakeProfitPrice, hasTp ? "drawing" : "no indicator"));
            }
        }

        if (hasStop) {
            aiStopLossLine.addPoint(activeStopLossPrice);
        }
        if (hasTp) {
            aiTakeProfitLine.addPoint(activeTakeProfitPrice);
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
        gbc.gridy = 1; gbc.gridwidth = 1;
        addSeparator(settingsPanel, "Signal Thresholds", gbc);

        gbc.gridy = 2;
        settingsPanel.add(new JLabel("Min Confluence Score:"), gbc);
        gbc.gridx = 1;
        minConfluenceSpinner = new JSpinner(new SpinnerNumberModel(minConfluenceScore.intValue(), 8, 15, 1));
        minConfluenceSpinner.addChangeListener(e -> updateMinConfluence());
        settingsPanel.add(minConfluenceSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        settingsPanel.add(new JLabel("Threshold Multiplier:"), gbc);
        gbc.gridx = 1;
        thresholdMultSpinner = new JSpinner(new SpinnerNumberModel(thresholdMultiplier.doubleValue(), 1.5, 5.0, 0.5));
        thresholdMultSpinner.addChangeListener(e -> updateThresholdMultiplier());
        settingsPanel.add(thresholdMultSpinner, gbc);

        // Minimum Detection Thresholds
        gbc.gridx = 0; gbc.gridy = 4;
        JLabel icebergOrdersLabel = new JLabel("Iceberg Min Orders:");
        icebergOrdersLabel.setToolTipText("Minimum orders at one price to trigger iceberg signal");
        settingsPanel.add(icebergOrdersLabel, gbc);
        gbc.gridx = 1;
        JSpinner icebergOrdersSpinner = new JSpinner(new SpinnerNumberModel(icebergMinOrders.intValue(), 5, 100, 5));
        icebergOrdersSpinner.setToolTipText("Higher = fewer but more reliable signals (default: 20)");
        icebergOrdersSpinner.addChangeListener(e -> icebergMinOrders = (Integer) icebergOrdersSpinner.getValue());
        settingsPanel.add(icebergOrdersSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        JLabel spoofSizeLabel = new JLabel("Spoof Min Size:");
        spoofSizeLabel.setToolTipText("Minimum order size to consider as potential spoof");
        settingsPanel.add(spoofSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner spoofSizeSpinner = new JSpinner(new SpinnerNumberModel(spoofMinSize.intValue(), 5, 100, 5));
        spoofSizeSpinner.setToolTipText("Higher = fewer spoof signals (default: 20)");
        spoofSizeSpinner.addChangeListener(e -> spoofMinSize = (Integer) spoofSizeSpinner.getValue());
        settingsPanel.add(spoofSizeSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 6;
        JLabel absorbSizeLabel = new JLabel("Absorption Min Size:");
        absorbSizeLabel.setToolTipText("Minimum trade size to detect absorption");
        settingsPanel.add(absorbSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner absorbSizeSpinner = new JSpinner(new SpinnerNumberModel(absorptionMinSize.intValue(), 10, 200, 10));
        absorbSizeSpinner.setToolTipText("Higher = fewer absorption signals (default: 50)");
        absorbSizeSpinner.addChangeListener(e -> absorptionMinSize = (Integer) absorbSizeSpinner.getValue());
        settingsPanel.add(absorbSizeSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "AI Adaptive Thresholds", gbc);

        gbc.gridy = 8; gbc.gridwidth = 2;
        settingsPanel.add(new JLabel("AI Auth Token:"), gbc);
        gbc.gridy = 9; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField aiTokenField = new JTextField();
        aiTokenField.setText(aiAuthToken);
        aiTokenField.setToolTipText("Your Claude API token for z.ai (leave empty to disable AI features)");
        aiTokenField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                aiAuthToken = aiTokenField.getText();
                // Reinitialize AI service if token changed
                if (aiAuthToken != null && !aiAuthToken.isEmpty()) {
                    aiThresholdService = new AIThresholdService(aiAuthToken);
                    log("🤖 AI Auth Token updated - Service initialized");
                } else {
                    aiThresholdService = null;
                    log("⚠️ AI Auth Token cleared - Service disabled");
                }
            }
        });
        settingsPanel.add(aiTokenField, gbc);
        gbc.fill = GridBagConstraints.NONE;  // Reset fill

        gbc.gridx = 0; gbc.gridy = 10; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Use AI Adaptive:"), gbc);
        gbc.gridx = 1;
        aiAdaptiveModeCheckBox = new JCheckBox();
        aiAdaptiveModeCheckBox.setSelected(useAIAdaptiveThresholds);
        aiAdaptiveModeCheckBox.setToolTipText("When enabled, AI analyzes market conditions and optimizes thresholds automatically");
        aiAdaptiveModeCheckBox.addActionListener(e -> updateAIAdaptiveMode());
        settingsPanel.add(aiAdaptiveModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 2;
        settingsPanel.add(new JLabel("AI Status:"), gbc);
        gbc.gridy = 12;
        aiStatusIndicator = new JLabel("🔴 AI Disabled");
        aiStatusIndicator.setForeground(Color.GRAY);
        settingsPanel.add(aiStatusIndicator, gbc);

        gbc.gridy = 14; gbc.gridwidth = 1;
        JButton aiChatButton = new JButton("💬 Open AI Chat");
        aiChatButton.setToolTipText("Open AI Chat window");
        aiChatButton.setEnabled(aiAuthToken != null && !aiAuthToken.isEmpty());
        aiChatButton.addActionListener(e -> openAIChatWindow());
        settingsPanel.add(aiChatButton, gbc);

        gbc.gridy = 16; gbc.gridwidth = 1;
        aiReevaluateButton = new JButton("🔄 Optimize Thresholds");
        aiReevaluateButton.setToolTipText("Ask AI to optimize trading thresholds based on market conditions");
        aiReevaluateButton.setEnabled(aiAuthToken != null && !aiAuthToken.isEmpty());
        aiReevaluateButton.addActionListener(e -> triggerAIReevaluation());
        settingsPanel.add(aiReevaluateButton, gbc);

        // AI Investment Strategist section
        gbc.gridx = 0; gbc.gridy = 17; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "AI Investment Strategist (Qid v2.0)", gbc);

        gbc.gridy = 18; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Enable AI Trading:"), gbc);
        gbc.gridx = 1;
        JCheckBox enableAITradingCheckBox = new JCheckBox();
        enableAITradingCheckBox.setSelected(enableAITrading);
        enableAITradingCheckBox.addActionListener(e -> {
            enableAITrading = enableAITradingCheckBox.isSelected();
            log("🤖 AI Trading " + (enableAITrading ? "ENABLED" : "DISABLED"));
        });
        settingsPanel.add(enableAITradingCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 19;
        settingsPanel.add(new JLabel("AI Mode:"), gbc);
        gbc.gridx = 1;
        String[] aiModes = {"MANUAL", "SEMI_AUTO", "FULL_AUTO"};
        JComboBox<String> aiModeComboBox = new JComboBox<>(aiModes);
        aiModeComboBox.setSelectedItem(aiMode);
        aiModeComboBox.addActionListener(e -> {
            aiMode = (String) aiModeComboBox.getSelectedItem();
            log("🔄 AI Mode: " + aiMode);
        });
        settingsPanel.add(aiModeComboBox, gbc);

        gbc.gridx = 0; gbc.gridy = 20;
        settingsPanel.add(new JLabel("Confluence Threshold:"), gbc);
        gbc.gridx = 1;
        JSpinner confThresholdSpinner = new JSpinner(new SpinnerNumberModel(confluenceThreshold.intValue(), 0, 135, 5));
        confThresholdSpinner.addChangeListener(e -> confluenceThreshold = (Integer) confThresholdSpinner.getValue());
        settingsPanel.add(confThresholdSpinner, gbc);

        // Safety Controls section
        gbc.gridx = 0; gbc.gridy = 21; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Safety Controls", gbc);

        gbc.gridy = 22; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Simulation Mode Only:"), gbc);
        gbc.gridx = 1;
        simModeCheckBox = new JCheckBox();
        simModeCheckBox.setSelected(simModeOnly);
        simModeCheckBox.addActionListener(e -> updateSimMode());
        settingsPanel.add(simModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 23;
        settingsPanel.add(new JLabel("Enable Auto-Execution:"), gbc);
        gbc.gridx = 1;
        autoExecCheckBox = new JCheckBox();
        autoExecCheckBox.setSelected(autoExecution);
        autoExecCheckBox.addActionListener(e -> updateAutoExecution());
        settingsPanel.add(autoExecCheckBox, gbc);

        // Risk Management section
        gbc.gridx = 0; gbc.gridy = 24; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Risk Management", gbc);

        gbc.gridy = 25; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Max Position:"), gbc);
        gbc.gridx = 1;
        JSpinner maxPosSpinner = new JSpinner(new SpinnerNumberModel(maxPosition.intValue(), 1, 10, 1));
        maxPosSpinner.addChangeListener(e -> maxPosition = (Integer) maxPosSpinner.getValue());
        settingsPanel.add(maxPosSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 26;
        settingsPanel.add(new JLabel("Daily Loss Limit ($):"), gbc);
        gbc.gridx = 1;
        JSpinner lossLimitSpinner = new JSpinner(new SpinnerNumberModel(dailyLossLimit.doubleValue(), 100.0, 5000.0, 100.0));
        lossLimitSpinner.addChangeListener(e -> dailyLossLimit = (Double) lossLimitSpinner.getValue());
        settingsPanel.add(lossLimitSpinner, gbc);

        // Apply button
        gbc.gridx = 0; gbc.gridy = 27; gbc.gridwidth = 2;
        JButton applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        settingsPanel.add(applyButton, gbc);

        // Version label (bottom right)
        gbc.gridx = 1; gbc.gridy = 28; gbc.gridwidth = 1;
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

        // AI Insights
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(statsPanel, "AI Coach Insights", gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.gridheight = 3;
        aiInsightsArea = new JTextArea(4, 30);
        aiInsightsArea.setEditable(false);
        aiInsightsArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane scrollPane = new JScrollPane(aiInsightsArea);
        statsPanel.add(scrollPane, gbc);
        row += 3;

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

        // Add components to window
        chatWindow.add(historyScrollPane, BorderLayout.CENTER);
        chatWindow.add(inputPanel, BorderLayout.SOUTH);

        // Add initial welcome message
        appendChatMessage("System", "Welcome to AI Chat! Ask me anything about trading, order flow, or market analysis.\n\nThis window stays open even when you close the Settings panel.\n\nType your message below and click Send or press Enter.");

        // Event listeners
        chatInputField.addActionListener(e -> sendChatMessage());
        chatSendButton.addActionListener(e -> sendChatMessage());
        chatClearButton.addActionListener(e -> clearChatHistory());

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
        .thenCompose(enhancedPrompt -> aiThresholdService.chat(enhancedPrompt))
        .thenAcceptAsync(response -> {
            // Remove "Thinking..." and add actual response
            SwingUtilities.invokeLater(() -> {
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
        }, SwingUtilities::invokeLater)
        .exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
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
        SwingUtilities.invokeLater(() -> {
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
        // Daily loss limit alert
        if (todayPnL <= -dailyLossLimit) {
            showAlert("⛔ DAILY LOSS LIMIT REACHED!",
                "Today's loss: $" + todayPnL + "\n" +
                "Limit: $" + dailyLossLimit + "\n\n" +
                "⛔ STOPPING TRADING FOR TODAY!");
        }

        // Drawdown alert
        if (todayMaxDrawdown > dailyLossLimit * 0.8) {
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
            aiStatusIndicator.setText("🟢 AI Active");
            aiStatusIndicator.setForeground(new Color(0, 150, 0));
            log("🤖 AI Adaptive Mode: ENABLED");
            log("   AI will analyze market conditions and optimize thresholds");

            // Trigger initial calculation
            triggerAIReevaluation();
        } else {
            aiStatusIndicator.setText("🔴 AI Disabled");
            aiStatusIndicator.setForeground(Color.GRAY);
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

        // Current market state
        context.currentPrice = getCurrentPrice();
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
                        String.format(" (Thresholds: %d orders, %d size, Avg: %.1f orders, %.1f size)",
                            adaptiveOrderThreshold, adaptiveSizeThreshold,
                            recentOrderCounts.stream().mapToInt(Integer::intValue).average().orElse(0.0),
                            recentTotalSizes.stream().mapToInt(Integer::intValue).average().orElse(0.0)));

                    if (signalWriter != null) {
                        signalWriter.println(signal);
                        signalWriter.flush();
                    }

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
                    perf.score = calculateConfluenceScore(isBid, price, totalSize);
                    perf.totalSize = totalSize;
                    perf.cvdAtSignal = cvdCalculator.getCVD();
                    perf.trendAtSignal = perf.cvdAtSignal > 0 ? "BULLISH" : "BEARISH";
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
                    log(String.format("🤖 AI CHECK: enableAITrading=%s, aiOrderManager=%s, aiStrategist=%s",
                        enableAITrading, aiOrderManager != null ? "ready" : "null", aiStrategist != null ? "ready" : "null"));

                    if (enableAITrading && aiOrderManager != null) {
                        // Create SignalData for AI evaluation
                        SignalData signalData = createSignalData(isBid, price, totalSize);

                        // Update session context with current price
                        if (sessionContext != null) {
                            sessionContext.update(price);
                        }

                        // Use AI Investment Strategist (memory-aware) if available
                        if (aiStrategist != null) {
                            log("🧠 Using AI Investment Strategist (memory-aware evaluation)");
                            aiStrategist.evaluateSetup(signalData, sessionContext, new AIInvestmentStrategist.AIStrategistCallback() {
                                @Override
                                public void onDecision(AIInvestmentStrategist.AIDecision decision) {
                                    // Execute AI decision
                                    if (decision.shouldTake && decision.plan != null) {
                                        log(String.format("✅ AI TAKE: %s (confidence: %.0f%%) - %s",
                                            decision.plan.orderType, decision.confidence * 100, decision.reasoning));

                                        // Validate SL/TP prices from plan
                                        int stopLossPrice = decision.plan.stopLossPrice;
                                        int takeProfitPrice = decision.plan.takeProfitPrice;
                                        boolean isLong = decision.plan.orderType.contains("BUY");

                                        // Fallback to signal-based calculation if plan prices are 0
                                        if (stopLossPrice == 0) {
                                            stopLossPrice = isLong ?
                                                signalData.price - (30 * signalData.pips) :
                                                signalData.price + (30 * signalData.pips);
                                            log("⚠️ SL was 0, calculated fallback: " + stopLossPrice);
                                        }
                                        if (takeProfitPrice == 0) {
                                            takeProfitPrice = isLong ?
                                                signalData.price + (70 * signalData.pips) :
                                                signalData.price - (70 * signalData.pips);
                                            log("⚠️ TP was 0, calculated fallback: " + takeProfitPrice);
                                        }

                                        log(String.format("📊 FINAL SL/TP: SL=%d TP=%d", stopLossPrice, takeProfitPrice));

                                        // Convert to AIIntegrationLayer.AIDecision format for order manager
                                        AIIntegrationLayer.AIDecision aiDecision = new AIIntegrationLayer.AIDecision();
                                        aiDecision.action = "TAKE";
                                        aiDecision.confidence = (int)(decision.confidence * 100);  // Convert 0-1 to 0-100
                                        aiDecision.reasoning = decision.reasoning;
                                        aiDecision.isLong = isLong;
                                        aiDecision.direction = isLong ? "LONG" : "SHORT";
                                        aiDecision.stopLoss = stopLossPrice;
                                        aiDecision.takeProfit = takeProfitPrice;
                                        aiOrderManager.executeEntry(aiDecision, signalData);
                                    } else {
                                        log(String.format("⏭️ AI SKIP: %s (confidence: %.0f%%)",
                                            decision.reasoning, decision.confidence * 100));
                                        AIIntegrationLayer.AIDecision aiDecision = new AIIntegrationLayer.AIDecision();
                                        aiDecision.action = "SKIP";
                                        aiDecision.confidence = (int)(decision.confidence * 100);
                                        aiDecision.reasoning = decision.reasoning;
                                        aiOrderManager.executeSkip(aiDecision, signalData);
                                    }
                                }
                            });
                        } else if (aiIntegration != null) {
                            // Fall back to basic AI integration
                            log("🤖 Using AI Integration Layer (basic evaluation)");
                            aiIntegration.evaluateSignalAsync(signalData)
                                .thenAccept(decision -> {
                                    // Execute AI decision
                                    if ("TAKE".equals(decision.action)) {
                                        aiOrderManager.executeEntry(decision, signalData);
                                    } else {
                                        aiOrderManager.executeSkip(decision, signalData);
                                    }
                                })
                                .exceptionally(ex -> {
                                    log("❌ AI evaluation failed: " + ex.getMessage());
                                    return null;
                                });
                        }
                    }
                    // ============================================

                    lastSignal = Integer.valueOf(totalSize);  // Store size as last signal
                    activeSignals.add(Integer.valueOf(totalSize));
                }
            }
        }
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
        signal.timestamp = System.currentTimeMillis();

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
        signal.market.timeOfDay = new SimpleDateFormat("HH:mm").format(new Date());
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
        signal.account.accountSize = 10000.0;
        signal.account.currentBalance = 10000.0 + (aiOrderManager != null ? aiOrderManager.getDailyPnl() : 0);
        signal.account.dailyPnl = aiOrderManager != null ? aiOrderManager.getDailyPnl() : 0;
        signal.account.tradesToday = aiOrderManager != null ? aiOrderManager.getActivePositionCount() : 0;
        signal.account.maxContracts = maxPosition;
        signal.account.maxTradesPerDay = maxPosition;
        signal.account.riskPerTradePercent = 1.0;

        // ========== PERFORMANCE HISTORY (simplified) ==========
        signal.performance = new SignalData.PerformanceHistory();
        signal.performance.totalTrades = 0;
        signal.performance.winRate = 0;

        // ========== RISK MANAGEMENT ==========
        signal.risk = new SignalData.RiskManagement();
        int stopLossTicks = 20;  // 20 ticks = $250 for ES
        int takeProfitTicks = 40;  // 40 ticks = $500 for ES (1:2 ratio)
        signal.risk.stopLossTicks = stopLossTicks;
        signal.risk.stopLossPrice = isBid ? price - (stopLossTicks * pips) : price + (stopLossTicks * pips);
        signal.risk.stopLossValue = stopLossTicks * 12.5;  // ES futures
        signal.risk.takeProfitTicks = takeProfitTicks;
        signal.risk.takeProfitPrice = isBid ? price + (takeProfitTicks * pips) : price - (takeProfitTicks * pips);
        signal.risk.takeProfitValue = takeProfitTicks * 12.5;
        signal.risk.breakEvenTicks = 3;
        signal.risk.breakEvenPrice = isBid ? price + (3 * pips) : price - (3 * pips);
        signal.risk.riskRewardRatio = "1:2";
        signal.risk.positionSizeContracts = 1;
        signal.risk.totalRiskPercent = 1.5;

        // ========== CALCULATE FINAL SCORE ==========
        signal.score = calculateConfluenceScore(isBid, price, totalSize);
        signal.threshold = confluenceThreshold;
        signal.thresholdPassed = signal.score >= confluenceThreshold;

        return signal;
    }

    /**
     * Calculate confluence score (Enhanced with all indicators)
     */
    private int calculateConfluenceScore(boolean isBid, int price, int totalSize) {
        int score = 0;

        // ========== ICEBERG DETECTION (max 40 points) ==========
        int icebergScore = Math.min(40, totalSize * 2);
        score += icebergScore;

        // ========== CVD CONFIRMATION (max 25 points) ==========
        long cvd = cvdCalculator.getCVD();
        String cvdTrend = cvdCalculator.getCVDTrend();
        double cvdStrength = cvdCalculator.getCVDStrength();

        if ((cvd > 0 && isBid) || (cvd < 0 && !isBid)) {
            // CVD confirms signal direction
            int cvdScore = (int)Math.min(15, cvdStrength / 2);
            score += cvdScore;
        } else if (cvdCalculator.isAtExtreme(5.0)) {
            // Extreme CVD = potential exhaustion
            score -= 10;
        }

        // CVD divergence bonus
        CVDCalculator.DivergenceType divergence = cvdCalculator.checkDivergence(price, 20);
        if (divergence == CVDCalculator.DivergenceType.BULLISH && isBid) {
            score += 10;
        } else if (divergence == CVDCalculator.DivergenceType.BEARISH && !isBid) {
            score += 10;
        }

        // ========== VOLUME PROFILE (max 20 points) ==========
        VolumeProfileCalculator.VolumeArea volumeArea = volumeProfile.getVolumeNearPrice(price);

        if (volumeArea.volumeRatio > 0.3) {
            // High volume node (support/resistance)
            score += 20;
        } else if (volumeArea.volumeRatio < 0.05) {
            // Low volume = easy move
            score += 5;
        }

        // ========== VOLUME IMBALANCE (max 10 points) ==========
        VolumeProfileCalculator.VolumeImbalance imbalance = volumeProfile.getImbalance(price);

        if ("STRONG_BUYING".equals(imbalance.sentiment) && isBid) {
            score += 10;
        } else if ("STRONG_SELLING".equals(imbalance.sentiment) && !isBid) {
            score += 10;
        } else if ("BUYING".equals(imbalance.sentiment) || "SELLING".equals(imbalance.sentiment)) {
            score += 5;
        }

        // ========== EMA TREND ALIGNMENT (max 15 points) ==========
        int emaAlignmentCount = 0;

        double ema9Val = ema9.isInitialized() ? ema9.getEMA() : Double.NaN;
        double ema21Val = ema21.isInitialized() ? ema21.getEMA() : Double.NaN;
        double ema50Val = ema50.isInitialized() ? ema50.getEMA() : Double.NaN;

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

        // Score based on EMA alignment
        if (emaAlignmentCount == 3) {
            score += 15;  // Strong trend
        } else if (emaAlignmentCount == 2) {
            score += 10;  // Moderate trend
        } else if (emaAlignmentCount == 1) {
            score += 5;   // Weak trend
        }

        // ========== VWAP ALIGNMENT (max 10 points) ==========
        if (vwapCalculator.isInitialized()) {
            String vwapRel = vwapCalculator.getRelationship(price);
            if ((isBid && "ABOVE".equals(vwapRel)) || (!isBid && "BELOW".equals(vwapRel))) {
                score += 10;
            }
        }

        // ========== TIME OF DAY (max 10 points) ==========
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 10 && hour <= 15) {
            score += 10;  // Prime trading hours
        } else if ((hour >= 9 && hour < 10) || (hour > 15 && hour <= 16)) {
            score += 5;   // Secondary hours
        }

        // ========== SIZE BONUS (max 5 points) ==========
        if (totalSize >= 50) {
            score += 5;   // Large iceberg
        } else if (totalSize >= 30) {
            score += 3;
        }

        // ========== DOM (DEPTH OF MARKET) ALIGNMENT (max 10 points) ==========
        // Add DOM confluence adjustment from analyzer
        int domAdjustment = domAnalyzer.getConfluenceAdjustment(isBid);
        score += Math.max(-10, Math.min(10, domAdjustment));  // Clamp to +/- 10

        return score;
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
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

        // ========== PERFORMANCE TRACKING: CHECK SIGNAL OUTCOMES ==========
        checkSignalOutcomes((int)price);

        // Monitor positions on each trade
        if (aiOrderManager != null) {
            aiOrderManager.onPriceUpdate((int)price, System.currentTimeMillis());
        }
    }

    @Override
    public void onBbo(int priceBid, int priceAsk, int sizeBid, int sizeAsk) {
        // Monitor positions on BBO update
        if (aiOrderManager != null) {
            // Use mid price for monitoring
            int midPrice = (priceBid + priceAsk) / 2;
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
        try {
            log("📍 AI ENTRY MARKER CALLBACK INVOKED:");
            log(String.format("   isLong=%s, price=%d, score=%d", isLong, price, score));
            log(String.format("   stopLossPrice=%d, takeProfitPrice=%d", stopLossPrice, takeProfitPrice));

            // Validate SL/TP values
            if (stopLossPrice == 0 || takeProfitPrice == 0) {
                log("⚠️ WARNING: SL or TP is 0! This might indicate a missing plan in AI decision");
            }

            // Create icon based on direction
            BufferedImage icon = isLong ?
                AIMarkerIcons.createLongEntryIcon() :   // CYAN circle
                AIMarkerIcons.createShortEntryIcon();   // PINK circle

            // Select appropriate indicator
            Indicator markerIndicator = isLong ? aiLongEntryMarker : aiShortEntryMarker;

            // Add marker to chart
            markerIndicator.addIcon(price, icon, 3, 3);

            // Track active SL/TP levels for line drawing
            activeStopLossPrice = stopLossPrice;
            activeTakeProfitPrice = takeProfitPrice;

            log(String.format("✅ SL/TP levels set: SL=%d TP=%d (indicators: %s, %s)",
                activeStopLossPrice, activeTakeProfitPrice,
                aiStopLossLine != null ? "OK" : "NULL",
                aiTakeProfitLine != null ? "OK" : "NULL"));
        } catch (Exception e) {
            log("❌ Failed to place entry marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onSkipMarker(int price, int score, String reasoning) {
        try {
            // Create skip icon (WHITE circle)
            BufferedImage icon = AIMarkerIcons.createSkipIcon();

            // Add marker to chart
            aiSkipMarker.addIcon(price, icon, 3, 3);

            log("⚪ AI SKIP MARKER @ " + price + " (Score: " + score +
                ", Reason: " + reasoning + ")");
        } catch (Exception e) {
            log("❌ Failed to place skip marker: " + e.getMessage());
        }
    }

    @Override
    public void onExitMarker(int price, String reason, double pnl, boolean isWin) {
        try {
            // Create icon based on exit reason
            BufferedImage icon;
            if (reason.contains("Stop Loss")) {
                icon = AIMarkerIcons.createStopLossIcon();  // ORANGE X
            } else if (reason.contains("Take Profit")) {
                icon = AIMarkerIcons.createTakeProfitIcon();  // BLUE diamond
            } else {
                icon = AIMarkerIcons.createStopLossIcon();  // Default to ORANGE X
            }

            // Add marker to chart
            aiExitMarker.addIcon(price, icon, 3, 3);

            // Clear SL/TP lines when position closes
            activeStopLossPrice = null;
            activeTakeProfitPrice = null;

            String emoji = isWin ? "💎" : "🛑";
            log(emoji + " AI EXIT MARKER @ " + price + " (P&L: $" +
                String.format("%.2f", pnl) + ", Reason: " + reason + ") - SL/TP lines cleared");
        } catch (Exception e) {
            log("❌ Failed to place exit marker: " + e.getMessage());
        }
    }

    @Override
    public void onBreakEvenMarker(int newStopPrice, int triggerPrice) {
        try {
            // Create break-even icon (YELLOW square)
            BufferedImage icon = AIMarkerIcons.createBreakEvenIcon();

            // Add marker to chart
            aiExitMarker.addIcon(newStopPrice, icon, 3, 3);

            // Update SL line to new break-even price
            activeStopLossPrice = newStopPrice;

            log("🟡 AI BREAK-EVEN MARKER @ " + newStopPrice +
                " (Stop moved from " + triggerPrice + ") - SL line updated");
        } catch (Exception e) {
            log("❌ Failed to place break-even marker: " + e.getMessage());
        }
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
        log("💰 ORDER EXECUTED: " + executionInfo.orderId + " @ " + executionInfo.price);
        // Delegate to order executor if available
        if (orderExecutor != null && orderExecutor instanceof BookmapOrderExecutor) {
            // Execution tracking is handled internally by executor
        }
    }
}
