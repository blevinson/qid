package velox.api.layer1.simpledemo.multipleaccount;

import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1MultiAccountTradingSupported;
import velox.api.layer1.annotations.Layer1StrategyName;

/**
 * This class is  annotated with @Layer1MultiAccountTradingSupported, so there should be multiple account data
 * received in MultipleAccountSupportDemoBase.
 * <br>
 * See {@link MultipleAccountSupportDemoBase} to see how to handle multiple account data.
 */
@Layer1Attachable
@Layer1StrategyName("Multiple Account Support Demo")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1MultiAccountTradingSupported
public class MultipleAccountSupportDemo extends MultipleAccountSupportDemoBase {
    
    public MultipleAccountSupportDemo(Layer1ApiProvider provider) {
        super(provider);
    }
}
