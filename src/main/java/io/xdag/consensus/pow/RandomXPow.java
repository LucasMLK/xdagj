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
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagchainListener;
import io.xdag.crypto.randomx.RandomXFlag;
import io.xdag.crypto.randomx.RandomXUtils;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * RandomX Proof of Work Implementation
 *
 * <p>Professional implementation of the RandomX mining algorithm with:
 * <ul>
 *   <li><strong>Event-Driven Seed Management</strong>: Automatically updates seeds on block events</li>
 *   <li><strong>Dual-Buffer Architecture</strong>: Seamless seed transitions without mining interruption</li>
 *   <li><strong>Thread-Safe Operations</strong>: Concurrent hash calculations supported</li>
 *   <li><strong>Clean API</strong>: Implements {@link PowAlgorithm} interface</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 *          RandomXPow (Facade + Event Handler)
 *                     │
 *       ┌─────────────┼─────────────┐
 *       │                           │
 * RandomXSeedManager        RandomXHashService
 * (Seed & Epochs)          (Hash Calculation)
 *       │                           │
 *   [Memory Slots]             [Templates]
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 * 1. new RandomXPow(config, dagChain)
 * 2. start() → Registers as blockchain listener
 * 3. [Automatic seed updates on block events]
 * 4. [Mining operations - hash calculations]
 * 5. stop() → Unregisters listener, cleans up
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All public methods are thread-safe. Multiple threads can call
 * {@link #calculateBlockHash} and {@link #calculatePoolHash} concurrently.
 *
 * @since XDAGJ v0.8.1
 */
@Slf4j
public class RandomXPow implements PowAlgorithm, DagchainListener {

  // ========== Dependencies (Injected) ==========

  private final Config config;
  private final DagChain dagChain;

  // ========== Internal Services ==========

  @Getter
  private final RandomXSeedManager seedManager;

  private final RandomXHashService hashService;

  // ========== State ==========

  private volatile boolean started = false;

  // ========== Constructor ==========

  /**
   * Create RandomX PoW with dependency injection.
   *
   * @param config   System configuration (for network type and RandomX flags)
   * @param dagChain Blockchain (for seed derivation and event registration)
   * @throws IllegalArgumentException if config or dagChain is null
   */
  public RandomXPow(Config config, DagChain dagChain) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (dagChain == null) {
      throw new IllegalArgumentException("DagChain cannot be null");
    }

    this.config = config;
    this.dagChain = dagChain;

    // Initialize RandomX flags
    Set<RandomXFlag> flags = RandomXUtils.getRecommendedFlags();
    if (config.getRandomxSpec().getRandomxFlag()) {
      flags.add(RandomXFlag.LARGE_PAGES);
      flags.add(RandomXFlag.FULL_MEM);
      log.info("RandomX large pages and full memory enabled");
    }

    // Create internal services
    this.seedManager = new RandomXSeedManager(config, flags);
    this.hashService = new RandomXHashService(seedManager);

    log.info("RandomXPow created with flags: {}", flags);
  }

  // ========== Lifecycle ==========

  @Override
  public void start() {
    if (started) {
      throw new IllegalStateException("RandomXPow already started");
    }

    log.info("Starting RandomXPow...");

    // Initialize seed manager
    seedManager.setDagChain(dagChain);
    seedManager.initialize();

    // Register as blockchain listener for automatic seed updates
    dagChain.addListener(this);

    started = true;

    log.info("RandomXPow started successfully");
  }

  @Override
  public void stop() {
    if (!started) {
      log.warn("RandomXPow not started, nothing to stop");
      return;
    }

    log.info("Stopping RandomXPow...");

    // Unregister blockchain listener
    dagChain.removeListener(this);

    // Cleanup resources
    seedManager.cleanup();

    started = false;

    log.info("RandomXPow stopped");
  }

  // ========== PowAlgorithm Implementation ==========

  @Override
  public byte[] calculateBlockHash(byte[] data, HashContext context) {
    if (!started) {
      throw new IllegalStateException("RandomXPow not started");
    }
    if (data == null) {
      throw new IllegalArgumentException("Data cannot be null");
    }
    if (context == null) {
      throw new IllegalArgumentException("HashContext cannot be null");
    }

    return hashService.calculateBlockHash(data, context.getTimestamp());
  }

  @Override
  public Bytes32 calculatePoolHash(byte[] data, HashContext context) {
    if (!started) {
      throw new IllegalStateException("RandomXPow not started");
    }
    if (data == null) {
      throw new IllegalArgumentException("Data cannot be null");
    }
    if (context == null) {
      throw new IllegalArgumentException("HashContext cannot be null");
    }

    return hashService.calculatePoolHash(Bytes.wrap(data), context.getTimestamp());
  }

  @Override
  public boolean isActive(long epoch) {
    if (!started) {
      return false;
    }
    return seedManager.isAfterFork(epoch);
  }

  @Override
  public boolean isReady() {
    return started && hashService.isReady();
  }

  @Override
  public String getName() {
    return "RandomX";
  }

  // ========== DagchainListener Implementation ==========

  /**
   * Automatically update seed when blocks are connected.
   *
   * <p>This method is called by the DAG chain when a block is added to
   * the main chain. It triggers seed manager to check for epoch boundaries and update seeds
   * accordingly.
   *
   * <p><strong>Thread Safety:</strong> This method may be called from
   * different threads. The seed manager handles synchronization internally.
   *
   * @param block Connected block
   */
  @Override
  public void onBlockConnected(Block block) {
    if (!started) {
      log.warn("Received block connected event but RandomXPow not started");
      return;
    }

    try {
      seedManager.updateSeedForBlock(block);

      // Log at epoch boundaries
      long height = block.getInfo().getHeight();
      long epochMask = seedManager.getSeedEpochBlocks() - 1;
      if ((height & epochMask) == 0) {
        log.info("Seed updated at epoch boundary: height={}, epoch={}",
            height, seedManager.getCurrentEpochIndex());
      }

    } catch (Exception e) {
      log.error("Failed to update seed for block at height " + block.getInfo().getHeight(), e);
    }
  }

  /**
   * Revert seed when blocks are disconnected (chain reorganization).
   *
   * <p>This method is called during chain reorganizations when blocks
   * are removed from the main chain. It reverts seed changes to maintain consistency with the
   * current chain state.
   *
   * @param block Disconnected block
   */
  @Override
  public void onBlockDisconnected(Block block) {
    if (!started) {
      log.warn("Received block disconnected event but RandomXPow not started");
      return;
    }

    try {
      seedManager.revertSeedForBlock(block);

      log.debug("Seed reverted for block at height {}", block.getInfo().getHeight());

    } catch (Exception e) {
      log.error("Failed to revert seed for block at height " + block.getInfo().getHeight(), e);
    }
  }

  // ========== Diagnostic Methods ==========

  @Override
  public String toString() {
    return String.format("RandomXPow[started=%s, ready=%s, epoch=%d]",
        started, isReady(), seedManager.getCurrentEpochIndex());
  }
}
