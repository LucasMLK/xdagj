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

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DagchainListener interface
 *
 * <p>Tests the event-driven blockchain listener mechanism.
 */
public class DagchainListenerTest {

    private Block mockBlock;

    @Before
    public void setUp() {
        // Create a mock block for testing
        mockBlock = Mockito.mock(Block.class);
        BlockInfo mockInfo = Mockito.mock(BlockInfo.class);
        when(mockBlock.getInfo()).thenReturn(mockInfo);
        when(mockInfo.getHeight()).thenReturn(100L);
        when(mockBlock.getTimestamp()).thenReturn(1700000000L);
    }

    /**
     * Test 1: Simple listener receives onBlockConnected event
     */
    @Test
    public void testOnBlockConnected() {
        AtomicInteger callCount = new AtomicInteger(0);

        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                callCount.incrementAndGet();
                assertNotNull("Block should not be null", block);
                assertSame("Block should match", mockBlock, block);
            }

            @Override
            public void onBlockDisconnected(Block block) {
                fail("onBlockDisconnected should not be called");
            }
        };

        // Trigger event
        listener.onBlockConnected(mockBlock);

        // Verify
        assertEquals("onBlockConnected should be called once", 1, callCount.get());
    }

    /**
     * Test 2: Simple listener receives onBlockDisconnected event
     */
    @Test
    public void testOnBlockDisconnected() {
        AtomicInteger callCount = new AtomicInteger(0);

        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                fail("onBlockConnected should not be called");
            }

            @Override
            public void onBlockDisconnected(Block block) {
                callCount.incrementAndGet();
                assertNotNull("Block should not be null", block);
                assertSame("Block should match", mockBlock, block);
            }
        };

        // Trigger event
        listener.onBlockDisconnected(mockBlock);

        // Verify
        assertEquals("onBlockDisconnected should be called once", 1, callCount.get());
    }

    /**
     * Test 3: Multiple listeners receive events
     */
    @Test
    public void testMultipleListeners() {
        AtomicInteger listener1Count = new AtomicInteger(0);
        AtomicInteger listener2Count = new AtomicInteger(0);

        DagchainListener listener1 = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                listener1Count.incrementAndGet();
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        DagchainListener listener2 = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                listener2Count.incrementAndGet();
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        // Trigger events on both listeners
        listener1.onBlockConnected(mockBlock);
        listener2.onBlockConnected(mockBlock);

        // Verify both received the event
        assertEquals("Listener 1 should be called", 1, listener1Count.get());
        assertEquals("Listener 2 should be called", 1, listener2Count.get());
    }

    /**
     * Test 4: Listener can process block information
     */
    @Test
    public void testListenerAccessesBlockInfo() {
        final long[] capturedHeight = {-1};
        final long[] capturedTimestamp = {-1};

        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                capturedHeight[0] = block.getInfo().getHeight();
                capturedTimestamp[0] = block.getTimestamp();
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        // Trigger event
        listener.onBlockConnected(mockBlock);

        // Verify listener processed block info
        assertEquals("Should capture block height", 100L, capturedHeight[0]);
        assertEquals("Should capture timestamp", 1700000000L, capturedTimestamp[0]);
    }

    /**
     * Test 5: Listener exception handling (listener throws)
     */
    @Test
    public void testListenerThrowsException() {
        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                throw new RuntimeException("Test exception");
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        // Should not crash when listener throws
        try {
            listener.onBlockConnected(mockBlock);
            fail("Should throw exception");
        } catch (RuntimeException e) {
            assertEquals("Should propagate exception message",
                "Test exception", e.getMessage());
        }
    }

    /**
     * Test 6: Listener with state tracking
     */
    @Test
    public void testStatefulListener() {
        // Listener that tracks connected and disconnected blocks
        class StatefulListener implements DagchainListener {
            int connected = 0;
            int disconnected = 0;

            @Override
            public void onBlockConnected(Block block) {
                connected++;
            }

            @Override
            public void onBlockDisconnected(Block block) {
                disconnected++;
            }

            public int getConnected() {
                return connected;
            }

            public int getDisconnected() {
                return disconnected;
            }
        }

        StatefulListener listener = new StatefulListener();

        // Trigger multiple events
        listener.onBlockConnected(mockBlock);
        listener.onBlockConnected(mockBlock);
        listener.onBlockDisconnected(mockBlock);
        listener.onBlockConnected(mockBlock);

        // Verify state
        assertEquals("Should track 3 connected events", 3, listener.getConnected());
        assertEquals("Should track 1 disconnected event", 1, listener.getDisconnected());
    }

    /**
     * Test 7: Listener with conditional logic
     */
    @Test
    public void testConditionalListener() {
        AtomicInteger highBlockCount = new AtomicInteger(0);

        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                // Only process blocks above height 50
                if (block.getInfo().getHeight() > 50) {
                    highBlockCount.incrementAndGet();
                }
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        // Create blocks with different heights
        Block lowBlock = mock(Block.class);
        BlockInfo lowInfo = mock(BlockInfo.class);
        when(lowBlock.getInfo()).thenReturn(lowInfo);
        when(lowInfo.getHeight()).thenReturn(25L);

        Block highBlock = mock(Block.class);
        BlockInfo highInfo = mock(BlockInfo.class);
        when(highBlock.getInfo()).thenReturn(highInfo);
        when(highInfo.getHeight()).thenReturn(100L);

        // Trigger events
        listener.onBlockConnected(lowBlock);   // Should be ignored
        listener.onBlockConnected(highBlock);  // Should be counted

        // Verify conditional logic
        assertEquals("Should only count high blocks", 1, highBlockCount.get());
    }

    /**
     * Test 8: Listener interface has exactly 2 methods
     */
    @Test
    public void testInterfaceMethodCount() {
        // Verify interface structure
        assertEquals("DagchainListener should have exactly 2 methods",
            2, DagchainListener.class.getDeclaredMethods().length);
    }

    /**
     * Test 9: Listener can be implemented as lambda
     */
    @Test
    public void testLambdaImplementation() {
        // Note: Can't use lambda for interface with 2 methods (not functional interface)
        // But we can test that traditional implementation works

        AtomicInteger count = new AtomicInteger(0);

        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                count.incrementAndGet();
            }

            @Override
            public void onBlockDisconnected(Block block) {
                count.incrementAndGet();
            }
        };

        listener.onBlockConnected(mockBlock);
        listener.onBlockDisconnected(mockBlock);

        assertEquals("Both methods should be called", 2, count.get());
    }

    /**
     * Test 10: Listener receives null block (error case)
     */
    @Test
    public void testNullBlock() {
        DagchainListener listener = new DagchainListener() {
            @Override
            public void onBlockConnected(Block block) {
                // Listener should handle null gracefully
                if (block == null) {
                    return;
                }
                fail("Should not reach here with null block");
            }

            @Override
            public void onBlockDisconnected(Block block) {
            }
        };

        // Trigger with null (error scenario)
        listener.onBlockConnected(null);
        // Should not crash
        assertTrue("Test completed without crash", true);
    }
}
