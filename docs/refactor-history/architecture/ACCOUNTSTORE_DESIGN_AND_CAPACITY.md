# AccountStore 设计与容量分析补充

## 添加时间
2025-11-06

## 最后更新
2025-11-06 - 修改为 32 字节地址（XDAG 标准）

## 概述

本文档作为 DAGSTORE_DESIGN_ANALYSIS.md 和 DAGSTORE_CAPACITY_AND_PERFORMANCE.md 的补充，专注于 AccountStore 的设计和容量分析。

**重要变更**: AccountStore 使用 **32 字节地址**（XDAG 标准），而非 20 字节 EVM 地址，以确保与 XDAG 区块链整体架构的一致性。

---

## 一、AccountStore 架构设计

### 1.1 核心数据结构

#### Account 模型 (XDAG 标准)

```java
@Value
@Builder
public class Account {
    Bytes32 address;            // 32 bytes (XDAG standard)
    UInt256 balance;            // 32 bytes (256-bit unsigned)
    UInt64 nonce;               // 8 bytes (transaction counter)
    Bytes32 codeHash;           // 32 bytes (optional, for contracts)
    Bytes32 storageRoot;        // 32 bytes (optional, for contracts)
}
```

#### 序列化格式

**EOA (Externally Owned Account)**:
```
+----------+----------+-------+----------+
| address  | balance  | nonce | has_code |
| 32 bytes | 32 bytes | 8 bytes| 1 byte  |
+----------+----------+-------+----------+
Total: 73 bytes
```

**Contract Account**:
```
+----------+----------+-------+----------+----------+-------------+
| address  | balance  | nonce | has_code | codeHash | storageRoot |
| 32 bytes | 32 bytes | 8 bytes| 1 byte  | 32 bytes | 32 bytes   |
+----------+----------+-------+----------+----------+-------------+
Total: 137 bytes
```

#### 设计说明

**为什么使用 32 字节地址？**

1. **系统一致性**
   - Block: 使用 Bytes32 (32 字节)
   - Transaction: 使用 Bytes32 (32 字节)
   - Account: 使用 Bytes32 (32 字节) ✅ **统一！**

2. **无需地址转换**
   - Transaction → Account: 直接使用，无需截取
   - 减少错误，提高性能

3. **XDAG 标准**
   - XDAG 区块链的原生地址就是 32 字节
   - 不应为假设的 EVM 兼容而牺牲系统一致性

4. **未来兼容性**
   - 如需 EVM 兼容，可在适配层做地址映射
   - 核心存储保持 XDAG 原生格式

### 1.2 存储布局

#### RocksDB Key Prefixes

```
0x01 - Account data (address → Account)
0x02 - Contract code (codeHash → bytes)
0x03 - Account count (singleton)
0x04 - Total balance (singleton)
```

#### Key Format

```
Account data:    0x01 + address(32) → Account(73-137 bytes)
Contract code:   0x02 + codeHash(32) → code bytes
Account count:   0x03 → UInt64
Total balance:   0x04 → UInt256
```

### 1.3 AccountStore 接口

```java
public interface AccountStore extends XdagLifecycle {
    // ==================== CRUD ====================
    void saveAccount(Account account);
    Optional<Account> getAccount(Bytes32 address);
    boolean hasAccount(Bytes32 address);
    boolean deleteAccount(Bytes32 address);

    // ==================== Balance ====================
    UInt256 getBalance(Bytes32 address);
    void setBalance(Bytes32 address, UInt256 balance);
    UInt256 addBalance(Bytes32 address, UInt256 amount);
    UInt256 subtractBalance(Bytes32 address, UInt256 amount);
    UInt256 getTotalBalance();

    // ==================== Nonce ====================
    UInt64 getNonce(Bytes32 address);
    void setNonce(Bytes32 address, UInt64 nonce);
    UInt64 incrementNonce(Bytes32 address);

    // ==================== Contract ====================
    void saveContractCode(Bytes32 codeHash, byte[] code);
    Optional<byte[]> getContractCode(Bytes32 codeHash);
    boolean hasContractCode(Bytes32 codeHash);
    Account createContractAccount(Bytes32 address, Bytes32 codeHash, Bytes32 storageRoot);

    // ==================== Batch ====================
    void saveAccounts(List<Account> accounts);
    Map<Bytes32, Account> getAccounts(List<Bytes32> addresses);

    // ==================== Statistics ====================
    UInt64 getAccountCount();
    List<Bytes32> getAllAddresses(int limit);
    long getDatabaseSize();
}
```

---

## 二、容量规划分析

### 2.1 Account 数量估算

#### 场景分析

**场景 A: 当前网络（保守）**
- **活跃节点**: 50 nodes
- **假设**: 每个节点 10 个账户（主账户 + 测试账户）
- **活跃账户**: 50 × 10 = **500 accounts**
- **额外账户**: 交易对手、合约账户等 = 500
- **总计**: ~**1,000 accounts**

**场景 B: 未来扩展（中期 2-3 年）**
- **节点增长**: 200 nodes
- **每节点账户**: 20 accounts
- **DApp 合约**: 100 contracts
- **用户账户**: 2,000 accounts
- **总计**: ~**6,000-10,000 accounts**

**场景 C: 长期目标（5-10 年）**
- **节点**: 1,000 nodes
- **DApp 生态**: 500 contracts
- **活跃用户**: 50,000 accounts
- **总计**: ~**100,000 accounts**

#### 推荐设计目标

- **短期（1 年）**: 1,000 - 5,000 accounts
- **中期（3 年）**: 10,000 - 50,000 accounts
- **长期（10 年）**: **100,000 - 500,000 accounts** ← 设计目标

### 2.2 Account 存储大小

#### EOA 账户存储 (32 字节地址)

```
单个 EOA:  73 bytes
1,000 EOA: 73 KB
10,000 EOA: 730 KB
100,000 EOA: 7.3 MB
500,000 EOA: 36.5 MB
```

#### Contract 账户存储 (32 字节地址)

```
单个 Contract:  137 bytes
100 Contracts:  13.7 KB
500 Contracts:  68.5 KB
1,000 Contracts: 137 KB
```

#### 混合场景（100K accounts, 假设 2% 为合约）

```
EOA (98,000):        98K × 73 = 7.15 MB
Contract (2,000):    2K × 137 = 0.27 MB
--------------------------------------
Total Account Data:  7.42 MB
```

**对比 20 字节地址方案**:
- 20 字节: 6.33 MB
- 32 字节: 7.42 MB
- **增加**: 1.09 MB (17% 增长)
- **影响**: 可忽略（< 0.01% 总存储）

### 2.3 Contract Code 存储

#### Contract Code 大小估算

**典型合约大小**:
- **小型合约**: 1-5 KB (简单 token, voting)
- **中型合约**: 5-20 KB (DEX, NFT marketplace)
- **大型合约**: 20-50 KB (复杂 DeFi protocol)
- **平均**: ~**10 KB**

#### Contract Code 总量

```
100 contracts × 10 KB = 1 MB
500 contracts × 10 KB = 5 MB
1,000 contracts × 10 KB = 10 MB
5,000 contracts × 10 KB = 50 MB
```

**推荐设计**: 支持 **5,000 contracts × 10 KB = 50 MB**

### 2.4 索引和元数据

#### Account Count Index
```
Single UInt64: 8 bytes (negligible)
```

#### Total Balance Index
```
Single UInt256: 32 bytes (negligible)
```

#### RocksDB Overhead

```
LSM-tree overhead: ~20-30% of data size
Bloom filters: ~10 bits/key = 1.25 bytes/account
Write-Ahead Log (WAL): ~10 MB temporary
```

**Overhead 估算 (32 字节地址)**:
```
Account data: 7.42 MB
Contract code: 50 MB
--------------------------------------
Raw data: 57.42 MB
LSM overhead (25%): 14.36 MB
Bloom filters: 0.64 MB
WAL: 10 MB (temporary)
--------------------------------------
Total: ~72.4 MB + 10 MB (WAL)
```

### 2.5 AccountStore 总容量

#### 保守估计 (100K accounts, 500 contracts, 32 字节地址)

```
Account data:        7.42 MB
Contract code:       5 MB
RocksDB overhead:    3.1 MB
Bloom filters:       0.13 MB
WAL (temporary):     10 MB
--------------------------------------
Total:               25.7 MB + 10 MB (WAL)
--------------------------------------
推荐预留:             50 MB
```

#### 扩展估计 (500K accounts, 5K contracts, 32 字节地址)

```
Account data:        36.5 MB
Contract code:       50 MB
RocksDB overhead:    21.6 MB
Bloom filters:       0.64 MB
WAL (temporary):     10 MB
--------------------------------------
Total:               109 MB + 10 MB (WAL)
--------------------------------------
推荐预留:             150 MB
```

**结论**: AccountStore 在 10 年内需要 **50-150 MB** 存储空间

**对比 20 字节方案**:
- 20 字节方案: 102 MB
- 32 字节方案: 109 MB
- **增加**: 7 MB (6.8%)
- **代价**: 可接受，换来系统一致性

---

## 三、性能分析

### 3.1 读写场景

#### 场景 1: Transaction 验证（高频）

**操作**:
1. `getNonce(from)` - 检查 nonce
2. `getBalance(from)` - 检查余额
3. `incrementNonce(from)` - 递增 nonce
4. `subtractBalance(from, amount + fee)` - 扣款
5. `addBalance(to, amount)` - 入账

**频率**: 每个交易 5 次读写
- 峰值: 5 tx/s × 5 ops = 25 ops/s

**性能目标**:
- **Read**: < 1ms (from cache), < 5ms (from disk)
- **Write**: < 5ms (async), < 20ms (sync)

#### 场景 2: Block 处理（中频）

**操作**:
1. 处理 Block 内所有 Transaction
2. 批量更新 Account 状态

**频率**: 每 64 秒 1-3 个 Block
- 每个 Block 平均 2-5 个 Transaction
- **平均**: 0.05-0.1 blocks/s

**优化**: 使用 `saveAccounts(List<Account>)` 批量写入

#### 场景 3: 余额查询（高频 RPC）

**操作**: `getBalance(address)`

**频率**:
- RPC API 查询: 10-100 req/s
- 钱包刷新: 1-10 req/s

**性能目标**:
- **Latency**: < 5ms (P99)
- **Throughput**: 100 req/s

#### 场景 4: Contract 部署（低频）

**操作**:
1. `createContractAccount(address, codeHash, storageRoot)`
2. `saveContractCode(codeHash, code)`

**频率**: 1-10 contracts/day

**性能目标**: < 100ms (non-critical path)

### 3.2 缓存策略

#### L1 Cache (Caffeine)

由于 Account 数据量较小（< 150 MB），可以考虑缓存热点账户：

```java
// AccountStore L1 Cache (32 字节地址)
Cache<Bytes32, Account> accountCache = Caffeine.newBuilder()
    .maximumSize(10_000)  // 10K accounts × 73 bytes = 730 KB
    .expireAfterAccess(Duration.ofMinutes(30))
    .recordStats()
    .build();
```

**缓存命中率目标**:
- **活跃账户**: 85-95% (矿工、交易所、热钱包)
- **普通账户**: 50-70%
- **Overall**: 70-80%

#### L2 Cache (RocksDB Block Cache)

```
RocksDB Block Cache: 256 MB (shared with DagStore)
Account data: ~110 MB (32 字节方案)
Block hit rate: 80-90%
```

#### 预热策略

```java
/**
 * 预加载活跃账户到缓存
 */
public void warmupCache() {
    // 1. 加载矿工账户
    List<Bytes32> minerAddresses = getMinerAddresses();
    for (Bytes32 address : minerAddresses) {
        getAccount(address).ifPresent(account ->
            accountCache.put(address, account));
    }

    // 2. 加载最近交易的账户 (from TX history)
    List<Bytes32> recentAddresses = getRecentTransactionAddresses(1000);
    for (Bytes32 address : recentAddresses) {
        getAccount(address).ifPresent(account ->
            accountCache.put(address, account));
    }

    log.info("AccountStore cache warmed up: {} accounts loaded",
        accountCache.estimatedSize());
}
```

### 3.3 RocksDB 优化

#### Write Options

```java
// Async writes for normal account updates
WriteOptions writeOptions = new WriteOptions()
    .setSync(false)
    .setDisableWAL(false);

// Sync writes for critical operations (final balance after block)
WriteOptions syncWriteOptions = new WriteOptions()
    .setSync(true)
    .setDisableWAL(false);
```

#### Batch Write Optimization

```java
/**
 * 批量处理 Block 内的 Account 更新
 */
public void processBlockAccounts(Block block, List<Transaction> txs) {
    WriteBatch batch = new WriteBatch();

    try {
        for (Transaction tx : txs) {
            // Update from account
            Account from = getAccount(tx.getFrom()).orElse(Account.createEOA(tx.getFrom()));
            Account updatedFrom = from
                .withBalance(from.getBalance().subtract(tx.getAmount().add(tx.getFee())))
                .withIncrementedNonce();

            byte[] fromKey = makeAccountKey(updatedFrom.getAddress());
            batch.put(defaultHandle, fromKey, updatedFrom.toBytes());

            // Update to account
            Account to = getAccount(tx.getTo()).orElse(Account.createEOA(tx.getTo()));
            Account updatedTo = to.withBalance(to.getBalance().add(tx.getAmount()));

            byte[] toKey = makeAccountKey(updatedTo.getAddress());
            batch.put(defaultHandle, toKey, updatedTo.toBytes());
        }

        // Atomic write
        db.write(syncWriteOptions, batch);

        log.debug("Processed {} account updates in batch", txs.size() * 2);

    } finally {
        batch.close();
    }
}
```

### 3.4 性能指标

| 操作 | 延迟目标 (P99) | 吞吐目标 |
|------|---------------|---------|
| **getAccount** | < 5ms (cache miss) | 100 req/s |
| **saveAccount** | < 10ms (async) | 50 req/s |
| **getBalance** | < 1ms (cache hit) | 200 req/s |
| **incrementNonce** | < 10ms | 50 req/s |
| **Batch Update** | < 50ms (10 accounts) | 20 batches/s |

---

## 四、集成到 DagKernel

### 4.1 DagKernel 组件更新

```java
@Slf4j
@Getter
public class DagKernel {
    private final Config config;

    // ==================== Storage Layer ====================
    private final DatabaseFactory dbFactory;
    private final DagStore dagStore;
    private final TransactionStore transactionStore;
    private final OrphanBlockStore orphanBlockStore;
    private final AccountStore accountStore;  // Account storage (32-byte addresses)

    // ==================== Cache Layer ====================
    private final DagCache dagCache;
    private final DagEntityResolver entityResolver;

    // ==================== Processing Layer (Phase 10) ====================
    private final DagAccountManager accountManager;
    private final DagTransactionProcessor transactionProcessor;
    private final DagBlockProcessor blockProcessor;

    // ==================== Consensus Layer ====================
    private final Blockchain blockchain;

    public DagKernel(Config config, Wallet wallet) {
        this.config = config;

        // Initialize DatabaseFactory
        this.dbFactory = new RocksdbFactory(config);

        // Initialize Storage Layer
        this.dagStore = new DagStoreImpl(config);
        this.transactionStore = new TransactionStoreImpl(
            dbFactory.getDB(DatabaseName.TRANSACTION),
            dbFactory.getDB(DatabaseName.INDEX)
        );
        this.orphanBlockStore = new OrphanBlockStoreImpl(
            dbFactory.getDB(DatabaseName.ORPHANIND)
        );

        // Initialize AccountStore (32-byte addresses)
        this.accountStore = new AccountStoreImpl(config);
        log.info("✓ AccountStore initialized (32-byte addresses)");

        // Initialize Cache Layer
        this.dagCache = new DagCache();
        this.entityResolver = new DagEntityResolver(dagStore, transactionStore);

        // Initialize Processing Layer
        this.accountManager = new DagAccountManager(accountStore, config);
        this.transactionProcessor = new DagTransactionProcessor(accountManager, transactionStore);
        this.blockProcessor = new DagBlockProcessor(transactionProcessor, dagStore);
        log.info("✓ Dag Processing Layer initialized");

        // Initialize Blockchain (consensus layer)
        this.blockchain = new BlockchainImpl(this);
    }

    @Override
    public synchronized void start() {
        // ... existing startup code

        // Start AccountStore
        accountStore.start();
        log.info("✓ AccountStore started: {} accounts, total balance {}",
            accountStore.getAccountCount().toLong(),
            accountStore.getTotalBalance().toDecimalString());

        // ... rest of startup
    }

    @Override
    public synchronized void stop() {
        // ... existing shutdown code

        // Stop AccountStore
        accountStore.stop();
        log.info("✓ AccountStore stopped");

        // ... rest of shutdown
    }
}
```

### 4.2 Blockchain 集成

```java
@Slf4j
public class BlockchainImpl implements Blockchain {
    private final DagKernel dagKernel;
    private final DagBlockProcessor blockProcessor;

    public BlockchainImpl(DagKernel dagKernel) {
        this.dagKernel = dagKernel;
        this.blockProcessor = dagKernel.getDagBlockProcessor();
    }

    @Override
    public DagImportResult processBlock(Block block) {
        // ... block validation

        // Process transactions and update accounts (using 32-byte addresses)
        List<Transaction> txs = resolveTransactions(block);
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlockTransactions(block, txs);

        if (!result.isSuccess()) {
            log.warn("Block processing failed: {}", result.getError());
            return DagImportResult.failed(result.getError());
        }

        // ... rest of block processing
    }
}
```

---

## 五、总体容量汇总（含 AccountStore）

### 5.1 完整存储容量（10 年运行）

#### 原有存储（参考 DAGSTORE_CAPACITY_AND_PERFORMANCE.md）

```
Blocks:          12-19 GB
BlockInfo:       2-3.4 GB
Transactions:    10-24 GB
Indexes:         18-30 GB
Overhead:        5-8 GB
---------------------------------
DagStore Total:  47-85 GB
```

#### 新增 AccountStore (32 字节地址)

```
Account data:    36.5 MB (500K accounts)
Contract code:   50 MB (5K contracts)
RocksDB overhead: 21.6 MB
---------------------------------
AccountStore:    ~109 MB
```

#### 总计

```
DagStore:        50-85 GB
TransactionStore: (included in DagStore)
AccountStore:    0.11 GB (32-byte addresses)
---------------------------------
Total Storage:   50-85.11 GB
```

**结论**: AccountStore 新增存储开销 **< 0.2%**，几乎可忽略

**对比 20 字节方案**:
- 20 字节: 102 MB
- 32 字节: 109 MB
- 差异: 7 MB (仅占总存储 0.01%)

### 5.2 内存使用（L1 Cache）

#### 原有 L1 Cache

```
Block Cache:        5 MB
BlockInfo Cache:    4.2 MB
Transaction Cache:  4 MB
Height Cache:       0.4 MB
Epoch Winner Cache: 0.2 MB
---------------------------------
DagCache Total:     13.8 MB
```

#### 新增 AccountStore L1 Cache (32 字节)

```
Account Cache:      0.73 MB (10K accounts × 73 bytes)
---------------------------------
AccountStore Cache: 0.73 MB
```

#### 总计

```
DagCache:           13.8 MB
AccountStore Cache: 0.73 MB
---------------------------------
Total L1 Cache:     14.53 MB
```

**结论**: AccountStore 新增内存开销 **0.73 MB**，可忽略

**对比 20 字节方案**:
- 20 字节: 0.62 MB
- 32 字节: 0.73 MB
- 差异: 0.11 MB (110 KB)

### 5.3 RocksDB L2 Cache (共享)

```
原 L2 Cache:        2-4 GB
新增 Account data:  ~110 MB (will be cached in shared L2)
---------------------------------
推荐 L2 Cache:      2-4 GB (无需增加)
```

---

## 六、性能影响评估

### 6.1 读性能

#### Block Import 流程新增操作

```
原流程 (Phase 8):
1. Validate links (20 reads)
2. Calculate difficulty (10 reads)
3. Save block (15 writes)
Total: ~30 reads, 15 writes

新增 Account 验证 (Phase 10):
1. Validate sender balance (1 read, 32-byte key)
2. Validate sender nonce (included in above)
Total: +1 read per transaction

For 2 tx/block average:
New total: 32 reads, 17 writes
```

**影响**: 增加 **6%** 读操作

**32 字节地址影响**:
- Key 大小: 33 bytes (1 prefix + 32 address)
- vs 20 字节: 21 bytes
- 差异: 12 bytes/key
- RocksDB 影响: < 1% (由于压缩和缓存)

### 6.2 写性能

```
原 Block import: ~15 writes
新增 Account updates: 2 accounts × 2 tx = 4 writes (32-byte keys)
New total: 19 writes

增加: 27%
```

**优化**: 使用 WriteBatch 原子写入，实际延迟增加 < 10%

**32 字节地址影响**:
- 数据大小: +11 bytes/EOA (+17%)
- WriteBatch overhead: < 5%
- 总体影响: < 10%

### 6.3 整体延迟目标

| 操作 | 原延迟目标 | 新延迟目标 (含 Account, 32B) | 影响 |
|------|-----------|------------------------|------|
| **Block Import** | < 50ms | < 55ms | +10% |
| **Transaction Validation** | < 10ms | < 12ms | +20% |
| **getBalance (RPC)** | N/A | < 5ms | 新增 |

**结论**:
- AccountStore 对整体性能影响 **< 10%**，完全可接受
- 32 字节地址额外开销 **< 2%**，换来系统一致性

---

## 七、设计决策总结

### 7.1 为什么选择 32 字节地址？

| 对比项 | 20 字节 (EVM) | 32 字节 (XDAG) | 胜出 |
|-------|--------------|---------------|-----|
| **系统一致性** | ❌ 需要转换 | ✅ 统一格式 | **32 字节** |
| **存储增长** | 102 MB | 109 MB (+6.8%) | 20 字节 |
| **内存增长** | 0.62 MB | 0.73 MB (+17%) | 20 字节 |
| **代码复杂度** | ❌ 需要转换逻辑 | ✅ 无需转换 | **32 字节** |
| **错误风险** | ❌ 转换可能出错 | ✅ 无转换风险 | **32 字节** |
| **EVM 兼容** | ✅ 直接兼容 | ⚠️ 需适配层 | 20 字节 |
| **XDAG 原生** | ❌ 非原生 | ✅ 原生支持 | **32 字节** |

**结论**: **32 字节地址胜出 (5:2)**

**核心理由**:
1. ✅ **系统一致性优先** - 避免地址转换，减少错误
2. ✅ **存储代价可接受** - 仅增加 7 MB (0.01% 总存储)
3. ✅ **性能影响可忽略** - < 2% 额外开销
4. ✅ **符合 XDAG 标准** - 保持原生架构
5. ⚠️ **EVM 兼容** - 可在未来适配层实现

### 7.2 架构优势

#### 当前架构（32 字节统一）

```
┌─────────────────────────────────────┐
│  Transaction (Bytes32: 32 bytes)    │
│         ↓                           │
│  DagTransactionProcessor            │
│         ↓  (无需转换)                │
│  DagAccountManager                  │
│         ↓                           │
│  AccountStore (Bytes32: 32 bytes)   │
└─────────────────────────────────────┘
✅ 无地址转换，类型安全
```

#### 旧架构（20 字节不一致）

```
┌─────────────────────────────────────┐
│  Transaction (Bytes32: 32 bytes)    │
│         ↓                           │
│  toAccountAddress()  ❌ 截取转换     │
│         ↓                           │
│  AccountStore (Bytes: 20 bytes)     │
└─────────────────────────────────────┘
❌ 需要转换，容易出错
```

### 7.3 未来扩展性

**如果未来需要 EVM 兼容**，可以：

1. **适配层方案**:
   ```java
   public class EvmAdapter {
       // EVM 20-byte address → XDAG 32-byte address
       public Bytes32 evmToXdagAddress(Bytes20 evmAddr) {
           return Bytes32.rightPad(evmAddr);
       }

       // XDAG 32-byte address → EVM 20-byte address
       public Bytes20 xdagToEvmAddress(Bytes32 xdagAddr) {
           return Bytes20.wrap(xdagAddr.slice(0, 20));
       }
   }
   ```

2. **地址映射表**:
   ```
   0x05 - EVM address mapping (evmAddr20 → xdagAddr32)
   ```

3. **保持核心存储不变**: AccountStore 继续使用 32 字节

---

## 八、测试验证

### 8.1 已完成测试 ✅

- ✅ `DagKernelIntegrationTest` - 19 tests passing
  - Account CRUD operations (32-byte addresses)
  - Balance operations
  - Nonce operations
  - Transaction processing with account state updates
  - Multi-transaction sequencing

### 8.2 测试覆盖

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 5.378 s

✓ Account creation with 32-byte addresses
✓ Balance add/subtract operations
✓ Nonce increment operations
✓ Transaction processing with state updates
✓ Multiple sequential transactions
✓ Insufficient balance validation
✓ Invalid nonce validation
```

### 8.3 未来测试计划

#### 性能测试
- [ ] Account read latency (P50, P99)
- [ ] Account write latency (async vs sync)
- [ ] Batch update throughput
- [ ] Cache hit rate measurement

#### 压力测试
- [ ] 100K accounts load test
- [ ] 1M accounts load test (future-proofing)
- [ ] Concurrent read/write test (100 threads)

---

## 九、总结

### 9.1 AccountStore 关键指标 (32 字节地址)

| 指标 | 数值 | 说明 |
|------|-----|------|
| **Address Size** | 32 bytes | XDAG 标准 |
| **Account Size** | 73 bytes (EOA) | +11 bytes vs 20B |
| **Contract Size** | 137 bytes | +11 bytes vs 20B |
| **Account 数量** | 100K - 500K | 10 年设计目标 |
| **存储容量** | 50-150 MB | 含 RocksDB overhead |
| **L1 Cache** | 0.73 MB | 10K 热点账户 |
| **读延迟** | < 5ms (P99) | From disk |
| **写延迟** | < 10ms (async) | 批量优化 |
| **Cache 命中率** | 70-80% | 活跃账户 |
| **性能影响** | < 10% | 对 Block import 影响 |

### 9.2 设计优势

1. ✅ **系统一致性** - Block/Transaction/Account 统一使用 32 字节
2. ✅ **紧凑存储** - 73 bytes/EOA, 137 bytes/contract
3. ✅ **高效缓存** - 10K 账户仅占 0.73 MB 内存
4. ✅ **原子操作** - WriteBatch 确保一致性
5. ✅ **类型安全** - 强类型 Account 模型，无需转换
6. ✅ **XDAG 原生** - 符合 XDAG 区块链标准
7. ✅ **可扩展** - 支持未来 contract storage 和 EVM 适配

### 9.3 存储占比

```
Total Storage (10 years): 50-85 GB

DagStore + TransactionStore: 50-85 GB (99.8%)
AccountStore (32-byte):       0.11 GB  (0.2%)
```

**AccountStore 仅占总存储的 0.2%，但提供了完整的统一账户模型！**

### 9.4 关键变更记录

| 日期 | 变更 | 原因 |
|------|------|------|
| 2025-11-06 (初版) | 使用 20 字节地址 | 假设 EVM 兼容需求 |
| 2025-11-06 (修订) | **改为 32 字节地址** | **系统一致性优先，符合 XDAG 标准** |

---

**XDAG v5.1 AccountStore 设计完成（32 字节地址）！** 🎉
