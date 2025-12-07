/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.core;

import io.xdag.DagKernel;
import io.xdag.store.DagStore;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.function.LongFunction;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DifficultyAdjuster to prevent overflow bugs.
 *
 * <p><b>BUG-DIFFICULTY-001:</b> When baseDifficultyTarget is UInt256.MAX_VALUE (DEVNET mode)
 * and adjustmentFactor = 2.0 (too few blocks), the calculation MAX_VALUE * 2 overflows.
 *
 * <p>This test ensures:
 * <ul>
 *   <li>Difficulty adjustment doesn't overflow with MAX_VALUE target</li>
 *   <li>New target is capped at MAX_VALUE</li>
 *   <li>No IllegalArgumentException is thrown</li>
 * </ul>
 *
 * @since XDAGJ 5.1
 */
public class DifficultyAdjusterOverflowTest {

    @Mock
    private DagKernel dagKernel;

    @Mock
    private DagStore dagStore;

    private DifficultyAdjuster difficultyAdjuster;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(dagKernel.getDagStore()).thenReturn(dagStore);
        difficultyAdjuster = new DifficultyAdjuster(dagKernel);
    }

    /**
     * Test that difficulty adjustment doesn't overflow when target is MAX_VALUE.
     *
     * <p>Scenario:
     * <ol>
     *   <li>baseDifficultyTarget = UInt256.MAX_VALUE (DEVNET mode)</li>
     *   <li>Very few blocks per epoch (e.g., 2 blocks avg)</li>
     *   <li>adjustmentFactor = 16/2 = 8.0, capped to 2.0</li>
     *   <li>newTarget = MAX_VALUE * 2 would overflow!</li>
     * </ol>
     *
     * <p>Expected: newTarget is capped at MAX_VALUE, no exception thrown.
     */
    @Test
    public void testMaxValueTargetDoesNotOverflow() {
        // Setup: DEVNET mode with MAX_VALUE target
        long startEpoch = 27577000L;
        long currentEpoch = startEpoch + 1001L;  // Past adjustment interval

        ChainStats chainStats = ChainStats.builder()
                .mainBlockCount(100)
                .difficulty(UInt256.ZERO)
                .baseDifficultyTarget(UInt256.MAX_VALUE)  // DEVNET mode
                .lastDifficultyAdjustmentEpoch(startEpoch)
                .lastOrphanCleanupEpoch(startEpoch)
                .build();

        // Mock: Very few blocks per epoch (triggers adjustmentFactor = 2.0)
        LongFunction<List<Block>> getCandidateBlocksInEpoch = epoch -> {
            // Return only 2 blocks per epoch (target is 16, so ratio = 16/2 = 8, capped to 2)
            return Collections.nCopies(2, mock(Block.class));
        };

        // Execute: Should NOT throw IllegalArgumentException
        ChainStats result = difficultyAdjuster.checkAndAdjustDifficulty(
                chainStats,
                100L,
                currentEpoch,
                getCandidateBlocksInEpoch);

        // Verify: Target should be capped at MAX_VALUE
        assertNotNull("Result should not be null", result);
        assertEquals("Target should be capped at MAX_VALUE",
                UInt256.MAX_VALUE, result.getBaseDifficultyTarget());
        assertEquals("Last adjustment epoch should be updated",
                currentEpoch, result.getLastDifficultyAdjustmentEpoch());
    }

    /**
     * Test that difficulty adjustment works correctly for normal targets.
     *
     * <p>When target is not MAX_VALUE, adjustment should work normally.
     */
    @Test
    public void testNormalTargetAdjustment() {
        // Setup: Normal target (2^192)
        long startEpoch = 27577000L;
        long currentEpoch = startEpoch + 1001L;

        UInt256 normalTarget = UInt256.valueOf(java.math.BigInteger.valueOf(2).pow(192));
        ChainStats chainStats = ChainStats.builder()
                .mainBlockCount(100)
                .difficulty(UInt256.ZERO)
                .baseDifficultyTarget(normalTarget)
                .lastDifficultyAdjustmentEpoch(startEpoch)
                .lastOrphanCleanupEpoch(startEpoch)
                .build();

        // Mock: Very few blocks per epoch (triggers adjustmentFactor = 2.0)
        LongFunction<List<Block>> getCandidateBlocksInEpoch = epoch ->
                Collections.nCopies(2, mock(Block.class));

        // Execute
        ChainStats result = difficultyAdjuster.checkAndAdjustDifficulty(
                chainStats,
                100L,
                currentEpoch,
                getCandidateBlocksInEpoch);

        // Verify: Target should increase (lower difficulty)
        assertNotNull("Result should not be null", result);
        assertTrue("Target should increase when blocks are too few",
                result.getBaseDifficultyTarget().compareTo(normalTarget) > 0);
    }

    /**
     * Test no adjustment when interval not reached.
     */
    @Test
    public void testNoAdjustmentWhenIntervalNotReached() {
        // Setup
        long startEpoch = 27577000L;
        long currentEpoch = startEpoch + 500L;  // Less than 1000 epochs

        ChainStats chainStats = ChainStats.builder()
                .mainBlockCount(100)
                .difficulty(UInt256.ZERO)
                .baseDifficultyTarget(UInt256.MAX_VALUE)
                .lastDifficultyAdjustmentEpoch(startEpoch)
                .lastOrphanCleanupEpoch(startEpoch)
                .build();

        LongFunction<List<Block>> getCandidateBlocksInEpoch = epoch ->
                Collections.nCopies(2, mock(Block.class));

        // Execute
        ChainStats result = difficultyAdjuster.checkAndAdjustDifficulty(
                chainStats,
                100L,
                currentEpoch,
                getCandidateBlocksInEpoch);

        // Verify: No change (same object returned)
        assertSame("Should return same ChainStats when interval not reached",
                chainStats, result);
    }

    /**
     * Test adjustment factor is properly capped.
     */
    @Test
    public void testAdjustmentFactorCapping() {
        // Setup: Target that won't overflow even with 2x multiplier
        long startEpoch = 27577000L;
        long currentEpoch = startEpoch + 1001L;

        // Use a target small enough that we can verify the capping
        UInt256 smallTarget = UInt256.valueOf(1000000L);
        ChainStats chainStats = ChainStats.builder()
                .mainBlockCount(100)
                .difficulty(UInt256.ZERO)
                .baseDifficultyTarget(smallTarget)
                .lastDifficultyAdjustmentEpoch(startEpoch)
                .lastOrphanCleanupEpoch(startEpoch)
                .build();

        // Mock: Only 1 block per epoch (would give factor = 16, capped to 2)
        LongFunction<List<Block>> getCandidateBlocksInEpoch = epoch ->
                Collections.nCopies(1, mock(Block.class));

        // Execute
        ChainStats result = difficultyAdjuster.checkAndAdjustDifficulty(
                chainStats,
                100L,
                currentEpoch,
                getCandidateBlocksInEpoch);

        // Verify: Target should be exactly 2x (factor capped at 2.0)
        UInt256 expected = UInt256.valueOf(2000000L);
        assertEquals("Target should be 2x when factor is capped",
                expected, result.getBaseDifficultyTarget());
    }
}
