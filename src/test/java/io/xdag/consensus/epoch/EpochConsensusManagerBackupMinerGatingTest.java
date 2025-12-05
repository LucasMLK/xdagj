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

package io.xdag.consensus.epoch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import io.xdag.DagKernel;
import io.xdag.consensus.miner.BlockGenerator;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.p2p.SyncManager;
import io.xdag.store.DagStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for BUG-SYNC-001 fix: BackupMiner gating based on sync status.
 *
 * <p>The BackupMiner should only trigger when the node is synchronized with peers.
 * This prevents new nodes from mining before catching up with the network.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class EpochConsensusManagerBackupMinerGatingTest {

  @Mock private DagKernel dagKernel;
  @Mock private DagChain dagChain;
  @Mock private DagStore dagStore;
  @Mock private BlockGenerator blockGenerator;
  @Mock private SyncManager syncManager;
  @Mock private Block candidateBlock;
  @Mock private BackupMiner backupMiner;

  private EpochConsensusManager manager;
  private ConcurrentHashMap<Long, EpochContext> epochContexts;

  private static final UInt256 MIN_DIFFICULTY = UInt256.fromHexString(
      "0x00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

  @Before
  public void setUp() throws Exception {
    // Setup mock chain
    when(candidateBlock.getHash()).thenReturn(Bytes32.random());
    when(blockGenerator.generateCandidate()).thenReturn(candidateBlock);

    // Create manager with mocked kernel
    manager = new EpochConsensusManager(
        dagKernel,
        dagChain,
        dagStore,
        blockGenerator,
        2,
        MIN_DIFFICULTY
    );

    // Inject mocked BackupMiner via reflection
    Field backupMinerField = EpochConsensusManager.class.getDeclaredField("backupMiner");
    backupMinerField.setAccessible(true);
    backupMinerField.set(manager, backupMiner);

    // Get epoch contexts map via reflection
    Field epochContextsField = EpochConsensusManager.class.getDeclaredField("epochContexts");
    epochContextsField.setAccessible(true);
    epochContexts = (ConcurrentHashMap<Long, EpochContext>) epochContextsField.get(manager);
  }

  /**
   * Get the private triggerBackupMinerIfNeeded method via reflection.
   */
  private Method getTriggerMethod() throws Exception {
    Method method = EpochConsensusManager.class.getDeclaredMethod(
        "triggerBackupMinerIfNeeded", long.class);
    method.setAccessible(true);
    return method;
  }

  /**
   * Create a test EpochContext with no solutions.
   */
  private EpochContext createEmptyEpochContext(long epoch) {
    return new EpochContext(epoch, 0, 64000, candidateBlock);
  }

  // ==================== BUG-SYNC-001: BackupMiner gating tests ====================

  /**
   * Test: When SyncManager is null (kernel not fully initialized),
   * backup miner should still trigger (fail-open for single node scenario).
   */
  @Test
  public void triggerBackupMiner_WhenSyncManagerNull_ShouldTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(null);

    long epoch = 100;
    epochContexts.put(epoch, createEmptyEpochContext(epoch));

    getTriggerMethod().invoke(manager, epoch);

    verify(backupMiner, times(1)).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: When node is synchronized, backup miner should trigger.
   */
  @Test
  public void triggerBackupMiner_WhenSynchronized_ShouldTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);
    when(syncManager.isSynchronized()).thenReturn(true);

    long epoch = 100;
    epochContexts.put(epoch, createEmptyEpochContext(epoch));

    getTriggerMethod().invoke(manager, epoch);

    verify(backupMiner, times(1)).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: When node is NOT synchronized, backup miner should NOT trigger.
   * This is the core fix for BUG-SYNC-001.
   */
  @Test
  public void triggerBackupMiner_WhenNotSynchronized_ShouldNotTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);
    when(syncManager.isSynchronized()).thenReturn(false);
    when(syncManager.getLocalTipEpoch()).thenReturn(50L);
    when(syncManager.getRemoteTipEpoch()).thenReturn(100L);

    long epoch = 100;
    epochContexts.put(epoch, createEmptyEpochContext(epoch));

    getTriggerMethod().invoke(manager, epoch);

    verify(backupMiner, never()).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: When epoch context is missing, should not trigger (and not throw).
   */
  @Test
  public void triggerBackupMiner_WhenContextMissing_ShouldNotTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);
    when(syncManager.isSynchronized()).thenReturn(true);

    // Don't add context for epoch 100
    getTriggerMethod().invoke(manager, 100L);

    verify(backupMiner, never()).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: When context already has solutions, should not trigger backup miner.
   */
  @Test
  public void triggerBackupMiner_WhenHasSolutions_ShouldNotTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);
    when(syncManager.isSynchronized()).thenReturn(true);

    long epoch = 100;
    EpochContext context = createEmptyEpochContext(epoch);

    // Add a solution to the context
    Block solutionBlock = mock(Block.class);
    when(solutionBlock.getHash()).thenReturn(Bytes32.random());
    BlockSolution solution = new BlockSolution(
        solutionBlock,
        "test-pool",
        System.currentTimeMillis(),
        MIN_DIFFICULTY.divide(2)
    );
    context.addSolution(solution);

    epochContexts.put(epoch, context);

    getTriggerMethod().invoke(manager, epoch);

    verify(backupMiner, never()).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: When block already produced, should not trigger backup miner.
   */
  @Test
  public void triggerBackupMiner_WhenBlockProduced_ShouldNotTrigger() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);
    when(syncManager.isSynchronized()).thenReturn(true);

    long epoch = 100;
    EpochContext context = createEmptyEpochContext(epoch);
    context.markBlockProduced();

    epochContexts.put(epoch, context);

    getTriggerMethod().invoke(manager, epoch);

    verify(backupMiner, never()).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  // ==================== Edge Cases ====================

  /**
   * Test: Sync status transitions from not-synced to synced.
   * BackupMiner should trigger after sync completes.
   */
  @Test
  public void triggerBackupMiner_SyncStatusTransition_ShouldRespect() throws Exception {
    when(dagKernel.getSyncManager()).thenReturn(syncManager);

    long epoch = 100;
    epochContexts.put(epoch, createEmptyEpochContext(epoch));

    // First call: not synchronized
    when(syncManager.isSynchronized()).thenReturn(false);
    when(syncManager.getLocalTipEpoch()).thenReturn(50L);
    when(syncManager.getRemoteTipEpoch()).thenReturn(100L);
    getTriggerMethod().invoke(manager, epoch);
    verify(backupMiner, never()).startBackupMining(any(EpochContext.class), any(Block.class));

    // Second call: now synchronized
    when(syncManager.isSynchronized()).thenReturn(true);
    getTriggerMethod().invoke(manager, epoch);
    verify(backupMiner, times(1)).startBackupMining(any(EpochContext.class), any(Block.class));
  }

  /**
   * Test: DagKernel is null (should not happen in practice, but defensive).
   */
  @Test
  public void triggerBackupMiner_WhenKernelNull_ShouldTrigger() throws Exception {
    // Create manager with null kernel
    EpochConsensusManager nullKernelManager = new EpochConsensusManager(
        null,
        dagChain,
        dagStore,
        blockGenerator,
        2,
        MIN_DIFFICULTY
    );

    // Inject mocked BackupMiner
    Field backupMinerField = EpochConsensusManager.class.getDeclaredField("backupMiner");
    backupMinerField.setAccessible(true);
    backupMinerField.set(nullKernelManager, backupMiner);

    // Get epoch contexts map
    Field epochContextsField = EpochConsensusManager.class.getDeclaredField("epochContexts");
    epochContextsField.setAccessible(true);
    ConcurrentHashMap<Long, EpochContext> contexts =
        (ConcurrentHashMap<Long, EpochContext>) epochContextsField.get(nullKernelManager);

    long epoch = 100;
    contexts.put(epoch, createEmptyEpochContext(epoch));

    Method method = EpochConsensusManager.class.getDeclaredMethod(
        "triggerBackupMinerIfNeeded", long.class);
    method.setAccessible(true);
    method.invoke(nullKernelManager, epoch);

    // Should trigger (fail-open) when kernel is null
    verify(backupMiner, times(1)).startBackupMining(any(EpochContext.class), any(Block.class));
  }
}
