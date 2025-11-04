# TODO 清理报告 - Phase 7.3 完成后状态

**生成时间**: 2025-11-04
**总计**: 41 个有效 TODO（已排除 49 个 DELETED 标记）

---

## 📊 分类统计

### 1️⃣ v5.1 迁移待恢复功能 (7个) - 🔴 阻塞
> 需要等待 BlockV5 完全迁移完成才能恢复

| 文件 | 行号 | 说明 |
|------|------|------|
| RefactoredStorageDemo.java | 54 | 恢复存储系统演示 |
| Commands.java | 60 | 恢复传统显示方法 |
| TransactionHistoryStore.java | 35 | 恢复交易历史系统 |
| PoolAwardManagerImpl.java | 48, 73, 263, 291 | 恢复矿池奖励分配系统（4处） |

**建议**: ⏸️ 暂时保留，等待 Phase 8.4+ 完成 BlockV5 完全迁移

---

### 2️⃣ v5.1 重构需求 (2个) - 🟡 中优先级

| 文件 | 行号 | 说明 |
|------|------|------|
| XdagSync.java | 270 | 迁移到 BlockV5（getLastTime方法） |
| Commands.java | 1166 | 重写为使用 BlockV5 Transaction 系统 |

**建议**: 📝 可以开始规划，但依赖 BlockV5 完整实现

---

### 3️⃣ Phase 任务 (4个) - 🟢 可处理

| 文件 | 行号 | 说明 | Phase |
|------|------|------|-------|
| SyncManager.java | 415 | 完成同步迁移到 BlockV5 | 7.1 |
| ChannelManager.java | 225 | 创建 BlockV5Distribution 类 | 3.3 |
| BlockStoreImpl.java | 610 | 实现完整的时间范围迭代 | 7.3 |
| PoolAwardManagerImpl.java | 185 | 迁移到 BlockV5 结构 | 9 |

**建议**: ✅ Phase 7.3 已完成，这些是下一步任务

---

### 4️⃣ 实现待完成 (5个) - 🟡 中优先级

| 文件 | 行号 | 说明 | 难度 |
|------|------|------|------|
| Transaction.java | 178, 192, 202 | 实现签名提取（3处） | 中 |
| PoolAwardManagerImpl.java | 312, 318 | 实现 nonce 跟踪（2处） | 低 |

**建议**: 🔧 可以开始实现，不阻塞主流程

---

### 5️⃣ 优化和改进 (2个) - 🟢 可选

| 文件 | 行号 | 说明 |
|------|------|------|
| SyncManager.java | 523 | 考虑未接收请求块的超时 |
| XdagPow.java | 683 | 限制每个矿池在每个区块周期内提交的份额数量 |

**建议**: 💡 性能优化，非紧急

---

### 6️⃣ 配置和注释 (10个) - 🔵 低优先级

| 文件 | 行号 | 说明 |
|------|------|------|
| SyncManager.java | 407, 475, 555, 573 | 修改共识、P2P请求、区块导入、同步状态（4处） |
| XdagSync.java | 86 | 设置同步开始时间/快照时间 |
| XdagP2pHandler.java | 425, 428 | 多区块请求处理、防攻击线程（2处） |
| BlockFinalizationService.java | 207 | 可选：删除已finalized的区块节省空间 |
| BlockchainImpl.java | 382 | 重新设计 onNewTxHistory() |
| AbstractConfig.java | 284, 295 | 设置挖矿线程数、仅加载区块（2处） |

**建议**: 📌 标记为future work，不阻塞进度

---

### 7️⃣ 遗留问题 (11个) - 🟠 需要审查

| 文件 | 行号 | 说明 | 类型 |
|------|------|------|------|
| Commands.java | 1202 | 需要从某处获取 amount | 缺失数据 |
| SnapshotStoreImpl.java | 253 | FIXME: toCanonical | 需修复 |
| SnapshotStoreImpl.java | 334 | 从快照恢复交易数量 | 未完成 |
| AddressStoreImpl.java | 114 | 将计算移到应用层 | 架构改进 |
| OrphanBlockStoreImpl.java | 97 | 判断时间（中文注释） | 需审查 |
| BlockStoreImpl.java | 524 | 需要正确实现 loadBlockInfoFromIndex | 实现不完整 |
| ChannelManager.java | 72, 86, 93, 100 | Legacy 区块分发线程已删除（4处） | 历史标记 |

**建议**: 🔍 需要逐个审查，部分可能是死代码注释

---

## 🎯 处理建议

### 立即可处理（不阻塞主流程）
1. **清理历史标记** (ChannelManager.java 4处)
   - 删除 "Legacy block distribution thread deleted" 注释

2. **实现简单功能** (5个)
   - Transaction 签名提取（3处）
   - PoolAwardManagerImpl nonce 跟踪（2处）

3. **文档化 Future Work**
   - 将低优先级 TODO 移到专门的 FUTURE_WORK.md

### 中期规划（依赖 BlockV5）
4. **Phase 任务** (4个)
   - Phase 7.3 后续任务
   - BlockV5Distribution 类创建
   - 时间范围迭代实现

5. **v5.1 重构** (2个)
   - XdagSync.getLastTime() 迁移
   - Commands Transaction 系统重写

### 长期保留（等待大迁移）
6. **v5.1 待恢复功能** (7个)
   - 等待 Phase 8.4+ BlockV5 完全迁移
   - 包括矿池奖励、交易历史、存储演示

---

## 📈 处理优先级

```
🔴 高优先级 (立即处理)
├── 清理历史标记 (4个) ✅ 可快速完成
└── 实现简单功能 (5个) ⏱️ 1-2小时

🟡 中优先级 (本周内)
├── Phase 任务 (4个) ⏱️ 4-6小时
└── v5.1 重构 (2个) ⏱️ 2-4小时

🟢 低优先级 (随时)
├── 优化和改进 (2个)
├── 配置和注释 (10个)
└── 遗留问题审查 (11个)

🔴 阻塞（等待迁移）
└── v5.1 待恢复功能 (7个) ⏸️ Phase 8.4+
```

---

## 💡 下一步建议

### 方案 A: 快速清理（30分钟）
1. 删除 ChannelManager.java 中的4个历史标记
2. 将低优先级 TODO 转为文档化的 Future Work

### 方案 B: 深度清理（2-4小时）
1. 方案A的所有内容
2. 实现 Transaction 签名提取（3处）
3. 实现 nonce 跟踪（2处）
4. 完成 Phase 7.3 后续任务（4个）

### 方案 C: 全面审查（1-2天）
1. 方案B的所有内容
2. 审查所有遗留问题（11个）
3. 文档化所有待办事项
4. 创建详细的 Phase 8 规划

---

## 📝 统计总结

- **总计**: 41 个 TODO
- **可立即处理**: 9 个（22%）
- **中期规划**: 6 个（15%）
- **低优先级**: 21 个（51%）
- **阻塞**: 7 个（17%）+ 10 个在 SyncManager.java

**建议**: 先执行方案A，快速清理简单的TODO，然后评估是否继续方案B。
