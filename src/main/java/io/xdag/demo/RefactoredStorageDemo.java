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
 * TODO v5.1: DELETED - Block and LegacyBlockInfo classes no longer exist
 * This demo is temporarily disabled - waiting for migration to BlockV5
 *
 * This demo shows:
 * 1. Creating blocks with the new BlockInfo structure
 * 2. Serializing with CompactSerializer
 * 3. Storing in FinalizedBlockStore
 * 4. Querying by height and epoch
 * 5. Performance comparison with old system
 */
public class RefactoredStorageDemo {

    // TODO v5.1: Restore after migrating to BlockV5
    /*
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

            // ... rest of demos commented out ...

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

    private static Block createDemoBlock(long height, boolean isMainBlock) {
        // ... implementation commented out ...
        return null;
    }
    */

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
