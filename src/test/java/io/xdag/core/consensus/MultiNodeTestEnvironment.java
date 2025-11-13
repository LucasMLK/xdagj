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

package io.xdag.core.consensus;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Multi-node test environment for consensus testing
 *
 * <p>Provides infrastructure for:
 * <ul>
 *   <li>Creating multiple independent nodes</li>
 *   <li>Simulating P2P connections between nodes</li>
 *   <li>Network partition and recovery</li>
 *   <li>Consensus verification</li>
 * </ul>
 *
 * @since Phase 12.5+ Consensus Testing
 */
@Slf4j
public class MultiNodeTestEnvironment implements AutoCloseable {

    // Node registry
    private final Map<String, TestNode> nodes = new ConcurrentHashMap<>();

    // Network connections (bidirectional)
    private final Map<String, Set<String>> connections = new ConcurrentHashMap<>();

    // Test configuration
    private final Path testBaseDir;
    private final long genesisTimestamp;
    private final Bytes32 deterministicGenesisCoinbase;

    /**
     * Internal test node representation
     */
    @Slf4j
    public static class TestNode {
        private final String nodeId;
        private final DagKernel dagKernel;
        private final DagChainImpl dagChain;
        private final Config config;
        private final Wallet wallet;
        private final Path nodeDir;

        public TestNode(String nodeId, DagKernel dagKernel, Config config, Wallet wallet, Path nodeDir) {
            this.nodeId = nodeId;
            this.dagKernel = dagKernel;
            this.dagChain = (DagChainImpl) dagKernel.getDagChain();
            this.config = config;
            this.wallet = wallet;
            this.nodeDir = nodeDir;
        }

        public String getNodeId() {
            return nodeId;
        }

        public DagKernel getDagKernel() {
            return dagKernel;
        }

        public DagChainImpl getDagChain() {
            return dagChain;
        }

        public Config getConfig() {
            return config;
        }

        public Wallet getWallet() {
            return wallet;
        }

        public Path getNodeDir() {
            return nodeDir;
        }

        public void shutdown() {
            try {
                dagKernel.stop();
                log.info("Node {} shut down successfully", nodeId);
            } catch (Exception e) {
                log.error("Error shutting down node {}: {}", nodeId, e.getMessage(), e);
            }
        }
    }

    /**
     * Create multi-node test environment
     */
    public MultiNodeTestEnvironment() throws IOException {
        this.testBaseDir = Files.createTempDirectory("multi-node-consensus-test-");
        this.genesisTimestamp = 1516406400L; // Fixed genesis timestamp for all nodes
        // Use deterministic coinbase for reproducible genesis blocks
        this.deterministicGenesisCoinbase = Bytes32.fromHexString(
                "0x1111111111111111111111111111111111111111111111111111111111111111");

        log.info("Created multi-node test environment at: {}", testBaseDir);
        log.info("Genesis timestamp: {}", genesisTimestamp);
        log.info("Genesis coinbase: {}", deterministicGenesisCoinbase.toHexString());
    }

    /**
     * Create a new node with specified ID
     *
     * @param nodeId unique node identifier
     * @return TestNode instance
     */
    public TestNode createNode(String nodeId) throws IOException {
        if (nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("Node with ID '" + nodeId + "' already exists");
        }

        // Create node directory
        Path nodeDir = testBaseDir.resolve("node-" + nodeId);
        Files.createDirectories(nodeDir);

        // Create genesis.json
        createGenesisFile(nodeDir);

        // Create config
        Config config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return nodeDir.toString();
            }

            @Override
            public String getRootDir() {
                return nodeDir.toString();
            }

            @Override
            public long getXdagEra() {
                return genesisTimestamp;
            }
        };

        // Create wallet
        Wallet wallet = new Wallet(config);
        wallet.unlock("test-password-" + nodeId);
        wallet.addAccountRandom();

        // Create and start DagKernel
        DagKernel dagKernel = new DagKernel(config, wallet);
        dagKernel.start();

        // Wrap in TestNode
        TestNode testNode = new TestNode(nodeId, dagKernel, config, wallet, nodeDir);
        nodes.put(nodeId, testNode);

        // Initialize empty connection set
        connections.put(nodeId, ConcurrentHashMap.newKeySet());

        log.info("Created node '{}' at {}", nodeId, nodeDir);
        return testNode;
    }

    /**
     * Get node by ID
     */
    public TestNode getNode(String nodeId) {
        TestNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node '" + nodeId + "' not found");
        }
        return node;
    }

    /**
     * Get all nodes
     */
    public Collection<TestNode> getAllNodes() {
        return nodes.values();
    }

    /**
     * Connect two nodes (bidirectional)
     *
     * <p>In this test environment, "connection" means blocks can be exchanged.
     * We simulate P2P by manually transferring blocks between nodes.
     *
     * @param nodeId1 first node ID
     * @param nodeId2 second node ID
     */
    public void connectNodes(String nodeId1, String nodeId2) {
        if (!nodes.containsKey(nodeId1) || !nodes.containsKey(nodeId2)) {
            throw new IllegalArgumentException("Both nodes must exist");
        }

        connections.get(nodeId1).add(nodeId2);
        connections.get(nodeId2).add(nodeId1);

        log.info("Connected nodes: {} ↔ {}", nodeId1, nodeId2);
    }

    /**
     * Disconnect two nodes (bidirectional)
     *
     * @param nodeId1 first node ID
     * @param nodeId2 second node ID
     */
    public void disconnectNodes(String nodeId1, String nodeId2) {
        connections.get(nodeId1).remove(nodeId2);
        connections.get(nodeId2).remove(nodeId1);

        log.info("Disconnected nodes: {} ✗ {}", nodeId1, nodeId2);
    }

    /**
     * Broadcast a block from source node to all connected nodes
     *
     * @param sourceNodeId source node that mined the block
     * @param block block to broadcast
     * @return map of nodeId → import result
     */
    public Map<String, DagImportResult> broadcastBlock(String sourceNodeId, Block block) {
        TestNode sourceNode = getNode(sourceNodeId);
        Set<String> connectedNodes = connections.get(sourceNodeId);

        Map<String, DagImportResult> results = new HashMap<>();

        log.debug("Broadcasting block {} from node '{}'", block.getHash().toHexString(), sourceNodeId);

        for (String targetNodeId : connectedNodes) {
            TestNode targetNode = getNode(targetNodeId);
            DagImportResult result = targetNode.getDagChain().tryToConnect(block);
            results.put(targetNodeId, result);

            log.debug("  → Node '{}': {}", targetNodeId, result.getStatus());
        }

        return results;
    }

    /**
     * Synchronize all blocks between connected nodes
     *
     * <p>Simulates full P2P synchronization. Each node shares all its blocks
     * with directly connected peers.
     *
     * @param maxRounds maximum sync rounds (prevents infinite loops)
     * @return true if sync converged, false if maxRounds reached
     */
    public boolean synchronizeNetwork(int maxRounds) {
        log.info("Starting network synchronization (max rounds: {})", maxRounds);

        for (int round = 0; round < maxRounds; round++) {
            boolean anyChanges = false;

            // For each node, share its blocks with connected peers
            for (TestNode sourceNode : nodes.values()) {
                String sourceId = sourceNode.getNodeId();
                Set<String> peers = connections.get(sourceId);

                if (peers.isEmpty()) {
                    continue;
                }

                // Get all blocks from source node
                List<Block> sourceBlocks = sourceNode.getDagChain().listMainBlocks(10000);

                // Send each block to connected peers
                for (String peerId : peers) {
                    TestNode peerNode = getNode(peerId);

                    for (Block block : sourceBlocks) {
                        // Check if peer already has this block
                        if (peerNode.getDagKernel().getDagStore().hasBlock(block.getHash())) {
                            continue;
                        }

                        // Import block to peer
                        DagImportResult result = peerNode.getDagChain().tryToConnect(block);

                        if (result.getStatus() != DagImportResult.ImportStatus.DUPLICATE) {
                            anyChanges = true;
                            log.debug("Round {}: {} → {}: {} ({})",
                                    round, sourceId, peerId,
                                    block.getHash().toHexString().substring(0, 16),
                                    result.getStatus());
                        }
                    }
                }
            }

            if (!anyChanges) {
                log.info("Network converged after {} rounds", round + 1);
                return true;
            }
        }

        log.warn("Network did not converge after {} rounds", maxRounds);
        return false;
    }

    /**
     * Wait for network to reach consensus (with timeout)
     *
     * @param timeoutSeconds maximum wait time
     * @return true if consensus reached, false if timeout
     */
    public boolean waitForConsensus(int timeoutSeconds) throws InterruptedException {
        log.info("Waiting for consensus (timeout: {}s)", timeoutSeconds);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (verifyConsensus().isConsensusReached()) {
                log.info("Consensus reached in {}ms",
                        System.currentTimeMillis() - startTime);
                return true;
            }

            Thread.sleep(100); // Check every 100ms
        }

        log.warn("Consensus not reached after {}s", timeoutSeconds);
        return false;
    }

    /**
     * Verify if all nodes have reached consensus
     *
     * @return ConsensusReport with detailed verification results
     */
    public ConsensusReport verifyConsensus() {
        if (nodes.isEmpty()) {
            return ConsensusReport.empty();
        }

        ConsensusReport report = new ConsensusReport();

        // Get stats from all nodes
        Map<String, ChainStats> allStats = new HashMap<>();
        for (TestNode node : nodes.values()) {
            allStats.put(node.getNodeId(), node.getDagChain().getChainStats());
        }

        // 1. Check genesis blocks
        Set<Bytes32> genesisHashes = new HashSet<>();
        for (TestNode node : nodes.values()) {
            Block genesis = node.getDagChain().getMainBlockByHeight(1);
            if (genesis != null) {
                genesisHashes.add(genesis.getHash());
            }
        }
        report.setGenesisConsensus(genesisHashes.size() <= 1);

        // 2. Check cumulative difficulty
        Set<UInt256> maxDifficulties = allStats.values().stream()
                .map(ChainStats::getMaxDifficulty)
                .collect(Collectors.toSet());
        report.setDifficultyConsensus(maxDifficulties.size() == 1);

        // 3. Check main chain length
        Set<Long> mainBlockCounts = allStats.values().stream()
                .map(ChainStats::getMainBlockCount)
                .collect(Collectors.toSet());
        report.setChainLengthConsensus(mainBlockCounts.size() == 1);

        // 4. Check block hashes at each height
        if (report.isChainLengthConsensus()) {
            long chainLength = mainBlockCounts.iterator().next();
            boolean allPositionsMatch = true;

            for (long height = 1; height <= chainLength; height++) {
                Set<Bytes32> hashesAtPosition = new HashSet<>();

                for (TestNode node : nodes.values()) {
                    Block block = node.getDagChain().getMainBlockByHeight(height);
                    if (block != null) {
                        hashesAtPosition.add(block.getHash());
                    }
                }

                if (hashesAtPosition.size() > 1) {
                    allPositionsMatch = false;
                    report.addMismatch(height, hashesAtPosition);
                }
            }

            report.setBlockSequenceConsensus(allPositionsMatch);
        }

        // Overall consensus
        report.setConsensusReached(
                report.isGenesisConsensus() &&
                report.isDifficultyConsensus() &&
                report.isChainLengthConsensus() &&
                report.isBlockSequenceConsensus()
        );

        return report;
    }

    /**
     * Create genesis.json file for node
     */
    private void createGenesisFile(Path nodeDir) throws IOException {
        String genesisJson = String.format("{\n" +
                "  \"networkId\": \"consensus-test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"timestamp\": %d,\n" +
                "  \"initialDifficulty\": \"0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF\",\n" +
                "  \"genesisCoinbase\": \"%s\",\n" +
                "  \"epochLength\": 64,\n" +
                "  \"extraData\": \"XDAG Consensus Test Genesis\",\n" +
                "  \"alloc\": {},\n" +
                "  \"snapshot\": {\n" +
                "    \"enabled\": false,\n" +
                "    \"height\": 0,\n" +
                "    \"hash\": \"0x0000000000000000000000000000000000000000000000000000000000000000\",\n" +
                "    \"timestamp\": 0,\n" +
                "    \"dataFile\": \"\",\n" +
                "    \"verify\": false,\n" +
                "    \"format\": \"v1\",\n" +
                "    \"expectedAccounts\": 0,\n" +
                "    \"expectedBlocks\": 0\n" +
                "  }\n" +
                "}", genesisTimestamp, deterministicGenesisCoinbase.toHexString());

        Path genesisFile = nodeDir.resolve("genesis.json");
        Files.writeString(genesisFile, genesisJson);
    }

    @Override
    public void close() {
        log.info("Shutting down multi-node test environment");

        // Shutdown all nodes
        for (TestNode node : nodes.values()) {
            node.shutdown();
        }

        // Clean up test directory
        try {
            Files.walk(testBaseDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
            log.info("Cleaned up test directory: {}", testBaseDir);
        } catch (IOException e) {
            log.error("Error cleaning up test directory: {}", e.getMessage());
        }
    }

    /**
     * Consensus verification report
     */
    public static class ConsensusReport {
        private boolean consensusReached;
        private boolean genesisConsensus;
        private boolean difficultyConsensus;
        private boolean chainLengthConsensus;
        private boolean blockSequenceConsensus;
        private final Map<Long, Set<Bytes32>> mismatchedHeights = new HashMap<>();

        public static ConsensusReport empty() {
            ConsensusReport report = new ConsensusReport();
            report.setConsensusReached(true);
            report.setGenesisConsensus(true);
            report.setDifficultyConsensus(true);
            report.setChainLengthConsensus(true);
            report.setBlockSequenceConsensus(true);
            return report;
        }

        public boolean isConsensusReached() {
            return consensusReached;
        }

        public void setConsensusReached(boolean consensusReached) {
            this.consensusReached = consensusReached;
        }

        public boolean isGenesisConsensus() {
            return genesisConsensus;
        }

        public void setGenesisConsensus(boolean genesisConsensus) {
            this.genesisConsensus = genesisConsensus;
        }

        public boolean isDifficultyConsensus() {
            return difficultyConsensus;
        }

        public void setDifficultyConsensus(boolean difficultyConsensus) {
            this.difficultyConsensus = difficultyConsensus;
        }

        public boolean isChainLengthConsensus() {
            return chainLengthConsensus;
        }

        public void setChainLengthConsensus(boolean chainLengthConsensus) {
            this.chainLengthConsensus = chainLengthConsensus;
        }

        public boolean isBlockSequenceConsensus() {
            return blockSequenceConsensus;
        }

        public void setBlockSequenceConsensus(boolean blockSequenceConsensus) {
            this.blockSequenceConsensus = blockSequenceConsensus;
        }

        public void addMismatch(long height, Set<Bytes32> hashes) {
            mismatchedHeights.put(height, hashes);
        }

        public Map<Long, Set<Bytes32>> getMismatchedPositions() {
            return mismatchedHeights;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("========== Consensus Report ==========\n");
            sb.append("Overall Consensus: ").append(consensusReached ? "✓ REACHED" : "✗ FAILED").append("\n");
            sb.append("  Genesis Consensus: ").append(genesisConsensus ? "✓" : "✗").append("\n");
            sb.append("  Difficulty Consensus: ").append(difficultyConsensus ? "✓" : "✗").append("\n");
            sb.append("  Chain Length Consensus: ").append(chainLengthConsensus ? "✓" : "✗").append("\n");
            sb.append("  Block Sequence Consensus: ").append(blockSequenceConsensus ? "✓" : "✗").append("\n");

            if (!mismatchedHeights.isEmpty()) {
                sb.append("\nMismatched Positions:\n");
                for (Map.Entry<Long, Set<Bytes32>> entry : mismatchedHeights.entrySet()) {
                    sb.append("  Position ").append(entry.getKey()).append(": ");
                    sb.append(entry.getValue().size()).append(" different hashes\n");
                    for (Bytes32 hash : entry.getValue()) {
                        sb.append("    - ").append(hash.toHexString().substring(0, 16)).append("...\n");
                    }
                }
            }

            sb.append("=====================================");
            return sb.toString();
        }
    }
}
