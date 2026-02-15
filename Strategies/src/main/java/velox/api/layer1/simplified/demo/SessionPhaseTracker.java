package velox.api.layer1.simplified.demo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Session Phase Tracker
 *
 * Automatically tracks trading session phases and triggers callbacks on transitions.
 * Uses Eastern Time (America/New_York) for phase boundaries.
 *
 * Phases:
 * - PRE_MARKET: Before 9:30 AM ET
 * - OPENING_RANGE: 9:30 AM - 10:00 AM ET
 * - MORNING_SESSION: 10:00 AM - 12:00 PM ET
 * - LUNCH: 12:00 PM - 2:00 PM ET
 * - AFTERNOON: 2:00 PM - 3:30 PM ET
 * - CLOSE: 3:30 PM - 4:00 PM ET
 * - AFTER_HOURS: After 4:00 PM ET
 *
 * Usage:
 * - Call start() to begin tracking
 * - Call stop() to shutdown
 * - Set onPhaseTransition callback to handle transitions
 */
public class SessionPhaseTracker {

    /**
     * Trading session phases
     */
    public enum SessionPhase {
        PRE_MARKET("Pre-Market", "Before market open"),
        OPENING_RANGE("Opening Range", "9:30-10:00 AM ET - High volatility, establishing direction"),
        MORNING_SESSION("Morning Session", "10:00 AM-12:00 PM ET - Trend trades, high conviction"),
        LUNCH("Lunch Doldrums", "12:00-2:00 PM ET - Lower volume, reduce size"),
        AFTERNOON("Afternoon Session", "2:00-3:30 PM ET - Trend resumption or reversal"),
        CLOSE("Market Close", "3:30-4:00 PM ET - Late-day positioning, MOC flows"),
        AFTER_HOURS("After Hours", "After 4:00 PM ET - Market closed");

        private final String displayName;
        private final String description;

        SessionPhase(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    // Timezone for phase boundaries
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // Phase boundaries in HHMM format (ET)
    private static final int PRE_MARKET_END = 930;     // 9:30 AM
    private static final int OPENING_END = 1000;       // 10:00 AM
    private static final int MORNING_END = 1200;       // 12:00 PM
    private static final int LUNCH_END = 1400;         // 2:00 PM
    private static final int AFTERNOON_END = 1530;     // 3:30 PM
    private static final int CLOSE_END = 1600;         // 4:00 PM

    // Scheduler for periodic checks
    private ScheduledExecutorService scheduler;

    // Current phase (atomic for thread safety)
    private final AtomicReference<SessionPhase> currentPhase = new AtomicReference<>(null);

    // Callback for phase transitions
    private Consumer<PhaseTransition> onPhaseTransition;

    // Logging
    private Consumer<String> logger;

    // Check interval in seconds
    private final int checkIntervalSeconds;

    // Data time supplier for replay mode support (uses Bookmap data time instead of wall clock)
    private Supplier<Long> dataTimeSupplier;

    // Last checked data time to detect phase changes in replay mode
    private long lastCheckedDataTime = 0;

    /**
     * Phase transition event
     */
    public static class PhaseTransition {
        public final SessionPhase fromPhase;
        public final SessionPhase toPhase;
        public final LocalTime time;
        public final String date;

        public PhaseTransition(SessionPhase fromPhase, SessionPhase toPhase, LocalTime time, String date) {
            this.fromPhase = fromPhase;
            this.toPhase = toPhase;
            this.time = time;
            this.date = date;
        }

        @Override
        public String toString() {
            return String.format("PhaseTransition[%s → %s at %s]",
                fromPhase != null ? fromPhase.name() : "NULL",
                toPhase.name(),
                time.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }

    /**
     * Create a new phase tracker
     *
     * @param checkIntervalSeconds How often to check for phase transitions (default 60)
     */
    public SessionPhaseTracker(int checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    /**
     * Create a phase tracker with default 60-second check interval
     */
    public SessionPhaseTracker() {
        this(60);
    }

    /**
     * Set the callback for phase transitions
     */
    public void setOnPhaseTransition(Consumer<PhaseTransition> callback) {
        this.onPhaseTransition = callback;
    }

    /**
     * Set the logger for debug output
     */
    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Set the data time supplier for replay mode support
     * When set, phase detection uses Bookmap's data timestamp instead of wall clock
     *
     * @param supplier Supplier that returns current data timestamp in milliseconds
     */
    public void setDataTimeSupplier(Supplier<Long> supplier) {
        this.dataTimeSupplier = supplier;
        log("📊 Phase tracker configured for replay mode (using data time)");
    }

    /**
     * Start tracking phases
     */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log("⚠️ Phase tracker already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionPhaseTracker");
            t.setDaemon(true);
            return t;
        });

        // Initial check immediately
        checkPhase();

        // Schedule periodic checks
        scheduler.scheduleAtFixedRate(
            this::checkPhase,
            checkIntervalSeconds,
            checkIntervalSeconds,
            TimeUnit.SECONDS
        );

        log("📊 Phase tracker started (checking every " + checkIntervalSeconds + "s)");
    }

    /**
     * Stop tracking phases
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log("📊 Phase tracker stopped");
        }
    }

    /**
     * Get current phase
     */
    public SessionPhase getCurrentPhase() {
        SessionPhase phase = currentPhase.get();
        return phase != null ? phase : determineCurrentPhase();
    }

    /**
     * Check for phase transition
     */
    private void checkPhase() {
        try {
            // In replay mode, check if data time has advanced enough to warrant a phase check
            // This prevents excessive checks when data is flowing fast
            if (dataTimeSupplier != null) {
                long currentDataTime = dataTimeSupplier.get();
                // Only check if data time has advanced by at least 30 seconds
                if (currentDataTime - lastCheckedDataTime < 30000) {
                    return;
                }
                lastCheckedDataTime = currentDataTime;
            }

            SessionPhase newPhase = determineCurrentPhase();
            SessionPhase oldPhase = currentPhase.getAndSet(newPhase);

            // Only trigger callback on actual transition
            if (oldPhase != null && oldPhase != newPhase) {
                // Use data time if available, otherwise wall clock
                LocalTime now;
                String date;
                if (dataTimeSupplier != null) {
                    long dataTs = dataTimeSupplier.get();
                    ZonedDateTime dataTime = Instant.ofEpochMilli(dataTs).atZone(ET_ZONE);
                    now = dataTime.toLocalTime();
                    date = dataTime.toLocalDate().toString();
                } else {
                    now = LocalTime.now(ET_ZONE);
                    date = java.time.LocalDate.now(ET_ZONE).toString();
                }

                PhaseTransition transition = new PhaseTransition(oldPhase, newPhase, now, date);

                log("📊 PHASE TRANSITION: " + oldPhase.getDisplayName() + " → " + newPhase.getDisplayName());

                if (onPhaseTransition != null) {
                    try {
                        onPhaseTransition.accept(transition);
                    } catch (Exception e) {
                        log("❌ Phase transition callback error: " + e.getMessage());
                    }
                }
            } else if (oldPhase == null) {
                // First check - just log current phase
                log("📊 Current phase: " + newPhase.getDisplayName() + " (" + newPhase.getDescription() + ")");
            }
        } catch (Exception e) {
            log("❌ Phase check error: " + e.getMessage());
        }
    }

    /**
     * Determine current phase based on time
     * Uses data time supplier if available (replay mode), otherwise wall clock
     */
    private SessionPhase determineCurrentPhase() {
        LocalTime now;
        if (dataTimeSupplier != null) {
            // Use Bookmap data time (replay-safe)
            long dataTs = dataTimeSupplier.get();
            ZonedDateTime dataTime = Instant.ofEpochMilli(dataTs).atZone(ET_ZONE);
            now = dataTime.toLocalTime();
        } else {
            // Use wall clock time
            now = LocalTime.now(ET_ZONE);
        }
        int timeAsInt = now.getHour() * 100 + now.getMinute();

        return determinePhase(timeAsInt);
    }

    /**
     * Determine phase from time integer (HHMM)
     */
    public static SessionPhase determinePhase(int timeAsInt) {
        if (timeAsInt < PRE_MARKET_END) {
            return SessionPhase.PRE_MARKET;
        } else if (timeAsInt < OPENING_END) {
            return SessionPhase.OPENING_RANGE;
        } else if (timeAsInt < MORNING_END) {
            return SessionPhase.MORNING_SESSION;
        } else if (timeAsInt < LUNCH_END) {
            return SessionPhase.LUNCH;
        } else if (timeAsInt < AFTERNOON_END) {
            return SessionPhase.AFTERNOON;
        } else if (timeAsInt < CLOSE_END) {
            return SessionPhase.CLOSE;
        } else {
            return SessionPhase.AFTER_HOURS;
        }
    }

    /**
     * Get phase display info
     */
    public static String getPhaseInfo(SessionPhase phase) {
        return String.format("%s: %s", phase.getDisplayName(), phase.getDescription());
    }

    /**
     * Check if we're in active trading hours (RTH)
     */
    public boolean isInActiveTradingHours() {
        SessionPhase phase = getCurrentPhase();
        return phase != SessionPhase.PRE_MARKET &&
               phase != SessionPhase.AFTER_HOURS;
    }

    /**
     * Check if current phase is good for trading (not lunch, not close)
     */
    public boolean isGoodForTrading() {
        SessionPhase phase = getCurrentPhase();
        return phase == SessionPhase.OPENING_RANGE ||
               phase == SessionPhase.MORNING_SESSION ||
               phase == SessionPhase.AFTERNOON;
    }

    /**
     * Get recommended behavior for current phase
     */
    public String getRecommendedBehavior() {
        SessionPhase phase = getCurrentPhase();
        return switch (phase) {
            case PRE_MARKET -> "Prepare, analyze, set expectations";
            case OPENING_RANGE -> "Higher thresholds, wait for direction";
            case MORNING_SESSION -> "Normal trading, high conviction";
            case LUNCH -> "Reduce size, raise thresholds, avoid chop";
            case AFTERNOON -> "Resume normal trading";
            case CLOSE -> "Tighten up, avoid new positions";
            case AFTER_HOURS -> "Market closed";
        };
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}
