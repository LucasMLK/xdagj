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

package io.xdag.consensus.pow;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.utils.XdagTime;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for Sha256Pow
 *
 * <p>Tests the SHA256 PoW implementation for blocks before RandomX fork.
 */
public class Sha256PowTest {

    private Config config;
    private Sha256Pow sha256Pow;

    @Before
    public void setUp() {
        // Create test configuration
        config = new DevnetConfig();
    }

    /**
     * Test 1: Sha256Pow creation
     */
    @Test
    public void testCreation() {
        sha256Pow = new Sha256Pow(config);

        assertNotNull("Sha256Pow should be created", sha256Pow);
        assertTrue("Should implement PowAlgorithm",
            sha256Pow instanceof PowAlgorithm);
    }

    /**
     * Test 2: Sha256Pow implements PowAlgorithm interface
     */
    @Test
    public void testImplementsPowAlgorithm() {
        sha256Pow = new Sha256Pow(config);

        // Verify PowAlgorithm methods exist
        assertNotNull("getName should be implemented", sha256Pow.getName());
        assertEquals("Name should be SHA256", "SHA256", sha256Pow.getName());
    }

    /**
     * Test 3: Start lifecycle
     */
    @Test
    public void testStart() {
        sha256Pow = new Sha256Pow(config);

        sha256Pow.start();

        assertTrue("Should be started", sha256Pow.isStarted());
        assertTrue("Should be ready", sha256Pow.isReady());
    }

    /**
     * Test 4: Stop lifecycle
     */
    @Test
    public void testStop() {
        sha256Pow = new Sha256Pow(config);

        sha256Pow.start();
        sha256Pow.stop();

        assertFalse("Should be stopped", sha256Pow.isStarted());
        assertFalse("Should not be ready", sha256Pow.isReady());
    }

    /**
     * Test 5: isReady returns correct state
     */
    @Test
    public void testIsReady() {
        sha256Pow = new Sha256Pow(config);

        // Before start
        assertFalse("Should not be ready before start", sha256Pow.isReady());

        // After start
        sha256Pow.start();
        assertTrue("Should be ready after start", sha256Pow.isReady());

        // After stop
        sha256Pow.stop();
        assertFalse("Should not be ready after stop", sha256Pow.isReady());
    }

    /**
     * Test 6: isActive before fork epoch
     */
    @Test
    public void testIsActiveBeforeFork() {
        sha256Pow = new Sha256Pow(config);
        long forkEpoch = sha256Pow.getForkEpoch();

        // Test epoch before fork
        boolean active = sha256Pow.isActive(forkEpoch - 1);

        assertTrue("SHA256 should be active before fork", active);
    }

    /**
     * Test 7: isActive after fork epoch
     */
    @Test
    public void testIsActiveAfterFork() {
        sha256Pow = new Sha256Pow(config);
        long forkEpoch = sha256Pow.getForkEpoch();

        // Test epoch after fork
        boolean active = sha256Pow.isActive(forkEpoch + 1);

        assertFalse("SHA256 should not be active after fork", active);
    }

    /**
     * Test 8: isActive at fork boundary
     */
    @Test
    public void testIsActiveAtFork() {
        sha256Pow = new Sha256Pow(config);
        long forkEpoch = sha256Pow.getForkEpoch();

        // Test exactly at fork epoch
        boolean activeAtFork = sha256Pow.isActive(forkEpoch);

        assertFalse("SHA256 should not be active at fork epoch (fork activates RandomX)", activeAtFork);
    }

    /**
     * Test 9: calculateBlockHash produces valid hash
     */
    @Test
    public void testCalculateBlockHash() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        // Create test data
        byte[] blockData = new byte[512];
        for (int i = 0; i < blockData.length; i++) {
            blockData[i] = (byte) (i % 256);
        }

        long timestamp = XdagTime.getCurrentTimestamp();
        HashContext context = HashContext.forMining(timestamp);

        // Calculate hash
        byte[] hash = sha256Pow.calculateBlockHash(blockData, context);

        // Verify result
        assertNotNull("Hash should not be null", hash);
        assertEquals("Hash should be 32 bytes", 32, hash.length);
    }

    /**
     * Test 10: calculatePoolHash produces valid hash
     */
    @Test
    public void testCalculatePoolHash() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        // Create test data
        byte[] poolData = new byte[256];
        for (int i = 0; i < poolData.length; i++) {
            poolData[i] = (byte) (i % 256);
        }

        long timestamp = XdagTime.getCurrentTimestamp();
        HashContext context = HashContext.forMining(timestamp);

        // Calculate pool hash
        Bytes32 hash = sha256Pow.calculatePoolHash(poolData, context);

        // Verify result
        assertNotNull("Pool hash should not be null", hash);
        assertEquals("Pool hash should be 32 bytes", 32, hash.size());
    }

    /**
     * Test 11: Hash calculation is deterministic
     */
    @Test
    public void testHashDeterminism() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        // Create test data
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }

        long timestamp = XdagTime.getCurrentTimestamp();
        HashContext context = HashContext.forMining(timestamp);

        // Calculate hash twice
        byte[] hash1 = sha256Pow.calculateBlockHash(data, context);
        byte[] hash2 = sha256Pow.calculateBlockHash(data, context);

        // Should be identical
        assertNotNull("First hash should not be null", hash1);
        assertNotNull("Second hash should not be null", hash2);
        assertArrayEquals("Same input should produce same hash", hash1, hash2);
    }

    /**
     * Test 12: Hash calculation with null data
     */
    @Test
    public void testHashWithNullData() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        HashContext context = HashContext.forMining(XdagTime.getCurrentTimestamp());

        // Calculate hash with null data
        byte[] hash = sha256Pow.calculateBlockHash(null, context);

        // Should return null (graceful handling)
        assertNull("Hash with null data should return null", hash);
    }

    /**
     * Test 13: Hash calculation with null context
     */
    @Test
    public void testHashWithNullContext() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        byte[] data = new byte[256];

        // Calculate hash with null context
        byte[] hash = sha256Pow.calculateBlockHash(data, null);

        // Should return null (graceful handling)
        assertNull("Hash with null context should return null", hash);
    }

    /**
     * Test 14: Multiple start calls are safe
     */
    @Test
    public void testMultipleStartCalls() {
        sha256Pow = new Sha256Pow(config);

        // Start multiple times - should be safe (just logs warning)
        sha256Pow.start();
        sha256Pow.start();  // Second start should just warn

        assertTrue("Should still be started", sha256Pow.isStarted());
    }

    /**
     * Test 15: Stop before start is safe
     */
    @Test
    public void testStopBeforeStart() {
        sha256Pow = new Sha256Pow(config);

        // Stop without starting - should be safe
        try {
            sha256Pow.stop();
            assertTrue("Stop before start should be safe", true);
        } catch (Exception e) {
            fail("Stop before start should not throw: " + e.getMessage());
        }
    }

    /**
     * Test 16: Null configuration throws exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNullConfigThrows() {
        new Sha256Pow(null);
    }

    /**
     * Test 17: getName returns "SHA256"
     */
    @Test
    public void testGetName() {
        sha256Pow = new Sha256Pow(config);

        String name = sha256Pow.getName();

        assertNotNull("Name should not be null", name);
        assertEquals("Name should be 'SHA256'", "SHA256", name);
    }

    /**
     * Test 18: Full lifecycle test
     */
    @Test
    public void testFullLifecycle() {
        sha256Pow = new Sha256Pow(config);

        // Start
        sha256Pow.start();
        assertTrue("Should be started", sha256Pow.isStarted());

        // Use
        byte[] data = new byte[512];
        HashContext context = HashContext.forMining(XdagTime.getCurrentTimestamp());
        byte[] hash = sha256Pow.calculateBlockHash(data, context);
        assertNotNull("Hash should be calculated", hash);

        // Stop
        sha256Pow.stop();
        assertFalse("Should be stopped", sha256Pow.isStarted());

        assertTrue("Full lifecycle should complete", true);
    }

    /**
     * Test 19: getForkEpoch returns valid value
     */
    @Test
    public void testGetForkEpoch() {
        sha256Pow = new Sha256Pow(config);

        long forkEpoch = sha256Pow.getForkEpoch();

        assertTrue("Fork epoch should be positive", forkEpoch > 0);
    }

    /**
     * Test 20: getDiagnostics returns valid string
     */
    @Test
    public void testGetDiagnostics() {
        sha256Pow = new Sha256Pow(config);

        String diagnostics = sha256Pow.getDiagnostics();

        assertNotNull("Diagnostics should not be null", diagnostics);
        assertTrue("Diagnostics should contain 'Sha256Pow'",
            diagnostics.contains("Sha256Pow"));
        assertTrue("Diagnostics should contain started state",
            diagnostics.contains("started="));
        assertTrue("Diagnostics should contain fork epoch",
            diagnostics.contains("forkEpoch="));
    }

    /**
     * Test 21: toString returns diagnostic string
     */
    @Test
    public void testToString() {
        sha256Pow = new Sha256Pow(config);

        String str = sha256Pow.toString();

        assertNotNull("toString should not be null", str);
        assertEquals("toString should equal getDiagnostics",
            sha256Pow.getDiagnostics(), str);
    }

    /**
     * Test 22: Hash calculation before start returns null
     */
    @Test
    public void testHashBeforeStart() {
        sha256Pow = new Sha256Pow(config);
        // Don't start

        byte[] data = new byte[256];
        HashContext context = HashContext.forMining(XdagTime.getCurrentTimestamp());

        byte[] hash = sha256Pow.calculateBlockHash(data, context);

        assertNull("Hash before start should return null", hash);
    }

    /**
     * Test 23: Pool hash calculation before start returns null
     */
    @Test
    public void testPoolHashBeforeStart() {
        sha256Pow = new Sha256Pow(config);
        // Don't start

        byte[] data = new byte[256];
        HashContext context = HashContext.forMining(XdagTime.getCurrentTimestamp());

        Bytes32 hash = sha256Pow.calculatePoolHash(data, context);

        assertNull("Pool hash before start should return null", hash);
    }

    /**
     * Test 24: isActive at epoch 0 (genesis)
     */
    @Test
    public void testIsActiveAtGenesis() {
        sha256Pow = new Sha256Pow(config);

        // Epoch 0 should be before fork (SHA256 active)
        boolean active = sha256Pow.isActive(0L);

        assertTrue("SHA256 should be active at genesis (epoch 0)", active);
    }

    /**
     * Test 25: Block hash and pool hash are different
     */
    @Test
    public void testBlockHashVsPoolHash() {
        sha256Pow = new Sha256Pow(config);
        sha256Pow.start();

        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }

        HashContext context = HashContext.forMining(XdagTime.getCurrentTimestamp());

        // Calculate both hashes
        byte[] blockHash = sha256Pow.calculateBlockHash(data, context);
        Bytes32 poolHash = sha256Pow.calculatePoolHash(data, context);

        assertNotNull("Block hash should not be null", blockHash);
        assertNotNull("Pool hash should not be null", poolHash);

        // They should be different (double SHA256 vs single SHA256)
        assertFalse("Block hash and pool hash should be different",
            java.util.Arrays.equals(blockHash, poolHash.toArray()));
    }
}
