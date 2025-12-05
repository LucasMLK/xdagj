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

package io.xdag.p2p;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagChainImpl;
import io.xdag.core.DagImportResult;
import io.xdag.core.Link;
import io.xdag.utils.TimeUtils;
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
 * Integration test for two-node synchronization.
 *
 * <p>This test simulates the scenario where:
 * <ol>
 *   <li>Node1 starts and generates 100 blocks</li>
 *   <li>Node2 starts fresh (only genesis)</li>
 *   <li>Node2 syncs blocks from Node1 using the epoch-based sync protocol</li>
 *   <li>Verify both nodes have identical block data</li>
 * </ol>
 *
 * <p>This test validates the linear sync approach without actual P2P networking,
 * by directly simulating the message exchange between nodes.
 */
public class TwoNodeSyncIntegrationTest {

    private static final int TARGET_BLOCK_COUNT = 50; // Reduced to avoid future epoch issues

    private DagKernel node1Kernel;
    private DagKernel node2Kernel;
    private DagChainImpl node1Chain;
    private DagChainImpl node2Chain;
    private Path tempDir1;
    private Path tempDir2;
    private Bytes miningCoinbase;
    private long genesisEpoch;

    @Before
    public void setUp() throws IOException {
        // Use epoch from 60 epochs ago (allows generating ~50 blocks without hitting future)
        genesisEpoch = TimeUtils.getCurrentEpochNumber() - 60;

        // Create temporary directories for both nodes
        tempDir1 = Files.createTempDirectory("xdagj-node1-");
        tempDir2 = Files.createTempDirectory("xdagj-node2-");

        // Create genesis files with same epoch for both nodes
        createGenesisFile(tempDir1, genesisEpoch);
        createGenesisFile(tempDir2, genesisEpoch);

        // Initialize Node1
        Config config1 = createConfig(tempDir1);
        Wallet wallet1 = new Wallet(config1);
        wallet1.unlock("test-password");
        wallet1.addAccountRandom();
        node1Kernel = new DagKernel(config1, wallet1);
        node1Kernel.setP2pEnabled(false);
        node1Kernel.start();
        node1Chain = (DagChainImpl) node1Kernel.getDagChain();

        // Initialize Node2
        Config config2 = createConfig(tempDir2);
        Wallet wallet2 = new Wallet(config2);
        wallet2.unlock("test-password");
        wallet2.addAccountRandom();
        node2Kernel = new DagKernel(config2, wallet2);
        node2Kernel.setP2pEnabled(false);
        node2Kernel.start();
        node2Chain = (DagChainImpl) node2Kernel.getDagChain();

        // Initialize mining coinbase (20 bytes)
        miningCoinbase = Bytes.wrap(new byte[20]);
        node1Chain.setMiningCoinbase(miningCoinbase);
        node2Chain.setMiningCoinbase(miningCoinbase);
    }

    @After
    public void tearDown() {
        stopKernel(node1Kernel);
        stopKernel(node2Kernel);
        deleteDirectory(tempDir1);
        deleteDirectory(tempDir2);
    }

    /**
     * Test: Linear sync from genesis to height 100.
     *
     * <p>Simulates the sync protocol:
     * <ol>
     *   <li>Node1 generates 100 blocks (one per epoch)</li>
     *   <li>Node2 requests epoch hashes in batches</li>
     *   <li>Node2 requests and imports blocks</li>
     *   <li>Verify final state matches</li>
     * </ol>
     */
    @Test
    public void testLinearSync_Node2SyncsFromNode1() throws Exception {
        System.out.println("\n========== Two-Node Linear Sync Test ==========\n");

        // Step 1: Node1 generates blocks
        System.out.println("Step 1: Node1 generating " + TARGET_BLOCK_COUNT + " blocks...");

        List<Block> node1Blocks = new ArrayList<>();
        long currentEpoch = genesisEpoch + 1;

        for (int i = 0; i < TARGET_BLOCK_COUNT; i++) {
            Block candidate = createBlockForEpoch(node1Chain, currentEpoch);
            DagImportResult result = node1Chain.tryToConnect(candidate);

            if (result.isSuccess()) {
                node1Blocks.add(candidate);
                if ((i + 1) % 20 == 0) {
                    System.out.println("  Generated " + (i + 1) + " blocks...");
                }
            } else {
                System.out.println("  Block " + i + " (epoch=" + currentEpoch + ") failed: " + result.getStatus() +
                        " - " + result.getErrorMessage());
            }

            currentEpoch++;
        }

        long node1Height = node1Chain.getMainChainLength();
        System.out.println("Node1 final height: " + node1Height);
        assertTrue("Node1 should have blocks", node1Height > 1);

        // Step 2: Record Node2 initial state
        System.out.println("\nStep 2: Recording Node2 initial state...");
        long node2InitialHeight = node2Chain.getMainChainLength();
        System.out.println("Node2 initial height: " + node2InitialHeight);
        // Node2 may have genesis + some blocks from EpochConsensusManager
        // The key test is that after sync, Node2 matches Node1

        // Step 3: Simulate linear sync protocol
        System.out.println("\nStep 3: Simulating linear sync protocol...");
        int syncedBlocks = simulateLinearSync(node1Chain, node2Chain, genesisEpoch + 1, currentEpoch - 1);
        System.out.println("Synced " + syncedBlocks + " blocks to Node2");

        // Step 4: Verify final state
        System.out.println("\nStep 4: Verifying final state...");
        long node2FinalHeight = node2Chain.getMainChainLength();
        System.out.println("Node1 height: " + node1Height);
        System.out.println("Node2 height: " + node2FinalHeight);

        assertEquals("Node2 height should match Node1", node1Height, node2FinalHeight);

        // Verify all blocks match
        System.out.println("\nStep 5: Verifying block-by-block consistency...");
        int matchCount = 0;
        int mismatchCount = 0;

        for (long h = 1; h <= node1Height; h++) {
            Block b1 = node1Chain.getMainBlockByHeight(h);
            Block b2 = node2Chain.getMainBlockByHeight(h);

            if (b1 == null || b2 == null) {
                System.out.println("  Height " + h + ": MISSING (b1=" + (b1 != null) + ", b2=" + (b2 != null) + ")");
                mismatchCount++;
                continue;
            }

            if (b1.getHash().equals(b2.getHash())) {
                matchCount++;
            } else {
                System.out.println("  Height " + h + ": MISMATCH");
                System.out.println("    Node1: " + b1.getHash().toHexString().substring(0, 16) + " (epoch=" + b1.getEpoch() + ")");
                System.out.println("    Node2: " + b2.getHash().toHexString().substring(0, 16) + " (epoch=" + b2.getEpoch() + ")");
                mismatchCount++;
            }
        }

        System.out.println("\nResults:");
        System.out.println("  Matching blocks: " + matchCount);
        System.out.println("  Mismatched blocks: " + mismatchCount);

        assertEquals("All blocks should match", 0, mismatchCount);
        System.out.println("\n========== Test PASSED ==========\n");
    }

    /**
     * Test: Sync with epoch gaps (sparse chain).
     *
     * <p>Simulates the scenario where some epochs have no blocks (node was offline).
     */
    @Test
    public void testLinearSync_WithEpochGaps() throws Exception {
        System.out.println("\n========== Sparse Chain Sync Test ==========\n");

        // Step 1: Node1 generates blocks with gaps (every 3rd epoch)
        System.out.println("Step 1: Node1 generating sparse blocks...");

        List<Long> usedEpochs = new ArrayList<>();
        long epoch = genesisEpoch + 1;

        for (int i = 0; i < 30; i++) {
            Block candidate = createBlockForEpoch(node1Chain, epoch);
            DagImportResult result = node1Chain.tryToConnect(candidate);

            if (result.isSuccess()) {
                usedEpochs.add(epoch);
            }

            // Skip 2 epochs (simulate offline periods)
            epoch += 3;
        }

        long node1Height = node1Chain.getMainChainLength();
        System.out.println("Node1 height: " + node1Height);
        System.out.println("Epochs used: " + usedEpochs.size());

        // Step 2: Simulate linear sync
        System.out.println("\nStep 2: Simulating linear sync over sparse epochs...");
        long startEpoch = genesisEpoch + 1;
        long endEpoch = epoch - 1;

        int syncedBlocks = simulateLinearSync(node1Chain, node2Chain, startEpoch, endEpoch);
        System.out.println("Synced " + syncedBlocks + " blocks");

        // Step 3: Verify
        long node2Height = node2Chain.getMainChainLength();
        System.out.println("\nNode1 height: " + node1Height);
        System.out.println("Node2 height: " + node2Height);

        assertEquals("Heights should match", node1Height, node2Height);

        // Verify block hashes
        for (long h = 1; h <= node1Height; h++) {
            Block b1 = node1Chain.getMainBlockByHeight(h);
            Block b2 = node2Chain.getMainBlockByHeight(h);

            assertNotNull("Node1 block at height " + h + " should exist", b1);
            assertNotNull("Node2 block at height " + h + " should exist", b2);
            assertEquals("Block hash at height " + h + " should match",
                    b1.getHash(), b2.getHash());
        }

        System.out.println("\n========== Test PASSED ==========\n");
    }

    /**
     * Simulate linear sync protocol between two chains.
     *
     * <p>This simulates:
     * <ol>
     *   <li>GET_EPOCH_HASHES request from receiver</li>
     *   <li>EPOCH_HASHES_REPLY from sender</li>
     *   <li>GET_BLOCKS request for missing blocks</li>
     *   <li>BLOCKS_REPLY with block data</li>
     *   <li>Block import on receiver</li>
     * </ol>
     *
     * @param sender   the chain to sync from (Node1)
     * @param receiver the chain to sync to (Node2)
     * @param startEpoch start of epoch range
     * @param endEpoch   end of epoch range
     * @return number of blocks synced
     */
    private int simulateLinearSync(DagChain sender, DagChain receiver,
                                   long startEpoch, long endEpoch) {
        int totalSynced = 0;
        int batchSize = 256; // MAX_EPOCHS_PER_REQUEST

        for (long batchStart = startEpoch; batchStart <= endEpoch; batchStart += batchSize) {
            long batchEnd = Math.min(batchStart + batchSize - 1, endEpoch);

            // Step A: Simulate GET_EPOCH_HASHES -> EPOCH_HASHES_REPLY
            List<Bytes32> hashesToFetch = new ArrayList<>();

            for (long epoch = batchStart; epoch <= batchEnd; epoch++) {
                List<Bytes32> epochHashes = sender.getBlockHashesByEpoch(epoch);
                for (Bytes32 hash : epochHashes) {
                    // Check if receiver already has this block
                    if (receiver.getBlockByHash(hash) == null) {
                        hashesToFetch.add(hash);
                    }
                }
            }

            if (hashesToFetch.isEmpty()) {
                continue;
            }

            // Step B: Simulate GET_BLOCKS -> BLOCKS_REPLY
            List<Block> blocksToSync = new ArrayList<>();
            for (Bytes32 hash : hashesToFetch) {
                Block block = sender.getBlockByHash(hash);
                if (block != null) {
                    blocksToSync.add(block);
                }
            }

            // Step C: Import blocks on receiver (in epoch order for linear sync)
            blocksToSync.sort((a, b) -> Long.compare(a.getEpoch(), b.getEpoch()));

            for (Block block : blocksToSync) {
                DagImportResult result = receiver.tryToConnect(block);
                if (result.isSuccess()) {
                    totalSynced++;
                }
            }
        }

        return totalSynced;
    }

    /**
     * Create a block for a specific epoch.
     */
    private Block createBlockForEpoch(DagChain chain, long epoch) {
        // Get parent block (latest main block)
        long height = chain.getMainChainLength();
        Block parent = height > 0 ? chain.getMainBlockByHeight(height) : null;

        // Create links to parent
        List<Link> links = new ArrayList<>();
        if (parent != null) {
            links.add(Link.toBlock(parent.getHash()));
        }

        // Get difficulty from chain stats
        UInt256 difficulty = chain.getChainStats().getBaseDifficultyTarget();

        // Create block using factory method
        return Block.createWithNonce(
                epoch,
                difficulty,
                Bytes32.random(),  // random nonce (devnet accepts any PoW)
                miningCoinbase,
                links);
    }

    private Config createConfig(Path dir) {
        return new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return dir.toString();
            }

            @Override
            public String getRootDir() {
                return dir.toString();
            }
        };
    }

    private void createGenesisFile(Path dir, long epoch) throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"epoch\": " + epoch + ",\n" +
                "  \"difficulty\": \"0x1\",\n" +
                "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "  \"alloc\": {}\n" +
                "}";

        Path genesisFile = dir.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }

    private void stopKernel(DagKernel kernel) {
        if (kernel != null) {
            try {
                kernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping kernel: " + e.getMessage());
            }
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir != null && Files.exists(dir)) {
            try {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(file -> {
                                if (!file.delete()) {
                                    System.err.println("Failed to delete: " + file);
                                }
                            });
                }
            } catch (Exception e) {
                System.err.println("Error deleting directory: " + e.getMessage());
            }
        }
    }
}
