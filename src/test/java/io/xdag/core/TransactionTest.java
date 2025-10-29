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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for Transaction (v5.1)
 */
public class TransactionTest {

    @Test
    public void testCreateTransfer() {
        Bytes32 from = Bytes32.random();
        Bytes32 to = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);
        long nonce = 1;
        XAmount fee = XAmount.of(1, XUnit.MILLI_XDAG);

        Transaction tx = Transaction.createTransfer(from, to, amount, nonce, fee);

        assertNotNull(tx);
        assertEquals(from, tx.getFrom());
        assertEquals(to, tx.getTo());
        assertEquals(amount, tx.getAmount());
        assertEquals(nonce, tx.getNonce());
        assertEquals(fee, tx.getFee());
        assertEquals(Bytes.EMPTY, tx.getData());
    }

    @Test
    public void testCreateWithData() {
        Bytes32 from = Bytes32.random();
        Bytes32 to = Bytes32.random();
        XAmount amount = XAmount.of(50, XUnit.XDAG);
        long nonce = 2;
        XAmount fee = XAmount.of(2, XUnit.MILLI_XDAG);
        Bytes data = Bytes.wrap("Hello XDAG".getBytes());

        Transaction tx = Transaction.createWithData(from, to, amount, nonce, fee, data);

        assertNotNull(tx);
        assertEquals(data, tx.getData());
        assertTrue(tx.hasData());
    }

    @Test
    public void testCreateWithDataTooLarge() {
        Bytes32 from = Bytes32.random();
        Bytes32 to = Bytes32.random();
        Bytes tooLargeData = Bytes.random(Transaction.MAX_DATA_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> {
            Transaction.createWithData(from, to, XAmount.ZERO, 1, XAmount.ZERO, tooLargeData);
        });
    }

    @Test
    public void testHashCalculation() {
        Transaction tx = Transaction.createTransfer(
            Bytes32.random(),
            Bytes32.random(),
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.of(1, XUnit.MILLI_XDAG)
        );

        Bytes32 hash1 = tx.getHash();
        Bytes32 hash2 = tx.getHash();

        assertNotNull(hash1);
        assertEquals(hash1, hash2);  // Hash caching
    }

    @Test
    public void testHashDeterministic() {
        Bytes32 from = Bytes32.random();
        Bytes32 to = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);
        long nonce = 1;
        XAmount fee = XAmount.of(1, XUnit.MILLI_XDAG);

        Transaction tx1 = Transaction.createTransfer(from, to, amount, nonce, fee);
        Transaction tx2 = Transaction.createTransfer(from, to, amount, nonce, fee);

        // Same parameters should produce same hash
        assertEquals(tx1.getHash(), tx2.getHash());
    }

    @Test
    public void testBasicValidation() {
        // Valid transaction
        Transaction validTx = Transaction.createTransfer(
            Bytes32.random(),
            Bytes32.random(),
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.of(1, XUnit.MILLI_XDAG)
        );
        assertTrue(validTx.isValid());

        // Invalid: negative amount
        Transaction invalidAmount = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.of(-100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.ZERO)
                .build();
        assertFalse(invalidAmount.isValid());

        // Invalid: negative nonce
        Transaction invalidNonce = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.ZERO)
                .nonce(-1)
                .fee(XAmount.ZERO)
                .build();
        assertFalse(invalidNonce.isValid());

        // Invalid: data too large
        Transaction invalidData = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.ZERO)
                .nonce(1)
                .fee(XAmount.ZERO)
                .data(Bytes.random(Transaction.MAX_DATA_LENGTH + 1))
                .build();
        assertFalse(invalidData.isValid());
    }

    @Test
    public void testFeeCalculation() {
        XAmount baseFee = XAmount.of(1, XUnit.MILLI_XDAG);

        // No data - base fee only
        Transaction tx1 = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.ZERO)
                .nonce(1)
                .fee(baseFee)
                .data(Bytes.EMPTY)
                .build();
        assertEquals(baseFee, tx1.calculateTotalFee(baseFee));

        // Data <= 256 bytes - base fee only
        Transaction tx2 = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.ZERO)
                .nonce(1)
                .fee(baseFee)
                .data(Bytes.random(256))
                .build();
        assertEquals(baseFee, tx2.calculateTotalFee(baseFee));

        // Data > 256 bytes - should be higher
        Transaction tx3 = Transaction.builder()
                .from(Bytes32.random())
                .to(Bytes32.random())
                .amount(XAmount.ZERO)
                .nonce(1)
                .fee(baseFee)
                .data(Bytes.random(512))
                .build();
        assertTrue(tx3.calculateTotalFee(baseFee).compareTo(baseFee) > 0);
    }

    @Test
    public void testGetSize() {
        Transaction tx = Transaction.createTransfer(
            Bytes32.random(),
            Bytes32.random(),
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.of(1, XUnit.MILLI_XDAG)
        );

        int expectedSize = 32 +  // from
                          32 +  // to
                          8 +   // amount
                          8 +   // nonce
                          8 +   // fee
                          2 +   // data length
                          0 +   // data (empty)
                          1 +   // v
                          32 +  // r
                          32;   // s

        assertEquals(expectedSize, tx.getSize());
    }

    @Test
    public void testToString() {
        Transaction tx = Transaction.createTransfer(
            Bytes32.random(),
            Bytes32.random(),
            XAmount.of(100, XUnit.XDAG),
            5,
            XAmount.of(1, XUnit.MILLI_XDAG)
        );

        String str = tx.toString();
        assertTrue(str.contains("nonce=5"));
        assertTrue(str.contains("amount=100"));
    }

    @Test
    public void testConstants() {
        assertEquals(1024, Transaction.MAX_DATA_LENGTH);
        assertEquals(256, Transaction.BASE_DATA_LENGTH);
    }

    @Test
    public void testImmutability() {
        Bytes32 from = Bytes32.random();
        Transaction tx1 = Transaction.createTransfer(
            from,
            Bytes32.random(),
            XAmount.of(100, XUnit.XDAG),
            1,
            XAmount.ZERO
        );

        // Modify via builder
        Transaction tx2 = tx1.toBuilder()
                .nonce(2)
                .build();

        // Original unchanged
        assertEquals(1, tx1.getNonce());
        assertEquals(2, tx2.getNonce());
        assertNotEquals(tx1.getHash(), tx2.getHash());
    }
}
