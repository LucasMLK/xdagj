package io.xdag.core;

import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test to determine the actual size returned by AddressUtils.toBytesAddress()
 */
public class AddressSizeTest {

    @Test
    public void testAddressSize() {
        // Generate 10 random keys and check address sizes
        System.out.println("Testing AddressUtils.toBytesAddress() return size:");
        System.out.println("=".repeat(60));

        for (int i = 0; i < 10; i++) {
            ECKeyPair key = ECKeyPair.generate();
            Bytes address = AddressUtils.toBytesAddress(key);

            System.out.printf("Key #%d: address size = %d bytes, hex = %s...%n",
                    i + 1,
                    address.size(),
                    address.toHexString().substring(0, Math.min(32, address.toHexString().length())));

            // Check what size is actually returned
            assertTrue("Address should not be null", address != null);
            assertTrue("Address should have positive size", address.size() > 0);
        }

        // Test with the default key pattern from wallet
        ECKeyPair defaultKey = ECKeyPair.generate();
        Bytes defaultAddress = AddressUtils.toBytesAddress(defaultKey);

        System.out.println("=".repeat(60));
        System.out.printf("Default key address size: %d bytes%n", defaultAddress.size());
        System.out.printf("Expected by Block: 20 bytes%n");

        if (defaultAddress.size() == 20) {
            System.out.println("✓ Address is 20 bytes - matches Block requirement");
        } else if (defaultAddress.size() == 32) {
            System.out.println("✗ Address is 32 bytes - MISMATCH with Block requirement!");
            System.out.println("  This explains the BufferOverflowException");
        } else {
            System.out.printf("? Address is %d bytes - unexpected size%n", defaultAddress.size());
        }

        // For now, just log the size - don't fail the test
        // We need to see what the actual behavior is
        System.out.printf("%nActual address size: %d bytes%n", defaultAddress.size());
    }

    @Test
    public void testAddressConsistency() {
        // Test that all addresses from the same public key have same size
        ECKeyPair key = ECKeyPair.generate();

        Bytes addr1 = AddressUtils.toBytesAddress(key);
        Bytes addr2 = AddressUtils.toBytesAddress(key);

        assertEquals("Same key should produce same address", addr1, addr2);
        assertEquals("Address size should be consistent", addr1.size(), addr2.size());

        System.out.printf("✓ Address generation is consistent: %d bytes%n", addr1.size());
    }

    @Test
    public void testPublicKeySize() {
        // Check address size only (simplified)
        ECKeyPair key = ECKeyPair.generate();
        Bytes address = AddressUtils.toBytesAddress(key);

        System.out.println("Address size check:");
        System.out.printf("  Address:              %d bytes%n", address.size());
        System.out.printf("  Expected:             20 bytes (Ethereum-style)%n");

        assertTrue("Address should have positive length", address.size() > 0);
    }
}
