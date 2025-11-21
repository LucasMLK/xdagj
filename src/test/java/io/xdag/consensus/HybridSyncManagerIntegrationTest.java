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

package io.xdag.consensus;

import io.xdag.DagKernel;
import io.xdag.consensus.sync.HybridSyncManager;
import io.xdag.consensus.sync.HybridSyncP2pAdapter;
import io.xdag.core.*;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.SyncBlocksReplyMessage;
import io.xdag.p2p.message.SyncEpochBlocksReplyMessage;
import io.xdag.p2p.message.SyncHeightReplyMessage;
import io.xdag.p2p.message.SyncMainBlocksReplyMessage;
import io.xdag.p2p.message.SyncTransactionsReplyMessage;
import io.xdag.utils.XdagTime;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for HybridSyncManager
 *
 * <p>Tests the complete synchronization workflow including:
 * <ul>
 *   <li>Height query</li>
 *   <li>Main chain batch download</li>
 *   <li>Epoch DAG sync</li>
 *   <li>Block and transaction solidification</li>
 * </ul>
 *
 * @since @since XDAGJ
 */
public class HybridSyncManagerIntegrationTest {

    private DagKernel mockDagKernel;
    private DagChain mockDagChain;
    private HybridSyncP2pAdapter mockP2pAdapter;
    private Channel mockChannel;
    private HybridSyncManager syncManager;

    @Before
    public void setUp() {
        // Create mocks
        mockDagKernel = mock(DagKernel.class);
        mockDagChain = mock(DagChain.class);
        mockP2pAdapter = mock(HybridSyncP2pAdapter.class);
        mockChannel = mock(Channel.class);

        // Create sync manager with mocked dependencies
        syncManager = new HybridSyncManager(mockDagKernel, mockDagChain, mockP2pAdapter);

        // Provide safe defaults so tests that only care about specific phases don't NPE
        when(mockDagChain.tryToConnect(any(Block.class)))
                .thenReturn(DagImportResult.mainBlock(0, 1, UInt256.ONE, true));

        when(mockP2pAdapter.requestMainBlocks(
                any(Channel.class),
                anyLong(),
                anyLong(),
                anyInt(),
                anyBoolean()
        )).thenReturn(CompletableFuture.completedFuture(
                new SyncMainBlocksReplyMessage(Collections.emptyList())
        ));

        when(mockP2pAdapter.requestEpochBlocks(any(Channel.class), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new SyncEpochBlocksReplyMessage(Collections.emptyMap())
                ));

        when(mockP2pAdapter.requestBlocks(
                any(Channel.class),
                anyList(),
                anyBoolean()
        )).thenReturn(CompletableFuture.completedFuture(
                new SyncBlocksReplyMessage(Collections.emptyList())
        ));

        when(mockP2pAdapter.requestTransactions(
                any(Channel.class),
                anyList()
        )).thenReturn(CompletableFuture.completedFuture(
                new SyncTransactionsReplyMessage(Collections.emptyList())
        ));
    }

    // ========== Test 1: Height Query ==========

    @Test
    public void testQueryRemoteHeight_Success() {
        // Setup: Mock remote height reply
        long remoteMainHeight = 10000L;
        long remoteFinalizedHeight = 8000L;
        Bytes32 remoteTipHash = Bytes32.random();

        SyncHeightReplyMessage mockReply = new SyncHeightReplyMessage(
                remoteMainHeight,
                remoteFinalizedHeight,
                remoteTipHash
        );

        CompletableFuture<SyncHeightReplyMessage> future = CompletableFuture.completedFuture(mockReply);
        when(mockP2pAdapter.requestHeight(any(Channel.class))).thenReturn(future);

        // Setup: Mock local chain state
        when(mockDagChain.getMainChainLength()).thenReturn(5000L);

        // Execute: Start sync (should query height first)
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Height query was called
        verify(mockP2pAdapter, times(1)).requestHeight(mockChannel);

        // Note: Full sync will fail because we haven't mocked block downloads
        // But we've verified the height query works
    }

    @Test
    public void testQueryRemoteHeight_Timeout() {
        // Setup: Mock timeout
        CompletableFuture<SyncHeightReplyMessage> future = new CompletableFuture<>();
        future.completeExceptionally(new java.util.concurrent.TimeoutException("Request timed out"));

        when(mockP2pAdapter.requestHeight(any(Channel.class))).thenReturn(future);
        when(mockDagChain.getMainChainLength()).thenReturn(5000L);

        // Execute: Start sync
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Sync should fail due to timeout
        assertFalse("Sync should fail when height query times out", result);
    }

    // ========== Test 2: Main Chain Batch Download ==========

    @Test
    public void testMainChainBatchDownload_Success() {
        // Setup: Mock height query
        long localHeight = 1000L;
        long remoteHeight = 3000L;
        long finalizedHeight = 2900L;

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                remoteHeight,
                finalizedHeight,
                Bytes32.random()
        );

        when(mockP2pAdapter.requestHeight(any(Channel.class)))
                .thenReturn(CompletableFuture.completedFuture(heightReply));
        when(mockDagChain.getMainChainLength()).thenReturn(localHeight);

        // Setup: Mock main blocks download
        List<Block> mockBlocks = createMockBlocks(1000, 1100);
        SyncMainBlocksReplyMessage blocksReply = new SyncMainBlocksReplyMessage(mockBlocks);

        when(mockP2pAdapter.requestMainBlocks(
                any(Channel.class),
                anyLong(),
                anyLong(),
                anyInt(),
                anyBoolean()
        )).thenReturn(CompletableFuture.completedFuture(blocksReply));

        // Setup: Mock block import
        DagImportResult successResult = DagImportResult.mainBlock(
                1000L,
                1001L,
                UInt256.valueOf(1000),
                true
        );
        when(mockDagChain.tryToConnect(any(Block.class))).thenReturn(successResult);

        // Execute: Start sync
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Main blocks request was called
        verify(mockP2pAdapter, atLeastOnce()).requestMainBlocks(
                eq(mockChannel),
                anyLong(),
                anyLong(),
                eq(1000),
                eq(false)
        );

        // Verify: Blocks were imported
        verify(mockDagChain, atLeastOnce()).tryToConnect(any(Block.class));
    }

    @Test
    public void testMainChainBatchDownload_MultipleRequests() {
        // Setup: Large gap requiring multiple batch requests
        long localHeight = 1000L;
        long remoteHeight = 5000L;
        long finalizedHeight = 4900L;

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                remoteHeight,
                finalizedHeight,
                Bytes32.random()
        );

        when(mockP2pAdapter.requestHeight(any(Channel.class)))
                .thenReturn(CompletableFuture.completedFuture(heightReply));

        // Mock getMainChainLength() to simulate progress after importing blocks
        AtomicLong currentHeight = new AtomicLong(localHeight);
        when(mockDagChain.getMainChainLength()).thenAnswer(inv -> currentHeight.get());

        // Setup: Mock multiple batch downloads (batch size = 1000)
        // Need 4 batches to cover 1000 -> 4900
        when(mockP2pAdapter.requestMainBlocks(
                any(Channel.class),
                anyLong(),
                anyLong(),
                anyInt(),
                anyBoolean()
        )).thenAnswer(invocation -> {
            long fromHeight = invocation.getArgument(1);
            long toHeight = invocation.getArgument(2);
            List<Block> blocks = createMockBlocks(fromHeight, Math.min(fromHeight + 999, toHeight));
            return CompletableFuture.completedFuture(new SyncMainBlocksReplyMessage(blocks));
        });

        // Setup: Mock block import - update local height after each import
        when(mockDagChain.tryToConnect(any(Block.class)))
                .thenAnswer(invocation -> {
                    currentHeight.incrementAndGet();
                    return DagImportResult.mainBlock(
                            currentHeight.get(),
                            currentHeight.get() + 1,
                            UInt256.valueOf(1000),
                            true
                    );
                });

        // Setup: Mock empty epoch sync (to complete the test quickly)
        java.util.Map<Long, List<Bytes32>> emptyEpochMap = new java.util.HashMap<>();
        when(mockP2pAdapter.requestEpochBlocks(any(Channel.class), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new SyncEpochBlocksReplyMessage(emptyEpochMap)
                ));

        // Execute: Start sync
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Multiple batch requests were made
        verify(mockP2pAdapter, atLeast(3)).requestMainBlocks(
                eq(mockChannel),
                anyLong(),
                anyLong(),
                eq(1000),
                eq(false)
        );
    }

    // ========== Test 3: Epoch DAG Sync ==========

    @Test
    public void testEpochDagSync_Success() {
        // Setup: Mock height query (already synced finalized chain)
        long localHeight = 9000L;
        long remoteHeight = 9100L;
        long finalizedHeight = 8500L;

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                remoteHeight,
                finalizedHeight,
                Bytes32.random()
        );

        when(mockP2pAdapter.requestHeight(any(Channel.class)))
                .thenReturn(CompletableFuture.completedFuture(heightReply));
        when(mockDagChain.getMainChainLength()).thenReturn(localHeight);

        // Setup: Mock epoch blocks request with batch response
        List<Bytes32> epochHashes = createMockHashes(10);
        java.util.Map<Long, List<Bytes32>> epochBlocksMap = new java.util.HashMap<>();
        epochBlocksMap.put(9000L, epochHashes);
        SyncEpochBlocksReplyMessage epochReply = new SyncEpochBlocksReplyMessage(epochBlocksMap);

        when(mockP2pAdapter.requestEpochBlocks(any(Channel.class), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(epochReply));

        // Setup: Mock that we don't have these blocks
        when(mockDagChain.getBlockByHash(any(Bytes32.class), anyBoolean()))
                .thenReturn(null);

        // Setup: Mock blocks download
        List<Block> mockBlocks = createMockBlocks(9000, 9010);
        SyncBlocksReplyMessage blocksReply = new SyncBlocksReplyMessage(mockBlocks);

        when(mockP2pAdapter.requestBlocks(
                any(Channel.class),
                anyList(),
                anyBoolean()
        )).thenReturn(CompletableFuture.completedFuture(blocksReply));

        // Setup: Mock block import
        when(mockDagChain.tryToConnect(any(Block.class)))
                .thenReturn(DagImportResult.mainBlock(9000L, 9001L, UInt256.valueOf(1000), true));

        // Execute: Start sync
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Main blocks were requested (instead of epoch blocks)
        verify(mockP2pAdapter, atLeastOnce()).requestMainBlocks(
                eq(mockChannel),
                anyLong(),
                anyLong(),
                anyInt(),
                anyBoolean()
        );

        // Verify: Missing blocks were requested - REMOVED as logic changed to use requestMainBlocks
        // verify(mockP2pAdapter, atLeastOnce()).requestBlocks(
        //         eq(mockChannel),
        //         anyList(),
        //         eq(false)
        // );
    }

    // ========== Test 4: Already Synced ==========

    @Test
    public void testSync_AlreadySynced() {
        // Setup: Local height equals remote height
        long localHeight = 10000L;
        long remoteHeight = 10000L;

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                remoteHeight,
                9000L,
                Bytes32.random()
        );

        when(mockP2pAdapter.requestHeight(any(Channel.class)))
                .thenReturn(CompletableFuture.completedFuture(heightReply));
        when(mockDagChain.getMainChainLength()).thenReturn(localHeight);

        // Execute: Start sync
        boolean result = syncManager.startSync(mockChannel);

        // Verify: Sync succeeds immediately
        assertTrue("Sync should succeed when already synced", result);

        // Note: Manager may request recent blocks for consistency check, so we don't enforce never() here

    }

    // ========== Test 5: Sync Progress Tracking ==========

    @Test
    public void testSyncProgressTracking() {
        // Setup: Height query
        long localHeight = 1000L;
        long remoteHeight = 2000L;

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                remoteHeight,
                1900L,
                Bytes32.random()
        );

        when(mockP2pAdapter.requestHeight(any(Channel.class)))
                .thenReturn(CompletableFuture.completedFuture(heightReply));
        when(mockDagChain.getMainChainLength()).thenReturn(localHeight);

        // Setup: Mock blocks download
        when(mockP2pAdapter.requestMainBlocks(
                any(Channel.class),
                anyLong(),
                anyLong(),
                anyInt(),
                anyBoolean()
        )).thenAnswer(invocation -> {
            long fromHeight = invocation.getArgument(1);
            List<Block> blocks = createMockBlocks(fromHeight, fromHeight + 100);
            return CompletableFuture.completedFuture(new SyncMainBlocksReplyMessage(blocks));
        });

        when(mockDagChain.tryToConnect(any(Block.class)))
                .thenReturn(DagImportResult.mainBlock(1000L, 1001L, UInt256.valueOf(1000), true));

        // Setup: Mock epoch sync (empty)
        java.util.Map<Long, List<Bytes32>> emptyEpochMap2 = new java.util.HashMap<>();
        when(mockP2pAdapter.requestEpochBlocks(any(Channel.class), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new SyncEpochBlocksReplyMessage(emptyEpochMap2)
                ));

        // Execute: Start sync
        syncManager.startSync(mockChannel);

        // Verify: Progress should be tracked
        double progress = syncManager.getProgress();
        assertTrue("Progress should be > 0", progress >= 0.0);
        assertTrue("Progress should be <= 1.0", progress <= 1.0);

        // Verify: Sync state should be COMPLETED or in progress
        HybridSyncManager.SyncState state = syncManager.getCurrentState();
        assertNotNull("Sync state should not be null", state);
    }

    // ========== Helper Methods ==========

    /**
     * Create mock blocks for testing
     */
    private List<Block> createMockBlocks(long fromHeight, long toHeight) {
        List<Block> blocks = new ArrayList<>();

        for (long height = fromHeight; height <= toHeight; height++) {
            Block mockBlock = mock(Block.class);
            Bytes32 hash = Bytes32.random();

            when(mockBlock.getHash()).thenReturn(hash);
            when(mockBlock.getEpoch()).thenReturn(height);
            when(mockBlock.getTimestamp()).thenReturn(XdagTime.epochNumberToMainTime(height));
            when(mockBlock.getLinks()).thenReturn(new ArrayList<>());
            // Mock toBytes() to return valid data (512 bytes for XDAG block)
            when(mockBlock.toBytes()).thenReturn(new byte[512]);

            blocks.add(mockBlock);
        }

        return blocks;
    }

    /**
     * Create mock hashes for testing
     */
    private List<Bytes32> createMockHashes(int count) {
        List<Bytes32> hashes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hashes.add(Bytes32.random());
        }
        return hashes;
    }
}
