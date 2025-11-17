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

import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.p2p.P2pService;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.NewTransactionMessage;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransactionBroadcastManager (Phase 3)
 *
 * <p>Tests the anti-loop protection mechanisms:
 * <ul>
 *   <li>Recently seen cache prevents re-processing</li>
 *   <li>Recently broadcasted cache prevents re-broadcasting</li>
 *   <li>Sender exclusion logic works correctly</li>
 * </ul>
 */
public class TransactionBroadcastManagerTest {

    private TransactionBroadcastManager broadcastManager;
    private P2pService mockP2pService;
    private ChannelManager mockChannelManager;
    private Channel mockChannel1;
    private Channel mockChannel2;
    private Channel mockChannel3;

    @Before
    public void setUp() throws Exception {
        broadcastManager = new TransactionBroadcastManager();

        // Create mock P2P service, channel manager and channels
        mockP2pService = mock(P2pService.class);
        mockChannelManager = mock(ChannelManager.class);
        mockChannel1 = mock(Channel.class);
        mockChannel2 = mock(Channel.class);
        mockChannel3 = mock(Channel.class);

        // Mock remote addresses properly
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 10001);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.1", 10002);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.1", 10003);

        when(mockChannel1.getRemoteAddress()).thenReturn(addr1);
        when(mockChannel2.getRemoteAddress()).thenReturn(addr2);
        when(mockChannel3.getRemoteAddress()).thenReturn(addr3);

        // Setup channel manager to return channels map
        Map<InetSocketAddress, Channel> channels = new HashMap<>();
        channels.put(addr1, mockChannel1);
        channels.put(addr2, mockChannel2);
        channels.put(addr3, mockChannel3);

        when(mockP2pService.getChannelManager()).thenReturn(mockChannelManager);
        when(mockChannelManager.getChannels()).thenReturn(channels);

        broadcastManager.setP2pService(mockP2pService);
    }

    @Test
    public void testShouldProcessFirstTime() {
        // First time seeing a transaction should return true
        Bytes32 txHash = Bytes32.random();

        assertTrue("First time should process", broadcastManager.shouldProcess(txHash));
    }

    @Test
    public void testShouldProcessSecondTime() {
        // Second time seeing the same transaction should return false
        Bytes32 txHash = Bytes32.random();

        assertTrue("First time should process", broadcastManager.shouldProcess(txHash));
        assertFalse("Second time should NOT process (anti-loop)",
                   broadcastManager.shouldProcess(txHash));
    }

    @Test
    public void testShouldProcessDifferentTransactions() {
        // Different transactions should all be processed
        Bytes32 txHash1 = Bytes32.random();
        Bytes32 txHash2 = Bytes32.random();
        Bytes32 txHash3 = Bytes32.random();

        assertTrue("Transaction 1 should process", broadcastManager.shouldProcess(txHash1));
        assertTrue("Transaction 2 should process", broadcastManager.shouldProcess(txHash2));
        assertTrue("Transaction 3 should process", broadcastManager.shouldProcess(txHash3));

        // But repeats should not
        assertFalse("Transaction 1 repeat should NOT process",
                   broadcastManager.shouldProcess(txHash1));
        assertFalse("Transaction 2 repeat should NOT process",
                   broadcastManager.shouldProcess(txHash2));
    }

    @Test
    public void testShouldBroadcastFirstTime() {
        // First time broadcasting should return true
        Bytes32 txHash = Bytes32.random();

        assertTrue("First time should broadcast", broadcastManager.shouldBroadcast(txHash));
    }

    @Test
    public void testShouldBroadcastSecondTime() {
        // Second time broadcasting should return false
        Bytes32 txHash = Bytes32.random();

        assertTrue("First time should broadcast", broadcastManager.shouldBroadcast(txHash));
        assertFalse("Second time should NOT broadcast (prevent spam)",
                   broadcastManager.shouldBroadcast(txHash));
    }

    @Test
    public void testBroadcastTransactionToAllPeers() {
        // Create a test transaction
        Transaction tx = createTestTransaction();

        // Broadcast to all peers (no exclusion)
        broadcastManager.broadcastTransaction(tx, null);

        // Verify message sent to all 3 peers
        ArgumentCaptor<NewTransactionMessage> messageCaptor =
            ArgumentCaptor.forClass(NewTransactionMessage.class);

        verify(mockChannel1, times(1)).send(messageCaptor.capture());
        verify(mockChannel2, times(1)).send(messageCaptor.capture());
        verify(mockChannel3, times(1)).send(messageCaptor.capture());

        // Verify all messages contain the correct transaction
        List<NewTransactionMessage> sentMessages = messageCaptor.getAllValues();
        assertEquals("Should send 3 messages", 3, sentMessages.size());

        for (NewTransactionMessage msg : sentMessages) {
            assertEquals("Message should contain correct transaction hash",
                        tx.getHash(), msg.getTransaction().getHash());
        }
    }

    @Test
    public void testBroadcastTransactionExcludeSender() {
        // Create a test transaction
        Transaction tx = createTestTransaction();

        // Broadcast but exclude channel2 (simulating it's the sender)
        broadcastManager.broadcastTransaction(tx, mockChannel2);

        // Verify message sent to channel1 and channel3, but NOT channel2
        ArgumentCaptor<NewTransactionMessage> messageCaptor =
            ArgumentCaptor.forClass(NewTransactionMessage.class);

        verify(mockChannel1, times(1)).send(messageCaptor.capture());
        verify(mockChannel2, never()).send(any(NewTransactionMessage.class));  // Excluded!
        verify(mockChannel3, times(1)).send(messageCaptor.capture());

        List<NewTransactionMessage> sentMessages = messageCaptor.getAllValues();
        assertEquals("Should send 2 messages (excluded 1)", 2, sentMessages.size());
    }

    @Test
    public void testBroadcastTransactionOnlyOnce() {
        // Create a test transaction
        Transaction tx = createTestTransaction();

        // First broadcast should succeed
        broadcastManager.broadcastTransaction(tx, null);

        // Verify messages sent
        verify(mockChannel1, times(1)).send(any(NewTransactionMessage.class));
        verify(mockChannel2, times(1)).send(any(NewTransactionMessage.class));
        verify(mockChannel3, times(1)).send(any(NewTransactionMessage.class));

        // Reset mock counters
        reset(mockChannel1, mockChannel2, mockChannel3);

        // Second broadcast should be blocked (already broadcasted)
        broadcastManager.broadcastTransaction(tx, null);

        // Verify NO messages sent on second attempt
        verify(mockChannel1, never()).send(any(NewTransactionMessage.class));
        verify(mockChannel2, never()).send(any(NewTransactionMessage.class));
        verify(mockChannel3, never()).send(any(NewTransactionMessage.class));
    }

    @Test
    public void testBroadcastWithoutP2pService() {
        // Create manager without P2P service
        TransactionBroadcastManager managerWithoutP2p = new TransactionBroadcastManager();

        Transaction tx = createTestTransaction();

        // Should not throw exception, just skip broadcast
        managerWithoutP2p.broadcastTransaction(tx, null);

        // No verification needed - just ensure no exception
    }

    @Test
    public void testClearCaches() {
        // Add some entries to caches
        Bytes32 txHash1 = Bytes32.random();
        Bytes32 txHash2 = Bytes32.random();

        broadcastManager.shouldProcess(txHash1);
        broadcastManager.shouldBroadcast(txHash2);

        // Verify entries are cached
        assertFalse("Should be in cache", broadcastManager.shouldProcess(txHash1));
        assertFalse("Should be in cache", broadcastManager.shouldBroadcast(txHash2));

        // Clear caches
        broadcastManager.clear();

        // Verify caches are cleared
        assertTrue("Should process after clear", broadcastManager.shouldProcess(txHash1));
        assertTrue("Should broadcast after clear", broadcastManager.shouldBroadcast(txHash2));
    }

    @Test
    public void testGetStatistics() {
        // Add some entries
        for (int i = 0; i < 10; i++) {
            broadcastManager.shouldProcess(Bytes32.random());
            broadcastManager.shouldBroadcast(Bytes32.random());
        }

        // Get statistics
        String stats = broadcastManager.getStatistics();

        assertNotNull("Statistics should not be null", stats);
        assertTrue("Statistics should contain 'seen'", stats.contains("seen"));
        assertTrue("Statistics should contain 'broadcasted'", stats.contains("broadcasted"));
    }

    @Test
    public void testAntiLoopScenario() {
        // Simulate anti-loop scenario:
        // 1. Node receives transaction from peer A
        // 2. Node broadcasts to peers B, C
        // 3. Peer B broadcasts back to this node
        // 4. Node should reject (already seen)

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // Step 1: Receive from peer A (first time)
        assertTrue("First time should process", broadcastManager.shouldProcess(txHash));

        // Step 2: Broadcast to peers B, C (exclude A)
        broadcastManager.broadcastTransaction(tx, mockChannel1);

        verify(mockChannel2, times(1)).send(any(NewTransactionMessage.class));
        verify(mockChannel3, times(1)).send(any(NewTransactionMessage.class));

        // Step 3: Peer B broadcasts back (loop attempt)
        // This should be caught by shouldProcess()
        assertFalse("Loop attempt should be rejected",
                   broadcastManager.shouldProcess(txHash));

        // Verify anti-loop protection worked!
    }

    // ========== Helper Methods ==========

    /**
     * Create a test transaction
     */
    private Transaction createTestTransaction() {
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(
            keyPair.getPublicKey().toBytes());
        Bytes to = Bytes.random(20);

        Transaction tx = Transaction.createTransfer(
            from,
            to,
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.of(1, XUnit.MILLI_XDAG)
        );

        return tx.sign(keyPair);
    }
}
