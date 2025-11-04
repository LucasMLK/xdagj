# Phase 4: Storage Layer Migration to BlockV5 - Implementation Plan

**Date**: 2025-10-30
**Branch**: refactor/core-v5.1
**Status**: 📋 **PLANNING**
**Estimated Duration**: 5-7 days

---

## 📋 Executive Summary

Phase 4 will migrate the storage layer from legacy `Block` to `BlockV5`, completing the v5.1 architecture transition. This phase enables BlockStore to save/load BlockV5, allowing the entire system to operate exclusively with the new v5.1 data structures.

**Key Goal**: Enable BlockStore to persist BlockV5 blocks, replacing all Block storage operations.

**Migration Strategy**: **Complete Refactor** (Stop-and-Migrate)
- Export snapshot (Block format)
- Convert Block → BlockV5
- Import snapshot (BlockV5 format)
- System restarts with pure BlockV5 storage

**Progress**: **Phase 4: 0% Complete** (Planning phase)

---

## 🔍 Current Storage Layer Analysis

### Storage Architecture (Discovered)

**BlockStoreImpl.java** uses 3 RocksDB data stores:

1. **blockSource**: `<hash, rawBlockData>`
   - Stores 512-byte raw block data
   - Key: Block hash (32 bytes)
   - Value: `block.getXdagBlock().getData()` (512 bytes)

2. **indexSource**: `<prefix+key, value>`
   - Stores BlockInfo metadata
   - Stores indexes (time, height, epoch, refs)
   - Key: `HASH_BLOCK_INFO + hash`
   - Value: Serialized BlockInfo (CompactSerializer)

3. **timeSource**: `<prefix+time+hash, marker>`
   - Time-based index for range queries
   - Key: `TIME_HASH_INFO + timestamp + hash`
   - Value: Empty marker

### Current Block Storage Flow

```java
// BlockStoreImpl.saveBlock() - line 277
public void saveBlock(Block block) {
    long time = block.getTimestamp();

    // 1. Time index
    timeSource.put(BlockUtils.getTimeKey(time, block.getHash()), new byte[]{0});

    // 2. Raw block data (512 bytes)
    blockSource.put(block.getHash().toArray(), block.getXdagBlock().getData().toArray());

    // 3. Sums (deprecated - old sync protocol)
    saveBlockSums(block);

    // 4. BlockInfo metadata
    saveBlockInfoV2(block.getInfo());
}
```

### Current Block Retrieval Flow

```java
// BlockStoreImpl.getBlockByHash() - line 572
public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
    if (isRaw) {
        // Get BlockInfo + parse raw 512-byte data
        return getRawBlockByHash(hash);
    }
    // Get BlockInfo only (no raw data)
    return getBlockInfoByHash(hash);
}
```

---

## ✅ BlockV5 Serialization Capabilities

**Good News**: BlockV5 already has complete serialization support!

### BlockV5.toBytes() - Serialization

**Location**: `src/main/java/io/xdag/core/BlockV5.java:480`

```java
public byte[] toBytes() {
    // Serialize BlockV5 to byte array
    // Layout: Header + Links + Transactions
    // Size: Variable (depends on number of links/transactions)
}
```

**Format**:
- Header (fixed size)
- Links (variable size, N × 36 bytes)
- Transactions (embedded in links)

**Size**: Unlike legacy Block (512 bytes fixed), BlockV5 is **variable-length**

### BlockV5.fromBytes() - Deserialization

**Location**: `src/main/java/io/xdag/core/BlockV5.java:506`

```java
public static BlockV5 fromBytes(byte[] bytes) {
    // Deserialize byte array to BlockV5
    // Parse Header + Links
    // Reconstruct immutable BlockV5 object
}
```

**Result**: Fully reconstructed BlockV5 object with all fields populated

---

## 🎯 Migration Strategy: Complete Refactor

### User's Requirement

> "我想完全重构的" (I want complete refactoring)

**Implication**:
- No backward compatibility needed
- No dual Block/BlockV5 support
- Stop-and-migrate upgrade path
- All nodes upgrade together

### Migration Workflow

```
┌─────────────────────────────────────────────────────────────┐
│ Step 1: Export Snapshot (Before Upgrade)                    │
├─────────────────────────────────────────────────────────────┤
│ Running System (Block-based)                                 │
│   ↓                                                          │
│ Export Tool: BlockStore → Snapshot File (Block format)       │
│   • Iterate all blocks from BlockStore                       │
│   • Serialize each Block to snapshot                         │
│   • Include BlockInfo metadata                               │
│   ↓                                                          │
│ snapshot-block.dat (legacy format)                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Step 2: Stop System                                          │
├─────────────────────────────────────────────────────────────┤
│ Gracefully shutdown XDAG node                                │
│ Backup database directory                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Step 3: Convert Snapshot (Offline Tool)                      │
├─────────────────────────────────────────────────────────────┤
│ Conversion Tool: snapshot-block.dat → snapshot-blockv5.dat   │
│   ↓                                                          │
│ For each Block in snapshot:                                  │
│   • Convert Block → BlockV5                                  │
│   • Validate BlockV5 structure                               │
│   • Serialize to new snapshot                                │
│   ↓                                                          │
│ snapshot-blockv5.dat (v5.1 format)                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Step 4: Import Snapshot (v5.1 Code)                          │
├─────────────────────────────────────────────────────────────┤
│ Import Tool (v5.1): snapshot-blockv5.dat → BlockStore        │
│   ↓                                                          │
│ For each BlockV5 in snapshot:                                │
│   • Validate BlockV5                                         │
│   • saveBlockV5(blockV5)                                     │
│   • Build indexes                                            │
│   ↓                                                          │
│ BlockStore (pure BlockV5)                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Step 5: Start System (v5.1 Code)                             │
├─────────────────────────────────────────────────────────────┤
│ Running System (BlockV5-only)                                │
│   • No Block class used                                      │
│   • All storage operations use BlockV5                       │
│   • Network layer uses NEW_BLOCK_V5 messages                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 📝 Implementation Tasks

### Phase 4.1: Add BlockV5 Storage Methods (2 days)

**Objective**: Extend BlockStore to save/load BlockV5

#### Task 4.1.1: Add saveBlockV5() to BlockStore Interface

**File**: `src/main/java/io/xdag/db/BlockStore.java`

```java
/**
 * Save BlockV5 to storage (Phase 4)
 *
 * Stores:
 * - Raw BlockV5 bytes (variable-length)
 * - BlockInfo metadata
 * - Time index
 * - Epoch/height indexes
 *
 * @param block BlockV5 to save
 */
void saveBlockV5(BlockV5 block);
```

#### Task 4.1.2: Implement saveBlockV5() in BlockStoreImpl

**File**: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`

```java
@Override
public void saveBlockV5(BlockV5 block) {
    long time = block.getTimestamp();
    Bytes32 hash = block.getHash();

    // 1. Time index (same as Block)
    timeSource.put(BlockUtils.getTimeKey(time, hash), new byte[]{0});

    // 2. Raw BlockV5 data (variable-length)
    byte[] blockV5Bytes = block.toBytes();
    blockSource.put(hash.toArray(), blockV5Bytes);

    // 3. BlockInfo metadata
    // Note: BlockV5 may not have info field yet, need to create it
    BlockInfo info = block.getInfo();
    if (info != null) {
        saveBlockInfoV2(info);
    } else {
        // Create minimal BlockInfo for storage
        BlockInfo minimalInfo = BlockInfo.builder()
            .hash(hash)
            .timestamp(block.getTimestamp())
            .build();
        saveBlockInfoV2(minimalInfo);
    }

    log.debug("Saved BlockV5: {} ({} bytes)", hash.toHexString(), blockV5Bytes.length);
}
```

#### Task 4.1.3: Add getBlockV5ByHash() Methods

**File**: `src/main/java/io/xdag/db/BlockStore.java`

```java
/**
 * Get BlockV5 by hash (Phase 4)
 *
 * @param hash Block hash
 * @param isRaw true to load raw data, false for BlockInfo only
 * @return BlockV5 or null if not found
 */
BlockV5 getBlockV5ByHash(Bytes32 hash, boolean isRaw);

/**
 * Get raw BlockV5 with full data
 */
BlockV5 getRawBlockV5ByHash(Bytes32 hash);

/**
 * Get BlockV5 with BlockInfo only (no raw data)
 */
BlockV5 getBlockV5InfoByHash(Bytes32 hash);
```

#### Task 4.1.4: Implement getBlockV5ByHash() in BlockStoreImpl

**File**: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`

```java
@Override
public BlockV5 getBlockV5ByHash(Bytes32 hash, boolean isRaw) {
    if (isRaw) {
        return getRawBlockV5ByHash(hash);
    }
    return getBlockV5InfoByHash(hash);
}

@Override
public BlockV5 getRawBlockV5ByHash(Bytes32 hash) {
    // 1. Get raw BlockV5 bytes
    byte[] blockV5Bytes = blockSource.get(hash.toArray());
    if (blockV5Bytes == null) {
        return null;
    }

    // 2. Deserialize BlockV5
    BlockV5 block = BlockV5.fromBytes(blockV5Bytes);

    // 3. Load BlockInfo from database
    BlockInfo info = loadBlockInfo(hash);
    if (info != null) {
        // Attach BlockInfo to BlockV5
        // Note: BlockV5 is immutable, so we may need to rebuild it with info
        // TODO: Add BlockV5.withInfo() method or similar
        block = block.toBuilder().info(info).build();
    }

    return block;
}

@Override
public BlockV5 getBlockV5InfoByHash(Bytes32 hash) {
    // Get BlockInfo only, no raw data
    BlockInfo info = loadBlockInfo(hash);
    if (info == null) {
        return null;
    }

    // Create minimal BlockV5 with BlockInfo only
    // Note: This requires BlockV5 to have a constructor that accepts BlockInfo
    // TODO: Add BlockV5 constructor for metadata-only instances
    return createBlockV5FromInfo(info);
}

private BlockInfo loadBlockInfo(Bytes32 hash) {
    byte[] value = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray()));
    if (value == null) {
        return null;
    }

    try {
        return io.xdag.serialization.CompactSerializer.deserializeBlockInfo(value);
    } catch (Exception e) {
        log.error("Failed to deserialize BlockInfo for {}", hash.toHexString(), e);
        return null;
    }
}
```

### Phase 4.2: Snapshot Export Tool (1 day)

**Objective**: Create tool to export current Block-based storage to snapshot file

#### Task 4.2.1: Create SnapshotExporter

**File**: `src/main/java/io/xdag/tools/SnapshotExporter.java` (new file)

```java
public class SnapshotExporter {

    private final BlockStore blockStore;
    private final String outputPath;

    /**
     * Export all blocks to snapshot file
     *
     * Format:
     * - Magic number (4 bytes): "XDAG"
     * - Version (4 bytes): 1
     * - Block count (8 bytes)
     * - For each block:
     *   - Hash (32 bytes)
     *   - Timestamp (8 bytes)
     *   - Raw block data (512 bytes for Block)
     *   - BlockInfo data (variable-length)
     */
    public void exportSnapshot() throws IOException {
        // Iterate all blocks from height 1 to nmain
        // Write to snapshot file
        // Include progress logging
    }
}
```

### Phase 4.3: Block → BlockV5 Conversion Tool (2 days)

**Objective**: Convert exported Block snapshot to BlockV5 snapshot

#### Task 4.3.1: Create Block → BlockV5 Converter

**File**: `src/main/java/io/xdag/tools/BlockToBlockV5Converter.java` (new file)

```java
public class BlockToBlockV5Converter {

    /**
     * Convert Block to BlockV5
     *
     * Challenges:
     * - Block uses Address objects (with amount)
     * - BlockV5 uses Link objects (no amount)
     * - Transaction amounts stored separately in TransactionStore
     *
     * Strategy:
     * 1. Parse Block's Address list
     * 2. Identify transaction inputs/outputs
     * 3. Create Transaction objects
     * 4. Store in TransactionStore
     * 5. Create Link objects (hash + type only)
     * 6. Build BlockV5
     */
    public BlockV5 convert(Block block) {
        // Implementation
    }
}
```

**Key Challenge**: Block and BlockV5 have different architectures:
- **Block**: Stores transaction amounts in Address objects (inline)
- **BlockV5**: Stores transaction amounts in separate TransactionStore

**Solution**: During conversion:
1. Extract transaction details from Block.Address
2. Create Transaction objects
3. Store in TransactionStore
4. Create BlockV5 with Link references

### Phase 4.4: Snapshot Import Tool (1 day)

**Objective**: Import BlockV5 snapshot into fresh BlockStore

#### Task 4.4.1: Create SnapshotImporter

**File**: `src/main/java/io/xdag/tools/SnapshotImporter.java` (new file)

```java
public class SnapshotImporter {

    private final BlockStore blockStore;
    private final String snapshotPath;

    /**
     * Import BlockV5 snapshot
     *
     * Process:
     * 1. Read snapshot file
     * 2. For each BlockV5:
     *    - Validate structure
     *    - Save to BlockStore
     *    - Update indexes
     * 3. Rebuild stats (XdagStats, XdagTopStatus)
     */
    public void importSnapshot() throws IOException {
        // Implementation
    }
}
```

### Phase 4.5: Update Blockchain to Use BlockV5 Storage (1-2 days)

**Objective**: Update BlockchainImpl to save/load BlockV5 instead of Block

#### Task 4.5.1: Update BlockchainImpl.saveBlock()

**File**: `src/main/java/io/xdag/core/BlockchainImpl.java`

Current (line 2083):
```java
public void saveBlock(Block block) {
    if (block == null) {
        return;
    }
    block.isSaved = true;
    blockStore.saveBlock(block);
    // ...
}
```

**New**: Add saveBlockV5() method:
```java
public void saveBlockV5(BlockV5 block) {
    if (block == null) {
        return;
    }
    blockStore.saveBlockV5(block);
    // Update memOurBlocks if needed
    // ...
}
```

**Integration**: Update tryToConnect(BlockV5) to call saveBlockV5():
```java
public synchronized ImportResult tryToConnect(BlockV5 block) {
    // ... validation ...

    // Save BlockV5 to storage
    saveBlockV5(block);

    // ... rest of processing ...
}
```

#### Task 4.5.2: Update Request Handlers (Phase 3.3 Completion)

**File**: `src/main/java/io/xdag/net/XdagP2pHandler.java`

After Phase 4 storage migration, complete Phase 3.3:

```java
// Phase 4: After storage migration, BlockStore returns BlockV5
protected void processBlockRequest(BlockRequestMessage msg) {
    Bytes hash = msg.getHash();
    // Now returns BlockV5 instead of Block
    BlockV5 block = blockStore.getBlockV5ByHash(Bytes32.wrap(hash), true);
    int ttl = config.getNodeSpec().getTTL();
    if (block != null) {
        log.debug("processBlockRequest: findBlockV5{}", Bytes32.wrap(hash).toHexString());
        // Send as BlockV5 message
        NewBlockV5Message message = new NewBlockV5Message(block, ttl);
        msgQueue.sendMessage(message);
    }
}
```

---

## 🚧 Technical Challenges

### Challenge 1: BlockV5 Immutability

**Issue**: BlockV5 is immutable, but we need to attach BlockInfo after loading from storage

**Current BlockV5 Design**:
```java
@Value
@Builder
public class BlockV5 implements Serializable {
    BlockV5Header header;
    List<Link> links;
    BlockInfo info;  // Optional metadata
}
```

**Solution Options**:

**Option A**: Add BlockV5.withInfo() method
```java
public BlockV5 withInfo(BlockInfo info) {
    return this.toBuilder().info(info).build();
}
```

**Option B**: Store BlockInfo separately and return Pair<BlockV5, BlockInfo>

**Recommendation**: Option A (cleaner API)

### Challenge 2: Block → BlockV5 Conversion Complexity

**Issue**: Block and BlockV5 have fundamentally different architectures

**Block Architecture** (legacy):
```
Block {
    List<Address> links;  // Contains hash + amount + type
    XdagBlock rawData;    // 512-byte binary format
}

Address {
    Bytes32 address;
    XAmount amount;       // Transaction amount stored here
    FieldType type;       // IN/OUT/INPUT/OUTPUT
    boolean isAddress;    // Block ref vs account address
}
```

**BlockV5 Architecture** (v5.1):
```
BlockV5 {
    BlockV5Header header;
    List<Link> links;     // Contains hash + type only (no amount)
}

Link {
    Bytes32 targetHash;
    LinkType type;        // TRANSACTION or BLOCK
}

Transaction {          // Stored separately in TransactionStore
    Bytes32 from;
    Bytes32 to;
    XAmount amount;
    XAmount fee;
    Signature signature;
}
```

**Conversion Strategy**:

1. **Parse Block.Address list**:
   - Identify transaction inputs (FIELD_INPUT, FIELD_IN)
   - Identify transaction outputs (FIELD_OUTPUT, FIELD_OUT)
   - Identify block references (FIELD_OUT with no amount, or isAddress=false)

2. **Create Transaction objects**:
   - Match inputs to outputs
   - Extract amount and fee
   - Store in TransactionStore

3. **Create Link objects**:
   - Transaction links → `Link(txHash, TRANSACTION)`
   - Block links → `Link(blockHash, BLOCK)`

4. **Build BlockV5**:
   - Construct header from Block metadata
   - Attach links
   - Calculate hash

**Code Skeleton**:
```java
public BlockV5 convertBlockToBlockV5(Block block) {
    // 1. Extract transactions from Address list
    List<Transaction> transactions = extractTransactions(block);

    // 2. Store transactions
    for (Transaction tx : transactions) {
        transactionStore.saveTransaction(tx);
    }

    // 3. Create links
    List<Link> links = new ArrayList<>();

    // Transaction links
    for (Transaction tx : transactions) {
        links.add(new Link(tx.getHash(), LinkType.TRANSACTION));
    }

    // Block links
    for (Address addr : block.getLinks()) {
        if (!addr.isAddress && addr.getAmount().isZero()) {
            links.add(new Link(addr.getAddress(), LinkType.BLOCK));
        }
    }

    // 4. Build BlockV5
    BlockV5Header header = BlockV5Header.builder()
        .timestamp(block.getTimestamp())
        .nonce(block.getNonce())
        .difficulty(block.getInfo().getDifficulty())
        .coinbase(extractCoinbase(block))
        .build();

    return BlockV5.builder()
        .header(header)
        .links(links)
        .build();
}
```

### Challenge 3: Transaction Store Population

**Issue**: TransactionStore must be populated during conversion

**Current State**: TransactionStore exists but is empty (no transactions imported)

**Solution**: During Block → BlockV5 conversion:
1. Extract transaction data from Block.Address
2. Create Transaction objects
3. Save to TransactionStore BEFORE creating BlockV5
4. Ensure transaction hashes match Link references

**Transaction Extraction Logic**:
```java
private List<Transaction> extractTransactions(Block block) {
    List<Transaction> txs = new ArrayList<>();

    // Find all transaction inputs/outputs
    List<Address> inputs = findAddresses(block, XDAG_FIELD_INPUT, XDAG_FIELD_IN);
    List<Address> outputs = findAddresses(block, XDAG_FIELD_OUTPUT, XDAG_FIELD_OUT);

    // Match inputs to outputs (simple pairing)
    for (int i = 0; i < Math.min(inputs.size(), outputs.size()); i++) {
        Address input = inputs.get(i);
        Address output = outputs.get(i);

        Transaction tx = Transaction.builder()
            .from(input.getAddress())
            .to(output.getAddress())
            .amount(input.getAmount().subtract(MIN_GAS))  // Subtract fee
            .fee(MIN_GAS)
            .timestamp(block.getTimestamp())
            .signature(extractSignature(block, i))
            .build();

        txs.add(tx);
    }

    return txs;
}
```

---

## 📊 Progress Tracking

### Phase 4 Overall Progress

```
Phase 4.1 - BlockV5 Storage Methods:    ░░░░░░░░░░░░░░░░░░░░   0% ⏳
Phase 4.2 - Snapshot Export:            ░░░░░░░░░░░░░░░░░░░░   0% ⏳
Phase 4.3 - Conversion Tool:            ░░░░░░░░░░░░░░░░░░░░   0% ⏳
Phase 4.4 - Snapshot Import:            ░░░░░░░░░░░░░░░░░░░░   0% ⏳
Phase 4.5 - Blockchain Integration:     ░░░░░░░░░░░░░░░░░░░░   0% ⏳
-------------------------------------------------------
Overall Phase 4:                        ░░░░░░░░░░░░░░░░░░░░   0%
```

### By Component

| Component | Status | Estimated Days | Details |
|-----------|--------|----------------|---------|
| **BlockStore.saveBlockV5()** | ⏳ Not started | 0.5 days | Interface + Impl |
| **BlockStore.getBlockV5ByHash()** | ⏳ Not started | 0.5 days | Interface + Impl |
| **BlockV5.withInfo()** | ⏳ Not started | 0.5 days | Immutability helper |
| **SnapshotExporter** | ⏳ Not started | 1 day | Export Block snapshot |
| **Block→BlockV5 Converter** | ⏳ Not started | 2 days | Complex conversion logic |
| **SnapshotImporter** | ⏳ Not started | 1 day | Import BlockV5 snapshot |
| **Blockchain Integration** | ⏳ Not started | 1-2 days | Update saveBlock/getBlock |
| **Testing & Validation** | ⏳ Not started | 1 day | End-to-end testing |

**Total**: 5-7 days

---

## 🎯 Success Criteria

### Phase 4 Completion Criteria

- ✅ BlockStore can save BlockV5 via saveBlockV5()
- ✅ BlockStore can load BlockV5 via getBlockV5ByHash()
- ✅ Snapshot export tool can export all blocks
- ✅ Conversion tool can convert Block → BlockV5
- ✅ Snapshot import tool can import BlockV5 snapshot
- ✅ BlockchainImpl uses BlockV5 storage methods
- ✅ Request handlers send BlockV5 (Phase 3.3 complete)
- ✅ Full system test: Export → Convert → Import → Start
- ✅ Compilation successful (mvn compile)
- ✅ Database migration successful (test on small dataset)

---

## 🚀 Next Steps

### Immediate Actions (After Approval)

1. **Create Phase 4.1 Branch**:
   ```bash
   git checkout -b phase4.1-blockv5-storage
   ```

2. **Implement BlockV5 Storage Methods**:
   - Add saveBlockV5() to BlockStore interface
   - Implement in BlockStoreImpl
   - Add getBlockV5ByHash() methods
   - Test serialization/deserialization

3. **Verify BlockV5 Serialization**:
   - Test BlockV5.toBytes() / fromBytes()
   - Measure size (variable vs fixed 512 bytes)
   - Validate round-trip conversion

### Questions for User

Before starting implementation, please confirm:

1. **Migration Timeline**: Is 5-7 days acceptable for Phase 4?

2. **Conversion Strategy**: Should we handle edge cases like:
   - Orphan blocks
   - Extra blocks
   - Snapshot blocks
   Or focus on main chain blocks only?

3. **TransactionStore**: Should conversion tool populate TransactionStore?

4. **Testing Approach**: Should we test on:
   - Mainnet snapshot (risky, large dataset)
   - Testnet snapshot (safer, smaller dataset)
   - Synthetic data (safest, controlled)

5. **Rollback Plan**: What if migration fails midway?
   - Keep backup of original database?
   - Ability to revert to Block-based code?

---

## 💡 Key Insights

### Architecture Observations

1. **BlockV5 is Variable-Length**: Unlike Block (512 bytes fixed), BlockV5 size depends on number of links
   - Fewer links → Smaller size
   - More links → Larger size
   - Trade-off: Flexibility vs Storage efficiency

2. **Transaction Separation**: BlockV5 architecture requires TransactionStore to be populated during conversion
   - Block: Inline transaction amounts in Address objects
   - BlockV5: External transaction amounts in TransactionStore

3. **BlockInfo Reuse**: BlockInfo can be shared between Block and BlockV5
   - Same metadata structure
   - Already uses CompactSerializer (efficient)
   - No changes needed

### Migration Risks

1. **Data Loss Risk**: Conversion must be lossless
   - Validate every converted block
   - Checksum verification
   - Transaction amount preservation

2. **Downtime Risk**: Stop-and-migrate requires system downtime
   - Minimize by pre-converting snapshot
   - Fast import process
   - Parallel processing if possible

3. **Disk Space Risk**: Requires 2× disk space during migration
   - Original database
   - New BlockV5 database
   - Temporary snapshot files

---

## 📚 Related Documents

### Phase Documents
- **[PHASE3.3_REQUEST_RESPONSE_DEFERRED.md](PHASE3.3_REQUEST_RESPONSE_DEFERRED.md)** - Phase 3.3 (deferred to Phase 4)
- **[PHASE3.2_BROADCASTING_COMPLETE.md](PHASE3.2_BROADCASTING_COMPLETE.md)** - Phase 3.2 completion
- **[PHASE3_NETWORK_LAYER_INITIAL.md](PHASE3_NETWORK_LAYER_INITIAL.md)** - Phase 3.1 completion
- **[PHASE2_BLOCKWRAPPER_COMPLETION.md](PHASE2_BLOCKWRAPPER_COMPLETION.md)** - Phase 2 completion

### Design Documents
- [docs/refactor-design/CORE_DATA_STRUCTURES.md](docs/refactor-design/CORE_DATA_STRUCTURES.md) - v5.1 specification
- [docs/refactor-design/HYBRID_SYNC_PROTOCOL.md](docs/refactor-design/HYBRID_SYNC_PROTOCOL.md) - Sync protocol design

---

## ✅ Conclusion

**Phase 4 Status**: 📋 **READY TO START** (Pending user approval)

**Key Deliverables**:
1. ✅ BlockStore supports BlockV5 (saveBlockV5, getBlockV5ByHash)
2. ✅ Snapshot export/import tools
3. ✅ Block → BlockV5 conversion tool
4. ✅ BlockchainImpl integration
5. ✅ End-to-end migration tested

**Estimated Duration**: 5-7 days

**Complexity**: ⚠️ **HIGH**
- Complex conversion logic (Block → BlockV5)
- Transaction extraction and storage
- Data integrity critical

**Recommendation**:
- Start with Phase 4.1 (BlockV5 storage methods)
- Test thoroughly with small dataset
- Gradually proceed to conversion tool
- Final test on testnet snapshot before mainnet

**Awaiting User Approval**: Please review this plan and confirm to proceed!

---

**Created**: 2025-10-30
**Phase**: Phase 4 - Storage Layer Migration (Planning)
**Status**: 📋 Planning Complete - Awaiting Approval
**Next**: Phase 4.1 - BlockV5 Storage Methods

🤖 Generated with [Claude Code](https://claude.com/claude-code)
