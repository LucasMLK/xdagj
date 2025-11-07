# DagStore Architecture Design Analysis

## 当前存储架构分析

### 现有 Store 职责

**BlockStore** (`0x10-0xd0`):
```
- Block 存储 (saveBlock/getBlock)
- BlockInfo 元数据
- ChainStats 统计
- 索引：
  * TIME_HASH_INFO (0x20): timestamp -> blockHash
  * HASH_BLOCK_INFO (0x30): blockHash -> BlockInfo
  * BLOCK_HEIGHT (0x80): height -> blockHash (main blocks)
  * BLOCK_EPOCH_INDEX (0xb0): epoch -> List<blockHash>
  * MAIN_BLOCKS_INDEX (0xc0): height -> blockHash
  * BLOCK_REFS_INDEX (0xd0): blockHash -> List<referencingHashes>
```

**TransactionStore** (`0xe0-0xe3`):
```
- Transaction 存储 (saveTransaction/getTransaction)
- 索引：
  * TX_DATA (0xe0): txHash -> Transaction
  * TX_BLOCK_INDEX (0xe1): blockHash -> List<txHash>
  * TX_ADDRESS_INDEX (0xe2): address -> List<txHash>
  * TRANSACTION_TO_BLOCK_INDEX (0xe3): txHash -> blockHash
```

**OrphanBlockStore**:
```
- 临时孤块存储
- 等待父块的块缓存
```

---

## 核心问题分析

### XDAG v5.1 的核心数据结构

```java
// 只有 3 个核心实体
Block      - 区块（可以有 coinbase）
Transaction - 交易（转账）
Link       - 引用关系（不单独存储，在 Block 内）

// Link 的特点
Link.toBlock(hash)       - 引用区块
Link.toTransaction(hash)  - 引用交易
```

### Link 验证的存储需求

在 `DagChainImpl.validateLinks()` 中：
```java
for (Link link : block.getLinks()) {
    if (link.isTransaction()) {
        Transaction tx = transactionStore.getTransaction(link.getTargetHash());  // 查询 1
        // 验证...
    } else {
        Block refBlock = blockStore.getBlockByHash(link.getTargetHash(), false);  // 查询 2
        // 验证...
    }
}
```

**问题**：需要查询两个 Store，增加了复杂度。

---

## 设计方案对比

### 方案 A：统一 DagStore（合并设计）

#### 接口设计

```java
/**
 * DagStore for XDAG v5.1 - Unified DAG Entity Storage
 *
 * This store manages all DAG entities (Block, Transaction) in a unified way.
 * Link references are resolved transparently across both entity types.
 */
public interface DagStore extends XdagLifecycle {

    // ==================== Block Operations ====================

    void saveBlock(Block block);
    Block getBlockByHash(Bytes32 hash, boolean isRaw);
    boolean hasBlock(Bytes32 hash);
    void deleteBlock(Bytes32 hash);

    // Main chain queries
    Block getBlockByHeight(long height, boolean isRaw);
    List<Block> getBlocksByTime(long startTime, long endTime);
    List<Block> getBlocksByEpoch(long epoch);

    // Block references (for DAG traversal)
    List<Bytes32> getBlockReferences(Bytes32 hash);

    // ==================== Transaction Operations ====================

    void saveTransaction(Transaction tx);
    Transaction getTransactionByHash(Bytes32 hash);
    boolean hasTransaction(Bytes32 hash);
    void deleteTransaction(Bytes32 hash);

    // Transaction queries
    List<Transaction> getTransactionsByBlock(Bytes32 blockHash);
    List<Transaction> getTransactionsByAddress(Bytes32 address);
    Bytes32 getBlockByTransaction(Bytes32 txHash);

    // ==================== Unified Link Resolution ====================

    /**
     * Get DAG entity by hash (works for both Block and Transaction)
     *
     * This method provides unified link resolution:
     * - If hash is a Block, return Block (as Object)
     * - If hash is a Transaction, return Transaction (as Object)
     * - If not found, return null
     *
     * @param hash Entity hash (Block or Transaction)
     * @return Block or Transaction, or null if not found
     */
    Object getEntityByHash(Bytes32 hash);

    /**
     * Check if hash exists (Block or Transaction)
     *
     * @param hash Entity hash
     * @return true if exists as Block or Transaction
     */
    boolean hasEntity(Bytes32 hash);

    /**
     * Resolve all links for a block
     *
     * @param block Block with links to resolve
     * @return ResolvedLinks containing all referenced blocks and transactions
     */
    ResolvedLinks resolveLinks(Block block);

    // ==================== Metadata & Stats ====================

    void saveBlockInfo(BlockInfo blockInfo);
    BlockInfo getBlockInfo(Bytes32 hash);

    void saveChainStats(ChainStats stats);
    ChainStats getChainStats();

    // ==================== Batch Operations ====================

    void saveBlocks(List<Block> blocks);
    void saveTransactions(List<Transaction> transactions);
    List<Block> getBlocksByHashes(List<Bytes32> hashes);
    List<Transaction> getTransactionsByHashes(List<Bytes32> hashes);
}

/**
 * Helper class for resolved links
 */
@Data
@Builder
public class ResolvedLinks {
    private final List<Block> referencedBlocks;
    private final List<Transaction> referencedTransactions;
    private final List<Bytes32> missingReferences;

    public boolean hasAllReferences() {
        return missingReferences.isEmpty();
    }
}
```

#### 存储布局（统一前缀）

```
// DAG Entities (0xa0-0xaf)
0xa0: Block storage (hash -> Block)
0xa1: Transaction storage (hash -> Transaction)
0xa2: BlockInfo metadata (hash -> BlockInfo)

// DAG Indexes (0xb0-0xbf)
0xb0: Time index (timestamp -> List<blockHash>)
0xb1: Epoch index (epoch -> List<blockHash>)
0xb2: Height index (height -> blockHash)
0xb3: Block references (blockHash -> List<referencingHashes>)
0xb4: Transaction-to-Block (txHash -> blockHash)
0xb5: Address-to-Transactions (address -> List<txHash>)

// DAG Stats (0xc0-0xcf)
0xc0: ChainStats
0xc1: Entity counts
```

#### 优点 ✅

1. **统一的数据访问**：
   ```java
   // 简化 Link 验证
   ResolvedLinks resolved = dagStore.resolveLinks(block);
   if (!resolved.hasAllReferences()) {
       // 处理缺失的引用
   }
   ```

2. **事务一致性**：
   - 所有 DAG 实体在同一个 store
   - 更容易实现原子操作
   - 统一的批量写入

3. **符合 DAG 概念**：
   - DAG 是一个整体
   - Block 和 Transaction 都是 DAG 节点
   - 统一管理更自然

4. **简化 Link 操作**：
   ```java
   // 检查 Link 是否存在
   boolean exists = dagStore.hasEntity(link.getTargetHash());

   // 获取 Link 目标（不用区分类型）
   Object entity = dagStore.getEntityByHash(link.getTargetHash());
   ```

5. **更好的缓存策略**：
   - 统一的 LRU 缓存
   - Block 和 Transaction 共享缓存空间
   - 更高效的内存使用

#### 缺点 ❌

1. **接口复杂**：
   - 包含 Block 和 Transaction 的所有方法
   - 可能有 30+ 个方法

2. **Transaction 特殊查询不灵活**：
   - `getTransactionsByAddress()` 可能需要特殊优化
   - 与 Block 查询混在一起

3. **迁移难度大**：
   - 需要重写所有调用 BlockStore 和 TransactionStore 的代码
   - 风险较高

4. **类型安全问题**：
   ```java
   Object entity = dagStore.getEntityByHash(hash);
   // 需要 instanceof 检查，不太优雅
   if (entity instanceof Block) { ... }
   else if (entity instanceof Transaction) { ... }
   ```

---

### 方案 B：分离 DagStore 和 TransactionStore（改进设计）

#### 接口设计

```java
/**
 * DagStore for XDAG v5.1 - Block-focused DAG Storage
 *
 * This store manages Block entities and DAG structure.
 * For Transaction storage, use TransactionStore.
 */
public interface DagStore extends XdagLifecycle {

    // ==================== Block Operations ====================

    void saveBlock(Block block);
    Block getBlockByHash(Bytes32 hash, boolean isRaw);
    boolean hasBlock(Bytes32 hash);
    void deleteBlock(Bytes32 hash);

    // ==================== Main Chain Queries ====================

    Block getMainBlockAtPosition(long position, boolean isRaw);
    long getMainChainLength();
    List<Block> listMainBlocks(int count);

    // ==================== Epoch Queries ====================

    List<Block> getCandidateBlocksInEpoch(long epoch);
    Block getWinnerBlockInEpoch(long epoch);
    long getPositionOfWinnerBlock(long epoch);
    List<Block> getBlocksByTimeRange(long startTime, long endTime);

    // ==================== DAG Structure ====================

    /**
     * Get all blocks that reference a specific block
     * (Reverse lookup for DAG traversal)
     */
    List<Bytes32> getBlockReferences(Bytes32 blockHash);

    /**
     * Save reference relationship: block A references block B
     * This builds the reverse index for getBlockReferences()
     */
    void indexBlockReference(Bytes32 referencingBlock, Bytes32 referencedBlock);

    // ==================== BlockInfo & Metadata ====================

    void saveBlockInfo(BlockInfo blockInfo);
    BlockInfo getBlockInfo(Bytes32 hash);

    void saveChainStats(ChainStats stats);
    ChainStats getChainStats();

    // ==================== Coordination with TransactionStore ====================

    /**
     * Validate all links in a block (helper method)
     *
     * This method checks:
     * - All Block links exist in DagStore
     * - All Transaction links exist in TransactionStore
     *
     * @param block Block to validate
     * @param txStore TransactionStore for transaction link validation
     * @return DagLinkValidation result with detailed error info
     */
    DagLinkValidation validateLinks(Block block, TransactionStore txStore);
}

/**
 * TransactionStore remains largely unchanged
 * (Keep existing design from Phase 4)
 */
public interface TransactionStore extends XdagLifecycle {
    // ... (keep existing methods)

    // NEW: Batch validation helper
    /**
     * Check if all transaction hashes exist
     *
     * @param hashes List of transaction hashes
     * @return Map<hash, exists>
     */
    Map<Bytes32, Boolean> checkTransactionsExist(List<Bytes32> hashes);
}

/**
 * Helper class for link validation result
 */
@Data
@Builder
public class DagLinkValidation {
    private final boolean allLinksValid;
    private final List<Bytes32> missingBlocks;
    private final List<Bytes32> missingTransactions;

    public List<Bytes32> getAllMissingLinks() {
        List<Bytes32> all = new ArrayList<>();
        all.addAll(missingBlocks);
        all.addAll(missingTransactions);
        return all;
    }
}
```

#### 存储布局（分离前缀）

```
// DagStore (0xa0-0xbf)
0xa0: Block storage (hash -> Block)
0xa1: BlockInfo metadata (hash -> BlockInfo)
0xa2: ChainStats

0xb0: Time index (timestamp -> List<blockHash>)
0xb1: Epoch index (epoch -> List<blockHash>)
0xb2: Height index (height -> blockHash)
0xb3: Block references (blockHash -> List<referencingHashes>)

// TransactionStore (0xe0-0xef) - 保持不变
0xe0: Transaction storage (hash -> Transaction)
0xe1: Block-to-Transactions (blockHash -> List<txHash>)
0xe2: Address-to-Transactions (address -> List<txHash>)
0xe3: Transaction-to-Block (txHash -> blockHash)
```

#### 优点 ✅

1. **关注点分离**：
   - DagStore 专注于 Block 和 DAG 结构
   - TransactionStore 专注于 Transaction 和索引
   - 职责清晰

2. **Transaction 查询灵活**：
   ```java
   // 专门的 Transaction 查询优化
   List<Transaction> txs = transactionStore.getTransactionsByAddress(address);

   // 不与 Block 查询混淆
   Block block = dagStore.getMainBlockAtPosition(100, false);
   ```

3. **类型安全**：
   ```java
   // 不需要 instanceof 检查
   Block block = dagStore.getBlockByHash(hash, false);
   Transaction tx = transactionStore.getTransactionByHash(hash);
   ```

4. **渐进式迁移**：
   - 可以先迁移 BlockStore → DagStore
   - TransactionStore 保持不变
   - 降低风险

5. **独立扩展**：
   - DagStore 可以添加 DAG 特有的方法（如 `getWinnerBlockInEpoch`）
   - TransactionStore 可以添加 Transaction 特有的索引
   - 互不干扰

6. **接口简洁**：
   - DagStore: ~20 个方法（专注 Block）
   - TransactionStore: ~15 个方法（专注 Transaction）
   - 更易理解和维护

#### 缺点 ❌

1. **需要协调两个 Store**：
   ```java
   // validateLinks 需要查询两个 store
   DagLinkValidation validation = dagStore.validateLinks(block, transactionStore);
   ```

2. **可能有重复代码**：
   - 两个 store 都需要实现 batch 操作
   - 两个 store 都需要实现 lifecycle 管理

3. **事务一致性需要额外处理**：
   - 跨 store 的原子操作需要特殊处理
   - 可能需要引入 StoreCoordinator

---

## 推荐方案: 方案 B（分离设计） + Facade 模式

### 核心理念

**基础层（存储层）**：
- `DagStore` - Block 存储和 DAG 结构
- `TransactionStore` - Transaction 存储和索引

**协调层（Facade）**：
- `DagEntityResolver` - 统一的 Link 解析

### DagEntityResolver 设计

```java
/**
 * DagEntityResolver - Unified Link Resolution Facade
 *
 * This class provides a unified interface for resolving Link references
 * across both DagStore and TransactionStore.
 */
@Component
public class DagEntityResolver {

    private final DagStore dagStore;
    private final TransactionStore transactionStore;

    /**
     * Resolve a link target (Block or Transaction)
     *
     * @param link Link to resolve
     * @return ResolvedEntity containing the target entity
     */
    public ResolvedEntity resolveLink(Link link) {
        if (link.isTransaction()) {
            Transaction tx = transactionStore.getTransactionByHash(link.getTargetHash());
            return ResolvedEntity.transaction(tx);
        } else {
            Block block = dagStore.getBlockByHash(link.getTargetHash(), false);
            return ResolvedEntity.block(block);
        }
    }

    /**
     * Resolve all links in a block
     *
     * @param block Block to resolve
     * @return ResolvedLinks containing all resolved entities
     */
    public ResolvedLinks resolveAllLinks(Block block) {
        List<Block> blocks = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();
        List<Bytes32> missing = new ArrayList<>();

        for (Link link : block.getLinks()) {
            ResolvedEntity entity = resolveLink(link);
            if (entity.isFound()) {
                if (entity.isBlock()) {
                    blocks.add(entity.getBlock());
                } else {
                    transactions.add(entity.getTransaction());
                }
            } else {
                missing.add(link.getTargetHash());
            }
        }

        return ResolvedLinks.builder()
                .referencedBlocks(blocks)
                .referencedTransactions(transactions)
                .missingReferences(missing)
                .build();
    }

    /**
     * Check if a link target exists
     *
     * @param link Link to check
     * @return true if target exists
     */
    public boolean linkExists(Link link) {
        if (link.isTransaction()) {
            return transactionStore.hasTransaction(link.getTargetHash());
        } else {
            return dagStore.hasBlock(link.getTargetHash());
        }
    }

    /**
     * Batch check link existence
     *
     * @param links List of links to check
     * @return Map<Link, exists>
     */
    public Map<Link, Boolean> checkLinksExist(List<Link> links) {
        // Separate into block links and transaction links
        List<Bytes32> blockHashes = links.stream()
                .filter(link -> !link.isTransaction())
                .map(Link::getTargetHash)
                .collect(Collectors.toList());

        List<Bytes32> txHashes = links.stream()
                .filter(Link::isTransaction)
                .map(Link::getTargetHash)
                .collect(Collectors.toList());

        // Batch query both stores
        Map<Bytes32, Boolean> blockExists = checkBlocksExist(blockHashes);
        Map<Bytes32, Boolean> txExists = transactionStore.checkTransactionsExist(txHashes);

        // Combine results
        Map<Link, Boolean> result = new HashMap<>();
        for (Link link : links) {
            boolean exists = link.isTransaction() ?
                    txExists.get(link.getTargetHash()) :
                    blockExists.get(link.getTargetHash());
            result.put(link, exists);
        }

        return result;
    }

    private Map<Bytes32, Boolean> checkBlocksExist(List<Bytes32> hashes) {
        // Batch check implementation
        return hashes.stream()
                .collect(Collectors.toMap(
                        hash -> hash,
                        dagStore::hasBlock
                ));
    }
}

/**
 * ResolvedEntity - Wrapper for resolved link target
 */
@Data
public class ResolvedEntity {
    private final Block block;
    private final Transaction transaction;

    public static ResolvedEntity block(Block block) {
        return new ResolvedEntity(block, null);
    }

    public static ResolvedEntity transaction(Transaction tx) {
        return new ResolvedEntity(null, tx);
    }

    public boolean isFound() {
        return block != null || transaction != null;
    }

    public boolean isBlock() {
        return block != null;
    }

    public boolean isTransaction() {
        return transaction != null;
    }
}
```

### 使用示例

```java
// 在 DagChainImpl 中使用

@Component
public class DagChainImpl implements DagChain {

    private final DagStore dagStore;
    private final TransactionStore transactionStore;
    private final DagEntityResolver entityResolver;  // 注入 Facade

    @Override
    public DagImportResult tryToConnect(Block block) {
        // Phase 2: Link validation - 使用 Facade
        ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

        if (!resolved.hasAllReferences()) {
            // 有缺失的引用
            Bytes32 missingHash = resolved.getMissingReferences().get(0);
            return DagImportResult.missingDependency(
                missingHash,
                "Missing link target: " + missingHash.toHexString()
            );
        }

        // 验证所有引用的 Block
        for (Block refBlock : resolved.getReferencedBlocks()) {
            if (refBlock.getTimestamp() >= block.getTimestamp()) {
                return DagImportResult.invalidLink(
                    "Referenced block timestamp >= current block timestamp",
                    refBlock.getHash()
                );
            }
        }

        // 验证所有引用的 Transaction
        for (Transaction tx : resolved.getReferencedTransactions()) {
            if (!tx.isValid()) {
                return DagImportResult.invalidLink(
                    "Invalid transaction structure",
                    tx.getHash()
                );
            }
            if (!tx.verifySignature()) {
                return DagImportResult.invalidLink(
                    "Invalid transaction signature",
                    tx.getHash()
                );
            }
        }

        // 继续其他验证...
    }
}
```

---

## 最终推荐架构

### 存储层（3个独立 Store）

```
┌─────────────────────────────────────────────────────────────┐
│                      Storage Layer                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │  DagStore    │  │ TransactionStore │  │ OrphanStore  │ │
│  │              │  │                  │  │              │ │
│  │ - Block      │  │ - Transaction    │  │ - Orphans    │ │
│  │ - BlockInfo  │  │ - TX indexes     │  │              │ │
│  │ - DAG index  │  │ - Address index  │  │              │ │
│  │ - ChainStats │  │                  │  │              │ │
│  └──────────────┘  └──────────────────┘  └──────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 协调层（Facade）

```
┌─────────────────────────────────────────────────────────────┐
│                    Coordination Layer                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│              ┌──────────────────────────┐                  │
│              │  DagEntityResolver       │                  │
│              │                          │                  │
│              │ - resolveLink()          │                  │
│              │ - resolveAllLinks()      │                  │
│              │ - linkExists()           │                  │
│              │ - checkLinksExist()      │                  │
│              └──────────────────────────┘                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 业务层（DagChain）

```
┌─────────────────────────────────────────────────────────────┐
│                      Business Layer                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│                   ┌──────────────┐                         │
│                   │  DagChain    │                         │
│                   │              │                         │
│                   │ Uses:        │                         │
│                   │ - DagStore   │                         │
│                   │ - TxStore    │                         │
│                   │ - Resolver   │                         │
│                   └──────────────┘                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 实施计划

### Phase 1: DagStore 接口设计（2天）
- [ ] 设计 DagStore 接口
- [ ] 从 BlockStore 迁移相关方法
- [ ] 添加 DAG 特有方法（epoch queries, position mapping）
- [ ] 更新方法命名（getBlockByHeight → getMainBlockAtPosition）

### Phase 2: DagEntityResolver 实现（1天）
- [ ] 创建 DagEntityResolver Facade
- [ ] 实现 resolveLink() 和 resolveAllLinks()
- [ ] 实现批量检查优化

### Phase 3: DagStore 实现（3天）
- [ ] 实现 DagStoreImpl
- [ ] 从 BlockStoreImpl 迁移核心逻辑
- [ ] 添加新的索引（epoch, position mapping）
- [ ] 单元测试

### Phase 4: TransactionStore 增强（1天）
- [ ] 添加 checkTransactionsExist() 批量方法
- [ ] 优化批量查询性能

### Phase 5: DagChainImpl 集成（2天）
- [ ] 更新 DagChainImpl 使用 DagStore
- [ ] 集成 DagEntityResolver
- [ ] 简化 validateLinks() 逻辑
- [ ] 集成测试

### Phase 6: 迁移和清理（2天）
- [ ] 逐步迁移其他使用 BlockStore 的代码
- [ ] 标记 BlockStore 为 @Deprecated
- [ ] 完整的回归测试
- [ ] 性能测试

**总计：11 天**

---

## 总结

### 推荐：方案 B + DagEntityResolver Facade

**理由**：
1. ✅ **关注点分离** - DagStore 专注 Block/DAG，TransactionStore 专注 Transaction
2. ✅ **类型安全** - 不需要 instanceof 检查，编译时类型安全
3. ✅ **渐进式迁移** - 可以分步实施，降低风险
4. ✅ **灵活性** - 两个 store 可以独立优化和扩展
5. ✅ **简洁性** - 通过 Facade 模式提供统一的 Link 解析
6. ✅ **可维护性** - 接口清晰，职责明确

**核心设计原则**：
- **存储层分离** - DagStore 和 TransactionStore 独立
- **协调层统一** - DagEntityResolver 提供统一接口
- **业务层简洁** - DagChainImpl 使用 Resolver 简化逻辑

这个架构既保持了存储层的独立性和灵活性，又通过 Facade 模式提供了统一的使用体验。
