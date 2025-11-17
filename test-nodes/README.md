# XDAG 2套件测试环境 (Suite-Based Architecture)

**目的**: 提供2套完整的XDAG挖矿-矿池-节点环境，便于测试从挖矿到出块的完整流程

---

## 🏗️ 架构总览

本测试环境采用**对称双套件架构**，每个套件包含完整的三层组件：

```
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│          Suite 1                │     │          Suite 2                │
│                                 │     │                                 │
│  Miner1 (xdagj-miner)          │     │  Miner2 (xdagj-miner)          │
│       ↓ Stratum (3333)         │     │       ↓ Stratum (3334)         │
│  Pool1 (xdagj-pool)            │     │  Pool2 (xdagj-pool)            │
│       ↓ HTTP RPC (10001)       │     │       ↓ HTTP RPC (10002)       │
│  Node1 (xdagj)                 │     │  Node2 (xdagj)                 │
│       ↓ P2P (8001)             │ ←──→│       ↓ P2P (8002)             │
│                                 │     │                                 │
└─────────────────────────────────┘     └─────────────────────────────────┘
```

**优势**：
- ✅ 完整的端到端流程：Miner → Pool → Node → Blockchain
- ✅ 对称架构便于对比测试和调试
- ✅ 可验证P2P同步、共识、挖矿等完整功能
- ✅ 清晰的组件职责和端口分配

---

## 📁 目录结构

```
test-nodes/
├── suite1/                    # 第一套完整环境
│   ├── node/                  # Node1 - XDAG节点
│   │   ├── xdagj.jar         # 节点程序
│   │   ├── xdag-devnet.conf  # 节点配置
│   │   ├── start.sh          # 启动脚本
│   │   ├── stop.sh           # 停止脚本
│   │   └── devnet/           # 区块链数据目录
│   ├── pool/                  # Pool1 - 矿池服务
│   │   ├── xdagj-pool.jar    # 矿池程序
│   │   └── pool-config.conf  # 矿池配置
│   └── miner/                 # Miner1 - 矿工客户端
│       ├── xdagj-miner.jar   # 矿工程序
│       └── miner-config.local.conf  # 矿工配置
│
├── suite2/                    # 第二套完整环境
│   ├── node/                  # Node2 - XDAG节点
│   ├── pool/                  # Pool2 - 矿池服务
│   └── miner/                 # Miner2 - 矿工客户端
│
├── scripts/                   # 管理脚本
│   ├── start-all.sh          # 启动所有服务
│   ├── stop-all.sh           # 停止所有服务
│   ├── status.sh             # 检查运行状态
│   ├── update-nodes.sh       # 更新节点
│   ├── test-framework.sh     # 测试框架
│   ├── compare-nodes.sh      # 节点对比
│   └── watch-logs.sh         # 日志监控
│
├── docs/                      # 文档
│   ├── README.md             # 本文档
│   ├── COMPREHENSIVE_TEST_PLAN.md
│   ├── WALLET_GENESIS_GUIDE.md
│   └── ...
│
└── README.md                  # 快速入门 (本文档)
```

---

## 🚀 快速开始

### 启动所有服务 (推荐)

```bash
cd test-nodes

# 启动两个套件的所有服务
./scripts/start-all.sh

# 检查运行状态
./scripts/status.sh
```

### 手动启动单个套件

#### Suite 1

```bash
# 1. 启动 Node1
cd suite1/node
./start.sh

# 2. 启动 Pool1 (连接到Node1)
cd ../pool
nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &

# 3. 启动 Miner1 (连接到Pool1)
cd ../miner
nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
```

#### Suite 2

```bash
# 1. 启动 Node2
cd suite2/node
./start.sh

# 2. 启动 Pool2 (连接到Node2)
cd ../pool
nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &

# 3. 启动 Miner2 (连接到Pool2)
cd ../miner
nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
```

### 停止所有服务

```bash
# 停止所有服务
./scripts/stop-all.sh

# 或手动停止
pkill -f xdagj.jar
pkill -f xdagj-pool
pkill -f xdagj-miner
```

---

## ⚙️ 端口配置

### Suite 1 端口分配

| 组件 | 服务 | 端口 | 用途 |
|------|------|------|------|
| Node1 | P2P | 8001 | 节点间通信 |
| Node1 | Telnet | 6001 | 管理控制台 |
| Node1 | HTTP RPC | 10001 | Pool连接 |
| Pool1 | Stratum | 3333 | Miner连接 |
| Miner1 | - | - | 客户端程序 |

### Suite 2 端口分配

| 组件 | 服务 | 端口 | 用途 |
|------|------|------|------|
| Node2 | P2P | 8002 | 节点间通信 |
| Node2 | Telnet | 6002 | 管理控制台 |
| Node2 | HTTP RPC | 10002 | Pool连接 |
| Pool2 | Stratum | 3334 | Miner连接 |
| Miner2 | - | - | 客户端程序 |

**重要**: Suite2的端口都比Suite1增加1，避免冲突

---

## 🔍 监控和调试

### 快速检查状态

```bash
# 使用status脚本 (推荐)
./scripts/status.sh

# 手动检查进程
ps aux | grep -E "xdagj.jar|xdagj-pool|xdagj-miner"

# 检查端口占用
lsof -i :6001,6002,8001,8002,10001,10002,3333,3334
```

### 查看日志

#### Node日志
```bash
# Node1
tail -f suite1/node/logs/xdag-info.log

# Node2
tail -f suite2/node/logs/xdag-info.log

# 并排查看两个节点 (需要tmux)
./scripts/watch-logs.sh --side-by-side
```

#### Pool日志
```bash
# Pool1
tail -f suite1/pool/pool.log | grep -E "Connected|candidate|share"

# Pool2
tail -f suite2/pool/pool.log | grep -E "Connected|candidate|share"
```

#### Miner日志
```bash
# Miner1
tail -f suite1/miner/miner.log | grep -E "Connected|Authorized|share"

# Miner2
tail -f suite2/miner/miner.log | grep -E "Connected|Authorized|share"
```

### 节点管理控制台

```bash
# 连接Node1
telnet localhost 6001
# 密码: root

# 连接Node2
telnet localhost 6002
# 密码: root

# 常用命令:
# stats       - 显示统计信息
# net         - 显示网络连接
# mainblocks  - 显示主链区块
# exit        - 退出
```

---

## 🧪 测试和验证

### 基础验证

```bash
# 1. 对比两个节点状态
./scripts/compare-nodes.sh

# 2. 运行自动化测试
./scripts/test-framework.sh run-tests

# 3. 查看测试报告
cat test-results/test_report_*.md
```

### 验证挖矿流程

#### 检查连接链路

```bash
# 1. 验证Miner1连接到Pool1
grep "Authorized successfully" suite1/miner/miner.log

# 2. 验证Pool1连接到Node1
grep "connected to node" suite1/pool/pool.log

# 3. 验证Pool1获取候选区块
grep "New candidate block" suite1/pool/pool.log

# 4. 验证Miner1找到share
grep "Found valid share" suite1/miner/miner.log | wc -l
```

#### 检查P2P同步

```bash
# 使用telnet连接两个节点
expect scripts/check_nodes.exp

# 或手动比较
telnet localhost 6001
# 输入: mainblocks 10

telnet localhost 6002
# 输入: mainblocks 10
# 比较两个节点的区块列表
```

---

## 🔧 配置说明

### Node配置 (xdag-devnet.conf)

**关键配置项**:
```hocon
# suite1/node/xdag-devnet.conf
node.ip = "127.0.0.1"
node.port = 8001              # Suite1: 8001, Suite2: 8002
node.telnet.port = 6001       # Suite1: 6001, Suite2: 6002
node.rpc.port = 10001         # Suite1: 10001, Suite2: 10002
node.whiteIPList = ["127.0.0.1"]
```

### Pool配置 (pool-config.conf)

**Suite1 Pool配置**:
```hocon
pool {
  id = "test-pool-node1"
  name = "Test Pool for Node1"

  node {
    rpcUrl = "http://localhost:10001"  # 连接Node1
    timeout = 30s
  }

  stratum {
    host = "0.0.0.0"
    port = 3333                        # Pool1使用3333
    initialDifficulty = 1000000000     # Share难度(10亿)
  }

  mining {
    blockInterval = 64s
  }
}
```

**Suite2 Pool配置**:
```hocon
pool {
  id = "test-pool-node2"
  name = "Test Pool for Node2"

  node {
    rpcUrl = "http://localhost:10002"  # 连接Node2
    ...
  }

  stratum {
    port = 3334                        # Pool2使用3334
    ...
  }
}
```

### Miner配置 (miner-config.local.conf)

**Suite1 Miner配置**:
```hocon
miner {
  pool {
    host = "localhost"
    port = 3333                        # 连接Pool1
  }

  worker {
    address = "nCR7vmdnVzsZ24VtqqurSmC7WLMF8Ymj"
    name = "test-miner-1"              # Miner1
    password = "x"
  }

  mining {
    threads = 2
    algorithm = "auto"
    nonceRange = 10000
  }
}
```

**Suite2 Miner配置**:
```hocon
miner {
  pool {
    port = 3334                        # 连接Pool2
  }

  worker {
    name = "test-miner-2"              # Miner2
    ...
  }
}
```

---

## 🛠️ 管理脚本

### scripts/start-all.sh
启动两个套件的所有服务（Node + Pool + Miner）

### scripts/stop-all.sh
停止所有服务

### scripts/status.sh
检查所有组件运行状态和端口占用

### scripts/update-nodes.sh
编译最新代码并更新节点
```bash
# 编译并部署
./scripts/update-nodes.sh

# 编译、部署并重启
./scripts/update-nodes.sh --restart

# 编译、部署、重启并等待就绪
./scripts/update-nodes.sh --restart --wait-ready
```

### scripts/compare-nodes.sh
对比两个节点状态
```bash
# 快速对比
./scripts/compare-nodes.sh

# 交互模式
./scripts/compare-nodes.sh --interactive
```

### scripts/watch-logs.sh
实时查看日志
```bash
# 交错模式
./scripts/watch-logs.sh --watch

# 并排模式 (需要tmux)
./scripts/watch-logs.sh --side-by-side

# 只看错误
./scripts/watch-logs.sh --errors
```

---

## 📊 理解组件关系

### 三层架构说明

1. **Miner (矿工)**
   - 执行哈希计算，寻找有效share
   - 通过Stratum协议连接Pool
   - 提交找到的share到Pool

2. **Pool (矿池)**
   - 管理多个Miner连接
   - 从Node获取候选区块 (via HTTP RPC)
   - 分配挖矿任务给Miner
   - 验证Miner提交的share
   - 将解决的区块提交回Node (Phase 2)

3. **Node (节点)**
   - 维护区块链状态
   - 提供候选区块给Pool
   - 接受新区块并广播
   - 与其他节点P2P同步

### Share vs Block

- **Share**: 满足较低难度的哈希结果
  - 用于追踪矿工工作量
  - Pool用来计算收益分配
  - 测试环境: 难度很低，经常找到

- **Block**: 满足区块难度的哈希结果
  - 用于创建实际区块链区块
  - 难度很高，很少找到
  - 成功后节点增加区块链高度

### 当前限制 (Phase 1)

⚠️ **重要**: 当前实现为 **Phase 1 - 候选区块获取**

**已实现**:
- ✅ Pool从Node获取候选区块
- ✅ Miner连接Pool并获取任务
- ✅ Miner提交share到Pool
- ✅ Pool验证share有效性

**未实现 (Phase 2)**:
- ❌ Pool提交区块回Node
- ❌ Node接受并导入Pool提交的区块
- ❌ 区块链高度增长

**预期行为**:
- ✅ Miner正常挖矿并找到shares
- ✅ Pool正常接收和验证shares
- ❌ 区块链**不会**增长（等待Phase 2）

---

## 🐛 故障排除

### Miner无法连接Pool

```bash
# 检查Pool进程
ps aux | grep xdagj-pool

# 检查Pool端口
lsof -i :3333   # Pool1
lsof -i :3334   # Pool2

# 检查Pool日志
tail -20 suite1/pool/pool.log
```

### Pool无法连接Node

```bash
# 检查Node进程
ps aux | grep xdagj.jar

# 检查Node HTTP RPC端口
lsof -i :10001   # Node1
lsof -i :10002   # Node2

# 测试HTTP API
curl -X POST http://localhost:10001/api/v1/mining \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"mining_getCandidateBlock","params":["test-pool"],"id":1}'
```

### P2P连接失败

```bash
# 检查两个节点是否都在运行
ps aux | grep xdagj.jar

# 检查P2P端口
lsof -i :8001,8002

# 查看连接状态
telnet localhost 6001
# 输入: net
```

### Stale Share现象

**现象**: Pool日志显示大量 "Stale share" 或 Miner日志显示很多share被拒绝

**原因**:
- 测试环境难度极低
- Miner提交share时Pool已切换到新job
- 这是**正常现象**，不影响功能验证

**解决**: 无需处理，系统工作正常

---

## 📚 参考文档

- [docs/COMPREHENSIVE_TEST_PLAN.md](./docs/COMPREHENSIVE_TEST_PLAN.md) - 全面测试计划
- [docs/WALLET_GENESIS_GUIDE.md](./docs/WALLET_GENESIS_GUIDE.md) - 钱包和Genesis配置
- [docs/P2P-SYNC-FINAL-REPORT.md](./docs/P2P-SYNC-FINAL-REPORT.md) - P2P同步测试报告
- [docs/WORKFLOW_SIMULATION.md](./docs/WORKFLOW_SIMULATION.md) - 工作流模拟

---

## 🎯 典型使用场景

### 场景1: 验证完整挖矿流程

```bash
# 1. 启动所有服务
./scripts/start-all.sh

# 2. 等待系统稳定 (30秒)
sleep 30

# 3. 验证Suite1连接
grep "Authorized successfully" suite1/miner/miner.log
grep "connected to node" suite1/pool/pool.log

# 4. 验证Suite2连接
grep "Authorized successfully" suite2/miner/miner.log
grep "connected to node" suite2/pool/pool.log

# 5. 查看挖矿统计
# Miner每60秒会打印统计信息
tail -f suite1/miner/miner.log | grep "Hashrate"
```

### 场景2: 开发新功能后测试

```bash
# 1. 修改代码
vim ../src/main/java/io/xdag/...

# 2. 编译并更新
./scripts/update-nodes.sh --restart --wait-ready

# 3. 对比节点状态
./scripts/compare-nodes.sh

# 4. 运行测试
./scripts/test-framework.sh run-tests

# 5. 查看结果
cat test-results/test_report_*.md
```

### 场景3: 调试问题

```bash
# 1. 并排查看两个节点日志
./scripts/watch-logs.sh --side-by-side

# 2. 只查看错误
./scripts/watch-logs.sh --errors

# 3. 过滤特定内容
./scripts/watch-logs.sh --filter "Block imported"

# 4. 检查Pool和Miner状态
./scripts/status.sh
```

---

## 📞 支持

如有问题，请参考:
1. 本文档故障排除章节
2. docs/ 目录下的详细文档
3. 查看组件日志文件

---

**创建日期**: 2025-11-16
**架构版本**: v2.0 (Suite-Based)
**维护者**: XDAG Development Team
