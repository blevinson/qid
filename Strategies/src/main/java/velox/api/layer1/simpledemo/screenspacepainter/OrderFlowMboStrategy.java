package velox.api.layer1.simpledemo.screenspacepainter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.HorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativeDataHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativeDataVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.VerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;

/**
 * Order Flow Strategy with MBO (Market By Order) Detection
 *
 * Uses individual order tracking for real order flow signals:
 * - Absorption: Large orders absorbing aggressive volume
 * - Spoofing: Large orders cancelled quickly (fake liquidity)
 * - Iceberg: Repeated small orders at same price (hidden large order)
 * - Aggressive Flow: Market orders aggressively eating limit orders
 */
@Layer1Attachable
@Layer1StrategyName("Order Flow MBO Strategy")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowMboStrategy implements
    Layer1ApiFinishable,
    Layer1ApiAdminAdapter,
    Layer1ApiDataAdapter,
    ScreenSpacePainterFactory,
    MarketByOrderDepthDataListener {

    private static final String INDICATOR_NAME = "Order Flow MBO Signals";

    private Layer1ApiProvider provider;
    private Map<String, String> indicatorsFullNameToUserName = new HashMap<>();

    // File logging
    private PrintWriter logWriter;
    private String logPath = "/Users/brant/bl-projects/DemoStrategies/mbo_strategy_log.txt";

    // Icon caches
    private Map<Color, PreparedImage> buyIconCache = new HashMap<>();
    private Map<Color, PreparedImage> sellIconCache = new HashMap<>();

    // State per instrument
    private Map<String, InstrumentState> instrumentStates = new ConcurrentHashMap<>();

    // ========== PARAMETERS ==========
    private static final int ABSORPTION_MIN_SIZE = 100;        // Min size for absorption
    private static final int SPOOF_MAX_AGE_MS = 500;           // Max age for spoof detection (ms)
    private static final int SPOOF_MIN_SIZE = 200;             // Min size for spoof detection
    private static final int ICEBERG_MIN_ORDERS = 5;           // Min orders at same price for iceberg
    private static final int ICEBERG_PRICE_TOLERANCE = 0;      // Price tolerance for iceberg grouping
    private static final double ATR_MULTIPLIER = 1.5;          // ATR multiplier for stops
    private static final double RISK_REWARD_RATIO = 2.0;       // Risk/reward ratio

    private class InstrumentState {
        // Order tracking
        Map<String, OrderInfo> orders = new HashMap<>();
        Map<Integer, List<String>> priceLevels = new HashMap<>(); // price -> list of order IDs

        // Signal tracking
        List<TradeSignal> tradeSignals = new ArrayList<>();
        int signalCount = 0;

        // ATR calculation
        List<Double> priceHistory = new ArrayList<>();
        List<Double> trueRanges = new ArrayList<>();
        double atr = 0;

        // Trade detection
        int tradeCount = 0;
        double lastTradePrice = 0;
    }

    private class OrderInfo {
        String orderId;
        boolean isBid;
        int price;
        int size;
        long creationTime;
        long modificationTime;
        boolean wasCancelled;
        boolean wasReplaced;

        OrderInfo(String orderId, boolean isBid, int price, int size) {
            this.orderId = orderId;
            this.isBid = isBid;
            this.price = price;
            this.size = size;
            this.creationTime = System.currentTimeMillis();
            this.modificationTime = creationTime;
        }

        long getAge() {
            return System.currentTimeMillis() - creationTime;
        }
    }

    private class TradeSignal {
        long timestamp;
        double entryPrice;
        double takeProfit;
        double stopLoss;
        boolean isLong;
        String signalType;
        String reason;

        TradeSignal(long timestamp, double entryPrice, double takeProfit, double stopLoss,
                   boolean isLong, String signalType, String reason) {
            this.timestamp = timestamp;
            this.entryPrice = entryPrice;
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
            this.isLong = isLong;
            this.signalType = signalType;
            this.reason = reason;
        }
    }

    // Constructor
    public OrderFlowMboStrategy(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
        log("OrderFlowMboStrategy constructed");

        try {
            logWriter = new PrintWriter(new FileWriter(logPath, false));
            log("File logging initialized: " + logPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUserMessage(Object data) {
        if (data instanceof UserMessageLayersChainCreatedTargeted) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == OrderFlowMboStrategy.class) {
                addIndicator();
            }
        }
    }

    public void addIndicator() {
        Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(OrderFlowMboStrategy.class, INDICATOR_NAME)
                .setScreenSpacePainterFactory(this)
                .setIsAdd(true)
                .build();

        synchronized (indicatorsFullNameToUserName) {
            indicatorsFullNameToUserName.put(message.fullName, message.userName);
        }

        provider.sendUserMessage(message);
        log("SSP Indicator added: " + INDICATOR_NAME);
    }

    // ========== MBO ORDER TRACKING ==========

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        // Find instrument alias from context or track per instrument
        // For now, we'll track in a unified way and refine as needed

        log("MBO SEND: " + (isBid ? "BID" : "ASK") + " " + orderId + " Price=" + price + " Size=" + size);

        // Get or create state (using default alias for now)
        String alias = "DEFAULT";
        InstrumentState state = instrumentStates.computeIfAbsent(alias, k -> new InstrumentState());

        // Create order info
        OrderInfo order = new OrderInfo(orderId, isBid, price, size);
        state.orders.put(orderId, order);

        // Track orders at this price level
        state.priceLevels.computeIfAbsent(price, k -> new ArrayList<>()).add(orderId);

        // Check for iceberg pattern (many small orders at same price)
        checkIcebergPattern(alias, state, price, isBid);

        // Check for large order (potential absorption)
        if (size >= ABSORPTION_MIN_SIZE) {
            log("LARGE ORDER DETECTED: " + (isBid ? "BID" : "ASK") + " size=" + size + " at " + price);
        }
    }

    @Override
    public void replace(String orderId, int price, int size) {
        String alias = "DEFAULT";
        InstrumentState state = instrumentStates.get(alias);
        if (state == null) return;

        OrderInfo order = state.orders.get(orderId);
        if (order == null) return;

        int oldPrice = order.price;
        int oldSize = order.size;
        boolean oldIsBid = order.isBid;

        // Update order
        order.price = price;
        order.size = size;
        order.modificationTime = System.currentTimeMillis();
        order.wasReplaced = true;

        // Update price level tracking
        if (oldPrice != price) {
            List<String> oldLevel = state.priceLevels.get(oldPrice);
            if (oldLevel != null) {
                oldLevel.remove(orderId);
            }
            state.priceLevels.computeIfAbsent(price, k -> new ArrayList<>()).add(orderId);
        }

        log("MBO REPLACE: " + orderId + " Price: " + oldPrice + "->" + price + " Size: " + oldSize + "->" + size);

        // Check if order is following market (smart money)
        checkOrderFollowingMarket(alias, state, order, oldPrice, price);
    }

    @Override
    public void cancel(String orderId) {
        String alias = "DEFAULT";
        InstrumentState state = instrumentStates.get(alias);
        if (state == null) return;

        OrderInfo order = state.orders.get(orderId);
        if (order == null) return;

        order.wasCancelled = true;
        long age = order.getAge();

        log("MBO CANCEL: " + orderId + " Price=" + order.price + " Size=" + order.size + " Age=" + age + "ms");

        // Check for spoofing (large order cancelled quickly)
        if (order.size >= SPOOF_MIN_SIZE && age < SPOOF_MAX_AGE_MS) {
            detectSpoofing(alias, state, order);
        }

        // Remove from tracking
        state.orders.remove(orderId);
        List<String> level = state.priceLevels.get(order.price);
        if (level != null) {
            level.remove(orderId);
        }
    }

    // ========== ORDER FLOW DETECTION ==========

    private void checkIcebergPattern(String alias, InstrumentState state, int price, boolean isBid) {
        List<String> ordersAtPrice = state.priceLevels.get(price);
        if (ordersAtPrice == null || ordersAtPrice.size() < ICEBERG_MIN_ORDERS) {
            return;
        }

        // Count total size at this price
        int totalSize = 0;
        for (String orderId : ordersAtPrice) {
            OrderInfo order = state.orders.get(orderId);
            if (order != null && !order.wasCancelled) {
                totalSize += order.size;
            }
        }

        log("ICEBERG DETECTED: " + (isBid ? "BID" : "ASK") + " at " + price +
            " Orders=" + ordersAtPrice.size() + " TotalSize=" + totalSize);

        // Generate signal
        double entryPrice = price;
        double atrValue = state.atr > 0 ? state.atr : entryPrice * 0.001;

        double stopLoss, takeProfit;
        if (isBid) {
            // Bid iceberg = support
            stopLoss = entryPrice - (atrValue * ATR_MULTIPLIER);
            takeProfit = entryPrice + (atrValue * ATR_MULTIPLIER * RISK_REWARD_RATIO);
        } else {
            // Ask iceberg = resistance
            stopLoss = entryPrice + (atrValue * ATR_MULTIPLIER);
            takeProfit = entryPrice - (atrValue * ATR_MULTIPLIER * RISK_REWARD_RATIO);
        }

        TradeSignal signal = new TradeSignal(
            System.nanoTime(),
            entryPrice,
            takeProfit,
            stopLoss,
            isBid,
            "ICEBERG",
            "Iceberg order: " + ordersAtPrice.size() + " orders at " + price
        );

        addTradeSignal(alias, state, signal);
    }

    private void detectSpoofing(String alias, InstrumentState state, OrderInfo order) {
        log("SPOOFING DETECTED: " + (order.isBid ? "BID" : "ASK") +
            " Size=" + order.size + " cancelled after " + order.getAge() + "ms");

        // Generate counter signal (spoofing indicates fake pressure)
        double entryPrice = order.price;
        double atrValue = state.atr > 0 ? state.atr : entryPrice * 0.001;

        // If they spoofed bid, they're actually bearish (fake buying pressure)
        // If they spoofed ask, they're actually bullish (fake selling pressure)
        boolean isLong = !order.isBid;

        double stopLoss, takeProfit;
        if (isLong) {
            stopLoss = entryPrice - (atrValue * ATR_MULTIPLIER);
            takeProfit = entryPrice + (atrValue * ATR_MULTIPLIER * RISK_REWARD_RATIO);
        } else {
            stopLoss = entryPrice + (atrValue * ATR_MULTIPLIER);
            takeProfit = entryPrice - (atrValue * ATR_MULTIPLIER * RISK_REWARD_RATIO);
        }

        TradeSignal signal = new TradeSignal(
            System.nanoTime(),
            entryPrice,
            takeProfit,
            stopLoss,
            isLong,
            "SPOOF",
            "Spoof detected: fake " + (order.isBid ? "bid" : "ask") + " of size " + order.size
        );

        addTradeSignal(alias, state, signal);
    }

    private void checkOrderFollowingMarket(String alias, InstrumentState state, OrderInfo order,
                                          int oldPrice, int newPrice) {
        // If order is moving in direction of trade flow, could be smart money positioning
        boolean isBid = order.isBid;
        boolean priceIncreased = newPrice > oldPrice;

        // Bid orders moving up with price (bullish positioning)
        // Ask orders moving down with price (bearish positioning)
        boolean isFollowing = isBid && priceIncreased || !isBid && !priceIncreased;

        if (isFollowing && Math.abs(newPrice - oldPrice) > 0) {
            log("SMART MONEY: Order " + order.orderId + " repositioning with market");
            // Could generate signal here if pattern is strong enough
        }
    }

    // ========== TRADE DATA FOR ATR & SIGNAL VALIDATION ==========

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
        InstrumentState state = instrumentStates.get(alias);
        if (state == null) {
            state = new InstrumentState();
            instrumentStates.put(alias, state);
            log("Created state for " + alias);
        }

        state.tradeCount++;
        state.lastTradePrice = price;

        // Update ATR
        state.priceHistory.add(price);
        if (state.priceHistory.size() > 15) {
            state.priceHistory.remove(0);
        }

        updateATR(state, price);

        // Log trades periodically
        if (state.tradeCount % 50 == 0) {
            log(alias + " Trade #" + state.tradeCount + ": Price=" + price + " Size=" + size + " ATR=" + state.atr);
        }

        // Check for aggressive trades (eating large orders)
        checkAggressiveTrade(alias, state, price, size);
    }

    private void checkAggressiveTrade(String alias, InstrumentState state, double price, int size) {
        // Look for large market orders
        if (size < ABSORPTION_MIN_SIZE) return;

        // Check if this trade hit a large order we were tracking
        boolean hitLargeOrder = false;
        for (OrderInfo order : state.orders.values()) {
            if (Math.abs(order.price - price) < 2) {  // Within 2 ticks
                hitLargeOrder = true;
                break;
            }
        }

        if (hitLargeOrder) {
            log("ABSORPTION: Large trade size=" + size + " hitting level at " + price);

            // Generate absorption signal
            double atrValue = state.atr > 0 ? state.atr : price * 0.001;
            double stopLoss = price - (atrValue * ATR_MULTIPLIER);
            double takeProfit = price + (atrValue * ATR_MULTIPLIER * RISK_REWARD_RATIO);

            TradeSignal signal = new TradeSignal(
                System.nanoTime(),
                price,
                takeProfit,
                stopLoss,
                true,
                "ABSORPTION",
                "Large trade absorbed: size=" + size
            );

            addTradeSignal(alias, state, signal);
        }
    }

    private void updateATR(InstrumentState state, double currentPrice) {
        if (state.priceHistory.size() < 2) return;

        double prevPrice = state.priceHistory.get(0);
        double trueRange = Math.abs(currentPrice - prevPrice);

        state.trueRanges.add(trueRange);
        if (state.trueRanges.size() > 14) {
            state.trueRanges.remove(0);
        }

        double sum = 0;
        for (Double tr : state.trueRanges) {
            sum += tr;
        }
        state.atr = sum / state.trueRanges.size();
    }

    private void addTradeSignal(String alias, InstrumentState state, TradeSignal signal) {
        synchronized (state.tradeSignals) {
            state.tradeSignals.add(signal);
            // Keep only last 20 signals
            if (state.tradeSignals.size() > 20) {
                state.tradeSignals.remove(0);
            }
        }

        state.signalCount++;
        log(alias + " SIGNAL #" + state.signalCount + ": " + signal.signalType +
            " " + (signal.isLong ? "BUY" : "SELL") + " @ " + signal.entryPrice +
            " | TP: " + signal.takeProfit + " | SL: " + signal.stopLoss +
            " | Reason: " + signal.reason);
    }

    // ========== SSP VISUALIZATION ==========

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
            ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        ScreenSpaceCanvas heatmapCanvas = screenSpaceCanvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        return new ScreenSpacePainterAdapter() {

            private List<CanvasIcon> displayedIcons = new ArrayList<>();

            @Override
            public void onMoveEnd() {
                // Get state for this instrument
                InstrumentState state = instrumentStates.get(indicatorAlias);
                if (state == null) return;

                // Remove old icons
                for (CanvasIcon icon : displayedIcons) {
                    heatmapCanvas.removeShape(icon);
                }
                displayedIcons.clear();

                // Draw all active trade signals
                synchronized (state.tradeSignals) {
                    for (TradeSignal signal : state.tradeSignals) {
                        List<CanvasIcon> icons = drawTradeSignal(heatmapCanvas, signal);
                        displayedIcons.addAll(icons);
                    }
                }
            }

            private List<CanvasIcon> drawTradeSignal(ScreenSpaceCanvas canvas, TradeSignal signal) {
                List<CanvasIcon> icons = new ArrayList<>();

                Color signalColor = signal.isLong ? Color.GREEN : Color.RED;
                PreparedImage icon = signal.isLong ? createBuyIcon(signalColor) : createSellIcon(signalColor);

                HorizontalCoordinate x = new RelativeDataHorizontalCoordinate(
                    RelativeDataHorizontalCoordinate.HORIZONTAL_DATA_ZERO, signal.timestamp);
                VerticalCoordinate yEntry = new RelativeDataVerticalCoordinate(
                    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.entryPrice);

                int iconSize = 20;
                HorizontalCoordinate x1 = new RelativeDataHorizontalCoordinate(x, -iconSize/2);
                HorizontalCoordinate x2 = new RelativeDataHorizontalCoordinate(x, iconSize/2);
                VerticalCoordinate y1 = new RelativeDataVerticalCoordinate(yEntry, -iconSize/2);
                VerticalCoordinate y2 = new RelativeDataVerticalCoordinate(yEntry, iconSize/2);

                CanvasIcon entryIcon = new CanvasIcon(icon, x1, y1, x2, y2);
                canvas.addShape(entryIcon);
                icons.add(entryIcon);

                // Draw TP line
                VerticalCoordinate yTP = new RelativeDataVerticalCoordinate(
                    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.takeProfit);
                CanvasIcon tpLine = createLineIcon(x, yEntry, x, yTP, Color.GREEN, 2);
                canvas.addShape(tpLine);
                icons.add(tpLine);

                // Draw SL line
                VerticalCoordinate ySL = new RelativeDataVerticalCoordinate(
                    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.stopLoss);
                CanvasIcon slLine = createLineIcon(x, yEntry, x, ySL, Color.RED, 2);
                canvas.addShape(slLine);
                icons.add(slLine);

                // Add TP label
                PreparedImage tpLabel = createLabel("TP", Color.GREEN);
                CanvasIcon tpLabelIcon = new CanvasIcon(tpLabel,
                    new RelativeDataHorizontalCoordinate(x, iconSize/2),
                    new RelativeDataVerticalCoordinate(yTP, -iconSize),
                    new RelativeDataHorizontalCoordinate(x, iconSize/2 + 30),
                    new RelativeDataVerticalCoordinate(yTP, -iconSize + 15));
                canvas.addShape(tpLabelIcon);
                icons.add(tpLabelIcon);

                // Add SL label
                PreparedImage slLabel = createLabel("SL", Color.RED);
                CanvasIcon slLabelIcon = new CanvasIcon(slLabel,
                    new RelativeDataHorizontalCoordinate(x, iconSize/2),
                    new RelativeDataVerticalCoordinate(ySL, 0),
                    new RelativeDataHorizontalCoordinate(x, iconSize/2 + 30),
                    new RelativeDataVerticalCoordinate(ySL, 15));
                canvas.addShape(slLabelIcon);
                icons.add(slLabelIcon);

                return icons;
            }

            @Override
            public void dispose() {
                heatmapCanvas.dispose();
            }
        };
    }

    // ========== ICON CREATION ==========

    private PreparedImage createBuyIcon(Color color) {
        if (!buyIconCache.containsKey(color)) {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            Polygon triangle = new Polygon();
            triangle.addPoint(10, 2);
            triangle.addPoint(2, 18);
            triangle.addPoint(18, 18);
            g.fill(triangle);
            g.dispose();
            buyIconCache.put(color, new PreparedImage(image));
        }
        return buyIconCache.get(color);
    }

    private PreparedImage createSellIcon(Color color) {
        if (!sellIconCache.containsKey(color)) {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            Polygon triangle = new Polygon();
            triangle.addPoint(10, 18);
            triangle.addPoint(2, 2);
            triangle.addPoint(18, 2);
            g.fill(triangle);
            g.dispose();
            sellIconCache.put(color, new PreparedImage(image));
        }
        return sellIconCache.get(color);
    }

    private PreparedImage createLabel(String text, Color color) {
        BufferedImage image = new BufferedImage(30, 15, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setFont(new Font("Arial", Font.BOLD, 10));
        g.setColor(color);
        g.drawString(text, 5, 12);
        g.dispose();
        return new PreparedImage(image);
    }

    private CanvasIcon createLineIcon(
        HorizontalCoordinate x1, VerticalCoordinate y1,
        HorizontalCoordinate x2, VerticalCoordinate y2,
        Color color, int width) {

        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, width);
        g.dispose();

        return new CanvasIcon(new PreparedImage(image),
            new RelativeDataHorizontalCoordinate(x1, -width/2),
            new RelativeDataVerticalCoordinate(y1, -width/2),
            new RelativeDataHorizontalCoordinate(x2, width/2),
            new RelativeDataVerticalCoordinate(y2, width/2));
    }

    @Override
    public void finish() {
        log("OrderFlowMboStrategy finish() called");

        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                        .builder(OrderFlowMboStrategy.class, userName)
                        .setIsAdd(false)
                        .build();
                provider.sendUserMessage(message);
            }
        }

        if (logWriter != null) {
            logWriter.close();
        }
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String logMessage = timestamp + " - " + message;
        System.err.println(logMessage);
        if (logWriter == null) {
            try {
                logWriter = new PrintWriter(new FileWriter(logPath, false));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }
}
