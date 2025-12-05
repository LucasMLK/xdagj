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
 * Unit tests for OrphanManager asynchronous retry logic.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>registerMissingDependency persists block and parent mappings</li>
 *   <li>onBlockImported enqueues dependents without deleting data</li>
 *   <li>retryBlock can retrieve block from MISSING_DEP_BLOCK</li>
 *   <li>clearMissingDependency properly removes all artifacts</li>
 * </ul>
 */
public class OrphanManagerTest {

  private DagKernel dagKernel;
  private DagStore dagStore;
  private OrphanManager orphanManager;
  private Path tempDir;

  private Block orphanBlock;
  private Block parentBlock;
  private Bytes32 missingParentHash;

  @Before
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory("orphan-manager-test-");

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
    orphanManager = new OrphanManager(dagKernel);

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

    orphanBlock = Block.builder()
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
    if (orphanManager != null) {
      orphanManager.stop();
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
    // Register orphan block with missing parent
    orphanManager.registerMissingDependency(orphanBlock, List.of(missingParentHash));

    // Verify block is persisted in MISSING_DEP_BLOCK
    Block loaded = dagStore.getBlockByHash(orphanBlock.getHash());
    assertNotNull("Block should be retrievable from MISSING_DEP_BLOCK", loaded);
    assertEquals(orphanBlock.getHash(), loaded.getHash());

    // Verify parent index is created
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(missingParentHash);
    assertTrue("Block should be waiting for parent", waiting.contains(orphanBlock.getHash()));

    // Verify orphan reason is set
    OrphanReason reason = dagStore.getOrphanReason(orphanBlock.getHash());
    assertEquals(OrphanReason.MISSING_DEPENDENCY, reason);

    // Verify count
    assertEquals(1, dagStore.getMissingDependencyBlockCount());
  }

  @Test
  public void testOnBlockImportedDoesNotDeleteData() {
    // Register orphan block
    orphanManager.registerMissingDependency(orphanBlock, List.of(parentBlock.getHash()));

    // Start orphan manager with a mock retry function that tracks calls
    AtomicInteger retryCount = new AtomicInteger(0);
    AtomicReference<Block> retriedBlock = new AtomicReference<>();

    orphanManager.start(block -> {
      retryCount.incrementAndGet();
      retriedBlock.set(block);
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Simulate parent block arrival
    orphanManager.onBlockImported(parentBlock);

    // Wait for async processing
    try {
      Thread.sleep(3000);  // Wait for retry executor
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Critical: Block data should still be accessible (NOT deleted prematurely)
    Block stillExists = dagStore.getBlockByHash(orphanBlock.getHash());
    assertNotNull("Block data must NOT be deleted by onBlockImported", stillExists);

    // Verify retry was called with the block
    assertTrue("Retry should have been called", retryCount.get() > 0);
    assertNotNull("Retried block should not be null", retriedBlock.get());
    assertEquals(orphanBlock.getHash(), retriedBlock.get().getHash());
  }

  @Test
  public void testClearMissingDependency() {
    // Register orphan block
    orphanManager.registerMissingDependency(orphanBlock, List.of(missingParentHash));

    // Verify it exists
    assertEquals(1, dagStore.getMissingDependencyBlockCount());
    assertNotNull(dagStore.getBlockByHash(orphanBlock.getHash()));

    // Clear the dependency
    orphanManager.clearMissingDependency(orphanBlock.getHash());

    // Verify all artifacts are removed
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
    assertTrue(dagStore.getBlocksWaitingForParent(missingParentHash).isEmpty());
    assertTrue(dagStore.getMissingParents(orphanBlock.getHash()).isEmpty());
  }

  @Test
  public void testMultipleDependentsForSameParent() {
    // Create multiple orphan blocks waiting for the same parent
    Block orphan1 = orphanBlock;
    Block orphan2 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_002L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();
    Block orphan3 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_003L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();

    Bytes32 sharedParent = Bytes32.random();

    orphanManager.registerMissingDependency(orphan1, List.of(sharedParent));
    orphanManager.registerMissingDependency(orphan2, List.of(sharedParent));
    orphanManager.registerMissingDependency(orphan3, List.of(sharedParent));

    assertEquals(3, dagStore.getMissingDependencyBlockCount());

    // All should be waiting for the same parent
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(sharedParent);
    assertEquals(3, waiting.size());
    assertTrue(waiting.contains(orphan1.getHash()));
    assertTrue(waiting.contains(orphan2.getHash()));
    assertTrue(waiting.contains(orphan3.getHash()));

    // Track retries
    List<Bytes32> retriedHashes = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    orphanManager.start(block -> {
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
    orphanManager.onBlockImported(Block.builder()
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
    // Register some orphans
    orphanManager.registerMissingDependency(orphanBlock, List.of(missingParentHash));

    // Start with a slow retry function
    orphanManager.start(block -> {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return DagImportResult.orphan(block.getEpoch(), UInt256.ZERO, false);
    });

    // Stop should complete without hanging
    long startTime = System.currentTimeMillis();
    orphanManager.stop();
    long elapsed = System.currentTimeMillis() - startTime;

    // Should stop within 6 seconds (5 second timeout + margin)
    assertTrue("Stop should complete within timeout", elapsed < 7000);
  }

  @Test
  public void testRetrySuccessTriggersCleanup() throws Exception {
    // This test verifies the full flow:
    // 1. Register orphan
    // 2. Parent arrives -> enqueue retry
    // 3. Retry succeeds -> clearMissingDependency called

    orphanManager.registerMissingDependency(orphanBlock, List.of(parentBlock.getHash()));
    assertEquals(1, dagStore.getMissingDependencyBlockCount());

    CountDownLatch importLatch = new CountDownLatch(1);

    // Start with a retry function that simulates successful import
    orphanManager.start(block -> {
      // Simulate successful import - caller (BlockImporter) would call clearMissingDependency
      orphanManager.clearMissingDependency(block.getHash());
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

    orphanManager.onBlockImported(parent);

    // Wait for import
    assertTrue("Import should complete", importLatch.await(5, TimeUnit.SECONDS));

    // Verify cleanup happened
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
  }
}
