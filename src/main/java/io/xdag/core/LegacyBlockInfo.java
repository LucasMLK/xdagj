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


import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

/**
 * Legacy mutable BlockInfo class with 24-byte truncated hash (v1.0 architecture)
 *
 * @deprecated As of v5.1 refactor (Phase 6.5 - Deep Core Cleanup), this class represents
 *             the legacy mutable BlockInfo implementation used for backward compatibility
 *             with old storage formats. In v5.1, the immutable {@link BlockInfo} class
 *             is used instead.
 *
 *             <p><b>Why Deprecated:</b>
 *             <ul>
 *             <li><b>Mutable Design:</b> LegacyBlockInfo uses public mutable fields and setters,
 *                 making it non-thread-safe. BlockInfo is immutable with builder pattern.</li>
 *             <li><b>Truncated Hash:</b> Uses 24-byte truncated hash (hashlow) instead of full
 *                 32-byte hash. BlockInfo uses Bytes32 for full hash storage.</li>
 *             <li><b>Primitive Types:</b> Uses raw byte[] for hash, ref, maxDiffLink.
 *                 BlockInfo uses typed Bytes32 objects.</li>
 *             <li><b>Storage Format Coupling:</b> Designed for legacy RocksDB storage format.
 *                 BlockInfo is storage-agnostic.</li>
 *             <li><b>Migration Bridge:</b> Only purpose is BlockInfo.fromLegacy() conversion.
 *                 After storage migration (Phase 7), this class will be removed.</li>
 *             </ul>
 *
 *             <p><b>v5.1 Replacement: {@link BlockInfo}</b>
 *             <pre>{@code
 * // Legacy: Mutable with truncated hash
 * LegacyBlockInfo legacy = new LegacyBlockInfo();
 * legacy.setHashlow(hash24bytes);  // Only 24 bytes
 * legacy.setHeight(123);           // Mutable
 * legacy.setFlags(flags);          // Mutable
 *
 * // v5.1: Immutable with full hash
 * BlockInfo info = BlockInfo.builder()
 *     .hash(Bytes32.wrap(hash32bytes))  // Full 32 bytes
 *     .height(123)
 *     .flags(flags)
 *     .build();
 * // Immutable - no setters
 *             }</pre>
 *
 *             <p><b>Hash Format Comparison:</b>
 *             <table border="1">
 *             <tr><th>Format</th><th>Size</th><th>Type</th><th>Usage</th></tr>
 *             <tr><td><b>LegacyBlockInfo.hashlow</b></td><td>24 bytes</td><td>byte[]</td>
 *                 <td>Truncated for legacy storage</td></tr>
 *             <tr><td><b>BlockInfo.hash</b></td><td>32 bytes</td><td>Bytes32</td>
 *                 <td>Full hash for v5.1</td></tr>
 *             </table>
 *
 *             <p><b>Migration Path:</b>
 *             <br>LegacyBlockInfo is used during storage migration to convert old format to new:
 *             <pre>{@code
 * // Phase 4 Storage: Reading legacy storage
 * byte[] legacyData = rocksdb.get(key);
 * LegacyBlockInfo legacy = deserialize(legacyData);
 *
 * // Convert to new immutable BlockInfo
 * BlockInfo info = BlockInfo.fromLegacy(legacy);
 *
 * // Future Phase 7: Direct BlockInfo storage (no LegacyBlockInfo)
 * BlockInfo info = BlockInfo.deserialize(data);
 *             }</pre>
 *
 *             <p><b>Current Usage (18 files):</b>
 *             <ul>
 *             <li>{@link Block#Block(LegacyBlockInfo)} - Legacy Block constructor</li>
 *             <li>{@link BlockInfo#fromLegacy(LegacyBlockInfo)} - Migration method</li>
 *             <li>{@link io.xdag.db.BlockStore} - Storage layer interfaces</li>
 *             <li>{@link io.xdag.db.rocksdb.BlockStoreImpl} - RocksDB implementation</li>
 *             <li>SnapshotStore - Snapshot operations</li>
 *             <li>Various test files</li>
 *             </ul>
 *
 *             <p><b>Design Differences:</b>
 *             <pre>
 * LegacyBlockInfo (Mutable):
 * ┌─────────────────────────────────────┐
 * │ public long type               (8B) │  Direct field access
 * │ public int flags               (4B) │  Public mutable
 * │ private byte[] hashlow        (24B) │  Truncated hash
 * │ private byte[] ref            (24B) │  Raw bytes
 * │ private byte[] maxDiffLink    (24B) │  Raw bytes
 * │ ... (setters for all fields)        │
 * └─────────────────────────────────────┘
 *
 * BlockInfo (Immutable):
 * ┌─────────────────────────────────────┐
 * │ Bytes32 hash                  (32B) │  Full hash, typed
 * │ long timestamp                 (8B) │  Immutable
 * │ long height                    (8B) │  Immutable
 * │ long type                      (8B) │  Immutable
 * │ int flags                      (4B) │  Immutable
 * │ UInt256 difficulty           (32B)  │  Typed
 * │ Bytes32 ref                   (32B) │  Full hash, typed
 * │ Bytes32 maxDiffLink           (32B) │  Full hash, typed
 * │ ... (no setters, builder only)      │
 * └─────────────────────────────────────┘
 *             </pre>
 *
 *             <p><b>Why 24-byte Hash is Legacy:</b>
 *             <br>In early XDAG implementation, only lower 24 bytes of hash were stored
 *             to save space. This caused issues:
 *             <ul>
 *             <li>Ambiguous references (hash collisions)</li>
 *             <li>Inconsistent with full 32-byte Bytes32 standard</li>
 *             <li>Complicates hash comparisons</li>
 *             <li>Not compatible with standard SHA-256 tooling</li>
 *             </ul>
 *
 *             <p><b>Thread Safety:</b>
 *             <pre>{@code
 * // Legacy: NOT thread-safe (mutable)
 * LegacyBlockInfo legacy = new LegacyBlockInfo();
 * thread1: legacy.setHeight(100);  // Race condition!
 * thread2: legacy.setHeight(200);  // Race condition!
 *
 * // v5.1: Thread-safe (immutable)
 * BlockInfo info = BlockInfo.builder().height(100).build();
 * // No setters - safe to share across threads
 *             }</pre>
 *
 *             <p><b>Performance Impact:</b>
 *             <br>Removing LegacyBlockInfo enables:
 *             <ul>
 *             <li>Thread-safe immutable design (safe multi-threaded access)</li>
 *             <li>Full 32-byte hash (eliminates collision risk)</li>
 *             <li>Typed fields (Bytes32, UInt256) instead of raw byte[]</li>
 *             <li>Builder pattern (cleaner construction)</li>
 *             <li>Defensive copying eliminated (immutability guarantees)</li>
 *             </ul>
 *
 *             <p><b>Removal Timeline:</b>
 *             <ul>
 *             <li>Phase 4 (Storage): Added BlockInfo support alongside LegacyBlockInfo</li>
 *             <li>Phase 6.5 (Current): Deprecated LegacyBlockInfo</li>
 *             <li>Phase 7 (Future): Storage migration to BlockInfo-only format</li>
 *             <li>Phase 7 (Future): Remove LegacyBlockInfo class entirely</li>
 *             </ul>
 *
 *             <p><b>Related Deprecations:</b>
 *             <ul>
 *             <li>{@link Block} - Uses LegacyBlockInfo constructor</li>
 *             <li>{@link PreBlockInfo} - Helper class for LegacyBlockInfo population</li>
 *             <li>{@link Address} - Legacy reference format</li>
 *             <li>{@link XdagBlock} - Legacy 512-byte serialization</li>
 *             </ul>
 *
 * @see BlockInfo
 * @see BlockInfo#fromLegacy(LegacyBlockInfo)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
@Getter
@Setter
public class LegacyBlockInfo {

    // Block type
    public long type;
    // Block flags indicating various states
    public int flags;
    // Block height in the chain
    private long height;
    // Block difficulty value
    private BigInteger difficulty;
    // Reference to another block
    private byte[] ref;
    // Link to block with maximum difficulty
    private byte[] maxDiffLink;
    // Transaction fee
    private XAmount fee = XAmount.ZERO;
    // Block remark/memo field
    private byte[] remark;
    // Legacy 24-byte truncated hash format (preserved for backward compatibility)
    private byte[] hashlow;
    // Block amount/value
    private XAmount amount = XAmount.ZERO;
    // Block timestamp
    private long timestamp;

    // Snapshot related fields
    private boolean isSnapshot = false;
    private SnapshotInfo snapshotInfo = null;

    @Override
    public String toString() {
        return "LegacyBlockInfo{" +
                "height=" + height +
                ", hashlow=" + Arrays.toString(hashlow) +
                ", amount=" + amount.toString() +
                ", type=" + type +
                ", difficulty=" + difficulty +
                ", ref=" + Arrays.toString(ref) +
                ", maxDiffLink=" + Arrays.toString(maxDiffLink) +
                ", flags=" + Integer.toHexString(flags) +
                ", fee=" + fee.toString() +
                ", timestamp=" + timestamp +
                ", remark=" + Arrays.toString(remark) +
                ", isSnapshot=" + isSnapshot +
                ", snapshotInfo=" + snapshotInfo +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LegacyBlockInfo blockInfo = (LegacyBlockInfo) o;
        return type == blockInfo.type &&
                flags == blockInfo.flags &&
                height == blockInfo.height &&
                timestamp == blockInfo.timestamp &&
                Arrays.equals(hashlow, blockInfo.hashlow);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, flags, height, timestamp);
        result = 31 * result + Arrays.hashCode(hashlow);
        return result;
    }
}
