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
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Order Flow Strategy 2026 - Detects absorption, delta imbalances, and retail traps
 *
 * Based on order flow concepts:
 * - Absorption: Large orders absorbing aggressive volume (walls preventing price movement)
 * - Delta: Aggressive buyers vs sellers imbalance
 * - Retail Trap: Price moves one way but delta moves opposite (potential reversal)
 */
// @Layer1SimpleAttachable - Hidden from Bookmap list
// @Layer1StrategyName("Order Flow Strategy 2026")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategy2026 implements
        CustomModule,
        TradeDataListener,
        DepthDataListener,
        BboListener {

    // ========== PARAMETERS ==========
    private Integer absorptionThreshold = 50;
    private Double bigPlayerMultiplier = 5.0;
    private Integer deltaWindowSize = 10;
    private Boolean showAbsorption = true;
    private Boolean showDelta = true;
    private Boolean showRetailTraps = true;
    private Long signalCooldownMs = 5000L; // Cooldown between signals (5 seconds)
    private Integer minSignalStrength = 100; // Minimum delta for signal consideration

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
    private long currentDelta = 0;
    private long cumulativeDelta = 0;
    private Deque<Long> deltaHistory = new ArrayDeque<>();
    private long averageDelta = 0;

    // Price tracking for retail trap detection
    private double lastPrice = 0;
    private double priceChange = 0;

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

    // Signal cooldown tracking
    private long lastBullishSignalTime = 0;
    private long lastBearishSignalTime = 0;
    private long lastRetailTrapSignalTime = 0;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("==== OrderFlowStrategy2026.initialize() called for " + alias + " ====");
        this.pips = info.pips;

        // Register BBO size indicators on main chart for visibility
        bidSizeIndicator = api.registerIndicator("Bid Size 2026", GraphType.PRIMARY);
        askSizeIndicator = api.registerIndicator("Ask Size 2026", GraphType.PRIMARY);
        bidSizeIndicator.setColor(Color.RED);
        askSizeIndicator.setColor(Color.GREEN);

        // Register delta indicators on main chart for visibility
        deltaIndicator = api.registerIndicator("Delta 2026", GraphType.PRIMARY);
        cumulativeDeltaIndicator = api.registerIndicator("Cumulative Delta 2026", GraphType.PRIMARY);
        deltaIndicator.setColor(Color.CYAN);
        cumulativeDeltaIndicator.setColor(Color.YELLOW);

        // Register absorption indicators
        absorptionBidIndicator = api.registerIndicator("Absorption Bid 2026", GraphType.PRIMARY);
        absorptionAskIndicator = api.registerIndicator("Absorption Ask 2026", GraphType.PRIMARY);
        absorptionBidIndicator.setColor(Color.RED);
        absorptionAskIndicator.setColor(Color.GREEN);

        // Register signal indicators
        bullishSignalIndicator = api.registerIndicator("Bullish Signal 2026", GraphType.PRIMARY);
        bearishSignalIndicator = api.registerIndicator("Bearish Signal 2026", GraphType.PRIMARY);
        retailTrapIndicator = api.registerIndicator("Retail Trap 2026", GraphType.PRIMARY);
        bullishSignalIndicator.setColor(Color.GREEN);
        bearishSignalIndicator.setColor(Color.RED);
        retailTrapIndicator.setColor(Color.ORANGE);

        System.err.println("==== OrderFlowStrategy2026 initialization complete for " + alias + " ====");
    }

    @Override
    public void stop() {
        System.err.println("OrderFlowStrategy2026 stopped");
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

        // Display sizes at the bid/ask prices (no division needed!)
        bidSizeIndicator.addPoint(bidPrice);
        askSizeIndicator.addPoint(askPrice);

        // Detect and display signals
        detectAbsorption(bidPrice, bidSize, askPrice, askSize);
    }

    // ========== DEPTH DATA LISTENER ==========
    @Override
    public void onDepth(boolean isBid, int price, int size) {
        // Update order book with depth changes
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

        // Display delta values at the trade price (no division needed!)
        deltaIndicator.addPoint(price);
        cumulativeDeltaIndicator.addPoint(price);

        // Detect signals based on delta
        detectDeltaSignals(price);
        detectRetailTrap(price);
    }

    // ========== SIGNAL DETECTION ==========

    /**
     * Detect absorption: Large orders that absorb aggressive volume
     */
    private void detectAbsorption(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (!showAbsorption) {
            return;
        }

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

        // Generate trading signals with cooldown and strength check
        long currentTime = System.currentTimeMillis();

        if (bidAbsorption && cumulativeDelta > 0 && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastBullishSignalTime >= signalCooldownMs) {
                bullishSignalIndicator.addPoint(bidPrice);
                lastBullishSignalTime = currentTime;
            }
        }

        if (askAbsorption && cumulativeDelta < 0 && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastBearishSignalTime >= signalCooldownMs) {
                bearishSignalIndicator.addPoint(askPrice);
                lastBearishSignalTime = currentTime;
            }
        }
    }

    /**
     * Detect delta-based signals
     */
    private void detectDeltaSignals(double price) {
        if (!showDelta) {
            return;
        }

        // Calculate "big player" threshold based on average trade size
        long bigPlayerThreshold = (long) (averageDelta * bigPlayerMultiplier);

        long currentTime = System.currentTimeMillis();

        // Bullish signal: Strong buying pressure from big players with cooldown
        if (currentDelta > bigPlayerThreshold && currentDelta > 0 && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastBullishSignalTime >= signalCooldownMs) {
                bullishSignalIndicator.addPoint(price);
                lastBullishSignalTime = currentTime;
            }
        }

        // Bearish signal: Strong selling pressure from big players with cooldown
        if (currentDelta < -bigPlayerThreshold && currentDelta < 0 && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastBearishSignalTime >= signalCooldownMs) {
                bearishSignalIndicator.addPoint(price);
                lastBearishSignalTime = currentTime;
            }
        }
    }

    /**
     * Detect retail traps
     */
    private void detectRetailTrap(double price) {
        if (!showRetailTraps) {
            return;
        }

        // Bullish trap: Price rising but delta negative
        boolean bullishTrap = priceChange > 0 && currentDelta < -Math.abs(averageDelta);

        // Bearish trap: Price falling but delta positive
        boolean bearishTrap = priceChange < 0 && currentDelta > Math.abs(averageDelta);

        long currentTime = System.currentTimeMillis();

        // Display trap signals with cooldown
        if (bullishTrap && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastRetailTrapSignalTime >= signalCooldownMs) {
                retailTrapIndicator.addPoint(price);
                lastRetailTrapSignalTime = currentTime;
            }
        }

        if (bearishTrap && Math.abs(currentDelta) >= minSignalStrength) {
            if (currentTime - lastRetailTrapSignalTime >= signalCooldownMs) {
                retailTrapIndicator.addPoint(price);
                lastRetailTrapSignalTime = currentTime;
            }
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
