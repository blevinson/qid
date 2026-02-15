package velox.api.layer1.simplified.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SignalData.RrQuality assessment calculations
 * Verifies R:R quality scoring, position sizing recommendations, and guidance generation
 */
public class RrQualityTest {

    // ========== Quality Level Tests ==========

    @Test
    @DisplayName("EXCELLENT quality for R:R >= 3.0")
    public void testExcellentQuality() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            3.0, 1.5,   // rrRatio=3.0, minRequired=1.5
            10, 30,      // slTicks=10, tpTicks=30
            "ATR", "ATR",
            "NONE", "",
            false, 0, 0
        );

        assertEquals("EXCELLENT", quality.qualityLevel);
        assertEquals(15, quality.qualityScore);
        assertEquals("🌟", quality.qualityEmoji);
        assertTrue(quality.meetsMinimum);
        assertEquals("FULL", quality.sizingRecommendation);
        assertEquals(1.0, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("GOOD quality for R:R >= 2.0")
    public void testGoodQuality() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            2.5, 1.5,
            10, 25,
            "ATR", "DOM_RESISTANCE",
            "NONE", "",
            false, 0, 0
        );

        assertEquals("GOOD", quality.qualityLevel);
        assertEquals(10, quality.qualityScore);
        assertEquals("✅", quality.qualityEmoji);
        assertTrue(quality.meetsMinimum);
        assertEquals("FULL", quality.sizingRecommendation);
    }

    @Test
    @DisplayName("ACCEPTABLE quality when R:R meets minimum")
    public void testAcceptableQuality() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.5, 1.5,  // Exactly at minimum
            20, 30,
            "DOM_SUPPORT", "ATR",
            "NONE", "",
            false, 0, 0
        );

        assertEquals("ACCEPTABLE", quality.qualityLevel);
        assertEquals(5, quality.qualityScore);
        assertEquals("👍", quality.qualityEmoji);
        assertTrue(quality.meetsMinimum);
        assertEquals("FULL", quality.sizingRecommendation);
    }

    @Test
    @DisplayName("MARGINAL quality at 80% of minimum")
    public void testMarginalQuality() {
        // Use 1.25 for rrRatio to be clearly above 1.5*0.8=1.2 boundary
        // (avoiding floating point boundary issues)
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.25, 1.5,  // 83% of minimum (clearly > 80%)
            24, 30,
            "ATR", "ATR",
            "POOR_RR", "R:R below minimum",
            false, 0, 0
        );

        assertEquals("MARGINAL", quality.qualityLevel);
        assertEquals(-5, quality.qualityScore);
        assertEquals("⚠️", quality.qualityEmoji);
        assertFalse(quality.meetsMinimum);
        assertEquals("REDUCED_75", quality.sizingRecommendation);
        assertEquals(0.75, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("POOR quality at 67% of minimum")
    public void testPoorQuality() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.0, 1.5,  // 67% of minimum
            30, 30,
            "FIXED", "FIXED",
            "POOR_RR", "R:R = 1:1.0",
            false, 0, 0
        );

        assertEquals("POOR", quality.qualityLevel);
        assertEquals(-10, quality.qualityScore);
        assertEquals("❌", quality.qualityEmoji);
        assertFalse(quality.meetsMinimum);
        assertEquals("REDUCED_50", quality.sizingRecommendation);
        assertEquals(0.5, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("VERY_POOR quality below 1.0 R:R")
    public void testVeryPoorQuality() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            0.8, 1.5,  // Below 1:1
            40, 32,
            "FIXED", "FIXED",
            "WIDE_SL", "Stop too wide",
            false, 0, 0
        );

        assertEquals("VERY_POOR", quality.qualityLevel);
        assertEquals(-15, quality.qualityScore);
        assertEquals("🚫", quality.qualityEmoji);
        assertFalse(quality.meetsMinimum);
        // 0.8 < 0.9 (minRequired * 0.6) and 0.8 < 1.0, so AVOID
        assertEquals("AVOID", quality.sizingRecommendation);
        assertEquals(0.0, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("AVOID recommendation for very low R:R")
    public void testAvoidRecommendation() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            0.5, 1.5,
            50, 25,
            "FIXED", "FIXED",
            "WIDE_SL", "Stop way too wide",
            false, 0, 0
        );

        assertEquals("VERY_POOR", quality.qualityLevel);
        assertEquals("AVOID", quality.sizingRecommendation);
        assertEquals(0.0, quality.suggestedRiskPercent, 0.01);
    }

    // ========== Score Boundary Tests ==========

    @Test
    @DisplayName("Verify exact boundary at 3.0 for EXCELLENT")
    public void testExcellentBoundary() {
        // Just below 3.0
        SignalData.RrQuality quality1 = SignalData.RrQuality.assess(
            2.99, 1.5, 10, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("GOOD", quality1.qualityLevel);
        assertEquals(10, quality1.qualityScore);

        // Exactly 3.0
        SignalData.RrQuality quality2 = SignalData.RrQuality.assess(
            3.0, 1.5, 10, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("EXCELLENT", quality2.qualityLevel);
        assertEquals(15, quality2.qualityScore);
    }

    @Test
    @DisplayName("Verify exact boundary at 2.0 for GOOD")
    public void testGoodBoundary() {
        // Just below 2.0
        SignalData.RrQuality quality1 = SignalData.RrQuality.assess(
            1.99, 1.5, 10, 20, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("ACCEPTABLE", quality1.qualityLevel);
        assertEquals(5, quality1.qualityScore);

        // Exactly 2.0
        SignalData.RrQuality quality2 = SignalData.RrQuality.assess(
            2.0, 1.5, 10, 20, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("GOOD", quality2.qualityLevel);
        assertEquals(10, quality2.qualityScore);
    }

    @Test
    @DisplayName("Verify boundary at minimum threshold")
    public void testMinimumBoundary() {
        // Just below minimum
        SignalData.RrQuality quality1 = SignalData.RrQuality.assess(
            1.49, 1.5, 20, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("MARGINAL", quality1.qualityLevel);
        assertFalse(quality1.meetsMinimum);

        // Exactly at minimum
        SignalData.RrQuality quality2 = SignalData.RrQuality.assess(
            1.5, 1.5, 20, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("ACCEPTABLE", quality2.qualityLevel);
        assertTrue(quality2.meetsMinimum);
    }

    // ========== Position Sizing Tests ==========

    @Test
    @DisplayName("Full position for R:R >= minimum")
    public void testFullPositionSizing() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.5, 1.5, 20, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );

        assertEquals("FULL", quality.sizingRecommendation);
        assertEquals(1.0, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("Reduced 75% for marginal R:R")
    public void testReduced75PositionSizing() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.3, 1.5,  // 87% of minimum
            20, 26, "ATR", "ATR", "NONE", "", false, 0, 0
        );

        assertEquals("REDUCED_75", quality.sizingRecommendation);
        assertEquals(0.75, quality.suggestedRiskPercent, 0.01);
    }

    @Test
    @DisplayName("Reduced 50% for poor R:R")
    public void testReduced50PositionSizing() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.0, 1.5,  // 67% of minimum
            20, 20, "FIXED", "FIXED", "NONE", "", false, 0, 0
        );

        assertEquals("REDUCED_50", quality.sizingRecommendation);
        assertEquals(0.5, quality.suggestedRiskPercent, 0.01);
    }

    // ========== Improvement Suggestion Tests ==========

    @Test
    @DisplayName("Can improve R:R with tighter SL")
    public void testImprovementSuggestion() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.2, 1.5,  // Current R:R
            25, 30,     // Current SL/TP
            "ATR", "ATR",
            "POOR_RR", "R:R below minimum",
            true,       // canBeImproved
            15, 30      // suggestedSl=15, suggestedTp=30 (would give 2.0 R:R)
        );

        assertTrue(quality.canBeImproved);
        assertEquals(15, quality.suggestedSlTicks);
        assertEquals(30, quality.suggestedTpTicks);
        assertEquals(2.0, quality.potentialRR, 0.01);  // 30/15 = 2.0
    }

    @Test
    @DisplayName("No improvement possible when already optimal")
    public void testNoImprovementWhenOptimal() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            3.0, 1.5,
            10, 30,
            "DOM_SUPPORT", "DOM_RESISTANCE",
            "NONE", "",
            false, 0, 0
        );

        assertFalse(quality.canBeImproved);
        assertEquals(0, quality.suggestedSlTicks);
        assertEquals(0, quality.suggestedTpTicks);
        assertEquals(3.0, quality.potentialRR, 0.01);  // Same as current
    }

    // ========== AI Guidance Tests ==========

    @Test
    @DisplayName("AI guidance for good R:R")
    public void testAiGuidanceForGoodRR() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            2.5, 1.5, 10, 25, "ATR", "ATR", "NONE", "", false, 0, 0
        );

        assertTrue(quality.aiGuidance.contains("meets minimum"));
        assertTrue(quality.aiGuidance.contains("favorable"));
    }

    @Test
    @DisplayName("AI guidance for poor R:R with improvement")
    public void testAiGuidanceForPoorRRWithImprovement() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.2, 1.5,
            25, 30,
            "ATR", "ATR",
            "POOR_RR", "R:R below minimum",
            true,
            15, 30
        );

        assertTrue(quality.aiGuidance.contains("below minimum"));
        assertTrue(quality.aiGuidance.contains("Could improve"));
        assertTrue(quality.aiGuidance.contains("REDUCED"));
    }

    @Test
    @DisplayName("AI guidance includes issue details")
    public void testAiGuidanceIncludesIssue() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.2, 1.5,
            25, 30,
            "ATR", "ATR",
            "WIDE_SL", "Stop loss 25 ticks is wider than optimal",
            false, 0, 0
        );

        assertTrue(quality.aiGuidance.contains("Stop loss"));
    }

    // ========== SL/TP Source Tracking Tests ==========

    @Test
    @DisplayName("Track SL/TP sources correctly")
    public void testSlTpSourceTracking() {
        SignalData.RrQuality quality1 = SignalData.RrQuality.assess(
            2.0, 1.5, 15, 30,
            "DOM_SUPPORT", "DOM_RESISTANCE",
            "NONE", "", false, 0, 0
        );
        assertEquals("DOM_SUPPORT", quality1.slSource);
        assertEquals("DOM_RESISTANCE", quality1.tpSource);

        SignalData.RrQuality quality2 = SignalData.RrQuality.assess(
            2.0, 1.5, 15, 30,
            "MEMORY", "ATR",
            "NONE", "", false, 0, 0
        );
        assertEquals("MEMORY", quality2.slSource);
        assertEquals("ATR", quality2.tpSource);
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Handle zero SL ticks gracefully")
    public void testZeroSlTicks() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            Double.POSITIVE_INFINITY, 1.5,  // Division by zero gives infinity
            0, 30,
            "FIXED", "FIXED",
            "NONE", "", false, 0, 0
        );

        // Should handle gracefully (implementation may vary)
        assertNotNull(quality);
    }

    @Test
    @DisplayName("Handle very high minimum threshold")
    public void testHighMinimumThreshold() {
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            2.0, 3.0,  // Below high minimum but >= 2.0
            15, 30,
            "ATR", "ATR",
            "POOR_RR", "Below high threshold",
            false, 0, 0
        );

        // 2.0 >= 2.0 is true, so GOOD quality level
        assertEquals("GOOD", quality.qualityLevel);
        assertEquals(10, quality.qualityScore);
        assertFalse(quality.meetsMinimum);  // 2.0 < 3.0
    }

    @Test
    @DisplayName("Handle different minimum thresholds correctly")
    public void testDifferentMinimumThresholds() {
        // With minRequired = 1.0, 1.5 meets minimum but 1.5 < 2.0
        SignalData.RrQuality quality1 = SignalData.RrQuality.assess(
            1.5, 1.0, 20, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("ACCEPTABLE", quality1.qualityLevel);  // 1.5 >= 1.0 but < 2.0
        assertTrue(quality1.meetsMinimum);

        // With minRequired = 2.0, need at least 2.0
        SignalData.RrQuality quality2 = SignalData.RrQuality.assess(
            2.0, 2.0, 15, 30, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        assertEquals("GOOD", quality2.qualityLevel);  // Exactly at 2.0 = GOOD
        assertTrue(quality2.meetsMinimum);
    }

    // ========== Summary String Tests ==========

    @Test
    @DisplayName("Summary string contains key information")
    public void testSummaryString() {
        // Use values that are clearly in MARGINAL range
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.3, 1.5, 23, 30, "ATR", "ATR",
            "POOR_RR", "Below minimum", false, 0, 0
        );

        String summary = quality.getSummary();
        assertTrue(summary.contains("1.3"));  // R:R ratio
        assertTrue(summary.contains("MARGINAL"));  // Quality level
        assertTrue(summary.contains("REDUCED"));  // Sizing recommendation
    }

    @Test
    @DisplayName("AI string contains all sections")
    public void testAiString() {
        // Use values that are clearly in MARGINAL range
        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            1.3, 1.5, 23, 30, "ATR", "DOM_RESISTANCE",
            "POOR_RR", "Below minimum", true, 20, 30
        );

        String aiString = quality.toAIString();
        assertTrue(aiString.contains("R:R QUALITY"));
        assertTrue(aiString.contains("1.3"));
        assertTrue(aiString.contains("MARGINAL"));
        assertTrue(aiString.contains("SL:"));
        assertTrue(aiString.contains("TP:"));
        assertTrue(aiString.contains("Score Impact"));
        assertTrue(aiString.contains("Sizing:"));
        // "Could improve" only appears if canBeImproved is true and suggestedSl > 0
        assertTrue(aiString.contains("Could improve"));
        assertTrue(aiString.contains("AI Guidance"));
    }

    // ========== Real-World Scenario Tests ==========

    @Test
    @DisplayName("Scenario: ES trade with good DOM levels")
    public void testScenarioGoodDomLevels() {
        // ES trade: entry 5800, DOM support at 5795 (5 ticks), DOM resistance at 5810 (10 ticks)
        int entryPrice = 5800;
        int supportPrice = 5795;
        int resistancePrice = 5810;
        int slTicks = entryPrice - supportPrice + 2;  // 7 ticks (5 + 2 buffer)
        int tpTicks = resistancePrice - entryPrice - 2;  // 8 ticks (10 - 2 buffer)

        double rrRatio = (double) tpTicks / slTicks;  // 8/7 = 1.14

        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            rrRatio, 1.5,
            slTicks, tpTicks,
            "DOM_SUPPORT", "DOM_RESISTANCE",
            "POOR_RR", "R:R constrained by nearby resistance",
            true,
            slTicks, 15  // Could target further resistance
        );

        assertEquals(1.14, quality.rrRatio, 0.01);
        // 1.14 < 1.2 (minRequired * 0.8) but >= 1.0, so POOR
        assertEquals("POOR", quality.qualityLevel);
        assertEquals(-10, quality.qualityScore);
        assertTrue(quality.canBeImproved);
    }

    @Test
    @DisplayName("Scenario: High volatility widens ATR-based SL")
    public void testScenarioHighVolatility() {
        // High volatility: ATR suggests 30 tick SL, but only 25 tick TP available
        int slTicks = 30;
        int tpTicks = 25;

        double rrRatio = (double) tpTicks / slTicks;  // 0.83

        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            rrRatio, 1.5,
            slTicks, tpTicks,
            "ATR", "ATR",
            "WIDE_SL", "High volatility requires wider stop",
            false, 0, 0
        );

        assertEquals(0.83, quality.rrRatio, 0.01);
        // 0.83 < 1.0, so VERY_POOR
        assertEquals("VERY_POOR", quality.qualityLevel);
        assertEquals(-15, quality.qualityScore);
        // 0.83 < 0.9 (minRequired * 0.6) and 0.83 < 1.0, so AVOID
        assertEquals("AVOID", quality.sizingRecommendation);
    }

    @Test
    @DisplayName("Scenario: Memory-driven levels from trade history")
    public void testScenarioMemoryLevels() {
        // From trade history: avg MAE was 8 ticks, avg MFE was 24 ticks
        int memorySl = 10;  // 120% of MAE
        int memoryTp = 19;  // 80% of MFE

        double rrRatio = (double) memoryTp / memorySl;  // 1.9

        SignalData.RrQuality quality = SignalData.RrQuality.assess(
            rrRatio, 1.5,
            memorySl, memoryTp,
            "MEMORY", "MEMORY",
            "NONE", "",
            false, 0, 0
        );

        assertEquals(1.9, quality.rrRatio, 0.01);
        assertEquals("ACCEPTABLE", quality.qualityLevel);  // 1.9 >= 1.5 but < 2.0
        assertEquals(5, quality.qualityScore);
        assertTrue(quality.meetsMinimum);
    }

    // ========== Score Impact Calculation Tests ==========

    @Test
    @DisplayName("Score adjustments correctly applied")
    public void testScoreAdjustmentRange() {
        // Create signals with different R:R and verify score adjustments
        int baseScore = 70;  // A decent confluence score

        // Excellent R:R should boost score significantly
        SignalData.RrQuality excellent = SignalData.RrQuality.assess(
            3.5, 1.5, 10, 35, "ATR", "ATR", "NONE", "", false, 0, 0
        );
        int adjustedScoreExcellent = baseScore + excellent.qualityScore;
        assertEquals(85, adjustedScoreExcellent);  // 70 + 15

        // Poor R:R should reduce score
        SignalData.RrQuality poor = SignalData.RrQuality.assess(
            1.0, 1.5, 20, 20, "FIXED", "FIXED", "NONE", "", false, 0, 0
        );
        int adjustedScorePoor = baseScore + poor.qualityScore;
        assertEquals(60, adjustedScorePoor);  // 70 - 10

        // Very poor R:R could push below threshold
        SignalData.RrQuality veryPoor = SignalData.RrQuality.assess(
            0.5, 1.5, 30, 15, "FIXED", "FIXED", "NONE", "", false, 0, 0
        );
        int adjustedScoreVeryPoor = baseScore + veryPoor.qualityScore;
        assertEquals(55, adjustedScoreVeryPoor);  // 70 - 15
    }
}
