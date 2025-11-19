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

import io.xdag.core.Block;
import io.xdag.core.Link;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.p2p.message.SyncBlocksReplyMessage;
import io.xdag.p2p.message.SyncBlocksRequestMessage;
import io.xdag.p2p.message.SyncEpochBlocksReplyMessage;
import io.xdag.p2p.message.SyncEpochBlocksRequestMessage;
import io.xdag.p2p.message.SyncHeightReplyMessage;
import io.xdag.p2p.message.SyncHeightRequestMessage;
import io.xdag.p2p.message.SyncMainBlocksReplyMessage;
import io.xdag.p2p.message.SyncMainBlocksRequestMessage;
import io.xdag.p2p.message.SyncTransactionsReplyMessage;
import io.xdag.p2p.message.SyncTransactionsRequestMessage;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for Hybrid Sync Protocol messages (Phase 1.5)
 *
 * Tests message serialization/deserialization for all 10 new P2P messages:
 * - SyncHeightRequestMessage / SyncHeightReplyMessage
 * - SyncMainBlocksRequestMessage / SyncMainBlocksReplyMessage
 * - SyncEpochBlocksRequestMessage / SyncEpochBlocksReplyMessage
 * - SyncBlocksRequestMessage / SyncBlocksReplyMessage
 * - SyncTransactionsRequestMessage / SyncTransactionsReplyMessage
 */
public class HybridSyncMessagesTest {

    // ========== Test Data Helpers ==========

    /**
     * Create a test block with deterministic data
     */
    private Block createTestBlock(int index) {
        // Create a minimal test block for testing
        long timestamp = 1000000 + index * 64L;
        UInt256 difficulty = UInt256.valueOf(1000);
        Bytes32 nonce = Bytes32.random();
        Bytes coinbase = Bytes.random(20);
        List<Link> links = new ArrayList<>();

        // Add a test block link
        links.add(Link.toBlock(Bytes32.random()));

        return Block.createWithNonce(timestamp, difficulty, nonce, coinbase, links);
    }

    /**
     * Create a test transaction with deterministic data
     */
    private Transaction createTestTransaction(int index) {
        Bytes from = Bytes.random(20);
        Bytes to = Bytes.random(20);
        XAmount amount = XAmount.ofXAmount(1000000L + index);
        long nonce = index;
        XAmount fee = XAmount.ofXAmount(1000L);

        return Transaction.builder()
            .from(from)
            .to(to)
            .amount(amount)
            .nonce(nonce)
            .fee(fee)
            .data(Bytes.EMPTY)
            .build();
    }

    // ========== Height Query Messages Tests ==========

    @Test
    public void testSyncHeightRequestMessage_Serialization() {
        // Create message
        SyncHeightRequestMessage request = new SyncHeightRequestMessage();

        // Serialize
        byte[] body = request.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Empty message should have 0 bytes", 0, body.length);

        // Deserialize
        SyncHeightRequestMessage decoded = new SyncHeightRequestMessage(body);
        assertNotNull("Decoded message should not be null", decoded);

        // Verify response class
        assertEquals("Should expect SyncHeightReplyMessage as response",
            SyncHeightReplyMessage.class, request.getResponseMessageClass());
    }

    @Test
    public void testSyncHeightReplyMessage_Serialization() {
        // Create message with test data
        long mainHeight = 1000000L;
        long finalizedHeight = 983616L; // mainHeight - 16384
        Bytes32 mainBlockHash = Bytes32.random();

        SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
            mainHeight, finalizedHeight, mainBlockHash);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 48 bytes (8+8+32)", 48, body.length);

        // Deserialize
        SyncHeightReplyMessage decoded = new SyncHeightReplyMessage(body);

        // Verify fields
        assertEquals("Main height mismatch", mainHeight, decoded.getMainHeight());
        assertEquals("Finalized height mismatch", finalizedHeight, decoded.getFinalizedHeight());
        assertEquals("Main block hash mismatch", mainBlockHash, decoded.getMainBlockHash());
    }

    // ========== Main Blocks Messages Tests ==========

    @Test
    public void testSyncMainBlocksRequestMessage_Serialization() {
        // Create message with test data
        long fromHeight = 1000L;
        long toHeight = 2000L;
        int maxBlocks = 1000;
        boolean isRaw = false;

        SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(
            fromHeight, toHeight, maxBlocks, isRaw);

        // Serialize
        byte[] body = request.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 21 bytes (8+8+4+1)", 21, body.length);

        // Deserialize
        SyncMainBlocksRequestMessage decoded = new SyncMainBlocksRequestMessage(body);

        // Verify fields
        assertEquals("From height mismatch", fromHeight, decoded.getFromHeight());
        assertEquals("To height mismatch", toHeight, decoded.getToHeight());
        assertEquals("Max blocks mismatch", maxBlocks, decoded.getMaxBlocks());
        assertEquals("IsRaw mismatch", isRaw, decoded.isRaw());

        // Verify response class
        assertEquals("Should expect SyncMainBlocksReplyMessage as response",
            SyncMainBlocksReplyMessage.class, request.getResponseMessageClass());
    }

    @Test
    public void testSyncMainBlocksReplyMessage_WithBlocks() {
        // Create message with test blocks
        List<Block> blocks = new ArrayList<>();
        blocks.add(createTestBlock(0));
        blocks.add(createTestBlock(1));
        blocks.add(null); // Missing block
        blocks.add(createTestBlock(3));

        SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(blocks);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);
        assertTrue("Body should have content", body.length > 4);

        // Deserialize
        SyncMainBlocksReplyMessage decoded = new SyncMainBlocksReplyMessage(body);

        // Verify block count
        assertEquals("Block count mismatch", 4, decoded.getBlocks().size());

        // Verify blocks
        assertNotNull("Block 0 should exist", decoded.getBlocks().get(0));
        assertNotNull("Block 1 should exist", decoded.getBlocks().get(1));
        assertNull("Block 2 should be null", decoded.getBlocks().get(2));
        assertNotNull("Block 3 should exist", decoded.getBlocks().get(3));
    }

    @Test
    public void testSyncMainBlocksReplyMessage_EmptyList() {
        // Create message with empty list
        List<Block> blocks = new ArrayList<>();

        SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(blocks);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);

        // Deserialize
        SyncMainBlocksReplyMessage decoded = new SyncMainBlocksReplyMessage(body);

        // Verify empty list
        assertEquals("Should have empty block list", 0, decoded.getBlocks().size());
    }

    // ========== Epoch Blocks Messages Tests ==========

    @Test
    public void testSyncEpochBlocksRequestMessage_Serialization() {
        // Create message with epoch range
        long startEpoch = 50000L;
        long endEpoch = 50099L;

        SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(startEpoch, endEpoch);

        // Serialize
        byte[] body = request.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 16 bytes (8+8)", 16, body.length);

        // Deserialize
        SyncEpochBlocksRequestMessage decoded = new SyncEpochBlocksRequestMessage(body);

        // Verify fields
        assertEquals("Start epoch mismatch", startEpoch, decoded.getStartEpoch());
        assertEquals("End epoch mismatch", endEpoch, decoded.getEndEpoch());

        // Verify response class
        assertEquals("Should expect SyncEpochBlocksReplyMessage as response",
            SyncEpochBlocksReplyMessage.class, request.getResponseMessageClass());
    }

    @Test
    public void testSyncEpochBlocksReplyMessage_WithHashes() {
        // Create message with test hashes in multiple epochs
        java.util.Map<Long, List<Bytes32>> epochBlocksMap = new java.util.HashMap<>();

        // Epoch 50000 with 2 blocks
        List<Bytes32> hashes1 = new ArrayList<>();
        hashes1.add(Bytes32.random());
        hashes1.add(Bytes32.random());
        epochBlocksMap.put(50000L, hashes1);

        // Epoch 50005 with 1 block
        List<Bytes32> hashes2 = new ArrayList<>();
        hashes2.add(Bytes32.random());
        epochBlocksMap.put(50005L, hashes2);

        SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(epochBlocksMap);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);
        // Expected: 4 bytes (epoch count) + 2 epochs * (8 + 4 + N*32)
        // Epoch 50000: 8 (epoch) + 4 (count) + 2*32 (hashes) = 76
        // Epoch 50005: 8 (epoch) + 4 (count) + 1*32 (hashes) = 44
        // Total: 4 + 76 + 44 = 124
        assertEquals("Should be 124 bytes", 124, body.length);

        // Deserialize
        SyncEpochBlocksReplyMessage decoded = new SyncEpochBlocksReplyMessage(body);

        // Verify fields
        assertEquals("Should have 2 epochs", 2, decoded.getEpochBlocksMap().size());
        assertTrue("Should contain epoch 50000", decoded.getEpochBlocksMap().containsKey(50000L));
        assertTrue("Should contain epoch 50005", decoded.getEpochBlocksMap().containsKey(50005L));
        assertEquals("Epoch 50000 should have 2 hashes", 2, decoded.getEpochBlocksMap().get(50000L).size());
        assertEquals("Epoch 50005 should have 1 hash", 1, decoded.getEpochBlocksMap().get(50005L).size());
    }

    @Test
    public void testSyncEpochBlocksReplyMessage_EmptyHashes() {
        // Create message with empty map
        java.util.Map<Long, List<Bytes32>> epochBlocksMap = new java.util.HashMap<>();

        SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(epochBlocksMap);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 4 bytes (epoch count=0)", 4, body.length);

        // Deserialize
        SyncEpochBlocksReplyMessage decoded = new SyncEpochBlocksReplyMessage(body);

        // Verify empty map
        assertEquals("Should have empty epoch map", 0, decoded.getEpochBlocksMap().size());
    }

    // ========== Blocks Batch Messages Tests ==========

    @Test
    public void testSyncBlocksRequestMessage_Serialization() {
        // Create message with test hashes
        List<Bytes32> hashes = new ArrayList<>();
        hashes.add(Bytes32.random());
        hashes.add(Bytes32.random());
        boolean isRaw = true;

        SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(hashes, isRaw);

        // Serialize
        byte[] body = request.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 69 bytes (4+32*2+1)", 4 + 32 * 2 + 1, body.length);

        // Deserialize
        SyncBlocksRequestMessage decoded = new SyncBlocksRequestMessage(body);

        // Verify fields
        assertEquals("Hash count mismatch", 2, decoded.getHashes().size());
        assertEquals("IsRaw mismatch", isRaw, decoded.isRaw());

        // Verify hashes
        for (int i = 0; i < 2; i++) {
            assertEquals("Hash " + i + " mismatch",
                hashes.get(i), decoded.getHashes().get(i));
        }

        // Verify response class
        assertEquals("Should expect SyncBlocksReplyMessage as response",
            SyncBlocksReplyMessage.class, request.getResponseMessageClass());
    }

    @Test
    public void testSyncBlocksReplyMessage_WithBlocks() {
        // Create message with test blocks
        List<Block> blocks = new ArrayList<>();
        blocks.add(createTestBlock(0));
        blocks.add(null); // Missing block
        blocks.add(createTestBlock(2));

        SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(blocks);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);

        // Deserialize
        SyncBlocksReplyMessage decoded = new SyncBlocksReplyMessage(body);

        // Verify block count
        assertEquals("Block count mismatch", 3, decoded.getBlocks().size());

        // Verify blocks
        assertNotNull("Block 0 should exist", decoded.getBlocks().get(0));
        assertNull("Block 1 should be null", decoded.getBlocks().get(1));
        assertNotNull("Block 2 should exist", decoded.getBlocks().get(2));
    }

    // ========== Transactions Batch Messages Tests ==========

    @Test
    public void testSyncTransactionsRequestMessage_Serialization() {
        // Create message with test hashes
        List<Bytes32> hashes = new ArrayList<>();
        hashes.add(Bytes32.random());
        hashes.add(Bytes32.random());
        hashes.add(Bytes32.random());

        SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(hashes);

        // Serialize
        byte[] body = request.getBody();
        assertNotNull("Body should not be null", body);
        assertEquals("Should be 100 bytes (4+32*3)", 4 + 32 * 3, body.length);

        // Deserialize
        SyncTransactionsRequestMessage decoded = new SyncTransactionsRequestMessage(body);

        // Verify fields
        assertEquals("Hash count mismatch", 3, decoded.getHashes().size());

        // Verify hashes
        for (int i = 0; i < 3; i++) {
            assertEquals("Hash " + i + " mismatch",
                hashes.get(i), decoded.getHashes().get(i));
        }

        // Verify response class
        assertEquals("Should expect SyncTransactionsReplyMessage as response",
            SyncTransactionsReplyMessage.class, request.getResponseMessageClass());
    }

    @Test
    public void testSyncTransactionsReplyMessage_WithTransactions() {
        // Create message with test transactions
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTestTransaction(0));
        transactions.add(null); // Missing transaction
        transactions.add(createTestTransaction(2));
        transactions.add(createTestTransaction(3));

        SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(transactions);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);

        // Deserialize
        SyncTransactionsReplyMessage decoded = new SyncTransactionsReplyMessage(body);

        // Verify transaction count
        assertEquals("Transaction count mismatch", 4, decoded.getTransactions().size());

        // Verify transactions
        assertNotNull("Transaction 0 should exist", decoded.getTransactions().get(0));
        assertNull("Transaction 1 should be null", decoded.getTransactions().get(1));
        assertNotNull("Transaction 2 should exist", decoded.getTransactions().get(2));
        assertNotNull("Transaction 3 should exist", decoded.getTransactions().get(3));
    }

    @Test
    public void testSyncTransactionsReplyMessage_EmptyList() {
        // Create message with empty list
        List<Transaction> transactions = new ArrayList<>();

        SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(transactions);

        // Serialize
        byte[] body = reply.getBody();
        assertNotNull("Body should not be null", body);

        // Deserialize
        SyncTransactionsReplyMessage decoded = new SyncTransactionsReplyMessage(body);

        // Verify empty list
        assertEquals("Should have empty transaction list", 0, decoded.getTransactions().size());
    }

    // ========== Edge Cases and Boundary Tests ==========

    @Test
    public void testSyncMainBlocksRequestMessage_MaxBatchSize() {
        // Test with maximum batch size
        long fromHeight = 0L;
        long toHeight = 9999L;
        int maxBlocks = 10000;
        boolean isRaw = true;

        SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(
            fromHeight, toHeight, maxBlocks, isRaw);

        // Serialize and deserialize
        byte[] body = request.getBody();
        SyncMainBlocksRequestMessage decoded = new SyncMainBlocksRequestMessage(body);

        // Verify
        assertEquals("Max blocks should be preserved", maxBlocks, decoded.getMaxBlocks());
    }

    @Test
    public void testSyncBlocksRequestMessage_LargeBatch() {
        // Test with large batch (100 hashes)
        List<Bytes32> hashes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hashes.add(Bytes32.random());
        }
        boolean isRaw = false;

        SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(hashes, isRaw);

        // Serialize and deserialize
        byte[] body = request.getBody();
        SyncBlocksRequestMessage decoded = new SyncBlocksRequestMessage(body);

        // Verify
        assertEquals("Hash count should match", 100, decoded.getHashes().size());
        assertEquals("IsRaw should match", isRaw, decoded.isRaw());
    }

    @Test
    public void testMessageToString() {
        // Test toString() methods for debugging/logging

        // Height request
        SyncHeightRequestMessage heightReq = new SyncHeightRequestMessage();
        assertNotNull("ToString should not be null", heightReq.toString());
        assertTrue("Should contain message name",
            heightReq.toString().contains("SyncHeightRequestMessage"));

        // Height reply
        SyncHeightReplyMessage heightReply = new SyncHeightReplyMessage(
            1000000L, 983616L, Bytes32.random());
        assertNotNull("ToString should not be null", heightReply.toString());
        assertTrue("Should contain heights", heightReply.toString().contains("1000000"));

        // Main blocks request
        SyncMainBlocksRequestMessage mainBlocksReq = new SyncMainBlocksRequestMessage(
            1000L, 2000L, 1000, false);
        assertNotNull("ToString should not be null", mainBlocksReq.toString());
        assertTrue("Should contain height range", mainBlocksReq.toString().contains("1000"));
    }
}
