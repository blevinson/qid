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

/**
 * Order Flow Strategy with SSP - CORE API Version
 *
 * Uses proper Core API to enable SSP visualization of trade signals
 */
@Layer1Attachable
@Layer1StrategyName("OF Trade Signals SSP")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowSSPCore implements
    Layer1ApiFinishable,
    Layer1ApiAdminAdapter,
    Layer1ApiDataAdapter,
    ScreenSpacePainterFactory {

    private static final String INDICATOR_NAME = "Order Flow Trade Signals";

    private Layer1ApiProvider provider;
    private Map<String, String> indicatorsFullNameToUserName = new HashMap<>();

    // File logging
    private PrintWriter logWriter;
    private String logPath = "/Users/brant/bl-projects/DemoStrategies/ssp_core_log.txt";

    // Icon caches
    private Map<Color, PreparedImage> buyIconCache = new HashMap<>();
    private Map<Color, PreparedImage> sellIconCache = new HashMap<>();

    // State per instrument
    private Map<String, InstrumentState> instrumentStates = new ConcurrentHashMap<>();

    private class InstrumentState {
        double pips;
        long currentDelta = 0;
        long cumulativeDelta = 0;
        List<Double> priceHistory = new ArrayList<>();
        double atr = 0;
        List<Double> trueRanges = new ArrayList<>();
        List<TradeSignal> tradeSignals = new ArrayList<>();

        int tradeCount = 0;
    }

    private class TradeSignal {
        long timestamp;
        double entryPrice;
        double takeProfit;
        double stopLoss;
        boolean isLong;
        String signalType;

        TradeSignal(long timestamp, double entryPrice, double takeProfit, double stopLoss, boolean isLong, String signalType) {
            this.timestamp = timestamp;
            this.entryPrice = entryPrice;
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
            this.isLong = isLong;
            this.signalType = signalType;
        }
    }

    // Constructor required by Core API
    public OrderFlowSSPCore(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
        log("OrderFlowSSPCore constructed");

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
            if (message.targetClass == OrderFlowSSPCore.class) {
                addIndicator();
            }
        }
    }

    public void addIndicator() {
        Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(OrderFlowSSPCore.class, INDICATOR_NAME)
                .setScreenSpacePainterFactory(this)
                .setIsAdd(true)
                .build();

        synchronized (indicatorsFullNameToUserName) {
            indicatorsFullNameToUserName.put(message.fullName, message.userName);
        }

        provider.sendUserMessage(message);
        log("SSP Indicator added: " + INDICATOR_NAME);
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
            ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        ScreenSpaceCanvas heatmapCanvas = screenSpaceCanvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        return new ScreenSpacePainterAdapter() {

            private List<CanvasIcon> displayedIcons = new ArrayList<>();
            private InstrumentState state = instrumentStates.get(indicatorAlias);

            @Override
            public void onMoveEnd() {
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
    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
        InstrumentState state = instrumentStates.get(alias);
        if (state == null) {
            state = new InstrumentState();
            instrumentStates.put(alias, state);
            log("Created state for " + alias);
        }

        state.tradeCount++;

        // Log EVERY trade for debugging
        log(alias + " Trade #" + state.tradeCount + ": Price=" + price + " Size=" + size);

        // Update delta
        state.currentDelta = size;
        state.cumulativeDelta += size;

        // Update price history
        state.priceHistory.add(price);
        if (state.priceHistory.size() > 15) {
            state.priceHistory.remove(0);
        }

        // Update ATR
        updateATR(state, price);

        // GENERATE SIGNAL ON EVERY TRADE FOR TESTING (maximum sensitivity)
        boolean isLong = state.tradeCount % 2 == 0; // Alternate long/short
        generateTradeSignal(alias, state, price, isLong);
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

    private void generateTradeSignal(String alias, InstrumentState state, double entryPrice, boolean isLong) {
        double atrValue = state.atr > 0 ? state.atr : entryPrice * 0.002;
        double riskRewardRatio = 2.0;
        double atrMultiplier = 1.5;

        double stopLoss, takeProfit;

        if (isLong) {
            stopLoss = entryPrice - (atrValue * atrMultiplier);
            takeProfit = entryPrice + (atrValue * atrMultiplier * riskRewardRatio);
        } else {
            stopLoss = entryPrice + (atrValue * atrMultiplier);
            takeProfit = entryPrice - (atrValue * atrMultiplier * riskRewardRatio);
        }

        TradeSignal signal = new TradeSignal(
            System.nanoTime(),
            entryPrice,
            takeProfit,
            stopLoss,
            isLong,
            "DEMO_SIGNAL"
        );

        synchronized (state.tradeSignals) {
            state.tradeSignals.add(signal);
            // Keep only last 10 signals to avoid clutter
            if (state.tradeSignals.size() > 10) {
                state.tradeSignals.remove(0);
            }
        }

        log(alias + " " + String.format("TRADE SIGNAL: %s @ %.2f | TP: %.2f | SL: %.2f | R/R: %.1f",
            isLong ? "BUY" : "SELL",
            entryPrice,
            takeProfit,
            stopLoss,
            riskRewardRatio));
    }

    @Override
    public void finish() {
        log("OrderFlowSSPCore finish() called");

        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                        .builder(OrderFlowSSPCore.class, userName)
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
