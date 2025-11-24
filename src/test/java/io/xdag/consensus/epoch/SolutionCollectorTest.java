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
package io.xdag.consensus.epoch;

import io.xdag.core.Block;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SolutionCollector
 *
 * <p>Tests the solution collection mechanism for epoch-based consensus.
 */
public class SolutionCollectorTest {

    private SolutionCollector solutionCollector;
    private ConcurrentHashMap<Long, EpochContext> epochContexts;
    private UInt256 minimumDifficulty;

    @Before
    public void setUp() {
        // Set minimum difficulty for testing
        minimumDifficulty = UInt256.fromHexString("0x0000ffffffffffffffffffffffffffff");

        // Create shared epoch contexts map
        epochContexts = new ConcurrentHashMap<>();

        // Create SolutionCollector
        solutionCollector = new SolutionCollector(minimumDifficulty, epochContexts);
    }

    /**
     * Test 1: Submit valid solution - should be accepted
     */
    @Test
    public void testSubmitValidSolution() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Create solution block with good difficulty (low hash)
        Bytes32 goodHash = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solutionBlock = createMockBlock(currentEpoch, goodHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertTrue("Solution should be accepted", result.isAccepted());
        assertEquals("Should have 1 solution in context", 1, context.getSolutionsCount());
    }

    /**
     * Test 2: Submit solution with epoch mismatch - should be rejected
     */
    @Test
    public void testSubmitSolutionEpochMismatch() {
        long currentEpoch = 1000;
        long wrongEpoch = 999;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Create solution block with wrong epoch
        Bytes32 goodHash = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solutionBlock = createMockBlock(wrongEpoch, goodHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertFalse("Solution should be rejected", result.isAccepted());
        assertTrue("Error message should mention epoch mismatch",
            result.getErrorMessage().contains("Epoch mismatch"));
    }

    /**
     * Test 3: Submit solution with insufficient difficulty - should be rejected
     */
    @Test
    public void testSubmitSolutionInsufficientDifficulty() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Create solution block with bad difficulty (high hash = low difficulty)
        Bytes32 badHash = createHashWithDifficulty("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000");
        Block solutionBlock = createMockBlock(currentEpoch, badHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertFalse("Solution should be rejected", result.isAccepted());
        assertTrue("Error message should mention insufficient difficulty",
            result.getErrorMessage().contains("Insufficient difficulty"));
    }

    /**
     * Test 4: Submit solution for non-existent epoch - should be rejected
     */
    @Test
    public void testSubmitSolutionNonExistentEpoch() {
        long currentEpoch = 1000;

        // No epoch context created

        // Create solution block
        Bytes32 goodHash = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solutionBlock = createMockBlock(currentEpoch, goodHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertFalse("Solution should be rejected", result.isAccepted());
        assertTrue("Error message should mention epoch context not found",
            result.getErrorMessage().contains("Epoch context not found"));
    }

    /**
     * Test 5: Submit solution after block produced - should be rejected
     */
    @Test
    public void testSubmitSolutionAfterBlockProduced() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Mark block as produced
        context.markBlockProduced();

        // Create solution block
        Bytes32 goodHash = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solutionBlock = createMockBlock(currentEpoch, goodHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertFalse("Solution should be rejected", result.isAccepted());
        assertTrue("Error message should mention block already produced",
            result.getErrorMessage().contains("Block already produced"));
    }

    /**
     * Test 6: Submit multiple solutions from different pools
     */
    @Test
    public void testSubmitMultipleSolutions() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Submit solution from pool1
        Bytes32 hash1 = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solution1 = createMockBlock(currentEpoch, hash1);
        SubmitResult result1 = solutionCollector.submitSolution(solution1, "pool1", currentEpoch);

        // Submit solution from pool2
        Bytes32 hash2 = createHashWithDifficulty("0x0000000000000001ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solution2 = createMockBlock(currentEpoch, hash2);
        SubmitResult result2 = solutionCollector.submitSolution(solution2, "pool2", currentEpoch);

        // Submit solution from pool3
        Bytes32 hash3 = createHashWithDifficulty("0x0000000000000002ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solution3 = createMockBlock(currentEpoch, hash3);
        SubmitResult result3 = solutionCollector.submitSolution(solution3, "pool3", currentEpoch);

        assertTrue("Solution 1 should be accepted", result1.isAccepted());
        assertTrue("Solution 2 should be accepted", result2.isAccepted());
        assertTrue("Solution 3 should be accepted", result3.isAccepted());
        assertEquals("Should have 3 solutions in context", 3, context.getSolutionsCount());
    }

    /**
     * Test 7: Get solutions for epoch
     */
    @Test
    public void testGetSolutions() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Submit 2 solutions
        Bytes32 hash1 = createHashWithDifficulty("0x0000000000000000ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solution1 = createMockBlock(currentEpoch, hash1);
        solutionCollector.submitSolution(solution1, "pool1", currentEpoch);

        Bytes32 hash2 = createHashWithDifficulty("0x0000000000000001ffffffffffffffffffffffffffffffffffffffffffffffff");
        Block solution2 = createMockBlock(currentEpoch, hash2);
        solutionCollector.submitSolution(solution2, "pool2", currentEpoch);

        // Get solutions
        List<BlockSolution> solutions = solutionCollector.getSolutions(currentEpoch);

        assertNotNull("Solutions list should not be null", solutions);
        assertEquals("Should have 2 solutions", 2, solutions.size());
    }

    /**
     * Test 8: Get solutions for non-existent epoch returns empty list
     */
    @Test
    public void testGetSolutionsNonExistentEpoch() {
        long nonExistentEpoch = 9999;

        // Get solutions for non-existent epoch
        List<BlockSolution> solutions = solutionCollector.getSolutions(nonExistentEpoch);

        assertNotNull("Solutions list should not be null", solutions);
        assertTrue("Solutions list should be empty", solutions.isEmpty());
    }

    /**
     * Test 9: Get minimum difficulty
     */
    @Test
    public void testGetMinimumDifficulty() {
        UInt256 minDiff = solutionCollector.getMinimumDifficulty();

        assertNotNull("Minimum difficulty should not be null", minDiff);
        assertEquals("Minimum difficulty should match constructor value",
            minimumDifficulty, minDiff);
    }

    /**
     * Test 10: Solution with exactly minimum difficulty is accepted
     */
    @Test
    public void testSubmitSolutionExactMinimumDifficulty() {
        long currentEpoch = 1000;

        // Create epoch context
        Block candidateBlock = createMockBlock(currentEpoch, Bytes32.ZERO);
        EpochContext context = new EpochContext(
            currentEpoch,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 64000,
            candidateBlock
        );
        epochContexts.put(currentEpoch, context);

        // Create hash that produces exactly minimum difficulty
        // minimum difficulty = 0x0000ffffffffffffffffffffffffffff...
        // So we need MAX - hash = minimum, thus hash = MAX - minimum
        UInt256 targetHash = UInt256.MAX_VALUE.subtract(minimumDifficulty);
        Bytes32 exactHash = Bytes32.wrap(targetHash.toBytes());

        Block solutionBlock = createMockBlock(currentEpoch, exactHash);

        // Submit solution
        SubmitResult result = solutionCollector.submitSolution(solutionBlock, "pool1", currentEpoch);

        assertTrue("Solution with exact minimum difficulty should be accepted",
            result.isAccepted());
    }

    // ========== Helper Methods ==========

    /**
     * Create a mock block with specified epoch and hash
     */
    private Block createMockBlock(long epoch, Bytes32 hash) {
        Block block = mock(Block.class);
        when(block.getEpoch()).thenReturn(epoch);
        when(block.getHash()).thenReturn(hash);
        return block;
    }

    /**
     * Create a hash from hex string (for testing difficulty calculation)
     */
    private Bytes32 createHashWithDifficulty(String hexHash) {
        return Bytes32.fromHexString(hexHash);
    }
}
