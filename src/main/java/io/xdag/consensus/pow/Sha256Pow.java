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

package io.xdag.consensus.pow;

import io.xdag.config.Config;
import io.xdag.config.RandomXConstants;
import io.xdag.crypto.hash.HashUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * SHA256 Proof of Work Implementation
 *
 * <p>Simple SHA256-based PoW algorithm for XDAG blocks before RandomX fork.
 * This is the original XDAG mining algorithm.
 *
 * <h2>Algorithm</h2>
 * <pre>
 * hash = SHA256(block_data)
 * Valid if: hash <= difficulty
 * </pre>
 *
 * <h2>Activation</h2>
 * <ul>
 *   <li>Active: Before RandomX fork epoch</li>
 *   <li>Inactive: After RandomX fork activation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * PowAlgorithm sha256 = new Sha256Pow(config);
 * sha256.start();
 *
 * byte[] blockData = block.toBytes();
 * HashContext context = HashContext.forMining(timestamp);
 * byte[] hash = sha256.calculateBlockHash(blockData, context);
 *
 * sha256.stop();
 * </pre>
 *
 * @since XDAGJ v0.8.1
 */
@Slf4j
public class Sha256Pow implements PowAlgorithm {

    private final Config config;
    private final long forkEpoch;
    private volatile boolean started = false;

    /**
     * Create SHA256 PoW algorithm
     *
     * @param config System configuration
     * @throws IllegalArgumentException if config is null
     */
    public Sha256Pow(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }

        this.config = config;

        // Determine fork epoch based on network type
        boolean isTestnet = !(config instanceof io.xdag.config.MainnetConfig);
        if (isTestnet) {
            this.forkEpoch = RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT / RandomXConstants.SEEDHASH_EPOCH_TESTNET_BLOCKS;
        } else {
            this.forkEpoch = RandomXConstants.RANDOMX_FORK_HEIGHT / RandomXConstants.SEEDHASH_EPOCH_BLOCKS;
        }

        log.info("Sha256Pow initialized: forkEpoch={}", forkEpoch);
    }

    // ========== Lifecycle ==========

    @Override
    public void start() {
        if (started) {
            log.warn("Sha256Pow already started");
            return;
        }

        started = true;
        log.info("Sha256Pow started (active before epoch {})", forkEpoch);
    }

    @Override
    public void stop() {
        if (!started) {
            log.warn("Sha256Pow not started");
            return;
        }

        started = false;
        log.info("Sha256Pow stopped");
    }

    @Override
    public boolean isReady() {
        return started;
    }

    // ========== PowAlgorithm Implementation ==========

    @Override
    public byte[] calculateBlockHash(byte[] data, HashContext context) {
        if (!started) {
            log.warn("Sha256Pow not started, hash calculation may fail");
            return null;
        }

        if (data == null) {
            log.warn("Block data is null");
            return null;
        }

        if (context == null) {
            log.warn("Hash context is null");
            return null;
        }

        // SHA256 hash calculation (double SHA256 for security)
        // hash = SHA256(SHA256(data))
        try {
            Bytes32 hash = HashUtils.sha256(HashUtils.sha256(Bytes.wrap(data)));
            return hash.toArray();
        } catch (Exception e) {
            log.error("Failed to calculate SHA256 hash", e);
            return null;
        }
    }

    @Override
    public Bytes32 calculatePoolHash(byte[] data, HashContext context) {
        if (!started) {
            log.warn("Sha256Pow not started, pool hash calculation may fail");
            return null;
        }

        if (data == null) {
            log.warn("Pool data is null");
            return null;
        }

        if (context == null) {
            log.warn("Hash context is null");
            return null;
        }

        // Pool hash: single SHA256
        try {
            return HashUtils.sha256(Bytes.wrap(data));
        } catch (Exception e) {
            log.error("Failed to calculate SHA256 pool hash", e);
            return null;
        }
    }

    @Override
    public boolean isActive(long epoch) {
        // SHA256 is active BEFORE the RandomX fork
        return epoch < forkEpoch;
    }

    @Override
    public String getName() {
        return "SHA256";
    }

    // ========== Status ==========

    /**
     * Get fork epoch
     *
     * @return RandomX fork epoch (SHA256 active before this)
     */
    public long getForkEpoch() {
        return forkEpoch;
    }

    /**
     * Check if started
     *
     * @return true if started
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Get diagnostic information
     *
     * @return diagnostic string
     */
    public String getDiagnostics() {
        return String.format(
            "Sha256Pow[started=%s, forkEpoch=%d, ready=%s]",
            started,
            forkEpoch,
            isReady()
        );
    }

    @Override
    public String toString() {
        return getDiagnostics();
    }
}
