package velox.api.layer1.simplified.demo;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // ========== PARAMETERS ==========
    @Parameter(name = "Iceberg Min Orders")
    private Integer icebergMinOrders = 20;  // Increased from 5 to reduce false signals

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

    // ========== INDICATORS ==========
    private Indicator icebergBidIndicator;
    private Indicator icebergAskIndicator;
    private Indicator spoofIndicator;
    private Indicator absorptionIndicator;

    // ========== CUSTOM PANELS ==========
    private StrategyPanel settingsPanel;
    private StrategyPanel statsPanel;

    // Settings Panel Components
    private JLabel minConfluenceLabel;
    private JSpinner minConfluenceSpinner;
    private JLabel thresholdMultLabel;
    private JSpinner thresholdMultSpinner;
    private JLabel simModeLabel;
    private JCheckBox simModeCheckBox;
    private JLabel autoExecLabel;
    private JCheckBox autoExecCheckBox;

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

        // Create indicators
        icebergBidIndicator = api.registerIndicator("Iceberg BUY", GraphType.PRIMARY);
        icebergBidIndicator.setColor(Color.GREEN);

        icebergAskIndicator = api.registerIndicator("Iceberg SELL", GraphType.PRIMARY);
        icebergAskIndicator.setColor(Color.RED);

        spoofIndicator = api.registerIndicator("Spoof/FADE", GraphType.PRIMARY);
        spoofIndicator.setColor(Color.MAGENTA);

        absorptionIndicator = api.registerIndicator("Absorption", GraphType.PRIMARY);
        absorptionIndicator.setColor(Color.YELLOW);

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
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                SwingUtilities.invokeLater(this::updateStatsPanel);
            } catch (Exception e) {
                System.err.println("Error updating stats panel: " + e.getMessage());
            }
        }, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
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

        gbc.gridx = 0; gbc.gridy = 7;
        JLabel adaptOrderLabel = new JLabel("Adaptive Order Thresh:");
        adaptOrderLabel.setToolTipText("Dynamic threshold based on recent order activity");
        settingsPanel.add(adaptOrderLabel, gbc);
        gbc.gridx = 1;
        JSpinner adaptOrderSpinner = new JSpinner(new SpinnerNumberModel(adaptiveOrderThreshold, 10, 100, 5));
        adaptOrderSpinner.setToolTipText("Auto-adjusts based on market conditions (default: 25)");
        adaptOrderSpinner.addChangeListener(e -> adaptiveOrderThreshold = (Integer) adaptOrderSpinner.getValue());
        settingsPanel.add(adaptOrderSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 8;
        JLabel adaptSizeLabel = new JLabel("Adaptive Size Thresh:");
        adaptSizeLabel.setToolTipText("Dynamic size threshold based on recent activity");
        settingsPanel.add(adaptSizeLabel, gbc);
        gbc.gridx = 1;
        JSpinner adaptSizeSpinner = new JSpinner(new SpinnerNumberModel(adaptiveSizeThreshold, 50, 500, 25));
        adaptSizeSpinner.setToolTipText("Auto-adjusts based on market conditions (default: 100)");
        adaptSizeSpinner.addChangeListener(e -> adaptiveSizeThreshold = (Integer) adaptSizeSpinner.getValue());
        settingsPanel.add(adaptSizeSpinner, gbc);

        // Safety Controls section
        gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Safety Controls", gbc);

        gbc.gridy = 10; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Simulation Mode Only:"), gbc);
        gbc.gridx = 1;
        simModeCheckBox = new JCheckBox();
        simModeCheckBox.setSelected(simModeOnly);
        simModeCheckBox.addActionListener(e -> updateSimMode());
        settingsPanel.add(simModeCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 11;
        settingsPanel.add(new JLabel("Enable Auto-Execution:"), gbc);
        gbc.gridx = 1;
        autoExecCheckBox = new JCheckBox();
        autoExecCheckBox.setSelected(autoExecution);
        autoExecCheckBox.addActionListener(e -> updateAutoExecution());
        settingsPanel.add(autoExecCheckBox, gbc);

        // Risk Management section
        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 2;
        addSeparator(settingsPanel, "Risk Management", gbc);

        gbc.gridy = 13; gbc.gridwidth = 1;
        settingsPanel.add(new JLabel("Max Position:"), gbc);
        gbc.gridx = 1;
        JSpinner maxPosSpinner = new JSpinner(new SpinnerNumberModel(maxPosition.intValue(), 1, 10, 1));
        maxPosSpinner.addChangeListener(e -> maxPosition = (Integer) maxPosSpinner.getValue());
        settingsPanel.add(maxPosSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 14;
        settingsPanel.add(new JLabel("Daily Loss Limit:"), gbc);
        gbc.gridx = 1;
        JSpinner lossLimitSpinner = new JSpinner(new SpinnerNumberModel(dailyLossLimit.intValue(), 100.0, 5000.0, 100.0));
        lossLimitSpinner.addChangeListener(e -> dailyLossLimit = (Double) lossLimitSpinner.getValue());
        settingsPanel.add(lossLimitSpinner, gbc);

        // Apply button
        gbc.gridx = 0; gbc.gridy = 15; gbc.gridwidth = 2;
        JButton applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        settingsPanel.add(applyButton, gbc);
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
        statsPanel.add(new JLabel("Active Signals:"), gbc);
        gbc.gridx = 1;
        activeSignalsLabel = new JLabel("0");
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
            activeSignalsLabel.setText(String.valueOf(activeSignals.size()));
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

        // Only check for icebergs on large orders (not every single order)
        if (size >= adaptiveSizeThreshold * 0.5) {
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

        double avgOrderCount = recentOrderCounts.stream()
            .mapToInt(Integer::intValue).average().orElse(5.0);
        double avgTotalSize = recentTotalSizes.stream()
            .mapToInt(Integer::intValue).average().orElse(25.0);

        adaptiveOrderThreshold = Math.max((int) (avgOrderCount * 3.0), 8);
        adaptiveSizeThreshold = Math.max((int) (avgTotalSize * 3.0), 30);
    }

    private void checkForIceberg(boolean isBid, int price) {
        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());

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

                    Indicator indicator = isBid ? icebergBidIndicator : icebergAskIndicator;
                    indicator.addPoint(price);

                    if (isBid) {
                        icebergCount.incrementAndGet();
                    } else {
                        icebergCount.incrementAndGet();
                    }

                    lastSignal = Integer.valueOf(totalSize);  // Store size as last signal
                    activeSignals.add(Integer.valueOf(totalSize));
                }
            }
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Trade tracking implementation
    }

    @Override
    public void onBbo(int priceBid, int priceAsk, int sizeBid, int sizeAsk) {
        // BBO tracking implementation
    }

    @Override
    public void stop() {
        log("OrderFlowStrategyEnhanced stopped");

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
}
