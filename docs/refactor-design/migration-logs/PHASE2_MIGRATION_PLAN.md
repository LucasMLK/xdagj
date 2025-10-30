# Phase 2: v5.1 代码迁移计划

**创建日期**: 2025-10-29
**状态**: 规划中
**预计时间**: 10-20小时（大型重构）

---

## 🎯 目标

将现有代码从旧Block架构迁移到v5.1的BlockV5架构。

### 关键架构差异

| 方面 | 旧设计 (Block.java) | v5.1 (BlockV5.java) |
|------|---------------------|---------------------|
| **Block大小** | 512字节固定 | 可变（48MB软限制） |
| **数据结构** | XdagField (16×32字节) | BlockHeader + List<Link> |
| **Transaction** | 嵌入Block内部 | 独立存储，Block只存hash |
| **Address类** | 复杂的Address (输入/输出) | 简单的Link (33字节引用) |
| **BlockLink类** | INPUT/OUTPUT/REFERENCE + amount | TRANSACTION/BLOCK (只存hash) |
| **可变性** | 可变 (Getter/Setter) | 不可变 (@Value) |
| **签名** | 复杂的签名机制 | Transaction独立签名 |

---

## ⚠️ 风险评估

### 高风险区域
1. **BlockchainImpl.java** (89KB, 核心逻辑)
2. **P2P消息类** (网络协议)
3. **存储层** (BlockStore/BlockStoreImpl)
4. **共识层** (XdagSync, RandomX)

### 影响范围
```bash
受影响的文件（初步识别）：
- 主要代码: 12+ 文件
- 测试代码: 10+ 文件
- 配置/工具: 5+ 文件

总计: 约30个文件需要更新或删除
```

---

## 📋 迁移策略

### 策略选择：渐进式重写

**原因**:
1. 用户明确说"直接重写，不必花时间调试过期的设计"
2. 架构差异太大，无法简单适配
3. v5.1是全新设计，不应被旧代码限制

### 步骤概览

#### Step 1: 清理和重命名 (2-3小时)
- 删除旧类: Address.java, BlockLink.java, BlockWrapper.java
- 将BlockV5.java重命名为Block.java
- 将BlockHeader.java, Link.java, Transaction.java标记为核心类
- 提交: "refactor: Rename BlockV5 to Block, remove legacy classes"

#### Step 2: 修复编译错误 (3-5小时)
- 编译识别所有错误
- 分类错误（存储/网络/共识/其他）
- 创建临时适配器（如果需要）
- 逐个修复关键文件

#### Step 3: 核心类重写 (5-8小时)
根据重要性排序：

**3.1 存储层** (最高优先级)
- BlockStore接口
- BlockStoreImpl
- OrphanBlockStore
- FinalizedBlockStore

**3.2 共识层**
- XdagSync
- RandomX

**3.3 网络层**
- P2P消息类
- XdagP2pHandler
- XdagP2pEventHandler

**3.4 其他**
- CompactSerializer
- 工具类

#### Step 4: 测试和验证 (2-4小时)
- 编译通过
- 单元测试修复
- 集成测试
- 性能测试

---

## 🗑️ 需要删除的文件

### 核心类（待删除）
```java
src/main/java/io/xdag/core/
├── Address.java              ❌ 删除（v5.1不需要）
├── BlockLink.java            ❌ 删除（被Link.java替代）
├── Block.java                ❌ 删除（被BlockV5重命名替代）
├── BlockWrapper.java         ❌ 删除（不再需要）
├── XdagField.java            ❌ 删除（512字节设计）
├── XdagBlock.java            ❌ 删除（512字节设计）
└── TxAddress.java            ❌ 可能删除
```

### 测试文件（待删除）
```java
src/test/java/io/xdag/core/
├── BlockTest.java            ❌ 已删除
├── AddressTest.java          ❌ 需要删除
├── BlockLinkTest.java        ❌ 需要删除
└── ... 其他相关测试
```

---

## 🔄 迁移检查清单

### Phase 2.1: 准备工作
- [ ] 创建新分支: `refactor/block-v5-migration`
- [ ] 备份当前代码
- [ ] 确认测试覆盖率基准
- [ ] 准备回滚计划

### Phase 2.2: 清理工作
- [ ] 删除Address.java
- [ ] 删除BlockLink.java
- [ ] 删除BlockWrapper.java
- [ ] 删除XdagField.java
- [ ] 删除XdagBlock.java
- [ ] 重命名BlockV5.java → Block.java
- [ ] 更新导入语句

### Phase 2.3: 核心类重写
- [ ] BlockStore接口更新
- [ ] BlockStoreImpl重写
- [ ] OrphanBlockStore重写
- [ ] CompactSerializer重写
- [ ] XdagSync适配
- [ ] P2P消息类更新

### Phase 2.4: 测试验证
- [ ] 编译通过
- [ ] 所有单元测试通过
- [ ] 集成测试通过
- [ ] 性能对比测试

### Phase 2.5: 文档更新
- [ ] 更新V5.1_IMPLEMENTATION_STATUS.md
- [ ] 更新README.md
- [ ] 创建迁移说明文档

---

## 🚨 不确定因素

### 技术挑战
1. **BlockchainImpl.java**: 89KB的核心逻辑如何重写？
2. **存储格式兼容**: 旧数据如何迁移？
3. **P2P协议**: 网络层如何升级？
4. **性能影响**: v5.1的性能是否达标？

### 决策点
1. **是否需要数据迁移工具**？
   - 用户说"用停机升级，快照导入导出"
   - 可能需要写一个转换工具

2. **是否保留部分旧代码**？
   - 用户明确说"不必花时间调试过期的设计"
   - 倾向于完全重写

3. **测试覆盖率目标**？
   - 当前: 39/39 v5.1核心测试通过
   - 目标: 需要多少测试才算完成？

---

## 💡 建议

### 当前阶段建议

**选项A: 分步迁移（推荐）**
1. 先完成Step 1（清理和重命名）- 2小时
2. 评估编译错误范围 - 1小时
3. 根据错误复杂度决定下一步
4. 每步都提交，保持可回滚

**选项B: 并行开发**
1. 保留旧代码，新功能使用BlockV5
2. 逐步迁移模块
3. 最后统一切换
4. 风险：两套代码并存，维护成本高

**选项C: 全面重写（高风险）**
1. 一次性删除所有旧代码
2. 从零开始重写所有模块
3. 风险：耗时长，可能遇到未知问题

### 用户需要决策

1. **是否现在开始迁移**？
   - Phase 1已完成（Block引用限制）
   - 可以开始Phase 2

2. **选择哪种迁移策略**？
   - 推荐：选项A（分步迁移）

3. **时间预算**？
   - 预计: 10-20小时
   - 可以分多次完成

---

**状态**: 等待用户确认是否开始迁移
**下一步**: 根据用户决策执行Step 1
