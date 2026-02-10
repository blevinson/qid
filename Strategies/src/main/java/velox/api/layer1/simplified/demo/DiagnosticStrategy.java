package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

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

@Layer1SimpleAttachable
@Layer1StrategyName("DIAGNOSTIC")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class DiagnosticStrategy implements CustomModule, TradeDataListener, BboListener {

    private Indicator indicator;
    private PrintWriter logWriter;
    private int bboCount = 0;
    private int tradeCount = 0;
    private String logPath = "/Users/brant/bl-projects/DemoStrategies/diagnostic_log.txt";

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        try {
            logWriter = new PrintWriter(new FileWriter(logPath, false));
            logWriter.println("========== DIAGNOSTIC STRATEGY INITIALIZE ==========");
            logWriter.println("Time: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
            logWriter.println("Alias: " + alias);
            logWriter.println("Pips: " + info.pips);
            logWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        indicator = api.registerIndicator("DIAG", GraphType.PRIMARY);
        indicator.setColor(Color.MAGENTA);

        log("Indicator registered: MAGENTA");
    }

    @Override
    public void stop() {
        log("STOP - BBO: " + bboCount + ", Trades: " + tradeCount);
        if (logWriter != null) {
            logWriter.close();
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        bboCount++;
        indicator.addPoint(askPrice / 100.0);

        if (bboCount <= 10) {
            log("BBO #" + bboCount + ": Bid=" + bidPrice + "/" + bidSize + " Ask=" + askPrice + "/" + askSize);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        tradeCount++;
        indicator.addPoint(price / 100.0);

        if (tradeCount <= 10) {
            log("TRADE #" + tradeCount + ": Price=" + price + " Size=" + size);
        }
    }

    private void log(String message) {
        if (logWriter != null) {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            logWriter.println(timestamp + " - " + message);
            logWriter.flush();
        }
    }
}
