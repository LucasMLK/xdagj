# XDAG重构上下文恢复指南 (AI专用)

> **目标**: 当AI API断开连接后，快速恢复项目上下文
> **阅读时间**: 3-5分钟
> **最后更新**: 2025-01

---

## 🚨 你是谁？你在哪？

你是一个AI助手，正在协助XDAG (DAG-based blockchain)的重构项目。

**项目路径**: `/Users/reymondtu/dev/github/xdagj`
**文档目录**: `/Users/reymondtu/dev/github/xdagj/docs/refactor-design/`
**Git分支**: `refactor/integrate-xdagj-p2p`

---

## 🎯 项目状态: 设计完成，准备实施

### 当前阶段
✅ **设计阶段完成** (100%)
⏳ **实施阶段未开始** (0%)

### 最近工作
- ✅ 完成9个核心设计文档
- ✅ 确定所有关键参数
- ✅ 设计安全防护机制
- ✅ 创建AI友好文档

---

## 🔢 关键数字（必须记住）

### Finality参数
```java
FINALITY_EPOCHS = 16384      // ≈12天 (2^14)
MAX_REORG_DEPTH = 32768      // ≈24天 (2^15)
```

### 安全限制
```java
MAX_INPUTS = 16              // 最多16个inputs
MAX_OUTPUTS = 16             // 最多16个outputs
MAX_TOTAL_LINKS = 20         // 总链接不超过20
MAX_SOLIDIFY_BLOCKS = 50000  // 单次固化最多5万块
MAX_RECURSION_DEPTH = 500    // 最大递归深度
```

### 时间转换（易错！）
```java
// XDAG时间戳: (ms * 1024) / 1000
// 2 * 1024 XDAG units = 2秒 (不是2048秒!)
// 1 epoch = 64秒
// 16384 epochs = 1048576秒 ≈ 12.14天
```

### 性能目标
```
同步时间:  数小时 → 20-30分钟 (10-15x)
写入TPS:   200 → 5000+ (25x)
存储空间:  100% → 40-60% (节省40-60%)
```

---

## 📚 文档结构（快速导航）

### 入门文档 (优先阅读)
1. **QUICK_START.md** - 5分钟快速理解全局
2. **本文档 (CONTEXT_RECOVERY.md)** - AI上下文恢复

### 核心设计文档
3. **REFACTOR_PLAN.md** - 完整重构计划（6-10周）
4. **NEW_DAG_DATA_STRUCTURE.md** - 新数据结构（变长序列化）
5. **HYBRID_SYNC_PROTOCOL.md** - 混合同步协议（10-15x提升）

### 安全设计文档
6. **DAG_SYNC_PROTECTION.md** - 6层防护机制
7. **FINALITY_ANALYSIS.md** - 最终确定性分析（12天）
8. **NETWORK_PARTITION_SOLUTION.md** - 网络分区解决方案

### 技术细节文档
9. **FINALIZED_BLOCK_STORAGE.md** - 存储策略（固化≠线性化）
10. **NEW_DAG_DATA_STRUCTURE_UPDATES.md** - 数据结构更新说明
11. **UPDATE_SUMMARY.md** - 更新总结

### 辅助文档
12. **DESIGN_DECISIONS.md** - 设计决策汇总（查询为什么）
13. **IMPLEMENTATION_CHECKLIST.md** - 实施检查清单
14. **README.md** - 文档总索引

---

## 🧠 核心概念（必须理解）

### 1. 混合同步协议（核心创新）

```
Timeline:
    0                H-16384                         H
    |----------------------|-------------------------|
    |  Finalized Chain     |    Active DAG          |
    |  (Linear Sync)       |    (DAG Sync)          |
    |  (12天前)            |    (最近12天)           |
    |----------------------|-------------------------|
```

**关键点**:
- 利用finality边界分割同步策略
- 已固化主链: 线性同步（批量1000块/次，4x并行）
- 动态DAG: DAG同步（按epoch查询）
- Solidification: 补全缺失引用

**性能**: 10-15倍提升

### 2. 固化 ≠ 线性化（重要！）

**错误理解**: ❌
- 块被finalized后变成线性格式
- inputs/outputs被删除

**正确理解**: ✅
- 块仍保持完整DAG结构
- 所有inputs/outputs链接保留
- 只是添加`BI_FINALIZED`标志
- 建立主链索引 (height → hash)

**原因**:
- 签名验证需要完整内容
- 向后兼容
- 审计友好

### 3. 为什么12天Finality？

**背景**: XDAG使用PoW共识，没有明确finality

**选择12天的原因**:
1. **社区规模小**: 需要1-2周时间协调
2. **安全性**: 攻击概率 ≈ (0.4/0.6)^16384 ≈ 0
3. **网络分区**: 给予时间手动处理
4. **比较保守**: 对比BTC(60min), ETH(6min), 12天很长但安全

**对用户影响**:
- 小额交易: 64确认 (~1.1小时) 即可
- Finality只影响同步协议优化
- 不影响日常交易确认体验

### 4. 6层DAG保护机制

防止恶意DAG攻击 (循环引用、深度攻击、资源耗尽):

| 层级 | 防护 | 防止问题 |
|------|------|---------|
| Layer 1 | Visited Set | 循环引用 |
| Layer 2 | 数量限制 | 恶意大DAG |
| Layer 3 | 深度限制 | 深度攻击 |
| Layer 4 | 时间窗口 | 时间异常 |
| Layer 5 | 引用计数 | 过度引用 |
| Layer 6 | 超时保护 | 无限挂起 |

---

## 🗂️ 关键设计决策（查询用）

### 决策1: Finality参数
**问题**: 如何确定最终确定性边界？
**决策**: 16384 epochs (≈12天)
**理由**: 社区小、需要协调时间、安全性高
**文档**: FINALITY_ANALYSIS.md

### 决策2: 存储格式
**问题**: 固化后的块如何存储？
**决策**: 保持完整DAG结构
**理由**: 签名验证、向后兼容、审计友好
**文档**: FINALIZED_BLOCK_STORAGE.md

### 决策3: 引用数量限制
**问题**: 是否限制inputs/outputs数量？
**决策**: 是，最多20个链接
**理由**: 防止循环引用、恶意DAG攻击
**文档**: DAG_SYNC_PROTECTION.md, NEW_DAG_DATA_STRUCTURE.md

### 决策4: 同步协议
**问题**: 如何提升同步速度10-15倍？
**决策**: 混合同步（线性+DAG）
**理由**: 利用finality、批量并行、减少网络往返
**文档**: HYBRID_SYNC_PROTOCOL.md

### 决策5: 数据结构
**问题**: 如何减少存储空间？
**决策**: 变长序列化（300-400字节）
**理由**: 节省25-40%空间、更灵活
**文档**: NEW_DAG_DATA_STRUCTURE.md

---

## ⚠️ 常见错误（必须避免）

### 错误1: 时间单位理解错误
```
❌ 错误: 2 * 1024 = 2048秒
✅ 正确: 2 * 1024 XDAG timestamp units = 2秒
```
**说明**: XDAG时间戳 = (ms * 1024) / 1000

### 错误2: Finality理解错误
```
❌ 错误: 单节点主块确认 = 不可逆
✅ 正确: 需要全网共识，基于累积PoW难度
```
**说明**: 需要16384个主块深度才能保证不可逆

### 错误3: 存储格式理解错误
```
❌ 错误: finalized后变成线性格式
✅ 正确: 仍保持完整DAG结构
```
**说明**: 只是加了BI_FINALIZED标志

### 错误4: 引用数量理解错误
```
❌ 错误: 无限制引用数量
✅ 正确: 最多20个链接
```
**说明**: 防止恶意DAG攻击

### 错误5: 性能提升来源错误
```
❌ 错误: 只是数据结构优化
✅ 正确: 存储+数据结构+同步协议三方面优化
```
**说明**: 10-15倍提升来自综合优化

---

## 🔍 快速查找信息

### 需要代码示例？
→ 查阅相应技术文档（HYBRID_SYNC_PROTOCOL.md、NEW_DAG_DATA_STRUCTURE.md等）

### 需要了解实施步骤？
→ IMPLEMENTATION_CHECKLIST.md（分步清单）
→ REFACTOR_PLAN.md（Phase 1-4路线图）

### 需要了解设计原因？
→ DESIGN_DECISIONS.md（决策汇总）
→ FINALITY_ANALYSIS.md（为什么12天）
→ DAG_SYNC_PROTECTION.md（为什么6层防护）

### 需要了解性能数据？
→ REFACTOR_PLAN.md（核心改进汇总）
→ HYBRID_SYNC_PROTOCOL.md（性能分析）

### 需要了解安全机制？
→ DAG_SYNC_PROTECTION.md（6层防护）
→ NETWORK_PARTITION_SOLUTION.md（4层防御）

---

## 🎬 对话恢复场景

### 场景1: 用户问"现在进度如何？"
**回答模板**:
```
设计阶段已完成100%，包括：
✅ 9个核心设计文档
✅ 所有关键参数确定
✅ 安全机制设计完成

实施阶段未开始(0%)，计划6-10周完成。

下一步: Phase 1 - 存储层重构 (2-3周)
```

### 场景2: 用户问"Finality是多少？"
**回答模板**:
```
FINALITY_EPOCHS = 16384 (≈12天)
MAX_REORG_DEPTH = 32768 (≈24天)

原因:
- XDAG社区小，需要1-2周协调时间
- 攻击成功概率≈0
- 不影响日常交易（小额64确认~1.1小时）

详见: FINALITY_ANALYSIS.md
```

### 场景3: 用户问"固化后块变成什么格式？"
**回答模板**:
```
固化 ≠ 线性化！

块仍保持完整DAG结构：
✅ 所有inputs/outputs保留
✅ 完整签名数据保留
✅ 只是添加BI_FINALIZED标志
✅ 建立主链索引

原因: 签名验证、向后兼容、审计友好

详见: FINALIZED_BLOCK_STORAGE.md
```

### 场景4: 用户问"如何开始实施？"
**回答模板**:
```
实施路线图 (6-10周):

Phase 1: 存储层重构 (2-3周)
- Week 1: 核心索引和批量写入
- Week 2: 缓存和Bloom Filter
- Week 3: 兼容层和迁移工具

Phase 2: 数据结构优化 (1-2周)
Phase 3: 混合同步协议 (2-3周)
Phase 4: 测试和部署 (1-2周)

详见: IMPLEMENTATION_CHECKLIST.md, REFACTOR_PLAN.md
```

### 场景5: 用户问"如何防止循环引用？"
**回答模板**:
```
6层DAG保护机制:

Layer 1: Visited Set - 防循环引用
Layer 2: 数量限制 - 防恶意大DAG (MAX_SOLIDIFY_BLOCKS=50000)
Layer 3: 深度限制 - 防深度攻击 (MAX_RECURSION_DEPTH=500)
Layer 4: 时间窗口 - 防时间异常
Layer 5: 引用计数 - 防过度引用 (MAX_TOTAL_LINKS=20)
Layer 6: 超时保护 - 防无限挂起

代码示例见: DAG_SYNC_PROTECTION.md
```

---

## 📋 对话恢复检查清单

当API断开重连后，快速回答以下问题以恢复上下文：

- [ ] **项目是什么？** XDAG重构，目标10-15倍性能提升
- [ ] **当前阶段？** 设计完成，准备实施
- [ ] **Finality参数？** 16384 epochs ≈ 12天
- [ ] **核心创新？** 混合同步协议（线性+DAG）
- [ ] **关键限制？** MAX_TOTAL_LINKS=20, MAX_SOLIDIFY_BLOCKS=50000
- [ ] **时间转换？** 2*1024 XDAG units = 2秒 (不是2048秒!)
- [ ] **固化格式？** 保持完整DAG结构 (固化≠线性化)
- [ ] **文档位置？** docs/refactor-design/ (12个文档)
- [ ] **下一步？** Phase 1: 存储层重构
- [ ] **性能目标？** 同步20-30分钟，TPS 5000+，存储节省40-60%

全部回答"是" → 上下文恢复成功！

---

## 🔗 快速链接

### 最常访问的文档
1. [QUICK_START.md](QUICK_START.md) - 快速入门
2. [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) - 设计决策
3. [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md) - 实施清单

### 最常查询的参数
```java
// Finality
FINALITY_EPOCHS = 16384      // ≈12天
MAX_REORG_DEPTH = 32768      // ≈24天

// 安全限制
MAX_INPUTS = 16
MAX_OUTPUTS = 16
MAX_TOTAL_LINKS = 20
MAX_SOLIDIFY_BLOCKS = 50000
MAX_RECURSION_DEPTH = 500

// 时间转换
1 epoch = 64秒
16384 epochs = 1048576秒 ≈ 12.14天
```

### 最常见的代码路径
```
当前代码:
  src/main/java/io/xdag/core/BlockchainImpl.java  # 共识
  src/main/java/io/xdag/core/XdagTime.java        # 时间转换

需要创建:
  src/main/java/io/xdag/core/DagNode.java         # 新数据结构
  src/main/java/io/xdag/sync/HybridSyncCoordinator.java  # 混合同步
  src/main/java/io/xdag/sync/protection/SafeDAGTraversal.java  # 安全遍历
```

---

## 🆘 紧急问题快速查询表

| 问题 | 答案 | 详见文档 |
|------|------|---------|
| Finality是多少？ | 16384 epochs ≈ 12天 | FINALITY_ANALYSIS.md |
| 为什么是12天？ | 社区小、需要协调时间 | FINALITY_ANALYSIS.md |
| 固化后变成什么格式？ | 保持完整DAG结构 | FINALIZED_BLOCK_STORAGE.md |
| 如何防止循环引用？ | 6层防护机制 | DAG_SYNC_PROTECTION.md |
| 同步为什么快10-15倍？ | 混合同步+批量并行 | HYBRID_SYNC_PROTOCOL.md |
| 引用数量有限制吗？ | 是，最多20个链接 | NEW_DAG_DATA_STRUCTURE.md |
| 2*1024是多少秒？ | 2秒 (不是2048秒!) | QUICK_START.md |
| 如何开始实施？ | Phase 1: 存储层重构 | IMPLEMENTATION_CHECKLIST.md |
| 当前进度？ | 设计完成，准备实施 | 本文档 |
| 性能目标？ | 同步20-30分钟, TPS 5000+ | REFACTOR_PLAN.md |

---

## ✅ 上下文恢复完成确认

如果你能回答以下问题，说明上下文已成功恢复：

1. **项目目标是什么？**
   → 性能提升10-15倍，支持10000+ TPS

2. **Finality参数是多少？为什么？**
   → 16384 epochs ≈ 12天，因为社区小、需要协调时间

3. **固化后块是什么格式？**
   → 保持完整DAG结构，不是线性格式

4. **如何防止恶意DAG？**
   → 6层防护：Visited Set、数量限制、深度限制、时间窗口、引用计数、超时保护

5. **下一步要做什么？**
   → Phase 1: 存储层重构 (2-3周)

**全部正确 → 恢复成功！可以继续工作。**

---

**版本**: v1.0
**创建时间**: 2025-01
**作者**: Claude Code
**目标**: 帮助AI快速恢复项目上下文
**使用频率**: 每次API断开重连后阅读

**记住**: 这不是给人类阅读的，是给AI恢复记忆用的！
