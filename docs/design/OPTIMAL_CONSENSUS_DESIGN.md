# XDAG Java版本最优共识方案设计

## 📅 设计日期
2025-11-13

## 🎯 设计目标

1. **解决orphan block爆炸问题**：10,000节点场景从99.99%降到合理水平
2. **确保网络安全性**：防止垃圾blocks和Sybil攻击
3. **保证活性**：每个epoch至少有一个主块
4. **适应性**：自动适应网络算力变化

---

## 📋 当前问题总结

### 问题1：Difficulty Target设置错误

**当前代码**（`DagChainImpl.java:533`）：
```java
UInt256 difficulty = chainStats.getDifficulty();  // ✗ 这是累计难度！
```

**问题**：
- `chainStats.getDifficulty()` 是**累计难度**（所有主块难度的累加）
- 可能是几百万甚至几十亿（随着主块增加持续增长）
- 几乎不可能找到满足 `hash <= 累计难度` 的nonce
- 结果：几乎所有blocks都会被拒绝

**例子**：
```
当前主链：1000个主块
累计难度：假设每个block difficulty = 1000 → 累计 = 1,000,000

新块挖矿：
- 需要找到 hash <= 1,000,000 的nonce
- 概率 ≈ 1,000,000 / 2^256 ≈ 10^-71
- 基本不可能
```

### 问题2：Orphan Block爆炸

**C代码遗留问题**：
- 接受所有blocks，无论difficulty
- N个节点 → N-1个orphan blocks/epoch
- 10,000节点 → 2.5TB存储/年

---

## 💡 最优方案：混合Difficulty + Epoch上限

### 方案架构

```
┌─────────────────────────────────────────────────────────────┐
│                  Block Acceptance Pipeline                   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
         ┌──────────────────────────────────┐
         │  1. Basic Validation              │
         │  - Timestamp, signature, etc.     │
         └──────────────────────────────────┘
                            │
                            ▼
         ┌──────────────────────────────────┐
         │  2. Minimum PoW Check             │
         │  hash <= BASE_DIFFICULTY_TARGET   │
         │  (防止垃圾blocks)                  │
         └──────────────────────────────────┘
                            │
                  ✓ Pass    │    ✗ Reject
                            ▼
         ┌──────────────────────────────────┐
         │  3. Epoch Limit Check             │
         │  Count < MAX_BLOCKS_PER_EPOCH?    │
         │  (控制orphan数量)                  │
         └──────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
          Yes │                       No  │
              ▼                           ▼
         ┌────────┐          ┌─────────────────────────┐
         │ Accept │          │ Compare with worst      │
         └────────┘          │ If better → Replace     │
                             │ If worse  → Reject      │
                             └─────────────────────────┘
```

### 核心参数

```java
/**
 * 基础难度目标（初始值）
 *
 * 含义：hash必须小于这个值才能被接受
 * 设计：要求hash前面至少有8个字节为0（即hash < 2^(256-64) = 2^192）
 *
 * 计算：平均需要 2^64 次hash才能找到一个合格的nonce
 *       = 18,446,744,073,709,551,616 次hash
 *
 * 假设单个矿工算力：
 * - 1 GH/s (10^9 hashes/sec) → 约 18,447 秒 ≈ 5小时
 * - 10 GH/s → 约 30分钟
 * - 100 GH/s → 约 3分钟
 *
 * 网络效果（1000个矿工，平均10 GH/s）：
 * - 总算力：10,000 GH/s
 * - 平均每64秒（1 epoch）能找到：10,000 * 64 / 1,847 ≈ 346 个合格blocks
 * - 我们只保留前100个 → orphan率 = 246/346 = 71%（比99.9%好很多！）
 */
private static final UInt256 INITIAL_BASE_DIFFICULTY_TARGET =
    UInt256.valueOf(BigInteger.valueOf(2).pow(192));

/**
 * 每个epoch接受的blocks数量上限
 *
 * 设计考虑：
 * - 太少（例如10）：竞争不充分，小矿工机会少
 * - 太多（例如1000）：orphan blocks仍然很多
 * - 平衡点：100个
 *
 * 效果：
 * - 10,000节点场景：从9,999个orphan降到约246个
 * - 存储：从2.5TB/年降到约80GB/年（减少97%）
 * - 前100名矿工有机会：算力前1%的矿工能参与竞争
 */
private static final int MAX_BLOCKS_PER_EPOCH = 100;

/**
 * 难度调整周期
 *
 * 每隔多少个epochs调整一次difficulty target
 *
 * 设计：
 * - 太频繁（例如每10个epochs）：波动大，不稳定
 * - 太稀疏（例如每10000个epochs）：无法及时适应算力变化
 * - Bitcoin：每2016个blocks（约2周）
 * - XDAG：每1000个epochs（约17.7小时）合适
 */
private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 1000;

/**
 * 目标blocks数量/epoch
 *
 * 难度调整的目标：每个epoch平均有多少个合格的blocks
 *
 * 设计：
 * - 等于MAX_BLOCKS_PER_EPOCH：所有合格blocks都被接受，没有淘汰
 * - 小于MAX_BLOCKS_PER_EPOCH：不够竞争
 * - 大于MAX_BLOCKS_PER_EPOCH：有竞争和淘汰
 * - 推荐：150（50%的合格blocks成为orphan，保持一定竞争压力）
 */
private static final int TARGET_BLOCKS_PER_EPOCH = 150;

/**
 * 难度调整幅度限制
 *
 * 防止difficulty target剧烈波动
 * 每次调整最多只能改变±50%
 */
private static final double MAX_ADJUSTMENT_FACTOR = 2.0;  // 最多提高2倍
private static final double MIN_ADJUSTMENT_FACTOR = 0.5;  // 最多降低50%
```

---

## 📐 详细设计

### 1. Difficulty Target管理

#### 1.1 数据结构

**添加到ChainStats**（`ChainStats.java`）：
```java
@Value
@Builder(toBuilder = true)
public class ChainStats {
    // ... 现有字段 ...

    /**
     * 当前基础难度目标（Base Difficulty Target）
     *
     * 所有blocks必须满足：hash <= baseDifficultyTarget
     * 定期根据网络状态调整
     */
    UInt256 baseDifficultyTarget;

    /**
     * 上次难度调整的epoch
     * 用于判断是否需要调整difficulty
     */
    long lastDifficultyAdjustmentEpoch;
}
```

#### 1.2 初始化

**在DagChainImpl构造函数中**：
```java
public DagChainImpl(DagKernel dagKernel) {
    // ... 现有代码 ...

    this.chainStats = dagStore.getChainStats();
    if (this.chainStats == null) {
        this.chainStats = ChainStats.builder()
                .mainBlockCount(0)
                .maxDifficulty(UInt256.ZERO)
                .difficulty(UInt256.ZERO)
                .baseDifficultyTarget(INITIAL_BASE_DIFFICULTY_TARGET)  // ← 新增
                .lastDifficultyAdjustmentEpoch(0)  // ← 新增
                .build();
        dagStore.saveChainStats(this.chainStats);
    }

    // 兼容旧数据：如果baseDifficultyTarget为null，初始化它
    if (this.chainStats.getBaseDifficultyTarget() == null) {
        this.chainStats = this.chainStats.toBuilder()
                .baseDifficultyTarget(INITIAL_BASE_DIFFICULTY_TARGET)
                .lastDifficultyAdjustmentEpoch(getCurrentEpoch())
                .build();
        dagStore.saveChainStats(this.chainStats);
    }
}
```

### 2. Block接受逻辑

#### 2.1 修改tryToConnect方法

**位置**：`DagChainImpl.java:107-280`

在`validateBasicRules()`之后，添加新的验证：

```java
@Override
public synchronized DagImportResult tryToConnect(Block block) {
    try {
        log.debug("Attempting to connect block: {}", block.getHash().toHexString());

        // 1. Basic validation
        DagImportResult basicValidation = validateBasicRules(block);
        if (basicValidation != null) {
            return basicValidation;
        }

        // ✓ 2. NEW: Minimum PoW validation
        DagImportResult powValidation = validateMinimumPoW(block);
        if (powValidation != null) {
            return powValidation;
        }

        // ✓ 3. NEW: Epoch limit validation
        DagImportResult epochLimitValidation = validateEpochLimit(block);
        if (epochLimitValidation != null) {
            return epochLimitValidation;
        }

        // 4. Link validation (Transaction and Block references)
        DagImportResult linkValidation = validateLinks(block);
        if (linkValidation != null) {
            return linkValidation;
        }

        // ... 继续原有逻辑 ...
    } catch (Exception e) {
        log.error("Error importing block {}: {}", block.getHash().toHexString(), e.getMessage(), e);
        return DagImportResult.error(e, "Exception during import: " + e.getMessage());
    }
}
```

#### 2.2 实现validateMinimumPoW方法

```java
/**
 * Validate minimum PoW requirement
 *
 * <p>Ensures block hash satisfies the base difficulty target.
 * This prevents spam blocks and ensures basic work was done.
 *
 * @param block block to validate
 * @return null if valid, error result if invalid
 */
private DagImportResult validateMinimumPoW(Block block) {
    UInt256 blockHash = UInt256.fromBytes(block.getHash());
    UInt256 baseDifficultyTarget = chainStats.getBaseDifficultyTarget();

    // Check: hash <= baseDifficultyTarget
    if (blockHash.compareTo(baseDifficultyTarget) > 0) {
        log.debug("Block {} rejected: insufficient PoW (hash {} > target {})",
                block.getHash().toHexString().substring(0, 16),
                blockHash.toHexString().substring(0, 16),
                baseDifficultyTarget.toHexString().substring(0, 16));

        return DagImportResult.invalidBasic(String.format(
                "Insufficient proof of work: hash exceeds difficulty target (hash=%s, target=%s)",
                blockHash.toHexString().substring(0, 16) + "...",
                baseDifficultyTarget.toHexString().substring(0, 16) + "..."
        ));
    }

    log.debug("Block {} passed minimum PoW check (hash {} <= target {})",
            block.getHash().toHexString().substring(0, 16),
            blockHash.toHexString().substring(0, 16),
            baseDifficultyTarget.toHexString().substring(0, 16));

    return null;  // Validation passed
}
```

#### 2.3 实现validateEpochLimit方法

```java
/**
 * Validate epoch block limit
 *
 * <p>Limits the number of blocks accepted per epoch to control orphan block growth.
 * If epoch already has MAX_BLOCKS_PER_EPOCH blocks, only accept new blocks if they
 * have better difficulty than the worst existing block.
 *
 * @param block block to validate
 * @return null if valid, error result if should be rejected
 */
private DagImportResult validateEpochLimit(Block block) {
    long epoch = block.getEpoch();
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);

    // If under limit, accept
    if (candidates.size() < MAX_BLOCKS_PER_EPOCH) {
        log.debug("Block {} accepted: epoch {} has {} < {} blocks",
                block.getHash().toHexString().substring(0, 16),
                epoch, candidates.size(), MAX_BLOCKS_PER_EPOCH);
        return null;  // Accept
    }

    // Epoch is full, check if this block is better than the worst one
    UInt256 thisBlockDifficulty = calculateBlockWork(block.getHash());

    // Find the worst block (smallest difficulty)
    Block worstBlock = null;
    UInt256 worstDifficulty = UInt256.MAX_VALUE;

    for (Block candidate : candidates) {
        UInt256 candidateDifficulty = calculateBlockWork(candidate.getHash());
        if (candidateDifficulty.compareTo(worstDifficulty) < 0) {
            worstDifficulty = candidateDifficulty;
            worstBlock = candidate;
        }
    }

    // Compare with worst block
    if (thisBlockDifficulty.compareTo(worstDifficulty) > 0) {
        // This block is better, will replace the worst one
        log.info("Block {} will replace worse block {} in epoch {} (difficulty {} > {})",
                block.getHash().toHexString().substring(0, 16),
                worstBlock.getHash().toHexString().substring(0, 16),
                epoch,
                thisBlockDifficulty.toHexString().substring(0, 16),
                worstDifficulty.toHexString().substring(0, 16));

        // Mark worst block for removal
        demoteBlockToRejected(worstBlock);

        return null;  // Accept this block
    } else {
        // This block is not better than worst, reject
        log.debug("Block {} rejected: epoch {} full and difficulty {} <= worst {}",
                block.getHash().toHexString().substring(0, 16),
                epoch,
                thisBlockDifficulty.toHexString().substring(0, 16),
                worstDifficulty.toHexString().substring(0, 16));

        return DagImportResult.invalidBasic(String.format(
                "Epoch %d full (%d blocks) and this block's difficulty not in top %d",
                epoch, MAX_BLOCKS_PER_EPOCH, MAX_BLOCKS_PER_EPOCH
        ));
    }
}
```

#### 2.4 实现demoteBlockToRejected方法

```java
/**
 * Remove a block that has been replaced by a better one
 *
 * <p>This is different from demoteBlockToOrphan - the block is completely
 * removed from storage to save space.
 *
 * @param block block to remove
 */
private synchronized void demoteBlockToRejected(Block block) {
    if (block == null || block.getInfo() == null) {
        log.warn("Attempted to remove null block or block without info");
        return;
    }

    log.info("Removing block {} (replaced by better block in epoch limit)",
            block.getHash().toHexString());

    // Remove from DagStore
    dagStore.deleteBlock(block.getHash());
    dagStore.deleteBlockInfo(block.getHash());

    // Remove from orphan store if present
    orphanBlockStore.deleteByHash(block.getHash().toArray());
}
```

### 3. Difficulty调整算法

#### 3.1 触发时机

**在tryToConnect成功后，检查是否需要调整难度**：

```java
@Override
public synchronized DagImportResult tryToConnect(Block block) {
    try {
        // ... 验证和导入block ...

        // Save block
        dagStore.saveBlockInfo(blockInfo);
        Block blockWithInfo = block.toBuilder().info(blockInfo).build();
        dagStore.saveBlock(blockWithInfo);

        // Update chain statistics
        if (isBestChain) {
            updateChainStatsForNewMainBlock(blockInfo);

            // ✓ NEW: Check if difficulty adjustment is needed
            checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());
        }

        // ... 其余逻辑 ...
    }
}
```

#### 3.2 实现checkAndAdjustDifficulty方法

```java
/**
 * Check if difficulty adjustment is needed and perform adjustment
 *
 * <p>Adjusts base difficulty target every DIFFICULTY_ADJUSTMENT_INTERVAL epochs
 * based on the average number of blocks per epoch.
 *
 * <p>Goal: Maintain TARGET_BLOCKS_PER_EPOCH blocks per epoch on average.
 *
 * @param currentHeight current main block height
 * @param currentEpoch current epoch
 */
private synchronized void checkAndAdjustDifficulty(long currentHeight, long currentEpoch) {
    long lastAdjustmentEpoch = chainStats.getLastDifficultyAdjustmentEpoch();

    // Check if adjustment interval reached
    if (currentEpoch - lastAdjustmentEpoch < DIFFICULTY_ADJUSTMENT_INTERVAL) {
        return;  // Not time yet
    }

    log.info("Difficulty adjustment triggered at epoch {} (last adjustment: epoch {})",
            currentEpoch, lastAdjustmentEpoch);

    // Calculate average blocks per epoch in the adjustment period
    int totalBlocks = 0;
    int epochCount = 0;

    for (long epoch = lastAdjustmentEpoch; epoch < currentEpoch; epoch++) {
        List<Block> blocks = getCandidateBlocksInEpoch(epoch);
        totalBlocks += blocks.size();
        epochCount++;
    }

    double avgBlocksPerEpoch = epochCount > 0 ? (double) totalBlocks / epochCount : 0;

    log.info("Average blocks per epoch in last {} epochs: {:.2f} (target: {})",
            epochCount, avgBlocksPerEpoch, TARGET_BLOCKS_PER_EPOCH);

    // Calculate adjustment factor
    double adjustmentFactor = 1.0;

    if (avgBlocksPerEpoch > TARGET_BLOCKS_PER_EPOCH * 1.5) {
        // Too many blocks → increase difficulty (lower target)
        adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
        log.info("Too many blocks, increasing difficulty (lowering target) by factor {:.2f}",
                adjustmentFactor);
    } else if (avgBlocksPerEpoch < TARGET_BLOCKS_PER_EPOCH * 0.5) {
        // Too few blocks → decrease difficulty (raise target)
        adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
        log.info("Too few blocks, decreasing difficulty (raising target) by factor {:.2f}",
                adjustmentFactor);
    } else {
        log.info("Block count in acceptable range, no adjustment needed");
        // Update last adjustment epoch even if no change
        chainStats = chainStats.toBuilder()
                .lastDifficultyAdjustmentEpoch(currentEpoch)
                .build();
        dagStore.saveChainStats(chainStats);
        return;
    }

    // Limit adjustment factor
    adjustmentFactor = Math.max(MIN_ADJUSTMENT_FACTOR,
                                Math.min(MAX_ADJUSTMENT_FACTOR, adjustmentFactor));

    log.info("Limited adjustment factor: {:.2f} (range: {:.2f} - {:.2f})",
            adjustmentFactor, MIN_ADJUSTMENT_FACTOR, MAX_ADJUSTMENT_FACTOR);

    // Calculate new target
    UInt256 currentTarget = chainStats.getBaseDifficultyTarget();
    BigInteger newTargetBigInt = currentTarget.toBigInteger()
            .multiply(BigInteger.valueOf((long)(adjustmentFactor * 1000)))
            .divide(BigInteger.valueOf(1000));

    UInt256 newTarget = UInt256.valueOf(newTargetBigInt);

    log.info("Difficulty adjusted: old target={}, new target={}, factor={:.2f}",
            currentTarget.toHexString().substring(0, 16) + "...",
            newTarget.toHexString().substring(0, 16) + "...",
            adjustmentFactor);

    // Update chain stats
    chainStats = chainStats.toBuilder()
            .baseDifficultyTarget(newTarget)
            .lastDifficultyAdjustmentEpoch(currentEpoch)
            .build();
    dagStore.saveChainStats(chainStats);
}
```

### 4. 创建候选块修改

**修改createCandidateBlock方法**（`DagChainImpl.java:527-558`）：

```java
@Override
public Block createCandidateBlock() {
    log.info("Creating candidate block for mining");

    long timestamp = XdagTime.getCurrentTimestamp();
    long epoch = timestamp / 64;

    // ✓ 使用baseDifficultyTarget而不是累计difficulty
    UInt256 difficulty = chainStats.getBaseDifficultyTarget();

    if (difficulty == null || difficulty.isZero()) {
        log.warn("Base difficulty target not set, using initial value");
        difficulty = INITIAL_BASE_DIFFICULTY_TARGET;
    }

    log.info("Creating block with base difficulty target: {}",
            difficulty.toHexString().substring(0, 16) + "...");

    Bytes coinbase = miningCoinbase;
    List<Link> links = collectCandidateLinks();

    Block candidateBlock = Block.createCandidate(timestamp, difficulty, coinbase, links);

    log.info("Created mining candidate block: epoch={}, target={}, links={}, hash={}",
            epoch,
            difficulty.toHexString().substring(0, 16) + "...",
            links.size(),
            candidateBlock.getHash().toHexString().substring(0, 16) + "...");

    return candidateBlock;
}
```

---

## 📊 效果评估

### 场景分析：10,000个节点网络

#### 参数设置
- `INITIAL_BASE_DIFFICULTY_TARGET = 2^192`
- `MAX_BLOCKS_PER_EPOCH = 100`
- `TARGET_BLOCKS_PER_EPOCH = 150`
- 平均节点算力：10 GH/s
- 总网络算力：100,000 GH/s

#### 计算

**1. 每个epoch合格blocks数量**

```
平均hash时间 = 2^64 / (100,000 * 10^9) ≈ 184 秒

每个epoch（64秒）期望合格blocks:
= (总算力 * epoch时间) / (平均hash时间)
= (100,000 * 10^9 * 64) / (2^64)
≈ 346 个blocks
```

**2. Orphan blocks**

```
接受的blocks = min(346, 100) = 100
Orphan blocks = 346 - 100 = 246
Orphan率 = 246 / 346 = 71%
```

**3. 存储消耗**

```
每天：
- Total blocks produced: 346 * 1,350 epochs = 467,100 blocks
- Blocks accepted: 100 * 1,350 = 135,000 blocks
- Storage: 135,000 * 512 bytes = 69 MB/day

每年：
- 69 MB * 365 = 25 GB/year
```

**4. 对比C代码**

| 指标 | C代码 | Java新方案 | 改进 |
|------|-------|-----------|------|
| 合格blocks/epoch | 10,000 | 346 | -97% |
| 接受blocks/epoch | 10,000 | 100 | -99% |
| Orphan率 | 99.99% | 71% | -29% |
| 存储/年 | 2.5 TB | 25 GB | -99% |

### 不同算力场景

| 网络总算力 | 合格blocks/epoch | 接受blocks/epoch | Orphan率 | 存储/年 |
|-----------|------------------|------------------|----------|---------|
| 1,000 GH/s | 3.5 | 3.5 | 0% | 0.9 GB |
| 10,000 GH/s | 35 | 35 | 0% | 9 GB |
| 100,000 GH/s | 346 | 100 | 71% | 25 GB |
| 1,000,000 GH/s | 3,460 | 100 | 97% | 25 GB |

**观察**：
- ✅ 低算力时（< 100,000 GH/s）：几乎无orphan
- ✅ 高算力时：orphan率稳定在97%左右，存储固定为25 GB/年
- ✅ 完美的扩展性：无论网络多大，存储和orphan都可控

---

## 🔧 实施步骤

### Phase 1：数据结构准备
1. ✅ 给`ChainStats`添加`baseDifficultyTarget`和`lastDifficultyAdjustmentEpoch`字段
2. ✅ 修改`DagChainImpl`构造函数，初始化新字段
3. ✅ 添加兼容性代码，处理旧数据升级

### Phase 2：Block验证逻辑
1. ✅ 实现`validateMinimumPoW()`方法
2. ✅ 实现`validateEpochLimit()`方法
3. ✅ 实现`demoteBlockToRejected()`方法
4. ✅ 修改`tryToConnect()`，集成新验证

### Phase 3：Difficulty调整
1. ✅ 实现`checkAndAdjustDifficulty()`方法
2. ✅ 在`tryToConnect()`成功后调用
3. ✅ 添加日志记录difficulty调整过程

### Phase 4：候选块创建
1. ✅ 修改`createCandidateBlock()`，使用`baseDifficultyTarget`
2. ✅ 移除DEVNET特殊处理（或保留为配置选项）

### Phase 5：测试验证
1. 单元测试：测试difficulty计算和调整逻辑
2. 集成测试：测试block接受/拒绝逻辑
3. 压力测试：模拟高节点数场景
4. 网络测试：多节点环境验证共识

---

## ⚠️ 注意事项

### 1. 数据迁移（快照导入）

**升级方式**：
- ✅ 全新版本停机升级
- ✅ 老数据通过快照导入（已验证的历史blocks）
- ✅ 新blocks从导入后严格验证PoW
- ✅ 无需渐进式升级策略

**快照导入逻辑**：
```java
/**
 * Import blocks from snapshot
 * Snapshot blocks are pre-validated, skip PoW check during import
 */
public void importFromSnapshot(List<Block> snapshotBlocks) {
    log.info("Importing {} blocks from snapshot", snapshotBlocks.size());

    for (Block block : snapshotBlocks) {
        // Import without validation (trusted snapshot)
        dagStore.saveBlock(block);
        dagStore.saveBlockInfo(block.getInfo());
    }

    log.info("Snapshot import completed");
}
```

### 2. 孤块池淘汰策略

**问题**：
- 每epoch接受100个blocks，只有1个成为主块
- 其他99个成为orphan blocks
- 随着时间推移，orphan blocks会累积
- 需要淘汰策略防止无限增长

**淘汰策略设计**：

#### 方案A：时间窗口淘汰（推荐）

**原理**：
- XDAG规则：blocks只能引用最近12天（16384个epochs）的blocks
- 超过12天的orphan blocks不可能再被新blocks引用
- 可以安全删除

**参数**：
```java
/**
 * Orphan block retention window (in epochs)
 *
 * XDAG rule: blocks can only reference blocks within 12 days (16384 epochs)
 * After this window, orphan blocks cannot become main blocks anymore
 *
 * Setting: 16384 epochs = 12 days (保留12天的orphan blocks)
 */
private static final long ORPHAN_RETENTION_WINDOW = 16384;

/**
 * Orphan cleanup interval (in epochs)
 *
 * Run cleanup every N epochs to remove old orphans
 *
 * Setting: 100 epochs ≈ 1.78 hours (每1.78小时清理一次)
 */
private static final long ORPHAN_CLEANUP_INTERVAL = 100;
```

**实现**：
```java
/**
 * Clean up old orphan blocks beyond retention window
 *
 * Called periodically (every ORPHAN_CLEANUP_INTERVAL epochs)
 * Removes orphan blocks older than ORPHAN_RETENTION_WINDOW
 */
private synchronized void cleanupOldOrphans(long currentEpoch) {
    long lastCleanupEpoch = chainStats.getLastOrphanCleanupEpoch();

    // Check if cleanup interval reached
    if (currentEpoch - lastCleanupEpoch < ORPHAN_CLEANUP_INTERVAL) {
        return;  // Not time yet
    }

    log.info("Orphan cleanup triggered at epoch {}", currentEpoch);

    long cutoffEpoch = currentEpoch - ORPHAN_RETENTION_WINDOW;
    int removedCount = 0;

    // Scan all epochs before cutoff
    for (long epoch = lastCleanupEpoch; epoch < cutoffEpoch; epoch++) {
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);

        for (Block block : candidates) {
            // Remove orphan blocks (height = 0)
            if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
                dagStore.deleteBlock(block.getHash());
                dagStore.deleteBlockInfo(block.getHash());
                orphanBlockStore.deleteByHash(block.getHash().toArray());
                removedCount++;
            }
        }
    }

    log.info("Orphan cleanup completed: removed {} blocks from epochs {} to {}",
            removedCount, lastCleanupEpoch, cutoffEpoch);

    // Update last cleanup epoch
    chainStats = chainStats.toBuilder()
            .lastOrphanCleanupEpoch(currentEpoch)
            .build();
    dagStore.saveChainStats(chainStats);
}
```

**触发时机**：
```java
@Override
public synchronized DagImportResult tryToConnect(Block block) {
    try {
        // ... 验证和导入block ...

        // Update chain statistics
        if (isBestChain) {
            updateChainStatsForNewMainBlock(blockInfo);

            // Check difficulty adjustment
            checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());

            // ✓ NEW: Cleanup old orphans
            cleanupOldOrphans(block.getEpoch());
        }

        // ... 其余逻辑 ...
    }
}
```

**效果评估**：
```
假设：
- MAX_BLOCKS_PER_EPOCH = 100
- ORPHAN_RETENTION_WINDOW = 16384 epochs (12 days)
- 每epoch 99个orphan blocks

最大orphan blocks数量：
= 99 blocks/epoch × 16384 epochs
= 1,621,536 blocks
≈ 829 MB (每block约512字节)

对比无淘汰策略：
- 1年后：99 × 493,560 epochs = 48.8M blocks = 25 GB
- 3年后：99 × 1,480,680 epochs = 146.6M blocks = 75 GB

结论：淘汰策略将orphan pool大小稳定在 ~830 MB
```

#### 方案B：数量上限淘汰（备选）

**原理**：
- 设置orphan blocks总数上限（例如1,000,000个）
- 达到上限后，删除最老的orphan blocks

**参数**：
```java
/**
 * Maximum orphan blocks to keep
 * When exceeded, oldest orphans are removed (FIFO)
 */
private static final int MAX_ORPHAN_BLOCKS = 1_000_000;
```

**实现**：
```java
private synchronized void enforceOrphanLimit() {
    // Count total orphan blocks
    int orphanCount = countTotalOrphans();

    if (orphanCount <= MAX_ORPHAN_BLOCKS) {
        return;  // Under limit
    }

    log.info("Orphan count {} exceeds limit {}, removing oldest",
            orphanCount, MAX_ORPHAN_BLOCKS);

    int toRemove = orphanCount - MAX_ORPHAN_BLOCKS;
    int removed = 0;

    // Remove oldest orphans (scan from earliest epochs)
    for (long epoch = 0; removed < toRemove; epoch++) {
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);

        for (Block block : candidates) {
            if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
                dagStore.deleteBlock(block.getHash());
                dagStore.deleteBlockInfo(block.getHash());
                orphanBlockStore.deleteByHash(block.getHash().toArray());
                removed++;

                if (removed >= toRemove) break;
            }
        }
    }

    log.info("Removed {} oldest orphan blocks", removed);
}
```

**优缺点对比**：

| 特性 | 方案A（时间窗口） | 方案B（数量上限） |
|------|------------------|------------------|
| 存储可预测性 | ✅ 固定 ~830 MB | ✅ 固定 ~512 MB |
| 逻辑简单性 | ✅ 简单清晰 | ⚠️ 需要计数 |
| 符合XDAG规则 | ✅ 与12天规则一致 | ⚠️ 不完全一致 |
| 性能开销 | ✅ 每100 epochs扫描 | ⚠️ 需要持续计数 |

**推荐**：使用**方案A（时间窗口淘汰）**

### 3. 配置参数

**建议将关键参数配置化**：

```java
// config.properties
consensus.base_difficulty_target=0x0000000000000000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF
consensus.max_blocks_per_epoch=100
consensus.target_blocks_per_epoch=150
consensus.difficulty_adjustment_interval=1000
consensus.pow_validation_start_height=100000
```

### 3. 监控指标

**需要监控的关键指标**：
- 每个epoch接受的blocks数量
- 当前difficulty target
- Orphan率
- 矿工算力分布
- Difficulty调整频率和幅度

**日志示例**：
```
[INFO] Epoch 23694: Accepted 87 blocks (target: 150, orphan rate: 58%)
[INFO] Current difficulty target: 0x00000000000000FF...
[INFO] Difficulty adjustment at epoch 24000: factor=1.2 (raising target 20%)
```

---

## 📚 参考资料

### 相关Bitcoin文档
- Bitcoin Difficulty Adjustment: https://en.bitcoin.it/wiki/Difficulty
- Bitcoin Target Calculation: https://en.bitcoin.it/wiki/Target

### XDAG设计文档
- 整点出块分析：`C_VS_JAVA_BLOCK_GENERATION.md`
- Difficulty验证分析：`DIFFICULTY_VALIDATION_ANALYSIS.md`
- Height vs Epoch分析：`HEIGHT_VS_EPOCH_ANALYSIS.md`

---

生成时间：2025-11-13
设计人：Claude
审核人：待审核
代码版本：xdagj @ refactor/core-v5.1
