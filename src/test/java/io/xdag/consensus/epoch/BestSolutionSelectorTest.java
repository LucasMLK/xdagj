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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BestSolutionSelector
 */
public class BestSolutionSelectorTest {

    private BestSolutionSelector selector;

    @Before
    public void setUp() {
        selector = new BestSolutionSelector();
    }

    @Test
    public void testSelectBestFromSingleSolution() {
        List<BlockSolution> solutions = new ArrayList<>();
        Block block = createMockBlock(Bytes32.ZERO);
        BlockSolution solution = new BlockSolution(block, "pool1", 1000L, UInt256.valueOf(100));
        solutions.add(solution);

        BlockSolution best = selector.selectBest(solutions);

        assertNotNull("Should return the solution", best);
        assertEquals("Should return the only solution", solution, best);
    }

    @Test
    public void testSelectBestFromMultipleSolutions() {
        List<BlockSolution> solutions = new ArrayList<>();

        Block block1 = createMockBlock(Bytes32.ZERO);
        BlockSolution solution1 = new BlockSolution(block1, "pool1", 1000L, UInt256.valueOf(100));
        solutions.add(solution1);

        Block block2 = createMockBlock(Bytes32.ZERO);
        BlockSolution solution2 = new BlockSolution(block2, "pool2", 2000L, UInt256.valueOf(200));
        solutions.add(solution2);

        Block block3 = createMockBlock(Bytes32.ZERO);
        BlockSolution solution3 = new BlockSolution(block3, "pool3", 3000L, UInt256.valueOf(150));
        solutions.add(solution3);

        BlockSolution best = selector.selectBest(solutions);

        assertNotNull("Should return a solution", best);
        assertEquals("Should return highest difficulty solution", solution2, best);
    }

    @Test
    public void testSelectBestWhenTiedDifficulty() {
        List<BlockSolution> solutions = new ArrayList<>();

        Block block1 = createMockBlock(Bytes32.ZERO);
        BlockSolution solution1 = new BlockSolution(block1, "pool1", 1000L, UInt256.valueOf(100));
        solutions.add(solution1);

        Block block2 = createMockBlock(Bytes32.ZERO);
        BlockSolution solution2 = new BlockSolution(block2, "pool2", 2000L, UInt256.valueOf(100));
        solutions.add(solution2);

        BlockSolution best = selector.selectBest(solutions);

        assertNotNull("Should return a solution", best);
        assertEquals("Should return first submitted when tied", solution1, best);
    }

    @Test
    public void testSelectBestFromEmptyList() {
        List<BlockSolution> solutions = Collections.emptyList();

        BlockSolution best = selector.selectBest(solutions);

        assertNull("Should return null for empty list", best);
    }

    @Test
    public void testSelectBestFromNullList() {
        BlockSolution best = selector.selectBest(null);

        assertNull("Should return null for null list", best);
    }

    private Block createMockBlock(Bytes32 hash) {
        Block block = mock(Block.class);
        when(block.getHash()).thenReturn(hash);
        return block;
    }
}
