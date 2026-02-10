package velox.api.layer1.simplified.demo;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
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
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.HorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativeDataHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativeDataVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.VerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import velox.api.layer1.layers.utils.OrderBook;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Order Flow Strategy with SSP Trade Visualization
 *
 * Displays buy/sell signals with TP/SL levels using Screen Space Painter:
 * - Buy icon (green triangle up) at entry price
 * - Sell icon (red triangle down) at entry price
 * - Take Profit line (green) with label
 * - Stop Loss line (red) with label
 * - Trade entry lines connecting entry to TP/SL
 *
 * TP/SL Calculation based on:
 * - ATR (Average True Range) for volatility-based stops
 * - Recent swing highs/lows for structure-based levels
 * - Risk/Reward ratio (default 2:1)
 */
@Layer1SimpleAttachable
@Layer1StrategyName("OF Strategy with Trade Signals")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategyWithSSP implements
        CustomModule,
        TradeDataListener,
        DepthDataListener,
        BboListener,
        ScreenSpacePainterFactory {

    // ========== PARAMETERS ==========
    @Parameter(name = "Absorption Threshold")
    private Integer absorptionThreshold = 50;

    @Parameter(name = "Big Player Multiplier")
    private Double bigPlayerMultiplier = 5.0;

    @Parameter(name = "Risk/Reward Ratio")
    private Double riskRewardRatio = 2.0;

    @Parameter(name = "ATR Period")
    private Integer atrPeriod = 14;

    @Parameter(name = "ATR Multiplier for SL")
    private Double atrMultiplier = 1.5;

    // ========== INDICATORS ==========
    private Indicator absorptionBidIndicator;
    private Indicator absorptionAskIndicator;
    private Indicator bullishSignalIndicator;
    private Indicator bearishSignalIndicator;

    // ========== SSP COMPONENTS ==========
    private Api api;
    private String alias;
    private double pips;

    // File logging
    private PrintWriter logWriter;
    private String logPath = "/Users/brant/bl-projects/DemoStrategies/ssp_strategy_log.txt";

    // Icon caches
    private Map<Color, PreparedImage> buyIconCache = new HashMap<>();
    private Map<Color, PreparedImage> sellIconCache = new HashMap<>();
    private Map<Color, PreparedImage> tpLineCache = new HashMap<>();
    private Map<Color, PreparedImage> slLineCache = new HashMap<>();

    // ========== STATE VARIABLES ==========
    private OrderBook orderBook = new OrderBook();

    // Delta tracking
    private long currentDelta = 0;
    private long cumulativeDelta = 0;
    private Deque<Long> deltaHistory = new ArrayDeque<>();
    private long averageDelta = 0;

    // Price tracking for ATR and swing detection
    private List<Double> priceHistory = new ArrayList<>();
    private double lastPrice = 0;
    private double priceChange = 0;

    // ATR calculation
    private Deque<Double> trueRanges = new ArrayDeque<>();
    private double atr = 0;

    // Swing high/low detection
    private Deque<Double> swingHighs = new ArrayDeque<>();
    private Deque<Double> swingLows = new ArrayDeque<>();
    private static final int SWING_PERIOD = 5;

    // Trade signals for SSP
    private Deque<TradeSignal> tradeSignals = new ArrayDeque<>();
    private static final int MAX_TRADE_SIGNALS = 20;

    // Absorption detection
    private long lastBidSize = 0;
    private long lastAskSize = 0;
    private double bidSizeMaShort = 0;
    private double askSizeMaShort = 0;
    private double bidSizeMaLong = 0;
    private double askSizeMaLong = 0;
    private static final int MA_SHORT_PERIOD = 5;
    private static final int MA_LONG_PERIOD = 20;
    private int maCounter = 0;

    /**
     * Trade Signal Data Structure
     */
    private class TradeSignal {
        long timestamp;          // When signal was generated
        double entryPrice;       // Entry price
        double takeProfit;       // TP price
        double stopLoss;         // SL price
        boolean isLong;          // true = buy, false = sell
        String signalType;       // "ABSORPTION", "BIG_PLAYER", "RETAIL_TRAP"

        TradeSignal(long timestamp, double entryPrice, double takeProfit, double stopLoss, boolean isLong, String signalType) {
            this.timestamp = timestamp;
            this.entryPrice = entryPrice;
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
            this.isLong = isLong;
            this.signalType = signalType;
        }
    }

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        log("========== OrderFlowStrategyWithSSP.initialize() START ==========");
        log("Alias: " + alias);
        log("Pips: " + info.pips);

        try {
            logWriter = new PrintWriter(new FileWriter(logPath, false));
            log("File logging initialized: " + logPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.alias = alias;
        this.api = api;
        this.pips = info.pips;

        // Register indicators (simplified dots)
        absorptionBidIndicator = api.registerIndicator("Absorption Bid", GraphType.PRIMARY);
        absorptionAskIndicator = api.registerIndicator("Absorption Ask", GraphType.PRIMARY);
        bullishSignalIndicator = api.registerIndicator("Bullish Signal", GraphType.PRIMARY);
        bearishSignalIndicator = api.registerIndicator("Bearish Signal", GraphType.PRIMARY);

        absorptionBidIndicator.setColor(Color.RED);
        absorptionAskIndicator.setColor(Color.GREEN);
        bullishSignalIndicator.setColor(Color.GREEN);
        bearishSignalIndicator.setColor(Color.RED);

        // Register SSP painter for trade visualization
        Layer1ApiUserMessageModifyScreenSpacePainter sspMessage = Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(OrderFlowStrategyWithSSP.class, "Trade Signals")
                .setScreenSpacePainterFactory(this)
                .setIsAdd(true)
                .build();

        api.sendUserMessage(sspMessage);

        log("==== OrderFlowStrategyWithSSP initialization complete ====");
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String logMessage = timestamp + " - " + message;
        System.err.println(logMessage);  // Also print to console
        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
            ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        ScreenSpaceCanvas heatmapCanvas = screenSpaceCanvasFactory.createCanvas(
            ScreenSpaceCanvasFactory.ScreenSpaceCanvasType.HEATMAP);

        return new ScreenSpacePainterAdapter() {

            private List<CanvasIcon> displayedIcons = new ArrayList<>();

            @Override
            public void onMoveEnd() {
                // Remove old icons
                for (CanvasIcon icon : displayedIcons) {
                    heatmapCanvas.removeShape(icon);
                }
                displayedIcons.clear();

                // Draw all active trade signals
                synchronized (tradeSignals) {
                    for (TradeSignal signal : tradeSignals) {
                        List<CanvasIcon> icons = drawTradeSignal(heatmapCanvas, signal);
                        displayedIcons.addAll(icons);
                    }
                }
            }

            private List<CanvasIcon> drawTradeSignal(ScreenSpaceCanvas canvas, TradeSignal signal) {
                List<CanvasIcon> icons = new ArrayList<>();

                // Create buy or sell icon
                Color signalColor = signal.isLong ? Color.GREEN : Color.RED;
                PreparedImage icon = signal.isLong ? createBuyIcon(signalColor) : createSellIcon(signalColor);

                // Draw icon at entry price
                HorizontalCoordinate x = new RelativeDataHorizontalCoordinate(
                    RelativeDataHorizontalCoordinate.HORIZONTAL_DATA_ZERO, signal.timestamp);
                VerticalCoordinate yEntry = new RelativeDataVerticalCoordinate(
                    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.entryPrice);

                // Icon dimensions
                int iconSize = 20;
                HorizontalCoordinate x1 = new RelativeDataHorizontalCoordinate(x, -iconSize/2);
                HorizontalCoordinate x2 = new RelativeDataHorizontalCoordinate(x, iconSize/2);
                VerticalCoordinate y1 = new RelativeDataVerticalCoordinate(yEntry, -iconSize/2);
                VerticalCoordinate y2 = new RelativeDataVerticalCoordinate(yEntry, iconSize/2);

                CanvasIcon entryIcon = new CanvasIcon(icon, x1, y1, x2, y2);
                canvas.addShape(entryIcon);
                icons.add(entryIcon);

                // Draw line to TP
                VerticalCoordinate yTP = new RelativeDataVerticalCoordinate(
                    RelativeDataVerticalCoordinate.VERTICAL_DATA_ZERO, signal.takeProfit);
                CanvasIcon tpLine = createLineIcon(x, yEntry, x, yTP, Color.GREEN, 2);
                canvas.addShape(tpLine);
                icons.add(tpLine);

                // Draw line to SL
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

    /**
     * Create buy icon (green triangle pointing up)
     */
    private PreparedImage createBuyIcon(Color color) {
        if (!buyIconCache.containsKey(color)) {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            Polygon triangle = new Polygon();
            triangle.addPoint(10, 2);   // top
            triangle.addPoint(2, 18);   // bottom left
            triangle.addPoint(18, 18);  // bottom right
            g.fill(triangle);
            g.dispose();
            buyIconCache.put(color, new PreparedImage(image));
        }
        return buyIconCache.get(color);
    }

    /**
     * Create sell icon (red triangle pointing down)
     */
    private PreparedImage createSellIcon(Color color) {
        if (!sellIconCache.containsKey(color)) {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            Polygon triangle = new Polygon();
            triangle.addPoint(10, 18);  // bottom
            triangle.addPoint(2, 2);    // top left
            triangle.addPoint(18, 2);   // top right
            g.fill(triangle);
            g.dispose();
            sellIconCache.put(color, new PreparedImage(image));
        }
        return sellIconCache.get(color);
    }

    /**
     * Create text label
     */
    private PreparedImage createLabel(String text, Color color) {
        BufferedImage image = new BufferedImage(30, 15, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setFont(new Font("Arial", Font.BOLD, 10));
        g.setColor(color);
        g.drawString(text, 5, 12);
        g.dispose();
        return new PreparedImage(image);
    }

    /**
     * Create line icon
     */
    private CanvasIcon createLineIcon(
        HorizontalCoordinate x1, VerticalCoordinate y1,
        HorizontalCoordinate x2, VerticalCoordinate y2,
        Color color, int width) {

        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, width);
        g.dispose();

        // Expand coordinates to accommodate line width
        x1 = new RelativeDataHorizontalCoordinate(x1, -width/2);
        x2 = new RelativeDataHorizontalCoordinate(x2, width/2);
        y1 = new RelativeDataVerticalCoordinate(y1, -width/2);
        y2 = new RelativeDataVerticalCoordinate(y2, width/2);

        return new CanvasIcon(new PreparedImage(image), x1, y1, x2, y2);
    }

    @Override
    public void stop() {
        log("OrderFlowStrategyWithSSP stopped");
        if (logWriter != null) {
            logWriter.close();
        }
    }

    // ========== BBO LISTENER ==========
    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Update order book
        orderBook.onUpdate(true, bidPrice, bidSize);
        orderBook.onUpdate(false, askPrice, askSize);

        lastBidSize = bidSize;
        lastAskSize = askSize;

        // Track price
        if (lastPrice > 0) {
            priceChange = askPrice - lastPrice;
        }
        lastPrice = askPrice;

        // Update moving averages
        updateMovingAverages(bidSize, askSize);

        // Detect signals
        detectAbsorption(bidPrice, bidSize, askPrice, askSize);
    }

    // ========== DEPTH DATA LISTENER ==========
    @Override
    public void onDepth(boolean isBid, int price, int size) {
        orderBook.onUpdate(isBid, price, size);
    }

    // ========== TRADE DATA LISTENER ==========
    private int tradeCount = 0;

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        tradeCount++;

        // Log every 100 trades
        if (tradeCount % 100 == 0) {
            log("Trade #" + tradeCount + ": Price=" + price + " Size=" + size + " Delta=" + currentDelta);
        }

        // Calculate delta
        long deltaChange = size;
        currentDelta = deltaChange;
        cumulativeDelta += deltaChange;

        // Update price history for ATR
        priceHistory.add(price);
        if (priceHistory.size() > atrPeriod + 1) {
            priceHistory.remove(0);
        }

        // Update ATR
        updateATR(price);

        // Update swing highs/lows
        updateSwingPoints(price);

        // Update delta history
        if (deltaHistory.size() >= atrPeriod) {
            deltaHistory.removeFirst();
        }
        deltaHistory.addLast(deltaChange);

        // Calculate average delta
        calculateAverageDelta();

        // Detect signals
        detectDeltaSignals(price);
        detectRetailTrap(price);
    }

    // ========== SIGNAL DETECTION ==========

    private void detectAbsorption(int bidPrice, int bidSize, int askPrice, int askSize) {
        boolean bidAbsorption = bidSize > absorptionThreshold
                && bidSizeMaShort > bidSizeMaLong * 1.5
                && currentDelta < 0;

        boolean askAbsorption = askSize > absorptionThreshold
                && askSizeMaShort > askSizeMaLong * 1.5
                && currentDelta > 0;

        // Plot indicator points
        if (bidAbsorption) {
            absorptionBidIndicator.addPoint(bidPrice);
        }

        if (askAbsorption) {
            absorptionAskIndicator.addPoint(askPrice);
        }

        // Generate trade signals
        if (bidAbsorption && cumulativeDelta > 0) {
            generateTradeSignal(bidPrice, true, "ABSORPTION");
        }

        if (askAbsorption && cumulativeDelta < 0) {
            generateTradeSignal(askPrice, false, "ABSORPTION");
        }
    }

    private void detectDeltaSignals(double price) {
        long bigPlayerThreshold = (long) (averageDelta * bigPlayerMultiplier);

        if (currentDelta > bigPlayerThreshold && currentDelta > 0) {
            bullishSignalIndicator.addPoint(price);
            generateTradeSignal(price, true, "BIG_PLAYER");
        }

        if (currentDelta < -bigPlayerThreshold && currentDelta < 0) {
            bearishSignalIndicator.addPoint(price);
            generateTradeSignal(price, false, "BIG_PLAYER");
        }
    }

    private void detectRetailTrap(double price) {
        boolean bullishTrap = priceChange > 0 && currentDelta < -Math.abs(averageDelta);
        boolean bearishTrap = priceChange < 0 && currentDelta > Math.abs(averageDelta);

        if (bullishTrap) {
            generateTradeSignal(price, false, "RETAIL_TRAP");
        }

        if (bearishTrap) {
            generateTradeSignal(price, true, "RETAIL_TRAP");
        }
    }

    /**
     * Generate trade signal with TP/SL calculation
     */
    private void generateTradeSignal(double entryPrice, boolean isLong, String signalType) {
        double atrValue = atr > 0 ? atr : entryPrice * 0.002; // fallback if ATR not calculated yet

        double stopLoss, takeProfit;

        if (isLong) {
            // Long position
            stopLoss = entryPrice - (atrValue * atrMultiplier);
            takeProfit = entryPrice + (atrValue * atrMultiplier * riskRewardRatio);

            // Adjust to nearest swing low if available
            if (!swingLows.isEmpty()) {
                double nearestSwingLow = swingLows.getLast();
                if (nearestSwingLow < entryPrice && nearestSwingLow > stopLoss) {
                    stopLoss = nearestSwingLow;
                }
            }
        } else {
            // Short position
            stopLoss = entryPrice + (atrValue * atrMultiplier);
            takeProfit = entryPrice - (atrValue * atrMultiplier * riskRewardRatio);

            // Adjust to nearest swing high if available
            if (!swingHighs.isEmpty()) {
                double nearestSwingHigh = swingHighs.getLast();
                if (nearestSwingHigh > entryPrice && nearestSwingHigh < stopLoss) {
                    stopLoss = nearestSwingHigh;
                }
            }
        }

        // Create trade signal
        TradeSignal signal = new TradeSignal(
            System.nanoTime(),
            entryPrice,
            takeProfit,
            stopLoss,
            isLong,
            signalType
        );

        // Add to signals (keep only recent ones)
        synchronized (tradeSignals) {
            tradeSignals.addLast(signal);
            if (tradeSignals.size() > MAX_TRADE_SIGNALS) {
                tradeSignals.removeFirst();
            }
        }

        log(String.format("TRADE SIGNAL: %s %s @ %.2f | TP: %.2f | SL: %.2f | R/R: %.1f",
            isLong ? "BUY" : "SELL",
            signalType,
            entryPrice,
            takeProfit,
            stopLoss,
            riskRewardRatio));
    }

    // ========== HELPER METHODS ==========

    private void updateMovingAverages(int bidSize, int askSize) {
        maCounter++;
        bidSizeMaShort = updateMa(bidSizeMaShort, bidSize, Math.min(maCounter, MA_SHORT_PERIOD));
        askSizeMaShort = updateMa(askSizeMaShort, askSize, Math.min(maCounter, MA_SHORT_PERIOD));
        bidSizeMaLong = updateMa(bidSizeMaLong, bidSize, Math.min(maCounter, MA_LONG_PERIOD));
        askSizeMaLong = updateMa(askSizeMaLong, askSize, Math.min(maCounter, MA_LONG_PERIOD));
    }

    private double updateMa(double currentMa, int newValue, int period) {
        return currentMa + (newValue - currentMa) / period;
    }

    private void calculateAverageDelta() {
        if (deltaHistory.isEmpty()) {
            averageDelta = 0;
            return;
        }
        long sum = 0;
        for (Long delta : deltaHistory) {
            sum += Math.abs(delta);
        }
        averageDelta = sum / deltaHistory.size();
    }

    /**
     * Update ATR (Average True Range) for volatility-based stops
     */
    private void updateATR(double currentPrice) {
        if (priceHistory.size() < 2) {
            return;
        }

        double prevPrice = priceHistory.get(0);
        double trueRange = Math.abs(currentPrice - prevPrice);

        trueRanges.addLast(trueRange);
        if (trueRanges.size() > atrPeriod) {
            trueRanges.removeFirst();
        }

        double sum = 0;
        for (Double tr : trueRanges) {
            sum += tr;
        }
        atr = sum / trueRanges.size();
    }

    /**
     * Update swing highs and lows for structure-based stops
     */
    private void updateSwingPoints(double price) {
        if (priceHistory.size() < SWING_PERIOD * 2 + 1) {
            return;
        }

        // Check for swing high
        boolean isSwingHigh = true;
        for (int i = 1; i <= SWING_PERIOD; i++) {
            if (priceHistory.size() <= i || priceHistory.size() <= SWING_PERIOD + i) {
                isSwingHigh = false;
                break;
            }
            Double left = priceHistory.get(priceHistory.size() - SWING_PERIOD - i);
            Double right = priceHistory.get(priceHistory.size() - i);
            if (left == null || right == null || price <= left || price <= right) {
                isSwingHigh = false;
                break;
            }
        }

        if (isSwingHigh) {
            swingHighs.addLast(price);
            if (swingHighs.size() > 10) {
                swingHighs.removeFirst();
            }
        }

        // Check for swing low
        boolean isSwingLow = true;
        for (int i = 1; i <= SWING_PERIOD; i++) {
            if (priceHistory.size() <= i || priceHistory.size() <= SWING_PERIOD + i) {
                isSwingLow = false;
                break;
            }
            Double left = priceHistory.get(priceHistory.size() - SWING_PERIOD - i);
            Double right = priceHistory.get(priceHistory.size() - i);
            if (left == null || right == null || price >= left || price >= right) {
                isSwingLow = false;
                break;
            }
        }

        if (isSwingLow) {
            swingLows.addLast(price);
            if (swingLows.size() > 10) {
                swingLows.removeFirst();
            }
        }
    }
}
