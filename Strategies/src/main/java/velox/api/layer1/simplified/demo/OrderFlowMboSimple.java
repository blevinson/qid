package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.io.FileWriter;
import javax.swing.JLabel;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Order Flow MBO Strategy - Simplified API Version
 *
 * REAL order flow signals using Market By Order data:
 * - ICEBERG: Hidden large orders showing as repeated small orders
 * - SPOOF: Fake large orders cancelled quickly
 * - ABSORPTION: Large trades eating levels
 *
 * Visual indicators on chart:
 * - Cyan dots = Iceberg signals
 * - Magenta dots = Spoof signals
 * - Yellow dots = Absorption signals
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Order Flow MBO")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowMboSimple implements
    CustomModule,
    MarketByOrderDepthDataListener,
    TradeDataListener,
    BboListener,
    CustomSettingsPanelProvider {

    // ========== PARAMETERS ==========
    @Parameter(name = "Iceberg Min Orders")
    private Integer icebergMinOrders = 5;  // Starting threshold (adaptive will adjust)

    @Parameter(name = "Spoof Max Age (ms)")
    private Integer spoofMaxAge = 500;

    @Parameter(name = "Spoof Min Size")
    private Integer spoofMinSize = 5;

    @Parameter(name = "Absorption Min Size")
    private Integer absorptionMinSize = 10;  // Adaptive based on instrument

    // ========== INDICATORS ==========
    private Indicator icebergBidIndicator;  // Green BUY signals
    private Indicator icebergAskIndicator;  // Red SELL signals
    private Indicator spoofIndicator;       // Orange FADE signals
    private Indicator absorptionIndicator;  // Yellow FADE signals

    // ========== CUSTOM PANEL ==========
    private StrategyPanel statusPanel;
    private JLabel icebergCountLabel;
    private JLabel spoofCountLabel;
    private JLabel absorptionCountLabel;
    private JLabel statusLabel;

    // ========== STATE ==========
    private Map<String, OrderInfo> orders = new HashMap<>();
    private Map<Integer, List<String>> priceLevels = new HashMap<>();

    private int icebergCount = 0;
    private int spoofCount = 0;
    private int absorptionCount = 0;

    // Signal cooldown to prevent too many signals
    private Map<Integer, Long> lastIcebergSignalTime = new HashMap<>();
    private static final long ICEBERG_COOLDOWN_MS = 10000;  // 10 seconds between signals at same price

    // Track last signal direction to prevent flip-flopping
    private String lastSignalDirection = null;
    private static final long SIGNAL_DIRECTION_COOLDOWN_MS = 30000;  // 30 seconds before reversing direction

    private int tradeCount = 0;
    private int mboEventCount = 0;
    private double pips;

    // ========== ADAPTIVE THRESHOLDS ==========
    // Track "normal" order flow for this instrument
    private int totalOrdersSeen = 0;
    private int totalSizeSeen = 0;
    private double avgOrderSize = 1.0;
    private int maxOrdersAtPrice = 0;
    private int maxSizeAtPrice = 0;

    // Rolling window of recent activity (last 100 events)
    private static final int HISTORY_WINDOW = 100;
    private java.util.LinkedList<Integer> recentOrderCounts = new java.util.LinkedList<>();
    private java.util.LinkedList<Integer> recentTotalSizes = new java.util.LinkedList<>();

    // File logging
    private PrintWriter logWriter;
    private String logPath = "/Users/brant/bl-projects/DemoStrategies/mbo_simple_log.txt";

    // Signal file for SSP reader
    private PrintWriter signalWriter;
    private String signalPath = "/Users/brant/bl-projects/DemoStrategies/mbo_signals.txt";

    private class OrderInfo {
        String orderId;
        boolean isBid;
        int price;
        int size;
        long creationTime;

        OrderInfo(String orderId, boolean isBid, int price, int size) {
            this.orderId = orderId;
            this.isBid = isBid;
            this.price = price;
            this.size = size;
            this.creationTime = System.currentTimeMillis();
        }

        long getAge() {
            return System.currentTimeMillis() - creationTime;
        }
    }

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        log("========== OrderFlowMboSimple.initialize() START ==========");
        log("Alias: " + alias);
        log("Pips: " + info.pips);
        this.pips = info.pips;

        try {
            logWriter = new PrintWriter(new FileWriter(logPath, false));
            log("File logging initialized: " + logPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            signalWriter = new PrintWriter(new FileWriter(signalPath, false));
            log("Signal file initialized: " + signalPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Register indicators - these WILL show on chart!
        icebergBidIndicator = api.registerIndicator("Iceberg BID (BUY)", GraphType.PRIMARY);
        icebergAskIndicator = api.registerIndicator("Iceberg ASK (SELL)", GraphType.PRIMARY);
        spoofIndicator = api.registerIndicator("Spoof (FADE)", GraphType.PRIMARY);
        absorptionIndicator = api.registerIndicator("Absorption (FADE)", GraphType.PRIMARY);

        icebergBidIndicator.setColor(Color.GREEN);      // Green = BUY
        icebergAskIndicator.setColor(Color.RED);        // Red = SELL
        spoofIndicator.setColor(Color.ORANGE);          // Orange = Fade it
        absorptionIndicator.setColor(Color.YELLOW);     // Yellow = Fade

        // Create custom panel on EDT thread to avoid EDT violation
        javax.swing.SwingUtilities.invokeLater(() -> {
            createStatusPanel();
        });

        log("========== OrderFlowMboSimple.initialize() COMPLETE ==========");
        log("MBO Interface: MarketByOrderDepthDataListener REGISTERED");
        log("Waiting for MBO events (send/replace/cancel)...");
        log("If you see 0 MBO events after 100+ trades, MBO data is NOT available from this feed!");
    }

    private void createStatusPanel() {
        statusPanel = new StrategyPanel("Order Flow MBO Signals");
        statusPanel.setLayout(new java.awt.GridLayout(5, 2, 5, 5));

        statusLabel = new JLabel("Active");
        icebergCountLabel = new JLabel("0");
        spoofCountLabel = new JLabel("0");
        absorptionCountLabel = new JLabel("0");

        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        statusPanel.add(new JLabel("BUY Signals (Green):"));
        statusPanel.add(icebergCountLabel);
        statusPanel.add(new JLabel("FADE Signals (Orange):"));
        statusPanel.add(spoofCountLabel);
        statusPanel.add(new JLabel("ABSORPTION (Yellow):"));
        statusPanel.add(absorptionCountLabel);
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        if (statusPanel == null) {
            createStatusPanel();
        }
        return new StrategyPanel[] { statusPanel };
    }

    @Override
    public void stop() {
        log("OrderFlowMboSimple stopped");
        if (logWriter != null) {
            logWriter.close();
        }
        if (signalWriter != null) {
            signalWriter.close();
        }
    }

    // ========== MBO ORDER TRACKING ==========
    // This is where the REAL order flow detection happens!

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        log("MBO SEND: " + (isBid ? "BID" : "ASK") + " " + orderId +
            " Price=" + price + " Size=" + size);
        mboEventCount++;

        // Track order
        OrderInfo order = new OrderInfo(orderId, isBid, price, size);
        orders.put(orderId, order);

        // Track orders at this price level
        priceLevels.computeIfAbsent(price, k -> new ArrayList<>()).add(orderId);

        // CHECK FOR ICEBERG PATTERN
        List<String> ordersAtPrice = priceLevels.get(price);
        if (ordersAtPrice != null && ordersAtPrice.size() >= icebergMinOrders) {

            // Check cooldown - prevent multiple signals at same price within 30 seconds
            Long lastSignalTime = lastIcebergSignalTime.get(price);
            long currentTime = System.currentTimeMillis();

            if (lastSignalTime != null && (currentTime - lastSignalTime) < ICEBERG_COOLDOWN_MS) {
                // Too soon since last signal at this price - skip it
                return;
            }

            // Check direction cooldown - prevent rapid BUY/SELL flip-flopping
            if (lastSignalDirection != null) {
                long timeSinceLastSignal = currentTime - Long.parseLong(lastSignalDirection.split(":")[1]);
                String lastDir = lastSignalDirection.split(":")[0];

                // If last signal was BUY and this is SELL, require longer cooldown
                if (lastDir.equals("BUY") && !isBid && timeSinceLastSignal < SIGNAL_DIRECTION_COOLDOWN_MS) {
                    log("❌ SKIP SELL - Too soon after BUY signal (" + timeSinceLastSignal + "ms ago)");
                    return;
                }
                // If last signal was SELL and this is BUY, require longer cooldown
                if (lastDir.equals("SELL") && isBid && timeSinceLastSignal < SIGNAL_DIRECTION_COOLDOWN_MS) {
                    log("❌ SKIP BUY - Too soon after SELL signal (" + timeSinceLastSignal + "ms ago)");
                    return;
                }
            }

            // Calculate total size at this level
            int totalSize = 0;
            for (String id : ordersAtPrice) {
                OrderInfo o = orders.get(id);
                if (o != null) {
                    totalSize += o.size;
                }
            }

            // Update adaptive statistics
            totalOrdersSeen++;
            totalSizeSeen += size;
            avgOrderSize = (double) totalSizeSeen / totalOrdersSeen;

            // Track max activity seen so far
            if (ordersAtPrice.size() > maxOrdersAtPrice) {
                maxOrdersAtPrice = ordersAtPrice.size();
            }
            if (totalSize > maxSizeAtPrice) {
                maxSizeAtPrice = totalSize;
            }

            // Add to rolling window
            recentOrderCounts.add(ordersAtPrice.size());
            recentTotalSizes.add(totalSize);
            if (recentOrderCounts.size() > HISTORY_WINDOW) {
                recentOrderCounts.removeFirst();
                recentTotalSizes.removeFirst();
            }

            // Calculate adaptive thresholds based on history
            double avgOrderCount = recentOrderCounts.stream().mapToInt(Integer::intValue).average().orElse(5.0);
            double avgTotalSize = recentTotalSizes.stream().mapToInt(Integer::intValue).average().orElse(25.0);

            // Only signal when we see 3x the normal activity
            int adaptiveOrderThreshold = (int) (avgOrderCount * 3.0);
            int adaptiveSizeThreshold = (int) (avgTotalSize * 3.0);

            // Minimum thresholds to avoid noise
            adaptiveOrderThreshold = Math.max(adaptiveOrderThreshold, 8);
            adaptiveSizeThreshold = Math.max(adaptiveSizeThreshold, 30);

            // Check if this is EXCEPTIONAL (3x normal activity)
            boolean isStrongSignal = ordersAtPrice.size() >= adaptiveOrderThreshold && totalSize >= adaptiveSizeThreshold;

            if (!isStrongSignal) {
                // Skip weak signals
                return;
            }

            String direction = isBid ? "BUY" : "SELL";
            log("🧊 ADAPTIVE " + direction + " SIGNAL: " + (isBid ? "BID" : "ASK") +
                " at " + price + " Orders=" + ordersAtPrice.size() + "/" + adaptiveOrderThreshold +
                " TotalSize=" + totalSize + "/" + adaptiveSizeThreshold +
                " (Avg: " + String.format("%.1f", avgOrderCount) + " orders, " +
                String.format("%.1f", avgTotalSize) + " size)");

            // GENERATE SIGNAL - Plot on chart!
            if (isBid) {
                icebergBidIndicator.addPoint(price);  // GREEN dot = BUY
            } else {
                icebergAskIndicator.addPoint(price);  // RED dot = SELL
            }

            // Remember this signal
            lastSignalDirection = direction + ":" + currentTime;

            // Write signal for SSP reader
            writeSignal("ICEBERG", isBid ? "BUY" : "SELL", price, totalSize);

            // Update cooldown timer
            lastIcebergSignalTime.put(price, currentTime);

            icebergCount++;
            updatePanel();
        }

        // Check for large order (potential absorption)
        if (size >= absorptionMinSize) {
            log("Large order: " + (isBid ? "BID" : "ASK") + " size=" + size);
        }
    }

    @Override
    public void replace(String orderId, int price, int size) {
        mboEventCount++;
        OrderInfo order = orders.get(orderId);
        if (order == null) return;

        int oldPrice = order.price;
        int oldSize = order.size;

        // Update order
        order.price = price;
        order.size = size;

        // Update price level tracking
        if (oldPrice != price) {
            List<String> oldLevel = priceLevels.get(oldPrice);
            if (oldLevel != null) {
                oldLevel.remove(orderId);
            }
            priceLevels.computeIfAbsent(price, k -> new ArrayList<>()).add(orderId);
        }

        log("MBO REPLACE: " + orderId + " Price: " + oldPrice + "->" + price +
            " Size: " + oldSize + "->" + size);

        // Check if order is following market (smart money)
        boolean priceIncreased = price > oldPrice;
        boolean isBid = order.isBid;
        boolean isFollowing = isBid && priceIncreased || !isBid && !priceIncreased;

        if (isFollowing) {
            log("🎯 SMART MONEY: Order repositioning with market");
        }
    }

    @Override
    public void cancel(String orderId) {
        mboEventCount++;
        OrderInfo order = orders.get(orderId);
        if (order == null) return;

        long age = order.getAge();

        log("MBO CANCEL: " + orderId + " Price=" + order.price +
            " Size=" + order.size + " Age=" + age + "ms");

        // CHECK FOR SPOOFING
        if (order.size >= spoofMinSize && age < spoofMaxAge) {
            log("🎭 SPOOFING DETECTED: " + (order.isBid ? "BID (fake buy)" : "ASK (fake sell)") +
                " Size=" + order.size + " cancelled after " + age + "ms - FADE IT!");

            // GENERATE SIGNAL - Plot opposite direction (fade the spoof)
            spoofIndicator.addPoint(order.price);  // Orange dot = FADE

            // Write signal for SSP reader - OPPOSITE direction
            writeSignal("SPOOF", order.isBid ? "SELL" : "BUY", order.price, order.size);

            spoofCount++;
            updatePanel();
        }

        // Remove from tracking
        orders.remove(orderId);
        List<String> level = priceLevels.get(order.price);
        if (level != null) {
            level.remove(orderId);
        }
    }

    // ========== TRADE DATA FOR ABSORPTION DETECTION ==========

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        tradeCount++;

        // Log every 100 trades with MBO status
        if (tradeCount % 100 == 0) {
            log("Trade #" + tradeCount + ": Price=" + price + " Size=" + size +
                " | MBO events so far: " + mboEventCount);
        }

        // CHECK FOR ABSORPTION - Large trade hitting a level
        if (size >= absorptionMinSize) {
            log("ABSORPTION: Large trade size=" + size + " at " + price);

            // GENERATE SIGNAL - Plot on chart!
            absorptionIndicator.addPoint(price);

            // Write signal for SSP reader - Fade the move
            writeSignal("ABSORPTION", "FADE", (int)price, size);

            absorptionCount++;
            updatePanel();
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Can use BBO for additional analysis if needed
    }

    private void updatePanel() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            icebergCountLabel.setText(String.valueOf(icebergCount));
            spoofCountLabel.setText(String.valueOf(spoofCount));
            absorptionCountLabel.setText(String.valueOf(absorptionCount));
            statusLabel.setText("Active");
        });
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String logMessage = timestamp + " - " + message;
        System.err.println(logMessage);

        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }

    private void writeSignal(String type, String direction, int price, int size) {
        if (signalWriter != null) {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            String signal = String.format("%s|%s|%d|%d", type, direction, price, size);
            signalWriter.println(signal);
            signalWriter.flush();
        }
    }
}
