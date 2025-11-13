# XDAG 2节点测试环境

**目的**: 自动化测试XDAG v5.1核心功能，对照设计文档查漏补缺

---

## 🚀 快速开始

### 1. 更新节点（编译最新代码）
```bash
./update-nodes.sh --restart     # 编译、部署并重启节点
```

### 2. 运行测试
```bash
./test-framework.sh run-tests   # 运行所有测试
```

### 3. 交互式管理
```bash
./test-framework.sh             # 打开交互菜单
```

---

## 📁 目录结构

```
test-nodes/
├── update-nodes.sh              # 🔧 更新工具 - 编译并部署最新代码
├── test-framework.sh            # 🧪 测试框架 - 自动化测试执行
├── compare-nodes.sh             # 📊 节点对比工具 - 对比两节点状态 (新)
├── watch-logs.sh                # 👀 日志查看工具 - 双节点日志监控 (新)
├── README.md                    # 📖 本文档
├── COMPREHENSIVE_TEST_PLAN.md   # 📋 全面测试计划 (新)
├── WORKFLOW_SIMULATION.md       # 🔄 工作流模拟文档
├── WALLET_GENESIS_GUIDE.md      # 🔑 钱包和Genesis配置说明 (重要)
├── P2P-SYNC-FINAL-REPORT.md     # 📊 P2P测试报告（Phase 12.5）
├── node1/                       # 节点1数据目录
└── node2/                       # 节点2数据目录
```

---

## 🔧 工具使用

### update-nodes.sh - 代码更新工具

**典型场景**: 修改代码后更新测试环境

```bash
# 选项1: 仅编译和部署（不重启）
./update-nodes.sh

# 选项2: 编译、部署并重启节点（推荐）
./update-nodes.sh --restart

# 选项3: 编译、部署、重启并等待节点就绪（最推荐）
./update-nodes.sh --restart --wait-ready

# 选项4: 清理节点数据（重新开始）
./update-nodes.sh --clean
```

**做什么**:
1. ✅ 停止节点（可选）
2. ✅ 运行 `mvn clean package -DskipTests`
3. ✅ 复制 JAR 到 node1 和 node2
4. ✅ 启动节点（可选）
5. ✅ 等待节点就绪（可选）- 新增功能

**新功能: --wait-ready**
- 自动检测节点是否可以响应telnet命令
- 等待最多60秒/节点
- 每5秒显示进度
- 需要安装expect: `brew install expect`

---

### compare-nodes.sh - 节点状态对比工具 🆕

**典型场景**: 快速对比两个节点的状态差异

```bash
# 快速对比
./compare-nodes.sh

# 交互模式
./compare-nodes.sh --interactive
```

**对比内容**:
- ✅ Genesis hash
- ✅ Main block count (主链高度)
- ✅ Total blocks (总区块数)
- ✅ Current difficulty (当前难度)
- ✅ Recent blocks (最近10个区块)

**输出示例**:
```
[1] Genesis Hash
✅ Match - Genesis: 0xabc123...

[2] Main Block Count
✅ Match - Main Blocks: 38

[3] Total Blocks
✅ Match - Total Blocks: 42

[4] Current Difficulty
✅ Match - Difficulty: 0x0000ffff...

[5] Recent Blocks
⚠️ Mismatch - Block lists differ
```

**需要**: expect (`brew install expect`)

---

### watch-logs.sh - 日志查看工具 🆕

**典型场景**: 同时查看两个节点的日志，方便调试

```bash
# 交互模式（默认）
./watch-logs.sh

# 实时查看（交错模式）
./watch-logs.sh --watch

# 并排查看（需要tmux）
./watch-logs.sh --side-by-side

# 只看错误
./watch-logs.sh --errors

# 过滤特定内容
./watch-logs.sh --filter "Block imported"

# 查看最近N行
./watch-logs.sh --lines 100
```

**特点**:
- 🎨 彩色输出：Node1 (青色), Node2 (紫色)
- 🔍 错误高亮：ERROR (红色), WARN (黄色), INFO (绿色)
- ⚡ 实时更新
- 🔀 多种查看模式

**需要**: tmux (可选，用于并排查看): `brew install tmux`

---

### test-framework.sh - 自动化测试框架

**典型场景**: 验证节点功能正确性

#### 非交互模式
```bash
./test-framework.sh run-tests   # 运行所有测试，生成报告
```

#### 交互模式
```bash
./test-framework.sh             # 打开菜单

# 菜单选项:
# 1. Start Both Nodes       - 启动两个节点
# 2. Stop Both Nodes        - 停止两个节点
# 3. Restart Both Nodes     - 重启两个节点
# 4. Check Node Status      - 检查节点状态
# 5. Run All Tests          - 运行所有测试
# 6. View Last Test Report  - 查看最新测试报告
# 7. Clean Test Results     - 清理测试结果
# 8. Exit                   - 退出
```

**已实现的测试**:
- ✅ P2P连接验证
- ✅ Genesis一致性验证
- ✅ 区块同步验证

**测试报告**: `test-results/test_report_*.md`

---

## 📊 测试覆盖度

### 当前状态
- **P0 (必须通过)**: 33% (6/18)
- **P1 (高优先级)**: 0% (0/19)
- **P2 (中优先级)**: 11% (1/9)
- **总计**: 15% (7/46)

### 待实现的测试 (优先级排序)

**Phase 1: P0核心功能**
1. ⏳ Epoch竞争机制
2. ⏳ Transaction创建和签名
3. ⏳ Account余额和Nonce管理
4. ⏳ 重复/无效区块拒绝
5. ⏳ Block引用Transaction

**Phase 2: P1高优先级**
6. ⏳ 链重组
7. ⏳ 孤块管理
8. ⏳ Mempool管理
9. ⏳ 双花检测
10. ⏳ 并发Block导入

**Phase 3: P2中优先级**
11. ⏳ 性能测试（TPS测量）
12. ⏳ 网络分区恢复
13. ⏳ 难度调整

---

## 🔄 典型工作流程

### 场景1: 修改代码后测试 (推荐工作流)

```bash
# 1. 编辑代码
vim src/main/java/io/xdag/...

# 2. 更新测试节点并等待就绪
cd test-nodes
./update-nodes.sh --restart --wait-ready

# 3. 对比两个节点状态
./compare-nodes.sh

# 4. 运行测试
./test-framework.sh run-tests

# 5. 查看报告
cat test-results/test_report_*.md
```

### 场景2: 调试节点问题

```bash
# 1. 对比节点状态，找出差异
./compare-nodes.sh

# 2. 实时查看日志
./watch-logs.sh --watch
# 或者并排查看（需要tmux）
./watch-logs.sh --side-by-side

# 3. 只查看错误
./watch-logs.sh --errors

# 4. 如果发现问题，修改代码
vim src/...

# 5. 更新并重新测试
./update-nodes.sh --restart --wait-ready
./compare-nodes.sh
```

### 场景3: 开发新测试用例

```bash
# 1. 编辑测试框架
vim test-framework.sh

# 2. 添加新的 test_* 函数

# 3. 在 run_all_tests() 中调用

# 4. 运行测试验证
./test-framework.sh run-tests
```

### 场景4: 完整的测试循环 (查漏补缺)

```bash
# 1. 查看全面测试计划
cat COMPREHENSIVE_TEST_PLAN.md

# 2. 启动节点
./update-nodes.sh --restart --wait-ready

# 3. 运行测试
./test-framework.sh run-tests

# 4. 对比节点状态
./compare-nodes.sh

# 5. 检查是否有差异
# 如果有差异 → 查看日志 → 修改代码 → 重新测试
# 如果无差异 → 测试通过 → 进入下一个测试
```

---

## ⚙️ 配置

### 节点配置
- **Node1**: 127.0.0.1:8001 (Telnet: 6001)
- **Node2**: 127.0.0.1:8002 (Telnet: 6002)
- **Password**: root
- **Network**: devnet

### 修改配置
```bash
# Node1配置
vim node1/xdag-devnet.conf

# Node2配置
vim node2/xdag-devnet.conf
```

---

## 📝 测试报告示例

```markdown
# XDAG 2-Node Automated Test Report

**Date**: 2025-11-12 15:30:00
**Test Environment**: 2-Node Devnet

## Test Results

### Test: P2P Connection
**Result**: ✅ PASS
- Node1 is responding
- Node2 is responding

### Test: Genesis Consistency
**Result**: ✅ PASS
- Genesis blocks match

### Test: Block Synchronization
**Result**: ✅ PASS
- Blocks are synchronized

## Summary
- **Total**: 3
- **Passed**: 3
- **Failed**: 0
- **Pass Rate**: 100%
```

---

## 🐛 已知问题

1. **Telnet命令bug** - `stats`, `account`, `balance`命令有地址格式错误
2. **Genesis验证** - 当前只检查响应，未比较genesis哈希
3. **区块同步验证** - 未实现详细的区块哈希比较

---

## 📚 参考文档

- [COMPREHENSIVE_TEST_PLAN.md](./COMPREHENSIVE_TEST_PLAN.md) - 🆕 **全面测试计划** (50个测试用例，基于设计文档)
- [WORKFLOW_SIMULATION.md](./WORKFLOW_SIMULATION.md) - 🆕 测试-修复-验证工作流模拟
- [WALLET_GENESIS_GUIDE.md](./WALLET_GENESIS_GUIDE.md) - 🔑 **钱包和Genesis配置说明** (重要，请勿删除)
- [P2P-SYNC-FINAL-REPORT.md](./P2P-SYNC-FINAL-REPORT.md) - Phase 12.5 P2P测试报告
- [../docs/testing/CONSENSUS_TEST_PLAN.md](../docs/testing/CONSENSUS_TEST_PLAN.md) - 共识测试计划（44个测试场景）
- [../docs/design/CORE_DATA_STRUCTURES.md](../docs/design/CORE_DATA_STRUCTURES.md) - 核心数据结构设计
- [../docs/design/DAG_REFERENCE_RULES.md](../docs/design/DAG_REFERENCE_RULES.md) - DAG引用规则和限制

---

## 🛠️ 工具链总结

### 核心工具 (4个)

1. **update-nodes.sh** - 代码更新和部署
   - `--restart` - 重启节点
   - `--wait-ready` - 等待节点就绪 🆕
   - `--clean` - 清理数据

2. **test-framework.sh** - 自动化测试
   - `run-tests` - 运行所有测试
   - 交互式菜单
   - 生成测试报告

3. **compare-nodes.sh** - 节点状态对比 🆕
   - 快速对比模式
   - 交互模式
   - 需要: expect

4. **watch-logs.sh** - 日志查看 🆕
   - 实时查看（交错/并排）
   - 错误过滤
   - 需要: tmux (可选)

### 文档 (3个)

1. **README.md** (本文档) - 快速入门和工具使用
2. **COMPREHENSIVE_TEST_PLAN.md** - 50个测试用例详细计划
3. **WALLET_GENESIS_GUIDE.md** - 钱包配置（重要，不要删除）

### 测试状态

- ✅ 已实现: 4个测试 (8%)
- ⏳ 计划中: 46个测试 (92%)
- 🎯 下一步: 实施P0核心测试 (18个)

---

## 🤝 开发者指南

### 添加新测试

在 `test-framework.sh` 中添加:

```bash
test_your_new_test() {
    echo "## Test: Your New Test" >> "$TEST_REPORT"
    log_info "Running test: Your New Test"

    # 你的测试逻辑
    if [ condition ]; then
        echo "**Result**: ✅ PASS" >> "$TEST_REPORT"
        log_success "Test passed"
        return 0
    else
        echo "**Result**: ❌ FAIL" >> "$TEST_REPORT"
        log_error "Test failed"
        return 1
    fi
}
```

然后在 `run_all_tests()` 中调用:
```bash
test_your_new_test && ((passed++)) || ((failed++))
```

---

**创建日期**: 2025-11-12
**维护者**: XDAG Development Team
**版本**: v1.0
