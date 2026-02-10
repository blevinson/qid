package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.layers.utils.OrderBook;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

/**
 * Order Flow Strategy with Custom Status Panel
 *
 * Demonstrates how to add custom UI panels to display real-time metrics:
 * - Current Delta
 * - Cumulative Delta
 * - Signal Counts (Absorption, Big Player, Retail Traps)
 * - Strategy Status
 */
@Layer1SimpleAttachable
@Layer1StrategyName("OF Strategy With Panel")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategyWithPanel implements
        CustomModule,
        TradeDataListener,
        DepthDataListener,
        BboListener,
        CustomSettingsPanelProvider {

    // ========== PARAMETERS ==========
    @Parameter(name = "Absorption Threshold")
    private Integer absorptionThreshold = 50;

    @Parameter(name = "Big Player Multiplier")
    private Double bigPlayerMultiplier = 5.0;

    @Parameter(name = "Delta Window Size")
    private Integer deltaWindowSize = 10;

    // ========== INDICATORS ==========
    private Indicator absorptionBidIndicator;
    private Indicator absorptionAskIndicator;
    private Indicator bullishSignalIndicator;
    private Indicator bearishSignalIndicator;
    private Indicator retailTrapIndicator;

    // ========== STATUS PANEL COMPONENTS ==========
    private StrategyPanel statusPanel;
    private JLabel deltaLabel;
    private JLabel cumulativeDeltaLabel;
    private JLabel absorptionCountLabel;
    private JLabel bigPlayerCountLabel;
    private JLabel retailTrapCountLabel;
    private JLabel statusLabel;

    // ========== STATE VARIABLES ==========
    private OrderBook orderBook = new OrderBook();
    private double pips;

    // Delta tracking
    private long currentDelta = 0;
    private long cumulativeDelta = 0;
    private Deque<Long> deltaHistory = new ArrayDeque<>();
    private long averageDelta = 0;

    // Price tracking for retail trap detection
    private double lastPrice = 0;
    private double priceChange = 0;

    // Signal counting
    private int absorptionCount = 0;
    private int bigPlayerCount = 0;
    private int retailTrapCount = 0;

    // Moving averages for absorption detection
    private double bidSizeMaShort = 0;
    private double askSizeMaShort = 0;
    private double bidSizeMaLong = 0;
    private double askSizeMaLong = 0;
    private static final int MA_SHORT_PERIOD = 5;
    private static final int MA_LONG_PERIOD = 20;
    private int maCounter = 0;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("==== OrderFlowStrategyWithPanel.initialize() called for " + alias + " ====");
        this.pips = info.pips;

        // Register indicators
        absorptionBidIndicator = api.registerIndicator("Absorption Bid", GraphType.PRIMARY);
        absorptionAskIndicator = api.registerIndicator("Absorption Ask", GraphType.PRIMARY);
        bullishSignalIndicator = api.registerIndicator("Bullish Signal", GraphType.PRIMARY);
        bearishSignalIndicator = api.registerIndicator("Bearish Signal", GraphType.PRIMARY);
        retailTrapIndicator = api.registerIndicator("Retail Trap", GraphType.PRIMARY);

        absorptionBidIndicator.setColor(Color.RED);
        absorptionAskIndicator.setColor(Color.GREEN);
        bullishSignalIndicator.setColor(Color.GREEN);
        bearishSignalIndicator.setColor(Color.RED);
        retailTrapIndicator.setColor(Color.ORANGE);

        System.err.println("==== OrderFlowStrategyWithPanel initialization complete ====");
    }

    /**
     * Creates the custom status panel that displays real-time metrics
     * This panel appears in Bookmap's settings dialog for this strategy
     */
    private void createStatusPanel() {
        statusPanel = new StrategyPanel("Order Flow Status");
        statusPanel.setLayout(new GridLayout(6, 2, 5, 5));

        // Create labels for each metric
        statusLabel = new JLabel("Active");
        deltaLabel = new JLabel("0");
        cumulativeDeltaLabel = new JLabel("0");
        absorptionCountLabel = new JLabel("0");
        bigPlayerCountLabel = new JLabel("0");
        retailTrapCountLabel = new JLabel("0");

        // Add labels to panel in a grid layout
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        statusPanel.add(new JLabel("Current Delta:"));
        statusPanel.add(deltaLabel);
        statusPanel.add(new JLabel("Cumulative Delta:"));
        statusPanel.add(cumulativeDeltaLabel);
        statusPanel.add(new JLabel("Absorption Signals:"));
        statusPanel.add(absorptionCountLabel);
        statusPanel.add(new JLabel("Big Player Signals:"));
        statusPanel.add(bigPlayerCountLabel);
        statusPanel.add(new JLabel("Retail Trap Signals:"));
        statusPanel.add(retailTrapCountLabel);
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        if (statusPanel == null) {
            createStatusPanel();
        }
        return new StrategyPanel[] { statusPanel };
    }

    /**
     * Updates all labels on the status panel
     * Must be called on Swing EDT (Event Dispatch Thread)
     */
    private void updateStatusPanel() {
        SwingUtilities.invokeLater(() -> {
            deltaLabel.setText(String.valueOf(currentDelta));
            cumulativeDeltaLabel.setText(String.valueOf(cumulativeDelta));
            absorptionCountLabel.setText(String.valueOf(absorptionCount));
            bigPlayerCountLabel.setText(String.valueOf(bigPlayerCount));
            retailTrapCountLabel.setText(String.valueOf(retailTrapCount));
            statusLabel.setText("Active");
        });
    }

    @Override
    public void stop() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Stopped");
        });
        System.err.println("OrderFlowStrategyWithPanel stopped");
    }

    // ========== BBO LISTENER ==========
    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Update order book
        orderBook.onUpdate(true, bidPrice, bidSize);
        orderBook.onUpdate(false, askPrice, askSize);

        // Track price for retail trap detection
        if (lastPrice > 0) {
            priceChange = askPrice - lastPrice;
        }
        lastPrice = askPrice;

        // Update moving averages for absorption detection
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
    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Calculate delta
        long deltaChange = size;
        currentDelta = deltaChange;
        cumulativeDelta += deltaChange;

        // Update delta history
        if (deltaHistory.size() >= deltaWindowSize) {
            deltaHistory.removeFirst();
        }
        deltaHistory.addLast(deltaChange);

        // Calculate average delta
        calculateAverageDelta();

        // Detect signals
        detectDeltaSignals(price);
        detectRetailTrap(price);

        // Update status panel (every 5 trades to avoid excessive UI updates)
        if (Math.abs(cumulativeDelta) % 5 == 0) {
            updateStatusPanel();
        }
    }

    // ========== SIGNAL DETECTION ==========

    private void detectAbsorption(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Bid absorption: Large bid wall that doesn't get eaten
        boolean bidAbsorption = bidSize > absorptionThreshold
                && bidSizeMaShort > bidSizeMaLong * 1.5
                && currentDelta < 0;

        // Ask absorption: Large ask wall that doesn't get eaten
        boolean askAbsorption = askSize > absorptionThreshold
                && askSizeMaShort > askSizeMaLong * 1.5
                && currentDelta > 0;

        // Display absorption signals (no division needed!)
        if (bidAbsorption) {
            absorptionBidIndicator.addPoint(bidPrice);
        }

        if (askAbsorption) {
            absorptionAskIndicator.addPoint(askPrice);
        }

        // Generate trading signals and count them
        if (bidAbsorption && cumulativeDelta > 0) {
            bullishSignalIndicator.addPoint(bidPrice);
            absorptionCount++;
        }

        if (askAbsorption && cumulativeDelta < 0) {
            bearishSignalIndicator.addPoint(askPrice);
            absorptionCount++;
        }
    }

    private void detectDeltaSignals(double price) {
        // Calculate "big player" threshold based on average trade size
        long bigPlayerThreshold = (long) (averageDelta * bigPlayerMultiplier);

        // Bullish signal: Strong buying pressure from big players
        if (currentDelta > bigPlayerThreshold && currentDelta > 0) {
            bullishSignalIndicator.addPoint(price);
            bigPlayerCount++;
        }

        // Bearish signal: Strong selling pressure from big players
        if (currentDelta < -bigPlayerThreshold && currentDelta < 0) {
            bearishSignalIndicator.addPoint(price);
            bigPlayerCount++;
        }
    }

    private void detectRetailTrap(double price) {
        // Bullish trap: Price rising but delta negative
        boolean bullishTrap = priceChange > 0 && currentDelta < -Math.abs(averageDelta);

        // Bearish trap: Price falling but delta positive
        boolean bearishTrap = priceChange < 0 && currentDelta > Math.abs(averageDelta);

        // Display trap signals (no division!)
        if (bullishTrap) {
            retailTrapIndicator.addPoint(price);
            retailTrapCount++;
        }

        if (bearishTrap) {
            retailTrapIndicator.addPoint(price);
            retailTrapCount++;
        }
    }

    // ========== HELPER METHODS ==========

    private void updateMovingAverages(int bidSize, int askSize) {
        maCounter++;

        // Calculate short-term MA
        bidSizeMaShort = updateMa(bidSizeMaShort, bidSize, Math.min(maCounter, MA_SHORT_PERIOD));
        askSizeMaShort = updateMa(askSizeMaShort, askSize, Math.min(maCounter, MA_SHORT_PERIOD));

        // Calculate long-term MA
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
}
