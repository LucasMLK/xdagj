# Blockchain Interface v5.1 重新设计方案

**日期**: 2025-11-05
**审查人**: Claude Code
**范围**: Blockchain 接口适配 XDAG v5.1 epoch 共识机制

---

## 执行摘要

当前 Blockchain 接口设计基于传统线性区块链假设，需要调整以支持 XDAG v5.1 的核心特性：
- ✅ **保留**: 70% 方法设计合理，无需修改
- ⚠️ **调整**: 20% 方法需要重命名或文档澄清
- ❌ **新增**: 10% 核心功能缺失，需要添加

**关键改进**:
1. 添加 epoch 相关查询方法（支持 epoch 共识）
2. 暴露累积难度计算方法（支持链选择）
3. 添加 DAG 遍历和验证方法（支持 DAG 结构）
4. 统一术语（height vs number）

---

## 1. 现有方法分析

### 1.1 需要重命名的方法

| 当前方法 | 问题 | 建议改名 | 理由 |
|---------|------|---------|------|
| `createMainBlock()` | 误导：块创建时不是 main block | `createCandidateBlock()` | XDAG 中块要竞争后才能成为 main block |
| `getLatestMainBlockNumber()` | 术语不一致（number vs height） | `getMainChainLength()` | 与 `getBlockByHeight()` 保持一致 |

### 1.2 需要文档澄清的方法

以下方法设计合理，但需要在 Javadoc 中明确说明 XDAG v5.1 语义：

1. **`getBlockByHeight(long height)`**
   - 当前：可能被误解为"DAG 层级高度"
   - 澄清：这是 main chain 上的序号（第 N 个 main block）
   - 建议文档：
   ```java
   /**
    * Get main block by its position in the main chain
    *
    * <p>In XDAG v5.1, "height" refers to the block's position in the main chain
    * (the sequence of epoch winners), NOT the traditional blockchain height.
    * Only main blocks have heights (height > 0), orphan blocks have height = 0.
    *
    * @param height main chain position (1-based, height=1 is first main block after genesis)
    * @return main block at this position, or null if not found
    */
   Block getBlockByHeight(long height);
   ```

2. **`checkNewMain()`**
   - 当前：实现为空（BlockchainImpl.java:979-988）
   - 需要：重新实现以支持 epoch 共识
   - 建议：在接口文档中明确说明职责

### 1.3 适合保留的方法（无需修改）

以下方法设计合理，符合 v5.1 架构，建议保留：

✅ **核心块操作**:
- `tryToConnect(Block block)` - 导入验证（实现需修复，但接口合理）
- `createGenesisBlock(ECKeyPair key, long timestamp)` - 创世块创建
- `createRewardBlock(...)` - 奖励分配
- `getBlockByHash(Bytes32 hash, boolean isRaw)` - 按哈希查询

✅ **链查询**:
- `listMainBlocks(int count)` - 列出主链块
- `listMinedBlocks(int count)` - 列出挖矿块
- `getMemOurBlocks()` - 内存块管理

✅ **统计与经济**:
- `getChainStats()` - 链统计（已用 ChainStats 替代 XdagStats）
- `getReward(long nmain)` - 块奖励
- `getSupply(long nmain)` - 总供应量

✅ **同步管理**:
- `incrementWaitingSyncCount()` - 增加等待同步计数
- `decrementWaitingSyncCount()` - 减少等待同步计数
- `updateStatsFromRemote(ChainStats remoteStats)` - 更新远程统计

✅ **生命周期**:
- `startCheckMain(long period)` - 启动主链检查线程
- `stopCheckMain()` - 停止主链检查线程
- `registerListener(Listener listener)` - 注册事件监听器

---

## 2. 需要新增的核心方法

基于 XDAG v5.1 的 epoch 共识和 DAG 结构，接口缺少以下关键功能：

### 2.1 Epoch 相关方法（必需）

XDAG 核心共识机制依赖 epoch，必须提供查询和操作方法：

```java
/**
 * Get current epoch number
 *
 * <p>Epoch calculation: {@code epoch = timestamp / 64}
 * Each epoch lasts 64 seconds, and blocks compete within each epoch
 * for the main chain position.
 *
 * @return current epoch number
 */
long getCurrentEpoch();

/**
 * Get all blocks in a specific epoch
 *
 * <p>In XDAG v5.1, all blocks created within the same 64-second epoch
 * compete to become the main block. This method returns all candidate
 * blocks for a given epoch.
 *
 * <p><strong>Winner Selection</strong>: The block with the smallest hash
 * becomes the main block for this epoch.
 *
 * @param epoch epoch number (timestamp / 64)
 * @return list of all blocks in this epoch (may be empty if epoch has no blocks)
 */
List<Block> getBlocksByEpoch(long epoch);

/**
 * Get the winning main block for a specific epoch
 *
 * <p>Returns the block with the smallest hash in the given epoch.
 * This is the "main block" for this epoch in the main chain.
 *
 * @param epoch epoch number (timestamp / 64)
 * @return main block for this epoch, or null if epoch has no main block
 */
Block getMainBlockByEpoch(long epoch);

/**
 * Get time range for a specific epoch
 *
 * <p>Returns [startTime, endTime) for the epoch.
 * - startTime: epoch * 64
 * - endTime: (epoch + 1) * 64
 *
 * @param epoch epoch number
 * @return two-element array: [startTime, endTime) in XDAG timestamp format
 */
long[] getEpochTimeRange(long epoch);
```

**使用场景**:
- 挖矿池需要查询当前 epoch 的所有候选块
- 区块浏览器需要显示 epoch 竞争情况
- 共识验证需要确定 epoch 获胜者

### 2.2 累积难度计算方法（必需）

累积难度是 XDAG 链选择的核心，必须暴露计算方法：

```java
/**
 * Calculate cumulative difficulty for a block
 *
 * <p>XDAG v5.1 uses cumulative difficulty to select the best chain.
 * The chain with maximum cumulative difficulty becomes the main chain.
 *
 * <p><strong>Calculation Algorithm</strong>:
 * <ol>
 *   <li>Find parent block with maximum cumulative difficulty among all block links</li>
 *   <li>Calculate this block's work: {@code blockWork = MAX_UINT256 / hash}</li>
 *   <li>Cumulative difficulty = parent's cumulative difficulty + block work</li>
 * </ol>
 *
 * <p><strong>XDAG Philosophy</strong>: Difficulty is inverse of hash value.
 * Smaller hash = higher work = higher difficulty.
 *
 * @param block block to calculate cumulative difficulty for
 * @return cumulative difficulty (sum of work from genesis to this block)
 * @throws IllegalArgumentException if block has no parent (except genesis)
 */
UInt256 calculateCumulativeDifficulty(Block block);

/**
 * Calculate work for a single block
 *
 * <p>Block work calculation follows XDAG original design:
 * {@code blockWork = MAX_UINT256 / hash}
 *
 * <p>This means:
 * - Smallest possible hash → maximum work (MAX_UINT256)
 * - Largest possible hash → minimum work (≈1)
 * - Block work represents the expected number of hash attempts needed
 *
 * @param hash block hash (32 bytes)
 * @return block work value
 */
UInt256 calculateBlockWork(Bytes32 hash);
```

**使用场景**:
- `tryToConnect()` 需要计算新块的累积难度
- `checkNewMain()` 需要比较不同链的累积难度
- 区块浏览器需要显示累积难度信息

### 2.3 DAG 结构方法（高优先级）

XDAG 是 DAG 结构，需要提供 DAG 遍历和验证方法：

```java
/**
 * Check if a block is in the main chain
 *
 * <p>In XDAG v5.1 DAG structure, a block is on the main chain if:
 * <ul>
 *   <li>It is a main block (BlockInfo.height > 0), OR</li>
 *   <li>It is directly or indirectly referenced by a main block</li>
 * </ul>
 *
 * @param hash block hash to check
 * @return true if block is on main chain (directly or indirectly)
 */
boolean isBlockInMainChain(Bytes32 hash);

/**
 * Get all blocks that reference a specific block
 *
 * <p>This is a reverse lookup for DAG traversal. Returns all blocks
 * that have a Link pointing to the specified block.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Check if orphan block is referenced (can be removed from orphan pool)</li>
 *   <li>Trace transaction validity (is tx referenced by a main block?)</li>
 *   <li>Analyze DAG connectivity</li>
 * </ul>
 *
 * @param hash block hash to find references for
 * @return list of blocks that reference this block (may be empty)
 */
List<Block> getBlockReferences(Bytes32 hash);

/**
 * Validate block against DAG structure rules
 *
 * <p>Validates block according to XDAG DAG rules from DAG_REFERENCE_RULES.md:
 * <ol>
 *   <li><strong>No cycles</strong>: Block cannot create a cycle in DAG</li>
 *   <li><strong>Time window</strong>: Can only reference blocks within 12 days (16384 epochs)</li>
 *   <li><strong>Link limits</strong>: Must have 1-16 block links</li>
 *   <li><strong>Timestamp order</strong>: All referenced blocks must have earlier timestamps</li>
 *   <li><strong>Traversal depth</strong>: Path depth from genesis must not exceed 1000 layers</li>
 * </ol>
 *
 * @param block block to validate
 * @return validation result with detailed error info if invalid
 */
DAGValidationResult validateDAGRules(Block block);
```

**使用场景**:
- `tryToConnect()` 需要验证 DAG 规则
- 孤块管理需要检查引用关系
- Transaction 有效性需要追踪到 main block

### 2.4 辅助查询方法（中优先级）

这些方法不是核心共识必需，但对完整性和易用性重要：

```java
/**
 * Get blocks within a time range
 *
 * <p>Returns all blocks with timestamps in the specified range.
 * Useful for time-based queries and analysis.
 *
 * @param startTime start timestamp (XDAG format, inclusive)
 * @param endTime end timestamp (XDAG format, exclusive)
 * @return list of blocks in time range, sorted by timestamp
 */
List<Block> getBlocksByTimeRange(long startTime, long endTime);

/**
 * Get main chain path from a block to genesis
 *
 * <p>Traces the main chain path backwards from the given block
 * to the genesis block. Each step follows the parent with maximum
 * cumulative difficulty.
 *
 * @param hash starting block hash
 * @return list of blocks from hash to genesis (descending order)
 * @throws IllegalArgumentException if block is not on main chain
 */
List<Block> getMainChainPath(Bytes32 hash);

/**
 * Get statistics for a specific epoch
 *
 * <p>Returns statistics for the given epoch:
 * - Total candidate blocks
 * - Winning block hash
 * - Average block time
 * - Total difficulty for epoch
 *
 * @param epoch epoch number
 * @return epoch statistics
 */
EpochStats getEpochStats(long epoch);
```

---

## 3. 新增数据类型

为支持新增方法，需要定义以下数据类型：

### 3.1 DAGValidationResult

```java
/**
 * DAG validation result containing detailed error information
 */
@Data
@Builder
public class DAGValidationResult {
    /** Validation passed */
    private final boolean valid;

    /** Error code if validation failed */
    private final DAGErrorCode errorCode;

    /** Human-readable error message */
    private final String errorMessage;

    /** Block hash that caused the error (if applicable) */
    private final Bytes32 conflictingBlockHash;

    public enum DAGErrorCode {
        VALID,
        CYCLE_DETECTED,
        TIME_WINDOW_VIOLATION,
        INVALID_LINK_COUNT,
        TIMESTAMP_ORDER_VIOLATION,
        TRAVERSAL_DEPTH_EXCEEDED
    }

    public static DAGValidationResult valid() {
        return DAGValidationResult.builder()
            .valid(true)
            .errorCode(DAGErrorCode.VALID)
            .build();
    }

    public static DAGValidationResult invalid(DAGErrorCode code, String message) {
        return DAGValidationResult.builder()
            .valid(false)
            .errorCode(code)
            .errorMessage(message)
            .build();
    }
}
```

### 3.2 EpochStats

```java
/**
 * Statistics for a specific epoch
 */
@Data
@Builder
public class EpochStats {
    /** Epoch number */
    private final long epoch;

    /** Start time of epoch (timestamp / 64 * 64) */
    private final long startTime;

    /** End time of epoch (startTime + 64) */
    private final long endTime;

    /** Total candidate blocks in this epoch */
    private final int totalBlocks;

    /** Winning block hash (smallest hash in epoch) */
    private final Bytes32 winningBlockHash;

    /** Average block time within epoch (seconds) */
    private final double averageBlockTime;

    /** Total difficulty added in this epoch */
    private final UInt256 totalDifficulty;

    /** Whether this epoch has a main block */
    private final boolean hasMainBlock;
}
```

---

## 4. 完整的重新设计接口

基于以上分析，这是建议的 Blockchain 接口 v5.1：

```java
package io.xdag.core;

/**
 * Blockchain interface for XdagJ v5.1
 *
 * <p>This interface defines all core blockchain operations for XDAG's
 * epoch-based DAG consensus mechanism.
 *
 * <h2>XDAG v5.1 Consensus Architecture</h2>
 * <ul>
 *   <li><strong>Epoch-based selection</strong>: Every 64 seconds (1 epoch),
 *       blocks compete and the smallest hash wins</li>
 *   <li><strong>Cumulative difficulty</strong>: Chains compete via cumulative
 *       difficulty (sum of 1/hash for all blocks)</li>
 *   <li><strong>DAG structure</strong>: Blocks can reference multiple parents,
 *       forming a Directed Acyclic Graph</li>
 *   <li><strong>Block/Transaction/Link</strong>: Only three core data structures</li>
 * </ul>
 *
 * @since v5.1
 */
public interface Blockchain {

    // ==================== Block Operations ====================

    /**
     * Try to connect a new Block to the blockchain
     *
     * <p>This method validates and imports a Block into the blockchain.
     * Block uses Link-based references instead of Address objects.
     *
     * <p><strong>Import Process</strong>:
     * <ol>
     *   <li>Basic validation (timestamp, structure)</li>
     *   <li>Link validation (Transaction and Block references)</li>
     *   <li>DAG rules validation (cycles, time windows, link limits)</li>
     *   <li>Cumulative difficulty calculation</li>
     *   <li>Main chain determination (compare with current max difficulty)</li>
     *   <li>Block storage and event notification</li>
     * </ol>
     *
     * <p>Possible results:
     * <ul>
     *   <li>IMPORTED_BEST - Block extends main chain (new highest difficulty)</li>
     *   <li>IMPORTED_NOT_BEST - Block imported but not on main chain</li>
     *   <li>EXIST - Block already exists</li>
     *   <li>NO_PARENT - Parent block not found</li>
     *   <li>INVALID_BLOCK - Validation failed</li>
     * </ul>
     *
     * @param block Block to connect
     * @return ImportResult indicating the result of the import operation
     */
    ImportResult tryToConnect(Block block);

    /**
     * Create a candidate block for mining
     *
     * <p>This method creates a new candidate block ready for Proof-of-Work mining.
     * The block includes:
     * <ul>
     *   <li>Current timestamp</li>
     *   <li>Current network difficulty</li>
     *   <li>Nonce = 0 (to be found by mining)</li>
     *   <li>Coinbase address (miner's address)</li>
     *   <li>Links to previous main block and orphan blocks</li>
     * </ul>
     *
     * <p><strong>XDAG v5.1 Note</strong>: The term "main block" is misleading.
     * This method creates a <em>candidate block</em> that will compete with other
     * blocks in the same epoch. Only after the epoch ends (64 seconds) and this
     * block has the smallest hash will it become a "main block".
     *
     * <p>After mining finds a valid nonce, use {@link Block#withNonce(Bytes32)}
     * to create the final block for import.
     *
     * @return candidate block for mining (nonce = 0, needs POW)
     * @see Block#createCandidate(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, java.util.List)
     * @see Block#withNonce(Bytes32)
     */
    Block createCandidateBlock();  // RENAMED from createMainBlock()

    /**
     * Create the genesis block for blockchain initialization
     *
     * <p>The genesis block is the first block in the blockchain, created during
     * fresh node startup. It has special characteristics:
     * <ul>
     *   <li>Empty links list (no previous blocks)</li>
     *   <li>Minimal difficulty (difficulty = 1)</li>
     *   <li>Zero nonce (no mining required)</li>
     *   <li>Coinbase set to provided key</li>
     *   <li>Specified timestamp (usually config genesis time)</li>
     * </ul>
     *
     * @param key ECKeyPair for coinbase address
     * @param timestamp genesis block timestamp (XDAG timestamp format)
     * @return genesis block
     */
    Block createGenesisBlock(ECKeyPair key, long timestamp);

    /**
     * Create a reward block for pool distribution
     *
     * <p>Creates a Block containing multiple Transaction references for distributing
     * mining rewards to pool participants.
     *
     * @param sourceBlockHash hash of source block (where funds come from)
     * @param recipients list of recipient addresses
     * @param amounts list of amounts for each recipient
     * @param sourceKey ECKeyPair for signing transactions
     * @param nonce account nonce for first transaction
     * @param totalFee total transaction fee
     * @return Block containing reward transactions
     */
    Block createRewardBlock(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee);

    // ==================== Block Queries ====================

    /**
     * Get Block by its hash
     *
     * @param hash block hash (32 bytes)
     * @param isRaw whether to include raw block data
     * @return Block instance, or null if not found
     */
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get main block by its position in the main chain
     *
     * <p>In XDAG v5.1, "height" refers to the block's position in the main chain
     * (the sequence of epoch winners), NOT the traditional blockchain height.
     * Only main blocks have heights (height > 0), orphan blocks have height = 0.
     *
     * @param height main chain position (1-based)
     * @return main block at this position, or null if not found
     */
    Block getBlockByHeight(long height);

    /**
     * Get list of recent main blocks
     *
     * <p>Returns the most recent main blocks in descending order (newest first).
     *
     * @param count maximum number of main blocks to retrieve
     * @return list of Block instances (may be empty)
     */
    List<Block> listMainBlocks(int count);

    /**
     * Get list of blocks mined by this node
     *
     * @param count maximum number of mined blocks to retrieve
     * @return list of Block instances (may be empty)
     */
    List<Block> listMinedBlocks(int count);

    /**
     * Get memory blocks created by current node
     *
     * @return map of block hash to creation count
     */
    Map<Bytes, Integer> getMemOurBlocks();

    // ==================== NEW: Epoch-Related Queries ====================

    /**
     * Get current epoch number
     *
     * <p>Epoch calculation: {@code epoch = timestamp / 64}
     *
     * @return current epoch number
     */
    long getCurrentEpoch();

    /**
     * Get all blocks in a specific epoch
     *
     * <p>Returns all candidate blocks for a given epoch.
     * The block with the smallest hash becomes the main block.
     *
     * @param epoch epoch number (timestamp / 64)
     * @return list of all blocks in this epoch (may be empty)
     */
    List<Block> getBlocksByEpoch(long epoch);

    /**
     * Get the winning main block for a specific epoch
     *
     * <p>Returns the block with the smallest hash in the given epoch.
     *
     * @param epoch epoch number (timestamp / 64)
     * @return main block for this epoch, or null if not found
     */
    Block getMainBlockByEpoch(long epoch);

    /**
     * Get time range for a specific epoch
     *
     * <p>Returns [startTime, endTime) for the epoch.
     *
     * @param epoch epoch number
     * @return two-element array: [startTime, endTime)
     */
    long[] getEpochTimeRange(long epoch);

    /**
     * Get statistics for a specific epoch
     *
     * @param epoch epoch number
     * @return epoch statistics
     */
    EpochStats getEpochStats(long epoch);

    // ==================== NEW: Cumulative Difficulty ====================

    /**
     * Calculate cumulative difficulty for a block
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Find parent block with max cumulative difficulty</li>
     *   <li>Calculate block work: {@code MAX_UINT256 / hash}</li>
     *   <li>Sum: parent difficulty + block work</li>
     * </ol>
     *
     * @param block block to calculate cumulative difficulty for
     * @return cumulative difficulty
     */
    UInt256 calculateCumulativeDifficulty(Block block);

    /**
     * Calculate work for a single block
     *
     * <p>XDAG formula: {@code blockWork = MAX_UINT256 / hash}
     *
     * @param hash block hash
     * @return block work value
     */
    UInt256 calculateBlockWork(Bytes32 hash);

    // ==================== NEW: DAG Structure ====================

    /**
     * Check if a block is in the main chain
     *
     * <p>A block is on main chain if:
     * <ul>
     *   <li>It is a main block (height > 0), OR</li>
     *   <li>It is referenced by a main block</li>
     * </ul>
     *
     * @param hash block hash to check
     * @return true if on main chain
     */
    boolean isBlockInMainChain(Bytes32 hash);

    /**
     * Get all blocks that reference a specific block
     *
     * <p>Reverse lookup for DAG traversal.
     *
     * @param hash block hash to find references for
     * @return list of blocks that reference this block
     */
    List<Block> getBlockReferences(Bytes32 hash);

    /**
     * Validate block against DAG structure rules
     *
     * <p>Validates:
     * <ol>
     *   <li>No cycles</li>
     *   <li>Time window (12 days / 16384 epochs)</li>
     *   <li>Link limits (1-16 block links)</li>
     *   <li>Timestamp order</li>
     *   <li>Traversal depth (max 1000 layers)</li>
     * </ol>
     *
     * @param block block to validate
     * @return validation result
     */
    DAGValidationResult validateDAGRules(Block block);

    /**
     * Get blocks within a time range
     *
     * @param startTime start timestamp (inclusive)
     * @param endTime end timestamp (exclusive)
     * @return list of blocks in time range
     */
    List<Block> getBlocksByTimeRange(long startTime, long endTime);

    /**
     * Get main chain path from a block to genesis
     *
     * @param hash starting block hash
     * @return list of blocks from hash to genesis
     * @throws IllegalArgumentException if block not on main chain
     */
    List<Block> getMainChainPath(Bytes32 hash);

    // ==================== Chain Management ====================

    /**
     * Check and update the main chain
     *
     * <p>In XDAG v5.1, this method:
     * <ol>
     *   <li>Scans recent epochs for winners (smallest hash)</li>
     *   <li>Calculates cumulative difficulty for competing chains</li>
     *   <li>Reorganizes to chain with maximum cumulative difficulty</li>
     * </ol>
     */
    void checkNewMain();

    /**
     * Get the length of the main chain
     *
     * <p>Returns the number of main blocks in the main chain.
     * Equivalent to the height of the latest main block.
     *
     * @return main chain length (number of main blocks)
     */
    long getMainChainLength();  // RENAMED from getLatestMainBlockNumber()

    // ==================== Statistics ====================

    /**
     * Get current blockchain statistics
     *
     * @return ChainStats containing current blockchain statistics
     */
    ChainStats getChainStats();

    /**
     * Increment waiting sync count
     */
    void incrementWaitingSyncCount();

    /**
     * Decrement waiting sync count
     */
    void decrementWaitingSyncCount();

    /**
     * Update blockchain statistics from remote peer
     *
     * @param remoteStats statistics received from remote peer
     */
    void updateStatsFromRemote(ChainStats remoteStats);

    /**
     * Calculate block mining reward
     *
     * @param nmain main block number (height)
     * @return reward amount
     */
    XAmount getReward(long nmain);

    /**
     * Calculate total XDAG supply
     *
     * @param nmain main block number (height)
     * @return total supply
     */
    XAmount getSupply(long nmain);

    // ==================== Lifecycle ====================

    /**
     * Start main chain check thread
     *
     * @param period check period in milliseconds
     */
    void startCheckMain(long period);

    /**
     * Stop main chain check thread
     */
    void stopCheckMain();

    /**
     * Register blockchain event listener
     *
     * @param listener event listener to register
     */
    void registerListener(Listener listener);

    /**
     * Get pre-seed for snapshot initialization
     *
     * @return pre-seed bytes, or null if not available
     */
    byte[] getPreSeed();
}
```

---

## 5. 实现优先级

### Phase 1: 关键修复（立即）
1. ✅ 重命名方法：
   - `createMainBlock()` → `createCandidateBlock()`
   - `getLatestMainBlockNumber()` → `getMainChainLength()`

2. ✅ 实现核心缺失方法：
   - `getCurrentEpoch()`
   - `calculateCumulativeDifficulty()`
   - `calculateBlockWork()`

3. ✅ 修复 `tryToConnect()` 实现（BlockchainImpl.java:269-420）
   - 实现累积难度计算
   - 实现主链判断逻辑
   - 正确设置 `BlockInfo.height` 和 `BlockInfo.difficulty`

4. ✅ 实现 `checkNewMain()` stub（BlockchainImpl.java:979-988）
   - Epoch 获胜者选择
   - 累积难度比较
   - 链重组

### Phase 2: Epoch 支持（高优先级）
5. `getBlocksByEpoch()`
6. `getMainBlockByEpoch()`
7. `getEpochTimeRange()`
8. `getEpochStats()`

### Phase 3: DAG 验证（高优先级）
9. `validateDAGRules()`
10. `isBlockInMainChain()`
11. `getBlockReferences()`

### Phase 4: 辅助查询（中优先级）
12. `getBlocksByTimeRange()`
13. `getMainChainPath()`

---

## 6. 向后兼容性

### 6.1 废弃的方法

建议使用 `@Deprecated` 标注旧方法，并在 Javadoc 中指向新方法：

```java
/**
 * @deprecated Use {@link #createCandidateBlock()} instead.
 * The term "main block" is misleading in XDAG's epoch-based consensus.
 */
@Deprecated
default Block createMainBlock() {
    return createCandidateBlock();
}

/**
 * @deprecated Use {@link #getMainChainLength()} instead.
 * Renamed for consistency with {@link #getBlockByHeight(long)}.
 */
@Deprecated
default long getLatestMainBlockNumber() {
    return getMainChainLength();
}
```

### 6.2 新增方法的默认实现

对于新增方法，可以在接口中提供默认实现以避免破坏现有代码：

```java
default long getCurrentEpoch() {
    return XdagTime.getCurrentTimestamp() / 64;
}

default long[] getEpochTimeRange(long epoch) {
    return new long[] { epoch * 64, (epoch + 1) * 64 };
}
```

---

## 7. 测试计划

### 7.1 单元测试
- `calculateCumulativeDifficulty()` 计算正确性
- `calculateBlockWork()` 边界条件（hash=0, hash=MAX）
- `validateDAGRules()` 各种违规场景
- Epoch 查询方法的边界条件

### 7.2 集成测试
- `tryToConnect()` 正确选择主链
- `checkNewMain()` 链重组逻辑
- Epoch 竞争场景（多个块在同一 epoch）
- DAG 引用完整性

### 7.3 性能测试
- `getBlocksByEpoch()` 大量块时的性能
- `calculateCumulativeDifficulty()` 深度 DAG 的性能
- `validateDAGRules()` 循环检测算法性能

---

## 8. 文档更新

需要更新以下文档：
1. ✅ Blockchain 接口 Javadoc（本文档）
2. 📝 CORE_DATA_STRUCTURES.md（补充累积难度计算细节）
3. 📝 开发者指南（如何使用新 API）
4. 📝 迁移指南（v5.0 → v5.1）

---

## 9. 总结

### 变更概览

| 类型 | 数量 | 说明 |
|-----|------|------|
| 保留方法 | 18 | 设计合理，无需修改 |
| 重命名方法 | 2 | 统一术语，提高准确性 |
| 新增方法 | 13 | 支持 epoch 共识和 DAG 结构 |
| 新增类型 | 2 | `DAGValidationResult`, `EpochStats` |

### 关键改进

1. ✅ **Epoch 支持**: 新增 6 个 epoch 相关方法，完整支持 epoch 共识
2. ✅ **累积难度**: 暴露计算方法，支持链选择逻辑
3. ✅ **DAG 验证**: 新增 DAG 结构验证和遍历方法
4. ✅ **术语统一**: 重命名方法，消除歧义

### 向后兼容

- 使用 `@Deprecated` 保留旧方法
- 接口默认实现避免破坏现有代码
- 渐进式迁移路径

### 下一步行动

1. **审查并批准**本设计方案
2. **实现 Phase 1** 关键修复（tryToConnect, checkNewMain）
3. **实现 Phase 2** Epoch 支持
4. **实现 Phase 3** DAG 验证
5. **编写测试**和文档

---

**审查完成**: 2025-11-05
**建议**: 立即开始 Phase 1 实现，修复 tryToConnect() 和 checkNewMain()
