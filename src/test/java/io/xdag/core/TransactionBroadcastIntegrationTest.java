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
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Integration test for transaction broadcast anti-loop mechanism (Phase 3)
 *
 * <p>Simulates multi-node network scenarios to verify:
 * <ul>
 *   <li>Transactions propagate correctly</li>
 *   <li>Loop prevention works across multiple hops</li>
 *   <li>Sender exclusion prevents immediate loops</li>
 * </ul>
 */
public class TransactionBroadcastIntegrationTest {

    private TransactionBroadcastManager nodeA;
    private TransactionBroadcastManager nodeB;
    private TransactionBroadcastManager nodeC;

    @Before
    public void setUp() {
        // Create 3 simulated nodes
        nodeA = new TransactionBroadcastManager();
        nodeB = new TransactionBroadcastManager();
        nodeC = new TransactionBroadcastManager();
    }

    @Test
    public void testSimpleAntiLoop() {
        // Scenario:
        // 1. Node A receives transaction (first time)
        // 2. Node A broadcasts to Node B
        // 3. Node B receives and tries to broadcast back to A
        // 4. Node A should reject (already seen)

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // Step 1: Node A sees transaction for first time
        assertTrue("Node A should process (first time)",
                  nodeA.shouldProcess(txHash));

        // Step 2: Node B receives from A
        assertTrue("Node B should process (first time)",
                  nodeB.shouldProcess(txHash));

        // Step 3: Node B tries to send back to A (loop attempt)
        // Node A should reject because it already saw this transaction
        assertFalse("Node A should reject (anti-loop protection)",
                   nodeA.shouldProcess(txHash));

        System.out.println("✓ Simple anti-loop test passed");
    }

    @Test
    public void testThreeNodeLoop() {
        // Scenario: A → B → C → A (loop)
        // Each node should see transaction only once

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // Node A: First to see transaction
        assertTrue("Node A: first time should process",
                  nodeA.shouldProcess(txHash));

        // Node B: Receives from A
        assertTrue("Node B: first time should process",
                  nodeB.shouldProcess(txHash));

        // Node C: Receives from B
        assertTrue("Node C: first time should process",
                  nodeC.shouldProcess(txHash));

        // Loop back to A (should be rejected)
        assertFalse("Node A: loop should be rejected",
                   nodeA.shouldProcess(txHash));

        // Try to loop to B (should also be rejected)
        assertFalse("Node B: loop should be rejected",
                   nodeB.shouldProcess(txHash));

        System.out.println("✓ Three-node loop test passed");
    }

    @Test
    public void testBroadcastPreventsDuplicates() {
        // Verify that broadcasting the same transaction twice is prevented

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // First broadcast should be allowed
        assertTrue("First broadcast should be allowed",
                  nodeA.shouldBroadcast(txHash));

        // Second broadcast should be prevented
        assertFalse("Second broadcast should be prevented",
                   nodeA.shouldBroadcast(txHash));

        System.out.println("✓ Broadcast duplicate prevention test passed");
    }

    @Test
    public void testMultipleTransactions() {
        // Verify that different transactions are handled independently

        Transaction tx1 = createTestTransaction();
        Transaction tx2 = createTestTransaction();
        Transaction tx3 = createTestTransaction();

        Bytes32 hash1 = tx1.getHash();
        Bytes32 hash2 = tx2.getHash();
        Bytes32 hash3 = tx3.getHash();

        // All transactions should be processed first time
        assertTrue("Transaction 1 should process", nodeA.shouldProcess(hash1));
        assertTrue("Transaction 2 should process", nodeA.shouldProcess(hash2));
        assertTrue("Transaction 3 should process", nodeA.shouldProcess(hash3));

        // But repeats should be rejected
        assertFalse("Transaction 1 repeat rejected", nodeA.shouldProcess(hash1));
        assertFalse("Transaction 2 repeat rejected", nodeA.shouldProcess(hash2));
        assertFalse("Transaction 3 repeat rejected", nodeA.shouldProcess(hash3));

        System.out.println("✓ Multiple transactions test passed");
    }

    @Test
    public void testCacheIsolationBetweenNodes() {
        // Verify that each node has independent cache

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // Node A sees transaction
        assertTrue("Node A should process", nodeA.shouldProcess(txHash));

        // Node B should still be able to process (independent cache)
        assertTrue("Node B should process (independent cache)",
                  nodeB.shouldProcess(txHash));

        // Node C should still be able to process
        assertTrue("Node C should process (independent cache)",
                  nodeC.shouldProcess(txHash));

        System.out.println("✓ Cache isolation test passed");
    }

    @Test
    public void testProcessAndBroadcastSeparation() {
        // Verify that process and broadcast caches are independent

        Transaction tx = createTestTransaction();
        Bytes32 txHash = tx.getHash();

        // Mark as seen (process)
        assertTrue("Should process first time", nodeA.shouldProcess(txHash));
        assertFalse("Should not process second time", nodeA.shouldProcess(txHash));

        // But should still be able to broadcast
        assertTrue("Should broadcast first time", nodeA.shouldBroadcast(txHash));
        assertFalse("Should not broadcast second time", nodeA.shouldBroadcast(txHash));

        System.out.println("✓ Process/broadcast separation test passed");
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
            (long) (Math.random() * 1000),  // Random nonce for uniqueness
            XAmount.of(1, XUnit.MILLI_XDAG)
        );

        return tx.sign(keyPair);
    }
}
