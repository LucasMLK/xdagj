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
import lombok.Getter;
import lombok.Setter;

/**
 * Legacy helper class for populating LegacyBlockInfo during snapshot operations
 *
 * @deprecated As of v5.1 refactor (Phase 6.5 - Deep Core Cleanup), this class is a temporary
 *             data transfer object (DTO) used only to populate {@link LegacyBlockInfo} during
 *             snapshot operations. Since LegacyBlockInfo is deprecated, PreBlockInfo is also
 *             deprecated and will be removed when storage migrates to BlockInfo-only format.
 *
 *             <p><b>Why Deprecated:</b>
 *             <ul>
 *             <li><b>Tied to LegacyBlockInfo:</b> Only purpose is to populate LegacyBlockInfo
 *                 via setBlockInfo() method. LegacyBlockInfo is deprecated.</li>
 *             <li><b>Mutable Design:</b> Uses public mutable fields and setters, non-thread-safe.</li>
 *             <li><b>Temporary DTO:</b> Acts as intermediate data structure with no business logic.</li>
 *             <li><b>Limited Usage:</b> Only used in SnapshotStore implementations (4 files).</li>
 *             </ul>
 *
 *             <p><b>v5.1 Replacement: Direct BlockInfo Usage</b>
 *             <pre>{@code
 * // Legacy: PreBlockInfo → LegacyBlockInfo → BlockInfo
 * PreBlockInfo pre = new PreBlockInfo();
 * pre.setHash(hash);
 * pre.setDifficulty(diff);
 * pre.setFee(fee);
 *
 * LegacyBlockInfo legacy = new LegacyBlockInfo();
 * snapshotStore.setBlockInfo(legacy, pre);  // Copy data
 *
 * BlockInfo info = BlockInfo.fromLegacy(legacy);  // Convert
 *
 * // v5.1: Direct BlockInfo construction (Future Phase 7)
 * BlockInfo info = BlockInfo.builder()
 *     .hash(Bytes32.wrap(hash))
 *     .difficulty(UInt256.fromBytes(diff))
 *     .fee(XAmount.of(fee))
 *     .isSnapshot(true)
 *     .snapshotInfo(snapshotInfo)
 *     .build();
 * // No intermediate DTOs needed
 *             }</pre>
 *
 *             <p><b>Current Usage (4 files):</b>
 *             <ul>
 *             <li>{@link io.xdag.db.SnapshotStore#setBlockInfo(LegacyBlockInfo, PreBlockInfo)} - Interface method</li>
 *             <li>{@link io.xdag.db.rocksdb.SnapshotStoreImpl#setBlockInfo(LegacyBlockInfo, PreBlockInfo)} - Implementation</li>
 *             <li>CORE_PACKAGE_LEGACY_ANALYSIS.md - Documentation</li>
 *             </ul>
 *
 *             <p><b>Data Flow (Legacy):</b>
 *             <pre>
 * Snapshot Operation:
 * ┌──────────────┐       ┌──────────────────┐       ┌────────────────┐       ┌─────────────┐
 * │ RocksDB Data │  →    │  PreBlockInfo    │  →    │ LegacyBlockInfo│  →    │  BlockInfo  │
 * │ (raw bytes)  │       │ (temporary DTO)  │       │ (legacy bridge)│       │ (v5.1)      │
 * └──────────────┘       └──────────────────┘       └────────────────┘       └─────────────┘
 *                         setHash()              setBlockInfo()          fromLegacy()
 *                         setDifficulty()        (copies all fields)
 *                         setFee()
 *             </pre>
 *
 *             <p><b>Data Flow (v5.1 - Future Phase 7):</b>
 *             <pre>
 * Snapshot Operation:
 * ┌──────────────┐       ┌─────────────┐
 * │ RocksDB Data │  →    │  BlockInfo  │
 * │ (raw bytes)  │       │ (v5.1)      │
 * └──────────────┘       └─────────────┘
 *                         deserialize()
 *                         (direct construction)
 * </pre>
 *
 *             <p><b>Why Temporary DTOs Are Problematic:</b>
 *             <ul>
 *             <li>Extra object allocations (memory overhead)</li>
 *             <li>Multiple field copy operations (CPU overhead)</li>
 *             <li>Complex data flow (3 objects instead of 1)</li>
 *             <li>Mutable state increases bug risk</li>
 *             <li>Harder to maintain and test</li>
 *             </ul>
 *
 *             <p><b>Removal Timeline:</b>
 *             <ul>
 *             <li>Phase 4 (Storage): Added BlockInfo support, kept PreBlockInfo for compatibility</li>
 *             <li>Phase 6.5 (Current): Deprecated PreBlockInfo and LegacyBlockInfo</li>
 *             <li>Phase 7 (Future): Migrate snapshot storage to BlockInfo-only format</li>
 *             <li>Phase 7 (Future): Remove PreBlockInfo and LegacyBlockInfo classes</li>
 *             </ul>
 *
 *             <p><b>Related Deprecations:</b>
 *             <ul>
 *             <li>{@link LegacyBlockInfo} - The class PreBlockInfo populates</li>
 *             <li>{@link Block} - Uses LegacyBlockInfo constructor</li>
 *             <li>{@link Address} - Legacy reference format</li>
 *             <li>{@link XdagBlock} - Legacy 512-byte serialization</li>
 *             </ul>
 *
 * @see LegacyBlockInfo
 * @see BlockInfo
 * @see io.xdag.db.SnapshotStore
 */
@Deprecated(since = "0.8.1", forRemoval = true)
@Getter
@Setter
public class PreBlockInfo {

    // Block type (main/wallet/transaction/snapshot)
    public long type;
    // Block flags for various attributes
    public int flags;
    // Block height in the chain
    private long height;
    // Block mining difficulty
    private BigInteger difficulty;
    // Reference to previous blocks
    private byte[] ref;
    // Link to block with maximum difficulty
    private byte[] maxDiffLink;
    // Transaction fee amount
    private long fee;
    // Block remark/memo field
    private byte[] remark;
    // Block hash
    private byte[] hash;
    // Block amount/value
    private XAmount amount;
    // Block timestamp
    private long timestamp;

    // Snapshot related fields
    private boolean isSnapshot = false;
    private SnapshotInfo snapshotInfo = null;

}
