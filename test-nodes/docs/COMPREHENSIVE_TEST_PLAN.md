# XDAG v5.1 全面测试计划

**目的**: 基于设计文档对XDAG v5.1核心功能进行系统性测试，确保实现与设计一致

**测试环境**: 2节点Devnet

**测试工具**: test-framework.sh + update-nodes.sh + compare-nodes.sh + watch-logs.sh

---

## 📋 目录

1. [测试覆盖范围](#测试覆盖范围)
2. [测试优先级分类](#测试优先级分类)
3. [P0测试 - 核心功能](#p0测试---核心功能-必须通过)
4. [P1测试 - 高优先级](#p1测试---高优先级)
5. [P2测试 - 中优先级](#p2测试---中优先级)
6. [测试执行计划](#测试执行计划)
7. [工具使用指南](#工具使用指南)

---

## 测试覆盖范围

### 基于设计文档的核心功能

| 功能模块 | 设计文档来源 | 测试数量 | 状态 |
|---------|------------|---------|------|
| **DAG基础** | CORE_DATA_STRUCTURES.md | 8 | ⏳ 待实施 |
| **共识机制** | CONSENSUS_TEST_PLAN.md | 12 | ⏳ 待实施 |
| **Transaction** | CORE_DATA_STRUCTURES.md | 6 | ⏳ 待实施 |
| **Block结构** | CORE_DATA_STRUCTURES.md | 5 | ⏳ 待实施 |
| **Epoch竞争** | CONSENSUS_TEST_PLAN.md | 4 | ⏳ 待实施 |
| **链重组** | CONSENSUS_TEST_PLAN.md | 3 | ⏳ 待实施 |
| **孤块管理** | CORE_DATA_STRUCTURES.md | 3 | ⏳ 待实施 |
| **DAG引用规则** | DAG_REFERENCE_RULES.md | 5 | ⏳ 待实施 |
| **网络同步** | P2P-SYNC-FINAL-REPORT.md | 4 | ✅ 部分完成 |
| **总计** | - | **50** | **8%** (4/50) |

---

## 测试优先级分类

### P0 (必须通过) - 18个测试

核心功能，不通过则系统不可用：

1. Genesis一致性验证 ✅ **已实现**
2. P2P连接验证 ✅ **已实现**
3. Block基础结构验证
4. Transaction签名验证
5. BlockHeader Hash计算
6. PoW难度验证
7. Epoch计算正确性
8. 单Epoch单个获胜者选择
9. 累积难度计算一致性
10. 简单分叉解决
11. Transaction有效性判断
12. Account余额管理
13. Nonce顺序验证
14. 重复Block拒绝
15. 无效Block拒绝
16. DAG环状引用检测
17. 时间窗口验证
18. Links数量限制验证

### P1 (高优先级) - 19个测试

影响系统稳定性和安全性：

1. Block同步验证 ✅ **已实现**
2. Genesis确定性生成 ✅ **已实现**
3. 连续Epoch共识
4. 复杂分叉解决
5. 同一Epoch多个候选块竞争
6. Transaction独立传播
7. Mempool管理
8. 双花检测
9. 并发Block导入
10. 简单网络分区恢复
11. 难度调整算法
12. 确认机制
13. 孤块自动管理
14. 孤块Transaction处理
15. 主链重组
16. Block引用其他Blocks
17. Transaction引用验证
18. DAG遍历深度限制
19. 被引用次数统计

### P2 (中优先级) - 9个测试

优化和边界条件：

1. 深度分叉处理
2. Epoch边界条件
3. 空Epoch处理
4. 不同启动时间节点同步
5. 长期网络分区恢复
6. 区块传播延迟
7. 性能测试(TPS测量)
8. 内存使用监控
9. 存储容量验证

### P3 (低优先级) - 4个测试

高级功能和压力测试：

1. 三方网络分区
2. Selfish Mining攻击抵抗
3. 超大Block处理
4. 长时间运行稳定性

---

## P0测试 - 核心功能 (必须通过)

### 测试1: Genesis一致性验证 ✅

**状态**: 已实现

**目的**: 验证两个节点生成相同的genesis区块

**测试步骤**:
```bash
./test-framework.sh run-tests
# 查看测试报告中的 "Genesis Consistency" 部分
```

**验证标准**:
- ✅ 两节点的genesis hash完全相同
- ✅ Genesis timestamp相同
- ✅ Genesis difficulty相同

**对应设计**: CORE_DATA_STRUCTURES.md - "Deterministic Genesis"

---

### 测试2: P2P连接验证 ✅

**状态**: 已实现

**目的**: 验证节点间P2P连接正常

**测试步骤**:
```bash
./test-framework.sh run-tests
# 查看测试报告中的 "P2P Connection" 部分
```

**验证标准**:
- ✅ Node1和Node2互相连接
- ✅ Telnet命令响应正常
- ✅ stats命令返回状态信息

**对应设计**: P2P-SYNC-FINAL-REPORT.md

---

### 测试3: Block基础结构验证 ⏳

**状态**: 待实施

**目的**: 验证Block的Header和Body结构符合设计

**测试步骤**:
```bash
# 1. 获取最新Block
telnet localhost 6001
> block -n 1

# 2. 验证结构
# - timestamp (8 bytes)
# - difficulty (32 bytes)
# - nonce (32 bytes)
# - coinbase (32 bytes)
# - links: List<Link>
```

**验证标准**:
- ✅ BlockHeader包含timestamp, difficulty, nonce, coinbase
- ✅ BlockBody包含links列表
- ✅ 每个Link包含targetHash(32字节) + type(1字节)
- ✅ Block hash通过Keccak256计算

**对应设计**: CORE_DATA_STRUCTURES.md - "BlockHeader" 和 "Block"

**实施方法**:
在test-framework.sh中添加:
```bash
test_block_structure() {
    echo "## Test: Block Structure Validation" >> "$TEST_REPORT"
    log_info "Running test: Block Structure"

    # 获取Block数据并验证结构
    # ...
}
```

---

### 测试4: Transaction签名验证 ⏳

**状态**: 待实施

**目的**: 验证Transaction的ECDSA签名正确性

**测试步骤**:
```bash
# 1. 创建Transaction
./xdagj-cli send <from> <to> <amount>

# 2. 验证签名字段
# - v (recovery id)
# - r (32 bytes)
# - s (32 bytes)

# 3. 验证hash计算
# hash = Keccak256(from + to + amount + nonce + fee + data)
```

**验证标准**:
- ✅ Transaction包含v, r, s签名字段
- ✅ 签名格式符合EVM标准
- ✅ 使用secp256k1曲线
- ✅ ECRecover可以恢复from地址

**对应设计**: CORE_DATA_STRUCTURES.md - "Transaction" 和 "签名方案"

**实施方法**:
需要添加Java单元测试:
```java
@Test
public void testTransactionSignature() {
    // 创建Transaction
    Transaction tx = Transaction.builder()
        .from(fromAddress)
        .to(toAddress)
        .amount(100)
        .nonce(1)
        .fee(1)
        .build();

    // 签名
    tx.sign(privateKey);

    // 验证
    assertTrue(tx.verifySignature());
    assertEquals(fromAddress, tx.recoverSigner());
}
```

---

### 测试5: BlockHeader Hash计算 ⏳

**状态**: 待实施

**目的**: 验证Block hash计算正确性

**测试步骤**:
```bash
# 1. 获取Block数据
# 2. 手动计算hash: Keccak256(serialize(header) + serialize(body))
# 3. 对比节点返回的hash
```

**验证标准**:
- ✅ hash = Keccak256(header + body)
- ✅ 签名不参与hash计算
- ✅ hash缓存机制正确(延迟计算)

**对应设计**: CORE_DATA_STRUCTURES.md - "Hash计算"

---

### 测试6: PoW难度验证 ⏳

**状态**: 待实施

**目的**: 验证Block的PoW满足难度要求

**测试步骤**:
```bash
# 1. 获取Block的difficulty和hash
# 2. 验证: hash <= difficulty
```

**验证标准**:
- ✅ 所有主块的hash满足difficulty要求
- ✅ 候选块的hash满足difficulty要求
- ✅ PoW nonce正确

**对应设计**: CORE_DATA_STRUCTURES.md - "Block PoW流程"

---

### 测试7: Epoch计算正确性 ⏳

**状态**: 待实施

**目的**: 验证epoch = timestamp / 64计算正确

**测试步骤**:
```bash
# 1. 获取多个Block的timestamp
# 2. 计算epoch: epoch = timestamp / 64
# 3. 验证同一epoch的Block使用相同的difficulty
```

**验证标准**:
- ✅ epoch = timestamp / 64
- ✅ epoch边界处理正确(6400, 6464)
- ✅ 同一epoch的所有候选块使用相同difficulty

**对应设计**: CONSENSUS_TEST_PLAN.md - "3.2 Epoch边界竞争条件"

---

### 测试8: 单Epoch单个获胜者选择 ⏳

**状态**: 待实施

**目的**: 验证同一epoch只有一个主块

**测试步骤**:
```bash
# 1. 等待一个完整epoch(64秒)
# 2. 查询该epoch的所有候选块
# 3. 验证只有hash最小的成为主块
```

**验证标准**:
- ✅ 同一epoch只有1个主块(height > 0)
- ✅ 主块是hash最小的候选块
- ✅ 其他候选块标记为孤块(height = 0)

**对应设计**: CONSENSUS_TEST_PLAN.md - "1.1 单Epoch单个获胜者"

**实施方法**:
```bash
test_single_epoch_winner() {
    echo "## Test: Single Epoch Winner Selection" >> "$TEST_REPORT"
    log_info "Running test: Single Epoch Winner"

    # 获取当前epoch的所有候选块
    # 验证只有一个主块
    # 验证主块是hash最小的
}
```

---

### 测试9: 累积难度计算一致性 ⏳

**状态**: 待实施

**目的**: 验证两节点计算相同的累积难度

**测试步骤**:
```bash
# 1. 两节点同步相同的Block序列
# 2. 查询两节点的累积难度
# 3. 验证完全相同
```

**验证标准**:
- ✅ 两节点的累积难度完全相同
- ✅ 累积难度持续增长
- ✅ getBlockWork()返回值一致

**对应设计**: CONSENSUS_TEST_PLAN.md - "1.3 累积难度计算一致性"

---

### 测试10: 简单分叉解决 ⏳

**状态**: 待实施

**目的**: 验证短链被长链替换

**测试场景**:
```
Node A:  genesis → A1 → A2
Node B:  genesis → B1 → B2 → B3
假设 difficulty(B链) > difficulty(A链)
```

**测试步骤**:
```bash
# 1. Node A独立挖2个块
# 2. Node B独立挖3个块
# 3. 连接两节点
# 4. 验证Node A切换到B链
```

**验证标准**:
- ✅ Node A切换到B链
- ✅ A1, A2标记为孤块(height=0)
- ✅ 主链高度=3
- ✅ 累积难度更新正确

**对应设计**: CONSENSUS_TEST_PLAN.md - "2.1 简单分叉解决"

---

### 测试11-18: 其他P0测试 ⏳

**状态**: 待实施

详细测试用例参见后续章节...

---

## P1测试 - 高优先级

### 测试19: Block同步验证 ✅

**状态**: 已实现(部分)

**目的**: 验证节点间Block同步正常

**当前实现**:
```bash
./test-framework.sh run-tests
# 查看 "Block Synchronization" 测试结果
```

**验证标准**:
- ✅ 两节点的主链高度接近(差异≤2)
- ⏳ 两节点的Block hash序列相同(未实现)
- ⏳ 同步速度合理(未测量)

**对应设计**: P2P-SYNC-FINAL-REPORT.md

**需要改进**:
- 添加详细的Block hash对比
- 测量同步速度(blocks/sec)
- 验证缺失Block的请求和恢复

---

### 测试20: Genesis确定性生成 ✅

**状态**: 已实现

**目的**: 验证deterministic genesis生成

**测试方法**:
```bash
# 1. 清理节点数据
./update-nodes.sh --clean

# 2. 重新启动节点
./update-nodes.sh --restart

# 3. 验证genesis一致
./test-framework.sh run-tests
```

**验证标准**:
- ✅ 两节点生成相同genesis
- ✅ 非交互式钱包创建
- ✅ 相同的genesis配置

**对应设计**: CORE_DATA_STRUCTURES.md - "Deterministic Genesis"

---

### 测试21-38: 其他P1测试 ⏳

详细测试用例参见CONSENSUS_TEST_PLAN.md...

---

## P2测试 - 中优先级

### 测试39-47: P2测试用例 ⏳

参见CONSENSUS_TEST_PLAN.md的P2部分...

---

## 测试执行计划

### Phase 1: P0核心测试 (优先级最高)

**目标**: 确保核心功能正常

**时间**: 1-2周

**任务**:
1. ✅ Genesis一致性 (已完成)
2. ✅ P2P连接 (已完成)
3. ⏳ Block结构验证 (本周)
4. ⏳ Transaction签名 (本周)
5. ⏳ PoW验证 (本周)
6. ⏳ Epoch计算 (下周)
7. ⏳ 单Epoch获胜者 (下周)
8. ⏳ 累积难度 (下周)

**工具准备**:
- ✅ test-framework.sh (已完成)
- ✅ update-nodes.sh (已完成)
- ⏳ compare-nodes.sh (本周)
- ⏳ watch-logs.sh (本周)

---

### Phase 2: P1高优先级测试

**目标**: 验证系统稳定性

**时间**: 2-3周

**任务**:
1. ⏳ Block同步优化
2. ⏳ 分叉处理
3. ⏳ Mempool管理
4. ⏳ 双花检测
5. ⏳ 并发导入

---

### Phase 3: P2中优先级测试

**目标**: 边界条件和优化

**时间**: 2周

**任务**:
1. ⏳ 深度分叉
2. ⏳ Epoch边界
3. ⏳ 空Epoch
4. ⏳ 性能测试

---

### Phase 4: P3低优先级和压力测试

**目标**: 高级功能和稳定性

**时间**: 按需进行

---

## 工具使用指南

### 1. 代码更新和部署

```bash
# 修改代码后
cd test-nodes
./update-nodes.sh --restart

# 等待节点就绪(约30秒)
```

---

### 2. 运行测试

```bash
# 运行所有测试
./test-framework.sh run-tests

# 交互式管理
./test-framework.sh

# 查看最新报告
cat test-results/test_report_*.md | tail -100
```

---

### 3. 节点状态对比 (即将实现)

```bash
# 对比两个节点状态
./compare-nodes.sh

# 输出示例:
# Node1 vs Node2:
# - Genesis: ✅ Match
# - Height: Node1=38, Node2=37 (diff: 1)
# - Last 10 blocks: ✅ Match (8/10)
# - Cumulative difficulty: ✅ Match
```

---

### 4. 实时日志查看 (即将实现)

```bash
# 同时查看两个节点日志
./watch-logs.sh

# 彩色输出:
# [Node1] 2025-11-12 15:30:00 [INFO] Block imported: 0xabc...
# [Node2] 2025-11-12 15:30:01 [INFO] Block imported: 0xabc...
```

---

### 5. 问题调试工作流

```bash
# 1. 发现问题
./test-framework.sh run-tests
# [ERROR] Test failed: Block hashes do not match

# 2. 对比节点状态
./compare-nodes.sh
# Node1 block #10: 0xabc123...
# Node2 block #10: 0xdef456...  ❌ Different

# 3. 查看实时日志
./watch-logs.sh
# [Node1] [ERROR] Block validation failed: ...
# [Node2] [WARN] Orphan block detected: ...

# 4. 修改代码
vim src/main/java/io/xdag/core/DagChainImpl.java

# 5. 更新并重新测试
./update-nodes.sh --restart
./test-framework.sh run-tests

# 6. 验证修复
./compare-nodes.sh
# Node1 vs Node2: ✅ All Match
```

---

## 测试成功标准

### P0测试 (必须100%通过)

- ✅ 18个P0测试全部通过
- ✅ 无数据不一致
- ✅ 无分叉遗留

### P1测试 (目标90%以上)

- ✅ 至少17个P1测试通过
- ✅ 已知问题有workaround

### P2测试 (目标70%以上)

- ✅ 至少6个P2测试通过
- ✅ 未通过的有roadmap

---

## 当前测试覆盖率

### 总体状态

```
P0 (必须通过): 11% (2/18) ⚠️ 需要大幅提升
P1 (高优先级): 10% (2/19) ⚠️ 需要提升
P2 (中优先级): 0% (0/9)   ⏳ 未开始
P3 (低优先级): 0% (0/4)   ⏳ 未开始

总计: 8% (4/50) ⚠️ 严重不足
```

### 已完成的测试

1. ✅ Genesis一致性验证 (P0)
2. ✅ P2P连接验证 (P0)
3. ✅ Block同步验证 (P1, 部分)
4. ✅ Genesis确定性生成 (P1)

### 下一步优先实施

1. ⏳ Block结构验证 (P0)
2. ⏳ Transaction签名验证 (P0)
3. ⏳ PoW难度验证 (P0)
4. ⏳ Epoch计算正确性 (P0)
5. ⏳ 单Epoch获胜者选择 (P0)

---

## 参考文档

- [CONSENSUS_TEST_PLAN.md](../docs/testing/CONSENSUS_TEST_PLAN.md) - 共识测试计划(44个场景)
- [CORE_DATA_STRUCTURES.md](../docs/design/CORE_DATA_STRUCTURES.md) - 核心数据结构设计
- [DAG_REFERENCE_RULES.md](../docs/design/DAG_REFERENCE_RULES.md) - DAG引用规则
- [P2P-SYNC-FINAL-REPORT.md](./P2P-SYNC-FINAL-REPORT.md) - P2P同步测试报告
- [WORKFLOW_SIMULATION.md](./WORKFLOW_SIMULATION.md) - 测试工作流模拟

---

**创建日期**: 2025-11-12
**版本**: v1.0
**维护者**: XDAG Development Team
**状态**: 初始版本 - 待执行

**关键指标**:
- 测试总数: 50
- 已完成: 4 (8%)
- P0优先级: 18个(必须通过)
- 下一步: 实施compare-nodes.sh和watch-logs.sh工具
