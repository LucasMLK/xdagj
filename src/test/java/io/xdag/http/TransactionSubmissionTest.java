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

package io.xdag.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.keys.ECKeyPair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * Test transaction serialization and deserialization for RPC submission
 *
 * <p>Tests the complete flow:
 * 1. Create a transaction
 * 2. Sign it with private key
 * 3. Serialize to hex string
 * 4. Parse back from hex string
 * 5. Verify signature
 */
public class TransactionSubmissionTest {

    @Test
    public void testTransactionSerializationForRPC() {
        // 1. Generate test keypair
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(keyPair.getPublicKey().toBytes());  // 20 bytes address
        Bytes to = Bytes.random(20);        // Random recipient

        // 2. Create transaction
        Transaction tx = Transaction.createTransfer(
                from,
                to,
                XAmount.of(100, XUnit.XDAG),
                1,  // nonce
                XAmount.of(1, XUnit.MILLI_XDAG)
        );

        // 3. Sign transaction
        Transaction signedTx = tx.sign(keyPair);

        // Verify signature
        assertTrue("Signature should be valid", signedTx.verifySignature());

        // 4. Serialize to bytes
        byte[] txBytes = signedTx.toBytes();
        assertNotNull("Transaction bytes should not be null", txBytes);

        // 5. Convert to hex string (for RPC submission)
        String txHex = "0x" + Bytes.wrap(txBytes).toHexString();
        System.out.println("Transaction hex for RPC: " + txHex);
        System.out.println("Transaction hash: " + signedTx.getHash().toHexString());
        System.out.println("Transaction size: " + txBytes.length + " bytes");

        // 6. Parse back from hex (simulating RPC receive)
        String hexData = txHex.startsWith("0x") ? txHex.substring(2) : txHex;
        byte[] parsedBytes = Bytes.fromHexString(hexData).toArray();
        Transaction parsedTx = Transaction.fromBytes(parsedBytes);

        // 7. Verify parsed transaction
        assertEquals("From address should match", from, parsedTx.getFrom());
        assertEquals("To address should match", to, parsedTx.getTo());
        assertEquals("Amount should match", signedTx.getAmount(), parsedTx.getAmount());
        assertEquals("Nonce should match", signedTx.getNonce(), parsedTx.getNonce());
        assertEquals("Fee should match", signedTx.getFee(), parsedTx.getFee());
        assertEquals("Hash should match", signedTx.getHash(), parsedTx.getHash());

        // 8. Verify signature of parsed transaction
        assertTrue("Parsed transaction signature should be valid", parsedTx.verifySignature());

        System.out.println("✓ Transaction serialization/deserialization test passed");
    }

    @Test
    public void testTransactionWithData() {
        // Test transaction with data field
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(keyPair.getPublicKey().toBytes());
        Bytes to = Bytes.random(20);
        Bytes data = Bytes.wrap("Hello XDAG!".getBytes());

        Transaction tx = Transaction.createWithData(
                from,
                to,
                XAmount.of(50, XUnit.XDAG),
                2,  // nonce
                XAmount.of(2, XUnit.MILLI_XDAG),
                data
        );

        Transaction signedTx = tx.sign(keyPair);
        assertTrue("Signature should be valid", signedTx.verifySignature());

        // Serialize and parse
        byte[] txBytes = signedTx.toBytes();
        Transaction parsedTx = Transaction.fromBytes(txBytes);

        // Verify
        assertEquals("Data should match", data, parsedTx.getData());
        assertTrue("Parsed signature should be valid", parsedTx.verifySignature());

        System.out.println("✓ Transaction with data test passed");
    }

    @Test
    public void testInvalidSignatureRejection() {
        // Create a transaction
        ECKeyPair keyPair = ECKeyPair.generate();
        Bytes from = io.xdag.crypto.hash.HashUtils.sha256hash160(keyPair.getPublicKey().toBytes());
        Transaction tx = Transaction.createTransfer(
                from,
                Bytes.random(20),
                XAmount.of(100, XUnit.XDAG),
                1,
                XAmount.of(1, XUnit.MILLI_XDAG)
        );

        Transaction signedTx = tx.sign(keyPair);

        // Tamper with signature (change v value)
        Transaction tamperedTx = signedTx.toBuilder()
                .v((signedTx.getV() + 1) % 256)
                .build();

        // Verify tampered signature is invalid
        assertTrue("Original signature should be valid", signedTx.verifySignature());
        assertTrue("Tampered signature should be invalid", !tamperedTx.verifySignature());

        System.out.println("✓ Invalid signature rejection test passed");
    }
}
