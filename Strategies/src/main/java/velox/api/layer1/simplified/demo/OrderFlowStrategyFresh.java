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
 * Order Flow Strategy Fresh - No history conflicts
 */
@Layer1SimpleAttachable
@Layer1StrategyName("OF Strategy Final")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategyFresh implements
        CustomModule,
        TradeDataListener,
        BboListener {

    private Indicator bidIndicator;
    private Indicator askIndicator;
    private Indicator deltaIndicator;
    private OrderBook orderBook = new OrderBook();
    private double pips;
    private long currentDelta = 0;
    private long cumulativeDelta = 0;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("========== OrderFlowStrategyFresh.initialize() START ==========");
        System.err.println("Alias: " + alias);
        System.err.println("Pips: " + info.pips);
        this.pips = info.pips;

        bidIndicator = api.registerIndicator("Bid OF", GraphType.PRIMARY);
        askIndicator = api.registerIndicator("Ask OF", GraphType.PRIMARY);
        deltaIndicator = api.registerIndicator("Delta OF", GraphType.PRIMARY);

        bidIndicator.setColor(Color.RED);
        askIndicator.setColor(Color.GREEN);
        deltaIndicator.setColor(Color.CYAN);

        System.err.println("========== OrderFlowStrategyFresh.initialize() COMPLETE ==========");
    }

    @Override
    public void stop() {
        System.err.println("========== OrderFlowStrategyFresh.stop() ==========");
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        orderBook.onUpdate(true, bidPrice, bidSize);
        orderBook.onUpdate(false, askPrice, askSize);

        // Plot at bid/ask prices (no division needed!)
        bidIndicator.addPoint(bidPrice);
        askIndicator.addPoint(askPrice);
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        currentDelta += size;
        cumulativeDelta += size;

        // Plot at trade price (no division needed!)
        deltaIndicator.addPoint(price);

        // Log every 100 trades
        if (cumulativeDelta % 100 == 0) {
            System.err.println("Delta: " + currentDelta + ", Cumulative: " + cumulativeDelta);
        }
    }
}
