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
package io.xdag.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.xdag.core.ChainStats;
import io.xdag.core.XAmount;
import java.io.IOException;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

public class CompactSerializerTest {

    @Test
    public void testChainStatsRoundTrip() throws IOException {
        ChainStats stats = ChainStats.builder()
            .difficulty(UInt256.valueOf(1000))
            .mainBlockCount(100)
            .balance(XAmount.of(5000))
            .baseDifficultyTarget(UInt256.valueOf(50))
            .lastDifficultyAdjustmentEpoch(12345L)
            .lastOrphanCleanupEpoch(67890L)
            .build();
        
        byte[] serialized = CompactSerializer.serialize(stats);
        assertNotNull(serialized);
        
        ChainStats deserialized = CompactSerializer.deserializeChainStats(serialized);
        
        assertEquals(stats.getDifficulty(), deserialized.getDifficulty());
        assertEquals(stats.getMainBlockCount(), deserialized.getMainBlockCount());
        assertEquals(stats.getBalance(), deserialized.getBalance());
        assertEquals(stats.getBaseDifficultyTarget(), deserialized.getBaseDifficultyTarget());
        assertEquals(stats.getLastDifficultyAdjustmentEpoch(), deserialized.getLastDifficultyAdjustmentEpoch());
        assertEquals(stats.getLastOrphanCleanupEpoch(), deserialized.getLastOrphanCleanupEpoch());
    }

    @Test
    public void testChainStatsRoundTripNullable() throws IOException {
        // Test with null baseDifficultyTarget
        ChainStats stats = ChainStats.builder()
            .difficulty(UInt256.valueOf(1000))
            .mainBlockCount(100)
            .balance(XAmount.of(5000))
            .baseDifficultyTarget(null) // baseDifficultyTarget is null
            .lastDifficultyAdjustmentEpoch(12345L)
            .lastOrphanCleanupEpoch(67890L)
            .build();
        
        byte[] serialized = CompactSerializer.serialize(stats);
        ChainStats deserialized = CompactSerializer.deserializeChainStats(serialized);
        
        assertEquals(stats.getDifficulty(), deserialized.getDifficulty());
        assertEquals(null, deserialized.getBaseDifficultyTarget());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testDeserializeNull() throws IOException {
        CompactSerializer.deserializeChainStats(null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testDeserializeEmpty() throws IOException {
        CompactSerializer.deserializeChainStats(new byte[0]);
    }
}
