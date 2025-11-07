# XDAG DagStore - Capacity Planning and Performance Optimization

## 容量需求分析 (Capacity Planning)

### 运行参数
- **节点数量**: 50 nodes
- **运行时长**: 5-10 years
- **Epoch 周期**: 64 seconds
- **目标**: 支持长期稳定运行

---

## 一、容量估算 (Capacity Estimation)

### 1.1 Epoch 和 Block 数量

#### Epoch 总数计算
```
1 year = 365 days × 24 hours × 3600 seconds / 64 = 492,750 epochs
5 years = 2,463,750 epochs
10 years = 4,927,500 epochs
```

#### Block 数量估算

**场景 A: 理想情况（低竞争）**
- 每个 epoch 平均 2-3 个候选块
- 50 nodes × 2-3 blocks/epoch = 100-150 candidate blocks/epoch
- 但只有少数 epoch 有竞争，大部分 epoch 只有 1 个块
- **保守估计**: 平均每 epoch 3 个块
- **10年总块数**: 4,927,500 epochs × 3 = **14,782,500 blocks** (~15M blocks)

**场景 B: 高竞争情况**
- 50 个节点在高峰期同时挖矿
- 网络延迟导致多个块几乎同时到达
- 每个 epoch 平均 5-10 个候选块
- **高负载估计**: 平均每 epoch 8 个块
- **10年总块数**: 4,927,500 epochs × 8 = **39,420,000 blocks** (~40M blocks)

**实际场景**: 介于两者之间，取 **25,000,000 blocks (25M)** 作为设计目标

### 1.2 Block 存储大小

#### Block 结构分析
```java
Block {
    timestamp: 8 bytes
    hash: 32 bytes
    nonce: 32 bytes
    difficulty: 32 bytes
    links: List<Link>  // 1-16 个 Link
    signature: 64 bytes
    info: BlockInfo reference  // 不存在 Block 内
}

Link {
    type: 1 byte
    targetHash: 32 bytes
}

平均 Link 数量: 8 个 (4个Block + 4个Transaction)
```

#### 单个 Block 大小
```
Fixed fields:    8 + 32 + 32 + 32 + 64 = 168 bytes
Links (8个):     8 × 33 = 264 bytes
Overhead:        ~50 bytes (serialization overhead)
-------------------------------------------------
Total per block: ~480 bytes
```

#### 总 Block 存储
```
25M blocks × 480 bytes = 12,000 MB = 12 GB
40M blocks × 480 bytes = 19,200 MB = 19.2 GB (高负载)
```

### 1.3 BlockInfo 元数据大小

```java
BlockInfo {
    hash: 32 bytes
    timestamp: 8 bytes
    height: 8 bytes  // main block position, 0 for orphan
    difficulty: 32 bytes  // cumulative difficulty
    flags: 4 bytes
}

Total: ~84 bytes per block
```

#### 总 BlockInfo 存储
```
25M blocks × 84 bytes = 2,100 MB = 2.1 GB
40M blocks × 84 bytes = 3,360 MB = 3.4 GB (高负载)
```

### 1.4 Transaction 存储大小

#### Transaction 结构
```java
Transaction {
    from: 32 bytes
    to: 32 bytes
    amount: 16 bytes
    fee: 16 bytes
    nonce: 8 bytes
    signature: 64 bytes
    hash: 32 bytes
}

Total: ~200 bytes per transaction
```

#### Transaction 数量估算
- **假设**: 平均每个 Block 引用 2 个 Transaction (Link)
- **保守**: 25M blocks × 2 = 50M transactions
- **高负载**: 40M blocks × 3 = 120M transactions

#### 总 Transaction 存储
```
50M transactions × 200 bytes = 10,000 MB = 10 GB
120M transactions × 200 bytes = 24,000 MB = 24 GB (高负载)
```

### 1.5 索引大小估算

#### Time Index (timestamp → blockHash)
```
Entry size: 8 bytes (timestamp) + 32 bytes (hash) = 40 bytes
25M blocks × 40 bytes = 1,000 MB = 1 GB
```

#### Epoch Index (epoch → List<blockHash>)
```
每个 epoch 平均 3-8 个块
Entry size: 8 bytes (epoch) + 3×32 bytes (hashes) = 104 bytes
4.93M epochs × 104 bytes = 512 MB = 0.5 GB
```

#### Height Index (height → blockHash) - Main blocks only
```
Main blocks: ~5M (每个 epoch 1个 main block)
Entry size: 8 bytes (height) + 32 bytes (hash) = 40 bytes
5M × 40 bytes = 200 MB = 0.2 GB
```

#### Block References Index (blockHash → List<referencingHashes>)
```
平均每个 block 被 5 个 block 引用
Entry size: 32 bytes (hash) + 5×32 bytes (refs) = 192 bytes
25M blocks × 192 bytes = 4,800 MB = 4.8 GB
```

#### Transaction-to-Block Index (txHash → blockHash)
```
50M transactions × 40 bytes = 2,000 MB = 2 GB
```

#### Address-to-Transaction Index (address → List<txHash>)
```
假设 100,000 个活跃地址
平均每个地址 500 个交易
Entry: 32 bytes (address) + 500×32 bytes (txHashes) = 16,032 bytes
100K addresses × 16KB = 1,600 MB = 1.6 GB
```

#### 总索引大小
```
Time: 1 GB
Epoch: 0.5 GB
Height: 0.2 GB
BlockRefs: 4.8 GB
TxToBlock: 10 GB
AddressToTx: 1.6 GB
-----------------
Total: ~18 GB
```

### 1.6 总容量汇总

#### 保守估计 (25M blocks, 50M transactions)
```
Blocks:        12 GB
BlockInfo:     2.1 GB
Transactions:  10 GB
Indexes:       18 GB
Overhead:      5 GB  (RocksDB metadata, WAL, etc.)
---------------------------------
Total:         47.1 GB  ≈ 50 GB
```

#### 高负载估计 (40M blocks, 120M transactions)
```
Blocks:        19.2 GB
BlockInfo:     3.4 GB
Transactions:  24 GB
Indexes:       30 GB  (按比例增加)
Overhead:      8 GB
---------------------------------
Total:         84.6 GB  ≈ 85 GB
```

**结论**:
- **10年50节点运行**: 需要 **50-85 GB** 存储空间
- **推荐配置**: 256 GB SSD (留有充足余量)
- **最低配置**: 128 GB SSD

---

## 二、读写场景分析 (Read/Write Patterns)

### 2.1 写入场景 (Write Patterns)

#### 场景1: Block 导入 (tryToConnect)

**频率**:
- 正常: 每 64 秒 1-3 个块
- 高峰: 每 64 秒 10-20 个块（网络延迟、竞争）
- **峰值吞吐**: 20 blocks / 64s = 0.31 blocks/s

**写入操作**:
1. 保存 Block raw data: 1 write
2. 保存 BlockInfo: 1 write
3. 更新 Time index: 1 write
4. 更新 Epoch index: 1 write (append to list)
5. 更新 Height index (if main block): 1 write
6. 更新 Block references: 8 writes (平均8个link)
7. Index transactions to block: 2 writes (平均2个tx link)

**每个 Block 导入**: ~15 writes

**RocksDB 优化**:
```java
// 使用 WriteBatch 原子写入
WriteBatch batch = new WriteBatch();
batch.put(blockKey, blockData);
batch.put(blockInfoKey, blockInfoData);
batch.put(timeIndexKey, blockHash);
batch.put(epochIndexKey, appendBlockHash);
// ... 其他索引
db.write(writeOptions, batch);
```

**性能目标**:
- **延迟**: < 50ms per block import
- **吞吐**: 支持 1 block/s 持续写入

#### 场景2: Transaction 导入

**频率**:
- Transaction 先于 Block 到达
- 每个 Block 平均引用 2 个新 Transaction
- **峰值吞吐**: 2 tx/block × 0.31 blocks/s = 0.62 tx/s

**写入操作**:
1. 保存 Transaction data: 1 write
2. Index to address (from): 1 write
3. Index to address (to): 1 write

**每个 Transaction**: ~3 writes

**性能目标**:
- **延迟**: < 10ms per transaction
- **吞吐**: 支持 5 tx/s 峰值

#### 场景3: Chain Stats 更新

**频率**: 每个 main block (每 64 秒 1 次)

**写入操作**: 1 write (ChainStats)

**性能目标**: < 5ms

#### 场景4: Batch Import (同步场景)

**同步场景**:
- 新节点启动，需要同步历史数据
- 可能需要导入数百万个 block
- **峰值吞吐**: 1000 blocks/s

**优化策略**:
```java
// 批量导入优化
WriteBatch batch = new WriteBatch();
for (Block block : blocks) {
    addBlockToBatch(batch, block);
    if (batch.count() >= 1000) {
        db.write(writeOptions, batch);
        batch.clear();
    }
}
```

**性能目标**:
- **吞吐**: 1000-5000 blocks/s (批量导入)
- **同步 25M blocks**: 25M / 1000 = 25,000 秒 ≈ 7 小时

### 2.2 读取场景 (Read Patterns)

#### 场景1: Block 验证 (validateLinks)

**频率**: 每个新 block 导入时

**读取操作**:
1. 检查 parent blocks 存在: 8 reads (平均8个block link)
2. 检查 transactions 存在: 2 reads (平均2个tx link)
3. 读取 parent BlockInfo (验证 timestamp): 8 reads
4. 读取 Transaction data (验证签名): 2 reads

**每个 Block 验证**: ~20 reads

**性能特点**:
- **热点数据**: 最近的 blocks (最近 100 个 epochs)
- **缓存命中率**: 90%+ (因为验证的都是最近的块)

**性能目标**:
- **延迟**: < 20ms (大部分从缓存读取)

#### 场景2: getMainBlockAtPosition (顺序访问)

**频率**: 高频（RPC查询、UI显示）

**读取操作**:
1. Height index lookup: 1 read
2. Block data fetch: 1 read

**性能特点**:
- **顺序访问**: position 1, 2, 3, ... (可以预取)
- **热点**: 最新的 1000 个 main blocks
- **缓存命中率**: 80%+

**性能目标**:
- **延迟**: < 5ms (缓存), < 20ms (磁盘)

#### 场景3: getWinnerBlockInEpoch (Epoch 查询)

**频率**: 中频（Epoch 统计、RPC查询）

**读取操作**:
1. Epoch index lookup: 1 read → List<blockHash>
2. Block data fetch: 3-8 reads (每个 candidate)
3. Compare hashes to find winner

**性能特点**:
- **热点**: 最近 100 个 epochs
- **缓存命中率**: 70%+

**性能目标**:
- **延迟**: < 30ms

#### 场景4: getCandidateBlocksInEpoch

**频率**: 中频

**读取操作**:
1. Epoch index lookup: 1 read → List<blockHash>
2. Block data fetch: 3-8 reads

**性能目标**:
- **延迟**: < 50ms

#### 场景5: getTransactionsByAddress (历史查询)

**频率**: 低频但重要（钱包查询、区块浏览器）

**读取操作**:
1. Address index lookup: 1 read → List<txHash> (可能很大)
2. Transaction data fetch: N reads (N = 交易数量)

**性能特点**:
- **冷数据**: 历史交易（缓存命中率低）
- **大量数据**: 一个地址可能有数千个交易

**性能目标**:
- **延迟**: < 500ms (分页查询)
- **优化**: 支持分页、限制返回数量

#### 场景6: calculateCumulativeDifficulty

**频率**: 每个 block 导入时

**读取操作**:
1. 读取 parent blocks: 8 reads (平均)
2. 读取 parent BlockInfo: 8 reads

**性能特点**:
- **递归查询**: 需要遍历 parent chain
- **热点数据**: 最近的 blocks

**性能目标**:
- **延迟**: < 10ms

---

## 三、性能优化策略 (Performance Optimization)

### 3.1 分层缓存架构 (Tiered Caching)

```
┌─────────────────────────────────────────────────────────────┐
│                     L1 Cache (Heap Memory)                   │
│                     Size: 256 MB - 512 MB                    │
│                     Hit Rate: 85-95%                         │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Block Cache  │  │ BlockInfo    │  │ Transaction  │     │
│  │              │  │ Cache        │  │ Cache        │     │
│  │ LRU 10K      │  │ LRU 50K      │  │ LRU 20K      │     │
│  │ ~5 MB        │  │ ~4 MB        │  │ ~4 MB        │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              L2 Cache (RocksDB Block Cache)                  │
│                     Size: 2 GB - 4 GB                        │
│                     Hit Rate: 75-85%                         │
├─────────────────────────────────────────────────────────────┤
│  RocksDB internal block cache (shared across CFs)           │
│  - Compressed blocks in memory                              │
│  - Bloom filters for fast lookup                            │
│  - Index blocks                                             │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                     L3 Storage (SSD)                         │
│                     Size: 50-85 GB                           │
│                     Access: ~1ms                             │
├─────────────────────────────────────────────────────────────┤
│  RocksDB LSM-tree on SSD                                    │
│  - Sequential writes (WAL)                                   │
│  - Compaction for read optimization                         │
└─────────────────────────────────────────────────────────────┘
```

#### L1 Cache 设计

```java
/**
 * DagCache - L1 in-memory cache for hot data
 */
@Component
public class DagCache {

    // Block cache - 最近访问的 Block (raw data)
    private final Cache<Bytes32, Block> blockCache;

    // BlockInfo cache - 最近访问的 BlockInfo (metadata)
    private final Cache<Bytes32, BlockInfo> blockInfoCache;

    // Transaction cache - 最近访问的 Transaction
    private final Cache<Bytes32, Transaction> transactionCache;

    // Height-to-Hash cache - Main chain position mapping
    private final Cache<Long, Bytes32> heightToHashCache;

    // Epoch winner cache - Epoch competition results
    private final Cache<Long, Bytes32> epochWinnerCache;

    public DagCache() {
        // Caffeine - 高性能 Java 缓存库
        this.blockCache = Caffeine.newBuilder()
                .maximumSize(10_000)  // 10K blocks × 500 bytes = 5 MB
                .expireAfterAccess(Duration.ofMinutes(30))
                .recordStats()  // 监控命中率
                .build();

        this.blockInfoCache = Caffeine.newBuilder()
                .maximumSize(50_000)  // 50K BlockInfo × 84 bytes = 4.2 MB
                .expireAfterAccess(Duration.ofHours(1))
                .recordStats()
                .build();

        this.transactionCache = Caffeine.newBuilder()
                .maximumSize(20_000)  // 20K tx × 200 bytes = 4 MB
                .expireAfterAccess(Duration.ofMinutes(15))
                .recordStats()
                .build();

        this.heightToHashCache = Caffeine.newBuilder()
                .maximumSize(10_000)  // 最近 10K main blocks
                .expireAfterAccess(Duration.ofHours(2))
                .recordStats()
                .build();

        this.epochWinnerCache = Caffeine.newBuilder()
                .maximumSize(5_000)  // 最近 5K epochs
                .expireAfterAccess(Duration.ofHours(2))
                .recordStats()
                .build();
    }

    // Cache 访问方法
    public Block getBlock(Bytes32 hash) {
        return blockCache.getIfPresent(hash);
    }

    public void putBlock(Bytes32 hash, Block block) {
        blockCache.put(hash, block);
    }

    // ... 其他方法

    // 监控方法
    public CacheStats getBlockCacheStats() {
        return blockCache.stats();
    }
}
```

**缓存预热策略**:
```java
/**
 * 预加载最近的 main blocks 到缓存
 */
public void warmupCache() {
    long currentHeight = getMainChainLength();
    long startHeight = Math.max(1, currentHeight - 1000);

    for (long height = startHeight; height <= currentHeight; height++) {
        Block block = dagStore.getMainBlockAtPosition(height, false);
        if (block != null) {
            cache.putBlock(block.getHash(), block);
            cache.putBlockInfo(block.getHash(), block.getInfo());
        }
    }

    log.info("Cache warmup completed: {} blocks loaded", currentHeight - startHeight + 1);
}
```

### 3.2 RocksDB 优化配置

```java
/**
 * RocksDB 优化配置 - 针对 XDAG 读写特点
 */
public class DagStoreRocksDBConfig {

    public static DBOptions createDBOptions() {
        DBOptions options = new DBOptions();

        // === 并发配置 ===
        options.setIncreaseParallelism(4);  // 4 个后台线程
        options.setMaxBackgroundJobs(4);    // 4 个后台任务

        // === WAL 配置 ===
        options.setWalSizeLimitMB(256);     // WAL 最大 256 MB
        options.setWalTtlSeconds(3600);     // WAL 保留 1 小时

        // === Compaction 配置 ===
        options.setMaxOpenFiles(500);       // 打开文件数上限

        return options;
    }

    public static ColumnFamilyOptions createCFOptions() {
        ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();

        // === Block Cache (L2 缓存) ===
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setBlockCache(new LRUCache(2L * 1024 * 1024 * 1024));  // 2 GB
        tableConfig.setBlockSize(16 * 1024);  // 16 KB block size

        // === Bloom Filter (加速查询) ===
        tableConfig.setFilterPolicy(new BloomFilter(10, false));  // 10 bits per key

        // === Cache Index and Filter Blocks ===
        tableConfig.setCacheIndexAndFilterBlocks(true);
        tableConfig.setPinL0FilterAndIndexBlocksInCache(true);

        cfOptions.setTableFormatConfig(tableConfig);

        // === Write Buffer (MemTable) ===
        cfOptions.setWriteBufferSize(64 * 1024 * 1024);  // 64 MB
        cfOptions.setMaxWriteBufferNumber(3);
        cfOptions.setMinWriteBufferNumberToMerge(1);

        // === Compaction ===
        cfOptions.setCompressionType(CompressionType.LZ4_COMPRESSION);  // LZ4 压缩
        cfOptions.setLevel0FileNumCompactionTrigger(4);
        cfOptions.setLevel0SlowdownWritesTrigger(20);
        cfOptions.setLevel0StopWritesTrigger(36);

        // === LSM Tree 层级配置 ===
        cfOptions.setNumLevels(7);
        cfOptions.setMaxBytesForLevelBase(256 * 1024 * 1024);  // L1 = 256 MB
        cfOptions.setMaxBytesForLevelMultiplier(10);  // 每层增长 10 倍

        return cfOptions;
    }

    public static WriteOptions createWriteOptions() {
        WriteOptions writeOptions = new WriteOptions();

        // === 同步配置 ===
        // 普通写入不同步（性能优先）
        writeOptions.setSync(false);
        writeOptions.setDisableWAL(false);  // 启用 WAL（数据安全）

        return writeOptions;
    }

    public static WriteOptions createSyncWriteOptions() {
        WriteOptions writeOptions = new WriteOptions();

        // 关键写入同步（如 ChainStats）
        writeOptions.setSync(true);
        writeOptions.setDisableWAL(false);

        return writeOptions;
    }

    public static ReadOptions createReadOptions() {
        ReadOptions readOptions = new ReadOptions();

        // 启用填充缓存
        readOptions.setFillCache(true);

        // 不进行校验（性能优先）
        readOptions.setVerifyChecksums(false);

        return readOptions;
    }
}
```

### 3.3 批量写入优化 (Batch Write Optimization)

```java
/**
 * 批量写入管理器
 */
@Component
public class DagStoreBatchWriter {

    private final RocksDB db;
    private final WriteOptions writeOptions;
    private final int batchSize;
    private final long flushIntervalMs;

    private WriteBatch currentBatch;
    private int batchCount;
    private long lastFlushTime;

    public DagStoreBatchWriter(RocksDB db) {
        this.db = db;
        this.writeOptions = createWriteOptions();
        this.batchSize = 1000;  // 每批 1000 个写操作
        this.flushIntervalMs = 1000;  // 每秒强制 flush
        this.currentBatch = new WriteBatch();
        this.batchCount = 0;
        this.lastFlushTime = System.currentTimeMillis();
    }

    /**
     * 添加写操作到批次
     */
    public synchronized void put(byte[] key, byte[] value) throws RocksDBException {
        currentBatch.put(key, value);
        batchCount++;

        // 检查是否需要 flush
        if (shouldFlush()) {
            flush();
        }
    }

    /**
     * 判断是否需要 flush
     */
    private boolean shouldFlush() {
        return batchCount >= batchSize ||
               (System.currentTimeMillis() - lastFlushTime) >= flushIntervalMs;
    }

    /**
     * 执行批量写入
     */
    public synchronized void flush() throws RocksDBException {
        if (batchCount == 0) {
            return;
        }

        try {
            db.write(writeOptions, currentBatch);
            log.debug("Flushed batch: {} operations", batchCount);
        } finally {
            currentBatch.clear();
            batchCount = 0;
            lastFlushTime = System.currentTimeMillis();
        }
    }

    /**
     * 同步导入优化（用于区块同步）
     */
    public void batchImportBlocks(List<Block> blocks) throws RocksDBException {
        WriteBatch batch = new WriteBatch();
        int count = 0;

        for (Block block : blocks) {
            // 添加 Block
            batch.put(getBlockKey(block.getHash()), serializeBlock(block));

            // 添加 BlockInfo
            batch.put(getBlockInfoKey(block.getHash()), serializeBlockInfo(block.getInfo()));

            // 添加索引
            batch.put(getTimeIndexKey(block.getTimestamp()), block.getHash().toArray());
            batch.put(getEpochIndexKey(block.getEpoch()), block.getHash().toArray());

            count++;

            // 每 1000 个 block flush 一次
            if (count >= 1000) {
                db.write(writeOptions, batch);
                batch.clear();
                count = 0;
            }
        }

        // Flush remaining
        if (count > 0) {
            db.write(writeOptions, batch);
        }

        batch.close();
    }
}
```

### 3.4 预取优化 (Prefetch Optimization)

```java
/**
 * 预取管理器 - 预测性数据加载
 */
@Component
public class DagStorePrefetcher {

    private final DagStore dagStore;
    private final DagCache cache;
    private final ExecutorService prefetchExecutor;

    public DagStorePrefetcher(DagStore dagStore, DagCache cache) {
        this.dagStore = dagStore;
        this.cache = cache;
        this.prefetchExecutor = Executors.newFixedThreadPool(2);
    }

    /**
     * 预取 main chain blocks
     * 当访问 position N 时，预取 N+1, N+2, ... N+10
     */
    public void prefetchMainBlocks(long startPosition, int count) {
        prefetchExecutor.submit(() -> {
            for (long pos = startPosition; pos < startPosition + count; pos++) {
                // 检查缓存是否已有
                Bytes32 hash = cache.getHashByHeight(pos);
                if (hash != null && cache.getBlock(hash) != null) {
                    continue;  // 已在缓存
                }

                // 从磁盘加载
                Block block = dagStore.getMainBlockAtPosition(pos, false);
                if (block != null) {
                    cache.putBlock(block.getHash(), block);
                    cache.putHashByHeight(pos, block.getHash());
                }
            }
        });
    }

    /**
     * 预取 epoch blocks
     * 当访问 epoch N 时，预取 N+1, N+2
     */
    public void prefetchEpochBlocks(long startEpoch, int count) {
        prefetchExecutor.submit(() -> {
            for (long epoch = startEpoch; epoch < startEpoch + count; epoch++) {
                // 检查缓存
                if (cache.getEpochWinner(epoch) != null) {
                    continue;
                }

                // 加载 epoch candidates
                List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);
                for (Block block : candidates) {
                    cache.putBlock(block.getHash(), block);
                }

                // 计算 winner
                Block winner = findWinnerBlock(candidates);
                if (winner != null) {
                    cache.putEpochWinner(epoch, winner.getHash());
                }
            }
        });
    }

    /**
     * 预取 parent blocks
     * 在验证 block 之前预取所有 parent blocks
     */
    public void prefetchParentBlocks(Block block) {
        List<Bytes32> parentHashes = block.getLinks().stream()
                .filter(link -> !link.isTransaction())
                .map(Link::getTargetHash)
                .filter(hash -> cache.getBlock(hash) == null)  // 不在缓存
                .collect(Collectors.toList());

        if (parentHashes.isEmpty()) {
            return;
        }

        prefetchExecutor.submit(() -> {
            for (Bytes32 hash : parentHashes) {
                Block parent = dagStore.getBlockByHash(hash, false);
                if (parent != null) {
                    cache.putBlock(hash, parent);
                }
            }
        });
    }
}
```

### 3.5 索引优化策略

#### Bloom Filter 使用

```java
/**
 * Block 存在性快速检查
 */
public boolean hasBlock(Bytes32 hash) {
    // 1. 先查 L1 cache
    if (cache.getBlock(hash) != null) {
        return true;
    }

    // 2. RocksDB Bloom filter 快速检查
    // RocksDB 会自动使用 Bloom filter 加速 key 查找
    // 配置见上面的 tableConfig.setFilterPolicy()
    byte[] key = getBlockKey(hash);
    try {
        byte[] value = db.get(key);
        return value != null;
    } catch (RocksDBException e) {
        log.error("Error checking block existence", e);
        return false;
    }
}
```

#### 复合索引优化

```java
/**
 * Epoch + Height 复合索引
 *
 * 格式: 0xb1 + epoch(8 bytes) + height(8 bytes) -> blockHash(32 bytes)
 *
 * 优势：
 * 1. 支持 epoch 范围查询
 * 2. 支持 epoch + height 精确查询
 * 3. 自然排序
 */
public List<Block> getMainBlocksInEpochRange(long startEpoch, long endEpoch) {
    List<Block> blocks = new ArrayList<>();

    byte[] startKey = buildCompositeKey(EPOCH_HEIGHT_INDEX, startEpoch, 0L);
    byte[] endKey = buildCompositeKey(EPOCH_HEIGHT_INDEX, endEpoch + 1, 0L);

    try (RocksIterator iterator = db.newIterator()) {
        iterator.seek(startKey);

        while (iterator.isValid() &&
               ByteBuffer.wrap(iterator.key()).compareTo(ByteBuffer.wrap(endKey)) < 0) {

            Bytes32 blockHash = Bytes32.wrap(iterator.value());
            Block block = getBlockByHash(blockHash, false);
            if (block != null && block.getInfo().getHeight() > 0) {
                blocks.add(block);
            }

            iterator.next();
        }
    }

    return blocks;
}
```

---

## 四、性能监控指标 (Performance Metrics)

### 4.1 关键指标 (Key Metrics)

```java
/**
 * DagStore 性能监控
 */
@Component
public class DagStoreMetrics {

    private final MeterRegistry registry;

    // 写入指标
    private final Timer blockWriteTimer;
    private final Timer transactionWriteTimer;
    private final Counter blockWriteCount;
    private final Counter transactionWriteCount;

    // 读取指标
    private final Timer blockReadTimer;
    private final Timer transactionReadTimer;
    private final Counter blockReadCount;
    private final Counter transactionReadCount;

    // 缓存指标
    private final Gauge cacheHitRate;
    private final Gauge cacheSize;

    // 存储指标
    private final Gauge databaseSize;
    private final Counter compactionCount;

    public DagStoreMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.blockWriteTimer = Timer.builder("dagstore.block.write")
                .description("Block write latency")
                .register(registry);

        this.blockReadTimer = Timer.builder("dagstore.block.read")
                .description("Block read latency")
                .register(registry);

        this.cacheHitRate = Gauge.builder("dagstore.cache.hitrate", this, m -> m.calculateHitRate())
                .description("L1 cache hit rate")
                .register(registry);

        // ... 其他指标
    }

    // 监控方法
    public void recordBlockWrite(long durationMs) {
        blockWriteTimer.record(durationMs, TimeUnit.MILLISECONDS);
        blockWriteCount.increment();
    }

    // ... 其他监控方法
}
```

### 4.2 性能目标 (Performance Targets)

| 操作 | 延迟目标 (P99) | 吞吐目标 |
|------|---------------|---------|
| **Block Import** | < 50ms | 1 block/s (持续) |
| **Block Validation** | < 20ms | 1 block/s |
| **Transaction Import** | < 10ms | 5 tx/s (峰值) |
| **getMainBlockAtPosition** | < 5ms (cache) / < 20ms (disk) | 100 req/s |
| **getWinnerBlockInEpoch** | < 30ms | 50 req/s |
| **getTransactionsByAddress** | < 500ms | 10 req/s |
| **Batch Import (sync)** | N/A | 1000-5000 blocks/s |

### 4.3 缓存命中率目标

| 缓存层 | 命中率目标 | 说明 |
|--------|-----------|------|
| **L1 Block Cache** | 85-95% | 最近访问的 blocks |
| **L1 BlockInfo Cache** | 90-95% | Metadata 访问频繁 |
| **L1 Transaction Cache** | 70-80% | Transaction 验证 |
| **L2 RocksDB Cache** | 75-85% | RocksDB block cache |
| **Overall Hit Rate** | 90%+ | 综合命中率 |

---

## 五、实施建议 (Implementation Recommendations)

### 5.1 硬件配置建议

#### 最低配置
- **CPU**: 4 cores
- **内存**: 8 GB RAM
- **存储**: 128 GB SSD
- **网络**: 100 Mbps

#### 推荐配置
- **CPU**: 8 cores (Intel Xeon / AMD EPYC)
- **内存**: 16 GB RAM
- **存储**: 256 GB NVMe SSD
- **网络**: 1 Gbps

#### 高性能配置
- **CPU**: 16+ cores
- **内存**: 32 GB RAM
- **存储**: 512 GB NVMe SSD (PCIe 4.0)
- **网络**: 10 Gbps

### 5.2 JVM 配置建议

```bash
# 推荐 JVM 配置
java -Xms8g -Xmx8g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:G1HeapRegionSize=16m \
     -XX:+ParallelRefProcEnabled \
     -XX:+DisableExplicitGC \
     -XX:+AlwaysPreTouch \
     -XX:+UseStringDeduplication \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar xdagj.jar
```

### 5.3 操作系统优化

```bash
# Linux 内核参数优化
# /etc/sysctl.conf

# 增加文件描述符限制
fs.file-max = 65536

# 增加 TCP buffer
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216

# 禁用 swappiness (SSD 优化)
vm.swappiness = 1

# 增加脏页限制
vm.dirty_ratio = 15
vm.dirty_background_ratio = 5

# 应用配置
sysctl -p
```

### 5.4 监控和告警

```yaml
# Prometheus 监控配置示例
dagstore_metrics:
  - dagstore_block_write_latency_seconds
  - dagstore_block_read_latency_seconds
  - dagstore_cache_hit_rate
  - dagstore_database_size_bytes
  - dagstore_compaction_count

alerts:
  - alert: HighBlockWriteLatency
    expr: dagstore_block_write_latency_seconds{quantile="0.99"} > 0.1
    for: 5m
    annotations:
      summary: "Block write latency too high"

  - alert: LowCacheHitRate
    expr: dagstore_cache_hit_rate < 0.8
    for: 10m
    annotations:
      summary: "Cache hit rate below 80%"

  - alert: DatabaseSizeTooLarge
    expr: dagstore_database_size_bytes > 100e9  # 100 GB
    annotations:
      summary: "Database size exceeds 100 GB"
```

---

## 六、总结 (Summary)

### 容量规划
- **10年50节点**: 50-85 GB 存储空间
- **推荐配置**: 256 GB SSD
- **增长预估**: 每年增长 5-8.5 GB

### 性能优化
- **分层缓存**: L1 (256-512 MB) + L2 (2-4 GB)
- **批量写入**: WriteBatch 优化
- **预取策略**: 顺序访问预取
- **索引优化**: Bloom filter + 复合索引

### 关键指标
- **Block Import**: < 50ms, 1 block/s
- **Cache Hit Rate**: 90%+
- **Batch Import**: 1000-5000 blocks/s

### 推荐架构
- **方案 B**: DagStore + TransactionStore (分离)
- **协调层**: DagEntityResolver Facade
- **缓存**: Caffeine L1 + RocksDB L2
- **存储引擎**: RocksDB (LSM-tree)

**该设计可以稳定支持 50 节点运行 10 年，并保持高性能！**
