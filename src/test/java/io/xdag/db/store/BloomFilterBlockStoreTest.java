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

import io.xdag.config.Constants;
import io.xdag.core.*;
import org.apache.tuweni.bytes.Bytes32;
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

import static org.junit.Assert.*;

/**
 * Test class for BloomFilterBlockStore
 */
public class BloomFilterBlockStoreTest {

    private Path tempDir;
    private FinalizedBlockStore baseStore;
    private BloomFilterBlockStore bloomStore;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("bloom-filter-test");
        baseStore = new FinalizedBlockStoreImpl(tempDir.toString());
        bloomStore = new BloomFilterBlockStore(baseStore);
    }

    @After
    public void tearDown() throws IOException {
        if (bloomStore != null) {
            bloomStore.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testBloomFilterBasicOperation() {
        Block block = createTestBlock(100, true);

        // Initially should not exist
        assertFalse(bloomStore.hasBlock(block.getHashLow()));

        // Save block
        bloomStore.saveBlock(block);

        // Now should exist
        assertTrue(bloomStore.hasBlock(block.getHashLow()));
    }

    @Test
    public void testBloomFilterNegativeLookup() {
        // Add 100 blocks
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            blocks.add(createTestBlock(i, i % 10 == 0));
        }
        bloomStore.saveBatch(blocks);

        // Query non-existent blocks (should be fast with Bloom Filter)
        int notFoundCount = 0;
        for (int i = 1000; i < 2000; i++) {
            Block testBlock = createTestBlock(i, false);
            if (!bloomStore.hasBlock(testBlock.getHashLow())) {
                notFoundCount++;
            }
        }

        // All should be not found
        assertEquals(1000, notFoundCount);

        // Check that Bloom Filter saved us from DB lookups
        double hitRate = bloomStore.getBloomHitRate();
        assertTrue("Bloom hit rate should be > 90%", hitRate > 90.0);
    }

    @Test
    public void testBloomFilterFalsePositiveRate() {
        // Add blocks
        for (int i = 0; i < 1000; i++) {
            bloomStore.saveBlock(createTestBlock(i, true));
        }

        bloomStore.resetStatistics();

        // Query many non-existent blocks
        for (int i = 10000; i < 20000; i++) {
            Block testBlock = createTestBlock(i, false);
            bloomStore.hasBlock(testBlock.getHashLow());
        }

        // False positive rate should be low (< 2%)
        double fpRate = bloomStore.getFalsePositiveRate();
        assertTrue("False positive rate should be < 2%: " + fpRate, fpRate < 2.0);
    }

    @Test
    public void testBloomFilterWithBatchSave() {
        List<Block> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            batch.add(createTestBlock(i, i % 5 == 0));
        }

        long saved = bloomStore.saveBatch(batch);
        assertEquals(500, saved);

        // All blocks should be findable
        for (Block block : batch) {
            assertTrue(bloomStore.hasBlock(block.getHashLow()));
        }
    }

    @Test
    public void testBloomFilterDoesNotAffectOtherOperations() {
        // Save blocks
        for (int i = 0; i < 10; i++) {
            bloomStore.saveBlock(createTestBlock(i, i % 2 == 0));
        }

        // Other operations should work normally
        assertEquals(10, bloomStore.getTotalBlockCount());
        assertEquals(5, bloomStore.getTotalMainBlockCount());

        var block = bloomStore.getMainBlockInfoByHeight(0);
        assertTrue(block.isPresent());
    }

    @Test
    public void testStatistics() {
        // Add some blocks
        for (int i = 0; i < 100; i++) {
            bloomStore.saveBlock(createTestBlock(i, true));
        }

        bloomStore.resetStatistics();

        // Perform queries
        for (int i = 0; i < 50; i++) {
            bloomStore.hasBlock(createTestBlock(i, false).getHashLow()); // Exists
        }
        for (int i = 1000; i < 1050; i++) {
            bloomStore.hasBlock(createTestBlock(i, false).getHashLow()); // Not exists
        }

        // Check statistics are tracked
        assertTrue(bloomStore.getBloomHitRate() >= 0);
        assertTrue(bloomStore.getFalsePositiveRate() >= 0);
    }

    @Test
    public void testPerformanceImprovement() {
        // Add blocks
        for (int i = 0; i < 1000; i++) {
            bloomStore.saveBlock(createTestBlock(i, true));
        }

        bloomStore.resetStatistics();

        // Query many non-existent blocks (should be fast)
        long startTime = System.nanoTime();
        for (int i = 10000; i < 20000; i++) {
            bloomStore.hasBlock(createTestBlock(i, false).getHashLow());
        }
        long bloomTime = System.nanoTime() - startTime;

        // Most queries should hit Bloom Filter (not DB)
        double hitRate = bloomStore.getBloomHitRate();
        assertTrue("Bloom hit rate should be > 95%: " + hitRate, hitRate > 95.0);

        System.out.printf("Bloom Filter Performance:%n");
        System.out.printf("  10000 queries in %d ms%n", bloomTime / 1_000_000);
        System.out.printf("  Hit rate: %.2f%%%n", hitRate);
        System.out.printf("  False positive rate: %.2f%%%n", bloomStore.getFalsePositiveRate());
    }

    // ========== Helper Methods ==========

    private Block createTestBlock(long height, boolean isMainBlock) {
        long timestamp = height * 64;

        LegacyBlockInfo legacyInfo = new LegacyBlockInfo();

        // Generate deterministic hash
        byte[] hashBytes = new byte[32];
        for (int i = 0; i < 8; i++) {
            hashBytes[i] = (byte) (height >> (i * 8));
        }
        for (int i = 8; i < 32; i++) {
            hashBytes[i] = (byte) ((height * 31 + i) & 0xFF);
        }

        legacyInfo.setHashlow(hashBytes);
        legacyInfo.setTimestamp(timestamp);
        legacyInfo.setHeight(height);
        legacyInfo.type = 0x01;
        legacyInfo.flags = isMainBlock ? Constants.BI_MAIN : 0;
        legacyInfo.setDifficulty(java.math.BigInteger.valueOf(1000));
        legacyInfo.setAmount(XAmount.of(100, XUnit.XDAG));
        legacyInfo.setFee(XAmount.of(1, XUnit.XDAG));

        Block block = new Block(legacyInfo);

        byte[] blockData = new byte[512];
        System.arraycopy(hashBytes, 0, blockData, 0, 32);
        block.setXdagBlock(new XdagBlock(blockData));

        return block;
    }
}
