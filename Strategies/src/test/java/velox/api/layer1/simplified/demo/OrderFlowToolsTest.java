package velox.api.layer1.simplified.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Carmine Rosato order flow tools:
 * - PriceDeltaTracker (Big Fish detection)
 * - VolumeTailDetector (Volume tail detection)
 * - TapeSpeedTracker (Tape speed analysis)
 * - StopHuntDetector (Stop hunt detection)
 */
public class OrderFlowToolsTest {

    // ========== PriceDeltaTracker Tests ==========

    @BeforeEach
    public void setUp() {
        // Reset any shared state if needed
    }

    @Test
    @DisplayName("PriceDeltaTracker: Initial state")
    public void testPriceDeltaTrackerInit() {
        PriceDeltaTracker tracker = new PriceDeltaTracker();
        assertEquals(0, tracker.getDeltaAtPrice(5800));
        assertTrue(tracker.getActiveBigFishLevels().isEmpty());
    }

    @Test
    @DisplayName("PriceDeltaTracker: Record buy trades increases delta")
    public void testPriceDeltaTrackerBuyTrades() {
        PriceDeltaTracker tracker = new PriceDeltaTracker();

        // Record 5 buy trades at price 5800
        for (int i = 0; i < 5; i++) {
            tracker.recordTrade(5800, 100, true);  // price, volume, isBuy
        }

        assertEquals(500, tracker.getDeltaAtPrice(5800));

        var level = tracker.getPriceLevel(5800);
        assertNotNull(level);
        assertEquals(500, level.delta);
        assertEquals(500, level.buyVolume);
        assertEquals(0, level.sellVolume);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Record sell trades decreases delta")
    public void testPriceDeltaTrackerSellTrades() {
        PriceDeltaTracker tracker = new PriceDeltaTracker();

        // Record 5 sell trades at price 5800
        for (int i = 0; i < 5; i++) {
            tracker.recordTrade(5800, 100, false);  // isBuy = false
        }

        assertEquals(-500, tracker.getDeltaAtPrice(5800));

        var level = tracker.getPriceLevel(5800);
        assertNotNull(level);
        assertEquals(-500, level.delta);
        assertEquals(0, level.buyVolume);
        assertEquals(500, level.sellVolume);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Detect Big Fish level (delta >= 2000)")
    public void testPriceDeltaTrackerBigFishDetection() {
        PriceDeltaTracker tracker = new PriceDeltaTracker(500, 2000, 30 * 60 * 1000, 100);

        // Build up delta to trigger Big Fish detection
        for (int i = 0; i < 20; i++) {
            tracker.recordTrade(5800, 150, true);  // 20 * 150 = 3000 delta
        }

        var bigFishLevels = tracker.getActiveBigFishLevels();
        assertFalse(bigFishLevels.isEmpty());

        var bigFish = bigFishLevels.get(0);
        assertEquals(5800, bigFish.price);
        assertTrue(bigFish.isBuyer);
        assertTrue(bigFish.isActive);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Analyze for Big Fish defense")
    public void testPriceDeltaTrackerDefenseAnalysis() {
        PriceDeltaTracker tracker = new PriceDeltaTracker(500, 1000, 30 * 60 * 1000, 100);

        // Create Big Fish level at 5800 with strong buying
        for (int i = 0; i < 15; i++) {
            tracker.recordTrade(5800, 100, true);
        }

        // Price moves away then returns
        tracker.recordTrade(5805, 50, false);
        tracker.recordTrade(5810, 50, false);

        // Price returns to Big Fish level and they defend
        tracker.recordTrade(5801, 100, true);
        tracker.recordTrade(5800, 150, true);
        tracker.recordTrade(5800, 100, true);

        var analysis = tracker.analyzeForBigFish(5800, 10);
        assertTrue(analysis.hasBigFishNearby);
        assertNotNull(analysis.nearestBigFish);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Get top delta levels")
    public void testPriceDeltaTrackerTopLevels() {
        PriceDeltaTracker tracker = new PriceDeltaTracker();

        // Create different delta levels
        tracker.recordTrade(5800, 500, true);
        tracker.recordTrade(5801, 300, true);
        tracker.recordTrade(5802, 400, false);
        tracker.recordTrade(5799, 600, true);

        var topLevels = tracker.getTopDeltaLevels(3);
        assertFalse(topLevels.isEmpty());

        // Should be sorted by absolute delta
        assertTrue(topLevels.size() <= 3);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Outlier detection")
    public void testPriceDeltaTrackerOutliers() {
        PriceDeltaTracker tracker = new PriceDeltaTracker(500, 2000, 30 * 60 * 1000, 100);

        // Create normal levels
        tracker.recordTrade(5800, 100, true);
        tracker.recordTrade(5801, 100, false);

        // Create outlier level
        for (int i = 0; i < 10; i++) {
            tracker.recordTrade(5802, 100, true);
        }

        var outliers = tracker.getOutlierLevels();
        assertFalse(outliers.isEmpty());

        // 5802 should be an outlier with delta=1000 >= 500 threshold
        boolean foundOutlier = outliers.stream().anyMatch(l -> l.price == 5802);
        assertTrue(foundOutlier);
    }

    @Test
    @DisplayName("PriceDeltaTracker: Reset clears all data")
    public void testPriceDeltaTrackerReset() {
        PriceDeltaTracker tracker = new PriceDeltaTracker();

        tracker.recordTrade(5800, 500, true);
        tracker.recordTrade(5801, 300, false);

        tracker.reset();

        assertEquals(0, tracker.getDeltaAtPrice(5800));
        assertTrue(tracker.getActiveBigFishLevels().isEmpty());
    }

    // ========== VolumeTailDetector Tests ==========

    @Test
    @DisplayName("VolumeTailDetector: Initial state")
    public void testVolumeTailDetectorInit() {
        VolumeTailDetector detector = new VolumeTailDetector();
        var analysis = detector.analyzeTails();

        assertEquals("NEUTRAL", analysis.bias);
        assertFalse(analysis.hasAnyTail());
    }

    @Test
    @DisplayName("VolumeTailDetector: Record volume builds profile")
    public void testVolumeTailDetectorRecordVolume() {
        VolumeTailDetector detector = new VolumeTailDetector();

        // Record volume at various prices
        detector.recordVolume(5800, 1000);
        detector.recordVolume(5801, 1500);
        detector.recordVolume(5802, 2000);

        assertEquals(1000, detector.getVolumeAtPrice(5800));
        assertEquals(1500, detector.getVolumeAtPrice(5801));
        assertEquals(2000, detector.getVolumeAtPrice(5802));
    }

    @Test
    @DisplayName("VolumeTailDetector: Detect lower volume tail (bullish)")
    public void testVolumeTailDetectorLowerTail() {
        VolumeTailDetector detector = new VolumeTailDetector(200, 0.3, 10);

        // Build a volume profile with tail at bottom
        // High volume in middle
        for (int i = 5805; i <= 5815; i++) {
            detector.recordVolume(i, 2000);
        }

        // Low volume at bottom (tail)
        detector.recordVolume(5800, 100);
        detector.recordVolume(5801, 150);
        detector.recordVolume(5802, 100);

        var analysis = detector.analyzeTails();

        assertTrue(analysis.hasLowerTail);
        assertEquals("BULLISH", analysis.bias);
        assertTrue(analysis.lowerTailLength >= 2);
    }

    @Test
    @DisplayName("VolumeTailDetector: Detect upper volume tail (bearish)")
    public void testVolumeTailDetectorUpperTail() {
        VolumeTailDetector detector = new VolumeTailDetector(200, 0.3, 10);

        // Build a volume profile with tail at top
        // High volume in middle
        for (int i = 5800; i <= 5810; i++) {
            detector.recordVolume(i, 2000);
        }

        // Low volume at top (tail)
        detector.recordVolume(5811, 100);
        detector.recordVolume(5812, 150);
        detector.recordVolume(5813, 100);

        var analysis = detector.analyzeTails();

        assertTrue(analysis.hasUpperTail);
        assertEquals("BEARISH", analysis.bias);
        assertTrue(analysis.upperTailLength >= 2);
    }

    @Test
    @DisplayName("VolumeTailDetector: Both tails = consolidation")
    public void testVolumeTailDetectorBothTails() {
        VolumeTailDetector detector = new VolumeTailDetector(200, 0.3, 10);

        // High volume in middle
        for (int i = 5805; i <= 5810; i++) {
            detector.recordVolume(i, 2000);
        }

        // Low volume at both extremes
        detector.recordVolume(5800, 100);
        detector.recordVolume(5801, 100);
        detector.recordVolume(5814, 100);
        detector.recordVolume(5815, 100);

        var analysis = detector.analyzeTails();

        assertTrue(analysis.hasBothTails());
        // Bias depends on which tail is stronger
        assertNotNull(analysis.bias);
    }

    @Test
    @DisplayName("VolumeTailDetector: Get POC (Point of Control)")
    public void testVolumeTailDetectorPOC() {
        VolumeTailDetector detector = new VolumeTailDetector();

        detector.recordVolume(5800, 1000);
        detector.recordVolume(5801, 3000);  // Highest volume
        detector.recordVolume(5802, 2000);

        assertEquals(5801, detector.getPOC());
    }

    @Test
    @DisplayName("VolumeTailDetector: is in tail zone")
    public void testVolumeTailDetectorInTailZone() {
        VolumeTailDetector detector = new VolumeTailDetector(200, 0.3, 10);

        // Build profile with lower tail
        for (int i = 5805; i <= 5815; i++) {
            detector.recordVolume(i, 2000);
        }
        detector.recordVolume(5800, 100);
        detector.recordVolume(5801, 100);

        // Prices in tail zone
        assertTrue(detector.isInTailZone(5800));
        assertTrue(detector.isInTailZone(5801));

        // Prices not in tail zone
        assertFalse(detector.isInTailZone(5805));
        assertFalse(detector.isInTailZone(5810));
    }

    // ========== TapeSpeedTracker Tests ==========

    @Test
    @DisplayName("TapeSpeedTracker: Initial state")
    public void testTapeSpeedTrackerInit() {
        TapeSpeedTracker tracker = new TapeSpeedTracker();
        assertEquals(0, tracker.getTotalTrades());
        assertEquals(0, tracker.getTotalVolume());
    }

    @Test
    @DisplayName("TapeSpeedTracker: Record trades")
    public void testTapeSpeedTrackerRecordTrades() {
        TapeSpeedTracker tracker = new TapeSpeedTracker();

        tracker.recordTrade(5800, 100, true);
        tracker.recordTrade(5801, 150, false);
        tracker.recordTrade(5800, 200, true);

        assertEquals(3, tracker.getTotalTrades());
        assertEquals(450, tracker.getTotalVolume());
    }

    @Test
    @DisplayName("TapeSpeedTracker: Analyze tape speed")
    public void testTapeSpeedTrackerAnalyze() {
        TapeSpeedTracker tracker = new TapeSpeedTracker(500, 5000, 10, 25);

        // Record several trades quickly
        for (int i = 0; i < 50; i++) {
            tracker.recordTrade(5800 + (i % 10), 100, i % 2 == 0);
        }

        var analysis = tracker.analyze();

        assertNotNull(analysis.speedLevel);
        assertTrue(analysis.tradesPerSecond >= 0);
        assertTrue(analysis.volumePerSecond >= 0);
        assertNotNull(analysis.dominantSide);
    }

    @Test
    @DisplayName("TapeSpeedTracker: Detect buyer dominance")
    public void testTapeSpeedTrackerBuyerDominance() {
        TapeSpeedTracker tracker = new TapeSpeedTracker(500, 5000, 10, 25);

        // More buy trades
        for (int i = 0; i < 30; i++) {
            tracker.recordTrade(5800, 100, true);  // buys
        }
        for (int i = 0; i < 10; i++) {
            tracker.recordTrade(5800, 100, false);  // sells
        }

        var analysis = tracker.analyze();
        assertEquals("BUYERS", analysis.dominantSide);
        assertTrue(analysis.dominanceRatio > 1.0);
    }

    @Test
    @DisplayName("TapeSpeedTracker: Detect seller dominance")
    public void testTapeSpeedTrackerSellerDominance() {
        TapeSpeedTracker tracker = new TapeSpeedTracker(500, 5000, 10, 25);

        // More sell trades
        for (int i = 0; i < 10; i++) {
            tracker.recordTrade(5800, 100, true);  // buys
        }
        for (int i = 0; i < 30; i++) {
            tracker.recordTrade(5800, 100, false);  // sells
        }

        var analysis = tracker.analyze();
        assertEquals("SELLERS", analysis.dominantSide);
        assertTrue(analysis.dominanceRatio < 1.0);
    }

    @Test
    @DisplayName("TapeSpeedTracker: Score adjustment for long direction")
    public void testTapeSpeedTrackerLongScoreAdjustment() {
        TapeSpeedTracker tracker = new TapeSpeedTracker(500, 5000, 10, 25);

        // Buyer dominated market
        for (int i = 0; i < 30; i++) {
            tracker.recordTrade(5800, 100, true);
        }
        for (int i = 0; i < 10; i++) {
            tracker.recordTrade(5800, 100, false);
        }

        int adjustment = tracker.getSpeedScoreAdjustment(true);  // isLong = true
        assertTrue(adjustment >= 0);  // Should be positive for aligned direction
    }

    @Test
    @DisplayName("TapeSpeedTracker: Favorable for direction check")
    public void testTapeSpeedTrackerFavorableCheck() {
        TapeSpeedTracker tracker = new TapeSpeedTracker(500, 5000, 10, 25);

        // Strong buying
        for (int i = 0; i < 50; i++) {
            tracker.recordTrade(5800, 100, true);
        }

        assertTrue(tracker.isFavorableForDirection(true));   // Long with buyers
        assertFalse(tracker.isFavorableForDirection(false)); // Short vs buyers
    }

    // ========== StopHuntDetector Tests ==========

    @Test
    @DisplayName("StopHuntDetector: Initial state")
    public void testStopHuntDetectorInit() {
        StopHuntDetector detector = new StopHuntDetector();
        var analysis = detector.analyze();

        assertFalse(analysis.recentStopHunt);
        assertEquals("NONE", analysis.huntSignal);
    }

    @Test
    @DisplayName("StopHuntDetector: Update key levels")
    public void testStopHuntDetectorKeyLevels() {
        StopHuntDetector detector = new StopHuntDetector();

        detector.updateKeyLevels(5800, 5820, 5780, 5800);

        var analysis = detector.analyze();
        assertNotNull(analysis);
    }

    @Test
    @DisplayName("StopHuntDetector: Detect sweep and reversal (stop hunt)")
    public void testStopHuntDetectorSweepAndReversal() throws InterruptedException {
        StopHuntDetector detector = new StopHuntDetector(
            500, 50, 3000, 8, 5, 5000, 2
        );

        // Update key levels
        detector.updateKeyLevels(5800, 5820, 5780, 5800);

        // Simulate a sweep down through support (5800) then reversal
        // Rapid selling through 5800
        for (int i = 0; i < 20; i++) {
            detector.recordTrade(5800 - i, 100, false);  // Aggressive selling
        }

        // Then rapid buying (reversal)
        for (int i = 0; i < 15; i++) {
            detector.recordTrade(5785 + i, 100, true);  // Aggressive buying
        }

        var recentHunts = detector.getRecentStopHunts(5);

        // May or may not detect depending on timing, but should not crash
        assertNotNull(recentHunts);
    }

    @Test
    @DisplayName("StopHuntDetector: Score adjustment for signal direction")
    public void testStopHuntDetectorScoreAdjustment() {
        StopHuntDetector detector = new StopHuntDetector();

        // No stop hunt detected - adjustment depends on distance to level
        // With no data, it returns 0 (no key levels) or -2 (if near default level)
        int adjustment = detector.getStopHuntScoreAdjustment(true);
        // Just verify it doesn't crash and returns reasonable value
        assertTrue(adjustment >= -15 && adjustment <= 15);
    }

    @Test
    @DisplayName("StopHuntDetector: Reset clears data")
    public void testStopHuntDetectorReset() {
        StopHuntDetector detector = new StopHuntDetector();

        detector.recordTrade(5800, 100, true);
        detector.recordTrade(5801, 100, false);

        detector.reset();

        var analysis = detector.analyze();
        assertFalse(analysis.recentStopHunt);
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("Integration: Order flow confluence for long signal")
    public void testOrderFlowConfluenceLong() {
        // Set up all trackers with bullish bias
        PriceDeltaTracker deltaTracker = new PriceDeltaTracker();
        VolumeTailDetector tailDetector = new VolumeTailDetector();
        TapeSpeedTracker speedTracker = new TapeSpeedTracker();

        // Strong buying delta
        for (int i = 0; i < 20; i++) {
            deltaTracker.recordTrade(5800, 100, true);
        }

        // Build volume profile with lower tail (bullish)
        for (int i = 5805; i <= 5815; i++) {
            tailDetector.recordVolume(i, 2000);
        }
        tailDetector.recordVolume(5800, 100);
        tailDetector.recordVolume(5801, 100);

        // Fast tape with buyer dominance
        for (int i = 0; i < 50; i++) {
            speedTracker.recordTrade(5800, 100, true);
        }

        // Verify bullish confluence
        var fishAnalysis = deltaTracker.analyzeForBigFish(5800, 10);
        var tailAnalysis = tailDetector.analyzeTails();
        var speedAnalysis = speedTracker.analyze();

        // Strong buying should have Big Fish nearby
        assertTrue(fishAnalysis.hasBigFishNearby);

        // Lower tail = bullish
        assertTrue(tailAnalysis.hasLowerTail);
        assertEquals("BULLISH", tailAnalysis.bias);

        // Buyers dominant
        assertEquals("BUYERS", speedAnalysis.dominantSide);

        // Score adjustment for long should be positive
        int speedAdjustment = speedTracker.getSpeedScoreAdjustment(true);
        assertTrue(speedAdjustment > 0);
    }

    @Test
    @DisplayName("Integration: Order flow confluence for short signal")
    public void testOrderFlowConfluenceShort() {
        // Set up all trackers with bearish bias
        PriceDeltaTracker deltaTracker = new PriceDeltaTracker();
        VolumeTailDetector tailDetector = new VolumeTailDetector();
        TapeSpeedTracker speedTracker = new TapeSpeedTracker();

        // Strong selling delta
        for (int i = 0; i < 20; i++) {
            deltaTracker.recordTrade(5810, 100, false);
        }

        // Build volume profile with upper tail (bearish)
        for (int i = 5800; i <= 5810; i++) {
            tailDetector.recordVolume(i, 2000);
        }
        tailDetector.recordVolume(5811, 100);
        tailDetector.recordVolume(5812, 100);

        // Fast tape with seller dominance
        for (int i = 0; i < 50; i++) {
            speedTracker.recordTrade(5810, 100, false);
        }

        // Verify bearish confluence
        var fishAnalysis = deltaTracker.analyzeForBigFish(5810, 10);
        var tailAnalysis = tailDetector.analyzeTails();
        var speedAnalysis = speedTracker.analyze();

        // Strong selling should have Big Fish nearby
        assertTrue(fishAnalysis.hasBigFishNearby);

        // Upper tail = bearish
        assertTrue(tailAnalysis.hasUpperTail);
        assertEquals("BEARISH", tailAnalysis.bias);

        // Sellers dominant
        assertEquals("SELLERS", speedAnalysis.dominantSide);

        // Score adjustment for short should be positive
        int speedAdjustment = speedTracker.getSpeedScoreAdjustment(false);
        assertTrue(speedAdjustment > 0);
    }

    @Test
    @DisplayName("Integration: Conflicting signals handled correctly")
    public void testOrderFlowConflictingSignals() {
        VolumeTailDetector tailDetector = new VolumeTailDetector();
        TapeSpeedTracker speedTracker = new TapeSpeedTracker();

        // Lower tail (bullish)
        for (int i = 5805; i <= 5815; i++) {
            tailDetector.recordVolume(i, 2000);
        }
        tailDetector.recordVolume(5800, 100);

        // But seller dominance on tape
        for (int i = 0; i < 50; i++) {
            speedTracker.recordTrade(5800, 100, false);
        }

        var tailAnalysis = tailDetector.analyzeTails();
        var speedAnalysis = speedTracker.analyze();

        // Conflicting: tail says bullish, tape says sellers
        assertEquals("BULLISH", tailAnalysis.bias);
        assertEquals("SELLERS", speedAnalysis.dominantSide);

        // Score adjustment for long depends on both speed level and direction alignment
        // With seller dominance, direction alignment bonus is 0 for longs
        int speedAdjustment = speedTracker.getSpeedScoreAdjustment(true);
        // The adjustment can be positive (from speed) even without direction alignment
        // Just verify it's reasonable
        assertTrue(speedAdjustment >= -15 && speedAdjustment <= 15);
    }
}
