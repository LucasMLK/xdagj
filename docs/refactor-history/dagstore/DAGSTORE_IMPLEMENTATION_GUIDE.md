# DagStore Implementation Guide

## 实现进度

### ✅ Phase 1-3 完成
- [x] DagStore 接口定义
- [x] ResolvedLinks 数据类
- [x] ResolvedEntity 数据类
- [x] DagCache (L1 缓存层 - Caffeine)
- [x] DagEntityResolver (Facade 层)

### 🚧 Phase 4-5 进行中
- [ ] DagStoreRocksDBConfig (RocksDB 配置)
- [ ] DagStoreImpl (核心存储实现)
- [ ] DagStoreBatchWriter (批量写入优化)

### 📋 Phase 6 待实现
- [ ] 集成到 DagChainImpl
- [ ] 编写单元测试
- [ ] 性能测试
- [ ] 迁移现有代码

---

## DagStoreImpl 实现要点

### 核心方法实现优先级

#### 高优先级 (P0 - 立即实现)
1. **saveBlock()** - Block 存储
2. **getBlockByHash()** - Block 查询
3. **hasBlock()** - 存在性检查
4. **saveBlockInfo()** - BlockInfo 存储
5. **getBlockInfo()** - BlockInfo 查询

#### 中优先级 (P1 - 核心功能)
6. **getMainBlockAtPosition()** - 主链查询
7. **getCandidateBlocksInEpoch()** - Epoch 查询
8. **getWinnerBlockInEpoch()** - Epoch winner
9. **indexBlockReference()** - 引用索引
10. **getBlockReferences()** - 反向查询

#### 低优先级 (P2 - 优化功能)
11. **saveBlocks()** - 批量保存
12. **getBlocksByHashes()** - 批量查询
13. **listMainBlocks()** - 列表查询

---

## 存储布局详细设计

### Key Format

```java
// Block Data: 0xa0 + hash(32) -> Block bytes
byte[] blockKey = new byte[33];
blockKey[0] = BLOCK_DATA;
System.arraycopy(hash.toArray(), 0, blockKey, 1, 32);

// BlockInfo: 0xa1 + hash(32) -> BlockInfo bytes
byte[] infoKey = new byte[33];
infoKey[0] = BLOCK_INFO;
System.arraycopy(hash.toArray(), 0, infoKey, 1, 32);

// Time Index: 0xb0 + timestamp(8) + hash(32) -> empty
// Enables range scan: all blocks in time range
byte[] timeKey = new byte[41];
timeKey[0] = TIME_INDEX;
ByteBuffer.wrap(timeKey, 1, 8).putLong(timestamp);
System.arraycopy(hash.toArray(), 0, timeKey, 9, 32);

// Epoch Index: 0xb1 + epoch(8) + hash(32) -> empty
// Enables range scan: all blocks in epoch
byte[] epochKey = new byte[41];
epochKey[0] = EPOCH_INDEX;
ByteBuffer.wrap(epochKey, 1, 8).putLong(epoch);
System.arraycopy(hash.toArray(), 0, epochKey, 9, 32);

// Height Index: 0xb2 + height(8) -> hash(32)
byte[] heightKey = new byte[9];
heightKey[0] = HEIGHT_INDEX;
ByteBuffer.wrap(heightKey, 1, 8).putLong(height);

// Block Refs: 0xb3 + blockHash(32) -> List<refHash(32)>
byte[] refsKey = new byte[33];
refsKey[0] = BLOCK_REFS_INDEX;
System.arraycopy(hash.toArray(), 0, refsKey, 1, 32);
```

### Value Serialization

```java
// Block Serialization
byte[] serializeBlock(Block block) {
    // Use CompactSerializer or protobuf
    return CompactSerializer.serialize(block);
}

Block deserializeBlock(byte[] data) {
    return CompactSerializer.deserialize(data, Block.class);
}

// BlockInfo Serialization
byte[] serializeBlockInfo(BlockInfo info) {
    ByteBuffer buffer = ByteBuffer.allocate(84);
    buffer.put(info.getHash().toArray());          // 32 bytes
    buffer.putLong(info.getTimestamp());          // 8 bytes
    buffer.putLong(info.getHeight());             // 8 bytes
    buffer.put(info.getDifficulty().toBytes());   // 32 bytes
    buffer.putInt(info.getFlags());               // 4 bytes
    return buffer.array();
}

BlockInfo deserializeBlockInfo(byte[] data) {
    ByteBuffer buffer = ByteBuffer.wrap(data);
    Bytes32 hash = Bytes32.wrap(buffer.get(new byte[32]));
    long timestamp = buffer.getLong();
    long height = buffer.getLong();
    UInt256 difficulty = UInt256.fromBytes(Bytes32.wrap(buffer.get(new byte[32])));
    int flags = buffer.getInt();

    return BlockInfo.builder()
            .hash(hash)
            .timestamp(timestamp)
            .height(height)
            .difficulty(difficulty)
            .flags(flags)
            .build();
}
```

---

## 集成到 DagChainImpl

### 更新 DagChainImpl 构造函数

```java
public class DagChainImpl implements DagChain {

    private final DagStore dagStore;          // NEW
    private final TransactionStore transactionStore;
    private final DagCache dagCache;          // NEW
    private final DagEntityResolver entityResolver;  // NEW
    private final BlockchainImpl blockchainImpl;  // Keep for gradual migration

    public DagChainImpl(Kernel kernel, BlockchainImpl blockchainImpl) {
        this.blockchainImpl = blockchainImpl;

        // Initialize new storage layer
        this.dagStore = new DagStoreImpl(kernel.getConfig());
        this.transactionStore = kernel.getTransactionStore();

        // Initialize L1 cache
        this.dagCache = new DagCache();

        // Initialize Facade
        this.entityResolver = new DagEntityResolver(dagStore, transactionStore);
    }
}
```

### 更新 validateLinks() 使用 Facade

```java
private DagImportResult validateLinks(Block block) {
    // 使用 DagEntityResolver 统一解析所有 Link
    ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

    // 检查缺失引用
    if (!resolved.hasAllReferences()) {
        Bytes32 missing = resolved.getMissingReferences().get(0);
        log.debug("Missing link target: {}", missing.toHexString());
        return DagImportResult.missingDependency(missing, "Link not found");
    }

    // 验证所有引用的 Block
    for (Block refBlock : resolved.getReferencedBlocks()) {
        // Timestamp order validation
        if (refBlock.getTimestamp() >= block.getTimestamp()) {
            return DagImportResult.invalidLink(
                "Referenced block timestamp >= current block timestamp",
                refBlock.getHash()
            );
        }
    }

    // 验证所有引用的 Transaction
    for (Transaction tx : resolved.getReferencedTransactions()) {
        // Structure validation
        if (!tx.isValid()) {
            return DagImportResult.invalidLink(
                "Invalid transaction structure",
                tx.getHash()
            );
        }

        // Signature validation
        if (!tx.verifySignature()) {
            return DagImportResult.invalidLink(
                "Invalid transaction signature",
                tx.getHash()
            );
        }

        // Amount validation
        if (tx.getAmount().add(tx.getFee()).subtract(MIN_GAS).isNegative()) {
            return DagImportResult.invalidLink(
                "Transaction amount + fee < MIN_GAS",
                tx.getHash()
            );
        }
    }

    return null;  // All links valid
}
```

---

## 性能优化要点

### 1. 读取路径优化

```java
@Override
public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
    // L1 Cache check
    Block cached = dagCache.getBlock(hash);
    if (cached != null) {
        cacheHits.increment();
        return cached;
    }

    cacheMisses.increment();

    // L2 + Disk read
    Block block = readBlockFromDisk(hash, isRaw);
    if (block != null) {
        // Populate L1 cache
        dagCache.putBlock(hash, block);
    }

    return block;
}
```

### 2. 写入路径优化

```java
@Override
public void saveBlock(Block block) {
    // Validate input
    if (block == null || block.getInfo() == null) {
        throw new IllegalArgumentException("Block or BlockInfo is null");
    }

    WriteBatch batch = new WriteBatch();
    try {
        // 1. Save block data
        byte[] blockKey = buildBlockKey(block.getHash());
        byte[] blockData = serializeBlock(block);
        batch.put(blockKey, blockData);

        // 2. Save BlockInfo
        byte[] infoKey = buildBlockInfoKey(block.getHash());
        byte[] infoData = serializeBlockInfo(block.getInfo());
        batch.put(infoKey, infoData);

        // 3. Index by time
        byte[] timeKey = buildTimeIndexKey(block.getTimestamp(), block.getHash());
        batch.put(timeKey, EMPTY_VALUE);

        // 4. Index by epoch
        byte[] epochKey = buildEpochIndexKey(block.getEpoch(), block.getHash());
        batch.put(epochKey, EMPTY_VALUE);

        // 5. Index by height (if main block)
        if (block.getInfo().getHeight() > 0) {
            byte[] heightKey = buildHeightIndexKey(block.getInfo().getHeight());
            batch.put(heightKey, block.getHash().toArray());
        }

        // 6. Write batch atomically
        db.write(writeOptions, batch);

        // 7. Update L1 cache
        dagCache.putBlock(block.getHash(), block);
        dagCache.putBlockInfo(block.getHash(), block.getInfo());

        if (block.getInfo().getHeight() > 0) {
            dagCache.putHashByHeight(block.getInfo().getHeight(), block.getHash());
        }

    } finally {
        batch.close();
    }
}
```

---

## 下一步

1. **实现 DagStoreRocksDBConfig** - RocksDB 优化配置
2. **实现 DagStoreImpl 骨架** - 核心方法框架
3. **实现关键方法** - P0 优先级方法
4. **集成测试** - 验证功能正确性
5. **性能测试** - 验证性能目标

建议先实现 P0 方法，然后集成到 DagChainImpl 进行测试，再逐步实现其他方法。
