package velox.api.layer1.simplified.demo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.Indicator;

/**
 * Order Flow Icons - Simplified API version
 * Reads signal file and displays as BUY/SELL indicators
 */
// @Layer1SimpleAttachable
// @Layer1StrategyName("OF Icons Simple")  - Hidden from Bookmap list
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class OrderFlowIconsSimple implements CustomModule {

    private static final String SIGNAL_FILE_PATH = "/Users/brant/bl-projects/DemoStrategies/mbo_signals.txt";

    private Indicator buyIndicator;
    private Indicator sellIndicator;

    private int lastLineRead = 0;
    private volatile boolean running = true;
    private Thread readerThread;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        System.err.println("OF Icons Simple: INITIALIZED for " + alias);
        System.err.println("OF Icons Simple: Starting signal reader from: " + SIGNAL_FILE_PATH);

        // Register indicators
        buyIndicator = api.registerIndicator("BUY Signal", GraphType.PRIMARY);
        sellIndicator = api.registerIndicator("SELL Signal", GraphType.PRIMARY);

        buyIndicator.setColor(java.awt.Color.GREEN);
        sellIndicator.setColor(java.awt.Color.RED);

        // Start signal reader thread
        startSignalReader(api);
    }

    private void startSignalReader(Api api) {
        readerThread = new Thread(() -> {
            while (running) {
                try {
                    readSignalFile(api);
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        System.err.println("OF Icons Simple: Signal reader thread started");
    }

    private void readSignalFile(Api api) {
        try (BufferedReader reader = new BufferedReader(new FileReader(SIGNAL_FILE_PATH))) {
            // Skip already-read lines
            for (int i = 0; i < lastLineRead; i++) {
                if (reader.readLine() == null) break;
            }

            // Read new lines
            String line;
            int lineNum = lastLineRead;
            int newLinesCount = 0;
            while ((line = reader.readLine()) != null) {
                processSignal(line, api);
                lineNum++;
                newLinesCount++;
            }
            lastLineRead = lineNum;

            if (newLinesCount > 0) {
                System.err.println("OF Icons Simple: Read " + newLinesCount + " new signals (total: " + lineNum + ")");
            }
        } catch (IOException e) {
            // File might not exist yet - log once per minute
            if (System.currentTimeMillis() % 60000 < 100) {
                System.err.println("OF Icons Simple: Signal file not found yet (will retry): " + SIGNAL_FILE_PATH);
            }
        }
    }

    private void processSignal(String line, Api api) {
        // Format: TYPE|DIRECTION|PRICE|SIZE
        String[] parts = line.split("\\|");
        if (parts.length != 4) return;

        String type = parts[0];
        String direction = parts[1];

        int price;
        int size;
        try {
            price = Integer.parseInt(parts[2]);
            size = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }

        // Determine if this is a buy or sell signal
        boolean isBuy;
        if (type.equals("ICEBERG")) {
            isBuy = direction.equals("BUY");
        } else if (type.equals("SPOOF")) {
            // Fade the spoof - opposite direction
            isBuy = direction.equals("SELL");
        } else if (type.equals("ABSORPTION")) {
            // Fade absorption
            isBuy = !direction.equals("FADE") && !direction.equals("SELL");
        } else {
            return;
        }

        // Plot indicator with safety checks
        if (running && buyIndicator != null && sellIndicator != null) {
            if (isBuy) {
                buyIndicator.addPoint(price);
                System.err.println("OF Icons Simple: BUY signal @" + price);
            } else {
                sellIndicator.addPoint(price);
                System.err.println("OF Icons Simple: SELL signal @" + price);
            }
        }
    }

    @Override
    public void stop() {
        System.err.println("OF Icons Simple: STOPPING");
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
