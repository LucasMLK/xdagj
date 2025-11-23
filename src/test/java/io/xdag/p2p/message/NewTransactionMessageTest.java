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
package io.xdag.p2p.message;

import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.keys.ECKeyPair;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for NewTransactionMessage (Phase 3)
 *
 * <p>Tests message serialization/deserialization for P2P transaction broadcasting
 */
public class NewTransactionMessageTest {

    @Test
    public void testMessageCreationFromTransaction() {
        // Create a test transaction
        Transaction tx = createTestTransaction();

        // Create message from transaction
        NewTransactionMessage message = new NewTransactionMessage(tx);

        // Verify message properties
        assertNotNull("Message should not be null", message);
        assertEquals("Message should contain correct transaction",
                    tx.getHash(), message.getTransaction().getHash());
        assertNotNull("Message body should not be null", message.getBody());
        assertTrue("Message body should not be empty", message.getBody().length > 0);
    }

    @Test
    public void testMessageDeserialization() {
        // Create original transaction
        Transaction originalTx = createTestTransaction();

        // Serialize to message
        NewTransactionMessage originalMessage = new NewTransactionMessage(originalTx);
        byte[] serializedBody = originalMessage.getBody();

        // Deserialize from body
        NewTransactionMessage deserializedMessage = new NewTransactionMessage(serializedBody);

        // Verify deserialized transaction matches original
        Transaction deserializedTx = deserializedMessage.getTransaction();

        assertEquals("Hash should match",
                    originalTx.getHash(), deserializedTx.getHash());
        assertEquals("From address should match",
                    originalTx.getFrom(), deserializedTx.getFrom());
        assertEquals("To address should match",
                    originalTx.getTo(), deserializedTx.getTo());
        assertEquals("Amount should match",
                    originalTx.getAmount(), deserializedTx.getAmount());
        assertEquals("Nonce should match",
                    originalTx.getNonce(), deserializedTx.getNonce());
        assertEquals("Fee should match",
                    originalTx.getFee(), deserializedTx.getFee());
    }

    @Test
    public void testMessageSignaturePreservation() {
        // Create and sign transaction
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(
            keyPair.getPublicKey().toBytes());

        Transaction tx = Transaction.createTransfer(
            from,
            Bytes.random(20),
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.of(1, XUnit.MILLI_XDAG),
            1L
        );

        Transaction signedTx = tx.sign(keyPair);

        // Verify original signature is valid
        assertTrue("Original signature should be valid",
                  signedTx.verifySignature());

        // Serialize and deserialize
        NewTransactionMessage message = new NewTransactionMessage(signedTx);
        NewTransactionMessage deserializedMessage = new NewTransactionMessage(message.getBody());

        // Verify deserialized signature is still valid
        assertTrue("Deserialized signature should be valid",
                  deserializedMessage.getTransaction().verifySignature());
    }

    @Test
    public void testMessageSize() {
        // Create transaction
        Transaction tx = createTestTransaction();

        // Create message
        NewTransactionMessage message = new NewTransactionMessage(tx);

        // Verify message size is reasonable
        int messageSize = message.getBody().length;

        assertTrue("Message size should be at least 131 bytes",
                  messageSize >= 131);
        assertTrue("Message size should be less than 2KB",
                  messageSize < 2048);

        System.out.println("Message size: " + messageSize + " bytes");
    }

    @Test
    public void testMessageWithData() {
        // Create transaction with data field
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(
            keyPair.getPublicKey().toBytes());
        Bytes to = Bytes.random(20);
        Bytes data = Bytes.wrap("Hello XDAG!".getBytes());

        Transaction tx = Transaction.createWithData(
            from,
            to,
            XAmount.of(50, XUnit.XDAG),
            2,
            XAmount.of(2, XUnit.MILLI_XDAG),
            1L,
            data
        );

        Transaction signedTx = tx.sign(keyPair);

        // Serialize and deserialize
        NewTransactionMessage message = new NewTransactionMessage(signedTx);
        NewTransactionMessage deserializedMessage = new NewTransactionMessage(message.getBody());

        // Verify data field is preserved
        assertEquals("Data should match",
                    data, deserializedMessage.getTransaction().getData());
    }

    @Test
    public void testMultipleMessagesIndependent() {
        // Create multiple different transactions
        Transaction tx1 = createTestTransaction();
        Transaction tx2 = createTestTransaction();
        Transaction tx3 = createTestTransaction();

        // Create messages
        NewTransactionMessage msg1 = new NewTransactionMessage(tx1);
        NewTransactionMessage msg2 = new NewTransactionMessage(tx2);
        NewTransactionMessage msg3 = new NewTransactionMessage(tx3);

        // Verify each message contains correct transaction
        assertEquals("Message 1 should contain tx1",
                    tx1.getHash(), msg1.getTransaction().getHash());
        assertEquals("Message 2 should contain tx2",
                    tx2.getHash(), msg2.getTransaction().getHash());
        assertEquals("Message 3 should contain tx3",
                    tx3.getHash(), msg3.getTransaction().getHash());

        // Verify messages are independent
        assertNotEquals("Message 1 and 2 should be different",
                       msg1.getTransaction().getHash(),
                       msg2.getTransaction().getHash());
        assertNotEquals("Message 2 and 3 should be different",
                       msg2.getTransaction().getHash(),
                       msg3.getTransaction().getHash());
    }

    @Test
    public void testToString() {
        // Create transaction
        Transaction tx = createTestTransaction();

        // Create message
        NewTransactionMessage message = new NewTransactionMessage(tx);

        // Verify toString contains useful information
        String str = message.toString();

        assertNotNull("toString should not be null", str);
        assertTrue("toString should contain 'NewTransactionMessage'",
                  str.contains("NewTransactionMessage"));
        assertTrue("toString should contain hash",
                  str.contains("hash="));
        assertTrue("toString should contain from",
                  str.contains("from="));
        assertTrue("toString should contain to",
                  str.contains("to="));
        assertTrue("toString should contain amount",
                  str.contains("amount="));

        System.out.println("Message toString: " + str);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMessageBody() {
        // Try to deserialize invalid body (too short)
        byte[] invalidBody = new byte[10];

        // Should throw exception
        new NewTransactionMessage(invalidBody);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyMessageBody() {
        // Try to deserialize empty body
        byte[] emptyBody = new byte[0];

        // Should throw exception
        new NewTransactionMessage(emptyBody);
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
            XAmount.of(1, XUnit.MILLI_XDAG),
            1L
        );

        return tx.sign(keyPair);
    }
}
