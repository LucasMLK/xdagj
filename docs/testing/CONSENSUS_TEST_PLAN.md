# XDAG共识算法测试计划

## 目标
严格测试XDAG的共识算法，确保多节点环境下数据一致性，防止分叉和数据不一致。

---

## 测试架构

### 测试工具类
```java
MultiNodeTestEnvironment.java  // 多节点测试环境
- createNode(nodeId): DagKernel
- connectNodes(node1, node2): void
- disconnectNodes(node1, node2): void  // 模拟网络分区
- waitForSync(timeout): boolean
- verifyConsensus(): ConsensusReport
```

---

## 测试用例分类

## 第一部分：基础共识测试 (Basic Consensus Tests)

### 1.1 单Epoch单个获胜者
**场景**: 两个节点在同一epoch挖出不同的区块
**预期**:
- 两个节点最终选择相同的获胜区块（最小哈希）
- 累积难度相同
- 主链高度相同

**测试步骤**:
```java
testSingleEpochSingleWinner() {
    1. 创建2个节点 (Node A, Node B)
    2. 都连接到同一个genesis区块
    3. 在epoch=100时刻：
       - Node A挖出区块 hashA = 0x3333...
       - Node B挖出区块 hashB = 0x5555...
    4. 节点间交换区块
    5. 验证：
       - 两节点都选择 min(hashA, hashB) 作为获胜者
       - 两节点的mainBlockCount相同
       - 两节点的累积难度相同
}
```

**关键验证点**:
- [ ] 获胜区块哈希一致
- [ ] 累积难度一致
- [ ] 主链高度一致
- [ ] 失败的区块标记为孤块 (height=0)

---

### 1.2 连续Epoch共识
**场景**: 多个连续epoch，每个epoch都有竞争
**预期**: 每个epoch的获胜者选择一致

**测试步骤**:
```java
testConsecutiveEpochConsensus() {
    1. 创建3个节点
    2. 连续10个epoch，每个epoch:
       - 每个节点都挖出一个区块
       - 区块哈希随机生成
    3. 节点间同步所有区块
    4. 验证：
       - 每个epoch的获胜者在所有节点上一致
       - 主链路径完全相同
       - 累积难度曲线相同
}
```

---

### 1.3 累积难度计算一致性
**场景**: 验证所有节点对相同区块序列计算出相同的累积难度
**预期**: 累积难度计算公式一致

**测试步骤**:
```java
testCumulativeDifficultyConsistency() {
    1. 创建固定的区块链：
       genesis → block1 → block2 → block3
       包含分叉：block2 → block3a 和 block2 → block3b
    2. 在3个独立节点上导入相同的区块（顺序不同）
    3. 验证：
       - 对于相同的区块，累积难度完全相同
       - 主链选择一致（最高累积难度）
       - getBlockWork() 返回值一致
}
```

---

## 第二部分：分叉处理测试 (Fork Handling Tests)

### 2.1 简单分叉 - 短链被长链替换
**场景**: 节点A在短链上，收到更长的链后切换
**预期**: 链重组，采用累积难度更高的链

**测试步骤**:
```java
testSimpleForkResolution() {
    1. Node A:  genesis → A1 → A2
    2. Node B:  genesis → B1 → B2 → B3
    3. 假设 difficulty(B链) > difficulty(A链)
    4. Node A 收到 B链所有区块
    5. 验证：
       - Node A 切换到 B链
       - A1, A2 变成孤块 (height=0)
       - 主链高度 = 3
       - mainBlockAtPosition(1) == B1
}
```

**关键验证点**:
- [ ] 链切换正确
- [ ] 孤块正确标记
- [ ] 累积难度更新
- [ ] 区块引用关系保持

---

### 2.2 复杂分叉 - 多个分支
**场景**: 3条分支竞争
**预期**: 选择累积难度最高的分支

**测试步骤**:
```java
testComplexForkResolution() {
    1. 创建分叉结构：
       genesis → common1 → common2
                           ├─→ branchA1 → branchA2 (difficulty=100)
                           ├─→ branchB1 → branchB2 → branchB3 (difficulty=150)
                           └─→ branchC1 (difficulty=80)
    2. 3个节点分别在不同分支上
    3. 全部节点互相同步
    4. 验证：
       - 所有节点选择 branchB (最高难度)
       - branchA 和 branchC 的区块变成孤块
       - 主链路径完全一致
}
```

---

### 2.3 深度分叉 - 从很早的区块分叉
**场景**: 两条链从第10个区块就分叉，各自增长到50个区块
**预期**: 选择累积难度高的链，即使需要回滚很多区块

**测试步骤**:
```java
testDeepForkResolution() {
    1. 共同链：genesis → block1...block10
    2. ChainA: block10 → blockA11...blockA50 (40 blocks, low work)
    3. ChainB: block10 → blockB11...blockB60 (50 blocks, high work)
    4. Node A 在 ChainA上
    5. Node A 收到 ChainB 所有区块
    6. 验证：
       - Node A 切换到 ChainB
       - blockA11...blockA50 全部变成孤块 (height=0)
       - mainBlockCount = 60
       - 检查 getMainChainPath() 完全属于 ChainB
}
```

**性能要求**:
- 重组操作应在 < 1秒内完成
- 数据库一致性保持

---

## 第三部分：Epoch竞争测试 (Epoch Competition Tests)

### 3.1 同一Epoch多个候选区块
**场景**: 5个节点在同一epoch挖出5个不同区块
**预期**: 所有节点选择最小哈希的区块

**测试步骤**:
```java
testEpochCompetitionMultipleCandidates() {
    1. 创建5个节点
    2. 在 epoch=200:
       Node1: hash=0x1111... (最小)
       Node2: hash=0x3333...
       Node3: hash=0x5555...
       Node4: hash=0x7777...
       Node5: hash=0x9999...
    3. 全部节点互相同步
    4. 验证：
       - getWinnerBlockInEpoch(200) == 0x1111... (所有节点)
       - 只有 Node1 的区块是主链区块 (height>0)
       - 其他4个区块都是孤块 (height=0)
       - EpochStats.totalBlocks = 5
       - EpochStats.winningBlockHash = 0x1111...
}
```

---

### 3.2 Epoch边界竞争条件
**场景**: 区块时间戳正好在epoch边界，测试归属判断
**预期**: epoch计算一致，边界处理正确

**测试步骤**:
```java
testEpochBoundaryConditions() {
    1. Epoch 100: 时间范围 [6400, 6464)
    2. 创建区块：
       blockA: timestamp=6400 (epoch=100, 边界开始)
       blockB: timestamp=6463 (epoch=100, 边界结束前)
       blockC: timestamp=6464 (epoch=101, 下一个epoch开始)
    3. 3个节点导入这些区块
    4. 验证：
       - getEpoch(blockA) == 100
       - getEpoch(blockB) == 100
       - getEpoch(blockC) == 101
       - getCandidateBlocksInEpoch(100) 包含 A, B
       - getCandidateBlocksInEpoch(101) 包含 C
}
```

---

### 3.3 空Epoch处理
**场景**: 某些epoch没有任何区块
**预期**: 空epoch不影响共识，链继续正常增长

**测试步骤**:
```java
testEmptyEpochHandling() {
    1. 创建区块链：
       epoch=100: block100
       epoch=101: (空)
       epoch=102: (空)
       epoch=103: block103
       epoch=104: block104
    2. 2个节点导入所有区块
    3. 验证：
       - getCandidateBlocksInEpoch(101) == []
       - getCandidateBlocksInEpoch(102) == []
       - getWinnerBlockInEpoch(101) == null
       - 主链正确连接：block100 → block103 → block104
       - 累积难度连续增长
}
```

---

## 第四部分：时序和传播测试 (Timing and Propagation Tests)

### 4.1 区块传播延迟
**场景**: 模拟真实网络延迟，区块到达顺序不同
**预期**: 即使接收顺序不同，最终共识一致

**测试步骤**:
```java
testBlockPropagationDelay() {
    1. 创建3个节点：A, B, C
    2. 同时挖出3个区块：block1, block2, block3
    3. 模拟不同的传播顺序：
       Node A: block1 → block2 → block3 (延迟 0ms, 100ms, 200ms)
       Node B: block3 → block1 → block2 (延迟 0ms, 50ms, 150ms)
       Node C: block2 → block3 → block1 (延迟 0ms, 80ms, 180ms)
    4. 验证：
       - 所有节点最终达成相同状态
       - 主链选择一致
       - 累积难度相同
}
```

---

### 4.2 并发区块导入
**场景**: 多个线程同时向同一节点导入区块
**预期**: 无竞态条件，最终状态一致

**测试步骤**:
```java
testConcurrentBlockImport() {
    1. 创建1个节点和100个区块
    2. 10个线程并发导入这些区块（随机顺序）
    3. 等待所有线程完成
    4. 验证：
       - 所有区块都被导入
       - 主链选择正确
       - 无数据竞争错误
       - 累积难度正确
       - ChainStats 数值正确
}
```

**线程安全验证**:
- [ ] 无 ConcurrentModificationException
- [ ] 累积难度计算无竞态
- [ ] ChainStats 更新原子性

---

### 4.3 不同启动时间的节点
**场景**: 模拟实际测试中观察到的情况 - 节点A先启动，节点B后启动
**预期**: 高度编号可能不同，但累积难度和区块哈希序列一致

**测试步骤**:
```java
testStaggeredNodeStartup() {
    1. t=0: 启动 Node A，挖出 genesis
    2. t=10s: Node A 独立挖出 3个区块
    3. t=20s: 启动 Node B，挖出自己的 genesis
    4. t=30s: Node B 独立挖出 2个区块
    5. t=40s: 连接 Node A 和 Node B
    6. t=50s: 等待同步完成
    7. 验证：
       - 两节点的累积难度相同
       - 两节点的主链区块哈希序列相同（可能起始编号不同）
       - 从收敛点开始的主链路径完全一致
       - 分析：高度差异 = |countA - countB|
}
```

---

## 第五部分：网络分区测试 (Network Partition Tests)

### 5.1 简单分区和恢复
**场景**: 网络分成两部分，各自独立增长，然后恢复连接
**预期**: 恢复后选择累积难度高的链

**测试步骤**:
```java
testNetworkPartitionAndRecovery() {
    1. 创建4个节点：A, B (分区1), C, D (分区2)
    2. 共同起点：genesis → block1 → block2
    3. 分区发生：
       分区1 (A,B): block2 → blockA3 → blockA4 → blockA5
       分区2 (C,D): block2 → blockC3 → blockC4 → blockC5 → blockC6
    4. 分区恢复：reconnect(A, C)
    5. 验证：
       - 所有节点最终在同一条链上
       - 如果 difficulty(分区2) > difficulty(分区1):
         * 所有节点采用分区2的链
         * blockA3, blockA4, blockA5 变成孤块
       - 累积难度一致
}
```

---

### 5.2 长期分区 - 两条独立链
**场景**: 两个分区独立运行很久（100个区块），然后恢复
**预期**: 正确处理大规模链重组

**测试步骤**:
```java
testLongTermPartition() {
    1. 分区1: genesis → 100 blocks (low work)
    2. 分区2: genesis → 120 blocks (high work)
    3. 分区时长：模拟数小时
    4. 恢复连接
    5. 验证：
       - 重组完成时间 < 10秒
       - 内存使用稳定
       - 选择正确的主链
       - 数据库一致性
}
```

**性能指标**:
- 重组速度: < 100ms per block
- 内存增长: < 2x
- 数据库大小: 合理

---

### 5.3 三方分区 - 复杂网络拓扑
**场景**: 网络分成3部分，拓扑复杂
**预期**: 恢复后达成全局共识

**测试步骤**:
```java
testThreeWayPartition() {
    1. 创建6个节点：A,B (分区1), C,D (分区2), E,F (分区3)
    2. 分区拓扑：
       分区1 ↔ 分区2 (连接)
       分区2 ↔ 分区3 (连接)
       分区1 ✗ 分区3 (断开)
    3. 各分区独立增长
    4. 恢复全连接
    5. 验证：
       - 所有节点达成共识
       - 主链选择一致
       - 无数据丢失
}
```

---

## 第六部分：恶意攻击测试 (Attack Resistance Tests)

### 6.1 Selfish Mining 攻击
**场景**: 恶意节点隐藏自己挖出的区块，突然发布
**预期**: 诚实节点正确处理，不被攻击者控制

**测试步骤**:
```java
testSelfishMiningResistance() {
    1. 3个诚实节点 + 1个恶意节点
    2. 恶意节点私下挖出5个区块（不广播）
    3. 诚实节点公开挖出3个区块
    4. 恶意节点突然发布私有链（5个区块）
    5. 验证：
       - 如果恶意链累积难度更高 → 被接受（正确）
       - 如果恶意链累积难度更低 → 被拒绝（正确）
       - 诚实节点间保持一致
}
```

---

### 6.2 重复区块攻击
**场景**: 攻击者重复发送同一个区块
**预期**: 节点正确去重，不重复处理

**测试步骤**:
```java
testDuplicateBlockAttack() {
    1. 创建1个节点和1个区块
    2. 连续发送同一个区块100次
    3. 验证：
       - 第一次导入成功
       - 后续99次返回 DagImportResult.duplicate()
       - 区块只存储一次
       - 主链状态正确
}
```

---

### 6.3 无效区块攻击
**场景**: 攻击者发送各种无效区块
**预期**: 所有无效区块被拒绝

**测试步骤**:
```java
testInvalidBlockAttack() {
    测试以下无效区块：
    1. 时间戳在未来的区块
    2. 时间戳早于XDAG era的区块
    3. 创建循环引用的区块
    4. 引用不存在区块的区块
    5. POW不满足难度的区块
    6. 签名错误的交易
    7. 双花交易

    验证：
    - 所有无效区块被拒绝
    - 节点状态不受影响
    - 日志中有相应警告
}
```

---

## 第七部分：一致性验证工具 (Consistency Verification)

### 辅助验证类
```java
class ConsensusVerifier {
    // 验证所有节点是否达成共识
    static ConsensusReport verifyConsensus(List<DagKernel> nodes) {
        1. 比较所有节点的：
           - Genesis hash
           - 主链长度
           - 累积难度
           - 每个height的区块哈希
           - ChainStats
        2. 生成详细报告
    }

    // 验证链的完整性
    static ChainIntegrityReport verifyChainIntegrity(DagKernel node) {
        1. 检查每个区块：
           - 引用的区块都存在
           - 累积难度计算正确
           - 高度编号连续
           - Epoch分配正确
        2. 生成报告
    }

    // 比较两条链的差异
    static ChainDiffReport compareChains(DagKernel node1, DagKernel node2) {
        1. 找出分叉点
        2. 列出差异区块
        3. 分析累积难度差异
        4. 生成可视化报告
    }
}
```

---

## 测试执行策略

### 单元测试
- 快速执行（< 5秒每个测试）
- 使用内存数据库
- 模拟网络延迟

### 集成测试
- 真实环境（< 30秒每个测试）
- 使用RocksDB
- 模拟真实网络条件

### 压力测试
- 长时间运行（数小时）
- 大量区块（10000+）
- 监控内存和性能

---

## 成功标准

### 功能正确性
- [ ] 所有单元测试通过（100%）
- [ ] 无数据不一致
- [ ] 无分叉遗留

### 性能要求
- [ ] 区块导入速度 > 100 blocks/sec
- [ ] 链重组速度 > 50 blocks/sec
- [ ] 内存使用 < 500MB (1000 blocks)

### 可靠性
- [ ] 1000次测试无失败
- [ ] 并发测试无死锁
- [ ] 网络分区恢复100%成功

---

## 优先级

### P0 (必须通过)
- 1.1 单Epoch单个获胜者
- 1.3 累积难度计算一致性
- 2.1 简单分叉解决
- 3.1 同一Epoch多个候选区块
- 6.2 重复区块攻击
- 6.3 无效区块攻击

### P1 (高优先级)
- 1.2 连续Epoch共识
- 2.2 复杂分叉解决
- 4.2 并发区块导入
- 5.1 简单分区和恢复

### P2 (中优先级)
- 2.3 深度分叉
- 3.2 Epoch边界
- 3.3 空Epoch
- 4.3 不同启动时间

### P3 (低优先级)
- 4.1 区块传播延迟
- 5.2 长期分区
- 5.3 三方分区
- 6.1 Selfish Mining

---

## 下一步

1. **审查测试计划**: 确认测试覆盖是否完整
2. **实现测试基础设施**: MultiNodeTestEnvironment, ConsensusVerifier
3. **实现P0测试用例**: 从最关键的开始
4. **逐步实现P1-P3**: 根据开发进度调整
5. **持续集成**: 加入CI/CD流程

---

**备注**: 这个测试计划覆盖了XDAG共识算法的所有关键方面。通过这些测试，我们可以确保：
- 多节点环境下数据一致性
- 分叉处理正确性
- Epoch竞争公平性
- 抗攻击能力
- 性能和可靠性

你觉得这个测试计划如何？有没有遗漏的场景？
