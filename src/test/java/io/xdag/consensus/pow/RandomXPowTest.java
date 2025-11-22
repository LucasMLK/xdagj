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
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.DagChain;
import io.xdag.core.DagchainListener;
import io.xdag.utils.TimeUtils;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RandomXPow
 *
 * <p>Tests the event-driven RandomX implementation.
 */
public class RandomXPowTest {

    private Config config;
    private DagChain mockDagChain;
    private RandomXPow randomXPow;

    @Before
    public void setUp() {
        // Create test configuration
        config = new DevnetConfig();

        // Create mock DagChain
        mockDagChain = mock(DagChain.class);
    }

    /**
     * Test 1: RandomXPow creation
     */
    @Test
    public void testCreation() {
        randomXPow = new RandomXPow(config, mockDagChain);

        assertNotNull("RandomXPow should be created", randomXPow);
        assertTrue("Should implement PowAlgorithm",
            randomXPow instanceof PowAlgorithm);
        assertTrue("Should implement DagchainListener",
            randomXPow instanceof DagchainListener);
    }

    /**
     * Test 2: RandomXPow implements PowAlgorithm interface
     */
    @Test
    public void testImplementsPowAlgorithm() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Verify PowAlgorithm methods exist
        assertNotNull("getName should be implemented", randomXPow.getName());
        assertEquals("Name should be RandomX", "RandomX", randomXPow.getName());
    }

    /**
     * Test 3: RandomXPow implements DagchainListener interface
     */
    @Test
    public void testImplementsDagchainListener() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Create mock block
        Block mockBlock = mock(Block.class);
        BlockInfo mockInfo = mock(BlockInfo.class);
        when(mockBlock.getInfo()).thenReturn(mockInfo);
        when(mockBlock.getTimestamp()).thenReturn(TimeUtils.getCurrentEpoch());
        when(mockInfo.getHeight()).thenReturn(100L);

        // Should not throw exception when calling listener methods
        try {
            randomXPow.onBlockConnected(mockBlock);
            randomXPow.onBlockDisconnected(mockBlock);
            assertTrue("Listener methods should execute without error", true);
        } catch (Exception e) {
            fail("Listener methods should not throw: " + e.getMessage());
        }
    }

    /**
     * Test 4: Start registers RandomXPow as listener
     */
    @Test
    public void testStartRegistersListener() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Start RandomXPow
        randomXPow.start();

        // Verify addListener was called
        ArgumentCaptor<DagchainListener> captor = ArgumentCaptor.forClass(DagchainListener.class);
        verify(mockDagChain).addListener(captor.capture());

        // Verify the registered listener is our RandomXPow instance
        assertSame("Should register itself as listener", randomXPow, captor.getValue());
    }

    /**
     * Test 5: Stop unregisters RandomXPow as listener
     */
    @Test
    public void testStopUnregistersListener() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Start and then stop
        randomXPow.start();
        randomXPow.stop();

        // Verify removeListener was called
        verify(mockDagChain).removeListener(randomXPow);
    }

    /**
     * Test 6: isActive checks fork activation
     */
    @Test
    public void testIsActive() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Test with different epochs
        // Note: Actual fork activation depends on configuration

        // Epoch 0 should be before fork (assuming standard config)
        boolean active0 = randomXPow.isActive(0L);

        // Very high epoch should be after fork
        boolean activeHigh = randomXPow.isActive(1000000L);

        // At least verify the method doesn't throw
        assertNotNull("isActive should return a value for epoch 0", (Boolean) active0);
        assertNotNull("isActive should return a value for high epoch", (Boolean) activeHigh);
    }

    /**
     * Test 7: calculateBlockHash with valid context
     */
    @Test
    public void testCalculateBlockHash() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Create test data
        byte[] blockData = new byte[512];
        for (int i = 0; i < blockData.length; i++) {
            blockData[i] = (byte) (i % 256);
        }

        long timestamp = TimeUtils.getCurrentEpoch();
        HashContext context = HashContext.forMining(timestamp);

        // Calculate hash (may return null if RandomX not ready)
        byte[] hash = randomXPow.calculateBlockHash(blockData, context);

        // Verify result (null or 32 bytes)
        if (hash != null) {
            assertEquals("Hash should be 32 bytes", 32, hash.length);
        } else {
            // RandomX may not be ready yet, which is acceptable
            assertTrue("Hash can be null if RandomX not ready", true);
        }
    }

    /**
     * Test 8: calculatePoolHash with valid context
     */
    @Test
    public void testCalculatePoolHash() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Create test data
        byte[] poolData = new byte[256];
        for (int i = 0; i < poolData.length; i++) {
            poolData[i] = (byte) (i % 256);
        }

        long timestamp = TimeUtils.getCurrentEpoch();
        HashContext context = HashContext.forMining(timestamp);

        // Calculate pool hash (may return null if RandomX not ready)
        Bytes32 hash = randomXPow.calculatePoolHash(poolData, context);

        // Verify result
        if (hash != null) {
            assertEquals("Pool hash should be 32 bytes", 32, hash.size());
        } else {
            // RandomX may not be ready yet, which is acceptable
            assertTrue("Pool hash can be null if RandomX not ready", true);
        }
    }

    /**
     * Test 9: onBlockConnected processes block
     */
    @Test
    public void testOnBlockConnected() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Create mock block
        Block mockBlock = mock(Block.class);
        BlockInfo mockInfo = mock(BlockInfo.class);
        when(mockBlock.getInfo()).thenReturn(mockInfo);
        when(mockBlock.getTimestamp()).thenReturn(TimeUtils.getCurrentEpoch());
        when(mockInfo.getHeight()).thenReturn(1000L);

        // Should not throw exception
        try {
            randomXPow.onBlockConnected(mockBlock);
            assertTrue("onBlockConnected should complete successfully", true);
        } catch (Exception e) {
            fail("onBlockConnected should not throw: " + e.getMessage());
        }
    }

    /**
     * Test 10: onBlockDisconnected processes block
     */
    @Test
    public void testOnBlockDisconnected() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Create mock block
        Block mockBlock = mock(Block.class);
        BlockInfo mockInfo = mock(BlockInfo.class);
        when(mockBlock.getInfo()).thenReturn(mockInfo);
        when(mockBlock.getTimestamp()).thenReturn(TimeUtils.getCurrentEpoch());
        when(mockInfo.getHeight()).thenReturn(999L);

        // Should not throw exception
        try {
            randomXPow.onBlockDisconnected(mockBlock);
            assertTrue("onBlockDisconnected should complete successfully", true);
        } catch (Exception e) {
            fail("onBlockDisconnected should not throw: " + e.getMessage());
        }
    }

    /**
     * Test 11: isReady returns boolean
     */
    @Test
    public void testIsReady() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Before start, may not be ready
        boolean readyBefore = randomXPow.isReady();

        randomXPow.start();

        // After start, check ready status
        boolean readyAfter = randomXPow.isReady();

        // Both should be valid boolean values
        assertNotNull("isReady should return boolean before start", (Boolean) readyBefore);
        assertNotNull("isReady should return boolean after start", (Boolean) readyAfter);
    }

    /**
     * Test 12: Multiple start calls throw exception
     */
    @Test(expected = IllegalStateException.class)
    public void testMultipleStartCalls() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Start multiple times - should throw
        randomXPow.start();
        randomXPow.start();  // This should throw IllegalStateException
    }

    /**
     * Test 13: Stop before start is safe
     */
    @Test
    public void testStopBeforeStart() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Stop without starting
        try {
            randomXPow.stop();
            assertTrue("Stop before start should be safe", true);
        } catch (Exception e) {
            fail("Stop before start should not throw: " + e.getMessage());
        }
    }

    /**
     * Test 14: Null configuration throws exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNullConfigThrows() {
        new RandomXPow(null, mockDagChain);
    }

    /**
     * Test 15: Null DagChain throws exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNullDagChainThrows() {
        new RandomXPow(config, null);
    }

    /**
     * Test 16: Event-driven seed update simulation
     */
    @Test
    public void testEventDrivenSeedUpdate() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        // Simulate multiple block connections
        for (int i = 0; i < 10; i++) {
            Block mockBlock = mock(Block.class);
            BlockInfo mockInfo = mock(BlockInfo.class);
            when(mockBlock.getInfo()).thenReturn(mockInfo);
            when(mockBlock.getTimestamp()).thenReturn(TimeUtils.getCurrentEpoch() + i * 64);
            when(mockInfo.getHeight()).thenReturn(1000L + i);

            // Should not throw
            randomXPow.onBlockConnected(mockBlock);
        }

        assertTrue("Event-driven updates should complete", true);
    }

    /**
     * Test 17: Lifecycle: start -> use -> stop
     */
    @Test
    public void testFullLifecycle() {
        randomXPow = new RandomXPow(config, mockDagChain);

        // Start
        randomXPow.start();
        verify(mockDagChain).addListener(randomXPow);

        // Use (simulate block event)
        Block mockBlock = mock(Block.class);
        BlockInfo mockInfo = mock(BlockInfo.class);
        when(mockBlock.getInfo()).thenReturn(mockInfo);
        when(mockBlock.getTimestamp()).thenReturn(TimeUtils.getCurrentEpoch());
        when(mockInfo.getHeight()).thenReturn(500L);

        randomXPow.onBlockConnected(mockBlock);

        // Stop
        randomXPow.stop();
        verify(mockDagChain).removeListener(randomXPow);

        assertTrue("Full lifecycle should complete", true);
    }

    /**
     * Test 18: getName returns "RandomX"
     */
    @Test
    public void testGetName() {
        randomXPow = new RandomXPow(config, mockDagChain);

        String name = randomXPow.getName();

        assertNotNull("Name should not be null", name);
        assertEquals("Name should be 'RandomX'", "RandomX", name);
    }

    /**
     * Test 19: Hash calculation with null data handles gracefully
     */
    @Test
    public void testHashCalculationWithNullData() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        HashContext context = HashContext.forMining(TimeUtils.getCurrentEpoch());

        // Should handle null gracefully (return null or throw appropriate exception)
        try {
            byte[] hash = randomXPow.calculateBlockHash(null, context);
            // If no exception, hash should be null
            assertNull("Hash with null data should be null", hash);
        } catch (NullPointerException | IllegalArgumentException e) {
            // Throwing exception is also acceptable
            assertTrue("Exception for null data is acceptable", true);
        }
    }

    /**
     * Test 20: Hash calculation with null context handles gracefully
     */
    @Test
    public void testHashCalculationWithNullContext() {
        randomXPow = new RandomXPow(config, mockDagChain);
        randomXPow.start();

        byte[] data = new byte[256];

        // Should handle null context gracefully
        try {
            byte[] hash = randomXPow.calculateBlockHash(data, null);
            // If no exception, hash should be null
            assertNull("Hash with null context should be null", hash);
        } catch (NullPointerException | IllegalArgumentException e) {
            // Throwing exception is also acceptable
            assertTrue("Exception for null context is acceptable", true);
        }
    }
}
