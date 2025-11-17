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

import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.ECKeyPair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for NewBlockMessage with TTL hop limit mechanism
 *
 * <p>Tests message serialization/deserialization and TTL functionality for P2P block broadcasting
 */
public class NewBlockMessageTest {

    @Test
    public void testMessageCreationWithDefaultTTL() {
        // Create a test block
        Block block = createTestBlock();

        // Create message from block (should use default TTL)
        NewBlockMessage message = new NewBlockMessage(block);

        // Verify message properties
        assertNotNull("Message should not be null", message);
        assertEquals("Message should contain correct block",
                block.getHash(), message.getBlock().getHash());
        assertEquals("Default TTL should be 5",
                NewBlockMessage.DEFAULT_TTL, message.getTtl());
        assertNotNull("Message body should not be null", message.getBody());
        assertTrue("Message body should not be empty", message.getBody().length > 0);
    }

    @Test
    public void testMessageCreationWithCustomTTL() {
        // Create a test block
        Block block = createTestBlock();

        // Create message with custom TTL
        int customTTL = 3;
        NewBlockMessage message = new NewBlockMessage(block, customTTL);

        // Verify TTL
        assertEquals("TTL should match custom value",
                customTTL, message.getTtl());
    }

    @Test
    public void testMessageDeserialization() {
        // Create original block
        Block originalBlock = createTestBlock();

        // Serialize to message
        NewBlockMessage originalMessage = new NewBlockMessage(originalBlock);
        byte[] serializedBody = originalMessage.getBody();

        // Deserialize from body
        NewBlockMessage deserializedMessage = new NewBlockMessage(serializedBody);

        // Verify deserialized block matches original
        Block deserializedBlock = deserializedMessage.getBlock();

        assertEquals("Hash should match",
                originalBlock.getHash(), deserializedBlock.getHash());
        assertEquals("TTL should match",
                originalMessage.getTtl(), deserializedMessage.getTtl());
    }

    @Test
    public void testTTLDecrement() {
        // Create message with TTL = 5
        Block block = createTestBlock();
        NewBlockMessage message1 = new NewBlockMessage(block, 5);

        assertEquals("Initial TTL should be 5", 5, message1.getTtl());
        assertTrue("Message with TTL=5 should forward", message1.shouldForward());

        // Decrement TTL
        NewBlockMessage message2 = message1.decrementTTL();
        assertEquals("Decremented TTL should be 4", 4, message2.getTtl());
        assertTrue("Message with TTL=4 should forward", message2.shouldForward());

        // Decrement multiple times
        NewBlockMessage message3 = message2.decrementTTL(); // TTL = 3
        NewBlockMessage message4 = message3.decrementTTL(); // TTL = 2
        NewBlockMessage message5 = message4.decrementTTL(); // TTL = 1

        assertEquals("TTL should be 1", 1, message5.getTtl());
        assertTrue("Message with TTL=1 should still forward", message5.shouldForward());

        // Decrement to 0
        NewBlockMessage message6 = message5.decrementTTL(); // TTL = 0
        assertEquals("TTL should be 0", 0, message6.getTtl());
        assertFalse("Message with TTL=0 should not forward", message6.shouldForward());
    }

    @Test
    public void testShouldForward() {
        Block block = createTestBlock();

        // TTL = 5: should forward
        NewBlockMessage msg5 = new NewBlockMessage(block, 5);
        assertTrue("TTL=5 should forward", msg5.shouldForward());

        // TTL = 3: should forward
        NewBlockMessage msg3 = new NewBlockMessage(block, 3);
        assertTrue("TTL=3 should forward", msg3.shouldForward());

        // TTL = 1: should forward
        NewBlockMessage msg1 = new NewBlockMessage(block, 1);
        assertTrue("TTL=1 should forward", msg1.shouldForward());

        // TTL = 0: should NOT forward
        NewBlockMessage msg0 = new NewBlockMessage(block, 0);
        assertFalse("TTL=0 should not forward", msg0.shouldForward());
    }

    @Test
    public void testTTLClamping() {
        Block block = createTestBlock();

        // TTL > DEFAULT_TTL should be clamped to DEFAULT_TTL
        NewBlockMessage msgHigh = new NewBlockMessage(block, 100);
        assertEquals("TTL should be clamped to DEFAULT_TTL",
                NewBlockMessage.DEFAULT_TTL, msgHigh.getTtl());

        // TTL < 0 should be clamped to 0
        NewBlockMessage msgNegative = new NewBlockMessage(block, -5);
        assertEquals("Negative TTL should be clamped to 0",
                0, msgNegative.getTtl());
    }

    @Test
    public void testTTLSerializationDeserialization() {
        Block block = createTestBlock();

        // Test various TTL values
        int[] ttlValues = {0, 1, 3, 5};

        for (int ttl : ttlValues) {
            // Create message with specific TTL
            NewBlockMessage originalMsg = new NewBlockMessage(block, ttl);
            assertEquals("Original TTL should match", ttl, originalMsg.getTtl());

            // Serialize and deserialize
            byte[] serialized = originalMsg.getBody();
            NewBlockMessage deserializedMsg = new NewBlockMessage(serialized);

            // Verify TTL is preserved
            assertEquals("Deserialized TTL should match original for TTL=" + ttl,
                    ttl, deserializedMsg.getTtl());
            assertEquals("Block hash should match for TTL=" + ttl,
                    block.getHash(), deserializedMsg.getBlock().getHash());
        }
    }

    @Test
    public void testMessageSize() {
        // Create block
        Block block = createTestBlock();

        // Create message
        NewBlockMessage message = new NewBlockMessage(block);

        // Verify message size is reasonable
        int messageSize = message.getBody().length;

        // Block size = 1 byte TTL + Block header (92) + 4 bytes (links count) + links
        // For empty links: 1 + 92 + 4 = 97 bytes
        assertTrue("Message size should be at least 97 bytes",
                messageSize >= 97);
        assertTrue("Message size should be less than 50MB",
                messageSize < 50 * 1024 * 1024);

        System.out.println("NewBlockMessage size: " + messageSize + " bytes (TTL=1 byte)");
    }

    @Test
    public void testToString() {
        // Create block
        Block block = createTestBlock();

        // Create message
        NewBlockMessage message = new NewBlockMessage(block);

        // Verify toString contains useful information
        String str = message.toString();

        assertNotNull("toString should not be null", str);
        assertTrue("toString should contain 'NewBlockMessage'",
                str.contains("NewBlockMessage"));
        assertTrue("toString should contain 'hash='",
                str.contains("hash="));
        assertTrue("toString should contain 'ttl='",
                str.contains("ttl="));
        assertTrue("toString should contain 'size='",
                str.contains("size="));

        System.out.println("Message toString: " + str);
    }

    @Test
    public void testMultipleMessagesIndependent() {
        // Create multiple different blocks
        Block block1 = createTestBlock();
        Block block2 = createTestBlock();
        Block block3 = createTestBlock();

        // Create messages with different TTLs
        NewBlockMessage msg1 = new NewBlockMessage(block1, 5);
        NewBlockMessage msg2 = new NewBlockMessage(block2, 3);
        NewBlockMessage msg3 = new NewBlockMessage(block3, 1);

        // Verify each message contains correct block and TTL
        assertEquals("Message 1 should contain block1",
                block1.getHash(), msg1.getBlock().getHash());
        assertEquals("Message 1 TTL should be 5", 5, msg1.getTtl());

        assertEquals("Message 2 should contain block2",
                block2.getHash(), msg2.getBlock().getHash());
        assertEquals("Message 2 TTL should be 3", 3, msg2.getTtl());

        assertEquals("Message 3 should contain block3",
                block3.getHash(), msg3.getBlock().getHash());
        assertEquals("Message 3 TTL should be 1", 1, msg3.getTtl());

        // Verify messages are independent
        assertNotEquals("Message 1 and 2 should have different blocks",
                msg1.getBlock().getHash(),
                msg2.getBlock().getHash());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMessageBody() {
        // Try to deserialize invalid body (too short)
        byte[] invalidBody = new byte[1];

        // Should throw exception
        new NewBlockMessage(invalidBody);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyMessageBody() {
        // Try to deserialize empty body
        byte[] emptyBody = new byte[0];

        // Should throw exception
        new NewBlockMessage(emptyBody);
    }

    @Test
    public void testTTLPropagationSimulation() {
        // Simulate block propagation through 5 hops
        Block block = createTestBlock();

        // Initial broadcast with default TTL=5
        NewBlockMessage hop1 = new NewBlockMessage(block);
        assertEquals("Hop 1 TTL should be 5", 5, hop1.getTtl());
        assertTrue("Hop 1 should forward", hop1.shouldForward());

        // Hop 2: received and forwarded
        NewBlockMessage hop2 = hop1.decrementTTL();
        assertEquals("Hop 2 TTL should be 4", 4, hop2.getTtl());
        assertTrue("Hop 2 should forward", hop2.shouldForward());

        // Hop 3
        NewBlockMessage hop3 = hop2.decrementTTL();
        assertEquals("Hop 3 TTL should be 3", 3, hop3.getTtl());
        assertTrue("Hop 3 should forward", hop3.shouldForward());

        // Hop 4
        NewBlockMessage hop4 = hop3.decrementTTL();
        assertEquals("Hop 4 TTL should be 2", 2, hop4.getTtl());
        assertTrue("Hop 4 should forward", hop4.shouldForward());

        // Hop 5
        NewBlockMessage hop5 = hop4.decrementTTL();
        assertEquals("Hop 5 TTL should be 1", 1, hop5.getTtl());
        assertTrue("Hop 5 should forward", hop5.shouldForward());

        // Hop 6: TTL expires
        NewBlockMessage hop6 = hop5.decrementTTL();
        assertEquals("Hop 6 TTL should be 0", 0, hop6.getTtl());
        assertFalse("Hop 6 should NOT forward (TTL expired)", hop6.shouldForward());

        System.out.println("✓ Block successfully propagated through 5 hops and stopped at hop 6");
    }

    // ========== Helper Methods ==========

    /**
     * Create a test block
     */
    private Block createTestBlock() {
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes coinbase = io.xdag.crypto.hash.HashUtils.sha256hash160(
                keyPair.getPublicKey().toBytes());

        // Create a simple test block with minimal data
        return Block.createWithNonce(
                System.currentTimeMillis() / 1000,  // timestamp
                org.apache.tuweni.units.bigints.UInt256.ONE,  // difficulty
                Bytes32.random(),  // nonce
                coinbase,  // coinbase address (20 bytes)
                java.util.Collections.emptyList()  // empty links for simplicity
        );
    }
}
