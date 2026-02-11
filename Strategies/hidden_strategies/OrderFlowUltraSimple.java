package velox.api.layer1.simplified.demo;

import java.awt.Color;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

// @Layer1SimpleAttachable
// @Layer1StrategyName("WHITE DOTS")  - Hidden from Bookmap list
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowUltraSimple implements CustomModule, TradeDataListener, BboListener {

    private Indicator whiteIndicator;
    private double pips;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.pips = info.pips;
        whiteIndicator = api.registerIndicator("WHITE", GraphType.PRIMARY);
        whiteIndicator.setColor(Color.WHITE);
    }

    @Override
    public void stop() {}

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        // Plot white dot at ask price for every BBO update (no division!)
        whiteIndicator.addPoint(askPrice);
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        // Plot white dot at trade price (no division!)
        whiteIndicator.addPoint(price);
    }
}
