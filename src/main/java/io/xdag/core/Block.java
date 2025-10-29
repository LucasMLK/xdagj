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

import io.xdag.crypto.hash.HashUtils;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Block for XDAG v5.1 - Candidate Block
 *
 * Design principles (from CORE_DATA_STRUCTURES.md):
 * 1. Only contains references (Link), not full Transaction/Block data
 * 2. All blocks are candidate blocks (all have nonce and coinbase)
 * 3. Winner block (hash <= difficulty and smallest hash) becomes main block
 * 4. Block only stores hash references, enabling 1,485,000+ links in 48MB
 * 5. Hash is cached (lazy computation)
 *
 * Structure:
 * ```
 * Block {
 *     header: BlockHeader  (timestamp, difficulty, nonce, coinbase, hash_cache)
 *     links: List<Link>    (references to Transactions and other Blocks)
 * }
 * ```
 *
 * Capacity (48MB block):
 * - 48MB / 33 bytes per link ≈ 1,485,000 links
 * - TPS: 1,485,000 txs / 64秒 ≈ 23,200 TPS (96.7% Visa level)
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">v5.1 Design</a>
 */
@Value
@Builder(toBuilder = true)
public class Block implements Serializable {

    /**
     * Block header (participates in hash calculation)
     * Contains: timestamp, difficulty, nonce, coinbase, hash_cache
     */
    BlockHeader header;

    /**
     * DAG links (references to Transactions and other Blocks)
     * Only stores hash references (33 bytes per link)
     * Supports up to 1,485,000 links in 48MB block
     */
    @Builder.Default
    List<Link> links = new ArrayList<>();

    // ========== Core Configuration ==========

    /**
     * Maximum block size: 48MB (soft limit)
     */
    public static final int MAX_BLOCK_SIZE = 48 * 1024 * 1024;

    /**
     * Minimum Block references per block: 1
     * Every block must reference at least one previous main block (prevMainBlock)
     * (from DESIGN_DECISIONS.md D6)
     */
    public static final int MIN_BLOCK_LINKS = 1;

    /**
     * Maximum Block references per block: 16
     * Prevents malicious complex DAG attacks
     * (from DESIGN_DECISIONS.md D6)
     */
    public static final int MAX_BLOCK_LINKS = 16;

    /**
     * Maximum total links per block: ~1,485,000
     * = 48MB / 33 bytes per link
     * This includes both Block and Transaction links
     */
    public static final int MAX_LINKS_PER_BLOCK = 1_485_000;

    /**
     * Target TPS: 23,200
     * = 1,485,000 txs / 64 seconds
     */
    public static final int TARGET_TPS = 23_200;

    // ========== Hash Calculation ==========

    /**
     * Get block hash (with caching)
     *
     * Hash calculation:
     * 1. If hash already cached in header, return it
     * 2. Otherwise: hash = Keccak256(serialize(header) + serialize(links))
     * 3. Cache the hash in header
     * 4. Return hash
     *
     * Thread-safe: uses immutable header with withHash()
     *
     * @return block hash (32 bytes)
     */
    public Bytes32 getHash() {
        if (header.getHash() != null) {
            return header.getHash();
        }

        // Calculate hash
        Bytes32 hash = calculateHash();

        // Cache hash in header (creates new immutable header)
        // Note: This creates a new BlockV5 instance with updated header
        // The caller should use the returned hash, not rely on this instance being updated
        return hash;
    }

    /**
     * Calculate block hash
     *
     * Hash = Keccak256(
     *     timestamp + difficulty + nonce + coinbase +
     *     links.size + links[0] + links[1] + ...
     * )
     *
     * @return calculated hash
     */
    private Bytes32 calculateHash() {
        // Calculate total size
        int headerSize = BlockHeader.getSerializedSize();  // 104 bytes
        int linksSize = 4 + (links.size() * Link.LINK_SIZE);  // 4 bytes size + N×33 bytes

        ByteBuffer buffer = ByteBuffer.allocate(headerSize + linksSize);

        // Serialize header
        buffer.putLong(header.getTimestamp());
        buffer.put(header.getDifficulty().toBytes().toArray());
        buffer.put(header.getNonce().toArray());
        buffer.put(header.getCoinbase().toArray());

        // Serialize links
        buffer.putInt(links.size());
        for (Link link : links) {
            buffer.put(link.toBytes());
        }

        return HashUtils.keccak256(Bytes.wrap(buffer.array()));
    }

    /**
     * Create a new Block with cached hash
     * This method should be called after hash calculation to cache it
     *
     * @param hash calculated hash
     * @return new Block with hash cached in header
     */
    public Block withHash(Bytes32 hash) {
        return this.toBuilder()
                .header(header.toBuilder().hash(hash).build())
                .build();
    }

    // ========== Block Properties ==========

    /**
     * Get epoch number
     * epoch = timestamp / 64
     *
     * @return epoch number
     */
    public long getEpoch() {
        return header.getEpoch();
    }

    /**
     * Get timestamp
     *
     * @return timestamp in seconds
     */
    public long getTimestamp() {
        return header.getTimestamp();
    }

    /**
     * Check if this block satisfies PoW difficulty
     * Valid if: hash <= difficulty
     *
     * @return true if valid PoW
     */
    public boolean isValidPoW() {
        Bytes32 hash = getHash();
        return header.toBuilder().hash(hash).build().satisfiesDifficulty();
    }

    /**
     * Get block size in bytes (for network transmission)
     *
     * @return size in bytes
     */
    public int getSize() {
        return BlockHeader.getSerializedSize() + 4 + (links.size() * Link.LINK_SIZE);
    }

    /**
     * Check if block size exceeds limit
     *
     * @return true if size > MAX_BLOCK_SIZE
     */
    public boolean exceedsMaxSize() {
        return getSize() > MAX_BLOCK_SIZE;
    }

    /**
     * Check if links count exceeds limit
     *
     * @return true if links.size() > MAX_LINKS_PER_BLOCK
     */
    public boolean exceedsMaxLinks() {
        return links.size() > MAX_LINKS_PER_BLOCK;
    }

    // ========== Link Operations ==========

    /**
     * Get all links
     *
     * @return unmodifiable list of links
     */
    public List<Link> getLinks() {
        return List.copyOf(links);
    }

    /**
     * Get transaction links only
     *
     * @return list of transaction links
     */
    public List<Link> getTransactionLinks() {
        return links.stream()
                .filter(Link::isTransaction)
                .toList();
    }

    /**
     * Get block links only
     *
     * @return list of block links
     */
    public List<Link> getBlockLinks() {
        return links.stream()
                .filter(Link::isBlock)
                .toList();
    }

    /**
     * Count transaction links
     *
     * @return number of transaction links
     */
    public int getTransactionCount() {
        return (int) links.stream().filter(Link::isTransaction).count();
    }

    /**
     * Count block links
     *
     * @return number of block links
     */
    public int getBlockRefCount() {
        return (int) links.stream().filter(Link::isBlock).count();
    }

    // ========== Validation ==========

    /**
     * Validate this block
     * Checks:
     * 1. Block size <= MAX_BLOCK_SIZE
     * 2. Total links count <= MAX_LINKS_PER_BLOCK
     * 3. Block references: MIN_BLOCK_LINKS <= count <= MAX_BLOCK_LINKS
     * 4. Header fields are valid (timestamp > 0, difficulty > 0, etc.)
     * 5. PoW is valid (hash <= difficulty)
     *
     * @return true if valid
     */
    public boolean isValid() {
        // Check size limits
        if (exceedsMaxSize() || exceedsMaxLinks()) {
            return false;
        }

        // Check Block reference limits (from DESIGN_DECISIONS.md D6)
        int blockRefCount = getBlockRefCount();
        if (blockRefCount < MIN_BLOCK_LINKS) {
            return false;  // Must reference at least one prevMainBlock
        }
        if (blockRefCount > MAX_BLOCK_LINKS) {
            return false;  // Too many Block references (prevents DAG attacks)
        }

        // Check header validity
        if (header.getTimestamp() <= 0) {
            return false;
        }
        if (header.getDifficulty() == null || header.getDifficulty().isZero()) {
            return false;
        }
        if (header.getNonce() == null || header.getCoinbase() == null) {
            return false;
        }

        // Check PoW
        return isValidPoW();
    }

    // ========== Factory Methods ==========

    /**
     * Create a candidate block (for mining)
     *
     * @param timestamp block timestamp
     * @param difficulty PoW difficulty target
     * @param coinbase miner address
     * @param links DAG links (transaction and block references)
     * @return candidate block (nonce not set, needs mining)
     */
    public static Block createCandidate(
            long timestamp,
            org.apache.tuweni.units.bigints.UInt256 difficulty,
            Bytes32 coinbase,
            List<Link> links) {

        BlockHeader header = BlockHeader.builder()
                .timestamp(timestamp)
                .difficulty(difficulty)
                .nonce(Bytes32.ZERO)  // Will be set by mining
                .coinbase(coinbase)
                .hash(null)  // Will be calculated
                .build();

        return Block.builder()
                .header(header)
                .links(new ArrayList<>(links))
                .build();
    }

    /**
     * Create a block with specified nonce (after mining)
     *
     * @param timestamp block timestamp
     * @param difficulty PoW difficulty target
     * @param nonce PoW nonce (found by mining)
     * @param coinbase miner address
     * @param links DAG links
     * @return block with nonce set
     */
    public static Block createWithNonce(
            long timestamp,
            org.apache.tuweni.units.bigints.UInt256 difficulty,
            Bytes32 nonce,
            Bytes32 coinbase,
            List<Link> links) {

        BlockHeader header = BlockHeader.builder()
                .timestamp(timestamp)
                .difficulty(difficulty)
                .nonce(nonce)
                .coinbase(coinbase)
                .hash(null)
                .build();

        Block block = Block.builder()
                .header(header)
                .links(new ArrayList<>(links))
                .build();

        // Calculate and cache hash
        Bytes32 hash = block.calculateHash();
        return block.withHash(hash);
    }

    // ========== Serialization ==========

    /**
     * Serialize block to bytes (for network transmission or storage)
     *
     * Format:
     * [Header - 104 bytes]
     *   timestamp (8) + difficulty (32) + nonce (32) + coinbase (32)
     *
     * [Links - variable]
     *   links_count (4) + link[0] (33) + link[1] (33) + ...
     *
     * @return serialized bytes
     */
    public byte[] toBytes() {
        int size = getSize();
        ByteBuffer buffer = ByteBuffer.allocate(size);

        // Serialize header (hash NOT included, it's cached)
        buffer.putLong(header.getTimestamp());
        buffer.put(header.getDifficulty().toBytes().toArray());
        buffer.put(header.getNonce().toArray());
        buffer.put(header.getCoinbase().toArray());

        // Serialize links
        buffer.putInt(links.size());
        for (Link link : links) {
            buffer.put(link.toBytes());
        }

        return buffer.array();
    }

    /**
     * Deserialize block from bytes
     *
     * @param bytes serialized block data
     * @return Block instance
     * @throws IllegalArgumentException if data is invalid
     */
    public static Block fromBytes(byte[] bytes) {
        if (bytes.length < BlockHeader.getSerializedSize() + 4) {
            throw new IllegalArgumentException(
                "Invalid block data: too small (" + bytes.length + " bytes)"
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        // Deserialize header
        long timestamp = buffer.getLong();

        byte[] diffBytes = new byte[32];
        buffer.get(diffBytes);
        org.apache.tuweni.units.bigints.UInt256 difficulty =
            org.apache.tuweni.units.bigints.UInt256.fromBytes(Bytes.wrap(diffBytes));

        byte[] nonceBytes = new byte[32];
        buffer.get(nonceBytes);
        Bytes32 nonce = Bytes32.wrap(nonceBytes);

        byte[] coinbaseBytes = new byte[32];
        buffer.get(coinbaseBytes);
        Bytes32 coinbase = Bytes32.wrap(coinbaseBytes);

        // Deserialize links
        int linksCount = buffer.getInt();
        if (linksCount < 0 || linksCount > MAX_LINKS_PER_BLOCK) {
            throw new IllegalArgumentException(
                "Invalid links count: " + linksCount
            );
        }

        List<Link> links = new ArrayList<>(linksCount);
        for (int i = 0; i < linksCount; i++) {
            byte[] linkBytes = new byte[Link.LINK_SIZE];
            buffer.get(linkBytes);
            links.add(Link.fromBytes(linkBytes));
        }

        // Create block
        return createWithNonce(timestamp, difficulty, nonce, coinbase, links);
    }

    // ========== Object Methods ==========

    @Override
    public String toString() {
        return String.format(
            "Block[epoch=%d, timestamp=%d, hash=%s, links=%d (%d txs, %d blocks), size=%d bytes]",
            getEpoch(),
            getTimestamp(),
            header.getHash() != null ? header.getHash().toHexString().substring(0, 16) + "..." : "not_calculated",
            links.size(),
            getTransactionCount(),
            getBlockRefCount(),
            getSize()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Block)) return false;
        Block block = (Block) o;
        return Objects.equals(getHash(), block.getHash());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getHash());
    }
}
