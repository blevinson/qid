package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;

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
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.HistoricalDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Order Flow Strategy - Detects absorption, delta imbalances, and retail traps
 *
 * Based on order flow concepts:
 * - Absorption: Large orders absorbing aggressive volume (walls preventing price movement)
 * - Delta: Aggressive buyers vs sellers imbalance
 * - Retail Trap: Price moves one way but delta moves opposite (potential reversal)
 */
// @Layer1SimpleAttachable
// @Layer1StrategyName("Order Flow Strategy")  - Hidden from Bookmap list
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategy implements
        CustomModule,
        TradeDataListener,
        DepthDataListener,
        BboListener {

    // ========== PARAMETERS ==========
    @Parameter(name = "Absorption Threshold (contracts)")
    private Integer absorptionThreshold = 50;

    @Parameter(name = "Big Player Multiplier")
    private Double bigPlayerMultiplier = 5.0;

    @Parameter(name = "Delta Window Size (bars)")
    private Integer deltaWindowSize = 10;

    @Parameter(name = "Show Absorption Signals")
    private Boolean showAbsorption = true;

    @Parameter(name = "Show Delta Signals")
    private Boolean showDelta = true;

    @Parameter(name = "Show Retail Trap Signals")
    private Boolean showRetailTraps = true;

    // ========== INDICATORS ==========
    private Indicator bidSizeIndicator;
    private Indicator askSizeIndicator;
    private Indicator deltaIndicator;
    private Indicator cumulativeDeltaIndicator;
    private Indicator absorptionBidIndicator;
    private Indicator absorptionAskIndicator;
    private Indicator bullishSignalIndicator;
    private Indicator bearishSignalIndicator;
    private Indicator retailTrapIndicator;

    // ========== STATE VARIABLES ==========
    private OrderBook orderBook = new OrderBook();
    private double pips;

    // Delta tracking
    private long currentDelta = 0;      // Positive = buying pressure, Negative = selling pressure
    private long cumulativeDelta = 0;
    private Deque<Long> deltaHistory = new ArrayDeque<>(deltaWindowSize);
    private long averageDelta = 0;

    // Price tracking for retail trap detection
    private double lastPrice = 0;
    private double priceChange = 0;

    // Absorption detection
    private long lastBidSize = 0;
    private long lastAskSize = 0;
    private double bidSizeMaShort = 0;   // Short-term MA
    private double askSizeMaShort = 0;
    private double bidSizeMaLong = 0;    // Long-term MA
    private double askSizeMaLong = 0;
    private static final int MA_SHORT_PERIOD = 5;
    private static final int MA_LONG_PERIOD = 20;
    private int maCounter = 0;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("==== OrderFlowStrategy.initialize() called for " + alias + " ====");
        this.pips = info.pips;

        // Register BBO size indicators
        bidSizeIndicator = api.registerIndicator("Bid Size", GraphType.BOTTOM);
        askSizeIndicator = api.registerIndicator("Ask Size", GraphType.BOTTOM);
        bidSizeIndicator.setColor(Color.RED);
        askSizeIndicator.setColor(Color.GREEN);

        // Register delta indicators
        deltaIndicator = api.registerIndicator("Delta", GraphType.BOTTOM);
        cumulativeDeltaIndicator = api.registerIndicator("Cumulative Delta", GraphType.BOTTOM);
        deltaIndicator.setColor(Color.CYAN);
        cumulativeDeltaIndicator.setColor(Color.YELLOW);

        // Register absorption indicators
        absorptionBidIndicator = api.registerIndicator("Absorption Bid", GraphType.PRIMARY);
        absorptionAskIndicator = api.registerIndicator("Absorption Ask", GraphType.PRIMARY);
        absorptionBidIndicator.setColor(Color.RED);
        absorptionAskIndicator.setColor(Color.GREEN);

        // Register signal indicators
        bullishSignalIndicator = api.registerIndicator("Bullish Signal", GraphType.PRIMARY);
        bearishSignalIndicator = api.registerIndicator("Bearish Signal", GraphType.PRIMARY);
        retailTrapIndicator = api.registerIndicator("Retail Trap", GraphType.PRIMARY);
        bullishSignalIndicator.setColor(Color.GREEN);
        bearishSignalIndicator.setColor(Color.RED);
        retailTrapIndicator.setColor(Color.ORANGE);

        System.err.println("==== OrderFlowStrategy initialization complete for " + alias + " ====");
    }

    @Override
    public void stop() {
        System.err.println("OrderFlowStrategy stopped");
    }

    // ========== BBO LISTENER ==========
    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Update order book
        orderBook.onUpdate(true, bidPrice, bidSize);
        orderBook.onUpdate(false, askPrice, askSize);

        lastBidSize = bidSize;
        lastAskSize = askSize;

        // Track price for retail trap detection
        if (lastPrice > 0) {
            priceChange = askPrice - lastPrice;
        }
        lastPrice = askPrice;

        // Update moving averages for absorption detection
        updateMovingAverages(bidSize, askSize);

        // Display sizes
        bidSizeIndicator.addPoint(bidSize);
        askSizeIndicator.addPoint(askSize);

        // Detect and display signals
        detectAbsorption(bidPrice, bidSize, askPrice, askSize);
    }

    // ========== DEPTH DATA LISTENER ==========
    @Override
    public void onDepth(boolean isBid, int price, int size) {
        // Update order book with depth changes
        orderBook.onUpdate(isBid, price, size);

        // Could add additional depth analysis here
        // For example: tracking large orders at specific levels
    }

    // ========== TRADE DATA LISTENER ==========
    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Calculate delta based on trade direction
        // Trade closer to ask = aggressive buying (positive delta)
        // Trade closer to bid = aggressive selling (negative delta)

        // Use last BBO data to determine trade direction
        // If we have lastBidSize and lastAskSize from onBbo, we can infer direction
        // Simplified approach: use trade size itself for delta magnitude
        // In a more sophisticated version, you'd track bid/ask hits separately

        // For now, we'll use a simplified delta calculation
        // In production, you'd want to track actual bid lift vs ask hit
        long deltaChange = size;  // Will be adjusted by BBO comparison if needed
        currentDelta = deltaChange;
        cumulativeDelta += deltaChange;

        // Update delta history
        if (deltaHistory.size() >= deltaWindowSize) {
            deltaHistory.removeFirst();
        }
        deltaHistory.addLast(deltaChange);

        // Calculate average delta
        calculateAverageDelta();

        // Display delta values
        deltaIndicator.addPoint(currentDelta);
        cumulativeDeltaIndicator.addPoint(cumulativeDelta);

        // Detect signals based on delta
        detectDeltaSignals(price);
        detectRetailTrap(price);
    }

    // ========== SIGNAL DETECTION ==========

    /**
     * Detect absorption: Large orders that absorb aggressive volume
     * Logic: Large size + low price movement = absorption
     */
    private void detectAbsorption(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (!showAbsorption) {
            return;
        }

        // Bid absorption: Large bid wall that doesn't get eaten
        boolean bidAbsorption = bidSize > absorptionThreshold
                && bidSizeMaShort > bidSizeMaLong * 1.5  // Recent spike in bid size
                && currentDelta < 0;  // Despite selling pressure

        // Ask absorption: Large ask wall that doesn't get eaten
        boolean askAbsorption = askSize > absorptionThreshold
                && askSizeMaShort > askSizeMaLong * 1.5  // Recent spike in ask size
                && currentDelta > 0;  // Despite buying pressure

        // Display absorption signals
        if (bidAbsorption) {
            absorptionBidIndicator.addPoint(bidPrice / pips);
        }

        if (askAbsorption) {
            absorptionAskIndicator.addPoint(askPrice / pips);
        }

        // Generate trading signals
        if (bidAbsorption && cumulativeDelta > 0) {
            // Large bid wall absorbing asks + positive delta = potential breakout up
            bullishSignalIndicator.addPoint(bidPrice / pips);
        }

        if (askAbsorption && cumulativeDelta < 0) {
            // Large ask wall absorbing bids + negative delta = potential breakout down
            bearishSignalIndicator.addPoint(askPrice / pips);
        }
    }

    /**
     * Detect delta-based signals
     * Logic: Strong directional delta + large size = big player activity
     */
    private void detectDeltaSignals(double price) {
        if (!showDelta) {
            return;
        }

        // Calculate "big player" threshold based on average trade size
        long bigPlayerThreshold = (long) (averageDelta * bigPlayerMultiplier);

        // Bullish signal: Strong buying pressure from big players
        if (currentDelta > bigPlayerThreshold && currentDelta > 0) {
            bullishSignalIndicator.addPoint(price / pips);
        }

        // Bearish signal: Strong selling pressure from big players
        if (currentDelta < -bigPlayerThreshold && currentDelta < 0) {
            bearishSignalIndicator.addPoint(price / pips);
        }
    }

    /**
     * Detect retail traps
     * Logic: Price moves one way but delta moves opposite = potential reversal
     */
    private void detectRetailTrap(double price) {
        if (!showRetailTraps) {
            return;
        }

        // Bullish trap: Price rising but delta negative (retail buying into institutional selling)
        boolean bullishTrap = priceChange > 0 && currentDelta < -Math.abs(averageDelta);

        // Bearish trap: Price falling but delta positive (retail selling into institutional buying)
        boolean bearishTrap = priceChange < 0 && currentDelta > Math.abs(averageDelta);

        // Display trap signals
        if (bullishTrap) {
            retailTrapIndicator.addPoint(price / pips);
            // Could add warning: "Potential bullish trap - reversal possible"
        }

        if (bearishTrap) {
            retailTrapIndicator.addPoint(price / pips);
            // Could add warning: "Potential bearish trap - reversal possible"
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
