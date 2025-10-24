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
 * Test class for CachedBlockStore
 */
public class CachedBlockStoreTest {

    private Path tempDir;
    private FinalizedBlockStore baseStore;
    private CachedBlockStore cachedStore;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cached-store-test");
        baseStore = new FinalizedBlockStoreImpl(tempDir.toString());
        cachedStore = new CachedBlockStore(baseStore);
    }

    @After
    public void tearDown() throws IOException {
        if (cachedStore != null) {
            cachedStore.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testBlockInfoCaching() {
        Block block = createTestBlock(100, true);
        cachedStore.saveBlock(block);

        cachedStore.resetStatistics();

        // First access - cache hit (saved during saveBlock)
        var info1 = cachedStore.getBlockInfoByHash(block.getHashLow());
        assertTrue(info1.isPresent());

        // Second access - cache hit
        var info2 = cachedStore.getBlockInfoByHash(block.getHashLow());
        assertTrue(info2.isPresent());

        // Should have 100% cache hit rate
        assertEquals(100.0, cachedStore.getBlockInfoCacheHitRate(), 0.1);
    }

    @Test
    public void testBlockCaching() {
        Block block = createTestBlock(200, false);
        cachedStore.saveBlock(block);

        cachedStore.resetStatistics();

        // First access - cache hit (saved during saveBlock)
        var block1 = cachedStore.getBlockByHash(block.getHashLow());
        assertTrue(block1.isPresent());

        // Second access - cache hit
        var block2 = cachedStore.getBlockByHash(block.getHashLow());
        assertTrue(block2.isPresent());

        // Should have 100% cache hit rate
        assertEquals(100.0, cachedStore.getBlockCacheHitRate(), 0.1);
    }

    @Test
    public void testHeightCaching() {
        Block block = createTestBlock(300, true);
        cachedStore.saveBlock(block);

        cachedStore.resetStatistics();

        // First access by height - cache hit (saved during saveBlock)
        var info1 = cachedStore.getMainBlockInfoByHeight(300);
        assertTrue(info1.isPresent());

        // Second access - cache hit
        var info2 = cachedStore.getMainBlockInfoByHeight(300);
        assertTrue(info2.isPresent());

        // Should have 100% cache hit rate
        assertEquals(100.0, cachedStore.getHeightCacheHitRate(), 0.1);
    }

    @Test
    public void testCacheMiss() {
        // Save block without caching
        Block block = createTestBlock(400, false);
        baseStore.saveBlock(block);

        cachedStore.resetStatistics();

        // First access - cache miss
        var info1 = cachedStore.getBlockInfoByHash(block.getHashLow());
        assertTrue(info1.isPresent());

        // Second access - cache hit (cached after first access)
        var info2 = cachedStore.getBlockInfoByHash(block.getHashLow());
        assertTrue(info2.isPresent());

        // Should have 50% cache hit rate (1 miss, 1 hit)
        assertEquals(50.0, cachedStore.getBlockInfoCacheHitRate(), 0.1);
    }

    @Test
    public void testBatchSaveCaching() {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            blocks.add(createTestBlock(i, i % 10 == 0));
        }

        cachedStore.saveBatch(blocks);
        cachedStore.resetStatistics();

        // All blocks should be cached
        for (Block block : blocks) {
            assertTrue(cachedStore.getBlockInfoByHash(block.getHashLow()).isPresent());
        }

        // Should have 100% cache hit rate
        assertEquals(100.0, cachedStore.getBlockInfoCacheHitRate(), 0.1);
    }

    @Test
    public void testCachePerformance() {
        // Save 1000 blocks
        for (int i = 0; i < 1000; i++) {
            cachedStore.saveBlock(createTestBlock(i, i % 10 == 0));
        }

        cachedStore.resetStatistics();

        // Access first 100 blocks multiple times
        long startTime = System.nanoTime();
        for (int repeat = 0; repeat < 10; repeat++) {
            for (int i = 0; i < 100; i++) {
                Block block = createTestBlock(i, i % 10 == 0);
                cachedStore.getBlockInfoByHash(block.getHashLow());
            }
        }
        long cachedTime = System.nanoTime() - startTime;

        // Should have high cache hit rate (90% hits after first round)
        assertTrue("Cache hit rate should be > 90%",
                cachedStore.getBlockInfoCacheHitRate() > 90.0);

        System.out.printf("Cache Performance:%n");
        System.out.printf("  1000 queries in %d ms%n", cachedTime / 1_000_000);
        System.out.printf("  Cache hit rate: %.1f%%%n", cachedStore.getBlockInfoCacheHitRate());
    }

    @Test
    public void testClearCaches() {
        // Save some blocks
        for (int i = 0; i < 50; i++) {
            cachedStore.saveBlock(createTestBlock(i, true));
        }

        // Verify caches are populated
        var stats1 = cachedStore.getCacheStats();
        assertTrue(stats1.blockInfoCacheSize() > 0);

        // Clear caches
        cachedStore.clearCaches();

        // Verify caches are empty
        var stats2 = cachedStore.getCacheStats();
        assertEquals(0, stats2.blockInfoCacheSize());
        assertEquals(0, stats2.blockCacheSize());
        assertEquals(0, stats2.heightCacheSize());
    }

    @Test
    public void testCacheStatistics() {
        // Save blocks
        for (int i = 0; i < 100; i++) {
            cachedStore.saveBlock(createTestBlock(i, i % 5 == 0));
        }

        cachedStore.resetStatistics();

        // Perform various queries
        for (int i = 0; i < 50; i++) {
            cachedStore.getBlockInfoByHash(createTestBlock(i, false).getHashLow());
        }
        for (int i = 0; i < 20; i += 5) {
            cachedStore.getMainBlockInfoByHeight(i);
        }

        // Check statistics
        var stats = cachedStore.getCacheStats();
        assertTrue(stats.blockInfoCacheSize() > 0);
        assertTrue(stats.blockInfoHitRate() >= 0);
        assertTrue(stats.heightHitRate() >= 0);
    }

    @Test
    public void testHasBlockWithCache() {
        Block block = createTestBlock(500, false);
        cachedStore.saveBlock(block);

        // hasBlock should use cache
        assertTrue(cachedStore.hasBlock(block.getHashLow()));

        // Non-existent block
        assertFalse(cachedStore.hasBlock(createTestBlock(9999, false).getHashLow()));
    }

    @Test
    public void testCustomCacheSizes() {
        // Create store with small cache sizes
        CachedBlockStore smallCache = new CachedBlockStore(baseStore, 10, 5, 10);

        // Save more blocks than cache size
        for (int i = 0; i < 50; i++) {
            smallCache.saveBlock(createTestBlock(i, true));
        }

        // Cache should be limited in size
        var stats = smallCache.getCacheStats();
        assertTrue(stats.blockInfoCacheSize() <= 10);
        assertTrue(stats.blockCacheSize() <= 5);

        smallCache.close();
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
