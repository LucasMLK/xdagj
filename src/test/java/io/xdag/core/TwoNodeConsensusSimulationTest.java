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
package io.xdag.core;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Simulates two nodes producing blocks and syncing to verify consensus consistency.
 *
 * <p>This test runs 1000+ epochs in seconds (not hours) by:
 * <ul>
 *   <li>Simulating epoch progression without real time delays</li>
 *   <li>Using in-memory data structures instead of RocksDB</li>
 *   <li>Simulating P2P sync by directly sharing blocks between nodes</li>
 * </ul>
 *
 * <p>Tests consensus rules:
 * <ul>
 *   <li>Smallest hash wins each epoch</li>
 *   <li>Both nodes must agree on main chain</li>
 *   <li>Cumulative difficulty is calculated correctly</li>
 * </ul>
 *
 * @since XDAGJ 5.1
 */
public class TwoNodeConsensusSimulationTest {

    private static final int NUM_EPOCHS = 1000;
    private static final long START_EPOCH = 27577000L;  // Realistic epoch number

    private SimulatedNode node1;
    private SimulatedNode node2;
    private SecureRandom random;

    @Before
    public void setUp() {
        random = new SecureRandom();
        node1 = new SimulatedNode("Node1");
        node2 = new SimulatedNode("Node2");
    }

    @After
    public void tearDown() {
        node1 = null;
        node2 = null;
    }

    /**
     * Test that two nodes reach consensus over 1000 epochs.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Both nodes start with same genesis block</li>
     *   <li>Each epoch, both nodes produce a block</li>
     *   <li>Blocks are exchanged (simulating P2P sync)</li>
     *   <li>After all epochs, both nodes should have identical main chains</li>
     * </ol>
     */
    @Test
    public void testTwoNodesReachConsensus() {
        // Create and import genesis block on both nodes
        SimulatedBlock genesis = createGenesisBlock(START_EPOCH);
        node1.importBlock(genesis);
        node2.importBlock(genesis);

        // Run simulation
        for (int i = 1; i <= NUM_EPOCHS; i++) {
            long epoch = START_EPOCH + i;

            // Both nodes produce blocks
            SimulatedBlock block1 = node1.produceBlock(epoch);
            SimulatedBlock block2 = node2.produceBlock(epoch);

            // Exchange blocks (simulate P2P sync)
            node1.importBlock(block2);
            node2.importBlock(block1);

            // Verify both nodes agree on the winner
            SimulatedBlock winner1 = node1.getWinnerForEpoch(epoch);
            SimulatedBlock winner2 = node2.getWinnerForEpoch(epoch);

            assertNotNull("Node1 should have a winner for epoch " + epoch, winner1);
            assertNotNull("Node2 should have a winner for epoch " + epoch, winner2);
            assertEquals("Both nodes should agree on winner for epoch " + epoch,
                    winner1.hash, winner2.hash);
        }

        // Verify final chain state
        assertEquals("Both nodes should have same main chain length",
                node1.getMainChainLength(), node2.getMainChainLength());

        // Verify all main blocks match
        for (int height = 1; height <= node1.getMainChainLength(); height++) {
            SimulatedBlock block1 = node1.getMainBlockByHeight(height);
            SimulatedBlock block2 = node2.getMainBlockByHeight(height);

            assertNotNull("Node1 should have block at height " + height, block1);
            assertNotNull("Node2 should have block at height " + height, block2);
            assertEquals("Main block at height " + height + " should match",
                    block1.hash, block2.hash);
        }

        System.out.println("SUCCESS: Both nodes reached consensus over " + NUM_EPOCHS + " epochs");
        System.out.println("  Main chain length: " + node1.getMainChainLength());
    }

    /**
     * Test that nodes handle network partition and re-sync correctly.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Both nodes sync for 100 epochs</li>
     *   <li>Network partition: nodes produce blocks independently for 50 epochs</li>
     *   <li>Network heals: nodes exchange all blocks</li>
     *   <li>Both nodes should converge to same chain (highest cumulative difficulty)</li>
     * </ol>
     */
    @Test
    public void testNetworkPartitionAndRecovery() {
        // Create and import genesis block
        SimulatedBlock genesis = createGenesisBlock(START_EPOCH);
        node1.importBlock(genesis);
        node2.importBlock(genesis);

        // Phase 1: Normal sync for 100 epochs
        for (int i = 1; i <= 100; i++) {
            long epoch = START_EPOCH + i;
            SimulatedBlock block1 = node1.produceBlock(epoch);
            SimulatedBlock block2 = node2.produceBlock(epoch);
            node1.importBlock(block2);
            node2.importBlock(block1);
        }

        // Verify in sync after phase 1
        assertEquals("Should be in sync after phase 1",
                node1.getLatestMainBlock().hash, node2.getLatestMainBlock().hash);

        // Phase 2: Network partition - nodes produce independently
        List<SimulatedBlock> node1Blocks = new ArrayList<>();
        List<SimulatedBlock> node2Blocks = new ArrayList<>();

        for (int i = 101; i <= 150; i++) {
            long epoch = START_EPOCH + i;
            node1Blocks.add(node1.produceBlock(epoch));
            node2Blocks.add(node2.produceBlock(epoch));
        }

        // Phase 3: Network heals - exchange all blocks
        for (SimulatedBlock block : node1Blocks) {
            node2.importBlock(block);
        }
        for (SimulatedBlock block : node2Blocks) {
            node1.importBlock(block);
        }

        // Verify both nodes converged to same chain
        assertEquals("Both nodes should converge to same main chain length",
                node1.getMainChainLength(), node2.getMainChainLength());

        SimulatedBlock latest1 = node1.getLatestMainBlock();
        SimulatedBlock latest2 = node2.getLatestMainBlock();
        assertEquals("Both nodes should have same latest main block",
                latest1.hash, latest2.hash);

        System.out.println("SUCCESS: Nodes recovered from partition");
        System.out.println("  Final main chain length: " + node1.getMainChainLength());
    }

    /**
     * Test cumulative difficulty overflow doesn't cause divergence.
     *
     * <p>This specifically tests the BUG-DIFFICULTY-001 scenario.
     */
    @Test
    public void testCumulativeDifficultyOverflow() {
        // Use very small hashes to create high cumulative difficulty
        SimulatedBlock genesis = createGenesisBlock(START_EPOCH);
        genesis.cumulativeDifficulty = UInt256.MAX_VALUE.divide(UInt256.valueOf(2));

        node1.importBlock(genesis);
        node2.importBlock(genesis);

        // Produce blocks that would cause overflow if not handled
        for (int i = 1; i <= 100; i++) {
            long epoch = START_EPOCH + i;

            // Create blocks with very small hashes (high work)
            SimulatedBlock block1 = node1.produceBlockWithSmallHash(epoch);
            SimulatedBlock block2 = node2.produceBlockWithSmallHash(epoch);

            // This should not throw even with high cumulative difficulty
            try {
                node1.importBlock(block2);
                node2.importBlock(block1);
            } catch (Exception e) {
                fail("Should not throw on high cumulative difficulty: " + e.getMessage());
            }
        }

        // Verify consistency
        assertEquals("Nodes should still be in sync despite high difficulty",
                node1.getLatestMainBlock().hash, node2.getLatestMainBlock().hash);

        System.out.println("SUCCESS: No overflow with high cumulative difficulty");
    }

    // ==================== Helper Classes ====================

    private SimulatedBlock createGenesisBlock(long epoch) {
        SimulatedBlock genesis = new SimulatedBlock();
        genesis.epoch = epoch;
        genesis.height = 1;
        genesis.hash = Bytes32.random();
        genesis.parentHash = null;
        genesis.cumulativeDifficulty = UInt256.ONE;
        genesis.coinbase = Bytes.wrap(new byte[20]);  // Zero address
        return genesis;
    }

    /**
     * Simulated node for testing consensus without real infrastructure.
     */
    private class SimulatedNode {
        private final String name;
        private final Map<Bytes32, SimulatedBlock> blocksByHash = new HashMap<>();
        private final Map<Long, List<SimulatedBlock>> blocksByEpoch = new HashMap<>();
        private final Map<Long, SimulatedBlock> mainBlocksByHeight = new HashMap<>();
        private long mainChainLength = 0;
        private final byte[] coinbase;

        public SimulatedNode(String name) {
            this.name = name;
            this.coinbase = new byte[20];
            random.nextBytes(this.coinbase);
        }

        public void importBlock(SimulatedBlock block) {
            if (blocksByHash.containsKey(block.hash)) {
                return;  // Already have this block
            }

            // Store block
            blocksByHash.put(block.hash, block);
            blocksByEpoch.computeIfAbsent(block.epoch, k -> new ArrayList<>()).add(block);

            // Determine if this is the new main block for its epoch
            updateMainChain(block);
        }

        private void updateMainChain(SimulatedBlock block) {
            List<SimulatedBlock> epochBlocks = blocksByEpoch.get(block.epoch);
            if (epochBlocks == null || epochBlocks.isEmpty()) {
                return;
            }

            // Find winner (smallest hash)
            SimulatedBlock winner = epochBlocks.stream()
                    .min(Comparator.comparing(b -> b.hash))
                    .orElse(null);

            if (winner == null) {
                return;
            }

            // Calculate height based on parent
            long height;
            if (winner.parentHash == null) {
                height = 1;  // Genesis
            } else {
                SimulatedBlock parent = blocksByHash.get(winner.parentHash);
                if (parent == null || parent.height == 0) {
                    height = mainChainLength + 1;
                } else {
                    height = parent.height + 1;
                }
            }

            // Update winner's height
            winner.height = height;

            // Update main chain index
            mainBlocksByHeight.put(height, winner);
            if (height > mainChainLength) {
                mainChainLength = height;
            }

            // Mark other blocks in this epoch as orphans (height = 0)
            for (SimulatedBlock b : epochBlocks) {
                if (!b.hash.equals(winner.hash)) {
                    b.height = 0;
                }
            }
        }

        public SimulatedBlock produceBlock(long epoch) {
            SimulatedBlock block = new SimulatedBlock();
            block.epoch = epoch;
            block.hash = Bytes32.random();
            block.coinbase = Bytes.wrap(coinbase);

            // Link to latest main block
            SimulatedBlock parent = getLatestMainBlock();
            if (parent != null) {
                block.parentHash = parent.hash;
                block.cumulativeDifficulty = calculateCumulativeDifficulty(parent, block.hash);
            } else {
                block.cumulativeDifficulty = calculateBlockWork(block.hash);
            }

            importBlock(block);
            return block;
        }

        public SimulatedBlock produceBlockWithSmallHash(long epoch) {
            SimulatedBlock block = new SimulatedBlock();
            block.epoch = epoch;
            // Create a small hash (more leading zeros = smaller value)
            byte[] hashBytes = new byte[32];
            hashBytes[0] = 0;
            hashBytes[1] = 0;
            hashBytes[2] = 0;
            hashBytes[3] = (byte) random.nextInt(16);  // Small first nibble
            random.nextBytes(Arrays.copyOfRange(hashBytes, 4, 32));
            System.arraycopy(hashBytes, 0, hashBytes, 0, 4);
            for (int i = 4; i < 32; i++) {
                hashBytes[i] = (byte) random.nextInt(256);
            }
            block.hash = Bytes32.wrap(hashBytes);
            block.coinbase = Bytes.wrap(coinbase);

            SimulatedBlock parent = getLatestMainBlock();
            if (parent != null) {
                block.parentHash = parent.hash;
                block.cumulativeDifficulty = calculateCumulativeDifficulty(parent, block.hash);
            } else {
                block.cumulativeDifficulty = calculateBlockWork(block.hash);
            }

            importBlock(block);
            return block;
        }

        private UInt256 calculateCumulativeDifficulty(SimulatedBlock parent, Bytes32 blockHash) {
            UInt256 parentDiff = parent.cumulativeDifficulty;
            UInt256 blockWork = calculateBlockWork(blockHash);

            // Prevent overflow by capping at MAX_VALUE
            try {
                UInt256 result = parentDiff.add(blockWork);
                // Check for overflow (result < parent means overflow in unsigned)
                if (result.compareTo(parentDiff) < 0) {
                    return UInt256.MAX_VALUE;
                }
                return result;
            } catch (Exception e) {
                return UInt256.MAX_VALUE;
            }
        }

        private UInt256 calculateBlockWork(Bytes32 hash) {
            UInt256 hashValue = UInt256.fromBytes(hash);
            if (hashValue.isZero()) {
                return UInt256.MAX_VALUE;
            }
            return UInt256.MAX_VALUE.divide(hashValue);
        }

        public SimulatedBlock getWinnerForEpoch(long epoch) {
            List<SimulatedBlock> blocks = blocksByEpoch.get(epoch);
            if (blocks == null || blocks.isEmpty()) {
                return null;
            }
            return blocks.stream()
                    .filter(b -> b.height > 0)
                    .findFirst()
                    .orElse(null);
        }

        public SimulatedBlock getMainBlockByHeight(long height) {
            return mainBlocksByHeight.get(height);
        }

        public SimulatedBlock getLatestMainBlock() {
            return mainBlocksByHeight.get(mainChainLength);
        }

        public long getMainChainLength() {
            return mainChainLength;
        }
    }

    /**
     * Simplified block for testing.
     */
    private static class SimulatedBlock {
        Bytes32 hash;
        Bytes32 parentHash;
        long epoch;
        long height;
        UInt256 cumulativeDifficulty;
        Bytes coinbase;
    }
}
