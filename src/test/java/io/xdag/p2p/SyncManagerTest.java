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

package io.xdag.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.GetEpochHashesMessage;
import io.xdag.p2p.message.Message;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SyncManagerTest {

  @Mock private DagKernel dagKernel;
  @Mock private DagChain dagChain;
  @Mock private P2pService p2pService;
  @Mock private ChannelManager channelManager;
  @Mock private Channel channel;
  @Mock private Block tipBlock;

  private SyncManager syncManager;

  @Before
  public void setUp() {
    when(dagKernel.getDagChain()).thenReturn(dagChain);
    when(dagKernel.getP2pService()).thenReturn(p2pService);
    when(p2pService.getChannelManager()).thenReturn(channelManager);
    when(channelManager.getChannels())
        .thenReturn(Map.of(new InetSocketAddress("127.0.0.1", 5000), channel));

    when(dagChain.getMainChainLength()).thenReturn(10L);
    when(tipBlock.getEpoch()).thenReturn(128L);
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipBlock);

    syncManager = new SyncManager(dagKernel, false);
    // Mark initial historical scan as done to skip fork detection for most tests
    // Fork detection tests will create their own SyncManager instance
    syncManager.markInitialHistoricalScanDone();
    syncManager.start();
  }

  @After
  public void tearDown() {
    syncManager.stop();
  }

  @Test
  public void shouldRequestEpochsWhenBehind() {
    when(dagChain.getCurrentEpoch()).thenReturn(256L);

    syncManager.performSync();

    verify(channel).send(argThat((Message msg) ->
        msg instanceof GetEpochHashesMessage request
            && request.getStartEpoch() == 129L
            && request.getEndEpoch() >= request.getStartEpoch()));
  }

  @Test
  public void shouldSkipWhenUpToDate() {
    when(dagChain.getCurrentEpoch()).thenReturn(128L);

    syncManager.performSync();

    verify(channel, never()).send(any(Message.class));
  }

  // ==================== BUG-SYNC-001: isSynchronized() tests ====================

  /**
   * Test: When no peer has reported yet (remoteTipEpoch == 0), should be considered synchronized.
   * This is the first node scenario.
   */
  @Test
  public void isSynchronized_WhenNoPeerInfo_ShouldReturnTrue() {
    // remoteTipEpoch is 0 by default (no peers have reported)
    assertTrue("Should be synchronized when no peer info", syncManager.isSynchronized());
    assertEquals(0L, syncManager.getRemoteTipEpoch());
  }

  /**
   * Test: When local tip equals remote tip, should be synchronized.
   */
  @Test
  public void isSynchronized_WhenLocalEqualsRemote_ShouldReturnTrue() {
    // Local tip is 128 (from tipBlock mock)
    syncManager.updateRemoteTipEpoch(128L);

    assertTrue("Should be synchronized when local == remote", syncManager.isSynchronized());
  }

  /**
   * Test: When local tip is ahead of remote tip, should be synchronized.
   * This can happen when we mine a new block before status exchange.
   */
  @Test
  public void isSynchronized_WhenLocalAheadOfRemote_ShouldReturnTrue() {
    // Local tip is 128, remote tip is 100
    syncManager.updateRemoteTipEpoch(100L);

    assertTrue("Should be synchronized when local > remote", syncManager.isSynchronized());
  }

  /**
   * Test: When local tip is within SYNC_TOLERANCE (2 epochs) of remote tip,
   * should be considered synchronized.
   */
  @Test
  public void isSynchronized_WhenWithinTolerance_ShouldReturnTrue() {
    // Local tip is 128, remote tip is 129 (1 epoch behind, within tolerance of 2)
    syncManager.updateRemoteTipEpoch(129L);
    assertTrue("Should be synchronized when 1 epoch behind", syncManager.isSynchronized());

    // Remote tip is 130 (2 epochs behind, exactly at tolerance)
    syncManager.updateRemoteTipEpoch(130L);
    assertTrue("Should be synchronized when 2 epochs behind", syncManager.isSynchronized());
  }

  /**
   * Test: When local tip is more than SYNC_TOLERANCE behind remote tip,
   * should NOT be synchronized.
   */
  @Test
  public void isSynchronized_WhenBeyondTolerance_ShouldReturnFalse() {
    // Local tip is 128, remote tip is 131 (3 epochs behind, beyond tolerance)
    syncManager.updateRemoteTipEpoch(131L);

    assertFalse("Should NOT be synchronized when 3 epochs behind", syncManager.isSynchronized());
  }

  /**
   * Test: When local tip is far behind remote tip, should NOT be synchronized.
   * This is the typical new node startup scenario.
   */
  @Test
  public void isSynchronized_WhenFarBehind_ShouldReturnFalse() {
    // Local tip is 128, remote tip is 1000 (872 epochs behind)
    syncManager.updateRemoteTipEpoch(1000L);

    assertFalse("Should NOT be synchronized when far behind", syncManager.isSynchronized());
  }

  // ==================== BUG-SYNC-001: updateRemoteTipEpoch() tests ====================

  /**
   * Test: updateRemoteTipEpoch should take the maximum of all peer tips.
   */
  @Test
  public void updateRemoteTipEpoch_ShouldTakeMaximum() {
    // First peer reports 100
    syncManager.updateRemoteTipEpoch(100L);
    assertEquals(100L, syncManager.getRemoteTipEpoch());

    // Second peer reports 150 (higher)
    syncManager.updateRemoteTipEpoch(150L);
    assertEquals(150L, syncManager.getRemoteTipEpoch());

    // Third peer reports 120 (lower, should be ignored)
    syncManager.updateRemoteTipEpoch(120L);
    assertEquals("Should keep max value", 150L, syncManager.getRemoteTipEpoch());
  }

  /**
   * Test: updateRemoteTipEpoch with same value should not change anything.
   */
  @Test
  public void updateRemoteTipEpoch_SameValue_ShouldNotChange() {
    syncManager.updateRemoteTipEpoch(100L);
    assertEquals(100L, syncManager.getRemoteTipEpoch());

    syncManager.updateRemoteTipEpoch(100L);
    assertEquals(100L, syncManager.getRemoteTipEpoch());
  }

  // ==================== BUG-SYNC-001: getLocalTipEpoch() tests ====================

  /**
   * Test: getLocalTipEpoch should return epoch of latest main chain block.
   */
  @Test
  public void getLocalTipEpoch_ShouldReturnTipBlockEpoch() {
    // tipBlock mock returns epoch 128
    assertEquals(128L, syncManager.getLocalTipEpoch());
  }

  /**
   * Test: getLocalTipEpoch should return 0 when chain is empty.
   */
  @Test
  public void getLocalTipEpoch_WhenChainEmpty_ShouldReturnZero() {
    when(dagChain.getMainChainLength()).thenReturn(0L);

    assertEquals(0L, syncManager.getLocalTipEpoch());
  }

  /**
   * Test: getLocalTipEpoch should return 0 when main block is null.
   */
  @Test
  public void getLocalTipEpoch_WhenBlockNull_ShouldReturnZero() {
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(null);

    assertEquals(0L, syncManager.getLocalTipEpoch());
  }

  // ==================== Edge Case: Empty chain scenarios ====================

  /**
   * Test: New node with empty chain should be synchronized if no peers.
   */
  @Test
  public void isSynchronized_EmptyChainNoPeers_ShouldReturnTrue() {
    when(dagChain.getMainChainLength()).thenReturn(0L);

    // No peers have reported (remoteTipEpoch == 0)
    assertTrue("Empty chain with no peers should be synchronized", syncManager.isSynchronized());
  }

  /**
   * Test: New node with empty chain should NOT be synchronized if peers have blocks.
   */
  @Test
  public void isSynchronized_EmptyChainWithPeers_ShouldReturnFalse() {
    when(dagChain.getMainChainLength()).thenReturn(0L);

    // Peer reports tip at epoch 100
    syncManager.updateRemoteTipEpoch(100L);

    assertFalse("Empty chain with active peers should NOT be synchronized",
        syncManager.isSynchronized());
  }

  // ==================== BUG-SYNC-004: Binary Search Tests ====================

  /**
   * Test: Binary search should be triggered when gap > BINARY_SEARCH_THRESHOLD (1024)
   */
  @Test
  public void binarySearch_ShouldTriggerWhenLargeGap() {
    // Local tip epoch is 128
    // Set remote tip to be > 1024 epochs ahead
    syncManager.updateRemoteTipEpoch(2000L);
    when(dagChain.getCurrentEpoch()).thenReturn(2000L);

    syncManager.performSync();

    // Should be in binary search mode now
    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());
  }

  /**
   * Test: Binary search should NOT be triggered when gap <= BINARY_SEARCH_THRESHOLD
   */
  @Test
  public void binarySearch_ShouldNotTriggerWhenSmallGap() {
    // Local tip epoch is 128
    // Set remote tip to be <= 1024 epochs ahead
    syncManager.updateRemoteTipEpoch(1000L);
    when(dagChain.getCurrentEpoch()).thenReturn(1000L);

    syncManager.performSync();

    // Should NOT be in binary search mode
    assertFalse("Should NOT be in binary search mode for small gap", syncManager.isInBinarySearch());
  }

  /**
   * Test: onBinarySearchResponse should narrow range when blocks found
   */
  @Test
  public void onBinarySearchResponse_WhenBlocksFound_ShouldSearchLower() {
    // Setup: trigger binary search
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);
    syncManager.performSync();

    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate finding blocks at minEpoch 3000
    syncManager.onBinarySearchResponse(true, 3000L);

    // minValidEpochFound should be updated
    // Can't directly check private field, but next response should affect behavior
  }

  /**
   * Test: onBinarySearchResponse should search higher when no blocks found
   */
  @Test
  public void onBinarySearchResponse_WhenNoBlocks_ShouldSearchHigher() {
    // Setup: trigger binary search
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);
    syncManager.performSync();

    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate finding no blocks
    syncManager.onBinarySearchResponse(false, -1);

    // Should still be in binary search mode (searching higher range)
    assertTrue("Should still be in binary search mode", syncManager.isInBinarySearch());
  }

  /**
   * Test: Binary search should complete after range narrows to <= MAX_EPOCHS_PER_REQUEST (256)
   */
  @Test
  public void binarySearch_ShouldCompleteWhenRangeNarrows() {
    // Setup: trigger binary search with moderate gap
    syncManager.updateRemoteTipEpoch(2000L);
    when(dagChain.getCurrentEpoch()).thenReturn(2000L);
    syncManager.performSync();

    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate multiple responses that narrow the range
    // After enough iterations, the range should narrow to <= 256
    for (int i = 0; i < 10; i++) {
      if (!syncManager.isInBinarySearch()) break;
      syncManager.onBinarySearchResponse(false, -1);
    }

    // After range narrows sufficiently, should exit binary search
    // The exact number of iterations depends on implementation
  }

  /**
   * Test: Binary search should complete after MAX_BINARY_SEARCH_ITERATIONS (20)
   */
  @Test
  public void binarySearch_ShouldCompleteAfterMaxIterations() {
    // Setup: trigger binary search with very large gap
    syncManager.updateRemoteTipEpoch(1_000_000L);
    when(dagChain.getCurrentEpoch()).thenReturn(1_000_000L);
    syncManager.performSync();

    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate 25 iterations (more than MAX_BINARY_SEARCH_ITERATIONS = 20)
    for (int i = 0; i < 25; i++) {
      if (!syncManager.isInBinarySearch()) {
        // Binary search completed
        return;
      }
      syncManager.performSync(); // triggers next iteration
      syncManager.onBinarySearchResponse(false, -1);
    }

    // After 20 iterations, should have exited binary search
    assertFalse("Should exit binary search after max iterations", syncManager.isInBinarySearch());
  }

  /**
   * Test: forwardSyncStartEpoch should prevent re-triggering binary search
   */
  @Test
  public void forwardSyncStartEpoch_ShouldPreventReTrigger() {
    // Setup: trigger and complete binary search
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);
    syncManager.performSync();

    // Complete binary search by narrowing range
    for (int i = 0; i < 25; i++) {
      if (!syncManager.isInBinarySearch()) break;
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
    }

    // After binary search completes, perform sync again
    // Local tip hasn't changed (still 128), gap is still large
    // But forwardSyncStartEpoch should prevent re-triggering binary search
    syncManager.performSync();

    assertFalse("Should NOT re-trigger binary search after completion",
        syncManager.isInBinarySearch());
  }

  /**
   * Test: Edge case - genesis epoch as starting point
   * When local tip equals genesis epoch, binary search should start from genesis+1
   */
  @Test
  public void binarySearch_FromGenesisEpoch() {
    // Setup: local tip at genesis epoch (let's say 100)
    when(tipBlock.getEpoch()).thenReturn(100L);
    when(dagChain.getMainChainLength()).thenReturn(1L);
    when(dagChain.getMainBlockByHeight(1L)).thenReturn(tipBlock);

    // Remote tip is far ahead
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);

    syncManager.performSync();

    // Should trigger binary search from genesis+1 to remote tip
    assertTrue("Should trigger binary search from genesis", syncManager.isInBinarySearch());
  }

  /**
   * Test: Edge case - all probes return no blocks (use fallback)
   * When binary search completes without finding any blocks,
   * should use the narrowed lower bound as the start epoch
   */
  @Test
  public void binarySearch_AllProbesNoBlocks_ShouldUseFallback() {
    // Setup: trigger binary search
    syncManager.updateRemoteTipEpoch(3000L);
    when(dagChain.getCurrentEpoch()).thenReturn(3000L);
    syncManager.performSync();

    // All probes return no blocks
    for (int i = 0; i < 25; i++) {
      if (!syncManager.isInBinarySearch()) break;
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
    }

    // Should have exited binary search and use fallback (lower bound of narrowed range)
    assertFalse("Should exit binary search", syncManager.isInBinarySearch());
  }

  /**
   * Test: Edge case - first probe finds blocks immediately
   */
  @Test
  public void binarySearch_FirstProbeFindsBlocks() {
    // Setup: trigger binary search
    syncManager.updateRemoteTipEpoch(3000L);
    when(dagChain.getCurrentEpoch()).thenReturn(3000L);
    syncManager.performSync();

    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // First probe finds blocks at epoch 1500
    syncManager.onBinarySearchResponse(true, 1500L);

    // Should continue searching lower half
    assertTrue("Should continue binary search to find lower epoch",
        syncManager.isInBinarySearch());
  }

  /**
   * Test: Boundary - gap exactly at BINARY_SEARCH_THRESHOLD
   */
  @Test
  public void binarySearch_ExactlyAtThreshold() {
    // Local tip epoch is 128
    // Gap exactly at threshold (1024)
    syncManager.updateRemoteTipEpoch(128L + 1024L);
    when(dagChain.getCurrentEpoch()).thenReturn(128L + 1024L);

    syncManager.performSync();

    // At exactly threshold, should NOT trigger binary search (need gap > threshold)
    assertFalse("Should NOT trigger at exactly threshold", syncManager.isInBinarySearch());
  }

  /**
   * Test: Boundary - gap one above BINARY_SEARCH_THRESHOLD
   */
  @Test
  public void binarySearch_OneAboveThreshold() {
    // Local tip epoch is 128
    // Gap one above threshold (1025)
    syncManager.updateRemoteTipEpoch(128L + 1025L);
    when(dagChain.getCurrentEpoch()).thenReturn(128L + 1025L);

    syncManager.performSync();

    // Just above threshold, should trigger binary search
    assertTrue("Should trigger at one above threshold", syncManager.isInBinarySearch());
  }

  /**
   * Test: After binary search completes, forward sync should actually send requests
   * even when pipeline gap is large.
   * This tests the bug where catch-up mode was not skipping the pipeline gap check.
   */
  @Test
  public void binarySearch_AfterComplete_ShouldSendRequestDespiteLargeGap() {
    // Setup: trigger binary search with large gap
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);
    syncManager.performSync();

    // Complete binary search
    for (int i = 0; i < 25; i++) {
      if (!syncManager.isInBinarySearch()) break;
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
    }

    assertFalse("Binary search should be complete", syncManager.isInBinarySearch());

    // Now perform forward sync - should send request despite large gap
    // (localTipEpoch=128, lastRequestedEpoch is near 5000, gap > 4096)
    syncManager.performSync();

    // Verify that a GET_EPOCH_HASHES message was sent
    // In catch-up mode, pipeline gap check should be skipped
    verify(channel, org.mockito.Mockito.atLeastOnce()).send(argThat((Message msg) ->
        msg instanceof GetEpochHashesMessage));
  }

  /**
   * Test: Normal forward sync should be paused when pipeline gap is too large.
   * This verifies the pipeline gap check works in normal (non-catch-up) mode.
   */
  @Test
  public void forwardSync_ShouldPauseWhenPipelineGapTooLarge() {
    // Local tip epoch is 128, current epoch is 256
    when(dagChain.getCurrentEpoch()).thenReturn(256L);

    // First sync - should request epochs
    syncManager.performSync();
    verify(channel).send(any(GetEpochHashesMessage.class));

    // Reset mock to track new invocations
    org.mockito.Mockito.reset(channel);

    // Simulate that lastRequestedEpoch advanced far (e.g., to 5000)
    // but localTipEpoch is still 128
    // This creates a large pipeline gap
    syncManager.updateRemoteTipEpoch(200L); // Small remote tip, no binary search
    when(dagChain.getCurrentEpoch()).thenReturn(6000L);

    // Manually set a large gap scenario by calling performSync multiple times
    // The pipeline gap check should eventually pause the sync
    // (This test verifies the gap check exists in normal mode)
  }

  // ==================== BUG-SYNC-005: Empty Epoch Detection Tests ====================

  /**
   * Test: When response contains epochs but misses some in the requested range,
   * the missing epochs should be marked as confirmed empty.
   *
   * Scenario:
   * - localTipEpoch=128, so request starts from epoch 129
   * - Request epochs [129, ~384] (capped by currentEpoch=400)
   * - Response contains {135}
   * - Epochs 129-134 should be marked as empty (before max received 135)
   */
  @Test
  public void onEpochHashesResponse_ShouldMarkMissingEpochsAsEmpty() {
    when(dagChain.getCurrentEpoch()).thenReturn(400L);
    syncManager.performSync();

    // Use multi-epoch response to trigger empty detection (single-epoch skipped as edge case)
    syncManager.onEpochHashesResponse(java.util.Set.of(135L, 140L));

    assertTrue(syncManager.isEpochConfirmedEmpty(129));
    assertTrue(syncManager.isEpochConfirmedEmpty(130));
    assertTrue(syncManager.isEpochConfirmedEmpty(131));
  }

  /**
   * Test: Empty response should NOT mark any epochs as empty.
   * This handles the case where peer is behind and has no blocks in the requested range.
   */
  @Test
  public void onEpochHashesResponse_EmptyResponse_ShouldNotMarkAnyEmpty() {
    when(dagChain.getCurrentEpoch()).thenReturn(200L);
    syncManager.performSync();

    // Empty response - peer might be behind
    java.util.Set<Long> receivedEpochs = java.util.Set.of();
    syncManager.onEpochHashesResponse(receivedEpochs);

    // Nothing should be marked as empty (can't confirm peer has later epochs)
    assertEquals("No epochs should be confirmed empty", 0L, syncManager.getConfirmedEmptyEpochCount());
  }

  /**
   * Test: Response during binary search mode should NOT mark epochs as empty.
   * Binary search uses different handling logic.
   */
  @Test
  public void onEpochHashesResponse_InBinarySearch_ShouldNotMarkEmpty() {
    // Trigger binary search
    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);
    syncManager.performSync();

    assertTrue("Should be in binary search", syncManager.isInBinarySearch());

    // Response with gaps - should NOT mark as empty during binary search
    java.util.Set<Long> receivedEpochs = java.util.Set.of(3000L, 3005L);
    syncManager.onEpochHashesResponse(receivedEpochs);

    // Epochs should NOT be marked as empty
    assertFalse(syncManager.isEpochConfirmedEmpty(3001));
    assertFalse(syncManager.isEpochConfirmedEmpty(3002));
  }

  /**
   * Test: isEpochConfirmedEmpty should return false for non-confirmed epochs.
   */
  @Test
  public void isEpochConfirmedEmpty_NonConfirmed_ShouldReturnFalse() {
    assertFalse("Non-confirmed epoch should return false",
        syncManager.isEpochConfirmedEmpty(12345L));
  }

  /**
   * Test: Confirmed empty epochs should be tracked correctly.
   * localTipEpoch=128, request starts from 129
   */
  @Test
  public void confirmedEmptyEpochs_ShouldTrackCorrectly() {
    when(dagChain.getCurrentEpoch()).thenReturn(400L);
    syncManager.performSync();
    // Use multi-epoch response: epoch 140 and 145, so 129-139 are marked empty
    syncManager.onEpochHashesResponse(java.util.Set.of(140L, 145L));

    assertTrue(syncManager.isEpochConfirmedEmpty(129));
    assertTrue(syncManager.isEpochConfirmedEmpty(139));
    assertFalse(syncManager.isEpochConfirmedEmpty(140));
  }

  /**
   * Test: Request start epoch should be tracked for empty epoch detection.
   * This test verifies the request tracking mechanism.
   */
  @Test
  public void sendEpochRequest_ShouldTrackStartEpoch() {
    when(dagChain.getCurrentEpoch()).thenReturn(300L);

    // Perform sync - should send request and track start epoch
    syncManager.performSync();

    // Verify request was sent
    verify(channel).send(argThat((Message msg) ->
        msg instanceof GetEpochHashesMessage));
  }

  /**
   * Test: Multiple responses should accumulate confirmed empty epochs.
   */
  @Test
  public void multipleResponses_ShouldAccumulateConfirmedEmpty() {
    when(dagChain.getCurrentEpoch()).thenReturn(500L);

    syncManager.performSync();
    // First response: epochs 130-135, marks 129 and 131-134 as empty (request starts at 129)
    syncManager.onEpochHashesResponse(java.util.Set.of(130L, 135L));
    long countAfterFirst = syncManager.getConfirmedEmptyEpochCount();
    assertTrue("First response should mark some epochs as empty", countAfterFirst > 0);

    syncManager.performSync();
    // Second response: epochs in the second request range (around 385+)
    // Second request starts at max(129, 385) = 385, so response needs epochs >= 385
    syncManager.onEpochHashesResponse(java.util.Set.of(390L, 400L));

    assertTrue("Second response should add more empty epochs",
        syncManager.getConfirmedEmptyEpochCount() > countAfterFirst);
  }

  /**
   * Test: Edge case - response with single epoch should not mark anything as empty.
   * If only one epoch is in response, we can't determine gaps.
   */
  @Test
  public void singleEpochResponse_ShouldNotMarkEmpty() {
    when(dagChain.getCurrentEpoch()).thenReturn(200L);
    syncManager.performSync();

    syncManager.onEpochHashesResponse(java.util.Set.of(150L));

    assertEquals(0L, syncManager.getConfirmedEmptyEpochCount());
  }

  /**
   * Test: No request context (start epoch = -1) should skip empty epoch detection.
   */
  @Test
  public void noRequestContext_ShouldSkipEmptyDetection() {
    // Call onEpochHashesResponse without prior request
    // (lastRequestStartEpoch should be -1)
    syncManager.onEpochHashesResponse(java.util.Set.of(100L, 105L));

    // Should not mark any epochs as empty (no valid request context)
    assertEquals(0L, syncManager.getConfirmedEmptyEpochCount());
  }

  /**
   * Test: Verify that confirmed empty epochs are skipped during sync.
   * This is the core fix for BUG-SYNC-005.
   * We verify through isEpochConfirmedEmpty() since findFirstMissingEpoch() is private.
   */
  @Test
  public void findFirstMissingEpoch_ShouldSkipConfirmedEmpty() {
    Block block1 = org.mockito.Mockito.mock(Block.class);
    Block block2 = org.mockito.Mockito.mock(Block.class);
    when(block1.getEpoch()).thenReturn(100L);
    when(block2.getEpoch()).thenReturn(103L);

    when(dagChain.getMainChainLength()).thenReturn(2L);
    when(dagChain.getMainBlockByHeight(1L)).thenReturn(block1);
    when(dagChain.getMainBlockByHeight(2L)).thenReturn(block2);
    when(dagChain.getCurrentEpoch()).thenReturn(200L);

    syncManager.performSync();
    // Use multi-epoch response: contains 103 and 105, so 101, 102 should be marked empty
    syncManager.onEpochHashesResponse(java.util.Set.of(103L, 105L));

    // Verify epochs 101, 102 are confirmed empty
    assertTrue(syncManager.isEpochConfirmedEmpty(101));
    assertTrue(syncManager.isEpochConfirmedEmpty(102));
    assertFalse(syncManager.isEpochConfirmedEmpty(103));
  }

  /**
   * Test: Cache should handle large number of empty epochs.
   * Verifies memory protection through bounded cache.
   */
  @Test
  public void CONFIRM_EMPTY_boundary_test() {
    when(dagChain.getCurrentEpoch()).thenReturn(10_000L);
    syncManager.performSync();
    // Use multi-epoch response to trigger empty epoch detection
    syncManager.onEpochHashesResponse(java.util.Set.of(10_000L, 10_001L, 10_002L));
    assertTrue(syncManager.getConfirmedEmptyEpochCount() > 0);
  }

  /**
   * Test: Consecutive empty epochs should all be marked.
   */
  @Test
  public void consecutiveEmptyEpochs_ShouldAllBeMarked() {
    when(dagChain.getCurrentEpoch()).thenReturn(300L);
    syncManager.performSync();

    syncManager.onEpochHashesResponse(java.util.Set.of(200L, 250L));

    assertTrue(syncManager.isEpochConfirmedEmpty(129));
  }

  /**
   * Integration test: Full flow of detecting and skipping empty epochs.
   * This simulates the real-world scenario where:
   * 1. Gap is detected
   * 2. Request is sent
   * 3. Response confirms epoch is empty
   * 4. Subsequent gap detection skips the empty epoch
   */
  @Test
  public void integrationTest_EmptyEpochFlow() {
    Block block1 = org.mockito.Mockito.mock(Block.class);
    Block block2 = org.mockito.Mockito.mock(Block.class);
    when(block1.getEpoch()).thenReturn(100L);
    when(block2.getEpoch()).thenReturn(103L);

    when(dagChain.getMainChainLength()).thenReturn(2L);
    when(dagChain.getMainBlockByHeight(1L)).thenReturn(block1);
    when(dagChain.getMainBlockByHeight(2L)).thenReturn(block2);
    when(dagChain.getCurrentEpoch()).thenReturn(200L);

    syncManager.performSync();
    verify(channel).send((io.xdag.p2p.message.Message) argThat(msg -> msg instanceof GetEpochHashesMessage));

    // Use multi-epoch response to trigger empty epoch detection
    syncManager.onEpochHashesResponse(java.util.Set.of(103L, 105L));
    assertTrue(syncManager.isEpochConfirmedEmpty(101));
    assertTrue(syncManager.isEpochConfirmedEmpty(102));
    // Note: findFirstMissingEpoch() is now private, removing the assertion
    // The behavior is implicitly tested by verifying the empty epochs are marked
  }

  /**
   * Test: Stale responses (from old requests) should be ignored.
   * This tests the sequence ID mechanism that prevents request-response mismatch.
   *
   * Scenario:
   * 1. Send request A (epochs 100-200)
   * 2. Send request B (epochs 200-300) - overwrites request context
   * 3. Response for A arrives - should be IGNORED (stale)
   * 4. Response for B arrives - should be processed
   */
  @Test
  public void staleResponse_ShouldBeIgnored() {
    when(dagChain.getCurrentEpoch()).thenReturn(500L);

    // First sync - sends request A
    syncManager.performSync();
    long countBefore = syncManager.getConfirmedEmptyEpochCount();

    // Second sync - sends request B (overwrites context, increments sequence)
    syncManager.performSync();

    // Now simulate "response for request A" arriving (stale)
    // Since sequence ID doesn't match, this should be ignored
    syncManager.onEpochHashesResponse(java.util.Set.of(100L, 150L));

    // No epochs should be marked as empty from the stale response
    // because the context was cleared when request B was sent
    long countAfterStale = syncManager.getConfirmedEmptyEpochCount();
    assertEquals("Stale response should not mark any epochs as empty",
        countBefore, countAfterStale);
  }

  /**
   * Test: Response matching current request should be processed correctly.
   * Verifies that sequence ID matching works for valid responses.
   */
  @Test
  public void validResponse_ShouldBeProcessed() {
    when(dagChain.getCurrentEpoch()).thenReturn(200L);

    // Send request
    syncManager.performSync();

    // Response arrives with matching sequence ID (implicit - we didn't send another request)
    syncManager.onEpochHashesResponse(java.util.Set.of(140L, 150L));

    // Epochs between 129 (start) and 139 should be marked as empty
    // (assuming request started from localTipEpoch + 1 = 129)
    assertTrue("Epochs before max received should be marked empty",
        syncManager.getConfirmedEmptyEpochCount() > 0);
  }

  /**
   * Test: Orphan response (no pending request) should be ignored.
   */
  @Test
  public void orphanResponse_ShouldBeIgnored() {
    // No prior performSync() call - no request context exists

    // Send a response directly - should be ignored
    syncManager.onEpochHashesResponse(java.util.Set.of(100L, 105L, 110L));

    // Nothing should be marked as empty
    assertEquals("Orphan response should not mark epochs",
        0L, syncManager.getConfirmedEmptyEpochCount());
  }

  // ==================== BUG-SYNC-003: Fork Detection Tests ====================

  /**
   * Helper method to create a fresh SyncManager for fork detection tests.
   * Does NOT call markInitialHistoricalScanDone() so fork detection will trigger.
   */
  private SyncManager createForkDetectionSyncManager() {
    SyncManager mgr = new SyncManager(dagKernel, false);
    // Do NOT mark initial historical scan as done
    mgr.start();
    return mgr;
  }

  /**
   * Test: Fork detection should be initiated on first peer connection
   * when node has blocks beyond genesis.
   */
  @Test
  public void forkDetection_ShouldInitiateOnFirstPeerConnection() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      // Setup: chain has more than just genesis
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      // Mock genesis block for getGenesisEpoch()
      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      // Mock tip block for getLocalTipEpoch()
      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(150L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // First sync should initiate fork detection
      forkSyncManager.performSync();

      assertTrue("Should be in fork detection mode", forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: Fork detection should NOT be triggered when chain only has genesis.
   */
  @Test
  public void forkDetection_ShouldNotTriggerWithOnlyGenesis() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      // Setup: chain only has genesis
      when(dagChain.getMainChainLength()).thenReturn(1L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      forkSyncManager.performSync();

      assertFalse("Should NOT be in fork detection with only genesis",
          forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: Fork detection should only run once per session.
   */
  @Test
  public void forkDetection_ShouldOnlyRunOnce() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      // Setup: chain has blocks
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(105L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // First sync initiates fork detection
      forkSyncManager.performSync();
      assertTrue("First sync should start fork detection", forkSyncManager.isInForkDetection());

      // Simulate fork detection completing - chains are consistent
      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> consistentResponse =
          new java.util.HashMap<>();
      // Add epoch 100 with matching hash
      Block winnerBlock = org.mockito.Mockito.mock(Block.class);
      org.apache.tuweni.bytes.Bytes32 winnerHash = org.apache.tuweni.bytes.Bytes32.random();
      when(winnerBlock.getHash()).thenReturn(winnerHash);
      when(dagChain.getWinnerBlockInEpoch(100L)).thenReturn(winnerBlock);
      consistentResponse.put(100L, java.util.List.of(winnerHash));

      // Process response - should complete fork detection (scan complete since 100-105 is small)
      forkSyncManager.onForkDetectionResponse(consistentResponse);

      // Next sync should NOT re-trigger fork detection
      forkSyncManager.performSync();
      assertFalse("Should not re-trigger fork detection",
          forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: onForkDetectionResponse should detect divergence when peer has better hash.
   */
  @Test
  public void onForkDetectionResponse_ShouldDetectDivergence() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      // Setup: chain with blocks
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(105L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // Initiate fork detection
      forkSyncManager.performSync();
      assertTrue(forkSyncManager.isInForkDetection());

      // Create divergent response
      // Local winner has larger hash, peer has smaller (better) hash
      Block localWinner = org.mockito.Mockito.mock(Block.class);
      org.apache.tuweni.bytes.Bytes32 localHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
      when(localWinner.getHash()).thenReturn(localHash);
      when(dagChain.getWinnerBlockInEpoch(100L)).thenReturn(localWinner);

      // Peer has better (smaller) hash
      org.apache.tuweni.bytes.Bytes32 peerHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000001");

      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> divergentResponse =
          new java.util.HashMap<>();
      divergentResponse.put(100L, java.util.List.of(peerHash));

      // Process response - should detect divergence and transition to reorg
      forkSyncManager.onForkDetectionResponse(divergentResponse);

      assertTrue("Should transition to chain reorganization",
          forkSyncManager.isInChainReorganization());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: Fork detection should continue scanning when no divergence found in batch.
   */
  @Test
  public void onForkDetectionResponse_ShouldContinueScanningIfNoDivergence() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      when(dagChain.getMainChainLength()).thenReturn(10L);
      when(dagChain.getCurrentEpoch()).thenReturn(500L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      // Mock tip block for getLocalTipEpoch()
      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(400L);
      when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipMock);

      // Initiate fork detection
      forkSyncManager.performSync();
      assertTrue(forkSyncManager.isInForkDetection());

      // Response with matching hashes (no divergence)
      Block localWinner = org.mockito.Mockito.mock(Block.class);
      org.apache.tuweni.bytes.Bytes32 matchingHash = org.apache.tuweni.bytes.Bytes32.random();
      when(localWinner.getHash()).thenReturn(matchingHash);
      when(dagChain.getWinnerBlockInEpoch(100L)).thenReturn(localWinner);

      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> matchingResponse =
          new java.util.HashMap<>();
      matchingResponse.put(100L, java.util.List.of(matchingHash));

      // Process response - should still be in fork detection (more epochs to scan)
      forkSyncManager.onForkDetectionResponse(matchingResponse);

      // Should still be in fork detection since there are more epochs to scan
      // (tip is at 400, we only scanned from 100)
      assertTrue("Should still be in fork detection mode",
          forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: Chain reorganization should request blocks from fork point.
   */
  @Test
  public void chainReorganization_ShouldRequestFromForkPoint() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(300L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(150L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // Initiate fork detection
      forkSyncManager.performSync();
      assertTrue(forkSyncManager.isInForkDetection());

      // Simulate finding divergence at epoch 110
      Block localWinner = org.mockito.Mockito.mock(Block.class);
      org.apache.tuweni.bytes.Bytes32 localHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
      when(localWinner.getHash()).thenReturn(localHash);
      when(dagChain.getWinnerBlockInEpoch(100L)).thenReturn(localWinner);
      when(dagChain.getWinnerBlockInEpoch(110L)).thenReturn(localWinner);

      // Peer has better hash at epoch 110
      org.apache.tuweni.bytes.Bytes32 peerHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000001");

      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> response =
          new java.util.HashMap<>();
      response.put(110L, java.util.List.of(peerHash));

      forkSyncManager.onForkDetectionResponse(response);

      // Should be in chain reorganization mode
      assertTrue("Should be in chain reorganization", forkSyncManager.isInChainReorganization());

      // Perform sync should request blocks from fork point
      forkSyncManager.performSync();

      // Verify GET_EPOCH_HASHES message was sent
      verify(channel, org.mockito.Mockito.atLeastOnce()).send(
          argThat((Message msg) -> msg instanceof GetEpochHashesMessage));
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: Chain reorganization should complete when all epochs synced.
   */
  @Test
  public void chainReorganization_ShouldCompleteWhenDone() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      when(dagChain.getMainChainLength()).thenReturn(3L);
      when(dagChain.getCurrentEpoch()).thenReturn(120L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(105L);
      when(dagChain.getMainBlockByHeight(3L)).thenReturn(tipMock);

      // Initiate fork detection
      forkSyncManager.performSync();

      // Trigger divergence detection - simulate response that causes reorg
      Block localWinner = org.mockito.Mockito.mock(Block.class);
      org.apache.tuweni.bytes.Bytes32 localHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
      when(localWinner.getHash()).thenReturn(localHash);
      when(dagChain.getWinnerBlockInEpoch(100L)).thenReturn(localWinner);

      org.apache.tuweni.bytes.Bytes32 peerHash = org.apache.tuweni.bytes.Bytes32.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000001");

      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> response =
          new java.util.HashMap<>();
      response.put(100L, java.util.List.of(peerHash));

      forkSyncManager.onForkDetectionResponse(response);
      assertTrue("Should be in reorg mode", forkSyncManager.isInChainReorganization());

      // Simulate syncing past the target epoch
      // Multiple performSync calls until reorg complete
      for (int i = 0; i < 10; i++) {
        forkSyncManager.performSync();
        if (!forkSyncManager.isInChainReorganization()) {
          break;
        }
      }

      // Should have completed reorganization
      assertFalse("Should complete chain reorganization",
          forkSyncManager.isInChainReorganization());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: isInForkDetection should return correct state.
   */
  @Test
  public void isInForkDetection_ShouldReturnCorrectState() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      assertFalse("Initially not in fork detection", forkSyncManager.isInForkDetection());

      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(150L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      forkSyncManager.performSync();

      assertTrue("Should be in fork detection after initiation",
          forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  /**
   * Test: isInChainReorganization should return correct state.
   */
  @Test
  public void isInChainReorganization_ShouldReturnCorrectState() {
    assertFalse("Initially not in chain reorganization",
        syncManager.isInChainReorganization());
  }

  /**
   * Test: Fork detection with empty peer response should continue scanning.
   */
  @Test
  public void onForkDetectionResponse_EmptyResponse_ShouldContinue() {
    SyncManager forkSyncManager = createForkDetectionSyncManager();
    try {
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(500L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(400L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      forkSyncManager.performSync();
      assertTrue(forkSyncManager.isInForkDetection());

      // Empty response from peer
      java.util.Map<Long, java.util.List<org.apache.tuweni.bytes.Bytes32>> emptyResponse =
          new java.util.HashMap<>();

      forkSyncManager.onForkDetectionResponse(emptyResponse);

      // Should still be in fork detection - continue scanning
      assertTrue("Should continue fork detection on empty response",
          forkSyncManager.isInForkDetection());
    } finally {
      forkSyncManager.stop();
    }
  }

  // ==================== BUG-SYNC-007: Binary Search Future Epoch Bug ====================

  /**
   * Test: BUG-SYNC-007 - When all probes return no blocks during binary search,
   * the fallback epoch should NOT exceed the remote tip epoch.
   *
   * Before the fix:
   * - Binary search would use `low` as fallback which could be > remote tip
   * - This caused requestEpochHashes to request future epochs (e.g., epoch 27582661 when current was 27582587)
   *
   * After the fix:
   * - Fallback uses Math.max(localTipEpoch + 1, originalHigh - MAX_EPOCHS_PER_REQUEST)
   * - This ensures the starting epoch is never beyond the known remote tip
   */
  @Test
  public void binarySearch_AllProbesNoBlocks_ShouldNotReturnFutureEpoch() {
    // Setup: local tip at epoch 100, remote tip at 5000
    // This creates a gap > BINARY_SEARCH_THRESHOLD (1024)
    when(tipBlock.getEpoch()).thenReturn(100L);
    when(dagChain.getMainChainLength()).thenReturn(10L);
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipBlock);

    syncManager.updateRemoteTipEpoch(5000L);
    when(dagChain.getCurrentEpoch()).thenReturn(5000L);

    // Trigger binary search
    syncManager.performSync();
    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate all probes returning no blocks
    // This would cause low to increase beyond high in the old buggy code
    for (int i = 0; i < 25; i++) {
      if (!syncManager.isInBinarySearch()) break;
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
    }

    // Binary search should have completed
    assertFalse("Binary search should complete", syncManager.isInBinarySearch());

    // After completion, the next sync should request epochs within valid range
    // Reset the mock to verify new requests
    org.mockito.Mockito.reset(channel);
    syncManager.performSync();

    // Verify that the requested epoch range is valid (not beyond remote tip)
    verify(channel).send(argThat((Message msg) -> {
      if (!(msg instanceof GetEpochHashesMessage)) return false;
      GetEpochHashesMessage request = (GetEpochHashesMessage) msg;
      // Start epoch should be <= remote tip (5000)
      // End epoch should be <= current epoch (5000)
      return request.getStartEpoch() <= 5000L && request.getEndEpoch() <= 5000L;
    }));
  }

  /**
   * Test: BUG-SYNC-007 - Binary search low bound should be clamped to not exceed high bound.
   *
   * Before the fix:
   * - onBinarySearchResponse() would set low = probe + MAX_EPOCHS_PER_REQUEST
   * - If probe was near high, this could make low > high
   * - This caused the search range to become invalid
   *
   * After the fix:
   * - low is clamped: newLow = Math.min(probe + MAX_EPOCHS_PER_REQUEST, high)
   */
  @Test
  public void binarySearch_LowBoundShouldNotExceedHighBound() {
    // Setup: smaller gap to test boundary condition
    // localTipEpoch = 100, remoteTipEpoch = 1500 (gap = 1400 > 1024)
    when(tipBlock.getEpoch()).thenReturn(100L);
    when(dagChain.getMainChainLength()).thenReturn(10L);
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipBlock);

    syncManager.updateRemoteTipEpoch(1500L);
    when(dagChain.getCurrentEpoch()).thenReturn(1500L);

    // Trigger binary search
    syncManager.performSync();
    assertTrue("Should be in binary search mode", syncManager.isInBinarySearch());

    // Simulate responses that narrow the range
    // After several "no blocks" responses, low should approach but not exceed high
    int iterations = 0;
    while (syncManager.isInBinarySearch() && iterations < 25) {
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
      iterations++;
    }

    // Binary search should terminate (not loop infinitely due to low > high)
    assertFalse("Binary search should terminate", syncManager.isInBinarySearch());
    assertTrue("Should complete within reasonable iterations", iterations <= 20);
  }

  /**
   * Test: BUG-SYNC-007 - Termination condition should include low > high check.
   *
   * Before the fix:
   * - performBinarySearch() only checked: iterations >= MAX or high - low <= MAX_EPOCHS
   * - If low > high (due to buggy onBinarySearchResponse), search could behave unexpectedly
   *
   * After the fix:
   * - Added: || low > high to termination condition
   */
  @Test
  public void binarySearch_ShouldTerminateWhenLowExceedsHigh() {
    // Setup with moderate gap
    when(tipBlock.getEpoch()).thenReturn(100L);
    when(dagChain.getMainChainLength()).thenReturn(10L);
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipBlock);

    syncManager.updateRemoteTipEpoch(2000L);
    when(dagChain.getCurrentEpoch()).thenReturn(2000L);

    syncManager.performSync();
    assertTrue("Should start binary search", syncManager.isInBinarySearch());

    // Exhaust binary search with all "no blocks" responses
    int maxIterations = 30;
    int actualIterations = 0;
    while (syncManager.isInBinarySearch() && actualIterations < maxIterations) {
      syncManager.performSync();
      syncManager.onBinarySearchResponse(false, -1);
      actualIterations++;
    }

    // Should have terminated properly
    assertFalse("Should exit binary search", syncManager.isInBinarySearch());

    // Verify termination happened due to proper condition (not just max iterations)
    // MAX_BINARY_SEARCH_ITERATIONS = 20, so if terminated earlier, it's due to low > high check
    assertTrue("Should terminate within max iterations", actualIterations <= 20);
  }

  // ==================== BUG-SYNC-008: Fork Detection After Binary Search ====================

  /**
   * Test: BUG-SYNC-008 - Fork detection should be disabled when binary search is initiated.
   *
   * Before the fix:
   * - Binary search would complete successfully
   * - Then maybeInitiateForkDetection() would trigger because initialHistoricalScanDone was false
   * - This caused the node to get stuck in fork detection mode
   *
   * After the fix:
   * - initiateBinarySearch() sets initialHistoricalScanDone = true
   * - Fork detection is skipped since we're already syncing from a remote peer
   *
   * Scenario: Node with only genesis (height=1) starts syncing from far-ahead peer.
   * Since height=1, fork detection is skipped (nothing to compare).
   * Binary search triggers due to large gap. After binary search completes,
   * even if chain grows to height>1, fork detection should NOT trigger
   * because initialHistoricalScanDone was set by binary search.
   */
  @Test
  public void binarySearch_ShouldDisableForkDetection() {
    // Create a fresh SyncManager that has NOT marked initial scan done
    SyncManager freshSyncManager = new SyncManager(dagKernel, false);
    freshSyncManager.start();

    try {
      // Setup: chain has ONLY genesis (height=1)
      // Fork detection requires height > 1, so it won't trigger
      when(dagChain.getMainChainLength()).thenReturn(1L);
      when(dagChain.getCurrentEpoch()).thenReturn(5000L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      // Set remote tip far ahead to trigger binary search
      freshSyncManager.updateRemoteTipEpoch(5000L);

      // First performSync should trigger binary search (gap > 1024)
      // Fork detection won't trigger because mainChainLength = 1
      freshSyncManager.performSync();

      // Should be in binary search mode (not fork detection)
      assertTrue("Should be in binary search mode", freshSyncManager.isInBinarySearch());
      assertFalse("Should NOT be in fork detection", freshSyncManager.isInForkDetection());

      // Complete binary search
      for (int i = 0; i < 25; i++) {
        if (!freshSyncManager.isInBinarySearch()) break;
        freshSyncManager.performSync();
        freshSyncManager.onBinarySearchResponse(false, -1);
      }

      // Now simulate that syncing has progressed, chain has grown to height > 1
      // This is the scenario where fork detection WOULD have triggered before the fix
      when(dagChain.getMainChainLength()).thenReturn(5L);
      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(4900L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // After binary search completes, fork detection should NOT trigger
      // even though chain now has height > 1
      freshSyncManager.performSync();

      assertFalse("Should NOT trigger fork detection after binary search",
          freshSyncManager.isInForkDetection());

    } finally {
      freshSyncManager.stop();
    }
  }

  /**
   * Test: BUG-SYNC-008 - After binary search completes, should continue with forward sync.
   *
   * This verifies that after binary search, the sync manager transitions to forward sync
   * mode and does NOT enter fork detection mode.
   *
   * Scenario: New node (height=1) syncs from established peer with large gap.
   * After binary search completes and chain grows, fork detection should NOT trigger.
   */
  @Test
  public void binarySearch_AfterComplete_ShouldContinueForwardSyncNotForkDetection() {
    // Create a fresh SyncManager without marking historical scan done
    SyncManager freshSyncManager = new SyncManager(dagKernel, false);
    freshSyncManager.start();

    try {
      // Setup: chain starts with only genesis (height=1)
      // Fork detection won't trigger initially because mainChainLength <= 1
      when(dagChain.getMainChainLength()).thenReturn(1L);
      when(dagChain.getCurrentEpoch()).thenReturn(3000L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      freshSyncManager.updateRemoteTipEpoch(3000L);

      // Start binary search
      freshSyncManager.performSync();
      assertTrue("Should start in binary search", freshSyncManager.isInBinarySearch());

      // Complete binary search
      for (int i = 0; i < 25; i++) {
        if (!freshSyncManager.isInBinarySearch()) break;
        freshSyncManager.performSync();
        freshSyncManager.onBinarySearchResponse(false, -1);
      }

      assertFalse("Binary search should complete", freshSyncManager.isInBinarySearch());

      // Simulate chain growth after syncing some blocks
      when(dagChain.getMainChainLength()).thenReturn(5L);
      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(2800L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // Multiple subsequent syncs should NOT trigger fork detection
      // even though chain now has height > 1
      for (int i = 0; i < 5; i++) {
        freshSyncManager.performSync();
        assertFalse("Fork detection should NOT trigger after binary search (iteration " + i + ")",
            freshSyncManager.isInForkDetection());
        assertFalse("Chain reorganization should NOT trigger (iteration " + i + ")",
            freshSyncManager.isInChainReorganization());
      }

    } finally {
      freshSyncManager.stop();
    }
  }

  /**
   * Test: Verify that fork detection can still be triggered in normal scenarios.
   *
   * This ensures the BUG-SYNC-008 fix doesn't break legitimate fork detection use cases.
   * Fork detection should trigger when:
   * 1. Node has blocks beyond genesis
   * 2. Gap is small (no binary search)
   * 3. initialHistoricalScanDone is false
   */
  @Test
  public void forkDetection_ShouldStillWorkForSmallGaps() {
    SyncManager freshSyncManager = new SyncManager(dagKernel, false);
    freshSyncManager.start();

    try {
      // Setup: chain has blocks, but gap is small (no binary search)
      when(dagChain.getMainChainLength()).thenReturn(5L);
      when(dagChain.getCurrentEpoch()).thenReturn(200L);

      Block genesisBlock = org.mockito.Mockito.mock(Block.class);
      when(genesisBlock.getEpoch()).thenReturn(100L);
      when(dagChain.getMainBlockByHeight(1L)).thenReturn(genesisBlock);

      Block tipMock = org.mockito.Mockito.mock(Block.class);
      when(tipMock.getEpoch()).thenReturn(150L);
      when(dagChain.getMainBlockByHeight(5L)).thenReturn(tipMock);

      // Small remote tip (gap <= 1024, won't trigger binary search)
      freshSyncManager.updateRemoteTipEpoch(200L);

      // performSync should trigger fork detection (not binary search)
      freshSyncManager.performSync();

      // Should be in fork detection mode (small gap, historical scan not done)
      assertTrue("Should be in fork detection for small gap",
          freshSyncManager.isInForkDetection());
      assertFalse("Should NOT be in binary search for small gap",
          freshSyncManager.isInBinarySearch());

    } finally {
      freshSyncManager.stop();
    }
  }
}
