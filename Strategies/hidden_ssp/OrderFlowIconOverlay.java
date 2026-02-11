package velox.api.layer1.simpledemo.screenspacepainter;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;

/**
 * Order Flow Icons - Shows buy/sell signal ICONS on the RIGHT of chart
 * Follows Layer1ApiLastPricePlankDemo pattern exactly
 */
@Layer1Attachable
@Layer1StrategyName("OF Icon Overlay")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowIconOverlay implements
    Layer1ApiFinishable,
    Layer1ApiAdminAdapter,
    Layer1ApiDataAdapter,
    ScreenSpacePainterFactory {

    private static final String INDICATOR_NAME = "Order Flow Icons";
    private static final String SIGNAL_FILE_PATH = "/Users/brant/bl-projects/DemoStrategies/mbo_signals.txt";
    private static final int ICON_SIZE = 30;

    private Layer1ApiProvider provider;

    private Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private Map<String, String> indicatorsUserNameToFullName = new HashMap<>();

    private Map<String, SignalPainter> painters = Collections.synchronizedMap(new HashMap<>());
    private Map<String, InstrumentInfo> instrumentInfos = Collections.synchronizedMap(new HashMap<>());

    // Icon cache
    private Map<String, PreparedImage> iconCache = new HashMap<>();

    // Signal file reading
    private int lastLineRead = 0;

    class SignalPainter implements ScreenSpacePainterAdapter {

        private static final int ICONS_TO_KEEP = 10;

        private final ScreenSpaceCanvas canvas;
        private final String alias;

        int rightOfTimelineWidth;

        private List<SignalIcon> signalIcons = new ArrayList<>();

        public SignalPainter(ScreenSpaceCanvas canvas, String alias) {
            this.canvas = canvas;
            this.alias = alias;
        }

        private void onSignal(boolean isBuy, double price, long timestamp) {
            // Create or get icon from cache
            String iconKey = isBuy ? "BUY" : "SELL";
            PreparedImage iconImage = iconCache.get(iconKey);
            if (iconImage == null) {
                iconImage = isBuy ? createBuyIcon() : createSellIcon();
                iconCache.put(iconKey, iconImage);
            }

            // Position icon at the SIGNAL timestamp so it travels with chart
            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, -ICON_SIZE/2, timestamp);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, -ICON_SIZE/2, price);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, ICON_SIZE/2, timestamp);
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, ICON_SIZE/2, price);

            CanvasIcon icon = new CanvasIcon(iconImage, x1, y1, x2, y2);
            canvas.addShape(icon);

            // Track icon
            signalIcons.add(new SignalIcon(icon, price));

            // Remove old icons
            if (signalIcons.size() > ICONS_TO_KEEP) {
                SignalIcon oldSignal = signalIcons.remove(0);
                canvas.removeShape(oldSignal.icon);
            }
        }

        public void onHeatmapPixelsHeight(int heatmapPixelsHeight) {
        }

        public void onHeatmapPixelsBottom(int heatmapPixelsBottom) {
        }

        public void onRightOfTimelineWidth(int rightOfTimelineWidth) {
            this.rightOfTimelineWidth = rightOfTimelineWidth;
        }

        public void onRightOfTimelineLeft(int rightOfTimelineLeft) {
        }

        public void onMoveEnd() {
        }

        public void dispose() {
            canvas.dispose();
        }
    }

    class SignalIcon {
        CanvasIcon icon;
        double price;

        SignalIcon(CanvasIcon icon, double price) {
            this.icon = icon;
            this.price = price;
        }
    }

    public OrderFlowIconOverlay(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);

        System.err.println("IconOverlay: INITIALIZED - Starting signal file reader from: " + SIGNAL_FILE_PATH);

        // Start signal file reader thread
        startSignalReader();
    }

    private void startSignalReader() {
        Thread readerThread = new Thread(() -> {
            while (true) {
                try {
                    readSignalFile();
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readSignalFile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(SIGNAL_FILE_PATH))) {
            // Skip already-read lines
            for (int i = 0; i < lastLineRead; i++) {
                if (reader.readLine() == null) break;
            }

            // Read new lines
            String line;
            int lineNum = lastLineRead;
            int newLinesCount = 0;
            while ((line = reader.readLine()) != null) {
                processSignal(line);
                lineNum++;
                newLinesCount++;
            }
            lastLineRead = lineNum;

            if (newLinesCount > 0) {
                System.err.println("IconOverlay: Read " + newLinesCount + " new signals from file (total: " + lineNum + ")");
            }
        } catch (IOException e) {
            // File might not exist yet - log once per minute
            if (System.currentTimeMillis() % 60000 < 100) {
                System.err.println("IconOverlay: Signal file not found yet (will retry): " + SIGNAL_FILE_PATH);
            }
        }
    }

    private void processSignal(String line) {
        // Format: TYPE|DIRECTION|PRICE|SIZE
        String[] parts = line.split("\\|");
        if (parts.length != 4) return;

        String type = parts[0];
        String direction = parts[1];

        int price;
        int size;
        try {
            price = Integer.parseInt(parts[2]);
            size = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }

        // Determine if this is a buy or sell signal
        boolean isBuy;
        if (type.equals("ICEBERG")) {
            isBuy = direction.equals("BUY");
        } else if (type.equals("SPOOF")) {
            // Fade the spoof - opposite direction
            isBuy = direction.equals("SELL");
        } else if (type.equals("ABSORPTION")) {
            // Fade absorption
            isBuy = !direction.equals("FADE") && !direction.equals("SELL");
        } else {
            return;
        }

        // Log signal received
        System.err.println("ICON SIGNAL: " + type + " " + direction + " @" + price + " Painters: " + painters.size());

        // Use current time as signal timestamp (in nanoseconds)
        long timestamp = System.currentTimeMillis() * 1_000_000;

        // Add signal to ALL painters immediately (like onTrade in last price plank)
        // If no painters exist yet, create a default one for the first instrument
        if (painters.isEmpty()) {
            // Log warning but don't fail
            System.err.println("Warning: No painters registered yet, skipping signal");
            return;
        }

        for (SignalPainter painter : painters.values()) {
            painter.onSignal(isBuy, price, timestamp);
        }
    }

    @Override
    public void onUserMessage(Object data) {
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == OrderFlowIconOverlay.class) {
                addIndicator();
            }
        }
    }

    private Layer1ApiUserMessageModifyScreenSpacePainter getUserMessageAdd(String userName) {
        return Layer1ApiUserMessageModifyScreenSpacePainter.builder(OrderFlowIconOverlay.class, userName)
                .setIsAdd(true)
                .setScreenSpacePainterFactory(this)
                .build();
    }

    public void addIndicator() {
        Layer1ApiUserMessageModifyScreenSpacePainter message = getUserMessageAdd(INDICATOR_NAME);

        synchronized (indicatorsFullNameToUserName) {
            indicatorsFullNameToUserName.put(message.fullName, message.userName);
            indicatorsUserNameToFullName.put(message.userName, message.fullName);
        }
        provider.sendUserMessage(message);
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
            ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        System.err.println("IconOverlay: Creating painter for alias=" + indicatorAlias);

        // Use HEATMAP canvas so icons can be positioned at specific timestamps!
        ScreenSpaceCanvas canvas = screenSpaceCanvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        SignalPainter painter = new SignalPainter(canvas, indicatorAlias);
        painters.put(indicatorAlias, painter);

        System.err.println("IconOverlay: Painter created! Total painters: " + painters.size());

        return painter;
    }

    private PreparedImage createBuyIcon() {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // Green upward triangle
        g.setColor(Color.GREEN);
        Polygon triangle = new Polygon();
        triangle.addPoint(ICON_SIZE/2, 2);
        triangle.addPoint(2, ICON_SIZE-2);
        triangle.addPoint(ICON_SIZE-2, ICON_SIZE-2);
        g.fill(triangle);

        // White border
        g.setColor(Color.WHITE);
        g.drawPolygon(triangle);

        // "B" text
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.drawString("B", ICON_SIZE/2 - 5, ICON_SIZE - 6);

        g.dispose();
        return new PreparedImage(image);
    }

    private PreparedImage createSellIcon() {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // Red downward triangle
        g.setColor(Color.RED);
        Polygon triangle = new Polygon();
        triangle.addPoint(ICON_SIZE/2, ICON_SIZE-2);
        triangle.addPoint(2, 2);
        triangle.addPoint(ICON_SIZE-2, 2);
        g.fill(triangle);

        // White border
        g.setColor(Color.WHITE);
        g.drawPolygon(triangle);

        // "S" text
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.drawString("S", ICON_SIZE/2 - 5, ICON_SIZE - 4);

        g.dispose();
        return new PreparedImage(image);
    }

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
        // Not used - signals come from file reader
    }

    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                        .builder(OrderFlowIconOverlay.class, userName).setIsAdd(false).build();
                provider.sendUserMessage(message);
            }
        }
    }
}
