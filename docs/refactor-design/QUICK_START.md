# XDAG重构快速入门指南 (v5.1)

> **目标读者**: 开发者、AI助手（在API断开后快速恢复上下文）
> **最后更新**: 2025-10-29
> **版本**: v5.1
> **状态**: 核心设计完成，准备实施

---

## 🎯 5分钟快速理解

### 核心目标
将XDAG性能提升至**接近Visa级别**，支持 **23,200 TPS** (96.7% Visa水平)

### 关键数字（v5.1）
```
TPS性能:   7 → 23,200 (超越Bitcoin 3,314倍)
Block大小: 512字节 → 48MB (比Bitcoin大24倍)
确认时间:  1-2小时 → 6-13分钟 (Level 6确认)
同步时间:  数小时 → 20-30分钟 (10-15x)
存储优化:  100% → 60-75% (节省25-40%)
孤块存储:  575 GB → 5.76 GB (100x优化)
```

### 三大革命性创新
1. **极简架构**: 只有Transaction和Block两种类型（移除连接块）
2. **超高TPS**: Block只存hash引用（33字节），不存完整Transaction
3. **孤块防御**: 5层防御机制，100x存储优化

---

## 📐 核心设计参数（v5.1必记）

### Block大小和TPS
```java
// v5.1核心决策
MAX_BLOCK_SIZE = 48 * 1024 * 1024;    // 48MB软限制
TARGET_TPS = 23200;                   // 23,200 TPS (96.7% Visa)
MAX_LINKS_PER_BLOCK = 1485000;        // 约148万links

// TPS计算
TPS = 48MB / 33 bytes per link / 64秒
    = 1,485,000 txs / 64秒
    ≈ 23,200 TPS
```

### DAG引用限制（v5.1新增）
```java
// Block引用限制（防止DAG爆炸）
MIN_BLOCK_LINKS = 1;                  // 至少引用1个prevMainBlock
MAX_BLOCK_LINKS = 16;                 // 最多引用16个其他Blocks

// Transaction引用（支持超高TPS）
MAX_TX_LINKS = 无限制;                 // 最多1,485,000个（Block大小决定）
```

### 孤块防御（v5.1新增）
```java
// 5层防御机制
MAX_ORPHANS_PER_EPOCH = 10;           // 每epoch最多10个孤块
MAX_ROLLBACK_DEPTH = 12;              // 孤块回滚深度
MAX_INVALID_LINK_RATIO = 0.1;         // 最多10%无效引用容错

// 效果: 575 GB → 5.76 GB (100倍降低)
```

### Transaction data字段（v5.1决策）
```java
MAX_DATA_LENGTH = 1024;               // 1KB最大长度
BASE_DATA_LENGTH = 256;               // 256字节基础长度
DATA_FEE_MULTIPLIER = 1.0 / 256;      // 按字节收费

// 孤块Transaction处理
ORPHAN_TX_AUTO_MEMPOOL = true;        // 自动进入mempool（零成本）
```

### Finality参数（重要！）
```java
FINALITY_EPOCHS = 16384;              // ≈12天 (2^14)
MAX_REORG_DEPTH = 32768;              // ≈24天 (2^15)
```

**为什么是12天？**
- XDAG社区小，需要1-2周协调时间
- 给予足够时间处理网络分区等问题
- 攻击成功概率≈0

### 时间转换（重要！）
```java
// XDAG时间戳单位: (ms * 1024) / 1000
// 所以: 2 * 1024 XDAG units = 2 秒 (不是2048秒!)
// 1 epoch = 64秒
// 16384 epochs = 16384 * 64秒 ≈ 12.14天
```

---

## 🏗️ 核心架构（v5.1极简设计）

### 极简架构：只有2种类型

**关键洞察**：Block只存links（hash引用），不存完整Transaction！

```
XDAG v5.1只有2种块类型：

1️⃣ Transaction（独立类型）
   - 有签名（v, r, s）
   - 有nonce（账户模型）
   - 无timestamp（独立于Block）
   - 独立广播和存储

2️⃣ Block（候选块）
   - 有timestamp, difficulty, nonce
   - 有coinbase（矿工地址）
   - 有links（引用Transactions和Blocks）
   - 通过hash竞争成为主块
```

### 数据结构（v5.1）

#### Transaction（独立类型）
```java
public class Transaction {
    // 基础字段
    Bytes32 from;              // 发送方地址 (20 bytes for EVM)
    Bytes32 to;                // 接收方地址 (20 bytes for EVM)
    UInt256 amount;            // 转账金额
    UInt256 nonce;             // 账户nonce（防重放）
    UInt256 fee;               // Gas费用

    // Data字段（智能合约）
    byte[] data;               // 最多1KB，按字节收费

    // EVM兼容签名
    byte v;                    // Recovery ID
    UInt256 r;                 // ECDSA signature r
    UInt256 s;                 // ECDSA signature s

    // 可选字段
    String remark;             // 备注
}
```

#### Block（候选块）
```java
public class Block {
    // Header字段（参与hash计算）
    long timestamp;            // XDAG时间戳
    long epoch;                // timestamp / 64
    UInt256 difficulty;        // 难度目标
    UInt256 nonce;             // PoW nonce（所有Block都有）
    Bytes32 coinbase;          // 矿工地址
    Bytes32 maxDiffLink;       // 最大难度链接
    String remark;             // 备注（可选）

    // Body字段（引用）
    List<Link> links;          // 引用Transactions和Blocks
                               // Block引用: 1-16个
                               // Transaction引用: 最多148万个

    // 状态字段（不参与hash）
    Bytes32 hash;              // Block hash
    long height;               // 主链高度（0=孤块）
}
```

#### Link（DAG边，33字节）
```java
public class Link {
    Bytes32 targetHash;        // 32 bytes
    byte type;                 // 1 byte (0=Transaction, 1=Block)
}
```

### 架构优势

**超高TPS计算**：
```
48MB Block:
= 48MB / 33 bytes per link
≈ 1,485,000 links

TPS:
= 1,485,000 txs / 64秒
≈ 23,200 TPS (96.7% Visa水平)

关键点：
- Transaction的data字段大小不影响Block TPS
- 因为Block只存hash引用（33字节）
- data只影响Transaction存储和传播
```

**存储优化**：
- Block: ~193字节 (vs 512, 节省62%)
- Transaction: ~314字节（变长）
- 孤块: 5.76 GB (vs 575 GB, 100x优化)

---

## 🛡️ 5层孤块防御机制（v5.1核心）

### 问题
随节点增长，孤块存储爆炸：
- 1000节点 = 999个孤块/epoch × 48MB × 12 epochs = **575 GB** ❌

### 解决方案：5层防御

| 层级 | 防御措施 | 防止问题 |
|------|---------|---------|
| **Layer 1** | PoW验证 | 所有候选块必须满足难度 |
| **Layer 2** | 数量限制 ⭐ | 每epoch最多10个孤块（关键！） |
| **Layer 3** | 引用验证 | 最多10%无效引用容错 |
| **Layer 4** | 大小限制 | MAX_BLOCK_SIZE = 48MB |
| **Layer 5** | 节点信誉 | 追踪block质量，封禁恶意节点 |

**效果**：575 GB → 5.76 GB（**100倍降低**）✅

详见：[ORPHAN_BLOCK_ATTACK_DEFENSE.md](ORPHAN_BLOCK_ATTACK_DEFENSE.md)

---

## 🔄 混合同步协议（核心创新）

```
Timeline:
    0                H-16384                         H
    |----------------------|-------------------------|
    |  Finalized Chain     |    Active DAG          |
    |  (Linear Sync)       |    (DAG Sync)          |
    |  (12天前)            |    (最近12天)           |
    |----------------------|-------------------------|
```

**三阶段同步**:
1. **Phase 1**: 线性主链同步 (批量1000块/批，4x并行)
2. **Phase 2**: DAG区域同步 (按epoch查询，补全缺失)
3. **Phase 3**: Solidification (填补引用空缺)

**性能提升**: 数小时 → 20-30分钟 (**10-15x**)

详见：[HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md)

---

## 🛡️ 安全防护机制

### 6层DAG保护（防恶意攻击）

| 层级 | 防护措施 | 防止问题 |
|------|---------|---------|
| **Layer 1** | Visited Set | 循环引用 |
| **Layer 2** | 数量限制 | 恶意大DAG |
| **Layer 3** | 深度限制 | 深度攻击 |
| **Layer 4** | 时间窗口 | 时间异常 |
| **Layer 5** | 引用计数 | 过度引用 |
| **Layer 6** | 超时保护 | 无限挂起 |

详见：[DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md)

### 4层网络分区防御

| 层级 | 措施 | 说明 |
|------|------|------|
| **Layer 1** | 最大累积难度 | 已有机制 |
| **Layer 2** | Reorg深度限制 | 32768 epochs |
| **Layer 3** | Checkpoint | 可选 |
| **Layer 4** | 监控预警 | 人工干预 |

详见：[NETWORK_PARTITION_SOLUTION.md](NETWORK_PARTITION_SOLUTION.md)

---

## 🔑 关键设计决策

### 1. 固化 ≠ 线性化（重要！）

**错误理解**:
- ❌ 块被finalized后变成线性格式（像BTC）
- ❌ inputs/outputs被删除或压缩

**正确理解**:
- ✅ 块仍保持完整DAG结构
- ✅ 所有inputs/outputs链接保留
- ✅ 只是添加finalized标记
- ✅ 建立主链索引 (height → hash)

**为什么？**
- 签名验证需要完整内容
- 向后兼容（旧节点能理解）
- 审计友好（可追溯完整历史）

详见：[FINALIZED_BLOCK_STORAGE.md](FINALIZED_BLOCK_STORAGE.md)

### 2. 为什么Block大小是48MB？（v5.1决策）

**考虑方案**：
- 32MB: 15,625 TPS (65.1% Visa) - 保守
- **48MB: 23,200 TPS (96.7% Visa) - 最优** ⭐
- 64MB: 31,250 TPS (130% Visa) - 激进

**选择48MB的理由**：
- 性能：96.7% Visa水平（接近但不超越）
- 网络：3.84秒传播（< 6% epoch时间）
- 存储：23.1 TB/年（可接受）
- 验证：1.5秒（非常快）

详见：[CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md)

### 3. 为什么孤块Transaction自动进入mempool？（v5.1决策）

**选择"自动进入mempool"的理由**：
- 用户体验最优（零感知）
- 网络成本为零（无需重新广播）
- 存储成本可忽略（0.005%占比）
- 实现简单

详见：[CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md)

### 4. 为什么data字段是1KB？（v5.1决策）

**选择1KB + 按字节收费的理由**：
- 智能合约支持充足（EVM兼容）
- 不影响TPS（Block只存hash）
- 激励机制合理（按字节收费）
- 防止滥用（收费机制）

详见：[CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md)

### 5. 为什么DAG引用有限制？（v5.1）

**Block引用限制**（1-16个）：
- 防止恶意复杂DAG
- 16个足够所有合法场景
- 保持验证成本可控

**Transaction引用无限制**：
- 支持超高TPS（148万+）
- Block只存hash（轻量级）
- 由Block大小自然限制

详见：[DAG_REFERENCE_RULES.md](DAG_REFERENCE_RULES.md)

---

## 📚 文档导航（v5.1）

### 按角色阅读

#### 👨‍💻 开发者（第一次接触）
1. 先读本文档 (QUICK_START.md) - 5分钟 ⭐
2. 再读 [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) - 15分钟 ⭐⭐⭐
3. 深入 [CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md) - 30分钟 ⭐⭐

#### 🤖 AI助手（断线恢复上下文）
1. 先读 [CONTEXT_RECOVERY.md](CONTEXT_RECOVERY.md) - 快速恢复 ⭐⭐⭐
2. 再读本文档 (QUICK_START.md) - 关键参数 ⭐⭐
3. 查阅 [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) - 设计决策 ⭐

#### 🔒 安全审计
1. [ORPHAN_BLOCK_ATTACK_DEFENSE.md](ORPHAN_BLOCK_ATTACK_DEFENSE.md) - 孤块防御 ⭐⭐
2. [DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md) - 6层防护 ⭐⭐
3. [NETWORK_PARTITION_SOLUTION.md](NETWORK_PARTITION_SOLUTION.md) - 分区处理 ⭐
4. [FINALITY_ANALYSIS.md](FINALITY_ANALYSIS.md) - 安全性证明 ⭐

#### 🏗️ 实施团队（技术规格）
1. [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) - 数据结构 ⭐⭐⭐
2. [DAG_REFERENCE_RULES.md](DAG_REFERENCE_RULES.md) - 引用规则 ⭐⭐
3. [HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md) - 同步协议 ⭐⭐
4. [OVERALL_PROGRESS.md](OVERALL_PROGRESS.md) - 实施进度 ⭐

### 完整文档列表（v5.1）

| # | 文档 | 类型 | 用途 |
|---|------|------|------|
| 1 | [QUICK_START.md](QUICK_START.md) | 🚀 入门 | 5分钟快速理解 |
| 2 | [CONTEXT_RECOVERY.md](CONTEXT_RECOVERY.md) | 🤖 AI | 断线恢复上下文 |
| 3 | [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) | 🏗️ 核心 | v5.1核心设计 ⭐⭐⭐ |
| 4 | [CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md) | 📊 核心 | v5.1参数决策 ⭐⭐ |
| 5 | [ORPHAN_BLOCK_ATTACK_DEFENSE.md](ORPHAN_BLOCK_ATTACK_DEFENSE.md) | 🛡️ 安全 | 孤块防御机制 ⭐⭐ |
| 6 | [DAG_REFERENCE_RULES.md](DAG_REFERENCE_RULES.md) | 📐 规则 | DAG引用规则 ⭐⭐ |
| 7 | [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) | 📖 参考 | 设计决策汇总 |
| 8 | [HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md) | 🔄 技术 | 混合同步协议 |
| 9 | [DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md) | 🛡️ 安全 | 6层防护机制 |
| 10 | [FINALITY_ANALYSIS.md](FINALITY_ANALYSIS.md) | 🔬 分析 | 最终确定性 |
| 11 | [FINALIZED_BLOCK_STORAGE.md](FINALIZED_BLOCK_STORAGE.md) | 💾 存储 | 存储策略 |
| 12 | [NETWORK_PARTITION_SOLUTION.md](NETWORK_PARTITION_SOLUTION.md) | 🌐 网络 | 分区解决方案 |
| 13 | [OVERALL_PROGRESS.md](OVERALL_PROGRESS.md) | 📊 进度 | 实施进度报告 |
| 14 | [NAMING_CONVENTION.md](NAMING_CONVENTION.md) | 📝 规范 | 命名规范 |
| 15 | [README.md](README.md) | 📚 索引 | 文档总览 |

---

## 📊 性能指标（v5.1）

### 竞争力对比

| 区块链 | TPS | Block大小 | 确认时间 |
|--------|-----|-----------|---------|
| Bitcoin | 7 | 1-2MB | 60分钟 |
| Ethereum | 15-30 | ~100KB | 6-13分钟 |
| Visa | 24,000 | N/A | 秒级 |
| **XDAG v5.1** | **23,200** | **48MB** | **6-13分钟** |

### 性能提升

| 指标 | v5.1最终值 | 对比 | 评级 |
|------|-----------|------|------|
| **TPS** | 23,200 | Visa: 24,000 | ⭐⭐⭐⭐⭐ (96.7%) |
| **Block大小** | 48MB | BTC: 1-2MB | ⭐⭐⭐⭐⭐ (24-48x) |
| **确认时间** | 6-13分钟 | BTC: 60分钟 | ⭐⭐⭐⭐⭐ (10x faster) |
| **网络传播** | 3.84秒 | < 64秒epoch | ⭐⭐⭐⭐⭐ |
| **验证速度** | 1.5秒 | 非常快 | ⭐⭐⭐⭐⭐ |
| **孤块存储** | 5.76 GB | 0.025%占比 | ⭐⭐⭐⭐⭐ |
| **存储/年** | 23.1 TB | 可接受 | ⭐⭐⭐⭐ |

### 最终定位

```
XDAG v5.1 定位：
"接近Visa级别的高性能区块链DAG系统"

核心数据：
- 23,200 TPS（96.7% Visa水平）
- 6-13分钟确认（比Bitcoin快10倍）
- 48MB Block（比Bitcoin大24倍）
- 1KB智能合约data（EVM兼容）
- 零成本孤块恢复（独家优势）

竞争优势：
✅ 超越Bitcoin：3,314倍TPS
✅ 超越Ethereum：773-1,546倍TPS
✅ 接近Visa：96.7%性能水平
✅ 保留去中心化
✅ 智能合约就绪
```

---

## ⚠️ 常见误解（v5.1更新）

### ❌ 误解1: 固化后块变成线性格式
**正确理解**: 块仍保持完整DAG结构，不删除任何引用

### ❌ 误解2: 2048秒确认时间
**正确理解**: 是 `2 * 1024` XDAG时间单位 = 2秒 (不是2048秒!)

### ❌ 误解3: 单节点确认即不可逆
**正确理解**: 需要考虑全网共识，基于累积PoW难度

### ❌ 误解4: 12天太长影响用户体验
**正确理解**:
- 小额交易: Level 6确认 (~6-13分钟) 即可
- Finality主要用于同步协议优化
- 不影响日常交易确认体验

### ❌ 误解5: TPS受data字段大小影响
**正确理解**: Block只存hash（33字节），data大小不影响TPS

### ❌ 误解6: 引用数量无限制
**正确理解**: Block引用1-16个，Transaction引用无限制（由Block大小决定）

### ❌ 误解7: 需要存储所有孤块
**正确理解**: 每epoch最多10个，hash最小的优先（100x存储优化）

---

## 💡 快速查询

### Q: 如何计算epoch?
```java
epoch = timestamp / 64
```

### Q: 如何判断块是否finalized?
```java
// v5.1: 不再使用flags字段
boolean isFinalized = (currentHeight - blockHeight) >= 16384;
```

### Q: 主链索引如何查询?
```java
// RocksDB Column Family: MAIN_BLOCKS_INDEX (0x90)
Key:   [height (8 bytes)]
Value: [block_hash (32 bytes)]
```

### Q: 如何防止循环引用?
```java
Set<Bytes32> visited = new HashSet<>();
if (visited.contains(blockHash)) {
    return; // 跳过已访问的块
}
visited.add(blockHash);
```

### Q: Block能引用多少个Transaction?
```java
// v5.1关键洞察
MAX_TX_LINKS = 48MB / 33 bytes ≈ 1,485,000 txs
TPS = 1,485,000 / 64秒 ≈ 23,200 TPS
```

### Q: 孤块的Transaction如何处理?
```java
// v5.1决策: 自动进入mempool
if (block.height == 0) {  // 孤块
    for (Transaction tx : block.getTransactions()) {
        mempool.add(tx);  // 零成本，用户无感知
    }
}
```

---

## 📞 需要帮助？

### 文档问题
- 查阅 [README.md](README.md) 的完整索引
- 查找相关技术文档

### 设计问题
- 查阅 [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) - 设计决策
- 查阅 [CORE_PARAMETERS_DECISIONS.md](CORE_PARAMETERS_DECISIONS.md) - 参数决策

### 上下文丢失（AI断线）
- 立即阅读 [CONTEXT_RECOVERY.md](CONTEXT_RECOVERY.md)
- 快速恢复v5.1关键信息

### 安全审计
- 查阅 [ORPHAN_BLOCK_ATTACK_DEFENSE.md](ORPHAN_BLOCK_ATTACK_DEFENSE.md) - 孤块防御
- 查阅 [DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md) - DAG防护

---

**版本**: v5.1
**创建时间**: 2025-01
**最后更新**: 2025-10-29
**作者**: Claude Code
**状态**: v5.1核心设计完成
**适用对象**: 开发者、AI助手、审计人员、实施团队

---

## 🎉 v5.1核心成就

✅ **极简架构**: Transaction + Block（2种类型）
✅ **超高TPS**: 23,200（96.7% Visa水平）
✅ **Block大小**: 48MB（平衡性能、存储、网络）
✅ **孤块防御**: 5层机制，100x存储优化
✅ **智能合约**: 1KB data字段，EVM兼容
✅ **零成本恢复**: 孤块Transaction自动进入mempool

**下一步**: 开始Phase 6实施，架构清理与XDAG 2.0协议升级
