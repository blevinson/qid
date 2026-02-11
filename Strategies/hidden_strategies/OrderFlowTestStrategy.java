package velox.api.layer1.simplified.demo;

import java.awt.Color;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Fresh Order Flow Test Strategy - No previous settings history
 */
// @Layer1SimpleAttachable
// @Layer1StrategyName("Order Flow Test 2026")  - Hidden from Bookmap list
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowTestStrategy implements CustomModule, TradeDataListener {

    private Indicator testIndicator;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("******** OrderFlowTestStrategy.initialize() CALLED for " + alias + " ********");
        System.err.println("******** Pips: " + info.pips + " ********");

        testIndicator = api.registerIndicator("Test 2026", GraphType.PRIMARY);
        testIndicator.setColor(Color.MAGENTA);

        System.err.println("******** OrderFlowTestStrategy initialization COMPLETE ********");
    }

    @Override
    public void stop() {
        System.err.println("******** OrderFlowTestStrategy.stop() CALLED ********");
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Plot every trade as a magenta point
        testIndicator.addPoint(price);
    }
}
