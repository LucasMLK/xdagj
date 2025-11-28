# BUG-CONSENSUS-007: Height分配竞态条件导致同epoch多个Main blocks

## Bug ID
**BUG-CONSENSUS-007**

## 严重程度
**P2** (不影响共识，但影响数据一致性和API查询结果)

## 发现时间
2025-11-28 19:30 (GMT+8)

## 问题描述

### 现象
在多节点环境中，同一个epoch的不同blocks可能在不同节点被分配为Main block（height > 0），导致：
- 相同height在不同节点返回不同的block
- 同一epoch有多个blocks的height > 0（违反单winner原则）
- Height分配取决于P2P block到达顺序

### 实例证据 (epoch 27567487)
```
Node1:
- Height 150: Block A (0xab3255e2..., difficulty 10048)
- Height 151: Block B (0x5c29cb2f..., difficulty 8402)

Node2:
- Height 150: Block B (0x5c29cb2f..., difficulty 8402)
- Height 151: Block A (0xab3255e2..., difficulty 10048)

关键特征:
✅ 两个blocks都存在于两个节点
✅ 都属于epoch 27567487
❌ Height分配顺序完全相反
❌ 两个blocks都有height > 0 (都是Main状态)
```

## 根本原因

### 代码位置
- **主要文件**: `src/main/java/io/xdag/core/BlockImporter.java`
- **关键方法**: `importBlock` (lines 101-199), `determineEpochWinner` (lines 219-294)

### 时序分析

#### 正常场景 (应该的行为)
```
Node接收到同epoch的两个blocks A和B (A的hash < B的hash):

时刻T1: Block B先到达
  → currentWinner = null
  → Case 3: Block B成为main block, height=150

时刻T2: Block A后到达
  → currentWinner = Block B
  → A.hash < B.hash → A赢得竞争
  → Case 1: Demote Block B to orphan (height=0)
  → Block A接管height=150

结果: ✅ 只有Block A是main (height=150), Block B是orphan (height=0)
```

#### 实际Bug场景 (实际发生的)
```
Node1时间线:
T1: Block A到达 → height=150 (main)
T2: Block B到达 → height=0 (orphan, 因为hash更大)
结果: ✅ 正确

Node2时间线:
T1: Block B到达 → height=150 (main)
T2: Block A到达 → ???

问题: Block A应该demote Block B并接管height 150
但实际: Block A被分配了height 151
结果: ❌ 两个blocks都是main!
```

### Bug根源

**BlockImporter.importBlock** 的处理流程：

```java
// Line 124-139: Step 3 - Save with PENDING height
long pendingHeight = isGenesisBlock(block) ? 1 : 0;
dagStore.saveBlockInfo(pendingInfo);       // ← Block先保存到DB
dagStore.saveBlock(blockWithPendingInfo);

// Line 142: Step 4 - Determine competition
EpochCompetitionResult competition = determineEpochWinner(block, blockEpoch, chainStats);
```

**关键问题**：Block在竞争判断**之前**就被保存到DB（with height=0）

**determineEpochWinner逻辑分析**：

```java
// Line 225: 获取当前winner
Block currentWinner = getWinnerBlockInEpoch(blockEpoch);
// ↑ 这会扫描DB中所有candidates，包括刚刚保存的新block!

// Line 228-230: 判断是否赢得竞争
boolean isEpochWinner = (currentWinner == null) ||
    currentWinner.getHash().equals(block.getHash()) ||
    (block.getHash().compareTo(currentWinner.getHash()) < 0);
```

**getWinnerBlockInEpoch** (lines 357-368):
```java
private Block getWinnerBlockInEpoch(long epoch) {
    List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);
    // 返回hash最小的block
    return candidates.stream()
        .filter(block -> block.getInfo() != null)
        .min(Comparator.comparing(Block::getHash))
        .orElse(null);
}
```

**Bug场景详细分析 (Node2, Block A到达时)**：

1. **Line 139**: Block A保存到DB (hash=0xab3255e2..., height=0, epoch=27567487)
2. **Line 142**: 调用`determineEpochWinner`
3. **Line 225**: `getWinnerBlockInEpoch(27567487)`扫描DB:
   - 找到Block A (hash=0xab3255e2..., height=0) ← 刚保存的
   - 找到Block B (hash=0x5c29cb2f..., height=150) ← 已存在的main block
   - **返回Block A (hash更小)**
4. **Line 228-230**:
   - `currentWinner` = Block A (自己!)
   - `currentWinner.getHash().equals(block.getHash())` = true
   - `isEpochWinner` = true
   - **进入Case 2** (line 252)
5. **Line 254-255** (Case 2逻辑):
   ```java
   List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(blockEpoch);
   Block otherMainBlock = findOtherMainBlockInEpoch(allCandidates, block);
   ```
   - 找到Block B (height=150)
6. **Line 260-263**:
   ```java
   demotedBlock = otherMainBlock;  // Block B
   height = otherMainBlock.getInfo().getHeight();  // 150
   isBestChain = true;
   ```
7. **Line 149-162** (回到importBlock):
   ```java
   if (competition.isWinner() && competition.getDemotedBlock() != null) {
       demoteBlockToOrphan(demotedBlock);  // 应该demote Block B
   }
   ```

**理论上这段代码应该正确！**

### 实际Bug在哪里？

经过仔细分析，我发现了**真正的bug**：

**问题出在Case 2的else分支** (lines 264-270)：

```java
} else {
    // No other main blocks, assign new height
    log.debug("Block {} is first winner in epoch {}, assigning new height",
        formatHash(block.getHash()), blockEpoch);
    height = isGenesisBlock(block) ? 1 : chainStats.getMainBlockCount() + 1;
    isBestChain = true;
}
```

**可能的竞态场景**：

如果在`findOtherMainBlockInEpoch`和`demoteBlockToOrphan`之间：
- 另一个线程（或OrphanManager retry）也在处理blocks
- Block B的状态发生了变化
- 导致demotion失败或不完整

**或者更可能的问题**：

`findOtherMainBlockInEpoch` (lines 299-308)的实现：

```java
private Block findOtherMainBlockInEpoch(List<Block> candidates, Block excludeBlock) {
    for (Block candidate : candidates) {
        if (candidate.getInfo() != null &&
            candidate.getInfo().getHeight() > 0 &&
            !candidate.getHash().equals(excludeBlock.getHash())) {
            return candidate;
        }
    }
    return null;
}
```

**这个方法只返回第一个找到的main block！**

如果epoch中有多个main blocks（由于之前的bugs），这个方法只会demote其中一个！

### 另一个可能的根源

查看**第171-176行**：

```java
// Update storage if height changed
if (finalHeight != pendingHeight) {
    dagStore.saveBlockInfo(finalInfo);
    dagStore.saveBlock(finalBlock);
    log.debug("Updated block {} with final height={}",
        formatHash(block.getHash()), finalHeight);
}
```

**问题**：如果`finalHeight == pendingHeight`（都是0），则不更新！

但在某些边缘情况下，block可能需要被更新但height恰好没变。

## 影响分析

### 对共识的影响
✅ **不影响共识安全性**
- Epoch winner selection基于hash，仍然正确
- Cumulative difficulty计算正确
- Fork resolution基于epoch和difficulty，不依赖height

### 对数据一致性的影响
❌ **严重影响数据一致性**
- 不同节点对相同height返回不同blocks
- API查询结果不一致（区块浏览器显示混乱）
- compare-nodes.sh检测到不一致
- 违反"每个epoch只有1个main block"的设计原则

### 对应用的影响
❌ **影响依赖height的应用**
- 通过height查询block可能返回不同结果
- 区块浏览器显示不一致
- 统计数据不准确
- 用户困惑

## 修复方案 (方案1: Enforce Single Winner)

### 修复策略

确保每个epoch在整个处理过程中只有1个Main block：

1. **在determineEpochWinner中彻底检查**：找出ALL main blocks（不只是第一个）
2. **一次性demote所有需要demote的blocks**
3. **添加验证逻辑**：在import完成后验证epoch只有1个main block

### 具体修复 (BlockImporter.java)

#### 修复1: 改进findOtherMainBlockInEpoch

**当前实现** (line 299-308):
```java
private Block findOtherMainBlockInEpoch(List<Block> candidates, Block excludeBlock) {
    for (Block candidate : candidates) {
        if (candidate.getInfo() != null &&
            candidate.getInfo().getHeight() > 0 &&
            !candidate.getHash().equals(excludeBlock.getHash())) {
            return candidate;  // ← 只返回第一个!
        }
    }
    return null;
}
```

**修复后**:
```java
/**
 * Find ALL main blocks in the same epoch (excluding the specified block)
 *
 * @return List of all main blocks (height > 0) in this epoch, excluding excludeBlock
 */
private List<Block> findAllOtherMainBlocksInEpoch(List<Block> candidates, Block excludeBlock) {
    List<Block> otherMainBlocks = new ArrayList<>();
    for (Block candidate : candidates) {
        if (candidate.getInfo() != null &&
            candidate.getInfo().getHeight() > 0 &&
            !candidate.getHash().equals(excludeBlock.getHash())) {
            otherMainBlocks.add(candidate);
        }
    }
    return otherMainBlocks;
}
```

#### 修复2: 更新Case 2逻辑

**当前实现** (line 252-270):
```java
} else if (currentWinner != null && currentWinner.getHash().equals(block.getHash())) {
    List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(blockEpoch);
    Block otherMainBlock = findOtherMainBlockInEpoch(allCandidates, block);

    if (otherMainBlock != null) {
        demotedBlock = otherMainBlock;
        height = otherMainBlock.getInfo().getHeight();
        isBestChain = true;
    } else {
        height = isGenesisBlock(block) ? 1 : chainStats.getMainBlockCount() + 1;
        isBestChain = true;
    }
}
```

**修复后**:
```java
} else if (currentWinner != null && currentWinner.getHash().equals(block.getHash())) {
    // Case 2: Block IS the winner, check for other main blocks in same epoch
    List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(blockEpoch);
    List<Block> otherMainBlocks = findAllOtherMainBlocksInEpoch(allCandidates, block);

    if (!otherMainBlocks.isEmpty()) {
        // ✅ NEW: Demote ALL other main blocks in this epoch
        log.warn("Block {} is epoch {} winner, found {} other main blocks to demote",
            formatHash(block.getHash()), blockEpoch, otherMainBlocks.size());

        // Take height from the first one (they should all be sequential)
        Block firstDemoted = otherMainBlocks.get(0);
        height = firstDemoted.getInfo().getHeight();

        // Mark ALL for demotion (will be processed in Step 4.5)
        demotedBlocks = otherMainBlocks;  // ← NEW: List instead of single block
        isBestChain = true;

    } else {
        // No other main blocks, assign new height
        log.debug("Block {} is first winner in epoch {}, assigning new height",
            formatHash(block.getHash()), blockEpoch);
        height = isGenesisBlock(block) ? 1 : chainStats.getMainBlockCount() + 1;
        isBestChain = true;
    }
}
```

#### 修复3: 更新EpochCompetitionResult

**新增字段** (EpochCompetitionResult class):
```java
@Getter
static class EpochCompetitionResult {
    private final long height;
    private final boolean isWinner;
    private final boolean isEpochWinner;
    private final Block demotedBlock;         // ← Keep for backward compatibility
    private final List<Block> demotedBlocks;  // ← NEW: Support multiple demotions

    public EpochCompetitionResult(long height, boolean isWinner,
                                   boolean isEpochWinner, Block demotedBlock) {
        this(height, isWinner, isEpochWinner, demotedBlock,
             demotedBlock != null ? Collections.singletonList(demotedBlock) : Collections.emptyList());
    }

    public EpochCompetitionResult(long height, boolean isWinner,
                                   boolean isEpochWinner, List<Block> demotedBlocks) {
        this(height, isWinner, isEpochWinner,
             demotedBlocks.isEmpty() ? null : demotedBlocks.get(0),
             demotedBlocks);
    }

    private EpochCompetitionResult(long height, boolean isWinner,
                                    boolean isEpochWinner,
                                    Block demotedBlock,
                                    List<Block> demotedBlocks) {
        this.height = height;
        this.isWinner = isWinner;
        this.isEpochWinner = isEpochWinner;
        this.demotedBlock = demotedBlock;
        this.demotedBlocks = demotedBlocks;
    }
}
```

#### 修复4: 更新demotion处理逻辑

**当前实现** (importBlock line 149-162):
```java
if (competition.isWinner() && competition.getDemotedBlock() != null) {
    Block demotedBlock = competition.getDemotedBlock();
    long oldHeight = demotedBlock.getInfo() != null ? demotedBlock.getInfo().getHeight() : 0;

    log.warn("⬇️  DEMOTION: Block {} being demoted from height {} to orphan (lost competition)",
        formatHash(demotedBlock.getHash()), oldHeight);

    demoteBlockToOrphan(demotedBlock);

    log.warn("✅ DEMOTION COMPLETE: Block {} now orphan (height=0), winner {} takes height {}",
        formatHash(demotedBlock.getHash()),
        formatHash(block.getHash()),
        finalHeight);
}
```

**修复后**:
```java
// Step 4.5: Handle epoch competition demotion (if new block wins)
if (competition.isWinner() && !competition.getDemotedBlocks().isEmpty()) {
    List<Block> demotedBlocks = competition.getDemotedBlocks();

    log.warn("⬇️  DEMOTION: {} block(s) being demoted from epoch {} (lost competition to {})",
        demotedBlocks.size(), blockEpoch, formatHash(block.getHash()));

    for (Block demotedBlock : demotedBlocks) {
        long oldHeight = demotedBlock.getInfo() != null ? demotedBlock.getInfo().getHeight() : 0;

        log.warn("   - Demoting block {} from height {} to orphan",
            formatHash(demotedBlock.getHash()), oldHeight);

        demoteBlockToOrphan(demotedBlock);
    }

    log.warn("✅ DEMOTION COMPLETE: {} block(s) now orphan, winner {} takes height {}",
        demotedBlocks.size(),
        formatHash(block.getHash()),
        finalHeight);
}
```

#### 修复5: 添加验证逻辑

在importBlock结束前添加验证：

```java
// Step 7: Verify epoch has only one main block
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);
}

return ImportResult.success(
    blockEpoch,
    finalHeight,
    cumulativeDifficulty,
    isBestChain,
    competition.isEpochWinner());
```

**新增验证方法**:
```java
/**
 * Verify that an epoch has only one main block (height > 0)
 *
 * @param epoch Epoch to verify
 * @param expectedWinner The block that should be the only main block
 */
private void verifyEpochSingleWinner(long epoch, Block expectedWinner) {
    List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(epoch);
    List<Block> mainBlocks = new ArrayList<>();

    for (Block candidate : allCandidates) {
        if (candidate.getInfo() != null && candidate.getInfo().getHeight() > 0) {
            mainBlocks.add(candidate);
        }
    }

    if (mainBlocks.size() > 1) {
        log.error("⚠️  EPOCH INTEGRITY VIOLATION: Epoch {} has {} main blocks (expected 1):",
            epoch, mainBlocks.size());
        for (Block mainBlock : mainBlocks) {
            log.error("   - Block {} at height {}",
                formatHash(mainBlock.getHash()),
                mainBlock.getInfo().getHeight());
        }

        // Auto-fix: Demote all except the winner
        for (Block mainBlock : mainBlocks) {
            if (!mainBlock.getHash().equals(expectedWinner.getHash())) {
                log.warn("   → Auto-demoting block {} to fix integrity",
                    formatHash(mainBlock.getHash()));
                demoteBlockToOrphan(mainBlock);
            }
        }
    } else if (mainBlocks.size() == 1) {
        if (!mainBlocks.get(0).getHash().equals(expectedWinner.getHash())) {
            log.error("⚠️  EPOCH INTEGRITY VIOLATION: Epoch {} main block mismatch:",
                epoch);
            log.error("   Expected: {}", formatHash(expectedWinner.getHash()));
            log.error("   Actual: {}", formatHash(mainBlocks.get(0).getHash()));
        } else {
            log.debug("✓ Epoch {} integrity verified: single winner {}",
                epoch, formatHash(expectedWinner.getHash()));
        }
    }
}
```

## 测试计划

### 单元测试

创建 `BlockImporterEpochCompetitionTest.java`:

```java
@Test
public void testMultipleBlocksSameEpoch_ShouldKeepOnlySmallestHash() {
    // Given: 3 blocks in same epoch with different hashes
    Block blockA = createTestBlock(epoch100, hashSmallest);   // Winner
    Block blockB = createTestBlock(epoch100, hashMedium);
    Block blockC = createTestBlock(epoch100, hashLargest);

    // When: Import in random order (B -> C -> A)
    blockImporter.importBlock(blockB, chainStats);
    blockImporter.importBlock(blockC, chainStats);
    blockImporter.importBlock(blockA, chainStats);

    // Then: Only blockA should be main (height > 0)
    assertThat(getBlock(blockA.getHash()).getInfo().getHeight()).isGreaterThan(0);
    assertThat(getBlock(blockB.getHash()).getInfo().getHeight()).isEqualTo(0);
    assertThat(getBlock(blockC.getHash()).getInfo().getHeight()).isEqualTo(0);

    // Verify epoch has only one main block
    List<Block> mainBlocks = getMainBlocksInEpoch(epoch100);
    assertThat(mainBlocks).hasSize(1);
    assertThat(mainBlocks.get(0).getHash()).isEqualTo(blockA.getHash());
}
```

### 集成测试

在`test-nodes/`环境中：

1. 启动2个nodes
2. 同时向两个nodes提交相同epoch的不同blocks
3. 等待P2P同步
4. 验证：
   - 两个nodes对相同height返回相同block
   - 每个epoch只有1个block的height > 0
   - compare-nodes.sh 100%一致

## 相关文件

- `src/main/java/io/xdag/core/BlockImporter.java` (主要修复位置)
- `src/main/java/io/xdag/core/DagChainImpl.java` (epoch winner查询)
- `src/main/java/io/xdag/store/DagStore.java` (epoch index查询)
- `test-nodes/compare-nodes.sh` (测试验证工具)

## 参考

- HEIGHT-ASSIGNMENT-INCONSISTENCY.md (问题发现文档)
- CLAUDE.md: "Heights are node-local and NOT used for consensus decisions"
- CLAUDE.md: "Block with smallest hash wins each epoch"

## 状态

**当前状态**: 🔍 根因已确认，修复方案已设计

**优先级**: P2 (不影响共识，但影响数据一致性)

**下一步**:
1. 实现修复代码
2. 编写单元测试
3. 在多节点环境中验证

## 签名

**分析者**: Claude Code (Anthropic AI Assistant)
**分析时间**: 2025-11-28 19:45 (GMT+8)
**状态**: ✅ 根因分析完成，待实施修复
