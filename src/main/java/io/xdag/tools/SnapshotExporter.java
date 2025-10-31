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

package io.xdag.tools;

import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.Blockchain;
import io.xdag.db.BlockStore;
import io.xdag.serialization.CompactSerializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Snapshot Exporter for Phase 4 Storage Migration
 *
 * Exports all blocks from BlockStore to a snapshot file for offline conversion.
 *
 * Snapshot File Format (Binary):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Header                                                       │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Magic (4 bytes): "XDAG"                                      │
 * │ Version (4 bytes): 1                                         │
 * │ Block Count (8 bytes): N                                     │
 * │ Export Timestamp (8 bytes): Unix timestamp                   │
 * │ Latest Height (8 bytes): Last block height                   │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Block 1                                                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Hash (32 bytes)                                              │
 * │ Height (8 bytes)                                             │
 * │ Timestamp (8 bytes)                                          │
 * │ Raw Block Data Length (4 bytes): L1                          │
 * │ Raw Block Data (L1 bytes): 512 bytes for Block               │
 * │ BlockInfo Length (4 bytes): L2                               │
 * │ BlockInfo Data (L2 bytes): CompactSerializer format          │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Block 2                                                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │ ... (same structure)                                         │
 * └─────────────────────────────────────────────────────────────┘
 *
 * Usage:
 * ```java
 * SnapshotExporter exporter = new SnapshotExporter(blockStore, blockchain, outputPath);
 * exporter.export();
 * ```
 *
 * @see <a href="PHASE4_STORAGE_MIGRATION_PLAN.md">Phase 4 Plan</a>
 */
@Slf4j
public class SnapshotExporter {

    /**
     * Magic number: "XDAG" (4 bytes ASCII)
     */
    private static final byte[] MAGIC = "XDAG".getBytes(StandardCharsets.US_ASCII);

    /**
     * Snapshot format version: 1
     * This allows future format upgrades while maintaining backward compatibility
     */
    private static final int VERSION = 1;

    /**
     * Progress reporting interval (blocks)
     * Report progress every 10,000 blocks
     */
    private static final int PROGRESS_INTERVAL = 10_000;

    private final BlockStore blockStore;
    private final Blockchain blockchain;
    private final String outputPath;

    /**
     * Create SnapshotExporter
     *
     * @param blockStore BlockStore instance
     * @param blockchain Blockchain instance (for getting latest height)
     * @param outputPath Output file path (e.g., "snapshot-block-2025-10-31.dat")
     */
    public SnapshotExporter(BlockStore blockStore, Blockchain blockchain, String outputPath) {
        this.blockStore = blockStore;
        this.blockchain = blockchain;
        this.outputPath = outputPath;
    }

    /**
     * Export all blocks to snapshot file
     *
     * Process:
     * 1. Get latest height from blockchain
     * 2. Count total blocks to export
     * 3. Write snapshot header
     * 4. Iterate from height 1 to nmain
     * 5. Export each block with progress logging
     *
     * @throws IOException if file write fails
     */
    public void export() throws IOException {
        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directory: " + parentDir);
            }
        }

        log.info("======== Snapshot Export Started ========");
        log.info("Output file: {}", outputFile.getAbsolutePath());

        long startTime = System.currentTimeMillis();
        long latestHeight = blockchain.getXdagStats().getNmain();

        log.info("Latest height: {}", latestHeight);
        log.info("Estimating block count...");

        // Count actual blocks (some heights may be missing)
        long blockCount = countBlocks(latestHeight);
        log.info("Total blocks to export: {}", blockCount);

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile), 1024 * 1024))) { // 1MB buffer

            // Write header
            writeHeader(out, blockCount, latestHeight);

            // Export blocks
            long exportedCount = exportBlocks(out, latestHeight);

            if (exportedCount != blockCount) {
                log.warn("Block count mismatch! Expected: {}, Actual: {}", blockCount, exportedCount);
            }

            long duration = System.currentTimeMillis() - startTime;
            long fileSize = outputFile.length();

            log.info("======== Snapshot Export Complete ========");
            log.info("Exported blocks: {}", exportedCount);
            log.info("File size: {} MB ({} bytes)", fileSize / 1024 / 1024, fileSize);
            log.info("Duration: {} seconds", duration / 1000);
            log.info("Average: {} blocks/second", exportedCount * 1000 / Math.max(duration, 1));
            log.info("Output: {}", outputFile.getAbsolutePath());
        }
    }

    /**
     * Count total blocks to export
     *
     * Iterates through all heights to count existing blocks.
     * Some heights may not have blocks (gaps in the chain).
     *
     * @param latestHeight Latest block height
     * @return Total block count
     */
    private long countBlocks(long latestHeight) {
        long count = 0;
        for (long height = 1; height <= latestHeight; height++) {
            Block block = blockStore.getBlockByHeight(height);
            if (block != null) {
                count++;
            }

            // Progress logging (every 100K heights)
            if (height % 100_000 == 0) {
                log.info("Counting... height: {} / {}, found: {} blocks", height, latestHeight, count);
            }
        }
        return count;
    }

    /**
     * Write snapshot file header
     *
     * Header format:
     * - Magic: "XDAG" (4 bytes)
     * - Version: 1 (4 bytes int)
     * - Block Count: N (8 bytes long)
     * - Export Timestamp: Unix timestamp (8 bytes long)
     * - Latest Height: Last block height (8 bytes long)
     *
     * @param out DataOutputStream
     * @param blockCount Total block count
     * @param latestHeight Latest block height
     * @throws IOException if write fails
     */
    private void writeHeader(DataOutputStream out, long blockCount, long latestHeight) throws IOException {
        log.info("Writing header...");

        // Magic number
        out.write(MAGIC);

        // Version
        out.writeInt(VERSION);

        // Block count
        out.writeLong(blockCount);

        // Export timestamp
        long exportTimestamp = System.currentTimeMillis() / 1000;
        out.writeLong(exportTimestamp);

        // Latest height
        out.writeLong(latestHeight);

        log.info("Header written: version={}, blockCount={}, latestHeight={}, timestamp={}",
                VERSION, blockCount, latestHeight, exportTimestamp);
    }

    /**
     * Export all blocks
     *
     * Iterates from height 1 to latestHeight and exports each block.
     *
     * @param out DataOutputStream
     * @param latestHeight Latest block height
     * @return Number of blocks exported
     * @throws IOException if write fails
     */
    private long exportBlocks(DataOutputStream out, long latestHeight) throws IOException {
        log.info("Exporting blocks...");

        long exportedCount = 0;
        long lastProgressTime = System.currentTimeMillis();

        for (long height = 1; height <= latestHeight; height++) {
            Block block = blockStore.getBlockByHeight(height);

            if (block == null) {
                // Height has no block (gap in chain)
                continue;
            }

            // Export block
            exportBlock(out, block);
            exportedCount++;

            // Progress logging
            if (exportedCount % PROGRESS_INTERVAL == 0) {
                long now = System.currentTimeMillis();
                long elapsed = now - lastProgressTime;
                double rate = PROGRESS_INTERVAL * 1000.0 / elapsed;

                log.info("Progress: {} / {} blocks exported ({} blocks/sec), current height: {}",
                        exportedCount, latestHeight, String.format("%.1f", rate), height);

                lastProgressTime = now;
            }
        }

        return exportedCount;
    }

    /**
     * Export single block
     *
     * Block format:
     * - Hash (32 bytes)
     * - Height (8 bytes)
     * - Timestamp (8 bytes)
     * - Raw Block Data Length (4 bytes)
     * - Raw Block Data (512 bytes for Block)
     * - BlockInfo Length (4 bytes)
     * - BlockInfo Data (variable, CompactSerializer)
     *
     * @param out DataOutputStream
     * @param block Block to export
     * @throws IOException if write fails
     */
    private void exportBlock(DataOutputStream out, Block block) throws IOException {
        Bytes32 hash = block.getHash();
        BlockInfo info = block.getInfo();

        // 1. Hash (32 bytes)
        out.write(hash.toArray());

        // 2. Height (8 bytes)
        long height = info != null ? info.getHeight() : 0;
        out.writeLong(height);

        // 3. Timestamp (8 bytes)
        long timestamp = block.getTimestamp();
        out.writeLong(timestamp);

        // 4. Raw block data
        byte[] rawData = block.getXdagBlock().getData().toArray();
        out.writeInt(rawData.length);  // Length (4 bytes)
        out.write(rawData);             // Data (512 bytes for Block)

        // 5. BlockInfo data (if exists)
        if (info != null) {
            try {
                byte[] blockInfoData = CompactSerializer.serialize(info);
                out.writeInt(blockInfoData.length);  // Length (4 bytes)
                out.write(blockInfoData);             // Data (variable)
            } catch (Exception e) {
                log.error("Failed to serialize BlockInfo for block {}", hash.toHexString(), e);
                // Write empty BlockInfo (length = 0)
                out.writeInt(0);
            }
        } else {
            // No BlockInfo (length = 0)
            out.writeInt(0);
        }
    }

    /**
     * Main method for standalone execution
     *
     * Usage:
     * java -cp xdagj.jar io.xdag.tools.SnapshotExporter <dbPath> <outputPath>
     *
     * Example:
     * java -cp xdagj.jar io.xdag.tools.SnapshotExporter ./testnet_data ./snapshot-block.dat
     *
     * @param args [dbPath, outputPath]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: SnapshotExporter <dbPath> <outputPath>");
            System.err.println("Example: SnapshotExporter ./testnet_data ./snapshot-block.dat");
            System.exit(1);
        }

        String dbPath = args[0];
        String outputPath = args[1];

        log.info("SnapshotExporter starting...");
        log.info("Database path: {}", dbPath);
        log.info("Output path: {}", outputPath);

        // TODO: Initialize Kernel/BlockStore/Blockchain from dbPath
        // This requires proper Kernel initialization which depends on Config
        // For now, this main method serves as documentation

        log.error("Standalone execution not yet implemented");
        log.error("Please use SnapshotExporter from within XdagJ application context");
        System.exit(1);
    }
}
