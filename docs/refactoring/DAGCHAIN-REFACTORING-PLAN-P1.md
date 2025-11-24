# DagChain重构计划 - P1阶段

## 当前状态 (2025-11-24)

### 已完成 (P0)
- ✅ **BlockValidator** (650行) - 区块验证逻辑
- ✅ **BlockImporter** (550行) - 区块导入逻辑
- ✅ 已集成到DagChainImpl并通过所有测试

### 当前问题
- ❌ **DagChainImpl仍有2,640行** (目标: <1,000行)
- ❌ 代码仍然承担太多职责
- ❌ 还有大量代码可以继续提取

---

## P1阶段目标

**目标**: 继续提取4个核心模块，将DagChainImpl从2,640行降到约1,400行

### 模块拆分计划

```
DagChainImpl (当前: 2,640行)
    ↓ 提取P1模块
┌──────────────────────────────────────────┐
│ 1. BlockBuilder         (~260行)         │ - 候选块创建
│ 2. ChainReorganizer     (~420行)         │ - 链重组管理
│ 3. DifficultyAdjuster   (~200行)         │ - 难度调整
│ 4. OrphanManager        (~150行)         │ - 孤块管理
└──────────────────────────────────────────┘
提取后: DagChainImpl剩余 ~1,610行

后续P2阶段可继续优化到 <1,000行
```

---

## P1-1: BlockBuilder - 候选块创建器

### 职责
- 创建候选区块（用于挖矿）
- 收集候选链接（从主链和交易池）
- Mining coinbase管理

### 当前代码位置
```
DagChainImpl.java:
- createCandidateBlock()           (lines 936-964)   - 29行
- collectCandidateLinks()          (lines 966-1194)  - 229行
- 相关辅助方法                                       - ~20行
```

### 提取后接口
```java
/**
 * Block Builder - 候选区块构建器
 *
 * 负责创建用于挖矿的候选区块
 */
public class BlockBuilder {
    private final DagStore dagStore;
    private final DagQueryService queryService;
    private final TransactionPool transactionPool;
    private final Config config;
    private volatile Bytes miningCoinbase;

    /**
     * 创建候选区块
     *
     * @return 新的候选区块（包含链接和交易）
     */
    public Block createCandidateBlock() {
        long epoch = TimeUtils.getCurrentEpochNumber();
        UInt256 difficultyTarget = getBaseDifficultyTarget();
        Bytes coinbase = miningCoinbase;
        List<Link> links = collectCandidateLinks();

        return Block.createCandidate(epoch, difficultyTarget, coinbase, links);
    }

    /**
     * 收集候选链接
     *
     * 策略：
     * 1. 获取前一个主块的所有候选块（同epoch）
     * 2. 按工作量排序，取top 16
     * 3. 添加交易链接（最多1024个）
     */
    private List<Link> collectCandidateLinks() {
        List<Link> links = new ArrayList<>();

        // 1. 引用上一个高度的epoch获胜者区块
        Block previousMain = getPreviousMainBlock();
        if (previousMain != null) {
            long previousEpoch = previousMain.getEpoch();
            List<Block> candidates = getCandidateBlocksInEpoch(previousEpoch);

            // 按工作量排序，取top 16
            candidates.stream()
                .sorted(Comparator.comparing(this::calculateBlockWork).reversed())
                .limit(MAX_BLOCK_LINKS)
                .forEach(block -> links.add(Link.toBlock(block.getHash())));
        }

        // 2. 添加交易链接（最多1024个）
        List<Transaction> txs = transactionPool.getPendingTransactions(MAX_TX_LINKS);
        for (Transaction tx : txs) {
            links.add(Link.toTransaction(tx.getHash()));
        }

        return links;
    }

    public void setMiningCoinbase(Bytes coinbase) {
        this.miningCoinbase = coinbase;
    }
}
```

### 预期收益
- DagChainImpl: 2,640行 → 2,380行 (-260行)
- 候选块创建逻辑清晰独立
- 易于测试和优化链接选择策略

---

## P1-2: ChainReorganizer - 链重组管理器

### 职责
- 检测链重组条件
- 执行链重组
- 查找分叉点
- 升级/降级区块

### 当前代码位置
```
DagChainImpl.java:
- checkNewMain()                   (lines 1571-1673)  - 103行
- performChainReorganization()     (lines 1675-1847)  - 173行
- demoteBlockToOrphan()            (lines 1849-1920)  - 72行
- demoteBlocksFromHeight()         (lines 1922-1988)  - 67行
- 相关辅助方法                                        - ~25行
```

### 提取后接口
```java
/**
 * Chain Reorganizer - 链重组管理器
 *
 * 处理主链重组和区块升降级
 */
public class ChainReorganizer {
    private final DagStore dagStore;
    private final TransactionStore transactionStore;
    private final DagAccountManager accountManager;

    /**
     * 检查并更新主链
     *
     * 扫描最近epochs，查找累积难度更高的分叉链
     */
    public void checkNewMain() {
        long currentEpoch = TimeUtils.getCurrentEpochNumber();
        long scanStart = Math.max(1, currentEpoch - ORPHAN_RETENTION_WINDOW);

        // 收集所有epoch赢家
        Map<Long, Block> epochWinners = collectEpochWinners(scanStart, currentEpoch);

        // 按epoch排序并分配连续高度
        assignContinuousHeights(epochWinners);

        // 检查是否需要链重组
        checkChainReorganization();
    }

    /**
     * 执行链重组
     *
     * @param newForkHead 新链的头部
     */
    public void performChainReorganization(Block newForkHead) {
        // 1. 查找分叉点
        Block forkPoint = findForkPoint(newForkHead);
        long forkHeight = forkPoint.getInfo().getHeight();

        // 2. 降级旧链（分叉点之后）
        List<Block> demoted = demoteBlocksAfterHeight(forkHeight);

        // 3. 升级新链
        List<Block> newChain = buildChainPath(newForkHead, forkPoint);
        promoteChain(newChain, forkHeight);

        // 4. 回滚和重放交易
        rollbackTransactions(demoted);
        replayTransactions(newChain);
    }

    /**
     * 降级单个区块到orphan
     */
    public void demoteBlockToOrphan(Block block) {
        if (block.getInfo().getHeight() == 0) {
            return; // 已经是orphan
        }

        long oldHeight = block.getInfo().getHeight();

        // 更新BlockInfo (height = 0)
        BlockInfo orphanInfo = block.getInfo().toBuilder()
            .height(0)
            .build();
        dagStore.saveBlockInfo(orphanInfo);

        // 删除高度索引
        dagStore.deleteBlockByHeight(oldHeight);

        // 回滚交易
        rollbackBlockTransactions(block);
    }

    /**
     * 降级指定高度之后的所有区块
     */
    public List<Block> demoteBlocksAfterHeight(long afterHeight) {
        List<Block> demoted = new ArrayList<>();
        long maxHeight = getMaxHeight();

        for (long h = afterHeight + 1; h <= maxHeight; h++) {
            Block block = getMainBlockByHeight(h);
            if (block != null && block.getInfo().getHeight() > 1) {
                demoteBlockToOrphan(block);
                demoted.add(block);
            }
        }

        return demoted;
    }

    private Block findForkPoint(Block forkHead) { ... }
    private List<Block> buildChainPath(Block head, Block base) { ... }
    private void rollbackBlockTransactions(Block block) { ... }
}
```

### 预期收益
- DagChainImpl: 2,380行 → 1,960行 (-420行)
- 链重组逻辑清晰，易于理解和调试
- 交易回滚/重放逻辑集中管理

---

## P1-3: DifficultyAdjuster - 难度调整器

### 职责
- 定期检查难度
- 计算难度调整
- 更新网络难度目标
- 计算网络算力

### 当前代码位置
```
DagChainImpl.java:
- checkAndAdjustDifficulty()       (lines 791-885)   - 95行
- calculateNetworkHashrate()       (lines 887-920)   - 34行
- getCandidateBlocksInEpoch()      (lines 922-932)   - 11行
- 相关常量和字段                                     - ~60行
```

### 提取后接口
```java
/**
 * Difficulty Adjuster - 难度调整器
 *
 * 负责网络难度的定期调整
 */
public class DifficultyAdjuster {
    private static final long DIFFICULTY_ADJUSTMENT_INTERVAL = 1000; // 每1000个epoch调整一次
    private static final double MIN_ADJUSTMENT_FACTOR = 0.5;
    private static final double MAX_ADJUSTMENT_FACTOR = 2.0;

    private final DagStore dagStore;
    private final ChainStats chainStats;

    /**
     * 检查并调整难度
     *
     * 在每1000个主块后触发
     */
    public DifficultyAdjustment checkAndAdjustDifficulty(long currentHeight, long currentEpoch) {
        // 1. 检查是否需要调整
        if (!shouldAdjustDifficulty(currentHeight)) {
            return DifficultyAdjustment.noChange();
        }

        // 2. 统计最近1000个epoch的区块数
        long lastAdjustmentEpoch = getLastAdjustmentEpoch();
        long scanStart = Math.max(lastAdjustmentEpoch,
            currentEpoch - DIFFICULTY_ADJUSTMENT_INTERVAL);

        int totalBlocks = 0;
        int epochCount = 0;
        for (long epoch = scanStart; epoch < currentEpoch; epoch++) {
            List<Block> blocks = getCandidateBlocksInEpoch(epoch);
            totalBlocks += blocks.size();
            epochCount++;
        }

        // 3. 计算调整因子
        double actualRate = (double) totalBlocks / epochCount;
        double targetRate = 1.0; // 目标：每epoch 1个主块
        double adjustmentFactor = targetRate / actualRate;

        // 限制调整范围
        adjustmentFactor = Math.max(MIN_ADJUSTMENT_FACTOR,
            Math.min(MAX_ADJUSTMENT_FACTOR, adjustmentFactor));

        // 4. 应用调整
        UInt256 oldTarget = chainStats.getBaseDifficultyTarget();
        UInt256 newTarget = adjustDifficulty(oldTarget, adjustmentFactor);

        // 5. 保存新难度
        chainStats.setBaseDifficultyTarget(newTarget);
        chainStats.setLastDifficultyAdjustmentHeight(currentHeight);
        chainStats.setLastDifficultyAdjustmentEpoch(currentEpoch);

        return DifficultyAdjustment.adjusted(adjustmentFactor, oldTarget, newTarget);
    }

    /**
     * 计算网络算力
     *
     * @return 网络算力 (hashes/second)
     */
    public double calculateNetworkHashrate() {
        // 统计最近100个epoch的工作量
        long currentEpoch = TimeUtils.getCurrentEpochNumber();
        UInt256 totalWork = UInt256.ZERO;
        int blockCount = 0;

        for (long epoch = currentEpoch - 100; epoch < currentEpoch; epoch++) {
            List<Block> candidates = getCandidateBlocksInEpoch(epoch);
            for (Block block : candidates) {
                totalWork = totalWork.add(calculateBlockWork(block.getHash()));
                blockCount++;
            }
        }

        // 网络算力 = 总工作量 / 时间
        double timeSeconds = 100 * 64.0; // 100个epoch * 64秒
        double hashrate = totalWork.toDecimalString().doubleValue() / timeSeconds;

        return hashrate;
    }

    private boolean shouldAdjustDifficulty(long height) {
        return height >= DIFFICULTY_ADJUSTMENT_INTERVAL
            && height % DIFFICULTY_ADJUSTMENT_INTERVAL == 0;
    }

    private UInt256 adjustDifficulty(UInt256 oldTarget, double factor) {
        return oldTarget.multiply(UInt256.valueOf((long)(factor * 1000000)))
            .divide(UInt256.valueOf(1000000));
    }
}
```

### 预期收益
- DagChainImpl: 1,960行 → 1,760行 (-200行)
- 难度调整逻辑独立，易于测试
- 可以轻松实现不同的难度算法

---

## P1-4: OrphanManager - 孤块管理器

### 职责
- 重试孤块导入
- 清理过期孤块
- 跟踪孤块统计

### 当前代码位置
```
DagChainImpl.java:
- retryOrphanBlocks()              (lines 663-737)   - 75行
- cleanupOldOrphans()              (lines 737-789)   - 53行
- 相关字段和计数                                     - ~20行
```

### 提取后接口
```java
/**
 * Orphan Manager - 孤块管理器
 *
 * 管理孤块的重试和清理
 */
public class OrphanManager {
    private static final long ORPHAN_RETENTION_WINDOW = 16384; // 12天
    private static final long ORPHAN_CLEANUP_INTERVAL = 1024;  // 每1024个epoch清理一次

    private final DagStore dagStore;
    private final BlockImporter blockImporter;
    private long lastOrphanRetryHeight = 0;
    private long lastCleanupEpoch = 0;

    /**
     * 重试孤块导入
     *
     * 尝试重新导入之前因缺少依赖而变成orphan的区块
     */
    public int retryOrphanBlocks() {
        List<Bytes32> orphanHashes = dagStore.getOrphanBlockHashes();
        int successCount = 0;

        for (Bytes32 hash : orphanHashes) {
            Block orphan = dagStore.getBlockByHash(hash, false);
            if (orphan == null) {
                continue;
            }

            // 尝试重新导入
            BlockImporter.ImportResult result = blockImporter.importBlock(orphan, chainStats);
            if (result.isSuccess() && result.isBestChain()) {
                successCount++;
                log.info("Successfully promoted orphan {} to main chain at height {}",
                    formatHash(hash), result.getHeight());
            }
        }

        if (successCount > 0) {
            log.info("Orphan retry: promoted {} blocks to main chain", successCount);
        }

        return successCount;
    }

    /**
     * 清理过期的孤块
     *
     * 删除超过保留窗口的孤块
     */
    public int cleanupOldOrphans(long currentEpoch) {
        // 检查是否需要清理
        if (currentEpoch - lastCleanupEpoch < ORPHAN_CLEANUP_INTERVAL) {
            return 0;
        }

        long cutoffEpoch = currentEpoch - ORPHAN_RETENTION_WINDOW;
        List<Bytes32> orphanHashes = dagStore.getOrphanBlockHashes();
        int cleanedCount = 0;

        for (Bytes32 hash : orphanHashes) {
            Block orphan = dagStore.getBlockByHash(hash, false);
            if (orphan != null && orphan.getEpoch() < cutoffEpoch) {
                dagStore.deleteBlock(hash);
                cleanedCount++;
            }
        }

        lastCleanupEpoch = currentEpoch;

        if (cleanedCount > 0) {
            log.info("Cleaned up {} old orphan blocks (before epoch {})",
                cleanedCount, cutoffEpoch);
        }

        return cleanedCount;
    }

    /**
     * 获取孤块统计信息
     */
    public OrphanStats getOrphanStats() {
        List<Bytes32> orphanHashes = dagStore.getOrphanBlockHashes();
        return new OrphanStats(
            orphanHashes.size(),
            lastOrphanRetryHeight,
            lastCleanupEpoch
        );
    }
}
```

### 预期收益
- DagChainImpl: 1,760行 → 1,610行 (-150行)
- 孤块管理逻辑集中，易于优化
- 可以添加更智能的重试策略

---

## P1阶段实施计划

### 第1步：BlockBuilder (预计2小时)
1. 创建 `BlockBuilder.java`
2. 迁移 `createCandidateBlock()` 和 `collectCandidateLinks()`
3. 在 `DagChainImpl` 中使用 `BlockBuilder`
4. 运行测试验证

### 第2步：ChainReorganizer (预计4小时)
1. 创建 `ChainReorganizer.java`
2. 迁移链重组相关方法
3. 迁移区块升降级方法
4. 在 `DagChainImpl` 中使用 `ChainReorganizer`
5. 运行测试验证

### 第3步：DifficultyAdjuster (预计2小时)
1. 创建 `DifficultyAdjuster.java`
2. 迁移难度调整逻辑
3. 在 `DagChainImpl` 中使用 `DifficultyAdjuster`
4. 运行测试验证

### 第4步：OrphanManager (预计1.5小时)
1. 创建 `OrphanManager.java`
2. 迁移孤块管理逻辑
3. 在 `DagChainImpl` 中使用 `OrphanManager`
4. 运行测试验证

### 第5步：最终集成测试 (预计1小时)
1. 运行完整测试套件
2. 性能对比测试
3. 代码审查
4. 更新文档

**总预计时间**: 10.5小时 (~1.5个工作日)

---

## 成功标准

### 代码指标
- ✅ DagChainImpl从2,640行降到约1,610行 (减少39%)
- ✅ 提取4个独立的模块，每个<300行
- ✅ 所有方法<100行

### 功能指标
- ✅ 所有测试通过 (388个测试)
- ✅ 无性能回归（导入速度、内存使用）
- ✅ 无新bug引入

### 质量指标
- ✅ 代码可测试性提升（每个模块可独立测试）
- ✅ 代码可维护性提升（职责清晰）
- ✅ 代码可扩展性提升（易于添加新功能）

---

## 后续计划 (P2阶段)

如果P1阶段成功，可以考虑P2阶段进一步优化：

1. **DagQueryService** (~200行) - 集中查询接口
2. **DagChainCoordinator** (~300行) - 协调器（新的精简核心）

**最终目标**: DagChainImpl < 1,000行

---

## 风险评估

### 低风险 ✅
- P0已验证可行，P1采用相同策略
- 逐步迁移，每步都有测试验证
- 可以随时回滚（通过git）

### 中风险 ⚠️
- ChainReorganizer逻辑较复杂，需要仔细测试
- 链重组涉及交易回滚，需要确保原子性

### 缓解措施
1. 在feature分支进行，不直接修改master
2. 每个模块提取后立即运行完整测试
3. 保留详细的commit历史，便于问题追溯
4. 集成测试包含链重组场景

---

**文档创建时间**: 2025-11-24
**当前状态**: P0完成，准备开始P1
**预期完成时间**: 1.5个工作日
**负责人**: Claude Code
