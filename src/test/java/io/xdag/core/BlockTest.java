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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

/**
 * Unit tests for Block (v5.1)
 *
 * Tests the v5.1 Block implementation which uses BlockHeader + List<Link> design.
 * This test suite covers all core functionality including creation, validation,
 * serialization, and Block reference limits.
 */
public class BlockTest {

    @Test
    public void testCreateCandidate() {
        long timestamp = 128;
        UInt256 difficulty = UInt256.ONE;
        Bytes32 coinbase = Bytes32.random();
        List<Link> links = List.of(
            Link.toTransaction(Bytes32.random()),
            Link.toBlock(Bytes32.random())
        );

        Block block = Block.createCandidate(timestamp, difficulty, coinbase, links);

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

        Block block = Block.createWithNonce(timestamp, difficulty, nonce, coinbase, links);

        assertNotNull(block);
        assertNotNull(block.getHash());
        assertEquals(nonce, block.getHeader().getNonce());
    }

    @Test
    public void testHashCalculation() {
        Block block = Block.createCandidate(
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

        Block block = Block.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links);

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

        Block original = Block.createWithNonce(100, UInt256.ONE, nonce, coinbase, links);

        byte[] bytes = original.toBytes();
        Block deserialized = Block.fromBytes(bytes);

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

        Block block = Block.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links);

        int expectedSize = BlockHeader.getSerializedSize() + 4 + (2 * Link.LINK_SIZE);
        assertEquals(expectedSize, block.getSize());
    }

    @Test
    public void testMaxSizeValidation() {
        // Test exceeding link count
        List<Link> tooManyLinks = new ArrayList<>();
        for (int i = 0; i < Block.MAX_LINKS_PER_BLOCK + 1; i++) {
            tooManyLinks.add(Link.toTransaction(Bytes32.random()));
        }
        Block blockExceedingLinks = Block.createCandidate(100, UInt256.ONE, Bytes32.ZERO, tooManyLinks);
        assertTrue(blockExceedingLinks.exceedsMaxLinks());

        // Test exceeding size (need ~1,526,000 links to exceed 48MB)
        List<Link> lotsOfLinks = new ArrayList<>();
        for (int i = 0; i < 1_526_000; i++) {
            lotsOfLinks.add(Link.toTransaction(Bytes32.random()));
        }
        Block blockExceedingSize = Block.createCandidate(100, UInt256.ONE, Bytes32.ZERO, lotsOfLinks);
        assertTrue(blockExceedingSize.exceedsMaxSize());
        assertTrue(blockExceedingSize.exceedsMaxLinks());  // This also exceeds link count
    }

    @Test
    public void testValidation() {
        // Create valid block with very low difficulty
        UInt256 easyDifficulty = UInt256.fromHexString(
            "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        );

        Block block = Block.createWithNonce(
            100,
            easyDifficulty,
            Bytes32.ZERO,
            Bytes32.random(),
            List.of(Link.toBlock(Bytes32.random()), Link.toTransaction(Bytes32.random()))
        );

        // Block should be valid (hash will almost certainly be <= max difficulty)
        assertTrue(block.isValid());
    }

    @Test
    public void testBlockReferenceLimits() {
        UInt256 easyDifficulty = UInt256.fromHexString(
            "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        );

        // Test: No Block references (invalid, must have at least 1)
        List<Link> noBlockLinks = List.of(
            Link.toTransaction(Bytes32.random())
        );
        Block blockNoRefs = Block.createWithNonce(
            100, easyDifficulty, Bytes32.ZERO, Bytes32.random(), noBlockLinks
        );
        assertFalse(blockNoRefs.isValid());  // Should fail: blockRefCount < MIN_BLOCK_LINKS

        // Test: Minimum valid (1 Block reference)
        List<Link> oneBlockLink = List.of(
            Link.toBlock(Bytes32.random()),
            Link.toTransaction(Bytes32.random())
        );
        Block blockOneRef = Block.createWithNonce(
            100, easyDifficulty, Bytes32.ZERO, Bytes32.random(), oneBlockLink
        );
        assertTrue(blockOneRef.isValid());  // Should pass: blockRefCount = 1

        // Test: Maximum valid (16 Block references)
        List<Link> maxBlockLinks = new ArrayList<>();
        for (int i = 0; i < Block.MAX_BLOCK_LINKS; i++) {
            maxBlockLinks.add(Link.toBlock(Bytes32.random()));
        }
        maxBlockLinks.add(Link.toTransaction(Bytes32.random()));
        Block blockMaxRefs = Block.createWithNonce(
            100, easyDifficulty, Bytes32.ZERO, Bytes32.random(), maxBlockLinks
        );
        assertTrue(blockMaxRefs.isValid());  // Should pass: blockRefCount = 16

        // Test: Too many Block references (17, invalid)
        List<Link> tooManyBlockLinks = new ArrayList<>();
        for (int i = 0; i < Block.MAX_BLOCK_LINKS + 1; i++) {
            tooManyBlockLinks.add(Link.toBlock(Bytes32.random()));
        }
        Block blockTooManyRefs = Block.createWithNonce(
            100, easyDifficulty, Bytes32.ZERO, Bytes32.random(), tooManyBlockLinks
        );
        assertFalse(blockTooManyRefs.isValid());  // Should fail: blockRefCount > MAX_BLOCK_LINKS
    }

    @Test
    public void testInvalidBlockWithZeroTimestamp() {
        BlockHeader header = BlockHeader.builder()
                .timestamp(0)
                .difficulty(UInt256.ZERO)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes32.ZERO)
                .build();

        Block block = Block.builder()
                .header(header)
                .links(List.of())
                .build();

        assertFalse(block.isValid());
    }

    @Test
    public void testImmutability() {
        List<Link> links1 = new ArrayList<>();
        links1.add(Link.toTransaction(Bytes32.random()));

        Block block1 = Block.createCandidate(100, UInt256.ONE, Bytes32.ZERO, links1);

        // Modify original list
        links1.add(Link.toTransaction(Bytes32.random()));

        // Block should be unchanged
        assertEquals(1, block1.getLinks().size());
    }

    @Test
    public void testEquality() {
        Bytes32 nonce = Bytes32.random();
        List<Link> links = List.of(Link.toTransaction(Bytes32.random()));

        Block block1 = Block.createWithNonce(100, UInt256.ONE, nonce, Bytes32.ZERO, links);
        Block block2 = Block.createWithNonce(100, UInt256.ONE, nonce, Bytes32.ZERO, links);

        // Same content, same hash
        assertEquals(block1.getHash(), block2.getHash());
        assertEquals(block1, block2);
    }

    @Test
    public void testToString() {
        Block block = Block.createWithNonce(
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
        assertEquals(48 * 1024 * 1024, Block.MAX_BLOCK_SIZE);
        assertEquals(1, Block.MIN_BLOCK_LINKS);
        assertEquals(16, Block.MAX_BLOCK_LINKS);
        assertEquals(1_485_000, Block.MAX_LINKS_PER_BLOCK);
        assertEquals(23_200, Block.TARGET_TPS);
    }
}
