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
package io.xdag.consensus.sync;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.SyncHeightReplyMessage;
import io.xdag.p2p.message.SyncHeightRequestMessage;
import io.xdag.p2p.message.SyncMainBlocksReplyMessage;
import io.xdag.p2p.message.SyncMainBlocksRequestMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for HybridSyncP2pAdapter
 *
 * <p>Focus on BUG-022 fix: Correct request-response matching with requestId
 */
public class HybridSyncP2pAdapterTest {

    private HybridSyncP2pAdapter adapter;
    private Channel mockChannel1;
    private Channel mockChannel2;

    @Before
    public void setUp() {
        adapter = new HybridSyncP2pAdapter();
        mockChannel1 = mock(Channel.class);
        mockChannel2 = mock(Channel.class);

        // Setup channel identification
        when(mockChannel1.getRemoteAddress()).thenReturn(
                new java.net.InetSocketAddress("peer1", 8001));
        when(mockChannel2.getRemoteAddress()).thenReturn(
                new java.net.InetSocketAddress("peer2", 8002));
    }

    /**
     * Test BUG-022 Fix: Concurrent requests to different peers should match correctly
     *
     * <p>Scenario:
     * 1. Send Request A to Peer 1
     * 2. Send Request B to Peer 2
     * 3. Peer 2 replies first (Reply B)
     * 4. Peer 1 replies second (Reply A)
     * 5. Verify Request A matches Reply A and Request B matches Reply B
     */
    @Test
    public void testConcurrentRequestsMatchCorrectly() throws Exception {
        // Step 1 & 2: Send concurrent requests
        CompletableFuture<SyncHeightReplyMessage> futureA = adapter.requestHeight(mockChannel1);
        CompletableFuture<SyncHeightReplyMessage> futureB = adapter.requestHeight(mockChannel2);

        assertFalse("Request A should not be completed yet", futureA.isDone());
        assertFalse("Request B should not be completed yet", futureB.isDone());

        // Capture the requestIds from sent messages
        ArgumentCaptor<SyncHeightRequestMessage> captorA =
                ArgumentCaptor.forClass(SyncHeightRequestMessage.class);
        ArgumentCaptor<SyncHeightRequestMessage> captorB =
                ArgumentCaptor.forClass(SyncHeightRequestMessage.class);

        verify(mockChannel1).send(captorA.capture());
        verify(mockChannel2).send(captorB.capture());

        String requestIdA = captorA.getValue().getRequestId();
        String requestIdB = captorB.getValue().getRequestId();

        assertNotNull("Request A should have requestId", requestIdA);
        assertNotNull("Request B should have requestId", requestIdB);
        assertNotEquals("RequestIds should be unique", requestIdA, requestIdB);

        // Step 3: Peer 2 replies first (Reply B with mainHeight=2000)
        SyncHeightReplyMessage replyB = new SyncHeightReplyMessage(
                requestIdB, 2000L, 1800L, Bytes32.random());
        adapter.onHeightReply(replyB);

        // Step 4: Peer 1 replies second (Reply A with mainHeight=1000)
        SyncHeightReplyMessage replyA = new SyncHeightReplyMessage(
                requestIdA, 1000L, 800L, Bytes32.random());
        adapter.onHeightReply(replyA);

        // Step 5: Verify correct matching
        assertTrue("Request A should be completed", futureA.isDone());
        assertTrue("Request B should be completed", futureB.isDone());

        SyncHeightReplyMessage resultA = futureA.get(100, TimeUnit.MILLISECONDS);
        SyncHeightReplyMessage resultB = futureB.get(100, TimeUnit.MILLISECONDS);

        assertEquals("Request A should receive Reply A (height=1000)",
                1000L, resultA.getMainHeight());
        assertEquals("Request B should receive Reply B (height=2000)",
                2000L, resultB.getMainHeight());

        System.out.println("✅ BUG-022 Fix Verified: Concurrent requests matched correctly");
        System.out.println("   Request A (requestId=" + requestIdA + ") → Reply A (height=1000)");
        System.out.println("   Request B (requestId=" + requestIdB + ") → Reply B (height=2000)");
    }

    /**
     * Test: Reply with unknown requestId should be dropped with warning
     */
    @Test
    public void testReplyWithUnknownRequestIdIsDropped() {
        // Send a request
        CompletableFuture<SyncHeightReplyMessage> future = adapter.requestHeight(mockChannel1);

        // Simulate reply with wrong requestId
        SyncHeightReplyMessage wrongReply = new SyncHeightReplyMessage(
                "unknown-request-id-12345", 1000L, 800L, Bytes32.random());
        adapter.onHeightReply(wrongReply);

        // Future should NOT be completed
        assertFalse("Future should not be completed by wrong requestId", future.isDone());

        System.out.println("✅ Unknown requestId correctly dropped");
    }

    /**
     * Test: Reply with null requestId should be dropped with warning
     */
    @Test
    public void testReplyWithNullRequestIdIsDropped() {
        // Send a request
        CompletableFuture<SyncHeightReplyMessage> future = adapter.requestHeight(mockChannel1);

        // Simulate reply with null requestId (using reflection to bypass constructor validation)
        SyncHeightReplyMessage replyWithNullId = new SyncHeightReplyMessage(
                "temp-id", 1000L, 800L, Bytes32.random());
        // Set requestId to null via setter
        replyWithNullId.setRequestId(null);

        adapter.onHeightReply(replyWithNullId);

        // Future should NOT be completed
        assertFalse("Future should not be completed by null requestId", future.isDone());

        System.out.println("✅ Null requestId correctly dropped");
    }

    /**
     * Test: Multiple concurrent requests to same peer are allowed
     *
     * <p>Note: HybridSyncP2pAdapter allows multiple concurrent requests to the same peer.
     * It only enforces a global capacity limit (MAX_PENDING_REQUESTS).
     */
    @Test
    public void testMultipleConcurrentRequestsToSamePeerAllowed() throws Exception {
        // First request should succeed
        CompletableFuture<SyncHeightReplyMessage> future1 = adapter.requestHeight(mockChannel1);
        assertFalse(future1.isDone());

        // Second request to same channel should also be allowed
        CompletableFuture<SyncHeightReplyMessage> future2 = adapter.requestHeight(mockChannel1);
        assertFalse("Second request should be pending", future2.isDone());

        // Capture requestIds
        ArgumentCaptor<SyncHeightRequestMessage> captor =
                ArgumentCaptor.forClass(SyncHeightRequestMessage.class);
        verify(mockChannel1, times(2)).send(captor.capture());

        String requestId1 = captor.getAllValues().get(0).getRequestId();
        String requestId2 = captor.getAllValues().get(1).getRequestId();

        assertNotEquals("RequestIds should be different", requestId1, requestId2);

        // Complete both requests
        SyncHeightReplyMessage reply1 = new SyncHeightReplyMessage(
                requestId1, 1000L, 800L, Bytes32.random());
        adapter.onHeightReply(reply1);

        SyncHeightReplyMessage reply2 = new SyncHeightReplyMessage(
                requestId2, 1001L, 801L, Bytes32.random());
        adapter.onHeightReply(reply2);

        // Both should complete successfully
        assertEquals(1000L, future1.get(100, TimeUnit.MILLISECONDS).getMainHeight());
        assertEquals(1001L, future2.get(100, TimeUnit.MILLISECONDS).getMainHeight());

        System.out.println("✅ Multiple concurrent requests to same peer correctly allowed");
    }

    /**
     * Test: Concurrent requests for different message types work correctly
     */
    @Test
    public void testConcurrentRequestsDifferentTypes() throws Exception {
        // Send height request to peer1
        CompletableFuture<SyncHeightReplyMessage> heightFuture =
                adapter.requestHeight(mockChannel1);

        // Send main blocks request to peer2
        CompletableFuture<SyncMainBlocksReplyMessage> blocksFuture =
                adapter.requestMainBlocks(mockChannel2, 1000L, 2000L, 100, false);

        // Capture requestIds
        ArgumentCaptor<SyncHeightRequestMessage> heightCaptor =
                ArgumentCaptor.forClass(SyncHeightRequestMessage.class);
        ArgumentCaptor<SyncMainBlocksRequestMessage> blocksCaptor =
                ArgumentCaptor.forClass(SyncMainBlocksRequestMessage.class);

        verify(mockChannel1).send(heightCaptor.capture());
        verify(mockChannel2).send(blocksCaptor.capture());

        String heightRequestId = heightCaptor.getValue().getRequestId();
        String blocksRequestId = blocksCaptor.getValue().getRequestId();

        // Send replies in reverse order
        SyncMainBlocksReplyMessage blocksReply = new SyncMainBlocksReplyMessage(
                blocksRequestId, new ArrayList<>());
        adapter.onMainBlocksReply(blocksReply);

        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
                heightRequestId, 1000L, 800L, Bytes32.random());
        adapter.onHeightReply(heightReply);

        // Both should complete correctly
        assertTrue("Height request should be completed", heightFuture.isDone());
        assertTrue("Blocks request should be completed", blocksFuture.isDone());

        assertNotNull("Height reply should be received", heightFuture.get());
        assertNotNull("Blocks reply should be received", blocksFuture.get());

        System.out.println("✅ Concurrent requests of different types work correctly");
    }

    /**
     * Performance test: Verify multiple sequential requests work correctly
     */
    @Test
    public void testMultipleSequentialRequests() throws Exception {
        int requestCount = 10;
        List<CompletableFuture<SyncHeightReplyMessage>> futures = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();

        // Send multiple sequential requests (to same channel, completing each before next)
        for (int i = 0; i < requestCount; i++) {
            CompletableFuture<SyncHeightReplyMessage> future = adapter.requestHeight(mockChannel1);
            futures.add(future);

            ArgumentCaptor<SyncHeightRequestMessage> captor =
                    ArgumentCaptor.forClass(SyncHeightRequestMessage.class);
            verify(mockChannel1, times(i + 1)).send(captor.capture());

            String requestId = captor.getValue().getRequestId();
            requestIds.add(requestId);

            // Complete this request before next
            SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
                    requestId, 1000L + i, 800L + i, Bytes32.random());
            adapter.onHeightReply(reply);

            assertTrue("Request " + i + " should be completed", future.isDone());
        }

        // Verify all completed with correct data
        for (int i = 0; i < requestCount; i++) {
            SyncHeightReplyMessage result = futures.get(i).get();
            assertEquals("Request " + i + " should have correct height",
                    1000L + i, result.getMainHeight());
        }

        System.out.println("✅ " + requestCount + " sequential requests completed correctly");
    }

    /**
     * Test: Verify requestId is properly passed through the protocol
     */
    @Test
    public void testRequestIdPassedThroughProtocol() {
        // Send request
        adapter.requestHeight(mockChannel1);

        // Capture sent message
        ArgumentCaptor<SyncHeightRequestMessage> captor =
                ArgumentCaptor.forClass(SyncHeightRequestMessage.class);
        verify(mockChannel1).send(captor.capture());

        SyncHeightRequestMessage sentMessage = captor.getValue();
        assertNotNull("Sent message should have requestId", sentMessage.getRequestId());
        assertFalse("RequestId should not be empty", sentMessage.getRequestId().isEmpty());

        // Verify requestId format (UUID-like)
        String requestId = sentMessage.getRequestId();
        assertTrue("RequestId should look like a UUID",
                requestId.length() >= 32 && requestId.contains("-"));

        System.out.println("✅ RequestId properly generated and passed: " + requestId);
    }
}
