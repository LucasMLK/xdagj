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

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for BlockV5 (v5.1)
 */
public class BlockV5Test {

    @Test
    public void testCreateCandidate() {
        long timestamp = 128;
        UInt256 difficulty = UInt256.ONE;
        Bytes32 coinbase = Bytes32.random();
        List<Link> links = List.of(
            Link.toTransaction(Bytes32.random()),
            Link.toBlock(Bytes32.random())
        );

        BlockV5 block = BlockV5.createCandidate(timestamp, difficulty, coinbase, links);

        assertNotNull(block);
        assertEquals(timestamp, block.getTimestamp());
        assertEquals(2, block.getEpoch());
        assertEquals(2, block.getLinks().size());
    }

    @Test
    public void testCreateWithNonce() {
        long timestamp = 64;
        UInt256 difficulty = UInt256.ONE;
        Bytes32 nonce = Bytes32.random();
        Bytes32 coinbase = Bytes32.random();
        List<Link> links = List.of(Link.toTransaction(Bytes32.random()));

        BlockV5 block = BlockV5.createWithNonce(timestamp, difficulty, nonce, coinbase, links);

        assertNotNull(block);
        assertNotNull(block.getHash());
        assertEquals(nonce, block.getHeader().getNonce());
    }

    @Test
    public void testHashCalculation() {
        BlockV5 block = BlockV5.createCandidate(
            100,
            UInt256.ONE,
            Bytes32.ZERO,
            List.of(Link.toTransaction(Bytes32.random()))
        );

        Bytes32 hash1 = block.getHash();
        Bytes32 hash2 = block.getHash();

        assertNotNull(hash1);
        assertEquals(hash1, hash2);  // Hash caching
    }

    @Test
    public void testLinkOperations() {
        Bytes32 tx1 = Bytes32.random();
        Bytes32 tx2 = Bytes32.random();
        Bytes32 block1 = Bytes32.random();

        List<Link> links = List.of(
            Link.toTransaction(tx1),
            Link.toTransaction(tx2),
            Link.toBlock(block1)
        );

        BlockV5 block = BlockV5.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links);

        assertEquals(3, block.getLinks().size());
        assertEquals(2, block.getTransactionCount());
        assertEquals(1, block.getBlockRefCount());

        List<Link> txLinks = block.getTransactionLinks();
        assertEquals(2, txLinks.size());
        assertTrue(txLinks.stream().allMatch(Link::isTransaction));

        List<Link> blockLinks = block.getBlockLinks();
        assertEquals(1, blockLinks.size());
        assertTrue(blockLinks.stream().allMatch(Link::isBlock));
    }

    @Test
    public void testSerialization() {
        Bytes32 nonce = Bytes32.random();
        Bytes32 coinbase = Bytes32.random();
        List<Link> links = List.of(
            Link.toTransaction(Bytes32.random()),
            Link.toTransaction(Bytes32.random())
        );

        BlockV5 original = BlockV5.createWithNonce(100, UInt256.ONE, nonce, coinbase, links);

        byte[] bytes = original.toBytes();
        BlockV5 deserialized = BlockV5.fromBytes(bytes);

        assertEquals(original.getTimestamp(), deserialized.getTimestamp());
        assertEquals(original.getHeader().getNonce(), deserialized.getHeader().getNonce());
        assertEquals(original.getHeader().getCoinbase(), deserialized.getHeader().getCoinbase());
        assertEquals(original.getLinks().size(), deserialized.getLinks().size());
        assertEquals(original.getHash(), deserialized.getHash());
    }

    @Test
    public void testBlockSize() {
        List<Link> links = List.of(
            Link.toTransaction(Bytes32.random()),
            Link.toTransaction(Bytes32.random())
        );

        BlockV5 block = BlockV5.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links);

        int expectedSize = BlockHeader.getSerializedSize() + 4 + (2 * Link.LINK_SIZE);
        assertEquals(expectedSize, block.getSize());
    }

    @Test
    public void testMaxSizeValidation() {
        // Test exceeding link count
        List<Link> tooManyLinks = new ArrayList<>();
        for (int i = 0; i < BlockV5.MAX_LINKS_PER_BLOCK + 1; i++) {
            tooManyLinks.add(Link.toTransaction(Bytes32.random()));
        }
        BlockV5 blockExceedingLinks = BlockV5.createCandidate(100, UInt256.ONE, Bytes32.ZERO, tooManyLinks);
        assertTrue(blockExceedingLinks.exceedsMaxLinks());

        // Test exceeding size (need ~1,526,000 links to exceed 48MB)
        List<Link> lotsOfLinks = new ArrayList<>();
        for (int i = 0; i < 1_526_000; i++) {
            lotsOfLinks.add(Link.toTransaction(Bytes32.random()));
        }
        BlockV5 blockExceedingSize = BlockV5.createCandidate(100, UInt256.ONE, Bytes32.ZERO, lotsOfLinks);
        assertTrue(blockExceedingSize.exceedsMaxSize());
        assertTrue(blockExceedingSize.exceedsMaxLinks());  // This also exceeds link count
    }

    @Test
    public void testValidation() {
        // Create valid block with very low difficulty
        UInt256 easyDifficulty = UInt256.fromHexString(
            "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        );

        BlockV5 block = BlockV5.createWithNonce(
            100,
            easyDifficulty,
            Bytes32.ZERO,
            Bytes32.random(),
            List.of(Link.toTransaction(Bytes32.random()))
        );

        // Block should be valid (hash will almost certainly be <= max difficulty)
        assertTrue(block.isValid());
    }

    @Test
    public void testInvalidBlockWithZeroTimestamp() {
        BlockHeader header = BlockHeader.builder()
                .timestamp(0)
                .difficulty(UInt256.ZERO)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes32.ZERO)
                .build();

        BlockV5 block = BlockV5.builder()
                .header(header)
                .links(List.of())
                .build();

        assertFalse(block.isValid());
    }

    @Test
    public void testImmutability() {
        List<Link> links1 = new ArrayList<>();
        links1.add(Link.toTransaction(Bytes32.random()));

        BlockV5 block1 = BlockV5.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links1);

        // Modify original list
        links1.add(Link.toTransaction(Bytes32.random()));

        // Block should be unchanged
        assertEquals(1, block1.getLinks().size());
    }

    @Test
    public void testEquality() {
        Bytes32 nonce = Bytes32.random();
        List<Link> links = List.of(Link.toTransaction(Bytes32.random()));

        BlockV5 block1 = BlockV5.createWithNonce(100, UInt256.ONE, nonce, Bytes32.ZERO, links);
        BlockV5 block2 = BlockV5.createWithNonce(100, UInt256.ONE, nonce, Bytes32.ZERO, links);

        // Same content, same hash
        assertEquals(block1.getHash(), block2.getHash());
        assertEquals(block1, block2);
    }

    @Test
    public void testToString() {
        BlockV5 block = BlockV5.createWithNonce(
            128,
            UInt256.ONE,
            Bytes32.ZERO,
            Bytes32.random(),
            List.of(
                Link.toTransaction(Bytes32.random()),
                Link.toBlock(Bytes32.random())
            )
        );

        String str = block.toString();
        assertTrue(str.contains("epoch=2"));
        assertTrue(str.contains("links=2"));
        assertTrue(str.contains("1 txs"));
        assertTrue(str.contains("1 blocks"));
    }

    @Test
    public void testConstants() {
        assertEquals(48 * 1024 * 1024, BlockV5.MAX_BLOCK_SIZE);
        assertEquals(1_485_000, BlockV5.MAX_LINKS_PER_BLOCK);
        assertEquals(23_200, BlockV5.TARGET_TPS);
    }
}
