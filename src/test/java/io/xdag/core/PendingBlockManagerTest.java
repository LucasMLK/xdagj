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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.config.DevnetConfig;
import io.xdag.store.DagStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for PendingBlockManager asynchronous retry logic.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>registerMissingDependency persists block and parent mappings</li>
 *   <li>onBlockImported enqueues dependents without deleting data</li>
 *   <li>retryBlock can retrieve block from MISSING_DEP_BLOCK</li>
 *   <li>clearMissingDependency properly removes all artifacts</li>
 * </ul>
 */
public class PendingBlockManagerTest {

  private DagKernel dagKernel;
  private DagStore dagStore;
  private PendingBlockManager pendingBlockManager;
  private Path tempDir;

  private Block pendingBlock;
  private Block parentBlock;
  private Bytes32 missingParentHash;

  @Before
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory("pending-block-manager-test-");

    // Create genesis file
    createTestGenesisFile(tempDir);

    DevnetConfig config = new DevnetConfig() {
      @Override
      public String getStoreDir() {
        return tempDir.toString();
      }

      @Override
      public String getRootDir() {
        return tempDir.toString();
      }
    };

    Wallet wallet = new Wallet(config);
    wallet.unlock("test-password");
    wallet.addAccountRandom();

    dagKernel = new DagKernel(config, wallet);
    dagKernel.setP2pEnabled(false);
    dagKernel.start();

    dagStore = dagKernel.getDagStore();
    pendingBlockManager = new PendingBlockManager(dagKernel);

    // Create test blocks
    missingParentHash = Bytes32.random();

    parentBlock = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_000L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();

    pendingBlock = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_001L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();
  }

  @After
  public void tearDown() {
    if (pendingBlockManager != null) {
      pendingBlockManager.stop();
    }

    if (dagKernel != null) {
      try {
        dagKernel.stop();
      } catch (Exception e) {
        // ignore
      }
    }

    if (tempDir != null && Files.exists(tempDir)) {
      try {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
              try {
                Files.delete(path);
              } catch (IOException e) {
                // ignore
              }
            });
      } catch (IOException e) {
        // ignore
      }
    }
  }

  private void createTestGenesisFile(Path directory) throws IOException {
    String genesisJson = "{\n" +
        "  \"networkId\": \"test\",\n" +
        "  \"chainId\": 999,\n" +
        "  \"epoch\": 1,\n" +
        "  \"initialDifficulty\": \"0x1000\",\n" +
        "  \"epochLength\": 64,\n" +
        "  \"extraData\": \"XDAGJ Test Genesis\",\n" +
        "  \"genesisCoinbase\": \"0x0000000000000000000000001111111111111111111111111111111111111111\",\n" +
        "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
        "  \"alloc\": {}\n" +
        "}";

    Path genesisFile = directory.resolve("genesis-devnet.json");
    Files.writeString(genesisFile, genesisJson);
  }

  @Test
  public void testRegisterMissingDependency() {
    // Register pending block with missing parent
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(missingParentHash));

    // Verify block is persisted in MISSING_DEP_BLOCK
    Block loaded = dagStore.getBlockByHash(pendingBlock.getHash());
    assertNotNull("Block should be retrievable from MISSING_DEP_BLOCK", loaded);
    assertEquals(pendingBlock.getHash(), loaded.getHash());

    // Verify parent index is created
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(missingParentHash);
    assertTrue("Block should be waiting for parent", waiting.contains(pendingBlock.getHash()));

    // Verify orphan reason is set
    OrphanReason reason = dagStore.getOrphanReason(pendingBlock.getHash());
    assertEquals(OrphanReason.MISSING_DEPENDENCY, reason);

    // Verify count
    assertEquals(1, dagStore.getMissingDependencyBlockCount());
  }

  @Test
  public void testOnBlockImportedDoesNotDeleteData() {
    // Register pending block
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(parentBlock.getHash()));

    // Start pending block manager with a mock retry function that tracks calls
    AtomicInteger retryCount = new AtomicInteger(0);
    AtomicReference<Block> retriedBlock = new AtomicReference<>();

    pendingBlockManager.start(block -> {
      retryCount.incrementAndGet();
      retriedBlock.set(block);
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Simulate parent block arrival
    pendingBlockManager.onBlockImported(parentBlock);

    // Wait for async processing
    try {
      Thread.sleep(3000);  // Wait for retry executor
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Critical: Block data should still be accessible (NOT deleted prematurely)
    Block stillExists = dagStore.getBlockByHash(pendingBlock.getHash());
    assertNotNull("Block data must NOT be deleted by onBlockImported", stillExists);

    // Verify retry was called with the block
    assertTrue("Retry should have been called", retryCount.get() > 0);
    assertNotNull("Retried block should not be null", retriedBlock.get());
    assertEquals(pendingBlock.getHash(), retriedBlock.get().getHash());
  }

  @Test
  public void testClearMissingDependency() {
    // Register pending block
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(missingParentHash));

    // Verify it exists
    assertEquals(1, dagStore.getMissingDependencyBlockCount());
    assertNotNull(dagStore.getBlockByHash(pendingBlock.getHash()));

    // Clear the dependency
    pendingBlockManager.clearMissingDependency(pendingBlock.getHash());

    // Verify all artifacts are removed
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
    assertTrue(dagStore.getBlocksWaitingForParent(missingParentHash).isEmpty());
    assertTrue(dagStore.getMissingParents(pendingBlock.getHash()).isEmpty());
  }

  @Test
  public void testMultipleDependentsForSameParent() {
    // Create multiple pending blocks waiting for the same parent
    Block pending1 = pendingBlock;
    Block pending2 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_002L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();
    Block pending3 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_003L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();

    Bytes32 sharedParent = Bytes32.random();

    pendingBlockManager.registerMissingDependency(pending1, List.of(sharedParent));
    pendingBlockManager.registerMissingDependency(pending2, List.of(sharedParent));
    pendingBlockManager.registerMissingDependency(pending3, List.of(sharedParent));

    assertEquals(3, dagStore.getMissingDependencyBlockCount());

    // All should be waiting for the same parent
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(sharedParent);
    assertEquals(3, waiting.size());
    assertTrue(waiting.contains(pending1.getHash()));
    assertTrue(waiting.contains(pending2.getHash()));
    assertTrue(waiting.contains(pending3.getHash()));

    // Track retries
    List<Bytes32> retriedHashes = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    pendingBlockManager.start(block -> {
      synchronized (retriedHashes) {
        retriedHashes.add(block.getHash());
      }
      latch.countDown();
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Simulate parent arrival
    Block parent = Block.builder()
        .header(BlockHeader.builder()
            .epoch(999_999L)
            .difficulty(UInt256.valueOf(1))
            .nonce(sharedParent)  // Use the hash as nonce to get the right hash
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();

    // Need to create a block with the exact hash
    // For this test, we'll use a different approach - trigger via the stored parent hash
    pendingBlockManager.onBlockImported(Block.builder()
        .header(BlockHeader.builder()
            .epoch(999_999L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.ZERO)
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .info(BlockInfo.builder()
            .hash(sharedParent)  // This is the key - the hash matches the parent we're waiting for
            .epoch(999_999L)
            .height(1)
            .difficulty(UInt256.ONE)
            .build())
        .build());

    // Wait for retries
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // All three should have been retried
    assertEquals(3, retriedHashes.size());
  }

  @Test
  public void testStopClearsQueue() {
    // Register some pending blocks
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(missingParentHash));

    // Start with a slow retry function
    pendingBlockManager.start(block -> {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Stop should complete without hanging
    long startTime = System.currentTimeMillis();
    pendingBlockManager.stop();
    long elapsed = System.currentTimeMillis() - startTime;

    // Should stop within 6 seconds (5 second timeout + margin)
    assertTrue("Stop should complete within timeout", elapsed < 7000);
  }

  @Test
  public void testRetrySuccessTriggersCleanup() throws Exception {
    // This test verifies the full flow:
    // 1. Register pending block
    // 2. Parent arrives -> enqueue retry
    // 3. Retry succeeds -> clearMissingDependency called

    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(parentBlock.getHash()));
    assertEquals(1, dagStore.getMissingDependencyBlockCount());

    CountDownLatch importLatch = new CountDownLatch(1);

    // Start with a retry function that simulates successful import
    pendingBlockManager.start(block -> {
      // Simulate successful import - caller (BlockImporter) would call clearMissingDependency
      pendingBlockManager.clearMissingDependency(block.getHash());
      importLatch.countDown();
      return DagImportResult.mainBlock(block.getEpoch(), 1, UInt256.ONE, true);
    });

    // Trigger retry via parent arrival
    Block parent = Block.builder()
        .header(BlockHeader.builder()
            .epoch(999_999L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.ZERO)
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .info(BlockInfo.builder()
            .hash(parentBlock.getHash())
            .epoch(999_999L)
            .height(1)
            .difficulty(UInt256.ONE)
            .build())
        .build();

    pendingBlockManager.onBlockImported(parent);

    // Wait for import
    assertTrue("Import should complete", importLatch.await(5, TimeUnit.SECONDS));

    // Verify cleanup happened
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
  }

  // ==================== BUG-SYNC-009: Race Condition Tests ====================

  /**
   * Test: BUG-SYNC-009 - Race condition where parent is already available when registering.
   *
   * <p>Scenario:
   * 1. Block A needs parent P
   * 2. P is imported before A finishes registerMissingDependency
   * 3. onBlockImported(P) fires but A is not yet registered
   * 4. A's registration completes but retry callback is never triggered
   *
   * <p>Fix: registerMissingDependency checks if parents exist after registration
   * and enqueues for immediate retry if they do.
   */
  @Test
  public void testRaceCondition_ParentAlreadyImportedDuringRegistration() throws Exception {
    // Step 1: Save parent block DIRECTLY to store (simulating the race condition where
    // parent was imported between BlockImporter detecting MISSING_DEPENDENCY and
    // PendingBlockManager.registerMissingDependency being called)
    Block parentWithInfo = parentBlock.toBuilder()
        .info(BlockInfo.builder()
            .hash(parentBlock.getHash())
            .epoch(parentBlock.getEpoch())
            .height(1)
            .difficulty(UInt256.ONE)
            .build())
        .build();
    dagStore.saveBlock(parentWithInfo);

    // Verify parent is now in the store
    assertNotNull("Parent should be saved", dagStore.getBlockByHash(parentBlock.getHash()));

    // Step 2: Now register a pending block that needs the already-imported parent
    // In the race condition scenario, this would happen because:
    // - Thread A detected parent was missing
    // - Thread B imported the parent
    // - Thread A continued with registration
    CountDownLatch retryLatch = new CountDownLatch(1);
    AtomicReference<Block> retriedBlock = new AtomicReference<>();

    pendingBlockManager.start(block -> {
      retriedBlock.set(block);
      retryLatch.countDown();
      // Note: clearMissingDependency would be called by BlockImporter after successful import
      // Here we just verify retry is triggered when parent exists
      return DagImportResult.mainBlock(block.getEpoch(), 1, UInt256.ONE, true);
    });

    // Register pending block - parent is ALREADY in the store
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(parentBlock.getHash()));

    // The block should be enqueued for retry immediately because parent exists
    // (This is the BUG-SYNC-009 fix)
    assertTrue("Block should be retried even though parent was already imported",
        retryLatch.await(5, TimeUnit.SECONDS));

    assertNotNull("Retried block should not be null", retriedBlock.get());
    assertEquals(pendingBlock.getHash(), retriedBlock.get().getHash());

    // Cleanup after retry (normally done by BlockImporter)
    pendingBlockManager.clearMissingDependency(retriedBlock.get().getHash());

    // Verify cleanup
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
  }

  /**
   * Test: BUG-SYNC-009 - Atomic write ensures index is visible when parent is imported.
   *
   * <p>This test verifies that the WriteBatch fix in saveMissingDependencyBlock ensures
   * all writes (block data + parent index) are visible atomically.
   *
   * <p>Before the fix, there was a window where:
   * 1. Block data was written
   * 2. onBlockImported queried the index
   * 3. Index entry was written (too late!)
   */
  @Test
  public void testAtomicWrite_IndexVisibleWhenParentImported() throws Exception {
    // Create a parent block that we'll use to trigger onBlockImported
    Bytes32 parentHash = Bytes32.random();

    // Track retry calls
    CountDownLatch retryLatch = new CountDownLatch(1);

    pendingBlockManager.start(block -> {
      retryLatch.countDown();
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Register pending block with missing parent
    pendingBlockManager.registerMissingDependency(pendingBlock, List.of(parentHash));

    // Immediately verify index is visible (atomic write means both should be visible)
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(parentHash);
    assertTrue("Index should be visible immediately after registration (atomic write)",
        waiting.contains(pendingBlock.getHash()));

    // Also verify block data is visible
    Block loaded = dagStore.getBlockByHash(pendingBlock.getHash());
    assertNotNull("Block data should be visible immediately after registration",
        loaded);

    // Now simulate parent arrival - this should find the pending block
    Block parent = Block.builder()
        .header(BlockHeader.builder()
            .epoch(999_999L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.ZERO)
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .info(BlockInfo.builder()
            .hash(parentHash)
            .epoch(999_999L)
            .height(1)
            .difficulty(UInt256.ONE)
            .build())
        .build();

    pendingBlockManager.onBlockImported(parent);

    // Retry should be triggered
    assertTrue("Block should be retried when parent arrives",
        retryLatch.await(5, TimeUnit.SECONDS));
  }

  /**
   * Test: BUG-SYNC-009 - Concurrent parent import and pending registration.
   *
   * <p>This test creates actual concurrent threads to simulate the race condition
   * and verify the fix works under concurrent access.
   */
  @Test
  public void testConcurrent_ParentImportAndPendingRegistration() throws Exception {
    // Use a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);
    AtomicInteger retryCount = new AtomicInteger(0);

    // Create a parent hash
    Bytes32 parentHash = Bytes32.random();

    pendingBlockManager.start(block -> {
      retryCount.incrementAndGet();
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Thread 1: Register pending block
    Thread registerThread = new Thread(() -> {
      try {
        startLatch.await();
        pendingBlockManager.registerMissingDependency(pendingBlock, List.of(parentHash));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        doneLatch.countDown();
      }
    });

    // Thread 2: Simulate parent arrival
    Thread importThread = new Thread(() -> {
      try {
        startLatch.await();
        Block parent = Block.builder()
            .header(BlockHeader.builder()
                .epoch(999_999L)
                .difficulty(UInt256.valueOf(1))
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build())
            .links(List.of())
            .info(BlockInfo.builder()
                .hash(parentHash)
                .epoch(999_999L)
                .height(1)
                .difficulty(UInt256.ONE)
                .build())
            .build();
        pendingBlockManager.onBlockImported(parent);
      } catch (Exception e) {
        // ignore
      } finally {
        doneLatch.countDown();
      }
    });

    registerThread.start();
    importThread.start();

    // Start both threads simultaneously
    startLatch.countDown();

    // Wait for both to complete
    assertTrue("Both threads should complete", doneLatch.await(10, TimeUnit.SECONDS));

    // Wait for async retry processing
    Thread.sleep(3000);

    // The pending block should be retried regardless of which thread won the race
    // Either:
    // 1. Registration completed first -> onBlockImported found the index -> retry triggered
    // 2. Parent import completed first -> registration check found parent exists -> retry triggered
    assertTrue("Block should be retried at least once despite race condition",
        retryCount.get() >= 1);
  }
}
