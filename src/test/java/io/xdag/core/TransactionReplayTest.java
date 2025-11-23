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
