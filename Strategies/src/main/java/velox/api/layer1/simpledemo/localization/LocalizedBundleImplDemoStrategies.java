package velox.api.layer1.simpledemo.localization;

import com.ibm.icu.util.ULocale;
import velox.gui.utils.localization.BookmapLocale;
import velox.gui.utils.localization.LocalizedBundleImpl;

public class LocalizedBundleImplDemoStrategies extends LocalizedBundleImpl {

    public static final String DEMO_STRATEGIES_BUNDLE_NAME = "resources.locale.DemoStrategies";

    protected LocalizedBundleImplDemoStrategies(String bundleName, ULocale locale) {
        super(bundleName, locale);
    }

    public static LocalizedBundleImplDemoStrategies getInstance() {
        return getInstance(BookmapLocale.getCurrentULocale());
    }

    public static LocalizedBundleImplDemoStrategies getInstance(ULocale locale) {
        return new LocalizedBundleImplDemoStrategies(DEMO_STRATEGIES_BUNDLE_NAME, locale);
    }
}
