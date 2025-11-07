# Blockchain 接口命名改进方案

**日期**: 2025-11-05
**问题**: `getBlockByHeight`, `getBlocksByEpoch`, `getMainBlockByEpoch` 三个方法容易混淆
**原因**: Height 和 Epoch 不是 1:1 映射，导致语义不清

---

## 问题分析

### 核心混淆点

**Height（主链位置）** vs **Epoch（时间段）** 是两个独立概念：

```
主链序列（Height）:  [Block_A(98)] → [Block_B(99)] → [Block_C(100)]
                         ↓              ↓                ↓
Epoch 时间段:         Epoch 98      Epoch 101         Epoch 102
```

**问题场景**：
- Epoch 99 没有块 → Height 跳过 99
- Epoch 100 有块但都是孤块 → Height 也跳过 100
- Height 和 Epoch 编号不对应，容易误解

### 当前方法的歧义

| 方法 | 歧义点 | 用户可能的误解 |
|-----|--------|---------------|
| `getBlockByHeight(100)` | "height" 可能被理解为 "epoch" | 认为返回 Epoch 100 的块 |
| `getBlocksByEpoch(100)` | 返回孤块列表（可能为空） | 不清楚是否包含主块 |
| `getMainBlockByEpoch(100)` | 可能返回 null | 与 `getBlockByHeight(100)` 关系不清 |

---

## 改进方案 A：重命名方法（推荐）

### 核心思想
用更精确的命名区分"主链位置"和"Epoch 时间段"两个维度。

### 新命名对比

| 旧方法 | 新方法 | 改进说明 |
|-------|-------|---------|
| `getBlockByHeight(long height)` | `getMainBlockAtPosition(long position)` | "position" 更清晰表达"主链序号" |
| `getBlocksByEpoch(long epoch)` | `getCandidateBlocksInEpoch(long epoch)` | "candidate" 明确包含所有竞争块 |
| `getMainBlockByEpoch(long epoch)` | `getWinnerBlockInEpoch(long epoch)` | "winner" 明确表达"获胜者" |

### 新接口设计

```java
// ==================== 主链查询（Position-based 维度） ====================

/**
 * 获取主链上第 N 个主块（按主链位置）
 *
 * <p>主链是由所有 epoch 获胜块按时间顺序组成的链。
 * Position 是主块在主链中的序号，从 1 开始计数。
 *
 * <p><strong>重要</strong>: Position 和 Epoch 不是 1:1 映射！
 * 某些 epoch 可能没有块，或者所有块都是孤块，导致主链跳过这些 epoch。
 *
 * <h3>示例</h3>
 * <pre>
 * Epoch 98:  有块，Block_A 获胜 → position=1
 * Epoch 99:  没有块              → 主链跳过
 * Epoch 100: 有块但都是孤块      → 主链跳过
 * Epoch 101: 有块，Block_B 获胜 → position=2
 *
 * getMainBlockAtPosition(1) → Block_A (来自 Epoch 98)
 * getMainBlockAtPosition(2) → Block_B (来自 Epoch 101)
 * </pre>
 *
 * @param position 主链位置（1-based，position=1 是创世块后的第一个主块）
 * @return 主链上第 N 个主块，如果位置无效返回 null
 * @see #getMainChainLength() 获取主链总长度
 * @see #getEpochOfMainBlock(long) 查询某个主块所在的 epoch
 */
Block getMainBlockAtPosition(long position);

/**
 * 获取主链总长度（主块总数）
 *
 * <p>返回当前主链中主块的总数量。
 * 等价于最新主块的 position 值。
 *
 * @return 主链长度（主块总数）
 */
long getMainChainLength();

/**
 * 查询主块所在的 epoch（Position → Epoch 映射）
 *
 * <p>给定主链位置，返回该主块所在的 epoch 编号。
 *
 * <h3>示例</h3>
 * <pre>
 * getEpochOfMainBlock(1) → 98  (第1个主块在 Epoch 98)
 * getEpochOfMainBlock(2) → 101 (第2个主块在 Epoch 101)
 * </pre>
 *
 * @param position 主链位置（1-based）
 * @return epoch 编号，如果位置无效返回 -1
 * @throws IllegalArgumentException 如果 position <= 0
 */
long getEpochOfMainBlock(long position);


// ==================== Epoch 查询（Time-based 维度） ====================

/**
 * 获取指定 epoch 内的所有候选块
 *
 * <p>返回在指定 epoch 时间段内创建的所有块，包括：
 * <ul>
 *   <li>获胜块（如果存在）- hash 最小的块</li>
 *   <li>失败块 - 其他未获胜的候选块</li>
 *   <li>孤块 - 未被引用的块</li>
 * </ul>
 *
 * <p>Epoch 时间段 = [epoch * 64, (epoch + 1) * 64) 秒
 *
 * <h3>示例</h3>
 * <pre>
 * Epoch 100 有 3 个候选块：
 * - Block_X (hash=0x123..., 孤块)
 * - Block_Y (hash=0x456..., 孤块)
 * - Block_Z (hash=0x789..., 孤块)
 *
 * getCandidateBlocksInEpoch(100) → [Block_X, Block_Y, Block_Z]
 * getWinnerBlockInEpoch(100)     → null (都是孤块，没有获胜者)
 * </pre>
 *
 * @param epoch epoch 编号 (timestamp / 64)
 * @return 该 epoch 内的所有候选块列表（可能为空）
 * @see #getWinnerBlockInEpoch(long) 获取获胜块
 */
List<Block> getCandidateBlocksInEpoch(long epoch);

/**
 * 获取指定 epoch 的获胜块
 *
 * <p>返回指定 epoch 内 hash 最小的块（如果存在且不是孤块）。
 *
 * <p><strong>获胜条件</strong>:
 * <ol>
 *   <li>块在该 epoch 时间段内创建</li>
 *   <li>块的 hash 是该 epoch 所有候选块中最小的</li>
 *   <li>块不是孤块（被其他块引用或成为主块）</li>
 * </ol>
 *
 * <p>如果 epoch 没有块，或所有块都是孤块，返回 null。
 *
 * <h3>示例</h3>
 * <pre>
 * Epoch 98:  有块，Block_A (hash 最小) → 返回 Block_A
 * Epoch 99:  没有块                   → 返回 null
 * Epoch 100: 有块但都是孤块           → 返回 null
 * Epoch 101: 有块，Block_B (hash 最小) → 返回 Block_B
 * </pre>
 *
 * @param epoch epoch 编号 (timestamp / 64)
 * @return 该 epoch 的获胜块，如果没有获胜块返回 null
 * @see #getCandidateBlocksInEpoch(long) 获取所有候选块
 */
Block getWinnerBlockInEpoch(long epoch);

/**
 * 查询 epoch 的获胜块在主链中的位置（Epoch → Position 映射）
 *
 * <p>给定 epoch 编号，返回该 epoch 获胜块在主链中的位置。
 * 如果该 epoch 没有获胜块，返回 -1。
 *
 * <h3>示例</h3>
 * <pre>
 * getPositionOfWinnerBlock(98)  → 1  (Epoch 98 的获胜块是第1个主块)
 * getPositionOfWinnerBlock(99)  → -1 (Epoch 99 没有块)
 * getPositionOfWinnerBlock(100) → -1 (Epoch 100 都是孤块)
 * getPositionOfWinnerBlock(101) → 2  (Epoch 101 的获胜块是第2个主块)
 * </pre>
 *
 * @param epoch epoch 编号
 * @return 主链位置（1-based），如果没有获胜块返回 -1
 */
long getPositionOfWinnerBlock(long epoch);

/**
 * 获取当前 epoch 编号
 *
 * <p>Epoch 计算公式: {@code epoch = currentTimestamp / 64}
 *
 * @return 当前 epoch 编号
 */
long getCurrentEpoch();

/**
 * 获取指定 epoch 的时间范围
 *
 * <p>返回 [startTime, endTime) 时间区间。
 * - startTime = epoch * 64
 * - endTime = (epoch + 1) * 64
 *
 * @param epoch epoch 编号
 * @return 两元素数组 [startTime, endTime) (XDAG timestamp 格式)
 */
long[] getEpochTimeRange(long epoch);

/**
 * 获取指定 epoch 的统计信息
 *
 * <p>返回该 epoch 的详细统计，包括：
 * - 候选块总数
 * - 获胜块 hash
 * - 平均出块时间
 * - 累积难度变化
 *
 * @param epoch epoch 编号
 * @return epoch 统计信息
 */
EpochStats getEpochStats(long epoch);
```

---

## 改进方案 B：保留旧名但增强文档（备选）

如果不想改名（避免破坏性变更），可以保留旧方法名但增强文档说明：

```java
/**
 * 获取主块（按主链位置）
 *
 * <p><strong>⚠️ 重要</strong>: height 参数表示"主链位置"，不是 epoch 编号！
 *
 * <p>主链位置和 epoch 不是 1:1 映射。某些 epoch 可能没有主块（空 epoch 或孤块 epoch），
 * 导致主链会跳过这些 epoch。
 *
 * <h3>示例对比</h3>
 * <table>
 *   <tr>
 *     <th>主链位置 (height)</th>
 *     <th>对应的 Epoch</th>
 *     <th>说明</th>
 *   </tr>
 *   <tr>
 *     <td>1</td>
 *     <td>98</td>
 *     <td>第1个主块在 Epoch 98</td>
 *   </tr>
 *   <tr>
 *     <td>2</td>
 *     <td>101</td>
 *     <td>第2个主块在 Epoch 101（跳过了 99, 100）</td>
 *   </tr>
 * </table>
 *
 * <p>如果需要查询特定 epoch 的获胜块，请使用 {@link #getMainBlockByEpoch(long)}。
 *
 * @param height 主链位置（1-based，不是 epoch 编号）
 * @return 主块，如果位置无效返回 null
 * @see #getMainBlockByEpoch(long) 按 epoch 查询获胜块
 */
Block getBlockByHeight(long height);

/**
 * 获取 epoch 内的所有候选块
 *
 * <p>返回指定 epoch 时间段内的所有块（包括主块、失败块、孤块）。
 *
 * <p><strong>与 {@link #getMainBlockByEpoch(long)} 的区别</strong>:
 * <ul>
 *   <li>本方法返回所有候选块（List）</li>
 *   <li>{@link #getMainBlockByEpoch(long)} 只返回获胜块（单个 Block 或 null）</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>
 * Epoch 100 有 3 个候选块但都是孤块:
 * getBlocksByEpoch(100)     → [Block_X, Block_Y, Block_Z]
 * getMainBlockByEpoch(100)  → null (没有获胜者)
 * </pre>
 *
 * @param epoch epoch 编号 (timestamp / 64)
 * @return 该 epoch 的所有候选块（可能为空列表）
 * @see #getMainBlockByEpoch(long) 获取获胜块
 */
List<Block> getBlocksByEpoch(long epoch);

/**
 * 获取 epoch 的获胜块
 *
 * <p>返回该 epoch 内 hash 最小且不是孤块的块。
 * 如果 epoch 没有块或所有块都是孤块，返回 null。
 *
 * <p><strong>与 {@link #getBlockByHeight(long)} 的区别</strong>:
 * <ul>
 *   <li>本方法按 epoch 查询，可能返回 null（某些 epoch 没有获胜块）</li>
 *   <li>{@link #getBlockByHeight(long)} 按主链位置查询，跳过空 epoch</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>
 * Epoch 98:  有获胜块 → getMainBlockByEpoch(98) = Block_A
 * Epoch 99:  没有块  → getMainBlockByEpoch(99) = null
 * Epoch 100: 都是孤块 → getMainBlockByEpoch(100) = null
 * Epoch 101: 有获胜块 → getMainBlockByEpoch(101) = Block_B
 *
 * 但主链位置：
 * getBlockByHeight(1) → Block_A (来自 Epoch 98)
 * getBlockByHeight(2) → Block_B (来自 Epoch 101)
 * </pre>
 *
 * @param epoch epoch 编号 (timestamp / 64)
 * @return 该 epoch 的获胜块，如果没有返回 null
 * @see #getBlocksByEpoch(long) 获取所有候选块
 * @see #getBlockByHeight(long) 按主链位置查询
 */
Block getMainBlockByEpoch(long epoch);
```

---

## 推荐方案：方案 A（重命名）

### 优点
1. ✅ **语义清晰**: Position vs Epoch, Candidate vs Winner 一目了然
2. ✅ **减少误用**: 方法名直接表达意图，不需要查文档
3. ✅ **类型安全**: Winner 返回单个 Block，Candidate 返回 List
4. ✅ **易于理解**: 新用户不需要理解 XDAG 内部机制就能正确使用

### 迁移路径

使用 `@Deprecated` 保持向后兼容：

```java
/**
 * @deprecated Use {@link #getMainBlockAtPosition(long)} instead.
 * The term "height" can be confused with "epoch" in XDAG's DAG structure.
 */
@Deprecated
default Block getBlockByHeight(long height) {
    return getMainBlockAtPosition(height);
}

/**
 * @deprecated Use {@link #getCandidateBlocksInEpoch(long)} instead.
 * Renamed for clarity (all blocks in epoch are candidates).
 */
@Deprecated
default List<Block> getBlocksByEpoch(long epoch) {
    return getCandidateBlocksInEpoch(epoch);
}

/**
 * @deprecated Use {@link #getWinnerBlockInEpoch(long)} instead.
 * Renamed for clarity (winner = smallest hash in epoch).
 */
@Deprecated
default Block getMainBlockByEpoch(long epoch) {
    return getWinnerBlockInEpoch(epoch);
}
```

### 新增辅助方法（解决映射问题）

为了解决 Position ↔ Epoch 映射问题，新增两个方法：

```java
/**
 * Position → Epoch 映射
 */
long getEpochOfMainBlock(long position);

/**
 * Epoch → Position 映射
 */
long getPositionOfWinnerBlock(long epoch);
```

---

## 对比总结

| 方案 | 优点 | 缺点 | 推荐度 |
|-----|------|------|-------|
| **方案 A: 重命名** | 语义清晰，易于理解 | 需要迁移旧代码 | ⭐⭐⭐⭐⭐ |
| **方案 B: 增强文档** | 无破坏性变更 | 依然容易误用 | ⭐⭐⭐ |

---

## 实现建议

如果采用方案 A，修改步骤：

1. **Phase 1: 添加新方法**（不破坏现有代码）
   ```java
   Block getMainBlockAtPosition(long position);
   List<Block> getCandidateBlocksInEpoch(long epoch);
   Block getWinnerBlockInEpoch(long epoch);
   long getEpochOfMainBlock(long position);
   long getPositionOfWinnerBlock(long epoch);
   ```

2. **Phase 2: 标记旧方法为 @Deprecated**
   ```java
   @Deprecated Block getBlockByHeight(long height);
   @Deprecated List<Block> getBlocksByEpoch(long epoch);
   @Deprecated Block getMainBlockByEpoch(long epoch);
   ```

3. **Phase 3: 在实现中使用新方法**
   - BlockchainImpl 内部逐步迁移到新方法
   - 保留旧方法作为 wrapper（调用新方法）

4. **Phase 4: 文档和示例更新**
   - 更新所有示例代码使用新方法
   - 添加迁移指南

---

**审查完成**: 2025-11-05
**建议**: 采用方案 A（重命名），提供清晰的 API 和迁移路径
