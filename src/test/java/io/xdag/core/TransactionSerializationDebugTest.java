/*
 * Simple debug test for Transaction serialization
 */
package io.xdag.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class TransactionSerializationDebugTest {

    @Test
    public void testBasicSerialization() {
        Transaction tx = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.of(100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        System.out.println("Original TX hash: " + tx.getHash().toHexString());
        System.out.println("Original TX size: " + tx.getSize());

        byte[] bytes = tx.toBytes();
        System.out.println("Serialized bytes length: " + bytes.length);

        Transaction deserialized = Transaction.fromBytes(bytes);
        System.out.println("Deserialized TX hash: " + deserialized.getHash().toHexString());

        assertNotNull(deserialized);
        assertEquals(tx.getFrom(), deserialized.getFrom());
        assertEquals(tx.getTo(), deserialized.getTo());
        assertEquals(tx.getAmount(), deserialized.getAmount());
        assertEquals(tx.getNonce(), deserialized.getNonce());
        assertEquals(tx.getFee(), deserialized.getFee());
        assertEquals(tx.getHash(), deserialized.getHash());
    }
}
