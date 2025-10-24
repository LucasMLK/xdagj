# XDAG DAG核心数据结构重新设计

## 设计理念

**抛开512字节固定大小限制,专注于DAG的本质需求**

### 核心问题
当前512字节固定Block设计的浪费:
- 大部分Block不需要16个Field
- 强制padding浪费存储空间
- 签名/公钥占用太多Field
- 不灵活,难以扩展

### DAG的本质需求
1. **节点(Block)** - 唯一标识 + 时间戳 + 数据
2. **边(Links)** - 父节点引用 (inputs/outputs)
3. **主链** - 通过最大难度链接形成主链
4. **交易** - 转账amount从inputs到outputs
5. **签名** - 证明所有权
6. **共识** - PoW难度计算

## 新数据结构设计

### 1. DagNode - DAG节点(核心)

```java
/**
 * DAG节点 - 不可变,紧凑设计
 */
@Value
@Builder(toBuilder = true)
public class DagNode implements Serializable {
    // ========== 核心标识 ==========
    Bytes32 hash;              // 节点hash (唯一标识)
    long timestamp;            // 时间戳 (秒)
    DagNodeType type;          // 节点类型

    // ========== DAG链接 ==========
    List<DagLink> inputs;      // 输入链接 (不可变列表)
    List<DagLink> outputs;     // 输出链接 (不可变列表)

    // ========== 共识相关 ==========
    UInt256 difficulty;        // 节点难度
    @Nullable Bytes32 maxDiffLink;    // 最大难度父节点

    // ========== 交易相关 ==========
    XAmount amount;            // 节点余额
    XAmount fee;               // 交易费

    // ========== 签名相关 ==========
    List<PublicKey> publicKeys;  // 公钥列表 (不可变)
    Signature signature;         // 输出签名

    // ========== 可选字段 ==========
    @Nullable Bytes32 nonce;           // 挖矿nonce (主块)
    @Nullable Bytes remark;            // 备注 (可变长)
    @Nullable Bytes32 coinbase;        // coinbase地址 (主块)

    // ========== 状态标志 ==========
    int flags;                 // 状态标志 (BI_MAIN/BI_MAIN_CHAIN/BI_APPLIED等)
    long height;               // 主块高度

    // 枚举:节点类型
    public enum DagNodeType {
        MAIN_BLOCK,      // 主块 (挖矿)
        TRANSACTION,     // 交易块
        LINK_BLOCK,      // 链接块 (帮助孤块)
        ADDRESS_TX       // 地址交易 (account模式)
    }

    // 辅助方法
    public long getEpoch() { return timestamp / 64; }
    public boolean isMainBlock() { return type == DagNodeType.MAIN_BLOCK; }
    public boolean isTransaction() { return type == DagNodeType.TRANSACTION; }
}
```

### 2. DagLink - DAG边

```java
/**
 * DAG链接 - 表示节点之间的连接
 */
@Value
public class DagLink implements Serializable {
    Bytes32 targetHash;    // 目标节点hash
    LinkType type;         // 链接类型
    XAmount amount;        // 转账金额 (交易时非零)

    public enum LinkType {
        BLOCK_REF,         // 块引用 (普通连接)
        TX_INPUT,          // 交易输入 (from block)
        TX_OUTPUT,         // 交易输出 (to block)
        ADDRESS_INPUT,     // 地址输入 (from address)
        ADDRESS_OUTPUT     // 地址输出 (to address)
    }

    public boolean isTransaction() {
        return !amount.isZero();
    }
}
```

### 3. DagMetadata - 节点元数据 (索引用)

```java
/**
 * DAG节点元数据 - 用于索引和快速查询
 * 类似当前的BlockInfo,但更紧凑
 */
@Value
@Builder(toBuilder = true)
public class DagMetadata implements Serializable {
    // 核心标识
    Bytes32 hash;
    long timestamp;
    long height;               // 主块有效
    DagNodeType type;

    // 状态
    int flags;
    UInt256 difficulty;

    // 链接 (只存hash,不存完整信息)
    @Nullable Bytes32 ref;              // 引用节点
    @Nullable Bytes32 maxDiffLink;      // 最大难度链接

    // 金额
    XAmount amount;
    XAmount fee;

    // 辅助方法
    public long getEpoch() { return timestamp / 64; }
    public boolean isMainBlock() { return (flags & BI_MAIN) != 0; }
}
```

### 4. 序列化格式

#### DagNode序列化 (变长)
```
[Header - 固定部分]
[0-31]   hash (32 bytes)
[32-39]  timestamp (8 bytes)
[40]     type (1 byte: 0=MAIN, 1=TX, 2=LINK, 3=ADDR_TX)
[41-72]  difficulty (32 bytes)
[73-96]  maxDiffLink (24 bytes, nullable, 全0=null)
[97-104] amount (8 bytes)
[105-112] fee (8 bytes)
[113-116] flags (4 bytes)
[117-124] height (8 bytes)

[Dynamic - 可变部分]
[125-126] inputCount (2 bytes)
[127-128] outputCount (2 bytes)
[129-130] publicKeyCount (2 bytes)
[131]     hasNonce (1 byte)
[132]     hasCoinbase (1 byte)
[133-134] remarkLength (2 bytes)

[Inputs]
  for each input:
    [0-31]   targetHash (32 bytes)
    [32]     linkType (1 byte)
    [33-40]  amount (8 bytes)

[Outputs]
  for each output:
    [0-31]   targetHash (32 bytes)
    [32]     linkType (1 byte)
    [33-40]  amount (8 bytes)

[PublicKeys]
  for each publicKey:
    [0-32]   compressed public key (33 bytes)

[Signature]
  [0-63]   signature (64 bytes)

[Optional Fields]
  if hasNonce:
    [0-31]   nonce (32 bytes)
  if hasCoinbase:
    [0-31]   coinbase (32 bytes)
  if remarkLength > 0:
    [0-N]    remark (variable)
```

#### 典型大小估算
```
主块 (3 outputs, 0 inputs):
  Fixed: 135 bytes
  Inputs: 0
  Outputs: 3 * 41 = 123 bytes
  PublicKeys: 0
  Signature: 64 bytes
  Nonce: 32 bytes
  Coinbase: 32 bytes
  --------------------------------
  Total: ~386 bytes (vs 512字节, 节省25%)

交易块 (1 input, 1 output):
  Fixed: 135 bytes
  Inputs: 1 * 41 = 41 bytes
  Outputs: 1 * 41 = 41 bytes
  PublicKeys: 1 * 33 = 33 bytes
  Signature: 64 bytes
  --------------------------------
  Total: ~314 bytes (vs 512字节, 节省39%)

链接块 (3 refs, 0 tx):
  Fixed: 135 bytes
  Inputs: 3 * 41 = 123 bytes
  Outputs: 0
  PublicKeys: 0
  Signature: 64 bytes
  --------------------------------
  Total: ~322 bytes (vs 512字节, 节省37%)
```

### 5. 对比当前设计

| 特性 | 当前设计 | 新设计 | 优势 |
|------|---------|--------|------|
| **Block大小** | 固定512字节 | 变长 ~300-400字节 | **节省25-40%存储** |
| **Field限制** | 最多15个链接 | 无限制 (理论上) | **更灵活** |
| **数据结构** | 可变 | 不可变 | **线程安全,易缓存** |
| **类型安全** | byte[], BigInteger | Bytes32, UInt256 | **类型安全** |
| **可读性** | 低 (Field索引) | 高 (语义化字段) | **易维护** |
| **扩展性** | 差 (固定16 Field) | 好 (可变长) | **易扩展** |

## 迁移策略

### 阶段1: 兼容层
```java
/**
 * 转换工具: 旧Block <-> 新DagNode
 */
public class DagNodeConverter {
    // 从旧Block转换
    public static DagNode fromLegacyBlock(Block legacyBlock) {
        // 解析XdagBlock, 提取inputs/outputs/signatures
        // 转换为DagNode
    }

    // 转换为旧Block (用于兼容)
    public static Block toLegacyBlock(DagNode dagNode) {
        // 重建512字节XdagBlock
    }
}
```

### 阶段2: 快照迁移
由于有快照能力,迁移策略:
1. **创建快照**: 从当前链导出快照
2. **格式转换**: 快照数据转换为新格式
3. **验证余额**: 确保所有地址余额正确
4. **新节点启动**: 从新格式快照启动

### 阶段3: 逐步替换
1. **存储层**: BlockStore使用新格式
2. **网络层**: P2P协议协商版本
3. **共识层**: 保持兼容
4. **完全切换**: 弃用旧格式

## 序列化器实现

### DagNodeSerializer

```java
public class DagNodeSerializer {

    public byte[] encode(DagNode node) {
        // 计算总大小
        int size = 135; // Fixed header
        size += 2 + node.getInputs().size() * 41;
        size += 2 + node.getOutputs().size() * 41;
        size += 2 + node.getPublicKeys().size() * 33;
        size += 64; // Signature
        if (node.getNonce() != null) size += 32;
        if (node.getCoinbase() != null) size += 32;
        if (node.getRemark() != null) size += node.getRemark().size();

        ByteBuffer buf = ByteBuffer.allocate(size);

        // Fixed header
        buf.put(node.getHash().toArray());
        buf.putLong(node.getTimestamp());
        buf.put(node.getType().ordinal());
        buf.put(node.getDifficulty().toBytes().toArray());
        // ... 其他fixed字段

        // Dynamic header
        buf.putShort((short)node.getInputs().size());
        buf.putShort((short)node.getOutputs().size());
        buf.putShort((short)node.getPublicKeys().size());
        buf.put((byte)(node.getNonce() != null ? 1 : 0));
        buf.put((byte)(node.getCoinbase() != null ? 1 : 0));
        buf.putShort((short)(node.getRemark() != null ? node.getRemark().size() : 0));

        // Inputs
        for (DagLink link : node.getInputs()) {
            buf.put(link.getTargetHash().toArray());
            buf.put((byte)link.getType().ordinal());
            buf.putLong(link.getAmount().toXAmount().toLong());
        }

        // Outputs
        for (DagLink link : node.getOutputs()) {
            buf.put(link.getTargetHash().toArray());
            buf.put((byte)link.getType().ordinal());
            buf.putLong(link.getAmount().toXAmount().toLong());
        }

        // PublicKeys
        for (PublicKey pk : node.getPublicKeys()) {
            buf.put(pk.toBytes().toArray());
        }

        // Signature
        buf.put(node.getSignature().encodedBytes().slice(0, 64).toArray());

        // Optional fields
        if (node.getNonce() != null) {
            buf.put(node.getNonce().toArray());
        }
        if (node.getCoinbase() != null) {
            buf.put(node.getCoinbase().toArray());
        }
        if (node.getRemark() != null) {
            buf.put(node.getRemark().toArray());
        }

        return buf.array();
    }

    public DagNode decode(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);

        // Fixed header
        byte[] hashBytes = new byte[32];
        buf.get(hashBytes);
        Bytes32 hash = Bytes32.wrap(hashBytes);

        long timestamp = buf.getLong();
        DagNodeType type = DagNodeType.values()[buf.get()];
        // ... 解析其他字段

        // Dynamic header
        int inputCount = buf.getShort() & 0xFFFF;
        int outputCount = buf.getShort() & 0xFFFF;
        int pkCount = buf.getShort() & 0xFFFF;
        boolean hasNonce = buf.get() == 1;
        boolean hasCoinbase = buf.get() == 1;
        int remarkLen = buf.getShort() & 0xFFFF;

        // Parse inputs
        List<DagLink> inputs = new ArrayList<>();
        for (int i = 0; i < inputCount; i++) {
            byte[] targetBytes = new byte[32];
            buf.get(targetBytes);
            LinkType linkType = LinkType.values()[buf.get()];
            XAmount amount = XAmount.ofXAmount(buf.getLong());
            inputs.add(new DagLink(Bytes32.wrap(targetBytes), linkType, amount));
        }

        // Parse outputs
        // ... 类似inputs

        // Build DagNode
        return DagNode.builder()
            .hash(hash)
            .timestamp(timestamp)
            .type(type)
            .inputs(List.copyOf(inputs))
            .outputs(List.copyOf(outputs))
            // ... 其他字段
            .build();
    }
}
```

## 优势总结

### 1. 存储效率
- **节省25-40%存储空间**
- 变长设计,按需分配
- 无强制padding浪费

### 2. 灵活性
- **无Field数量限制**
- 可以有更多inputs/outputs
- 易于扩展新字段

### 3. 可读性
- **语义化字段名**
- 清晰的类型 (DagLink, DagNodeType)
- 易于理解和维护

### 4. 类型安全
- **不可变设计**
- 使用Bytes32/UInt256
- 线程安全

### 5. 性能
- **紧凑序列化**
- 无Kryo反射开销
- 缓存友好

## 实施建议

### Week 1-2: 核心数据结构
- [ ] 实现DagNode, DagLink, DagMetadata
- [ ] 实现DagNodeSerializer
- [ ] 单元测试

### Week 3-4: 转换层
- [ ] 实现DagNodeConverter (Legacy <-> New)
- [ ] 测试兼容性
- [ ] 性能基准测试

### Week 5-6: 存储层集成
- [ ] 修改BlockStore支持新格式
- [ ] 快照导入/导出
- [ ] 集成测试

### Week 7-8: 全面测试
- [ ] 压力测试
- [ ] 安全审计
- [ ] 文档完善

## 结论

这个新设计:

✅ **抛开512字节限制** - 变长设计,节省25-40%存储
✅ **专注DAG本质** - 清晰的节点+边结构
✅ **类型安全** - 不可变,使用Bytes32/UInt256
✅ **灵活扩展** - 无Field限制
✅ **易于维护** - 语义化,可读性强
✅ **性能优化** - 紧凑序列化,缓存友好
✅ **向后兼容** - 支持快照迁移

这是一个真正专注于DAG需求的设计,不受旧有512字节限制的束缚。
