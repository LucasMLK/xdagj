# XDAG 重构设计文档

> **策略更新 (2025-01)**: 完全重构，追求最佳性能和架构
>
> **升级方式**: 通过快照导出/导入数据，暂停网络升级
>
> **关键发现**: BlockInfo（原文件名 `ImprovedBlockInfo.java`）已存在且完整！BLOCK_HEIGHT 索引已存在！可直接使用！

## 概述

本目录包含 XDAG 完整重构方案的详细设计文档，目标是实现 **10-15倍性能提升**，支持 **10000+ TPS**。

**核心策略**:
- ✅ **完全移除** SUMS（减少75%写入）
- ✅ **直接使用** BlockInfo（原文件名 `ImprovedBlockInfo.java`，已完整实现）
- ✅ **直接使用** BLOCK_HEIGHT 索引（已存在）
- ✅ **快照迁移** 通过导出/导入实现
- ✅ **不考虑向后兼容** 追求最佳实现

## 🎯 核心目标

| 指标 | 当前 | 目标 | 提升 |
|------|------|------|------|
| **同步时间** | 数小时 | 20-30分钟 | **10-15x** |
| **写入TPS** | 200 | 5000+ | **25x** |
| **读取延迟** | 2ms | 0.2ms | **10x** |
| **存储空间** | 100% | 40-60% | **节省40-60%** |

## 📚 文档列表（按类型）

### 🚀 入门文档（优先阅读）

1. **[QUICK_START.md](QUICK_START.md)** - 快速入门指南 ⭐⭐⭐
   - 5分钟快速理解全局
   - 核心参数和关键数字
   - 常见误解解答
   - AI友好格式

2. **[CONTEXT_RECOVERY.md](CONTEXT_RECOVERY.md)** - AI上下文恢复 🤖
   - 3分钟恢复项目上下文
   - 关键数字速查
   - 对话恢复场景
   - 紧急问题查询表

3. **[PRE_IMPLEMENTATION_REVIEW.md](PRE_IMPLEMENTATION_REVIEW.md)** - 实施前综合分析 🔍
   - 代码库现状分析
   - BlockInfo（原 ImprovedBlockInfo）发现
   - 文档与现实的差距
   - 调整后的实施策略

4. **[BLOCKINFO_ANALYSIS.md](BLOCKINFO_ANALYSIS.md)** - BlockInfo 详细分析 ⭐
   - BlockInfo（原 ImprovedBlockInfo）完整性验证
   - 当前使用情况（零使用）
   - 重命名和集成策略
   - 工作量评估

5. **[NAMING_CONVENTION.md](NAMING_CONVENTION.md)** - 命名规范 ⭐ 新增
   - 符合 DAG + 区块链特性的命名体系
   - 核心类命名：Block, BlockInfo, BlockLink, ChainStats, Snapshot
   - 重命名映射表
   - 命名原则和最佳实践

### 📋 规划文档

6. **[REFACTOR_PLAN.md](REFACTOR_PLAN.md)** - 完整重构计划 ⭐ 已更新
   - **先数据结构，后存储**（正确的实施顺序）
   - 完全重构策略（不考虑兼容性）
   - 核心命名体系（Block, BlockInfo, BlockLink, ChainStats, Snapshot）
   - 总体架构对比
   - 实施路线图（5.5-7.5周）
   - 快照迁移方案

7. **[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md)** - 设计决策汇总
   - 10个关键决策
   - 每个决策的原因、权衡和替代方案
   - 快速查询为什么这样设计

8. **[IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md)** - 实施检查清单 ✅ 已更新
   - Phase 0: 准备和分析（新增）
   - 分步实施指导
   - 每周检查点
   - 验收标准
   - 风险和缓解

### 🏗️ 核心技术文档

9. **[NEW_DAG_DATA_STRUCTURE.md](NEW_DAG_DATA_STRUCTURE.md)** - 新DAG数据结构
   - 变长序列化：300-400字节（节省25-40%）
   - 安全限制：最多20个链接
   - BI_FINALIZED 标志支持
   - **注**: 实际使用 BlockInfo（原 ImprovedBlockInfo）

10. **[HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md)** - 混合同步协议 ⭐
    - 12天finality边界
    - 线性主链同步 + DAG区域同步
    - 10-15倍性能提升
    - **完全替换** SUMS（不兼容）

### 🛡️ 安全设计文档

11. **[DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md)** - DAG同步保护机制
    - 6层防护：防循环、防恶意DAG
    - BFS遍历 + 多重验证
    - 参数配置建议

12. **[NETWORK_PARTITION_SOLUTION.md](NETWORK_PARTITION_SOLUTION.md)** - 网络分区解决方案
    - 分层防御策略
    - Reorg深度限制：32768 epochs（≈24天）
    - BTC对比分析

13. **[FINALITY_ANALYSIS.md](FINALITY_ANALYSIS.md)** - 最终确定性分析
    - Finality参数：16384 epochs（≈12天）
    - 安全性证明
    - 用户确认等级建议

### 💾 存储设计文档

14. **[FINALIZED_BLOCK_STORAGE.md](FINALIZED_BLOCK_STORAGE.md)** - 已固化块存储策略
    - 固化 ≠ 线性化
    - 保持完整DAG结构
    - 三种存储策略对比

## 🚀 快速开始（按角色）

### 👨‍💻 开发者（第一次接触项目）
1. **5分钟快速理解**: [QUICK_START.md](QUICK_START.md) ⭐
2. **了解命名规范**: [NAMING_CONVENTION.md](NAMING_CONVENTION.md) ⭐ 必读
3. **了解代码现状**: [PRE_IMPLEMENTATION_REVIEW.md](PRE_IMPLEMENTATION_REVIEW.md) 🔍
4. **BlockInfo 分析**: [BLOCKINFO_ANALYSIS.md](BLOCKINFO_ANALYSIS.md)
5. **15分钟了解全局**: [REFACTOR_PLAN.md](REFACTOR_PLAN.md)
6. **30分钟深入技术**: [HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md)
7. **开始实施**: [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md)

### 🤖 AI助手（断线后恢复上下文）
1. **立即阅读**: [CONTEXT_RECOVERY.md](CONTEXT_RECOVERY.md) 🚨
2. **快速参考**: [QUICK_START.md](QUICK_START.md)
3. **命名规范**: [NAMING_CONVENTION.md](NAMING_CONVENTION.md) ⭐
4. **代码现状**: [PRE_IMPLEMENTATION_REVIEW.md](PRE_IMPLEMENTATION_REVIEW.md) + [BLOCKINFO_ANALYSIS.md](BLOCKINFO_ANALYSIS.md)
5. **查询决策**: [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md)
6. **查看清单**: [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md)

### 🔍 项目负责人（想了解整体方案）
1. **核心目标和效果**: 本文档 (README.md) - 概览
2. **完整重构计划**: [REFACTOR_PLAN.md](REFACTOR_PLAN.md) - 路线图
3. **设计决策**: [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) - 为什么这样设计

### 🔒 安全审计人员
1. **安全机制总览**: [DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md) - 6层防护
2. **最终确定性分析**: [FINALITY_ANALYSIS.md](FINALITY_ANALYSIS.md) - 安全性证明
3. **网络分区处理**: [NETWORK_PARTITION_SOLUTION.md](NETWORK_PARTITION_SOLUTION.md) - 4层防御
4. **设计决策**: [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) - 安全相关决策

### 🏗️ 实施团队（准备开始编码）
1. **命名规范**: [NAMING_CONVENTION.md](NAMING_CONVENTION.md) ⭐ 第一个要读
2. **实施清单**: [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md) ⭐ 分步指导
3. **代码现状**: [PRE_IMPLEMENTATION_REVIEW.md](PRE_IMPLEMENTATION_REVIEW.md) 必读！
4. **BlockInfo 集成**: [BLOCKINFO_ANALYSIS.md](BLOCKINFO_ANALYSIS.md) 必读！
5. **技术规格**:
   - [NEW_DAG_DATA_STRUCTURE.md](NEW_DAG_DATA_STRUCTURE.md) - 数据结构（注意：实际使用 BlockInfo）
   - [HYBRID_SYNC_PROTOCOL.md](HYBRID_SYNC_PROTOCOL.md) - 同步协议
   - [DAG_SYNC_PROTECTION.md](DAG_SYNC_PROTECTION.md) - 保护机制

## 🔑 核心设计决策

### 1. 完全重构策略 ⭐ 新增

**升级方式**: 快照导出/导入 + 暂停网络

**理由**:
- 不需要向后兼容，可以追求最佳架构
- 可以完全移除 SUMS，减少 75% 写入
- 实施简化，周期缩短 1-2 周

### 2. Finality参数

```java
FINALITY_EPOCHS = 16384  // ≈12天 (2^14)
MAX_REORG_DEPTH = 32768  // ≈24天 (2^15)
```

**理由**：XDAG社区规模小，需要1-2周协调时间处理网络分区等问题

### 3. 安全限制

```java
MAX_INPUTS = 16                // 最多16个inputs
MAX_OUTPUTS = 16               // 最多16个outputs
MAX_TOTAL_LINKS = 20           // 总链接不超过20（或保持15？Phase 0 决定）
MAX_SOLIDIFY_BLOCKS = 50000    // 单次固化最多5万块
```

**理由**：防止循环引用、恶意深度DAG、过度引用等攻击

### 4. 存储策略

**核心结论**：块被标记为 `finalized` 后，**仍保持完整的DAG结构**

**理由**：
- 签名验证需要完整内容
- 审计友好
- **不需要兼容性** - 可以彻底优化存储格式

### 5. 混合同步

```
Timeline:
    0                H-16384                         H
    |----------------------|-------------------------|
    |  Finalized Chain     |    Active DAG          |
    |  (Linear Sync)       |    (DAG Sync)          |
    |  (12天前)            |    (最近12天)           |
    |----------------------|-------------------------|
```

**三阶段**：
1. 线性主链同步（批量并行，1000块/批）- **完全替换 SUMS**
2. DAG区域同步（按epoch查询）
3. Solidification（补全缺失块）

## 📊 预期效果

### 性能提升

- ✅ 同步速度：数小时 → 20-30分钟（**10-15x**）
- ✅ 写入TPS：200 → 5000+（**25x**）
- ✅ 读取延迟：2ms → 0.2ms（**10x**）
- ✅ 存储空间：节省 **40-60%**

### 功能目标

- ✅ 支持 **10000+ TPS**
- ✅ 新节点 **20-30分钟**完成同步
- ✅ DAG完整性 **>99%**（Solidification）
- ✅ 同步进度**可预测**
- ✅ **平滑**数据迁移
- ✅ 网络分区**自动保护**

## 🛠️ 实施路线图

**调整**: 总周期 **5.5-7.5 周**（简化后，原计划 6-10 周）
**关键**: 先数据结构，后存储（存储依赖数据结构）

### Phase 0: 准备和分析（0.5-1周）⭐ 新增
- 验证 BlockInfo（原 ImprovedBlockInfo）完整性
- 确认 BLOCK_HEIGHT 索引（已存在！）
- 决定 MAX_LINKS 参数
- 准备开发环境

### Phase 1: 数据结构优化（1-1.5 周）⭐ 先做！
- Week 1: 数据结构定义 + 序列化
  - 重命名：ImprovedBlockInfo → BlockInfo
  - 创建 ChainStats、BlockLink、Snapshot
  - 实现 CompactSerializer（3-4x 性能提升）
  - 基准测试

**核心命名**（符合 DAG + 区块链特性）：
- `Block` - 完整的块
- `BlockInfo` - 块元数据
- `BlockLink` - 块链接（体现 DAG）
- `ChainStats` - 链统计
- `Snapshot` - 快照

### Phase 2: 存储层重构（2-3 周）
- Week 1: 核心存储接口 + BlockStoreImpl
- Week 2: 缓存、Bloom Filter、写入缓冲
- Week 3: 快照迁移工具、删除旧代码

### Phase 3: 混合同步协议（2-3 周）
- Week 1: Finality 计算、主链固化、ChainSync
- Week 2: DagSync、xdagj-p2p 集成、**删除所有 SUMS 代码**
- Week 3: Solidification、6 层保护机制

### Phase 4: 测试和部署（1-2 周）
- Week 1: 完整测试（单元、集成、性能、压力）
- Week 2: 测试网部署、快照迁移实战、主网准备

## 🔐 安全机制

### 6层DAG保护
1. **Visited Set** - 防循环引用
2. **数量限制** - 防恶意大DAG  
3. **深度限制** - 防深度攻击
4. **时间窗口** - 防时间异常
5. **引用计数** - 防过度引用
6. **超时保护** - 防无限挂起

### 4层分区防御
1. **Layer 1**: 最大累积难度规则（已有）
2. **Layer 2**: Reorg深度限制（32768 epochs）
3. **Layer 3**: Checkpoint机制（可选）
4. **Layer 4**: 监控预警

## 📖 设计原则

### 1. 安全优先
- ✅ 保守的finality参数（12天）
- ✅ 多层防护机制
- ✅ 完整的验证流程
- ✅ 监控和告警

### 2. 性能优化
- ✅ 批量并行同步
- ✅ 多层缓存
- ✅ 紧凑序列化
- ✅ 索引优化

### 3. 向后兼容
- ✅ 保持完整DAG结构
- ✅ 协议版本协商
- ✅ 双版本并行
- ✅ 平滑迁移

### 4. 社区友好
- ✅ 足够长的finality时间
- ✅ 手动干预机制
- ✅ 透明的决策过程
- ✅ 清晰的文档

## 📝 参考资料

### 外部研究
- Bitcoin白皮书 - PoW共识和概率性finality
- Ethereum Casper FFG - 明确的finality机制
- IOTA Tangle - DAG结构和solidification
- Bitcoin网络分区处理 - 最长链规则

### 内部代码
- `BlockchainImpl.java` - 当前共识实现
- `XdagTime.java` - 时间戳转换
- `Constants.java` - 系统常量

---

**版本**: v1.0  
**创建时间**: 2025-01  
**作者**: Claude Code  
**状态**: 设计完成，待实施  

**联系方式**：如有问题或建议，请在GitHub提issue或PR。
