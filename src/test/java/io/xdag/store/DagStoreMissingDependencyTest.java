/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to do so, subject to the following conditions:
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

package io.xdag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.store.rocksdb.impl.DagStoreImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DagStoreMissingDependencyTest {

  private DagStore dagStore;
  private Path tempDir;
  private Block pendingBlock;
  private Bytes32 missingParent;

  @Before
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory("dagstore-missing-test-");

    DevnetConfig config = new DevnetConfig() {
      @Override
      public String getStoreDir() {
        return tempDir.toString();
      }
    };

    dagStore = new DagStoreImpl(config);
    dagStore.start();

    BlockHeader header = BlockHeader.builder()
        .epoch(1_000_000L)
        .difficulty(UInt256.valueOf(1))
        .nonce(Bytes32.random())
        .coinbase(Bytes.wrap(new byte[20]))
        .build();

    pendingBlock = Block.builder()
        .header(header)
        .links(List.of())
        .build();

    missingParent = Bytes32.random();
  }

  @After
  public void tearDown() throws IOException {
    if (dagStore != null) {
      dagStore.stop();
    }

    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir)
          .sorted(Comparator.reverseOrder())
          .forEach(path -> {
            try {
              Files.delete(path);
            } catch (IOException e) {
              // ignore
            }
          });
    }
  }

  @Test
  public void testPersistAndLoadMissingDependencyBlock() {
    dagStore.saveMissingDependencyBlock(pendingBlock, List.of(missingParent));

    List<Bytes32> hashes = dagStore.getMissingDependencyBlockHashes(10);
    assertTrue(hashes.contains(pendingBlock.getHash()));

    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(missingParent);
    assertTrue(waiting.contains(pendingBlock.getHash()));

    Block loaded = dagStore.getBlockByHash(pendingBlock.getHash());
    assertNotNull(loaded);
    assertEquals(pendingBlock.getHash(), loaded.getHash());

    List<Bytes32> parents = dagStore.getMissingParents(pendingBlock.getHash());
    assertEquals(1, parents.size());
    assertEquals(missingParent, parents.get(0));

    dagStore.deleteMissingDependencyBlock(pendingBlock.getHash());
    List<Bytes32> afterDelete = dagStore.getMissingDependencyBlockHashes(10);
    assertFalse(afterDelete.contains(pendingBlock.getHash()));

    List<Bytes32> waitingAfterDelete = dagStore.getBlocksWaitingForParent(missingParent);
    assertFalse(waitingAfterDelete.contains(pendingBlock.getHash()));
  }

  @Test
  public void testGetMissingDependencyBlockCount() {
    // Initially empty
    assertEquals(0, dagStore.getMissingDependencyBlockCount());

    // Add first block
    dagStore.saveMissingDependencyBlock(pendingBlock, List.of(missingParent));
    assertEquals(1, dagStore.getMissingDependencyBlockCount());

    // Add second block
    Block secondBlock = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_001L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();
    Bytes32 secondParent = Bytes32.random();
    dagStore.saveMissingDependencyBlock(secondBlock, List.of(secondParent));
    assertEquals(2, dagStore.getMissingDependencyBlockCount());

    // Delete first block
    dagStore.deleteMissingDependencyBlock(pendingBlock.getHash());
    assertEquals(1, dagStore.getMissingDependencyBlockCount());

    // Delete second block
    dagStore.deleteMissingDependencyBlock(secondBlock.getHash());
    assertEquals(0, dagStore.getMissingDependencyBlockCount());
  }

  @Test
  public void testMultipleParentsWaiting() {
    // Block waiting for multiple parents
    Bytes32 parent1 = Bytes32.random();
    Bytes32 parent2 = Bytes32.random();
    Bytes32 parent3 = Bytes32.random();

    dagStore.saveMissingDependencyBlock(pendingBlock, List.of(parent1, parent2, parent3));

    // Verify all parent indices are created
    List<Bytes32> waiting1 = dagStore.getBlocksWaitingForParent(parent1);
    List<Bytes32> waiting2 = dagStore.getBlocksWaitingForParent(parent2);
    List<Bytes32> waiting3 = dagStore.getBlocksWaitingForParent(parent3);

    assertTrue(waiting1.contains(pendingBlock.getHash()));
    assertTrue(waiting2.contains(pendingBlock.getHash()));
    assertTrue(waiting3.contains(pendingBlock.getHash()));

    // Verify getMissingParents returns all parents
    List<Bytes32> parents = dagStore.getMissingParents(pendingBlock.getHash());
    assertEquals(3, parents.size());
    assertTrue(parents.contains(parent1));
    assertTrue(parents.contains(parent2));
    assertTrue(parents.contains(parent3));

    // Delete and verify all indices are cleaned
    dagStore.deleteMissingDependencyBlock(pendingBlock.getHash());

    assertTrue(dagStore.getBlocksWaitingForParent(parent1).isEmpty());
    assertTrue(dagStore.getBlocksWaitingForParent(parent2).isEmpty());
    assertTrue(dagStore.getBlocksWaitingForParent(parent3).isEmpty());
  }

  @Test
  public void testMultipleBlocksWaitingForSameParent() {
    // Multiple blocks waiting for the same parent
    Block block1 = pendingBlock;
    Block block2 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_002L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();
    Block block3 = Block.builder()
        .header(BlockHeader.builder()
            .epoch(1_000_003L)
            .difficulty(UInt256.valueOf(1))
            .nonce(Bytes32.random())
            .coinbase(Bytes.wrap(new byte[20]))
            .build())
        .links(List.of())
        .build();

    Bytes32 sharedParent = Bytes32.random();

    dagStore.saveMissingDependencyBlock(block1, List.of(sharedParent));
    dagStore.saveMissingDependencyBlock(block2, List.of(sharedParent));
    dagStore.saveMissingDependencyBlock(block3, List.of(sharedParent));

    // All three blocks should be waiting for the same parent
    List<Bytes32> waiting = dagStore.getBlocksWaitingForParent(sharedParent);
    assertEquals(3, waiting.size());
    assertTrue(waiting.contains(block1.getHash()));
    assertTrue(waiting.contains(block2.getHash()));
    assertTrue(waiting.contains(block3.getHash()));

    // Delete one block, others should still be waiting
    dagStore.deleteMissingDependencyBlock(block1.getHash());
    waiting = dagStore.getBlocksWaitingForParent(sharedParent);
    assertEquals(2, waiting.size());
    assertFalse(waiting.contains(block1.getHash()));
    assertTrue(waiting.contains(block2.getHash()));
    assertTrue(waiting.contains(block3.getHash()));
  }
}
