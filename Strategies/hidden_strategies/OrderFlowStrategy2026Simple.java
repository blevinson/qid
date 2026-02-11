package velox.api.layer1.simplified.demo;

import java.awt.Color;

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
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Simple Order Flow 2026 - Tests if BBO and Trade listeners are working
 */
// @Layer1SimpleAttachable
// @Layer1StrategyName("Order Flow Simple 2026")  - Hidden from Bookmap list
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategy2026Simple implements
        CustomModule,
        TradeDataListener,
        BboListener {

    private Indicator bboIndicator;
    private Indicator tradeIndicator;
    private long bboCount = 0;
    private long tradeCount = 0;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("==== OrderFlowStrategy2026Simple.initialize() called for " + alias + " ====");

        // Create indicators on main chart (PRIMARY, not BOTTOM)
        bboIndicator = api.registerIndicator("BBO Updates", GraphType.PRIMARY);
        tradeIndicator = api.registerIndicator("Trades", GraphType.PRIMARY);
        bboIndicator.setColor(Color.BLUE);
        tradeIndicator.setColor(Color.MAGENTA);

        System.err.println("==== OrderFlowStrategy2026Simple initialization COMPLETE ====");
    }

    @Override
    public void stop() {
        System.err.println("OrderFlowStrategy2026Simple stopped - BBO: " + bboCount + ", Trades: " + tradeCount);
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        bboCount++;
        // Plot a blue dot for every BBO update
        bboIndicator.addPoint(askPrice);

        // Log every 100th BBO to verify it's working
        if (bboCount % 100 == 0) {
            System.err.println("BBO count: " + bboCount + " (Bid: " + bidSize + ", Ask: " + askSize + ")");
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        tradeCount++;
        // Plot a magenta dot for every trade
        tradeIndicator.addPoint(price);

        // Log every 10th trade
        if (tradeCount % 10 == 0) {
            System.err.println("Trade count: " + tradeCount + " @ " + price + " size: " + size);
        }
    }
}
