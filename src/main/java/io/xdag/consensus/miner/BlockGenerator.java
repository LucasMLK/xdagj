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

package io.xdag.consensus.miner;

import io.xdag.Wallet;
import io.xdag.consensus.RandomX;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * BlockGenerator - Generates candidate blocks for mining
 *
 * <p>This component is responsible for creating candidate blocks that will be mined.
 * It handles both RandomX and non-RandomX block generation.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Single Responsibility: Only generates blocks, doesn't validate or broadcast</li>
 *   <li>Alignment: Uses DagChain API instead of legacy Blockchain</li>
 *   <li>Immutability: Works with immutable Block objects</li>
 *   <li>Testability: Easy to mock dependencies</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * BlockGenerator generator = new BlockGenerator(dagChain, wallet, randomX);
 * Block candidate = generator.generateCandidate();
 * // Pass candidate to miner...
 * </pre>
 *
 * @since XDAGJ
 */
@Slf4j
public class BlockGenerator {

    private final DagChain dagChain;
    private final Wallet wallet;
    private final RandomX randomX;

    /**
     * Create a new BlockGenerator
     *
     * @param dagChain DagChain for block creation
     * @param wallet Wallet for coinbase key
     * @param randomX RandomX instance (can be null if not using RandomX)
     */
    public BlockGenerator(DagChain dagChain, Wallet wallet, RandomX randomX) {
        if (dagChain == null) {
            throw new IllegalArgumentException("DagChain cannot be null");
        }
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet cannot be null");
        }
        this.dagChain = dagChain;
        this.wallet = wallet;
        this.randomX = randomX;
    }

    /**
     * Generate a candidate block for mining
     *
     * <p>This method:
     * <ol>
     *   <li>Creates a candidate block via DagChain</li>
     *   <li>Sets initial nonce (random 12 bytes + wallet address 20 bytes)</li>
     *   <li>Returns immutable Block ready for mining</li>
     * </ol>
     *
     * <p>The block generation differs based on RandomX fork status:
     * <ul>
     *   <li>RandomX: Uses RandomX pre-hash calculation</li>
     *   <li>Non-RandomX: Uses SHA256 hash calculation</li>
     * </ul>
     *
     * @return Block candidate block with initial nonce
     * @throws IllegalStateException if wallet has no default key
     */
    public Block generateCandidate() {
        // Get coinbase key
        ECKeyPair coinbaseKey = wallet.getDefKey();
        if (coinbaseKey == null) {
            throw new IllegalStateException("Wallet has no default key for coinbase");
        }

        // Get current time
        long timestamp = XdagTime.getMainTime();

        // BUGFIX: Generate coinbase address (20 bytes, NOT 32 bytes)
        // AddressUtils.toBytesAddress() returns exactly 20 bytes (Ethereum-style address)
        // BlockHeader expects coinbase to be exactly 20 bytes (see BlockHeader.getSerializedSize())
        org.apache.tuweni.bytes.Bytes coinbase =
                io.xdag.crypto.keys.AddressUtils.toBytesAddress(coinbaseKey.getPublicKey());

        // Validation: Ensure coinbase is exactly 20 bytes
        if (coinbase.size() != 20) {
            throw new IllegalStateException(String.format(
                    "Wallet address must be 20 bytes, got %d bytes: %s",
                    coinbase.size(), coinbase.toHexString()));
        }

        // Set mining coinbase address for DagChain
        if (dagChain instanceof io.xdag.core.DagChainImpl) {
            ((io.xdag.core.DagChainImpl) dagChain).setMiningCoinbase(coinbase);
        }

        // Create candidate block via DagChain (API)
        Block candidate = dagChain.createCandidateBlock();

        // Generate initial nonce
        // Format: [12 bytes random] + [20 bytes wallet address]
        Bytes32 initialNonce = generateInitialNonce(coinbaseKey);

        // Set nonce (Block is immutable, returns new instance)
        candidate = candidate.withNonce(initialNonce);

        log.debug("Generated candidate block: hash={}, timestamp={}, randomX={}",
                candidate.getHash().toHexString(),
                timestamp,
                isRandomXFork(timestamp));

        return candidate;
    }

    /**
     * Generate initial nonce for candidate block
     *
     * <p>Nonce format: [12 bytes random] + [20 bytes wallet address]
     * This ensures that each node generates unique nonces.
     *
     * @param coinbaseKey Coinbase key for wallet address
     * @return Bytes32 initial nonce
     */
    private Bytes32 generateInitialNonce(ECKeyPair coinbaseKey) {
        byte[] randomBytes = CryptoProvider.nextBytes(12);
        byte[] walletAddress = AddressUtils.toBytesAddress(coinbaseKey).toArray();
        return Bytes32.wrap(BytesUtils.merge(randomBytes, walletAddress));
    }

    /**
     * Check if current time is in RandomX fork
     *
     * @param timestamp Current timestamp
     * @return true if RandomX fork is active
     */
    public boolean isRandomXFork(long timestamp) {
        if (randomX == null) {
            return false;
        }
        long epoch = XdagTime.getEpoch(timestamp);
        return randomX.isRandomxFork(epoch);
    }

    /**
     * Get the DagChain instance
     *
     * @return DagChain instance
     */
    public DagChain getDagChain() {
        return dagChain;
    }

    /**
     * Get the Wallet instance
     *
     * @return Wallet instance
     */
    public Wallet getWallet() {
        return wallet;
    }

    /**
     * Get the RandomX instance
     *
     * @return RandomX instance (can be null)
     */
    public RandomX getRandomX() {
        return randomX;
    }
}
