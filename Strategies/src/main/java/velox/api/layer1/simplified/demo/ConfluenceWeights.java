package velox.api.layer1.simplified.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * Adjustable Confluence Weights
 *
 * Allows AI to adjust the weights of individual confluence factors.
 * All weights have safety bounds to prevent extreme values.
 *
 * Weight Categories:
 * - ICEBERG: Iceberg detection score (base multiplier)
 * - CVD_ALIGN: CVD alignment bonus
 * - CVD_DIVERGE: CVD divergence penalty (negative)
 * - VOLUME_PROFILE: Volume profile scoring
 * - VOLUME_IMBALANCE: Volume imbalance scoring
 * - EMA_ALIGN: EMA trend alignment bonus
 * - EMA_DIVERGE: EMA divergence penalty (negative)
 * - VWAP: VWAP alignment scoring
 * - TIME_OF_DAY: Time-based scoring
 * - DOM: Depth of Market alignment
 */
public class ConfluenceWeights {

    // Weight definitions with safety bounds
    public static class WeightDef {
        public final String name;
        public final int defaultValue;
        public final int minValue;
        public final int maxValue;
        public final String description;

        public WeightDef(String name, int defaultValue, int minValue, int maxValue, String description) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.description = description;
        }
    }

    // All weight definitions
    public static final Map<String, WeightDef> DEFINITIONS = new HashMap<>();

    // Weight keys
    public static final String ICEBERG_MAX = "icebergMax";
    public static final String ICEBERG_MULTIPLIER = "icebergMultiplier";
    public static final String CVD_ALIGN_MAX = "cvdAlignMax";
    public static final String CVD_DIVERGE_PENALTY = "cvdDivergePenalty";
    public static final String VOLUME_PROFILE_MAX = "volumeProfileMax";
    public static final String VOLUME_IMBALANCE_MAX = "volumeImbalanceMax";
    public static final String EMA_ALIGN_MAX = "emaAlignMax";
    public static final String EMA_ALIGN_PARTIAL = "emaAlignPartial";
    public static final String EMA_DIVERGE_PENALTY = "emaDivergePenalty";
    public static final String EMA_DIVERGE_PARTIAL = "emaDivergePartial";
    public static final String VWAP_ALIGN = "vwapAlign";
    public static final String VWAP_DIVERGE = "vwapDiverge";
    public static final String TIME_OF_DAY_MAX = "timeOfDayMax";
    public static final String TIME_OF_DAY_SECONDARY = "timeOfDaySecondary";
    public static final String DOM_MAX = "domMax";

    static {
        // Iceberg detection
        DEFINITIONS.put(ICEBERG_MAX, new WeightDef(ICEBERG_MAX, 40, 20, 60,
            "Max points for iceberg detection (totalSize * multiplier, capped)"));
        DEFINITIONS.put(ICEBERG_MULTIPLIER, new WeightDef(ICEBERG_MULTIPLIER, 2, 1, 4,
            "Points per iceberg order"));

        // CVD alignment/divergence
        DEFINITIONS.put(CVD_ALIGN_MAX, new WeightDef(CVD_ALIGN_MAX, 25, 10, 40,
            "Max bonus when CVD aligns with signal direction"));
        DEFINITIONS.put(CVD_DIVERGE_PENALTY, new WeightDef(CVD_DIVERGE_PENALTY, 30, 15, 50,
            "Penalty when CVD opposes signal direction (absolute value)"));

        // Volume profile
        DEFINITIONS.put(VOLUME_PROFILE_MAX, new WeightDef(VOLUME_PROFILE_MAX, 20, 10, 30,
            "Max points for high volume node"));

        // Volume imbalance
        DEFINITIONS.put(VOLUME_IMBALANCE_MAX, new WeightDef(VOLUME_IMBALANCE_MAX, 10, 5, 20,
            "Max points for volume imbalance alignment"));

        // EMA alignment/divergence
        DEFINITIONS.put(EMA_ALIGN_MAX, new WeightDef(EMA_ALIGN_MAX, 20, 10, 30,
            "Bonus when price is on correct side of all EMAs"));
        DEFINITIONS.put(EMA_ALIGN_PARTIAL, new WeightDef(EMA_ALIGN_PARTIAL, 10, 5, 15,
            "Bonus for partial EMA alignment (2 of 3)"));
        DEFINITIONS.put(EMA_DIVERGE_PENALTY, new WeightDef(EMA_DIVERGE_PENALTY, 15, 8, 25,
            "Penalty when price is on wrong side of all EMAs (absolute value)"));
        DEFINITIONS.put(EMA_DIVERGE_PARTIAL, new WeightDef(EMA_DIVERGE_PARTIAL, 8, 4, 12,
            "Penalty for partial EMA divergence (absolute value)"));

        // VWAP
        DEFINITIONS.put(VWAP_ALIGN, new WeightDef(VWAP_ALIGN, 10, 5, 15,
            "Bonus when aligned with VWAP"));
        DEFINITIONS.put(VWAP_DIVERGE, new WeightDef(VWAP_DIVERGE, 5, 2, 10,
            "Penalty when against VWAP (absolute value)"));

        // Time of day
        DEFINITIONS.put(TIME_OF_DAY_MAX, new WeightDef(TIME_OF_DAY_MAX, 5, 0, 15,
            "Bonus during prime trading hours"));
        DEFINITIONS.put(TIME_OF_DAY_SECONDARY, new WeightDef(TIME_OF_DAY_SECONDARY, 2, 0, 8,
            "Bonus during secondary trading hours"));

        // DOM
        DEFINITIONS.put(DOM_MAX, new WeightDef(DOM_MAX, 10, 5, 20,
            "Max adjustment from DOM imbalance"));
    }

    // Current weight values
    private final Map<String, Integer> weights = new HashMap<>();

    public ConfluenceWeights() {
        // Initialize with defaults
        resetToDefaults();
    }

    /**
     * Reset all weights to default values
     */
    public void resetToDefaults() {
        for (Map.Entry<String, WeightDef> entry : DEFINITIONS.entrySet()) {
            weights.put(entry.getKey(), entry.getValue().defaultValue);
        }
    }

    /**
     * Get a weight value (always within safety bounds)
     */
    public int get(String key) {
        WeightDef def = DEFINITIONS.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown weight: " + key);
        }
        Integer value = weights.get(key);
        if (value == null) {
            return def.defaultValue;
        }
        return clamp(value, def.minValue, def.maxValue);
    }

    /**
     * Set a weight value (clamped to safety bounds)
     * @return the actual value set (after clamping)
     */
    public int set(String key, int value) {
        WeightDef def = DEFINITIONS.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown weight: " + key);
        }
        int clamped = clamp(value, def.minValue, def.maxValue);
        weights.put(key, clamped);
        return clamped;
    }

    /**
     * Apply adjustments from AI recommendation
     * Only non-null values are applied
     */
    public void applyAdjustments(WeightAdjustment adjustment) {
        if (adjustment == null) return;

        if (adjustment.icebergMax != null) set(ICEBERG_MAX, adjustment.icebergMax);
        if (adjustment.icebergMultiplier != null) set(ICEBERG_MULTIPLIER, adjustment.icebergMultiplier);
        if (adjustment.cvdAlignMax != null) set(CVD_ALIGN_MAX, adjustment.cvdAlignMax);
        if (adjustment.cvdDivergePenalty != null) set(CVD_DIVERGE_PENALTY, adjustment.cvdDivergePenalty);
        if (adjustment.volumeProfileMax != null) set(VOLUME_PROFILE_MAX, adjustment.volumeProfileMax);
        if (adjustment.volumeImbalanceMax != null) set(VOLUME_IMBALANCE_MAX, adjustment.volumeImbalanceMax);
        if (adjustment.emaAlignMax != null) set(EMA_ALIGN_MAX, adjustment.emaAlignMax);
        if (adjustment.emaAlignPartial != null) set(EMA_ALIGN_PARTIAL, adjustment.emaAlignPartial);
        if (adjustment.emaDivergePenalty != null) set(EMA_DIVERGE_PENALTY, adjustment.emaDivergePenalty);
        if (adjustment.emaDivergePartial != null) set(EMA_DIVERGE_PARTIAL, adjustment.emaDivergePartial);
        if (adjustment.vwapAlign != null) set(VWAP_ALIGN, adjustment.vwapAlign);
        if (adjustment.vwapDiverge != null) set(VWAP_DIVERGE, adjustment.vwapDiverge);
        if (adjustment.timeOfDayMax != null) set(TIME_OF_DAY_MAX, adjustment.timeOfDayMax);
        if (adjustment.timeOfDaySecondary != null) set(TIME_OF_DAY_SECONDARY, adjustment.timeOfDaySecondary);
        if (adjustment.domMax != null) set(DOM_MAX, adjustment.domMax);
    }

    /**
     * Clamp value to bounds
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Get current weights as a map (for serialization)
     */
    public Map<String, Integer> getAll() {
        return new HashMap<>(weights);
    }

    /**
     * Set weights from a map (values are clamped)
     */
    public void setAll(Map<String, Integer> values) {
        if (values == null) return;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (DEFINITIONS.containsKey(entry.getKey())) {
                set(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Get maximum possible confluence score with current weights
     */
    public int getMaxPossibleScore() {
        return get(ICEBERG_MAX) + get(CVD_ALIGN_MAX) + get(VOLUME_PROFILE_MAX) +
               get(VOLUME_IMBALANCE_MAX) + get(EMA_ALIGN_MAX) + get(VWAP_ALIGN) +
               get(TIME_OF_DAY_MAX) + get(DOM_MAX);
    }

    /**
     * Create a summary of current weights for AI prompt
     */
    public String toAIString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CURRENT CONFLUENCE WEIGHTS:\n");
        sb.append(String.format("- Iceberg: max=%d, per_order=%d\n",
            get(ICEBERG_MAX), get(ICEBERG_MULTIPLIER)));
        sb.append(String.format("- CVD: align_bonus=%d, diverge_penalty=-%d\n",
            get(CVD_ALIGN_MAX), get(CVD_DIVERGE_PENALTY)));
        sb.append(String.format("- Volume Profile: max=%d\n", get(VOLUME_PROFILE_MAX)));
        sb.append(String.format("- Volume Imbalance: max=%d\n", get(VOLUME_IMBALANCE_MAX)));
        sb.append(String.format("- EMA: align=%d (partial=%d), diverge=-%d (partial=-%d)\n",
            get(EMA_ALIGN_MAX), get(EMA_ALIGN_PARTIAL),
            get(EMA_DIVERGE_PENALTY), get(EMA_DIVERGE_PARTIAL)));
        sb.append(String.format("- VWAP: align=%d, diverge=-%d\n",
            get(VWAP_ALIGN), get(VWAP_DIVERGE)));
        sb.append(String.format("- Time of Day: prime=%d, secondary=%d\n",
            get(TIME_OF_DAY_MAX), get(TIME_OF_DAY_SECONDARY)));
        sb.append(String.format("- DOM: max_adjustment=+/-%d\n", get(DOM_MAX)));
        sb.append(String.format("MAX POSSIBLE SCORE: %d\n", getMaxPossibleScore()));
        return sb.toString();
    }

    /**
     * Get safety bounds info for AI prompt
     */
    public static String getBoundsInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("WEIGHT ADJUSTMENT BOUNDS:\n");
        for (Map.Entry<String, WeightDef> entry : DEFINITIONS.entrySet()) {
            WeightDef def = entry.getValue();
            sb.append(String.format("- %s: %d-%d (default: %d) - %s\n",
                def.name, def.minValue, def.maxValue, def.defaultValue, def.description));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ConfluenceWeights" + weights;
    }

    /**
     * Weight Adjustment from AI
     * All fields are optional - only non-null values are applied
     */
    public static class WeightAdjustment {
        public Integer icebergMax;
        public Integer icebergMultiplier;
        public Integer cvdAlignMax;
        public Integer cvdDivergePenalty;
        public Integer volumeProfileMax;
        public Integer volumeImbalanceMax;
        public Integer emaAlignMax;
        public Integer emaAlignPartial;
        public Integer emaDivergePenalty;
        public Integer emaDivergePartial;
        public Integer vwapAlign;
        public Integer vwapDiverge;
        public Integer timeOfDayMax;
        public Integer timeOfDaySecondary;
        public Integer domMax;
        public String reasoning;

        public boolean hasAdjustments() {
            return icebergMax != null || icebergMultiplier != null ||
                   cvdAlignMax != null || cvdDivergePenalty != null ||
                   volumeProfileMax != null || volumeImbalanceMax != null ||
                   emaAlignMax != null || emaAlignPartial != null ||
                   emaDivergePenalty != null || emaDivergePartial != null ||
                   vwapAlign != null || vwapDiverge != null ||
                   timeOfDayMax != null || timeOfDaySecondary != null ||
                   domMax != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("WeightAdjustment{");
            if (icebergMax != null) sb.append("icebergMax=").append(icebergMax).append(" ");
            if (icebergMultiplier != null) sb.append("icebergMultiplier=").append(icebergMultiplier).append(" ");
            if (cvdAlignMax != null) sb.append("cvdAlignMax=").append(cvdAlignMax).append(" ");
            if (cvdDivergePenalty != null) sb.append("cvdDivergePenalty=").append(cvdDivergePenalty).append(" ");
            if (volumeProfileMax != null) sb.append("volumeProfileMax=").append(volumeProfileMax).append(" ");
            if (volumeImbalanceMax != null) sb.append("volumeImbalanceMax=").append(volumeImbalanceMax).append(" ");
            if (emaAlignMax != null) sb.append("emaAlignMax=").append(emaAlignMax).append(" ");
            if (emaAlignPartial != null) sb.append("emaAlignPartial=").append(emaAlignPartial).append(" ");
            if (emaDivergePenalty != null) sb.append("emaDivergePenalty=").append(emaDivergePenalty).append(" ");
            if (emaDivergePartial != null) sb.append("emaDivergePartial=").append(emaDivergePartial).append(" ");
            if (vwapAlign != null) sb.append("vwapAlign=").append(vwapAlign).append(" ");
            if (vwapDiverge != null) sb.append("vwapDiverge=").append(vwapDiverge).append(" ");
            if (timeOfDayMax != null) sb.append("timeOfDayMax=").append(timeOfDayMax).append(" ");
            if (timeOfDaySecondary != null) sb.append("timeOfDaySecondary=").append(timeOfDaySecondary).append(" ");
            if (domMax != null) sb.append("domMax=").append(domMax).append(" ");
            sb.append("reasoning='").append(reasoning).append("'}");
            return sb.toString();
        }
    }
}
