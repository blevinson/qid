package velox.api.layer1.simpledemo.multipleaccount;

import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;

/**
 * This class is not annotated with @Layer1MultiAccountTradingSupported, so no multiple account data
 * should be received in MultipleAccountSupportDemoBase.
 * <br>
 * See {@link MultipleAccountSupportDemo} and {@link MultipleAccountSupportDemoBase} to see how to handle multiple
 * account data, but with this class such should not be received.
 */
@Layer1Attachable
@Layer1StrategyName("No Multiple Account Support Demo")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class NoMultipleAccountSupportDemo extends MultipleAccountSupportDemoBase {
    
    public NoMultipleAccountSupportDemo(Layer1ApiProvider provider) {
        super(provider);
    }
}
