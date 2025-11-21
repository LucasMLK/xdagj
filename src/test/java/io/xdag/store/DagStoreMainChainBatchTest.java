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

package io.xdag.store;

import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.Link;
import io.xdag.store.rocksdb.impl.DagStoreImpl;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test for batch main chain queries (Phase 1.1)
 *
 * <p>Tests the new batch query methods:
 * <ul>
 *   <li>{@link DagStore#getMainBlocksByHeightRange(long, long, boolean)}</li>
 *   <li>{@link DagStore#verifyMainChainContinuity(long, long)}</li>
 * </ul>
 */
public class DagStoreMainChainBatchTest {

    private DagStore dagStore;
    private Path tempDir;
    private List<Block> testBlocks;

    @Before
    public void setUp() throws IOException {
        // Create temp dir
        tempDir = Files.createTempDirectory("dagstore-batch-test-");

        // Create config
        DevnetConfig config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return tempDir.toString();
            }
        };

        // Initialize DagStore
        dagStore = new DagStoreImpl(config);
        dagStore.start();

        // Create 100 test main blocks with proper chain structure
        testBlocks = new ArrayList<>();
        Block prevBlock = null;

        for (int i = 1; i <= 100; i++) {
            List<Link> links = new ArrayList<>();

            // Link to previous block (except first block)
            if (prevBlock != null) {
                links.add(Link.toBlock(prevBlock.getHash()));
            }

            // Create block header
            BlockHeader header = BlockHeader.builder()
                    .epoch(1000000L + i * 64)
                    .difficulty(UInt256.valueOf(1000))
                    .nonce(Bytes32.random())
                    .coinbase(Bytes.wrap(new byte[20]))
                    .build();

            // Create block with proper info
            Block block = Block.builder()
                    .header(header)
                    .links(links)
                    .build();

            // Create BlockInfo (main block)
            BlockInfo info = BlockInfo.builder()
                    .hash(block.getHash())
                    .epoch(header.getEpoch())
                    .height(i)  // Main block at height i
                    .difficulty(UInt256.valueOf(i * 1000))
                    .build();

            // Attach info to block
            Block blockWithInfo = block.toBuilder().info(info).build();

            // Save to store
            dagStore.saveBlock(blockWithInfo);
            testBlocks.add(blockWithInfo);

            prevBlock = blockWithInfo;
        }
    }

    @After
    public void tearDown() throws IOException {
        if (dagStore != null) {
            dagStore.stop();
        }

        // Clean up temp dir
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_Small() {
        // Fetch blocks 1-10
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 10, false);

        assertEquals(10, blocks.size());
        for (int i = 0; i < 10; i++) {
            assertNotNull("Block at index " + i + " should not be null", blocks.get(i));
            assertEquals(i + 1, blocks.get(i).getInfo().getHeight());
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_Large() {
        // Fetch blocks 1-100
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 100, false);

        assertEquals(100, blocks.size());
        for (int i = 0; i < 100; i++) {
            assertNotNull("Block at index " + i + " should not be null", blocks.get(i));
            assertEquals(i + 1, blocks.get(i).getInfo().getHeight());
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_WithRawData() {
        // Fetch with raw data
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 10, true);

        assertEquals(10, blocks.size());
        for (Block block : blocks) {
            assertNotNull(block);
            assertNotNull("Header should be loaded for raw blocks", block.getHeader());
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_PartialRange() {
        // Fetch middle range
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(40, 60, false);

        assertEquals(21, blocks.size());
        assertEquals(40, blocks.get(0).getInfo().getHeight());
        assertEquals(60, blocks.get(20).getInfo().getHeight());
    }

    @Test
    public void testGetMainBlocksByHeightRange_InvalidRange() {
        // Invalid: fromHeight > toHeight
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(50, 10, false);
        assertTrue("Invalid range should return empty list", blocks.isEmpty());

        // Invalid: fromHeight < 1
        blocks = dagStore.getMainBlocksByHeightRange(0, 10, false);
        assertTrue("Invalid fromHeight should return empty list", blocks.isEmpty());
    }

    @Test
    public void testGetMainBlocksByHeightRange_MissingBlocks() {
        // Delete some blocks to create gaps
        Block block50 = testBlocks.get(49);
        dagStore.deleteBlock(block50.getHash());

        // Fetch range with gap
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(45, 55, false);

        assertEquals(11, blocks.size());
        assertNull("Block 50 should be null (deleted)", blocks.get(5));  // Block 50 index
    }

    @Test
    public void testVerifyMainChainContinuity_Valid() {
        // Verify continuous chain
        boolean isContinuous = dagStore.verifyMainChainContinuity(1, 10);

        // Should be continuous because blocks reference each other
        assertTrue("Chain should be continuous", isContinuous);
    }

    @Test
    public void testVerifyMainChainContinuity_FullChain() {
        // Verify entire chain
        boolean isContinuous = dagStore.verifyMainChainContinuity(1, 100);

        assertTrue("Full chain should be continuous", isContinuous);
    }

    @Test
    public void testVerifyMainChainContinuity_WithGap() {
        // Delete middle block
        Block block50 = testBlocks.get(49);
        dagStore.deleteBlock(block50.getHash());

        // Verify chain with gap
        boolean isContinuous = dagStore.verifyMainChainContinuity(40, 60);

        assertFalse("Chain should not be continuous with gap", isContinuous);
    }

    @Test
    public void testVerifyMainChainContinuity_InvalidRange() {
        // Invalid range
        boolean isContinuous = dagStore.verifyMainChainContinuity(50, 10);
        assertFalse("Invalid range should return false", isContinuous);

        isContinuous = dagStore.verifyMainChainContinuity(0, 10);
        assertFalse("Invalid fromHeight should return false", isContinuous);
    }

    @Test
    public void testBatchPerformance() {
        // Measure batch query performance
        long startTime = System.nanoTime();

        // Query 100 blocks (would be 1000 in production, but 100 is enough for test)
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 100, false);

        long duration = (System.nanoTime() - startTime) / 1_000_000;  // Convert to ms

        System.out.println("Batch query 100 blocks: " + duration + "ms");

        assertEquals("Should fetch all 100 blocks", 100, blocks.size());
        assertTrue("Batch query should complete in < 100ms", duration < 100);
    }

    @Test
    public void testBatchSizeLimit() {
        // Test that batch size is limited to 10000
        // This should be limited automatically
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 50000, false);

        // Should return 10000 entries (batch size limit), including nulls for missing blocks
        assertEquals("Should respect batch size limit of 10000", 10000, blocks.size());

        // Count non-null blocks (we only have 100)
        long nonNullCount = blocks.stream().filter(b -> b != null).count();
        assertEquals("Should have 100 actual blocks", 100, nonNullCount);
    }

    @Test
    public void testCacheEfficiency() {
        // First query - populate cache
        List<Block> blocks1 = dagStore.getMainBlocksByHeightRange(1, 50, false);
        assertEquals(50, blocks1.size());

        // Measure second query (should be faster due to cache)
        long startTime = System.nanoTime();
        List<Block> blocks2 = dagStore.getMainBlocksByHeightRange(1, 50, false);
        long duration = (System.nanoTime() - startTime) / 1_000_000;

        assertEquals(50, blocks2.size());
        System.out.println("Cached query 50 blocks: " + duration + "ms");

        // Cached query should be very fast (< 10ms)
        assertTrue("Cached query should be fast (< 10ms)", duration < 10);
    }
}
