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
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * BlockV5 for XDAG v5.1 - Candidate Block
 *
 * NOTE: This is the v5.1 Block implementation. During Phase 3 migration, it coexists
 * with the legacy Block.java. Once migration is complete, this class will be renamed
 * back to Block.java and the legacy Block will be removed.
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
 * BlockV5 {
 *     header: BlockHeader  (timestamp, difficulty, nonce, coinbase, hash_cache)
 *     links: List<Link>    (references to Transactions and other Blocks)
 *     info: BlockInfo      (runtime metadata, not serialized)
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
public class BlockV5 implements Serializable {

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

    /**
     * Block metadata (runtime only, not serialized)
     *
     * Phase 4 Step 2.3: BlockInfo integration
     * - Contains: flags, difficulty, ref, maxDiffLink, amount, fee, etc.
     * - Loaded from BlockStore at runtime
     * - Does NOT participate in serialization (toBytes/fromBytes)
     * - Does NOT participate in equals/hashCode (only hash is used)
     *
     * Usage:
     * - getInfo(): Get BlockInfo (may be null if not loaded)
     * - withInfo(info): Create new BlockV5 with BlockInfo attached
     */
    @Builder.Default
    BlockInfo info = null;

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
     * Create a new BlockV5 with cached hash
     * This method should be called after hash calculation to cache it
     *
     * @param hash calculated hash
     * @return new BlockV5 with hash cached in header
     */
    public BlockV5 withHash(Bytes32 hash) {
        return this.toBuilder()
                .header(header.toBuilder().hash(hash).build())
                .build();
    }

    /**
     * Create a new BlockV5 with updated nonce (for mining)
     *
     * Phase 5.5: This method enables updating nonce during POW mining.
     * Since BlockV5 is immutable, this creates a new instance with the new nonce.
     *
     * Usage during mining:
     * ```java
     * BlockV5 template = blockchain.createMainBlockV5();  // nonce = 0
     * // Mining finds better nonce
     * BlockV5 minedBlock = template.withNonce(bestNonce);  // Create new instance
     * ```
     *
     * @param nonce POW nonce (32 bytes)
     * @return new BlockV5 with updated nonce
     */
    public BlockV5 withNonce(Bytes32 nonce) {
        return this.toBuilder()
                .header(header.toBuilder().nonce(nonce).hash(null).build())  // Clear hash cache
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

    // ========== BlockInfo Operations (Phase 4 Step 2.3) ==========

    /**
     * Create new BlockV5 with BlockInfo attached
     *
     * Phase 4 Step 2.3: This method allows attaching runtime metadata to BlockV5
     *
     * Usage:
     * ```java
     * BlockV5 blockWithInfo = block.withInfo(newInfo);
     * ```
     *
     * @param newInfo BlockInfo to attach
     * @return new BlockV5 with BlockInfo attached
     */
    public BlockV5 withInfo(BlockInfo newInfo) {
        return this.toBuilder().info(newInfo).build();
    }

    /**
     * Get BlockInfo (may be null if not loaded from BlockStore)
     *
     * Phase 4 Step 2.3: BlockInfo contains runtime metadata:
     * - flags (BI_MAIN, BI_APPLIED, BI_REF, BI_MAIN_REF, etc.)
     * - difficulty, ref, maxDiffLink
     * - amount, fee
     * - remark, snapshot info
     *
     * Note: Use withInfo() to attach BlockInfo after loading from BlockStore
     *
     * @return BlockInfo or null if not loaded
     */
    public BlockInfo getInfo() {
        return info;
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
     *    (Exception: Genesis block can have 0 links)
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

        // Phase 7.5: Allow genesis block with empty links
        // Genesis block is identified by: empty links list and difficulty == 1
        boolean isGenesis = (links.isEmpty() &&
                           header.getDifficulty() != null &&
                           header.getDifficulty().equals(org.apache.tuweni.units.bigints.UInt256.ONE));

        if (!isGenesis && blockRefCount < MIN_BLOCK_LINKS) {
            return false;  // Non-genesis blocks must reference at least one prevMainBlock
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
    public static BlockV5 createCandidate(
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

        return BlockV5.builder()
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
    public static BlockV5 createWithNonce(
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

        BlockV5 block = BlockV5.builder()
                .header(header)
                .links(new ArrayList<>(links))
                .build();

        // Calculate and cache hash
        Bytes32 hash = block.calculateHash();
        return block.withHash(hash);
    }

    // ========== Mining & RandomX Support (Phase 5.5) ==========

    /**
     * Calculate preHash for RandomX mining
     *
     * Phase 5.5: This method replaces the legacy block.getXdagBlock().getData().slice(0, 480)
     * approach. For BlockV5, we use the serialized header + links metadata as input.
     *
     * PreHash calculation:
     * 1. Serialize header (timestamp, difficulty, nonce, coinbase) = 104 bytes
     * 2. Serialize links metadata (count + first few link hashes) to reach ~480 bytes
     * 3. Calculate SHA256 of the combined data
     *
     * This preHash is used by RandomX for POW mining:
     * - taskData = [preHash(32 bytes) + share(32 bytes)]
     * - RandomX calculates: hash = randomx(taskData, seed)
     *
     * @return preHash (32 bytes) for RandomX input
     */
    public Bytes32 getRandomXPreHash() {
        // Calculate how much data we need for ~480 bytes equivalent
        // Header: 104 bytes (timestamp 8 + difficulty 32 + nonce 32 + coinbase 32)
        // Links: 4 bytes (count) + N × 33 bytes (each link)
        // Target: ~480 bytes to match legacy behavior

        int headerSize = BlockHeader.getSerializedSize();  // 104 bytes
        int remainingSize = 480 - headerSize;  // 376 bytes
        int maxLinks = Math.min((remainingSize - 4) / Link.LINK_SIZE, links.size());  // ~11 links

        ByteBuffer buffer = ByteBuffer.allocate(headerSize + 4 + (maxLinks * Link.LINK_SIZE));

        // Serialize header
        buffer.putLong(header.getTimestamp());
        buffer.put(header.getDifficulty().toBytes().toArray());
        buffer.put(header.getNonce().toArray());
        buffer.put(header.getCoinbase().toArray());

        // Serialize links metadata (count + first N links)
        buffer.putInt(links.size());  // Total link count
        for (int i = 0; i < maxLinks; i++) {
            buffer.put(links.get(i).toBytes());
        }

        // Calculate SHA256 of the data (equivalent to legacy SHA256(block.getData().slice(0, 480)))
        return HashUtils.sha256(Bytes.wrap(buffer.array()));
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
     * @return BlockV5 instance
     * @throws IllegalArgumentException if data is invalid
     */
    public static BlockV5 fromBytes(byte[] bytes) {
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
            "BlockV5[epoch=%d, timestamp=%d, hash=%s, links=%d (%d txs, %d blocks), size=%d bytes]",
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
        if (!(o instanceof BlockV5)) return false;
        BlockV5 block = (BlockV5) o;
        return Objects.equals(getHash(), block.getHash());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getHash());
    }
}
