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
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TransactionReplayTest {

    @Test
    public void testReplayAttackVulnerability() {
        // Setup common transaction parameters
        ECKeyPair senderKey = ECKeyPair.generate();
        Bytes from = senderKey.toAddress();
        Bytes to = Bytes.random(20);
        XAmount amount = XAmount.of(100, XUnit.XDAG);
        long nonce = 1;
        XAmount fee = XAmount.of(1, XUnit.MILLI_XDAG);

        // Create transaction 1 (intended for Mainnet, Chain ID 1)
        Transaction txMainnet = Transaction.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .nonce(nonce)
                .fee(fee)
                .chainId(1L)
                .data(Bytes.EMPTY)
                .build();
        
        Transaction signedMainnet = txMainnet.sign(senderKey);

        // Create transaction 2 (intended for Testnet, Chain ID 2)
        // Identical parameters except chainId
        Transaction txTestnet = Transaction.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .nonce(nonce)
                .fee(fee)
                .chainId(2L)
                .data(Bytes.EMPTY)
                .build();
        
        Transaction signedTestnet = txTestnet.sign(senderKey);

        // FIX VERIFICATION:
        // Hashes and signatures MUST be different due to chainId inclusion
        assertNotEquals("Transaction hashes should be different (Replay Protection Active)", 
                signedMainnet.getHash(), signedTestnet.getHash());
                
        assertNotEquals("Signatures should be different (Replay Protection Active)",
                signedMainnet.getR(), signedTestnet.getR());
    }
}
