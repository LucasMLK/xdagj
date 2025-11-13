# 钱包和Genesis配置说明

**目的**: 记录测试节点的钱包生成和genesis配置方法

---

## 📋 当前配置

### Genesis配置文件

测试节点使用的genesis配置：
- **Node1**: `node1/devnet/genesis.json` (自动生成)
- **Node2**: `node2/genesis.json` + `node2/devnet/genesis.json`

### 钱包文件位置

钱包数据文件：
- **Node1**: `node1/devnet/wallet/wallet.data`
- **Node2**: `node2/devnet/wallet/wallet.data`

---

## 🔑 钱包生成方式

### 自动生成（推荐）

节点启动时会自动创建钱包：

```bash
# 节点启动时使用 --password 参数
java -jar xdagj.jar -d --password test123

# 框架脚本中的启动方式
cd node1
bash start.sh  # 内部包含 --password test123
```

**优点**:
- ✅ 自动创建deterministic genesis
- ✅ 两个节点使用相同的genesis配置
- ✅ 不需要手动配置

### 手动配置Genesis（高级）

如果需要自定义genesis配置，可以参考 `config/genesis-devnet.json`：

```json
{
  "networkId": "devnet",
  "chainId": 1,
  "timestamp": 1516406400,
  "initialDifficulty": "0xFFFF...",
  "genesisCoinbase": "0x1111...",
  "epochLength": 64,
  "extraData": "XDAG Devnet Genesis Block",
  "alloc": {},
  "snapshot": {
    "enabled": false
  }
}
```

**关键参数**:
- `timestamp`: genesis区块时间戳（所有节点必须相同）
- `genesisCoinbase`: genesis矿工地址（32字节）
- `initialDifficulty`: 初始难度
- `epochLength`: epoch长度（秒）

---

## 🔄 重新生成钱包

### 方法1: 清理数据目录

```bash
# 使用框架工具
cd test-nodes
./update-nodes.sh --clean

# 手动清理
rm -rf node1/devnet
rm -rf node2/devnet
```

**效果**: 节点下次启动时会重新创建钱包和genesis

### 方法2: 仅删除钱包

```bash
# 删除钱包但保留区块数据
rm -rf node1/devnet/wallet
rm -rf node2/devnet/wallet
```

**效果**: 节点下次启动时会重新创建钱包，但保留区块链数据

---

## 🎯 Deterministic Genesis

### 什么是Deterministic Genesis？

两个节点在没有预先约定的情况下，能创建完全相同的genesis区块。

### 实现方式

**Phase 12.5实现** (commit: 76296a70):
- ✅ 非交互式钱包创建
- ✅ 使用相同的genesis配置
- ✅ 所有节点创建相同的genesis hash

**验证方法**:
```bash
# 查看Node1的genesis
cd node1/devnet
cat genesis.json

# 查看Node2的genesis
cd node2/devnet
cat genesis.json

# 两者应该完全相同
```

---

## 📚 参考示例

### Base58地址示例

`config/genesis-base58-example.json` 包含Base58格式的地址配置：

```json
{
  "networkId": "devnet",
  "chainId": 1,
  "timestamp": 1516406400,
  "initialDifficulty": "0xFFFF...",
  "genesisCoinbase": "Base58格式地址",
  "alloc": {
    "Base58地址1": "1000000000000000000000",
    "Base58地址2": "1000000000000000000000"
  }
}
```

### 测试网配置

`config/genesis-testnet.json` 包含testnet配置

### 主网配置

`config/genesis-mainnet.json` 包含mainnet配置

---

## ⚠️ 重要注意事项

### 不要清理的文件

- ✅ `node1/devnet/genesis.json` - Node1的genesis配置
- ✅ `node2/genesis.json` - Node2的genesis配置模板
- ✅ `node2/devnet/genesis.json` - Node2的实际genesis配置
- ✅ `config/genesis-*.json` - 各种genesis配置示例

### 清理时要小心

```bash
# ❌ 危险：会删除所有数据包括钱包
rm -rf node1/* node2/*

# ✅ 安全：只清理运行时数据
./update-nodes.sh --clean
```

---

## 🔍 故障排查

### 问题1: Genesis不一致

**症状**: 两个节点的genesis hash不同

**解决**:
```bash
# 1. 停止节点
./update-nodes.sh --clean

# 2. 确保两个节点的genesis配置相同
diff node1/devnet/genesis.json node2/devnet/genesis.json

# 3. 重新启动
./update-nodes.sh --restart
```

### 问题2: 钱包文件损坏

**症状**: 节点启动失败，报告钱包错误

**解决**:
```bash
# 删除钱包，保留区块数据
rm -rf node1/devnet/wallet
rm -rf node2/devnet/wallet

# 重启节点会自动重建钱包
./test-framework.sh
# 选择: 3 (Restart Both Nodes)
```

### 问题3: 无法启动节点

**症状**: PID文件存在但进程不存在

**解决**:
```bash
# 清理stale PID文件
rm -f node1/xdag.pid node2/xdag.pid

# 重新启动
./test-framework.sh
# 选择: 1 (Start Both Nodes)
```

---

## 📝 最佳实践

### 1. 测试前备份

```bash
# 备份整个测试环境
tar -czf test-nodes-backup.tar.gz test-nodes/
```

### 2. 定期清理

```bash
# 每周清理一次日志
find test-nodes -name "*.log" -mtime +7 -delete
```

### 3. 版本控制

```bash
# 不要提交节点数据到git
echo "test-nodes/node*/devnet/" >> .gitignore
echo "test-nodes/node*/logs/" >> .gitignore
echo "test-nodes/node*/*.log" >> .gitignore
echo "test-nodes/node*/xdag.pid" >> .gitignore
```

---

## 🎓 相关文档

- [README.md](./README.md) - 测试框架使用说明
- [P2P-SYNC-FINAL-REPORT.md](./P2P-SYNC-FINAL-REPORT.md) - P2P同步测试报告
- `../config/genesis-devnet.json` - Devnet genesis配置模板
- `../config/genesis-base58-example.json` - Base58地址示例

---

**创建日期**: 2025-11-12
**维护者**: XDAG Development Team
**版本**: v1.0
**重要性**: ⭐⭐⭐ 请勿删除
