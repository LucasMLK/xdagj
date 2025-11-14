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

package io.xdag.rpc.service;

import io.xdag.core.Block;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * NodeMiningRpcService - RPC interface for mining pool integration
 *
 * <p><strong>Architecture Note</strong>:
 * This interface defines the boundary between the XDAG node and external pool servers.
 * Pool servers connect to the node via this RPC interface to fetch mining tasks and
 * submit mined blocks.
 *
 * <h2>Three-Layer Architecture</h2>
 * <pre>
 * xdagj (Node)
 *   └─> NodeMiningRpcService ← YOU ARE HERE
 *        ↓ JSON-RPC
 * xdagj-pool (Pool Server)
 *   └─> StratumService
 *        ↓ Stratum Protocol
 * xdagj-miner (Miner)
 *   └─> Nonce iteration loop
 * </pre>
 *
 * <h2>Interface Methods</h2>
 * <ul>
 *   <li>{@link #getCandidateBlock(String)} - Pool fetches candidate block to mine</li>
 *   <li>{@link #submitMinedBlock(Block, String)} - Pool submits best mined block</li>
 *   <li>{@link #getCurrentDifficultyTarget()} - Get current network difficulty</li>
 *   <li>{@link #getRandomXInfo()} - Get RandomX fork status and epoch info</li>
 * </ul>
 *
 * <h2>Usage Flow</h2>
 * <ol>
 *   <li>Pool server calls {@code getCandidateBlock(poolId)} every epoch (64 seconds)</li>
 *   <li>Pool distributes work to miners via Stratum protocol</li>
 *   <li>Miners find shares and submit to pool</li>
 *   <li>Pool tracks best share and calls {@code submitMinedBlock()} before epoch ends</li>
 *   <li>Node validates and imports block to blockchain</li>
 * </ol>
 *
 * @since XDAGJ v5.1
 * @see MiningRpcServiceImpl
 */
public interface NodeMiningRpcService {

    /**
     * Get current candidate block for mining
     *
     * <p>Pool servers call this method periodically (typically every 64 seconds at epoch start)
     * to fetch a fresh candidate block to mine.
     *
     * <p>The candidate block contains:
     * <ul>
     *   <li>Timestamp and epoch information</li>
     *   <li>Links to recent blocks (DAG structure)</li>
     *   <li>Coinbase transaction (reward address)</li>
     *   <li>Empty nonce field (to be filled by miners)</li>
     * </ul>
     *
     * <p>The node caches returned candidate blocks to validate submitted blocks later.
     *
     * @param poolId Unique identifier for the pool (for logging and authentication)
     * @return Candidate block ready for mining, or null if unable to generate
     */
    Block getCandidateBlock(String poolId);

    /**
     * Submit a mined block to the node
     *
     * <p>Pool servers call this method when they have found a valid block (best share
     * with nonce filled in). The node validates the block and attempts to import it
     * to the blockchain.
     *
     * <p>Validation checks include:
     * <ul>
     *   <li>Block is based on a known candidate block</li>
     *   <li>Nonce produces valid PoW hash</li>
     *   <li>Block timestamp is within epoch bounds</li>
     *   <li>Block links are valid</li>
     * </ul>
     *
     * @param block The mined block with nonce filled in
     * @param poolId Unique identifier for the pool submitting the block
     * @return Result indicating success or failure reason
     */
    BlockSubmitResult submitMinedBlock(Block block, String poolId);

    /**
     * Get current network difficulty target
     *
     * <p>Returns the current base difficulty target that blocks must meet.
     * This is used by pools to set share difficulty for miners.
     *
     * <p>In XDAG, lower hash values indicate higher difficulty (Bitcoin-style).
     * A valid block hash must be less than or equal to this target.
     *
     * @return Current difficulty target as UInt256
     */
    UInt256 getCurrentDifficultyTarget();

    /**
     * Get RandomX mining information
     *
     * <p>Returns information about the RandomX proof-of-work fork status.
     * Pools and miners use this to determine which hashing algorithm to use.
     *
     * <p>Information includes:
     * <ul>
     *   <li>Whether RandomX fork is active</li>
     *   <li>Current epoch number</li>
     *   <li>Fork activation epoch</li>
     *   <li>RandomX VM initialization status</li>
     * </ul>
     *
     * @return RandomX status information
     */
    RandomXInfo getRandomXInfo();
}
