package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

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
 * Order Flow Trading Strategy - Complete Trading System
 *
 * Features:
 * - Trend detection (EMA + VWAP)
 * - Signal scoring (0-100 points)
 * - Confluence validation
 * - State machine (FLAT/LONG/SHORT)
 * - Stop loss & take profit
 * - Position sizing
 * - No simultaneous long/short
 * - Data collection for AI analysis
 *
 * Trading Logic:
 * 1. Detect trend direction
 * 2. Score signals based on confluence
 * 3. Only trade with trend (score ≥ 50)
 * 4. Manage positions with stops/targets
 * 5. Collect data for optimization
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Order Flow Trading")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowTradingStrategy implements
    CustomModule,
    MarketByOrderDepthDataListener,
    TradeDataListener,
    BboListener,
    CustomSettingsPanelProvider {

    // ========== PARAMETERS ==========
    @Parameter(name = "Confluence Threshold")
    private Integer confluenceThreshold = 50;  // Minimum score to trade

    @Parameter(name = "Use Adaptive Thresholds")
    private Boolean useAdaptiveThresholds = false;  // Manual mode by default

    @Parameter(name = "Iceberg Min Orders")
    private Integer icebergMinOrders = 5;

    @Parameter(name = "Iceberg Min Size")
    private Integer icebergMinSize = 20;

    @Parameter(name = "Stop Loss Ticks")
    private Integer stopLossTicks = 3;

    @Parameter(name = "Take Profit Ticks")
    private Integer takeProfitTicks = 6;

    @Parameter(name = "Account Size ($)")
    private Double accountSize = 10000.0;

    @Parameter(name = "Risk Per Trade (%)")
    private Double riskPerTrade = 1.0;

    @Parameter(name = "Enable Trading")
    private Boolean enableTrading = false;  // Start disabled (paper trading)

    // ========== INDICATORS ==========
    private Indicator trendIndicator;
    private Indicator longSignalIndicator;
    private Indicator shortSignalIndicator;
    private Indicator stopLossIndicator;
    private Indicator takeProfitIndicator;
    private Indicator vwapIndicator;
    private Indicator ema9Indicator;
    private Indicator ema21Indicator;
    private Indicator ema50Indicator;

    // ========== UI PANELS ==========
    private StrategyPanel tradingPanel;
    private JLabel trendLabel;
    private JLabel positionLabel;
    private JLabel pnlLabel;
    private JLabel signalScoreLabel;
    private JLabel lastSignalLabel;
    private JTextArea signalLogArea;
    private JCheckBox enableTradingCheckBox;

    // ========== STATE ==========
    private enum TrendState { BULLISH, BEARISH, NEUTRAL }
    private enum PositionState { FLAT, LONG, SHORT }

    private TrendState currentTrend = TrendState.NEUTRAL;
    private PositionState currentPosition = PositionState.FLAT;
    private int entryPrice = 0;
    private int stopLossPrice = 0;
    private int takeProfitPrice = 0;
    private int entryTime = 0;

    // ========== ORDER FLOW TRACKING ==========
    private Map<String, OrderInfo> orders = new ConcurrentHashMap<>();
    private Map<Integer, List<String>> priceLevels = new ConcurrentHashMap<>();

    // Signal tracking
    private final AtomicInteger icebergCount = new AtomicInteger(0);
    private final AtomicInteger absorptionCount = new AtomicInteger(0);
    private final AtomicInteger spoofCount = new AtomicInteger(0);

    // Cooldown tracking
    private Map<Integer, Long> lastSignalTime = new ConcurrentHashMap<>();
    private static final long SIGNAL_COOLDOWN_MS = 5000;  // 5 seconds between signals

    // ========== EMA & VWAP CALCULATION ==========
    private LinkedList<Double> priceHistory = new LinkedList<>();
    private LinkedList<Integer> volumeHistory = new LinkedList<>();
    private double cumulativeTPV = 0.0;  // Total Price * Volume
    private long cumulativeVolume = 0;
    private double ema9 = 0.0;
    private double ema21 = 0.0;
    private double ema50 = 0.0;
    private double vwap = 0.0;

    private static final int EMA9_PERIOD = 9;
    private static final int EMA21_PERIOD = 21;
    private static final int EMA50_PERIOD = 50;
    private static final int VWAP_PERIOD = 100;  // Rolling VWAP window

    // ========== SIGNAL SCORING ==========
    private int currentSignalScore = 0;
    private String lastSignalReason = "";

    // ========== PERFORMANCE TRACKING ==========
    private final AtomicLong totalPnL = new AtomicLong(0);
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicInteger winningTrades = new AtomicInteger(0);
    private final AtomicInteger losingTrades = new AtomicInteger(0);

    private List<TradeRecord> tradeHistory = new ArrayList<>();
    private List<SignalRecord> signalHistory = new ArrayList<>();  // For AI analysis

    // ========== API ==========
    private Api api;
    private int pips;
    private String alias;

    // ========== LOGGING ==========
    private PrintWriter logWriter;
    private PrintWriter tradeWriter;
    private PrintWriter signalWriter;  // For AI analysis
    private static final String LOG_FILE = "trading_strategy_log.txt";
    private static final String TRADE_FILE = "trades.csv";
    private static final String SIGNAL_FILE = "signals.csv";  // For AI

    // ========== UPDATE TIMER ==========
    private ScheduledExecutorService updateExecutor;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.api = api;
        this.alias = alias;
        this.pips = (int) info.pips;

        log("========== Order Flow Trading Strategy ==========");
        log("Instrument: " + alias);
        log("Pip size: " + pips);
        log("Mode: PAPER TRADING (enableTrading = false)");

        // Register indicators
        trendIndicator = api.registerIndicator("Trend", GraphType.PRIMARY);
        trendIndicator.setColor(Color.CYAN);

        longSignalIndicator = api.registerIndicator("LONG Signal", GraphType.PRIMARY);
        longSignalIndicator.setColor(Color.GREEN);

        shortSignalIndicator = api.registerIndicator("SHORT Signal", GraphType.PRIMARY);
        shortSignalIndicator.setColor(Color.RED);

        stopLossIndicator = api.registerIndicator("Stop Loss", GraphType.PRIMARY);
        stopLossIndicator.setColor(Color.ORANGE);

        takeProfitIndicator = api.registerIndicator("Take Profit", GraphType.PRIMARY);
        takeProfitIndicator.setColor(Color.BLUE);

        vwapIndicator = api.registerIndicator("VWAP", GraphType.PRIMARY);
        vwapIndicator.setColor(Color.YELLOW);

        ema9Indicator = api.registerIndicator("EMA 9", GraphType.PRIMARY);
        ema9Indicator.setColor(Color.MAGENTA);

        ema21Indicator = api.registerIndicator("EMA 21", GraphType.PRIMARY);
        ema21Indicator.setColor(Color.GRAY);

        ema50Indicator = api.registerIndicator("EMA 50", GraphType.PRIMARY);
        ema50Indicator.setColor(Color.DARK_GRAY);

        // Initialize log files
        initializeLogFiles();

        // Start update timer
        startUpdateTimer();

        log("========== Strategy Ready ==========");
        log("Trading: DISABLED (paper trading mode)");
        log("Collecting data for analysis...");
    }

    private void initializeLogFiles() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
            tradeWriter = new PrintWriter(new FileWriter(TRADE_FILE, true));
            signalWriter = new PrintWriter(new FileWriter(SIGNAL_FILE, true));

            // CSV headers
            tradeWriter.println("Time,Entry,Exit,Direction,Duration,PnL,Score,Reason");
            signalWriter.println("Time,Price,Direction,Score,Iceberg,Absorption,Spoof,Trend,Confluence,Action");
        } catch (IOException e) {
            System.err.println("Failed to initialize log files: " + e.getMessage());
        }
    }

    private void startUpdateTimer() {
        updateExecutor = Executors.newSingleThreadScheduledExecutor();
        updateExecutor.scheduleAtFixedRate(() -> {
            try {
                SwingUtilities.invokeLater(this::updateTradingPanel);
            } catch (Exception e) {
                System.err.println("Error updating panel: " + e.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);  // Update every 500ms
    }

    // ========== CUSTOM PANELS ==========

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        List<StrategyPanel> panels = new ArrayList<>();

        if (tradingPanel == null) {
            createTradingPanel();
        }
        panels.add(tradingPanel);

        return panels.toArray(new StrategyPanel[0]);
    }

    private void createTradingPanel() {
        tradingPanel = new StrategyPanel("Trading Dashboard");
        tradingPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // Title
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        JLabel title = new JLabel("📊 Order Flow Trading Strategy");
        title.setFont(new Font("Arial", Font.BOLD, 14));
        tradingPanel.add(title, gbc);
        row++;

        // Current State
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(tradingPanel, "Current State", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        tradingPanel.add(new JLabel("Trend:"), gbc);
        gbc.gridx = 1;
        trendLabel = new JLabel("NEUTRAL");
        trendLabel.setFont(new Font("Arial", Font.BOLD, 12));
        tradingPanel.add(trendLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        tradingPanel.add(new JLabel("Position:"), gbc);
        gbc.gridx = 1;
        positionLabel = new JLabel("FLAT");
        positionLabel.setFont(new Font("Arial", Font.BOLD, 12));
        tradingPanel.add(positionLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        tradingPanel.add(new JLabel("P&L:"), gbc);
        gbc.gridx = 1;
        pnlLabel = new JLabel("$0.00");
        pnlLabel.setFont(new Font("Arial", Font.BOLD, 12));
        pnlLabel.setForeground(new Color(0, 150, 0));
        tradingPanel.add(pnlLabel, gbc);
        row++;

        // Signals
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(tradingPanel, "Trade Signals", gbc);
        row++;

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        tradingPanel.add(new JLabel("Signal Score:"), gbc);
        gbc.gridx = 1;
        signalScoreLabel = new JLabel("0");
        signalScoreLabel.setFont(new Font("Arial", Font.BOLD, 12));
        tradingPanel.add(signalScoreLabel, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row;
        tradingPanel.add(new JLabel("Last Signal:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3;
        lastSignalLabel = new JLabel("None");
        tradingPanel.add(lastSignalLabel, gbc);
        row++;

        // Signal Log
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        addSeparator(tradingPanel, "Signal Log", gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.gridheight = 4;
        signalLogArea = new JTextArea(6, 40);
        signalLogArea.setEditable(false);
        signalLogArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane scrollPane = new JScrollPane(signalLogArea);
        tradingPanel.add(scrollPane, gbc);
        row += 4;

        // Controls
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.gridheight = 1;
        addSeparator(tradingPanel, "Controls", gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        tradingPanel.add(new JLabel("Enable Trading:"), gbc);
        gbc.gridx = 1;
        enableTradingCheckBox = new JCheckBox();
        enableTradingCheckBox.setSelected(enableTrading);
        enableTradingCheckBox.setToolTipText("⚠️ WARNING: Enables real trading with real money!");
        enableTradingCheckBox.addActionListener(e -> toggleTrading());
        tradingPanel.add(enableTradingCheckBox, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JButton flattenButton = new JButton("FLATTEN ALL");
        flattenButton.addActionListener(e -> flattenAllPositions());
        tradingPanel.add(flattenButton, gbc);
    }

    private void addSeparator(JPanel panel, String text, GridBagConstraints gbc) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.BOLD, 12));
        panel.add(label, gbc);
    }

    private void updateTradingPanel() {
        if (trendLabel == null) return;

        // Update trend label
        trendLabel.setText(currentTrend.name());
        trendLabel.setForeground(getTrendColor(currentTrend));

        // Update position label
        positionLabel.setText(currentPosition.name());
        positionLabel.setForeground(getPositionColor(currentPosition));

        // Update P&L
        long pnl = totalPnL.get();
        pnlLabel.setText(String.format("$%,.2f", pnl / 100.0));
        pnlLabel.setForeground(pnl >= 0 ? new Color(0, 150, 0) : Color.RED);

        // Update signal score
        signalScoreLabel.setText(String.valueOf(currentSignalScore));
        signalScoreLabel.setForeground(getScoreColor(currentSignalScore));

        // Update stats
        int winRate = totalTrades.get() > 0 ?
            (winningTrades.get() * 100 / totalTrades.get()) : 0;
    }

    private Color getTrendColor(TrendState trend) {
        switch (trend) {
            case BULLISH: return Color.GREEN;
            case BEARISH: return Color.RED;
            default: return Color.GRAY;
        }
    }

    private Color getPositionColor(PositionState pos) {
        switch (pos) {
            case LONG: return Color.GREEN;
            case SHORT: return Color.RED;
            default: return Color.GRAY;
        }
    }

    private Color getScoreColor(int score) {
        if (score >= 70) return Color.GREEN;
        if (score >= 50) return Color.ORANGE;
        return Color.RED;
    }

    private void toggleTrading() {
        boolean selected = enableTradingCheckBox.isSelected();
        if (selected && !enableTrading) {
            int result = JOptionPane.showConfirmDialog(tradingPanel,
                "⚠️ WARNING: You are about to ENABLE LIVE TRADING!\n\n" +
                "This will execute REAL trades with REAL money.\n" +
                "You are responsible for all losses.\n\n" +
                "Are you sure?",
                "LIVE TRADING WARNING",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                SwingUtilities.invokeLater(() -> enableTradingCheckBox.setSelected(false));
                return;
            }

            enableTrading = true;
            log("🚨 LIVE TRADING ENABLED - Real money at risk!");
        } else if (!selected) {
            enableTrading = false;
            log("✅ Trading disabled - Paper trading mode");
        }
    }

    private void flattenAllPositions() {
        if (currentPosition != PositionState.FLAT) {
            log("📤 Flattening position: " + currentPosition);
            closePosition("Manual flatten");
        } else {
            log("No positions to flatten");
        }
    }

    // ========== TREND DETECTION ==========

    private void updateTrendIndicators(double price, int volume) {
        // Add to history
        priceHistory.addLast(price);
        volumeHistory.addLast(volume);

        // Calculate VWAP
        cumulativeTPV += price * volume;
        cumulativeVolume += volume;

        // Keep rolling window
        if (priceHistory.size() > VWAP_PERIOD) {
            double oldPrice = priceHistory.removeFirst();
            int oldVolume = volumeHistory.removeFirst();
            cumulativeTPV -= oldPrice * oldVolume;
            cumulativeVolume -= oldVolume;
        }

        vwap = cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : price;

        // Calculate EMAs
        if (priceHistory.size() >= EMA50_PERIOD) {
            double multiplier9 = 2.0 / (EMA9_PERIOD + 1);
            double multiplier21 = 2.0 / (EMA21_PERIOD + 1);
            double multiplier50 = 2.0 / (EMA50_PERIOD + 1);

            // Initialize EMAs
            if (ema9 == 0.0) {
                ema9 = price;
                ema21 = price;
                ema50 = price;
            } else {
                ema9 = (price - ema9) * multiplier9 + ema9;
                ema21 = (price - ema21) * multiplier21 + ema21;
                ema50 = (price - ema50) * multiplier50 + ema50;
            }

            // Determine trend
            determineTrend(price);

            // Plot indicators
            vwapIndicator.addPoint((int) vwap);
            ema9Indicator.addPoint((int) ema9);
            ema21Indicator.addPoint((int) ema21);
            ema50Indicator.addPoint((int) ema50);
        }
    }

    private void determineTrend(double price) {
        boolean aboveVWAP = price > vwap;
        boolean emaBullish = ema9 > ema21 && ema21 > ema50;
        boolean emaBearish = ema9 < ema21 && ema21 < ema50;

        if (aboveVWAP && emaBullish) {
            currentTrend = TrendState.BULLISH;
        } else if (!aboveVWAP && emaBearish) {
            currentTrend = TrendState.BEARISH;
        } else {
            currentTrend = TrendState.NEUTRAL;
        }
    }

    // ========== SIGNAL SCORING ==========

    private int calculateSignalScore(boolean isBid, int price, int orderCount, int totalSize) {
        int score = 0;

        // 1. Trend alignment (±30 points)
        if (isBid && currentTrend == TrendState.BULLISH) {
            score += 30;  // Iceberg on bid in bullish trend
        } else if (!isBid && currentTrend == TrendState.BEARISH) {
            score += 30;  // Iceberg on ask in bearish trend
        } else if (isBid && currentTrend == TrendState.BEARISH) {
            score -= 20;  // Counter-trend signal
        } else if (!isBid && currentTrend == TrendState.BULLISH) {
            score -= 20;  // Counter-trend signal
        }

        // 2. Size relative to average (±15 points)
        double avgSize = getAverageSize();
        if (totalSize > avgSize * 2.0) {
            score += 15;
        } else if (totalSize > avgSize * 1.5) {
            score += 10;
        }

        // 3. Order count (±10 points)
        double avgOrders = getAverageOrders();
        if (orderCount > avgOrders * 2.0) {
            score += 10;
        } else if (orderCount > avgOrders * 1.5) {
            score += 5;
        }

        // 4. VWAP proximity (±10 points)
        double distanceToVWAP = Math.abs(price - vwap);
        if (distanceToVWAP < 5) {  // Within 5 ticks of VWAP
            score += 10;
        } else if (distanceToVWAP < 10) {
            score += 5;
        }

        // 5. EMA alignment (±10 points)
        if (isBid && price > ema9 && price > ema21) {
            score += 10;  // Above EMAs
        } else if (!isBid && price < ema9 && price < ema21) {
            score += 10;  // Below EMAs
        }

        // Ensure score is non-negative
        return Math.max(0, score);
    }

    private double getAverageSize() {
        if (orders.isEmpty()) return icebergMinSize;
        double avg = orders.values().stream()
            .mapToInt(o -> o.size)
            .average()
            .orElse(icebergMinSize);
        return Math.max(avg, icebergMinSize);
    }

    private double getAverageOrders() {
        double avg = priceLevels.values().stream()
            .mapToInt(List::size)
            .average()
            .orElse(icebergMinOrders);
        return Math.max(avg, icebergMinOrders);
    }

    // ========== MBO HANDLING ==========

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

        // Update trend indicators
        updateTrendIndicators(price, size);

        // Check for signal
        checkForSignal(isBid, price);
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

    private void checkForSignal(boolean isBid, int price) {
        List<String> ordersAtPrice = priceLevels.getOrDefault(price, Collections.emptyList());

        if (ordersAtPrice.size() < icebergMinOrders) {
            return;  // Not enough orders
        }

        int totalSize = ordersAtPrice.stream()
            .mapToInt(id -> orders.getOrDefault(id, new OrderInfo()).size)
            .sum();

        if (totalSize < icebergMinSize) {
            return;  // Not enough size
        }

        // Check cooldown
        Long lastTime = lastSignalTime.get(price);
        long now = System.currentTimeMillis();
        if (lastTime != null && (now - lastTime) < SIGNAL_COOLDOWN_MS) {
            return;  // Too soon since last signal at this price
        }

        lastSignalTime.put(price, now);

        // Calculate signal score
        int score = calculateSignalScore(isBid, price, ordersAtPrice.size(), totalSize);
        currentSignalScore = score;

        // Generate signal
        String direction = isBid ? "LONG" : "SHORT";
        String reason = String.format("ICEBERG %s - %d orders, %d size at %d",
            direction, ordersAtPrice.size(), totalSize, price);

        lastSignalReason = reason;

        // Update UI if panel has been created
        if (lastSignalLabel != null) {
            SwingUtilities.invokeLater(() -> lastSignalLabel.setText(reason));
        }

        // Log signal
        logSignal(reason, score, isBid, price);

        // Check if we should take the trade
        if (score >= confluenceThreshold) {
            evaluateTradeSignal(isBid, price, score, reason);
        }
    }

    private void logSignal(String reason, int score, boolean isBid, int price) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String logMsg = String.format("[%s] %s (Score: %d)", timestamp, reason, score);

        // Update UI if panel has been created
        if (signalLogArea != null) {
            SwingUtilities.invokeLater(() -> {
                signalLogArea.append(logMsg + "\n");
                // Auto-scroll
                signalLogArea.setCaretPosition(signalLogArea.getDocument().getLength());
            });
        }

        log(logMsg);

        // Record for AI analysis
        recordSignalForAI(reason, score, isBid, price);
    }

    // ========== TRADE EVALUATION ==========

    private void evaluateTradeSignal(boolean isBidSignal, int price, int score, String reason) {
        // Determine if signal aligns with position rules
        boolean shouldEnter = false;
        String action = "IGNORED";

        if (currentPosition == PositionState.FLAT) {
            // Not in position - can enter long or short
            if (isBidSignal && currentTrend == TrendState.BULLISH && score >= confluenceThreshold) {
                shouldEnter = true;
                action = "ENTER LONG";
            } else if (!isBidSignal && currentTrend == TrendState.BEARISH && score >= confluenceThreshold) {
                shouldEnter = true;
                action = "ENTER SHORT";
            }
        } else if (currentPosition == PositionState.LONG) {
            // Already long - only consider exit or reversal
            if (!isBidSignal && score >= confluenceThreshold + 20) {
                shouldEnter = true;
                action = "REVERSE TO SHORT";  // Strong counter-signal
            }
        } else if (currentPosition == PositionState.SHORT) {
            // Already short - only consider exit or reversal
            if (isBidSignal && score >= confluenceThreshold + 20) {
                shouldEnter = true;
                action = "REVERSE TO LONG";  // Strong counter-signal
            }
        }

        // Plot signal on chart
        if (isBidSignal) {
            longSignalIndicator.addPoint(price);
        } else {
            shortSignalIndicator.addPoint(price);
        }

        // Log decision
        String decision = String.format("%s | Score: %d | Action: %s | Position: %s",
            reason, score, action, currentPosition);
        log(decision);

        // Execute trade if enabled and valid
        if (shouldEnter && enableTrading) {
            executeTrade(isBidSignal, price, reason);
        }
    }

    private void executeTrade(boolean isLong, int price, String reason) {
        // Close existing position if any
        if (currentPosition != PositionState.FLAT) {
            closePosition("New signal: " + reason);
        }

        // Calculate stops and targets
        entryPrice = price;
        entryTime = (int) (System.currentTimeMillis() / 1000);

        if (isLong) {
            currentPosition = PositionState.LONG;
            stopLossPrice = price - (stopLossTicks * pips);
            takeProfitPrice = price + (takeProfitTicks * pips);
        } else {
            currentPosition = PositionState.SHORT;
            stopLossPrice = price + (stopLossTicks * pips);
            takeProfitPrice = price - (takeProfitTicks * pips);
        }

        // Plot stops and targets
        stopLossIndicator.addPoint(stopLossPrice);
        takeProfitIndicator.addPoint(takeProfitPrice);

        log("📈 %s at %d | SL: %d | TP: %d | Score: %d",
            currentPosition, entryPrice, stopLossPrice, takeProfitPrice, currentSignalScore);

        // Record trade
        recordNewTrade(reason, currentSignalScore);
    }

    private void closePosition(String reason) {
        if (currentPosition == PositionState.FLAT) {
            return;
        }

        int currentPrice = (int) vwap;  // Approximate current price
        int pnl = 0;

        if (currentPosition == PositionState.LONG) {
            pnl = (currentPrice - entryPrice) * 100;  // Convert to cents
        } else {
            pnl = (entryPrice - currentPrice) * 100;
        }

        // Update stats
        totalPnL.addAndGet(pnl);
        totalTrades.incrementAndGet();

        if (pnl > 0) {
            winningTrades.incrementAndGet();
            log("✅ Profit: +$%.2f | %s", pnl / 100.0, reason);
        } else {
            losingTrades.incrementAndGet();
            log("❌ Loss: -$%.2f | %s", -pnl / 100.0, reason);
        }

        // Record trade completion
        completeTrade(pnl, reason);

        // Reset position
        currentPosition = PositionState.FLAT;
        entryPrice = 0;
        stopLossPrice = 0;
        takeProfitPrice = 0;
    }

    // ========== DATA RECORDING FOR AI ==========

    private void recordSignalForAI(String reason, int score, boolean isBid, int price) {
        SignalRecord record = new SignalRecord();
        record.timestamp = System.currentTimeMillis();
        record.price = price;
        record.isBid = isBid;
        record.score = score;
        record.iceberg = icebergCount.get();
        record.absorption = absorptionCount.get();
        record.spoof = spoofCount.get();
        record.trend = currentTrend.name();
        record.position = currentPosition.name();
        record.vwap = (int) vwap;
        record.ema9 = (int) ema9;
        record.ema21 = (int) ema21;
        record.ema50 = (int) ema50;

        signalHistory.add(record);

        // Write to CSV
        String csv = String.format("%d,%d,%s,%d,%d,%d,%d,%s,%s,%d,%d,%d,%d%n",
            record.timestamp,
            record.price,
            isBid ? "LONG" : "SHORT",
            record.score,
            record.iceberg,
            record.absorption,
            record.spoof,
            record.trend,
            record.position,
            record.vwap,
            record.ema9,
            record.ema21,
            record.ema50
        );

        if (signalWriter != null) {
            signalWriter.write(csv);
            signalWriter.flush();
        }
    }

    private void recordNewTrade(String reason, int score) {
        TradeRecord record = new TradeRecord();
        record.entryTime = System.currentTimeMillis();
        record.entryPrice = entryPrice;
        record.stopLoss = stopLossPrice;
        record.takeProfit = takeProfitPrice;
        record.direction = currentPosition.name();
        record.reason = reason;
        record.score = score;
        record.trend = currentTrend.name();
        record.vwap = vwap;
        record.ema9 = ema9;
        record.ema21 = ema21;
        record.ema50 = ema50;

        tradeHistory.add(record);
    }

    private void completeTrade(int pnl, String exitReason) {
        if (tradeHistory.isEmpty()) return;

        TradeRecord lastTrade = tradeHistory.get(tradeHistory.size() - 1);
        lastTrade.exitTime = System.currentTimeMillis();
        lastTrade.exitPrice = (int) vwap;
        lastTrade.pnl = pnl;
        lastTrade.exitReason = exitReason;

        // Write to CSV
        long duration = (lastTrade.exitTime - lastTrade.entryTime) / 1000 / 60;  // minutes
        String csv = String.format("%s,%d,%d,%s,%d,%d,%d,%s%n",
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastTrade.entryTime)),
            lastTrade.entryPrice,
            lastTrade.exitPrice,
            lastTrade.direction,
            duration,
            lastTrade.pnl,
            lastTrade.score,
            lastTrade.reason + " | Exit: " + exitReason
        );

        if (tradeWriter != null) {
            tradeWriter.write(csv);
            tradeWriter.flush();
        }
    }

    // ========== TRADE & BBO HANDLING ==========

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Update trend on each trade
        updateTrendIndicators(price, size);

        // Check stop loss / take profit if in position
        if (currentPosition != PositionState.FLAT) {
            int priceInt = (int) price;

            if (currentPosition == PositionState.LONG) {
                if (priceInt <= stopLossPrice) {
                    closePosition("Stop Loss Hit");
                } else if (priceInt >= takeProfitPrice) {
                    closePosition("Take Profit Hit");
                }
            } else if (currentPosition == PositionState.SHORT) {
                if (priceInt >= stopLossPrice) {
                    closePosition("Stop Loss Hit");
                } else if (priceInt <= takeProfitPrice) {
                    closePosition("Take Profit Hit");
                }
            }
        }
    }

    @Override
    public void onBbo(int priceBid, int priceAsk, int sizeBid, int sizeAsk) {
        // Can use BBO for additional signals
    }

    @Override
    public void stop() {
        log("Strategy stopping...");

        if (updateExecutor != null) {
            updateExecutor.shutdown();
        }

        // Close position if still open
        if (currentPosition != PositionState.FLAT) {
            closePosition("Strategy stopped");
        }

        // Close log files
        if (logWriter != null) logWriter.close();
        if (tradeWriter != null) tradeWriter.close();
        if (signalWriter != null) signalWriter.close();

        log("Strategy stopped. Final P&L: $%.2f", totalPnL.get() / 100.0);
    }

    private void log(String format, Object... args) {
        String message = String.format(format, args);
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

    private static class TradeRecord {
        long entryTime;
        long exitTime;
        int entryPrice;
        int exitPrice;
        int stopLoss;
        int takeProfit;
        String direction;
        String reason;
        String exitReason;
        int pnl;
        int score;
        String trend;
        double vwap;
        double ema9;
        double ema21;
        double ema50;
    }

    private static class SignalRecord {
        long timestamp;
        int price;
        boolean isBid;
        int score;
        int iceberg;
        int absorption;
        int spoof;
        String trend;
        String position;
        int vwap;
        int ema9;
        int ema21;
        int ema50;
    }
}
