# 快速参考 - 测试环境命令

## 核心管理脚本

### 启动/停止服务
```bash
# 启动所有服务 (2个节点 + 2个矿池 + 2个矿工)
./scripts/start-all.sh

# 停止所有服务
./scripts/stop-all.sh

# 检查运行状态
./scripts/status.sh
```

### 开发更新
```bash
# 编译最新代码并更新节点
./scripts/update-nodes.sh

# 编译、更新并重启
./scripts/update-nodes.sh --restart

# 编译、更新、重启并等待就绪
./scripts/update-nodes.sh --restart --wait-ready
```

### 监控和调试
```bash
# 对比两个节点状态
./scripts/compare-nodes.sh

# 实时查看日志 (交错模式)
./scripts/watch-logs.sh --watch

# 并排查看日志 (需要tmux)
./scripts/watch-logs.sh --side-by-side

# 只看错误日志
./scripts/watch-logs.sh --errors
```

### 测试
```bash
# 运行所有测试
./scripts/test-framework.sh run-tests

# 查看测试报告
cat test-results/test_report_*.md
```

## 手动操作 (高级)

### 启动单个套件

#### Suite 1
```bash
# Node1
cd suite1/node && ./start.sh

# Pool1
cd suite1/pool && nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &

# Miner1
cd suite1/miner && nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
```

#### Suite 2
```bash
# Node2
cd suite2/node && ./start.sh

# Pool2
cd suite2/pool && nohup java -jar xdagj-pool.jar -c pool-config.conf > pool.log 2>&1 &

# Miner2
cd suite2/miner && nohup java -jar xdagj-miner.jar > miner.log 2>&1 &
```

### 查看日志
```bash
# Node日志
tail -f suite1/node/logs/xdag-info.log
tail -f suite2/node/logs/xdag-info.log

# Pool日志
tail -f suite1/pool/pool.log
tail -f suite2/pool/pool.log

# Miner日志
tail -f suite1/miner/miner.log
tail -f suite2/miner/miner.log
```

### 连接节点控制台
```bash
# Node1 (密码: root)
telnet localhost 6001

# Node2 (密码: root)
telnet localhost 6002

# 常用命令:
# stats       - 显示统计信息
# net         - 显示网络连接
# mainblocks  - 显示主链区块
# exit        - 退出
```

### 检查进程和端口
```bash
# 检查所有进程
ps aux | grep -E "xdagj.jar|xdagj-pool|xdagj-miner"

# 检查端口占用
lsof -i :6001,6002,8001,8002,10001,10002,3333,3334

# 强制停止所有
pkill -9 -f xdagj.jar
pkill -9 -f xdagj-pool
pkill -9 -f xdagj-miner
```

## 端口分配

| 组件 | Suite1 | Suite2 | 用途 |
|------|--------|--------|------|
| Node P2P | 8001 | 8002 | 节点间通信 |
| Node Telnet | 6001 | 6002 | 管理控制台 |
| Node HTTP | 10001 | 10002 | Pool RPC连接 |
| Pool Stratum | 3333 | 3334 | Miner连接 |

## 典型工作流

### 开发调试流程
```bash
# 1. 修改代码
vim ../src/main/java/io/xdag/...

# 2. 更新并重启
./scripts/update-nodes.sh --restart --wait-ready

# 3. 检查状态
./scripts/status.sh

# 4. 查看日志
./scripts/watch-logs.sh --watch

# 5. 对比节点
./scripts/compare-nodes.sh
```

### 验证挖矿流程
```bash
# 1. 启动所有服务
./scripts/start-all.sh

# 2. 等待30秒稳定
sleep 30

# 3. 检查连接状态
./scripts/status.sh

# 4. 验证Miner1连接
grep "Authorized successfully" suite1/miner/miner.log

# 5. 验证Pool1连接
grep "connected to node" suite1/pool/pool.log

# 6. 查看挖矿统计
tail -f suite1/miner/miner.log | grep "Hashrate"
```

## 目录结构

```
test-nodes/
├── suite1/          # 第一套完整环境
│   ├── node/        # Node1
│   ├── pool/        # Pool1
│   └── miner/       # Miner1
├── suite2/          # 第二套完整环境
│   ├── node/        # Node2
│   ├── pool/        # Pool2
│   └── miner/       # Miner2
├── scripts/         # 管理脚本
└── docs/            # 文档
```

## 故障排除

### Pool无法连接Node
```bash
# 检查Node HTTP RPC是否启动
lsof -i :10001  # Node1
lsof -i :10002  # Node2

# 测试HTTP API
curl -X POST http://localhost:10001/api/v1/mining \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"mining_getCandidateBlock","params":["test-pool"],"id":1}'
```

### Miner无法连接Pool
```bash
# 检查Pool Stratum端口
lsof -i :3333  # Pool1
lsof -i :3334  # Pool2

# 查看Pool日志
tail -20 suite1/pool/pool.log
```

### 节点P2P连接失败
```bash
# 检查P2P端口
lsof -i :8001,8002

# 查看节点日志
tail -20 suite1/node/logs/xdag-info.log
tail -20 suite2/node/logs/xdag-info.log
```

## 清理环境

```bash
# 停止所有服务
./scripts/stop-all.sh

# 删除日志
find suite1 suite2 -name "*.log" -delete
find suite1 suite2 -name "*.pid" -delete

# 清理节点数据 (重新开始)
rm -rf suite1/node/devnet/*
rm -rf suite2/node/devnet/*
```

---

**更多信息**: 查看 README.md 和 docs/ 目录
