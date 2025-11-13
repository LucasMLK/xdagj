# XDAG v5.1 RPC API 重新设计

## 当前问题分析

### 1. 接口命名不规范
- `xdag_getBlockByHash` 有5个重载版本，参数混乱
- `xdag_getBalanceByNumber` - 语义不清
- 分页、时间过滤等参数混入基础查询方法

### 2. 参数设计不一致
- 有些用 String，有些用 int
- 缺少标准的区块标识符（latest/earliest/pending）
- 分页参数不统一

### 3. 返回值不统一
- 有些返回 String，有些返回 Object
- 缺少标准的JSON结构

### 4. 缺少关键接口
- 缺少交易回执（Transaction Receipt）
- 缺少批量查询接口
- 缺少挖矿相关接口

## 参考设计

### Bitcoin RPC特点
- 简单直接：`getblock`, `getblockcount`, `sendtoaddress`
- 参数明确：可选参数使用默认值
- 返回JSON对象

### Ethereum RPC特点（JSON-RPC 2.0）
- 命名规范：`eth_*`, `net_*`, `web3_*`
- 标准参数：区块标识符（hex/latest/earliest/pending）
- 统一返回：所有数据用hex编码（0x前缀）
- 明确语义：`getTransactionByHash` vs `getTransactionReceipt`

## 新设计方案

### 设计原则

1. **命名规范**：遵循 `namespace_method` 格式
2. **参数标准化**：统一使用标准类型
3. **返回值统一**：所有方法返回结构化JSON
4. **向后兼容**：保留旧接口但标记为 @Deprecated

### 命名空间划分

- `xdag_*` - XDAG核心功能（区块、交易、账户）
- `net_*` - 网络相关
- `miner_*` - 挖矿相关
- `admin_*` - 管理功能
- `debug_*` - 调试功能（可选）

---

## 新接口设计

### 一、账户相关 (Account)

#### 1.1 xdag_accounts
获取本地钱包账户列表

**参数：** 无

**返回：**
```json
{
  "accounts": [
    {
      "address": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",  // Base58地址
      "balance": "1000.123456789",                            // XDAG余额
      "nonce": 42,                                             // 账户nonce
      "type": "hd"                                             // 账户类型：hd/imported
    }
  ]
}
```

#### 1.2 xdag_getBalance
获取指定地址的余额

**参数：**
- `address` (String): Base58地址
- `blockNumber` (String, optional): 区块号（hex "0x1234" 或 "latest"/"earliest"）

**返回：**
```json
{
  "address": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",
  "balance": "1000.123456789",  // XDAG单位
  "blockNumber": "0x1234"
}
```

#### 1.3 xdag_getTransactionCount
获取地址的交易nonce

**参数：**
- `address` (String): Base58地址
- `blockNumber` (String, optional): 区块号

**返回：**
```json
{
  "address": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",
  "nonce": "0x2a",  // hex编码
  "blockNumber": "0x1234"
}
```

---

### 二、区块相关 (Block)

#### 2.1 xdag_blockNumber
获取最新主链区块高度

**参数：** 无

**返回：**
```json
{
  "blockNumber": "0x1234"  // hex编码的高度
}
```

#### 2.2 xdag_getBlockByNumber
按高度获取区块详情

**参数：**
- `blockNumber` (String): 区块号（hex "0x1234" 或 "latest"/"earliest"）
- `fullTransactions` (Boolean, optional): 是否返回完整交易（默认false）

**返回：**
```json
{
  "number": "0x1234",
  "hash": "0xabcd...",
  "timestamp": "0x...",
  "epoch": "0x...",
  "difficulty": "0x...",
  "coinbase": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",
  "state": "main",  // main/orphan
  "transactionCount": 10,
  "transactions": [
    "0xtxhash1...",  // 如果fullTransactions=false
    // 或完整交易对象（如果fullTransactions=true）
    {
      "hash": "0xtxhash1...",
      "from": "address1",
      "to": "address2",
      "amount": "100.0",
      "fee": "0.1",
      "nonce": "0x1",
      "blockNumber": "0x1234",
      "blockHash": "0xabcd...",
      "status": "confirmed"
    }
  ],
  "links": [
    {
      "hash": "0xlinkhash...",
      "height": "0x1233",
      "type": "parent"
    }
  ]
}
```

#### 2.3 xdag_getBlockByHash
按哈希获取区块详情

**参数：**
- `blockHash` (String): 区块哈希（hex with 0x prefix）
- `fullTransactions` (Boolean, optional): 是否返回完整交易

**返回：** 同 `xdag_getBlockByNumber`

#### 2.4 xdag_getBlockTransactionCountByHash
获取区块的交易数量

**参数：**
- `blockHash` (String): 区块哈希

**返回：**
```json
{
  "blockHash": "0xabcd...",
  "transactionCount": "0xa"  // hex编码
}
```

#### 2.5 xdag_getBlockTransactionCountByNumber
按高度获取区块的交易数量

**参数：**
- `blockNumber` (String): 区块号

**返回：** 同上

---

### 三、交易相关 (Transaction)

#### 3.1 xdag_getTransactionByHash
获取交易详情

**参数：**
- `transactionHash` (String): 交易哈希（0x前缀）

**返回：**
```json
{
  "hash": "0xtxhash...",
  "from": "address1",
  "to": "address2",
  "amount": "100.0",
  "fee": "0.1",
  "nonce": "0x1",
  "data": "0x...",  // 交易数据（hex）
  "signature": {
    "v": "0x1c",
    "r": "0x...",
    "s": "0x..."
  },
  "blockNumber": "0x1234",
  "blockHash": "0xabcd...",
  "timestamp": "0x...",
  "status": "confirmed",  // confirmed/pending/failed
  "valid": true,
  "signatureValid": true
}
```

#### 3.2 xdag_getTransactionByBlockHashAndIndex
获取区块中指定索引的交易

**参数：**
- `blockHash` (String): 区块哈希
- `transactionIndex` (String): 交易索引（hex，从0x0开始）

**返回：** 同 `xdag_getTransactionByHash`

#### 3.3 xdag_getTransactionByBlockNumberAndIndex
获取区块中指定索引的交易

**参数：**
- `blockNumber` (String): 区块号
- `transactionIndex` (String): 交易索引

**返回：** 同 `xdag_getTransactionByHash`

#### 3.4 xdag_sendRawTransaction
发送已签名的交易

**参数：**
- `signedTransactionData` (String): 签名后的交易数据（hex with 0x prefix）

**返回：**
```json
{
  "transactionHash": "0xtxhash...",
  "status": "pending"
}
```

#### 3.5 xdag_pendingTransactions
获取待处理交易列表

**参数：** 无

**返回：**
```json
{
  "transactions": [
    // 交易对象数组
  ]
}
```

---

### 四、网络相关 (Network)

#### 4.1 xdag_syncing
获取同步状态

**参数：** 无

**返回（同步中）：**
```json
{
  "syncing": true,
  "startingBlock": "0x0",
  "currentBlock": "0x1234",
  "highestBlock": "0x5678",
  "progress": 35.5  // 百分比
}
```

**返回（已同步）：**
```json
{
  "syncing": false
}
```

#### 4.2 xdag_chainId
获取链ID

**参数：** 无

**返回：**
```json
{
  "chainId": "0x1",  // 1=mainnet, 2=testnet, 3=devnet
  "networkType": "mainnet"
}
```

#### 4.3 net_version
获取网络版本

**参数：** 无

**返回：**
```json
{
  "version": "5.1.0"
}
```

#### 4.4 net_peerCount
获取对等节点数

**参数：** 无

**返回：**
```json
{
  "peerCount": "0xa"  // hex编码
}
```

#### 4.5 net_listening
节点是否在监听

**参数：** 无

**返回：**
```json
{
  "listening": true
}
```

#### 4.6 net_peers
获取对等节点详情

**参数：** 无

**返回：**
```json
{
  "peers": [
    {
      "id": "peer1",
      "host": "192.168.1.100",
      "port": 8001,
      "version": "5.1.0",
      "type": "outbound",
      "latency": 50,
      "lastSeen": "0x..."
    }
  ]
}
```

---

### 五、挖矿相关 (Mining)

#### 5.1 xdag_coinbase
获取挖矿收益地址

**参数：** 无

**返回：**
```json
{
  "coinbase": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi"
}
```

#### 5.2 xdag_mining
节点是否在挖矿

**参数：** 无

**返回：**
```json
{
  "mining": true
}
```

#### 5.3 xdag_hashrate
获取当前算力

**参数：** 无

**返回：**
```json
{
  "hashrate": "0x...",  // hex编码的hash/s
  "hashrateHuman": "1.5 MH/s"
}
```

#### 5.4 miner_start
开始挖矿

**参数：**
- `threads` (Number, optional): 挖矿线程数

**返回：**
```json
{
  "success": true,
  "mining": true
}
```

#### 5.5 miner_stop
停止挖矿

**参数：** 无

**返回：**
```json
{
  "success": true,
  "mining": false
}
```

---

### 六、Epoch相关 (XDAG特有)

#### 6.1 xdag_epochNumber
获取当前epoch

**参数：** 无

**返回：**
```json
{
  "epochNumber": "0x1234"
}
```

#### 6.2 xdag_getEpoch
获取epoch详情

**参数：**
- `epochNumber` (String, optional): epoch号（默认为当前epoch）

**返回：**
```json
{
  "epochNumber": "0x1234",
  "isCurrent": true,
  "startTime": "0x...",
  "endTime": "0x...",
  "duration": "0x40",  // 64秒
  "elapsed": "0x20",   // 已过时间（仅当前epoch）
  "progress": 50.0,    // 进度百分比（仅当前epoch）
  "candidateCount": 10,
  "winner": {
    "hash": "0x...",
    "height": "0x1234"
  },
  "orphanCount": 9,
  "averageDifficulty": "0x...",
  "totalDifficulty": "0x..."
}
```

#### 6.3 xdag_getBlocksByEpoch
获取epoch中的区块

**参数：**
- `epochNumber` (String): epoch号
- `offset` (Number, optional): 偏移量（默认0）
- `limit` (Number, optional): 数量限制（默认100，最大1000）

**返回：**
```json
{
  "epochNumber": "0x1234",
  "total": 100,
  "offset": 0,
  "limit": 100,
  "blocks": [
    // 区块摘要对象数组
  ]
}
```

---

### 七、统计相关 (Statistics)

#### 7.1 xdag_getStats
获取区块链统计信息

**参数：** 无

**返回：**
```json
{
  "mainBlockCount": "0x1234",
  "totalBlockCount": "0x5678",
  "topBlockHeight": "0x1234",
  "topBlockHash": "0xabcd...",
  "currentEpoch": "0x100",
  "currentDifficulty": "0x...",
  "maxDifficulty": "0x...",
  "orphanCount": "0xa",
  "waitingSyncCount": "0x0",
  "syncProgress": 100.0,
  "networkHashrate": "0x...",
  "supply": {
    "total": "1000000000.0",  // 总供应量
    "circulating": "500000000.0"  // 流通量
  },
  "wallet": {
    "balance": "1000.0",
    "accountCount": 5
  }
}
```

#### 7.2 xdag_getDifficulty
获取当前难度

**参数：** 无

**返回：**
```json
{
  "difficulty": "0x...",
  "maxDifficulty": "0x..."
}
```

---

### 八、节点信息 (Node Info)

#### 8.1 xdag_protocolVersion
获取协议版本

**参数：** 无

**返回：**
```json
{
  "protocolVersion": "5.1.0"
}
```

#### 8.2 xdag_nodeInfo
获取节点详细信息

**参数：** 无

**返回：**
```json
{
  "version": "5.1.0",
  "protocolVersion": "5.1.0",
  "networkId": "0x1",
  "networkType": "mainnet",
  "nodeId": "...",
  "listenAddr": "0.0.0.0:8001",
  "rpcAddr": "127.0.0.1:10001",
  "uptime": 86400,  // 秒
  "peers": 10,
  "mining": true,
  "syncing": false
}
```

---

## 数据类型标准

### 1. 数值类型
- 所有数值使用 **hex string** with **0x prefix**
- 例如：`"0x1234"` 表示 4660

### 2. 地址类型
- 使用 **Base58编码**（XDAG 20字节地址）
- 例如：`"4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi"`

### 3. 哈希类型
- 使用 **hex string** with **0x prefix**（32字节）
- 例如：`"0xabcd1234..."`

### 4. 区块标识符
- `"latest"` - 最新区块
- `"earliest"` - 创世区块
- `"pending"` - 待处理区块
- `"0x1234"` - 具体区块号（hex）

### 5. 金额类型
- 使用 **decimal string** 表示XDAG单位
- 精度：9位小数
- 例如：`"1000.123456789"`

---

## 向后兼容

### 旧接口保留（标记为 @Deprecated）

所有旧接口保留但标记为 `@Deprecated`，建议在文档中说明迁移路径：

```
@Deprecated
xdag_getBlockByHash(hash, page, pageSize)
  → 使用 xdag_getBlockByHash(hash, fullTransactions)
  → 分页需求使用 xdag_getBlocksByEpoch 或其他批量接口
```

---

## 实现优先级

### Phase 1 - 核心接口（高优先级）
- [x] 账户：accounts, getBalance, getTransactionCount
- [x] 区块：blockNumber, getBlockByNumber, getBlockByHash
- [x] 交易：getTransactionByHash, sendRawTransaction
- [x] 网络：syncing, chainId, peerCount

### Phase 2 - 扩展接口（中优先级）
- [ ] 交易：getTransactionByBlockHashAndIndex, pendingTransactions
- [ ] Epoch：epochNumber, getEpoch, getBlocksByEpoch
- [ ] 挖矿：mining, hashrate, miner_start/stop
- [ ] 统计：getStats, getDifficulty

### Phase 3 - 高级接口（低优先级）
- [ ] 批量查询接口
- [ ] 历史数据查询
- [ ] 调试接口（debug_*）
- [ ] 管理接口（admin_*）

---

## 测试计划

1. **单元测试**：每个RPC方法独立测试
2. **集成测试**：与真实节点交互测试
3. **兼容性测试**：旧接口向后兼容测试
4. **性能测试**：高并发场景测试
5. **文档测试**：确保所有示例可运行

---

## 参考资料

- [Ethereum JSON-RPC Specification](https://ethereum.org/en/developers/docs/apis/json-rpc/)
- [Bitcoin RPC API](https://developer.bitcoin.org/reference/rpc/)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)
