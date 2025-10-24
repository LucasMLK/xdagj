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

package io.xdag.db.store;

import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.config.DevnetConfig;
import io.xdag.core.*;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Test class for FinalizedBlockStore
 */
public class FinalizedBlockStoreTest {

    private FinalizedBlockStore store;
    private Path tempDir;
    private Config config;

    @Before
    public void setUp() throws IOException {
        // Create temporary directory for test database
        tempDir = Files.createTempDirectory("finalized-block-store-test");
        store = new FinalizedBlockStoreImpl(tempDir.toString());
        // Initialize config for block creation
        config = new DevnetConfig();
    }

    @After
    public void tearDown() throws IOException {
        // Close store
        if (store != null) {
            store.close();
        }

        // Delete temporary directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    // ========== Basic Operations Tests ==========

    @Test
    public void testSaveAndGetBlock() {
        Block block = createTestBlock(100, true);

        // Save block
        store.saveBlock(block);

        // Verify block exists
        assertTrue(store.hasBlock(block.getHash()));

        // Get block back
        Optional<Block> retrieved = store.getBlockByHash(block.getHash());
        assertTrue(retrieved.isPresent());
        assertEquals(block.getHash(), retrieved.get().getHash());
    }

    @Test
    public void testSaveAndGetBlockInfo() {
        Block block = createTestBlock(200, true);
        // Convert to BlockInfo using BlockInfo.fromLegacy()
        BlockInfo blockInfo = BlockInfo.fromLegacy(block.getInfo().toLegacy());

        // Save block info
        store.saveBlockInfo(blockInfo);

        // Get block info back
        Optional<BlockInfo> retrieved = store.getBlockInfoByHash(blockInfo.getHash());
        assertTrue(retrieved.isPresent());
        assertEquals(blockInfo.getHash(), retrieved.get().getHash());
        assertEquals(blockInfo.getHeight(), retrieved.get().getHeight());
        assertEquals(blockInfo.getTimestamp(), retrieved.get().getTimestamp());
    }

    @Test
    public void testHasBlock() {
        Block block = createTestBlock(300, false);

        // Should not exist initially
        assertFalse(store.hasBlock(block.getHash()));

        // Save and check again
        store.saveBlock(block);
        assertTrue(store.hasBlock(block.getHash()));
    }

    @Test
    public void testGetNonExistentBlock() {
        Bytes32 randomHash = Bytes32.random();

        // Should return empty
        assertFalse(store.hasBlock(randomHash));
        assertFalse(store.getBlockByHash(randomHash).isPresent());
        assertFalse(store.getBlockInfoByHash(randomHash).isPresent());
    }

    // ========== Batch Operations Tests ==========

    @Test
    public void testSaveBatch() {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            blocks.add(createTestBlock(1000 + i, i % 10 == 0)); // Every 10th is main block
        }

        // Save batch
        long saved = store.saveBatch(blocks);
        assertEquals(100, saved);

        // Verify all blocks exist
        for (Block block : blocks) {
            assertTrue(store.hasBlock(block.getHash()));
        }
    }

    @Test
    public void testSaveBatchEmpty() {
        List<Block> emptyList = new ArrayList<>();
        long saved = store.saveBatch(emptyList);
        assertEquals(0, saved);
    }

    // ========== Main Chain Index Tests ==========

    @Test
    public void testMainChainIndex() {
        // Create main blocks at different heights
        Block block1 = createTestBlock(100, true);
        Block block2 = createTestBlock(101, true);
        Block block3 = createTestBlock(102, true);

        store.saveBlock(block1);
        store.saveBlock(block2);
        store.saveBlock(block3);

        // Get by height
        Optional<Block> retrieved1 = store.getMainBlockByHeight(100);
        Optional<Block> retrieved2 = store.getMainBlockByHeight(101);
        Optional<Block> retrieved3 = store.getMainBlockByHeight(102);

        assertTrue(retrieved1.isPresent());
        assertTrue(retrieved2.isPresent());
        assertTrue(retrieved3.isPresent());

        assertEquals(block1.getHash(), retrieved1.get().getHash());
        assertEquals(block2.getHash(), retrieved2.get().getHash());
        assertEquals(block3.getHash(), retrieved3.get().getHash());
    }

    @Test
    public void testMainBlockInfoByHeight() {
        Block mainBlock = createTestBlock(500, true);
        store.saveBlock(mainBlock);

        Optional<BlockInfo> retrieved = store.getMainBlockInfoByHeight(500);
        assertTrue(retrieved.isPresent());
        assertEquals(mainBlock.getHash(), retrieved.get().getHash());
        assertTrue(retrieved.get().isMainBlock());
    }

    @Test
    public void testGetMainBlocksByHeightRange() {
        // Create 10 consecutive main blocks
        for (int i = 0; i < 10; i++) {
            Block block = createTestBlock(1000 + i, true);
            store.saveBlock(block);
        }

        // Get range
        List<Block> blocks = store.getMainBlocksByHeightRange(1000, 1009);
        assertEquals(10, blocks.size());

        // Verify order and heights
        for (int i = 0; i < 10; i++) {
            // Directly get height from block's info
            assertEquals(1000L + i, blocks.get(i).getInfo().getHeight());
        }
    }

    @Test
    public void testGetMainBlockInfosByHeightRange() {
        // Create main blocks
        for (int i = 0; i < 5; i++) {
            Block block = createTestBlock(2000 + i, true);
            store.saveBlock(block);
        }

        List<BlockInfo> infos = store.getMainBlockInfosByHeightRange(2000, 2004);
        assertEquals(5, infos.size());

        for (int i = 0; i < 5; i++) {
            assertEquals(2000L + i, infos.get(i).getHeight());
            assertTrue(infos.get(i).isMainBlock());
        }
    }

    @Test
    public void testGetMaxFinalizedHeight() {
        // Initially should be -1
        assertEquals(-1, store.getMaxFinalizedHeight());

        // Add some main blocks
        store.saveBlock(createTestBlock(100, true));
        store.saveBlock(createTestBlock(200, true));
        store.saveBlock(createTestBlock(150, true));

        // Max should be 200
        assertEquals(200, store.getMaxFinalizedHeight());
    }

    @Test
    public void testVerifyMainChainContinuity() {
        // Create continuous chain
        Block prev = null;
        for (int i = 0; i < 5; i++) {
            Block block = createTestBlock(3000 + i, true);
            if (prev != null) {
                // Set maxDiffLink to point to previous block using withMaxDiffLink()
                block.setInfo(block.getInfo().withMaxDiffLink(prev.getHash()));
            }
            store.saveBlock(block);
            prev = block;
        }

        // Should be continuous
        assertTrue(store.verifyMainChainContinuity(3001, 3004));
    }

    // ========== Epoch Index Tests ==========

    @Test
    public void testEpochIndex() {
        // Create blocks in same epoch (timestamp / 64)
        long baseTime = 64000; // epoch = 1000
        Block block1 = createTestBlockWithTime(baseTime, false);
        Block block2 = createTestBlockWithTime(baseTime + 10, false);
        Block block3 = createTestBlockWithTime(baseTime + 20, false);

        store.saveBlock(block1);
        store.saveBlock(block2);
        store.saveBlock(block3);

        // Get blocks by epoch
        long epoch = baseTime / 64;
        List<Bytes32> hashes = store.getBlockHashesByEpoch(epoch);
        assertEquals(3, hashes.size());

        assertTrue(hashes.contains(block1.getHash()));
        assertTrue(hashes.contains(block2.getHash()));
        assertTrue(hashes.contains(block3.getHash()));
    }

    @Test
    public void testGetBlocksByEpoch() {
        long baseTime = 128000; // epoch = 2000
        Block block1 = createTestBlockWithTime(baseTime, false);
        Block block2 = createTestBlockWithTime(baseTime + 30, false);

        store.saveBlock(block1);
        store.saveBlock(block2);

        long epoch = baseTime / 64;
        List<Block> blocks = store.getBlocksByEpoch(epoch);
        assertEquals(2, blocks.size());
    }

    @Test
    public void testGetBlockInfosByEpoch() {
        long baseTime = 256000; // epoch = 4000
        Block block1 = createTestBlockWithTime(baseTime, false);
        Block block2 = createTestBlockWithTime(baseTime + 40, false);

        store.saveBlock(block1);
        store.saveBlock(block2);

        long epoch = baseTime / 64;
        List<BlockInfo> infos = store.getBlockInfosByEpoch(epoch);
        assertEquals(2, infos.size());
    }

    @Test
    public void testGetBlocksByEpochRange() {
        // Create blocks in different epochs
        for (int i = 0; i < 5; i++) {
            long time = (5000 + i) * 64; // epochs 5000-5004
            Block block = createTestBlockWithTime(time, false);
            store.saveBlock(block);
        }

        List<Block> blocks = store.getBlocksByEpochRange(5000, 5004);
        assertEquals(5, blocks.size());
    }

    @Test
    public void testCountBlocksInEpoch() {
        long baseTime = 512000; // epoch = 8000

        // Add 3 blocks to same epoch
        for (int i = 0; i < 3; i++) {
            Block block = createTestBlockWithTime(baseTime + i * 10, false);
            store.saveBlock(block);
        }

        long epoch = baseTime / 64;
        assertEquals(3, store.countBlocksInEpoch(epoch));
    }

    @Test
    public void testEmptyEpoch() {
        long emptyEpoch = 9999;

        List<Bytes32> hashes = store.getBlockHashesByEpoch(emptyEpoch);
        assertTrue(hashes.isEmpty());

        assertEquals(0, store.countBlocksInEpoch(emptyEpoch));
    }

    // ========== Statistics Tests ==========

    @Test
    public void testTotalBlockCount() {
        assertEquals(0, store.getTotalBlockCount());

        // Add blocks
        for (int i = 0; i < 10; i++) {
            store.saveBlock(createTestBlock(4000 + i, i % 3 == 0));
        }

        assertEquals(10, store.getTotalBlockCount());
    }

    @Test
    public void testTotalMainBlockCount() {
        assertEquals(0, store.getTotalMainBlockCount());

        // Add 10 blocks, 3 of which are main blocks
        for (int i = 0; i < 10; i++) {
            boolean isMain = (i == 0 || i == 5 || i == 9);
            store.saveBlock(createTestBlock(5000 + i, isMain));
        }

        assertEquals(3, store.getTotalMainBlockCount());
    }

    @Test
    public void testGetStatsForRange() {
        // Add main blocks
        for (int i = 0; i < 5; i++) {
            store.saveBlock(createTestBlock(6000 + i, true));
        }

        FinalizedBlockStore.FinalizedStats stats = store.getStatsForRange(6000, 6004);

        assertEquals(5, stats.blockCount());
        assertEquals(5, stats.mainBlockCount());
        assertEquals(6000, stats.firstHeight());
        assertEquals(6004, stats.lastHeight());
    }

    @Test
    public void testGetStorageSize() {
        // Add some blocks
        for (int i = 0; i < 10; i++) {
            store.saveBlock(createTestBlock(7000 + i, true));
        }

        long size = store.getStorageSize();
        // Should be >= 0 (may be 0 if not yet flushed to SST files)
        assertTrue(size >= 0);
    }

    // ========== Maintenance Tests ==========

    @Test
    public void testRebuildIndexes() {
        // Add blocks
        for (int i = 0; i < 10; i++) {
            store.saveBlock(createTestBlock(8000 + i, i % 2 == 0));
        }

        // Rebuild indexes
        long count = store.rebuildIndexes();
        assertEquals(10, count);

        // Verify indexes still work
        assertTrue(store.getMainBlockByHeight(8000).isPresent());
    }

    @Test
    public void testVerifyIntegrity() {
        // Add valid blocks
        for (int i = 0; i < 5; i++) {
            store.saveBlock(createTestBlock(9000 + i, true));
        }

        // Should pass integrity check
        assertTrue(store.verifyIntegrity());
    }

    @Test
    public void testCompact() {
        // Add blocks
        for (int i = 0; i < 10; i++) {
            store.saveBlock(createTestBlock(10000 + i, true));
        }

        // Compact should not throw
        store.compact();

        // Data should still be accessible
        assertTrue(store.hasBlock(createTestBlock(10000, true).getHash()));
    }

    // ========== Helper Methods ==========

    /**
     * Create a test block with given height and main block flag
     */
    private Block createTestBlock(long height, boolean isMainBlock) {
        long timestamp = height * 64; // Simple mapping
        return createTestBlockWithTime(timestamp, isMainBlock);
    }

    /**
     * Create a test block with specific timestamp
     *
     * This method creates a proper Block using the Block constructor that generates
     * realistic XdagBlock data. The hash is then calculated naturally from the XdagBlock,
     * ensuring consistency when the block is loaded/saved.
     */
    private Block createTestBlockWithTime(long timestamp, boolean isMainBlock) {
        // Create a proper block using the Block constructor
        // This generates proper XdagBlock data with correct structure
        Block block = new Block(config, timestamp, null, null, false, null, null, -1, XAmount.of(1, XUnit.XDAG), null);

        // Force XdagBlock generation and hash calculation by calling toBytes()
        block.getXdagBlock();

        // Calculate the hash by calling getHash() which triggers hash calculation
        block.getHash();

        // Calculate height from timestamp (epoch = timestamp / 64)
        long height = timestamp / 64;

        // Set height and main block flag in BlockInfo
        BlockInfo info = block.getInfo()
                .withHeight(height);

        if (isMainBlock) {
            int newFlags = info.getFlags() | Constants.BI_MAIN;
            info = info.withFlags(newFlags);
        }

        block.setInfo(info);

        return block;
    }
}
