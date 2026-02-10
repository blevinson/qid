package velox.api.layer1.simplified.demo;

import java.awt.Color;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;

/**
 * Simple Order Flow Strategy - Minimal test version
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Order Flow Strategy Simple")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowStrategySimple implements CustomModule {

    private Indicator testIndicator;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("******** OrderFlowStrategySimple.initialize() CALLED for " + alias + " ********");
        System.err.println("******** Pips: " + info.pips + " ********");

        testIndicator = api.registerIndicator("Test Indicator", GraphType.PRIMARY);
        testIndicator.setColor(Color.GREEN);

        System.err.println("******** OrderFlowStrategySimple initialization COMPLETE ********");
    }

    @Override
    public void stop() {
        System.err.println("******** OrderFlowStrategySimple.stop() CALLED ********");
    }
}
