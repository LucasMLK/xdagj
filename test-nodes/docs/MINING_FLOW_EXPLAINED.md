# XDAG 出块流程详解

理解 xdagj (节点) → xdagj-pool (矿池) → xdagj-miner (矿工) 如何协作完成出块

---

## 一、组件角色

### 1. xdagj (节点/Node)
**职责**: 区块链核心，维护区块链状态

- 维护完整的区块链数据
- 验证和存储区块
- 提供P2P网络连接，与其他节点同步
- 提供HTTP RPC API给Pool调用
- **生成候选区块模板** (待挖矿的区块)
- **接受已挖出的区块** (Pool提交)

**关键API**:
```
POST /api/v1/mining
{
  "method": "mining_getCandidateBlock",  // 获取候选区块
  "params": ["pool-id"]
}

POST /api/v1/mining
{
  "method": "mining_submitBlock",        // 提交已挖区块
  "params": [block_data]
}
```

### 2. xdagj-pool (矿池/Pool)
**职责**: 连接Node和Miner，管理挖矿任务分配

- 从Node获取候选区块模板
- 通过Stratum协议管理多个Miner连接
- 将候选区块转换为挖矿任务(job)分发给Miner
- 验证Miner提交的share
- **将符合条件的share作为区块提交回Node**

**关键功能**:
- Stratum Server (端口3333/3334)
- 候选区块管理和job生成
- Share验证 (检查是否满足难度)
- 统计和分账

### 3. xdagj-miner (矿工/Miner)
**职责**: 执行实际的哈希计算

- 连接Pool的Stratum服务器
- 接收挖矿任务(job)
- 执行哈希计算 (SHA256 或 RandomX)
- 找到符合难度的nonce后提交share给Pool
- 不需要知道区块链状态，只负责计算

---

## 二、完整出块流程

### Phase 0: 初始化连接

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │ ◄─────────────────┤                   │
     │  HTTP连接 (10001)  │                   │
     │                   │                   │
     │                   │ ◄─────────────────┤
     │                   │  Stratum连接(3333) │
     │                   │                   │
```

**步骤**:
1. Pool启动，连接Node的HTTP RPC (localhost:10001)
2. Miner启动，连接Pool的Stratum端口 (localhost:3333)
3. Miner发送 `mining.subscribe` 和 `mining.authorize`
4. Pool验证Miner身份，准备发送任务

### Phase 1: 获取候选区块

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │ ◄─── 1. getCandidateBlock()           │
     │ ──── 2. 候选区块 ──────►               │
     │      {                                │
     │        hash: "0xabc...",             │
     │        difficulty: "0x00ff...",      │
     │        algorithm: "SHA256",          │
     │        ...                           │
     │      }                                │
     │                   │                   │
```

**Node生成候选区块**:
```java
// Node创建一个新的区块模板
Block candidateBlock = new Block();
candidateBlock.setTimestamp(System.currentTimeMillis());
candidateBlock.setDifficulty(currentDifficulty);
candidateBlock.setNonce(0);  // 初始nonce为0
candidateBlock.setHash(computeHash(candidateBlock));

// 返回给Pool
return BlockDto.fromBlock(candidateBlock);
```

**候选区块包含**:
- 区块头数据 (时间戳、前置区块引用等)
- 难度target (决定多难才能找到有效区块)
- 算法类型 (SHA256 或 RandomX)
- **nonce字段为空** (等待矿工填充)

### Phase 2: Pool分发挖矿任务

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │                   ├─── 3. mining.notify ────►
     │                   │    {                     │
     │                   │      job_id: "abc123",   │
     │                   │      block_hash: "0x...", │
     │                   │      seed: "...",         │
     │                   │      target: "0xff..."    │
     │                   │    }                      │
     │                   │                   │
```

**Pool转换为Stratum job**:
```java
// Pool将候选区块转换为Stratum格式的job
StratumJob job = new StratumJob();
job.setJobId(candidateBlock.getHash());
job.setBlockHash(candidateBlock.getHash());
job.setDifficulty(poolDifficulty);  // Pool难度 << 网络难度
job.setAlgorithm(candidateBlock.getAlgorithm());

// 广播给所有连接的Miner
pool.broadcastJob(job);
```

**Pool难度 vs 网络难度**:
- **网络难度** (Network Difficulty): 出块的真实难度，非常高
- **Pool难度** (Share Difficulty): Pool用来统计矿工工作量，较低
- 例如: Pool难度 = 10亿，网络难度 = 100万亿
- Miner找到的nonce如果满足Pool难度就是**share**，如果满足网络难度就是**block solution**

### Phase 3: Miner挖矿

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │                   │              4. 计算哈希
     │                   │              ┌───────────┐
     │                   │              │ Thread 1  │
     │                   │              │ nonce=1000│
     │                   │              │ hash=...  │
     │                   │              └───────────┘
     │                   │              ┌───────────┐
     │                   │              │ Thread 2  │
     │                   │              │ nonce=2000│
     │                   │              │ hash=...  │
     │                   │              └───────────┘
     │                   │                   │
```

**Miner工作流程**:
```java
// 每个挖矿线程
while (running) {
    // 1. 获取当前job
    MiningJob job = getCurrentJob();

    // 2. 生成随机nonce范围
    long startNonce = random.nextLong();
    long endNonce = startNonce + 10000;

    // 3. 遍历nonce范围
    for (long nonce = startNonce; nonce < endNonce; nonce++) {
        // 4. 计算哈希
        String nonceHex = String.format("%016x", nonce);
        byte[] hash = computeHash(job.getBlockHash(), nonceHex);

        // 5. 检查是否满足难度
        if (meetsTarget(hash, job.getTarget())) {
            // 找到有效share!
            submitShare(job.getJobId(), nonceHex);
        }
    }
}
```

**哈希计算**:
```
输入: block_data + nonce
输出: 64字节哈希值

例如:
block_data = "0xabc123..."
nonce = "00000000deadbeef"
hash = SHA256(SHA256(block_data + nonce))
     = "0000001234567890abcdef..." (64字节)
```

**难度检查**:
```java
// 检查哈希是否满足难度target
boolean meetsTarget(byte[] hash, String target) {
    BigInteger hashValue = new BigInteger(1, hash);
    BigInteger targetValue = new BigInteger(target, 16);

    // 哈希值必须 <= target才有效
    // target越小 = 难度越高
    return hashValue.compareTo(targetValue) <= 0;
}
```

### Phase 4: 提交Share

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │                   │ ◄── 5. mining.submit ───┤
     │                   │    {                     │
     │                   │      worker: "miner1",   │
     │                   │      job_id: "abc123",   │
     │                   │      nonce: "deadbeef"   │
     │                   │    }                     │
     │                   │                   │
     │              6. 验证share            │
     │                   │                   │
```

**Pool验证share**:
```java
// 1. 检查job是否有效
if (!job.getId().equals(share.getJobId())) {
    return "Stale share (job已过期)";
}

// 2. 重构区块
Block minedBlock = candidateBlock.withNonce(share.getNonce());

// 3. 重新计算哈希
byte[] hash = computeHash(minedBlock);

// 4. 验证难度
boolean meetsPoolDifficulty = checkDifficulty(hash, poolTarget);
boolean meetsNetworkDifficulty = checkDifficulty(hash, networkTarget);

if (meetsNetworkDifficulty) {
    // 找到block solution!
    return "BLOCK_SOLUTION";
} else if (meetsPoolDifficulty) {
    // 有效share
    return "VALID_SHARE";
} else {
    // 无效
    return "INVALID";
}
```

### Phase 5: 提交区块 (Block Solution)

```
┌─────────┐         ┌─────────┐         ┌─────────┐
│  Node   │         │  Pool   │         │  Miner  │
└────┬────┘         └────┬────┘         └────┬────┘
     │                   │                   │
     │ ◄─── 7. submitBlock()                 │
     │      {                                │
     │        block_data: "...",             │
     │        nonce: "deadbeef",             │
     │        hash: "0x0000..."              │
     │      }                                │
     │                                       │
     │ ──── 8. 验证并导入 ──────►             │
     │      区块链高度 +1                     │
     │                                       │
     │ ──── 9. 广播到网络 ───────►            │
     │      (P2P)                            │
     │                                       │
```

**Node接受区块**:
```java
// 1. 验证区块
if (!validateBlock(block)) {
    return "Invalid block";
}

// 2. 检查nonce和哈希
byte[] hash = computeHash(block);
if (!meetsNetworkDifficulty(hash)) {
    return "Difficulty not met";
}

// 3. 导入区块链
blockchain.importBlock(block);

// 4. 更新状态
currentHeight++;
currentDifficulty = adjustDifficulty();

// 5. 广播给其他节点
p2pNetwork.broadcastBlock(block);

return "Block accepted";
```

---

## 三、Share vs Block Solution

### Share (份额)
- **定义**: 满足Pool难度的哈希结果
- **用途**: 统计矿工工作量，用于分账
- **难度**: 低，经常找到
- **处理**: Pool记录，不提交给Node

**例子**:
```
Pool难度: hash <= 0xffffffffffffff... (容易达到)
Miner找到: hash = 0xabcdef1234567890...
结果: ✓ 有效share，Pool记录工作量
```

### Block Solution (区块解)
- **定义**: 满足网络难度的哈希结果
- **用途**: 创建新区块，扩展区块链
- **难度**: 高，很少找到
- **处理**: Pool提交给Node，Node导入并广播

**例子**:
```
网络难度: hash <= 0x00000fffffffffff... (很难达到)
Miner找到: hash = 0x00000abc12345678...
结果: ✓ Block solution! 提交给Node
```

### 关系
```
所有 Block Solutions 都是 Shares
但只有极少数 Shares 是 Block Solutions

比例示例:
Pool难度 = 10^9
网络难度 = 10^15
平均每 10^6 个share才有1个block solution
```

---

## 四、当前实现状态

### ✅ Phase 1: 已实现
1. ✅ Pool连接Node HTTP API
2. ✅ Pool通过RPC获取候选区块 (`mining_getCandidateBlock`)
3. ✅ Pool通过Stratum管理Miner连接
4. ✅ Miner接收job并计算哈希
5. ✅ Miner提交share到Pool
6. ✅ Pool验证share有效性
7. ✅ Pool识别block solution

### ❌ Phase 2: 未实现
1. ❌ `mining_submitBlock` RPC方法 (Node不存在此API)
2. ❌ Pool提交block solution回Node
3. ❌ Node接受并验证Pool提交的区块
4. ❌ 区块链高度增长

### 当前行为
```
Miner → Pool: 提交share ✓
Pool: 验证share ✓
Pool: 识别block solution ✓
Pool → Node: 提交区块 ✗ (API不存在)
Node: 导入区块 ✗
区块链: 不增长 ✗
```

### 验证当前状态
```bash
# 1. 检查Node API
curl -X POST http://localhost:10001/api/v1/mining \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"mining_getCandidateBlock","params":["test"],"id":1}'
# 结果: ✓ 可以获取候选区块

curl -X POST http://localhost:10001/api/v1/mining \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"mining_submitBlock","params":[{}],"id":2}'
# 结果: ✗ Method not found

# 2. 检查区块链高度
telnet localhost 6001
> stats
# 应该看到 main blocks = 1 (只有genesis block)
```

---

## 五、时序图总结

### 完整出块流程 (理想状态)

```
Miner                Pool                Node                Network
  │                   │                   │                     │
  ├──connect─────────►│                   │                     │
  │                   ├──getCandidateBlock►                     │
  │                   │◄─candidate────────┤                     │
  │◄──mining.notify───┤                   │                     │
  │                   │                   │                     │
  ├───compute hash────┤                   │                     │
  │   (millions)      │                   │                     │
  │                   │                   │                     │
  ├──submit share────►│                   │                     │
  │◄──accepted────────┤                   │                     │
  │   (repeated)      │                   │                     │
  │                   │                   │                     │
  ├──submit share────►│                   │                     │
  │   (block!)        │                   │                     │
  │◄──accepted────────┤                   │                     │
  │                   ├──submitBlock─────►│                     │
  │                   │◄──block accepted──┤                     │
  │                   │                   ├──broadcast block───►│
  │                   │                   │                     │
  │                   │◄─new candidate────┤                     │
  │◄──mining.notify───┤                   │                     │
  │   (new job)       │                   │                     │
```

### 当前实现 (Phase 1)

```
Miner                Pool                Node                Network
  │                   │                   │                     │
  ├──connect─────────►│                   │                     │
  │                   ├──getCandidateBlock►                     │
  │                   │◄─candidate────────┤                     │
  │◄──mining.notify───┤                   │                     │
  │                   │                   │                     │
  ├───compute hash────┤                   │                     │
  │                   │                   │                     │
  ├──submit share────►│                   │                     │
  │◄──accepted────────┤                   │                     │
  │   (repeated)      │                   │                     │
  │                   │                   │                     │
  ├──submit share────►│                   │                     │
  │   (block!)        │                   │                     │
  │◄──accepted────────┤                   │                     │
  │                   ├──submitBlock─────►X (API不存在)
  │                   │                   │
  │                   │                   │ (区块链不增长)
  │                   │                   │
```

---

## 六、下一步工作 (Phase 2)

要实现完整的出块流程，需要：

### 1. 在xdagj中实现`mining_submitBlock` API
```java
// src/main/java/io/xdag/api/service/MiningRpcService.java

@JsonRpcMethod("mining_submitBlock")
public JsonRpcResponse submitBlock(JsonRpcRequest request) {
    // 1. 解析提交的区块数据
    BlockDto blockDto = parseBlockFromParams(request.getParams());

    // 2. 验证区块
    if (!validateBlock(blockDto)) {
        return error("Invalid block");
    }

    // 3. 导入区块链
    Block block = blockDto.toBlock();
    blockchain.importBlock(block);

    // 4. 广播给网络
    p2pNetwork.broadcastBlock(block);

    return success("Block accepted");
}
```

### 2. Pool调用submitBlock
```java
// xdagj-pool: StratumHandler.java

if (result.isBlockSolution()) {
    log.info("✓ BLOCK SOLUTION!");

    // 提交到Node
    boolean submitted = nodeRpcClient.submitBlock(result.getBlockSolution());

    if (submitted) {
        log.info("✓ Block accepted by node");
    } else {
        log.error("✗ Block rejected by node");
    }
}
```

### 3. 测试验证
```bash
# 启动完整环境
./scripts/start-all.sh

# 等待挖出区块 (可能需要调低难度)

# 检查区块链高度
telnet localhost 6001
> stats
# 应该看到 main blocks > 1

# 检查Pool日志
grep "Block accepted" suite1/pool/pool.log
```

---

## 七、常见问题

### Q1: 为什么share这么多但没出块？
**A**: 因为share难度 << block难度
- Share: 验证矿工在工作
- Block: 实际产生区块
- 测试环境可能需要找几百万个share才能找到1个block

### Q2: Stale share是什么？
**A**: Job已过期的share
- Pool每隔一段时间(如64秒)更新job
- Miner提交时如果job已变，share就是stale
- 这是正常现象，不是错误

### Q3: 如何验证系统在正常工作？
**A**: 检查这些指标
```bash
# 1. Miner正在找到share
grep "Found valid share" suite1/miner/miner.log | wc -l

# 2. Pool正在接收share
grep "accepted" suite1/pool/pool.log | wc -l

# 3. Pool连接到Node
grep "connected to node" suite1/pool/pool.log

# 4. 算力统计 (Miner每60秒打印)
grep "Hashrate" suite1/miner/miner.log | tail -5
```

### Q4: 怎么加快出块速度（测试用）？
**A**: 降低难度
```bash
# 方法1: 修改Pool难度 (只影响share频率)
# pool-config.conf
initialDifficulty = 1000  # 降低到很小

# 方法2: 修改Node网络难度 (需要修改Node代码)
# 这个需要在Node的难度调整逻辑中修改
```

---

**文档版本**: 1.0
**创建日期**: 2025-11-16
**适用版本**: xdagj-pool Phase 1
