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

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
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
@Layer1StrategyName("Order Flow Enhanced")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategyEnhanced implements
    CustomModule,
    MarketByOrderDepthDataListener,
    TradeDataListener,
    BboListener,
    CustomSettingsPanelProvider {

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
    @Parameter(name = "Use Adaptive Thresholds")
    private Boolean useAdaptiveThresholds = true;  // Default: adaptive mode enabled

    // ========== AI TRADING PARAMETERS ==========
    @Parameter(name = "Enable AI Trading")
    private Boolean enableAITrading = false;

    @Parameter(name = "AI Mode")
    private String aiMode = "MANUAL";  // MANUAL, SEMI_AUTO, FULL_AUTO

    @Parameter(name = "Confluence Threshold")
    private Integer confluenceThreshold = 50;

    @Parameter(name = "AI Auth Token")
    private String aiAuthToken = "";

    // ========== INDICATORS (Layer 1: Detection Markers) ==========
    // NOTE: Using separate indicators for each marker to avoid connecting lines
    // TODO: Upgrade to ScreenSpacePainter (advanced API) for proper icons
    private Indicator icebergBuyMarker;     // GREEN buy signals
    private Indicator icebergSellMarker;    // RED sell signals
    private int icebergMarkerCount = 0;     // Track number of markers
    private Indicator spoofingMarker;       // MAGENTA triangle markers
    private Indicator absorptionMarker;     // YELLOW square markers

    // ========== AI COMPONENTS ==========
    private AIIntegrationLayer aiIntegration;
    private AIOrderManager aiOrderManager;
    private OrderExecutor orderExecutor;
    private final Map<String, SignalData> pendingSignals = new ConcurrentHashMap<>();

    // ========== ENHANCED CONFLUENCE INDICATORS ==========
    private final CVDCalculator cvdCalculator = new CVDCalculator();
    private final VolumeProfileCalculator volumeProfile = new VolumeProfileCalculator(50, 1000);
    private final EMACalculator ema9 = new EMACalculator(9);
    private final EMACalculator ema21 = new EMACalculator(21);
    private final EMACalculator ema50 = new EMACalculator(50);
    private final VWAPCalculator vwapCalculator = new VWAPCalculator();
    private final ATRCalculator atrCalculator = new ATRCalculator(14);

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
    private JButton askAIButton;

    // ========== STATE ==========
    private Map<String, OrderInfo> orders = new HashMap<>();
    private Map<Integer, List<String>> priceLevels = new HashMap<>();

    // Signal counts
    private final AtomicInteger icebergCount = new AtomicInteger(0);
    private final AtomicInteger spoofCount = new AtomicInteger(0);
    private final AtomicInteger absorptionCount = new AtomicInteger(0);

    // Cooldown tracking
    private Map<Integer, Long> lastIcebergSignalTime = new HashMap<>();
    private static final long ICEBERG_COOLDOWN_MS = 10000;
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

        // Initialize AI components if enabled
        if (enableAITrading && aiAuthToken != null && !aiAuthToken.isEmpty()) {
            log("🤖 Initializing AI Trading System...");

            // Create order executor with logger wrapper
            orderExecutor = new SimpleOrderExecutor(new AIIntegrationLayer.AIStrategyLogger() {
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

            // Create AI order manager with logger wrapper
            aiOrderManager = new AIOrderManager(orderExecutor, new AIIntegrationLayer.AIStrategyLogger() {
                @Override
                public void log(String message, Object... args) {
                    OrderFlowStrategyEnhanced.this.log(message);
                }
            });
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

        // Generate performance report every 5 minutes (silent, file only)
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport(false);  // false = no popup, file only
            } catch (Exception e) {
                System.err.println("Error generating performance report: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);  // Start after 5 minutes, then every 5 minutes
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
        minConfluenceSpinner = new JSpinner(new SpinnerNumberModel(10, 8, 15, 1));
        minConfluenceSpinner.addChangeListener(e -> updateMinConfluence());
        settingsPanel.add(minConfluenceSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        settingsPanel.add(new JLabel("Threshold Multiplier:"), gbc);
        gbc.gridx = 1;
        thresholdMultSpinner = new JSpinner(new SpinnerNumberModel(3.0, 1.5, 5.0, 0.5));
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
        addSeparator(settingsPanel, "Adaptive Thresholds", gbc);

        gbc.gridy = 8; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Use Adaptive Thresholds:"), gbc);
        gbc.gridx = 1;
        adaptiveModeCheckBox = new JCheckBox();
        adaptiveModeCheckBox.setSelected(useAdaptiveThresholds);
        adaptiveModeCheckBox.setToolTipText("When enabled, automatically calculates thresholds. When disabled, use manual values below.");
        adaptiveModeCheckBox.addActionListener(e -> updateAdaptiveMode());
        settingsPanel.add(adaptiveModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 9;
        JLabel adaptOrderLabel = new JLabel("Adaptive Order Thresh:");
        adaptOrderLabel.setToolTipText("Dynamic threshold based on recent order activity");
        settingsPanel.add(adaptOrderLabel, gbc);
        gbc.gridx = 1;
        // Clamp value to valid range to handle saved settings
        int safeOrderThreshold = Math.max(1, Math.min(adaptiveOrderThreshold, 100));
        adaptOrderSpinner = new JSpinner(new SpinnerNumberModel(safeOrderThreshold, 1, 100, 5));
        adaptOrderSpinner.setToolTipText("Auto-adjusts based on market conditions (default: 25)");
        adaptOrderSpinner.setEnabled(!useAdaptiveThresholds);  // Disabled in adaptive mode
        adaptOrderSpinner.addChangeListener(e -> {
            if (!useAdaptiveThresholds) {
                adaptiveOrderThreshold = (Integer) adaptOrderSpinner.getValue();
                log("📊 Manual Order Threshold: " + adaptiveOrderThreshold);
            }
        });
        settingsPanel.add(adaptOrderSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 10;
        JLabel adaptSizeLabel = new JLabel("Adaptive Size Thresh:");
        adaptSizeLabel.setToolTipText("Dynamic size threshold based on recent activity");
        settingsPanel.add(adaptSizeLabel, gbc);
        gbc.gridx = 1;
        // Clamp value to valid range to handle saved settings
        int safeSizeThreshold = Math.max(1, Math.min(adaptiveSizeThreshold, 500));
        adaptSizeSpinner = new JSpinner(new SpinnerNumberModel(safeSizeThreshold, 1, 500, 25));
        adaptSizeSpinner.setToolTipText("Auto-adjusts based on market conditions (default: 100)");
        adaptSizeSpinner.setEnabled(!useAdaptiveThresholds);  // Disabled in adaptive mode
        adaptSizeSpinner.addChangeListener(e -> {
            if (!useAdaptiveThresholds) {
                adaptiveSizeThreshold = (Integer) adaptSizeSpinner.getValue();
                log("📊 Manual Size Threshold: " + adaptiveSizeThreshold);
            }
        });
        settingsPanel.add(adaptSizeSpinner, gbc);

        // Safety Controls section
        gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Safety Controls", gbc);

        gbc.gridy = 12; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Simulation Mode Only:"), gbc);
        gbc.gridx = 1;
        simModeCheckBox = new JCheckBox();
        simModeCheckBox.setSelected(simModeOnly);
        simModeCheckBox.addActionListener(e -> updateSimMode());
        settingsPanel.add(simModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 13;
        settingsPanel.add(new JLabel("Enable Auto-Execution:"), gbc);
        gbc.gridx = 1;
        autoExecCheckBox = new JCheckBox();
        autoExecCheckBox.setSelected(autoExecution);
        autoExecCheckBox.addActionListener(e -> updateAutoExecution());
        settingsPanel.add(autoExecCheckBox, gbc);

        // Risk Management section
        gbc.gridx = 0; gbc.gridy = 14; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Risk Management", gbc);

        gbc.gridy = 15; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Max Position:"), gbc);
        gbc.gridx = 1;
        JSpinner maxPosSpinner = new JSpinner(new SpinnerNumberModel(maxPosition.intValue(), 1, 10, 1));
        maxPosSpinner.addChangeListener(e -> maxPosition = (Integer) maxPosSpinner.getValue());
        settingsPanel.add(maxPosSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 16;
        settingsPanel.add(new JLabel("Daily Loss Limit:"), gbc);
        gbc.gridx = 1;
        JSpinner lossLimitSpinner = new JSpinner(new SpinnerNumberModel(dailyLossLimit.intValue(), 100.0, 5000.0, 100.0));
        lossLimitSpinner.addChangeListener(e -> dailyLossLimit = (Double) lossLimitSpinner.getValue());
        settingsPanel.add(lossLimitSpinner, gbc);

        // Apply button
        gbc.gridx = 0; gbc.gridy = 17; gbc.gridwidth = 2;
        JButton applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        settingsPanel.add(applyButton, gbc);

        // Version label (bottom right)
        gbc.gridx = 1; gbc.gridy = 18; gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.weightx = 1.0;
        JLabel versionLabel = new JLabel("v0.03.00 (Performance Tracking)");
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
        askAIButton = new JButton("🤖 Ask AI");
        askAIButton.addActionListener(e -> askAICoach());
        statsPanel.add(askAIButton, gbc);
        gbc.gridx = 2;
        JButton reportButton = new JButton("📊 Report");
        reportButton.addActionListener(e -> generatePerformanceReport());
        statsPanel.add(reportButton, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        exportButton = new JButton("📥 Export Data");
        exportButton.addActionListener(e -> exportData());
        statsPanel.add(exportButton, gbc);
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
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(statsPanel, message, title, JOptionPane.ERROR_MESSAGE)
        );
    }

    private void showWarning(String title, String message) {
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(statsPanel, message, title, JOptionPane.WARNING_MESSAGE)
        );
    }

    // ========== SETTINGS UPDATE METHODS ==========

    private void updateMinConfluence() {
        int value = (Integer) minConfluenceSpinner.getValue();
        log("📊 Min Confluence Score updated: " + value);
    }

    private void updateThresholdMultiplier() {
        double value = (Double) thresholdMultSpinner.getValue();
        log("📊 Threshold Multiplier updated: " + value);
    }

    private void updateAdaptiveMode() {
        boolean selected = adaptiveModeCheckBox.isSelected();
        useAdaptiveThresholds = selected;

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

    private void applySettings() {
        log("✅ Settings applied");
        JOptionPane.showMessageDialog(settingsPanel,
            "✅ Settings have been applied successfully!",
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

            JOptionPane.showMessageDialog(statsPanel,
                "✅ Data exported successfully!\n\n" +
                "Trades: " + tradesFile + "\n" +
                "Report: " + reportFile,
                "Export Complete",
                JOptionPane.INFORMATION_MESSAGE);

            log("📥 Data exported: " + tradesFile + ", " + reportFile);

        } catch (IOException e) {
            showAlert("Export Failed",
                "Failed to export data: " + e.getMessage());
        }
    }

    private void askAICoach() {
        String question = JOptionPane.showInputDialog(statsPanel,
            "Ask the AI Coach a question about:\n" +
            "• Current market conditions\n" +
            "• Recent trades\n" +
            "• Strategy improvements\n" +
            "• Risk management\n\n" +
            "Your question:",
            "Ask AI Coach",
            JOptionPane.QUESTION_MESSAGE);

        if (question != null && !question.isEmpty()) {
            // For now, just display a placeholder
            SwingUtilities.invokeLater(() -> {
                aiInsightsArea.setText("🤖 AI Coach:\n" +
                    "Question: " + question + "\n\n" +
                    "AI integration coming soon!\n" +
                    "This will connect to Claude SDK for intelligent analysis.");
            });
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

        // Only calculate adaptive thresholds if adaptive mode is enabled
        if (!useAdaptiveThresholds) {
            // Manual mode - keep current threshold values, just track stats
            return;
        }

        double avgOrderCount = recentOrderCounts.stream()
            .mapToInt(Integer::intValue).average().orElse(5.0);
        double avgTotalSize = recentTotalSizes.stream()
            .mapToInt(Integer::intValue).average().orElse(25.0);

        // Calculate adaptive thresholds
        adaptiveOrderThreshold = Math.max(
            (int) (avgOrderCount * 5.0),  // Increased multiplier from 2.0 to 5.0
            icebergMinOrders.intValue()
        );
        adaptiveSizeThreshold = Math.max(
            (int) (avgTotalSize * 5.0),  // Increased multiplier from 2.0 to 5.0
            absorptionMinSize.intValue()
        );

        // Ensure minimums
        adaptiveOrderThreshold = Math.max(adaptiveOrderThreshold, 10);
        adaptiveSizeThreshold = Math.max(adaptiveSizeThreshold, 50);
    }

    private void checkForIceberg(boolean isBid, int price) {
        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());

        // Debug: Show highest order count
        if (ordersAtPrice.size() > 5) {
            log(String.format("🔍 Tracking %d orders at %d (threshold: %d)",
                ordersAtPrice.size(), price, adaptiveOrderThreshold));
        }

        if (ordersAtPrice.size() >= adaptiveOrderThreshold) {
            long now = System.currentTimeMillis();
            Long lastSignalTimeAtPrice = lastIcebergSignalTime.get(price);

            if (lastSignalTimeAtPrice == null || (now - lastSignalTimeAtPrice) >= ICEBERG_COOLDOWN_MS) {
                int totalSize = ordersAtPrice.stream()
                    .mapToInt(id -> orders.getOrDefault(id, new OrderInfo()).size)
                    .sum();

                if (totalSize >= adaptiveSizeThreshold) {
                    lastIcebergSignalTime.put(price, now);

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
                    if (enableAITrading && aiIntegration != null && aiOrderManager != null) {
                        // Create SignalData for AI evaluation
                        SignalData signalData = createSignalData(isBid, price, totalSize);

                        // Evaluate with AI (asynchronous)
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

        // Show popup dialog only for manual reports
        if (showPopup) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(statsPanel,
                    report.toString(),
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
}
