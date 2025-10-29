# XDAG重构上下文恢复指南 (AI专用)

> **目标**: 当AI API断开连接后，快速恢复项目上下文
> **阅读时间**: 3-5分钟
> **最后更新**: 2025-10-29 (v5.1)

---

## 🚨 你是谁？你在哪？

你是一个AI助手，正在协助XDAG (DAG-based blockchain)的重构项目。

**项目路径**: `/Users/reymondtu/dev/github/xdagj`
**文档目录**: `/Users/reymondtu/dev/github/xdagj/docs/refactor-design/`
**Git分支**: `refactor/integrate-xdagj-p2p`

---

## 🎯 项目状态: v5.1设计完成

### 当前阶段
✅ **Phase 1-5 全部完成** (100%)
✅ **v5.1核心设计完成** (100%)
⏳ **Phase 6 部分完成** (6.3, 6.4, 6.7)

### 最近工作 (2025-10-29)
- ✅ 核心数据结构v5.1设计完成
- ✅ 三大核心参数决策完成（48MB, auto-mempool, 1KB+fees）
- ✅ 孤块攻击防御机制设计完成（5层防御，100x存储优化）
- ✅ 文档简化和一致性review完成

---

## 🔢 关键数字（v5.1，必须记住）

### v5.1核心参数
```java
// Block大小
MAX_BLOCK_SIZE = 48 * 1024 * 1024;    // 48MB软限制
TARGET_TPS = 23200;                   // 23,200 TPS (96.7% Visa)
MAX_LINKS_PER_BLOCK = 1485000;        // 约148万links

// DAG引用限制
MIN_BLOCK_LINKS = 1;                  // 至少引用1个prevMainBlock
MAX_BLOCK_LINKS = 16;                 // 最多引用16个其他Blocks
MAX_TX_LINKS = 无限制;                 // Transaction引用无限制

// 孤块防御
MAX_ORPHANS_PER_EPOCH = 10;           // 每epoch最多10个孤块
MAX_INVALID_LINK_RATIO = 0.1;         // 最多10%无效引用

// Transaction data
MAX_DATA_LENGTH = 1024;               // 1KB最大长度
DATA_FEE_MULTIPLIER = 1.0 / 256;      // 按字节收费

// 孤块Transaction处理
ORPHAN_TX_AUTO_MEMPOOL = true;        // 自动进入mempool
```

### Finality参数
```java
FINALITY_EPOCHS = 16384      // ≈12天 (2^14)
MAX_REORG_DEPTH = 32768      // ≈24天 (2^15)
MAX_ROLLBACK_DEPTH = 12      // 孤块回滚深度
```

### 时间转换（易错！）
```java
// XDAG时间戳: (ms * 1024) / 1000
// 2 * 1024 XDAG units = 2秒 (不是2048秒!)
// 1 epoch = 64秒
// 16384 epochs = 1048576秒 ≈ 12.14天
```

### 性能指标 (v5.1)
```
TPS:        23,200 (96.7% Visa水平)
Block大小:  48MB (比Bitcoin大24倍)
确认时间:   6-13分钟 (Level 6，比Bitcoin快10倍)
网络传播:   3.84秒 (优秀)
验证速度:   1.5秒 (非常快)
存储/年:    23.1 TB (可接受)
```

---

## 📚 文档结构（v5.1，快速导航）

### 入门文档 (优先阅读)
1. **QUICK_START.md** - 5分钟快速理解全局 ⚠️ (需要更新到v5.1)
2. **本文档 (CONTEXT_RECOVERY.md)** - AI上下文恢复 ✅ (已更新到v5.1)
3. **README.md** - 项目总索引和文档导航 ✅

### 核心设计文档 (v5.1)
4. **CORE_DATA_STRUCTURES.md** ⭐⭐⭐ - 核心数据结构v5.1设计
5. **CORE_PARAMETERS_DECISIONS.md** ⭐⭐ - 三大核心参数决策
6. **DAG_REFERENCE_RULES.md** ⭐⭐ - DAG引用规则和限制
7. **ORPHAN_BLOCK_ATTACK_DEFENSE.md** ⭐⭐ - 孤块攻击防御机制

### 安全设计文档
8. **DAG_SYNC_PROTECTION.md** - 6层防护机制
9. **FINALITY_ANALYSIS.md** - 最终确定性分析（12天）
10. **NETWORK_PARTITION_SOLUTION.md** - 网络分区解决方案

### 技术细节文档
11. **HYBRID_SYNC_PROTOCOL.md** - 混合同步协议
12. **FINALIZED_BLOCK_STORAGE.md** - 存储策略（固化≠线性化）
13. **OVERALL_PROGRESS.md** - 总体进度报告

### 辅助文档
14. **DESIGN_DECISIONS.md** - 设计决策汇总（已更新到v5.1）
15. **NAMING_CONVENTION.md** - 命名规范
16. **CONSISTENCY_CHECK_REPORT.md** - 一致性检查报告

---

## 🧠 核心概念（v5.1，必须理解）

### 1. 极简架构（v5.1核心创新）

**关键洞察**：Block只存links（hash引用），不存完整数据！

```
XDAG v5.1只有2种块类型：

1️⃣ Transaction（独立类型）
   - 有签名（v, r, s）
   - 有nonce（账户模型）
   - 无timestamp
   - 独立广播和存储

2️⃣ Block（候选块）
   - 有timestamp, difficulty, nonce
   - 有coinbase（矿工地址）
   - 有links（引用Transactions和Blocks）
   - 通过hash竞争成为主块
```

### 2. 超高TPS（v5.1优势）

**关键洞察**：Block只存Transaction hash（33字节），不存完整Transaction！

```
48MB Block容量：
= 48MB / 33 bytes per link
≈ 1,485,000 links

TPS计算：
= 1,485,000 txs / 64秒
≈ 23,200 TPS
✅ 96.7% Visa水平！

关键点：
- Transaction的data字段大小不影响Block TPS
- 因为Block只存hash引用
- data只影响Transaction存储和传播
```

### 3. 孤块攻击防御（v5.1新增）

**问题**：随节点增长，孤块存储爆炸
- 1000节点 = 999个孤块/epoch × 48MB × 12 epochs = 575 GB ❌

**解决**：5层防御机制
```
Layer 1: PoW验证 - 必须满足难度
Layer 2: 数量限制 - 每epoch最多10个孤块 ⭐ 关键！
Layer 3: 引用验证 - 最多10%无效引用容错
Layer 4: 大小限制 - MAX_BLOCK_SIZE = 48MB
Layer 5: 节点信誉 - 追踪block质量，封禁恶意节点

效果：575 GB → 5.76 GB（100倍降低）✅
```

### 4. 混合同步协议（核心创新）

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

### 5. 固化 ≠ 线性化（重要！）

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

---

## 🗂️ 关键设计决策（v5.1更新）

### 决策1: Block大小（v5.1）
**问题**: 32MB、48MB还是64MB？
**决策**: 48MB软限制
**理由**: 23,200 TPS（96.7% Visa），性能、存储、网络平衡最优
**文档**: CORE_PARAMETERS_DECISIONS.md

### 决策2: 孤块Transaction处理（v5.1）
**问题**: 孤块的Transaction如何处理？
**决策**: 自动进入mempool
**理由**: 零网络成本，用户体验最优，存储成本可忽略（0.005%）
**文档**: CORE_PARAMETERS_DECISIONS.md

### 决策3: Transaction data字段（v5.1）
**问题**: data字段最大长度？
**决策**: 1KB + 按字节收费
**理由**: 智能合约支持充足，不影响TPS（Block只存hash）
**文档**: CORE_PARAMETERS_DECISIONS.md

### 决策4: DAG引用限制（v5.1）
**问题**: Block引用数量是否限制？
**决策**: Block引用1-16个，Transaction引用无限制
**理由**: 防止DAG爆炸，支持超高TPS
**文档**: DAG_REFERENCE_RULES.md

### 决策5: Finality参数
**问题**: 如何确定最终确定性边界？
**决策**: 16384 epochs (≈12天)
**理由**: 社区小、需要协调时间、安全性高
**文档**: FINALITY_ANALYSIS.md

---

## ⚠️ 常见错误（v5.1更新）

### 错误1: TPS理解错误
```
❌ 错误: data字段越大，TPS越低
✅ 正确: Block只存hash，data大小不影响TPS
```
**说明**: 48MB Block → 23,200 TPS（无论data是256字节还是1KB）

### 错误2: 引用数量理解错误
```
❌ 错误: 最多20个链接
✅ 正确: Block引用1-16个，Transaction引用100万+
```
**说明**: v5.1区分Block引用和Transaction引用

### 错误3: 孤块存储理解错误
```
❌ 错误: 需要存储所有孤块
✅ 正确: 每epoch最多10个，hash最小的
```
**说明**: 575 GB → 5.76 GB（100倍降低）

### 错误4: 时间单位理解错误
```
❌ 错误: 2 * 1024 = 2048秒
✅ 正确: 2 * 1024 XDAG timestamp units = 2秒
```
**说明**: XDAG时间戳 = (ms * 1024) / 1000

### 错误5: Finality理解错误
```
❌ 错误: 单节点主块确认 = 不可逆
✅ 正确: 需要全网共识，基于累积PoW难度
```
**说明**: 需要16384个主块深度才能保证不可逆

---

## 🔍 快速查找信息（v5.1）

### 需要代码示例？
→ CORE_DATA_STRUCTURES.md（核心设计）
→ ORPHAN_BLOCK_ATTACK_DEFENSE.md（防御实现）
→ DAG_REFERENCE_RULES.md（引用验证）

### 需要了解实施步骤？
→ OVERALL_PROGRESS.md（Phase 1-6进度）
→ README.md（项目总览）

### 需要了解设计原因？
→ DESIGN_DECISIONS.md（决策汇总）
→ CORE_PARAMETERS_DECISIONS.md（参数决策详解）
→ FINALITY_ANALYSIS.md（为什么12天）

### 需要了解性能数据？
→ CORE_PARAMETERS_DECISIONS.md（性能指标）
→ CORE_DATA_STRUCTURES.md（容量分析）

### 需要了解安全机制？
→ ORPHAN_BLOCK_ATTACK_DEFENSE.md（5层防御）
→ DAG_SYNC_PROTECTION.md（6层防护）
→ DAG_REFERENCE_RULES.md（引用规则）
→ NETWORK_PARTITION_SOLUTION.md（4层防御）

---

## 🎬 对话恢复场景（v5.1）

### 场景1: 用户问"现在进度如何？"
**回答模板**:
```
✅ Phase 1-5 全部完成（100%）
✅ v5.1核心设计完成：
   - 核心数据结构（Transaction + Block极简架构）
   - 三大核心参数（48MB, auto-mempool, 1KB+fees）
   - 孤块攻击防御（5层防御，100x存储优化）

🔄 Phase 6 部分完成（6.3, 6.4, 6.7）

📊 测试状态：334/334 passing (100%)

下一步：继续Phase 6架构清理
```

### 场景2: 用户问"v5.1有什么核心参数？"
**回答模板**:
```
v5.1三大核心参数决策：

1️⃣ Block大小：48MB软限制
   - TPS: 23,200（96.7% Visa水平）
   - 网络传播: 3.84秒
   - 存储: 23.1 TB/年

2️⃣ 孤块Transaction：自动进入mempool
   - 零网络成本
   - 用户体验最优
   - 存储成本可忽略（0.005%）

3️⃣ data字段：1KB + 按字节收费
   - 智能合约支持充足
   - 不影响TPS（Block只存hash）
   - 激励机制合理

详见: CORE_PARAMETERS_DECISIONS.md
```

### 场景3: 用户问"如何防止孤块攻击？"
**回答模板**:
```
v5.1孤块攻击防御机制：

问题：1000节点 = 575 GB孤块存储 ❌

解决：5层防御机制

Layer 1: PoW验证 - 所有候选块必须满足难度
Layer 2: 数量限制 ⭐ - 每epoch最多10个孤块（关键！）
Layer 3: 引用验证 - 最多10%无效引用容错
Layer 4: 大小限制 - MAX_BLOCK_SIZE = 48MB
Layer 5: 节点信誉 - 追踪block质量，封禁恶意节点

效果：575 GB → 5.76 GB（100倍降低）✅

详见: ORPHAN_BLOCK_ATTACK_DEFENSE.md
```

### 场景4: 用户问"v5.1竞争力如何？"
**回答模板**:
```
XDAG v5.1最终定位：
"接近Visa级别的高性能区块链DAG系统"

核心数据：
- TPS: 23,200（96.7% Visa水平）
- Block: 48MB（比Bitcoin大24倍）
- 确认: 6-13分钟（比Bitcoin快10倍）
- Data: 1KB（智能合约就绪）

竞争优势：
✅ 超越Bitcoin：3,314倍TPS
✅ 超越Ethereum：773-1,546倍TPS
✅ 接近Visa：96.7%性能水平
✅ 独家优势：零成本孤块恢复

详见: CORE_PARAMETERS_DECISIONS.md
```

### 场景5: 用户问"DAG引用有什么限制？"
**回答模板**:
```
v5.1 DAG引用规则：

1️⃣ 严格禁止环状引用
   - 使用Visited Set检测环
   - 包含环的Block被拒绝

2️⃣ 时间窗口限制
   - 只能引用最近12天内的Blocks
   - 与finality参数对齐

3️⃣ Block引用数量限制
   - 最少1个（prevMainBlock）
   - 最多16个其他Blocks
   - Transaction引用无限制（100万+）

4️⃣ 遍历深度限制
   - 最大1000层

5️⃣ 被引用次数
   - 无限制（正常DAG特性）

详见: DAG_REFERENCE_RULES.md
```

---

## 📋 对话恢复检查清单（v5.1）

当API断开重连后，快速回答以下问题以恢复上下文：

- [ ] **项目是什么？** XDAG重构，v5.1核心设计完成
- [ ] **当前阶段？** Phase 1-5完成，v5.1设计完成
- [ ] **v5.1核心参数？** 48MB, 23,200 TPS, auto-mempool, 1KB+fees
- [ ] **TPS水平？** 23,200（96.7% Visa水平）
- [ ] **Block大小？** 48MB软限制
- [ ] **孤块防御？** 5层防御，100x存储优化
- [ ] **DAG引用限制？** Block 1-16个，TX 无限制
- [ ] **Finality参数？** 16384 epochs ≈ 12天
- [ ] **固化格式？** 保持完整DAG结构（固化≠线性化）
- [ ] **文档位置？** docs/refactor-design/ (19个核心文档)

全部回答"是" → 上下文恢复成功！

---

## 🔗 快速链接（v5.1）

### 最常访问的文档
1. [README.md](README.md) - 项目总索引 ✅
2. [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) - 核心设计v5.1 ⭐⭐⭐
3. [CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md) - 参数决策v5.1 ⭐⭐
4. [OVERALL_PROGRESS.md](OVERALL_PROGRESS.md) - 总体进度 ⭐⭐⭐

### 最常查询的参数（v5.1）
```java
// v5.1核心参数
MAX_BLOCK_SIZE = 48 * 1024 * 1024;    // 48MB
TARGET_TPS = 23200;                   // 96.7% Visa
MAX_BLOCK_LINKS = 16;                 // Block引用限制
MAX_ORPHANS_PER_EPOCH = 10;           // 孤块数量限制
MAX_DATA_LENGTH = 1024;               // 1KB

// Finality
FINALITY_EPOCHS = 16384               // ≈12天
MAX_REORG_DEPTH = 32768               // ≈24天

// 时间转换
1 epoch = 64秒
16384 epochs = 1048576秒 ≈ 12.14天
```

---

## 🆘 紧急问题快速查询表（v5.1）

| 问题 | 答案 | 详见文档 |
|------|------|---------|
| v5.1核心参数？ | 48MB, 23,200 TPS, 1KB+fees | CORE_PARAMETERS_DECISIONS.md |
| TPS是多少？ | 23,200（96.7% Visa） | CORE_DATA_STRUCTURES.md |
| Block大小？ | 48MB软限制 | CORE_PARAMETERS_DECISIONS.md |
| 孤块如何防御？ | 5层防御，100x优化 | ORPHAN_BLOCK_ATTACK_DEFENSE.md |
| DAG引用限制？ | Block 1-16个，TX 无限制 | DAG_REFERENCE_RULES.md |
| Finality是多少？ | 16384 epochs ≈ 12天 | FINALITY_ANALYSIS.md |
| 为什么是12天？ | 社区小、需要协调时间 | FINALITY_ANALYSIS.md |
| 固化后变成什么格式？ | 保持完整DAG结构 | FINALIZED_BLOCK_STORAGE.md |
| 如何防止循环引用？ | 6层防护机制 | DAG_SYNC_PROTECTION.md |
| 当前进度？ | Phase 1-5完成，v5.1设计完成 | OVERALL_PROGRESS.md |

---

## ✅ 上下文恢复完成确认（v5.1）

如果你能回答以下问题，说明上下文已成功恢复：

1. **v5.1核心设计完成了什么？**
   → 核心数据结构、三大参数决策、孤块攻击防御

2. **v5.1的TPS是多少？占Visa多少？**
   → 23,200 TPS，96.7% Visa水平

3. **v5.1的Block大小决策是什么？**
   → 48MB软限制，平衡性能、存储、网络

4. **孤块攻击如何防御？**
   → 5层防御机制，存储从575 GB降到5.76 GB（100倍）

5. **DAG引用有什么限制？**
   → Block引用1-16个，Transaction引用无限制

**全部正确 → 恢复成功！可以继续工作。**

---

**版本**: v5.1
**创建时间**: 2025-01
**最后更新**: 2025-10-29
**作者**: Claude Code
**目标**: 帮助AI快速恢复项目上下文到v5.1状态
**使用频率**: 每次API断开重连后阅读

**记住**: 这不是给人类阅读的，是给AI恢复记忆用的！
