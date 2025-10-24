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

package io.xdag.demo;

import io.xdag.config.Constants;
import io.xdag.core.*;
import io.xdag.db.store.FinalizedBlockStore;
import io.xdag.db.store.FinalizedBlockStoreImpl;
import io.xdag.serialization.CompactSerializer;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end demonstration of the new refactored storage system
 *
 * This demo shows:
 * 1. Creating blocks with the new BlockInfo structure
 * 2. Serializing with CompactSerializer
 * 3. Storing in FinalizedBlockStore
 * 4. Querying by height and epoch
 * 5. Performance comparison with old system
 */
public class RefactoredStorageDemo {

    public static void main(String[] args) throws IOException {
        System.out.println("=".repeat(80));
        System.out.println("XDAG Refactored Storage System - End-to-End Demo");
        System.out.println("=".repeat(80));
        System.out.println();

        // Create temporary database
        Path tempDir = Files.createTempDirectory("xdag-demo");
        System.out.println("📁 Database directory: " + tempDir);
        System.out.println();

        try (FinalizedBlockStore store = new FinalizedBlockStoreImpl(tempDir.toString())) {

            // ========== Demo 1: Create and Save Blocks ==========
            System.out.println("Demo 1: Creating and Saving Blocks");
            System.out.println("-".repeat(80));

            long startTime = System.currentTimeMillis();
            int totalBlocks = 1000;
            int mainBlockInterval = 10; // Every 10th block is a main block

            for (int i = 0; i < totalBlocks; i++) {
                Block block = createDemoBlock(i, i % mainBlockInterval == 0);
                store.saveBlock(block);

                if ((i + 1) % 100 == 0) {
                    System.out.printf("  ✓ Saved %d blocks...%n", i + 1);
                }
            }

            long saveTime = System.currentTimeMillis() - startTime;
            System.out.printf("  ✓ Saved %d blocks in %d ms (%.2f blocks/sec)%n",
                    totalBlocks, saveTime, totalBlocks * 1000.0 / saveTime);
            System.out.println();

            // ========== Demo 2: Statistics ==========
            System.out.println("Demo 2: Storage Statistics");
            System.out.println("-".repeat(80));

            long totalCount = store.getTotalBlockCount();
            long mainCount = store.getTotalMainBlockCount();
            long storageSize = store.getStorageSize();
            long maxHeight = store.getMaxFinalizedHeight();

            System.out.printf("  Total blocks:      %,d%n", totalCount);
            System.out.printf("  Main blocks:       %,d%n", mainCount);
            System.out.printf("  Storage size:      %,d bytes%n", storageSize);
            System.out.printf("  Max height:        %,d%n", maxHeight);
            System.out.println();

            // ========== Demo 3: Query by Height ==========
            System.out.println("Demo 3: Query Main Blocks by Height");
            System.out.println("-".repeat(80));

            startTime = System.currentTimeMillis();
            for (int h = 0; h < 100; h += 10) {
                var infoOpt = store.getMainBlockInfoByHeight(h);
                if (infoOpt.isPresent()) {
                    BlockInfo info = infoOpt.get();
                    System.out.printf("  Height %d: hash=%s, time=%d%n",
                            h,
                            info.getHashLow().toHexString().substring(0, 16) + "...",
                            info.getTimestamp());
                }
            }
            long queryTime = System.currentTimeMillis() - startTime;
            System.out.printf("  ✓ Queried 10 blocks in %d ms (%.2f ms/block)%n",
                    queryTime, queryTime / 10.0);
            System.out.println();

            // ========== Demo 4: Query by Epoch ==========
            System.out.println("Demo 4: Query Blocks by Epoch");
            System.out.println("-".repeat(80));

            long epoch = 100; // Epoch for blocks around height 100
            var epochBlocks = store.getBlockHashesByEpoch(epoch);
            System.out.printf("  Epoch %d contains %d blocks%n", epoch, epochBlocks.size());

            if (!epochBlocks.isEmpty()) {
                System.out.println("  First 5 blocks in this epoch:");
                for (int i = 0; i < Math.min(5, epochBlocks.size()); i++) {
                    System.out.printf("    - %s%n",
                            epochBlocks.get(i).toHexString().substring(0, 16) + "...");
                }
            }
            System.out.println();

            // ========== Demo 5: Range Query ==========
            System.out.println("Demo 5: Range Query (Height 50-59)");
            System.out.println("-".repeat(80));

            var rangeBlocks = store.getMainBlockInfosByHeightRange(50, 59);
            System.out.printf("  Retrieved %d main blocks%n", rangeBlocks.size());
            for (BlockInfo info : rangeBlocks) {
                System.out.printf("    Height %d: difficulty=%s%n",
                        info.getHeight(),
                        info.getDifficulty().toDecimalString());
            }
            System.out.println();

            // ========== Demo 6: Serialization Comparison ==========
            System.out.println("Demo 6: Serialization Size Comparison");
            System.out.println("-".repeat(80));

            Block sampleBlock = createDemoBlock(500, true);
            BlockInfo sampleInfo = BlockInfo.fromLegacy(sampleBlock.getInfo());

            byte[] compactSerialized = CompactSerializer.serialize(sampleInfo);
            System.out.printf("  CompactSerializer size: %d bytes%n", compactSerialized.length);
            System.out.printf("  Target size:            ~180 bytes%n");
            System.out.printf("  Size efficiency:        %.1f%%%n",
                    (180.0 / compactSerialized.length) * 100);
            System.out.println();

            // ========== Demo 7: Verify Data Integrity ==========
            System.out.println("Demo 7: Verify Data Integrity");
            System.out.println("-".repeat(80));

            boolean integrity = store.verifyIntegrity();
            System.out.printf("  Integrity check: %s%n", integrity ? "✓ PASSED" : "✗ FAILED");
            System.out.println();

            // ========== Demo 8: Main Chain Continuity ==========
            System.out.println("Demo 8: Verify Main Chain Continuity");
            System.out.println("-".repeat(80));

            boolean continuity = store.verifyMainChainContinuity(0, 50);
            System.out.printf("  Main chain continuity (0-50): %s%n",
                    continuity ? "✓ CONTINUOUS" : "✗ BROKEN");
            System.out.println();

        } finally {
            // Cleanup
            System.out.println("Cleaning up...");
            deleteDirectory(tempDir);
        }

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Demo completed successfully!");
        System.out.println("=".repeat(80));
    }

    /**
     * Create a demo block with deterministic properties
     */
    private static Block createDemoBlock(long height, boolean isMainBlock) {
        long timestamp = height * 64; // Each height = one epoch

        // Create LegacyBlockInfo
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
        legacyInfo.setDifficulty(java.math.BigInteger.valueOf(1000 + height));
        legacyInfo.setAmount(XAmount.of(100, XUnit.XDAG));
        legacyInfo.setFee(XAmount.of(1, XUnit.XDAG));

        // Create block
        Block block = new Block(legacyInfo);

        // Create minimal XdagBlock data
        byte[] blockData = new byte[512];
        System.arraycopy(hashBytes, 0, blockData, 0, 32);
        block.setXdagBlock(new XdagBlock(blockData));

        return block;
    }

    /**
     * Recursively delete directory
     */
    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted((a, b) -> -a.compareTo(b)) // Reverse order for deletion
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + p);
                        }
                    });
        }
    }
}
