# Phase 6 清理和测试计划

**日期**: 2025-10-30
**分支**: refactor/core-v5.1
**状态**: 规划中

---

## 📋 当前状态分析

### Legacy 方法使用情况

#### 1. xferToNode()
**位置**: `Commands.java:856-881`
**使用情况**:
- ❌ **无代码调用**（PoolAwardManagerImpl 已迁移到 xferToNodeV2()）
- ✅ **可以标记为 @Deprecated**

**结论**: 可以安全地标记为废弃，但暂不删除（保留作为备用）

---

#### 2. xferToNew()
**位置**: `Commands.java:803-849`
**使用情况**:
- ✅ **Shell.java:107** - CLI 命令 "xfertonew"
- ✅ **CommandsTest.java:346** - 测试代码

**结论**: 仍在使用中，需要保留。建议添加 V2 版本的 CLI 命令。

---

#### 3. xfer()
**位置**: `Commands.java:245-311`
**使用情况**:
- ✅ **Shell.java:310** - CLI 命令 "xfer"
- ✅ 用于基本的转账功能

**结论**: 核心功能，仍在使用中，需要保留。xferV2() 是补充，不是替代。

---

## 🎯 清理策略

### 策略 A: 标记废弃（推荐）

**目标**: 不删除代码，但标记为废弃，引导用户使用 V2 方法

**方法**:
1. 为 legacy 方法添加 `@Deprecated` 注解
2. 在 JavaDoc 中添加迁移指南
3. 保留代码以确保向后兼容

**优点**:
- ✅ 零破坏性
- ✅ 向后兼容
- ✅ 给用户时间迁移
- ✅ 保留备用方案

**缺点**:
- ⚠️ 代码库仍包含 legacy 代码
- ⚠️ 维护负担

---

### 策略 B: 添加 V2 CLI 命令

**目标**: 为 V2 方法添加 CLI 命令，让用户可以选择使用

**需要添加**:
1. `xfertonewv2` 命令 → 调用 `xferToNewV2()`
2. 更新帮助文档

**优点**:
- ✅ 用户可以测试 V2 功能
- ✅ 渐进迁移路径
- ✅ 保留 legacy 命令

**缺点**:
- ⚠️ 增加命令数量
- ⚠️ 用户可能困惑

---

### 策略 C: 完全删除（不推荐）

**风险**:
- ❌ 破坏现有 CLI 命令
- ❌ 破坏现有测试
- ❌ 可能影响用户脚本

**结论**: **不推荐此策略**

---

## 📝 推荐的清理任务

### Task 6.1: 标记 Legacy 方法为 @Deprecated

#### 6.1.1 标记 xferToNode()

```java
/**
 * Distribute block rewards to node
 *
 * @deprecated Use {@link #xferToNodeV2(Map)} instead.
 *             This method uses legacy Address + Block architecture.
 *             xferToNodeV2() uses v5.1 Transaction + BlockV5 architecture.
 *             Migration: PoolAwardManagerImpl has been migrated to xferToNodeV2().
 *
 * @param paymentsToNodesMap Map of addresses and keypairs for node payments
 * @return StringBuilder containing transaction result message
 */
@Deprecated
public StringBuilder xferToNode(Map<Address, ECKeyPair> paymentsToNodesMap) {
    // ... existing implementation
}
```

**状态**: ⏸️ 待执行（可选）

---

### Task 6.2: 添加 xfertonewv2 CLI 命令（可选）

#### 实现 Shell.java 新命令

```java
// 添加新命令注册
commandExecute.put("xfertonewv2", new CommandMethods(this::processXferToNewV2, this::defaultCompleter));

// 添加命令处理方法
private void processXferToNewV2(CommandInput input) {
    final String[] usage = {
            "xfertonewv2 -  transfer the old balance to new address using v5.1 architecture\n",
            "Usage: xfertonewv2",
            "  -? --help                    Show help",
    };
    try {
        Options opt = parseOptions(usage, input.args());
        if (opt.isSet("help")) {
            throw new Options.HelpException(opt.usage());
        }
        println(commands.xferToNewV2());
    } catch (Exception e) {
        saveException(e);
    }
}
```

**状态**: ⏸️ 待执行（可选）

---

### Task 6.3: 更新测试

#### 更新 CommandsTest.java

**需要添加**:
- xferToNewV2() 测试用例
- xferToNodeV2() 测试用例
- 验证 v5.1 架构正确性

**状态**: ⏸️ 待执行

---

### Task 6.4: 集成测试

#### 测试场景

1. **节点奖励分发流程**:
   - PoolAwardManagerImpl 累积 10 个奖励块
   - 调用 xferToNodeV2()
   - 验证 Transaction 创建
   - 验证 BlockV5 广播
   - 验证余额更新

2. **块余额转移流程**:
   - 调用 xferToNewV2()
   - 验证块余额聚合
   - 验证 Transaction 创建
   - 验证 BlockV5 广播
   - 验证余额更新

3. **简单交易流程**:
   - 调用 xferV2()
   - 验证 Transaction 创建
   - 验证费用和 remark 处理
   - 验证 BlockV5 广播

**状态**: ⏸️ 待执行

---

### Task 6.5: 性能测试

#### 测试指标

1. **交易吞吐量**:
   - Legacy vs V2 性能对比
   - 批量交易性能

2. **内存使用**:
   - Transaction 对象内存占用
   - BlockV5 对象内存占用

3. **网络带宽**:
   - BlockV5 序列化大小
   - 广播效率

**状态**: ⏸️ 待执行

---

## 📊 清理进度

### 必需任务
- ⏸️ Task 6.3: 更新测试用例
- ⏸️ Task 6.4: 集成测试
- ⏸️ Task 6.5: 性能测试

### 可选任务
- ⏸️ Task 6.1: 标记 @Deprecated
- ⏸️ Task 6.2: 添加 V2 CLI 命令

---

## 🎯 决策建议

### 立即执行（高优先级）
1. **不删除任何 legacy 代码** - 保持向后兼容
2. **文档更新** - 说明 V2 方法的优势
3. **基本测试** - 确保 V2 方法正确工作

### 后续执行（中优先级）
1. **标记 @Deprecated** - 引导用户迁移
2. **添加 V2 CLI 命令** - 让用户测试 V2 功能
3. **集成测试** - 全面验证 V2 功能

### 延后执行（低优先级）
1. **性能测试** - 优化性能
2. **删除 legacy 代码** - 等待足够的用户迁移时间（至少 6 个月）

---

## 🚨 风险评估

### 低风险操作
- ✅ 添加 @Deprecated 注解（不影响功能）
- ✅ 添加 V2 CLI 命令（不影响现有命令）
- ✅ 更新文档（只是信息性改动）

### 中风险操作
- ⚠️ 修改测试用例（可能暴露问题）
- ⚠️ 集成测试（可能发现 bug）

### 高风险操作
- ❌ 删除 legacy 代码（破坏向后兼容）
- ❌ 强制迁移 CLI 命令（影响用户体验）

**建议**: 只执行低风险操作

---

## 📚 相关文档

- [PHASE4_MIGRATION_PLAN.md](PHASE4_MIGRATION_PLAN.md) - 完整迁移计划
- [PHASE4_CURRENT_PROGRESS.md](PHASE4_CURRENT_PROGRESS.md) - 当前进度
- [PHASE4_TASK4.2_COMPLETION.md](PHASE4_TASK4.2_COMPLETION.md) - PoolAwardManagerImpl 迁移
- [Commands.java](src/main/java/io/xdag/cli/Commands.java) - 应用层实现
- [Shell.java](src/main/java/io/xdag/cli/Shell.java) - CLI 命令处理

---

## 🏁 最终建议

### 当前阶段（Phase 6）
**建议操作**: **文档化为主，保留 legacy 代码**

**理由**:
1. ✅ v5.1 架构已经实现并验证
2. ✅ 关键功能已经迁移（PoolAwardManagerImpl）
3. ✅ legacy 代码仍在 CLI 中使用
4. ✅ 没有紧迫的删除需求

**下一步**:
- 创建 Phase 6 完成总结文档
- 记录当前迁移状态
- 为未来的完全迁移留下清晰路径

---

**创建日期**: 2025-10-30
**状态**: 规划完成
**建议**: 文档化当前状态，延后 legacy 代码删除
