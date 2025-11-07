# HybridSyncP2pAdapter 集成指南

**日期**: 2025-11-06
**版本**: Phase 1.6.2 完成
**状态**: ✅ P2P适配器和事件处理器已完成

---

## 概览

`HybridSyncP2pAdapter` 是连接 `HybridSyncManager` 和 `xdagj-p2p` 层的桥梁。

### 架构

```
HybridSyncManager  (同步管理器)
        ↓
HybridSyncP2pAdapter  (适配器 - 请求/响应跟踪)
        ↓
XdagP2pEventHandler  (P2P事件处理器)
        ↓
Channel  (P2P通道)
```

---

## 核心功能

### 1. 请求-响应模式

适配器提供同步API，内部使用`CompletableFuture`实现异步请求-响应：

```java
// Example: Query remote height
CompletableFuture<SyncHeightReplyMessage> future =
    adapter.requestHeight(channel);

// Blocks until reply or timeout
SyncHeightReplyMessage reply = future.get(30, TimeUnit.SECONDS);
```

### 2. 超时处理

所有请求都有30秒超时（可配置）：

```java
private static final long DEFAULT_TIMEOUT_MS = 30000;
```

超时后会自动取消请求并抛出`TimeoutException`。

### 3. 并发请求跟踪

使用`ConcurrentHashMap`跟踪最多100个并发请求：

```java
private static final int MAX_PENDING_REQUESTS = 100;
```

超过限制时会拒绝新请求。

---

## 集成步骤

### Step 1: 扩展 XdagP2pEventHandler

需要在`XdagP2pEventHandler`中：

1. **注册新消息类型** (10个):
```java
public XdagP2pEventHandler(Kernel kernel) {
    // ... existing code ...

    // Register hybrid sync messages
    this.messageTypes.add(MessageCode.SYNC_HEIGHT_REQUEST.toByte());
    this.messageTypes.add(MessageCode.SYNC_HEIGHT_REPLY.toByte());
    this.messageTypes.add(MessageCode.SYNC_MAIN_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(MessageCode.SYNC_MAIN_BLOCKS_REPLY.toByte());
    this.messageTypes.add(MessageCode.SYNC_EPOCH_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(MessageCode.SYNC_EPOCH_BLOCKS_REPLY.toByte());
    this.messageTypes.add(MessageCode.SYNC_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(MessageCode.SYNC_BLOCKS_REPLY.toByte());
    this.messageTypes.add(MessageCode.SYNC_TRANSACTIONS_REQUEST.toByte());
    this.messageTypes.add(MessageCode.SYNC_TRANSACTIONS_REPLY.toByte());
}
```

2. **处理Request消息** (发送Reply):
```java
@Override
public void onMessage(Channel channel, Bytes data) {
    byte messageType = data.get(0);

    switch (MessageCode.of(messageType)) {
        case SYNC_HEIGHT_REQUEST:
            handleSyncHeightRequest(channel, data);
            break;
        case SYNC_MAIN_BLOCKS_REQUEST:
            handleSyncMainBlocksRequest(channel, data);
            break;
        // ... other request handlers
    }
}

private void handleSyncHeightRequest(Channel channel, Bytes data) {
    try {
        // Deserialize request (body is empty for height request)
        SyncHeightRequestMessage request = new SyncHeightRequestMessage(data.toArray());

        // Get current chain stats
        ChainStats stats = blockchain.getChainStats();
        long mainHeight = stats.getMainBlockCount();
        long finalizedHeight = Math.max(0, mainHeight - FINALITY_EPOCHS);
        Bytes32 tipHash = blockchain.getBlockByHeight(mainHeight).getHash();

        // Create and send reply
        SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
            mainHeight, finalizedHeight, tipHash);

        channel.send(Bytes.wrap(reply.getBody()));

    } catch (Exception e) {
        log.error("Error handling SyncHeightRequest", e);
    }
}
```

3. **处理Reply消息** (通知Adapter):
```java
private HybridSyncP2pAdapter hybridSyncAdapter;  // Injected

@Override
public void onMessage(Channel channel, Bytes data) {
    byte messageType = data.get(0);

    switch (MessageCode.of(messageType)) {
        case SYNC_HEIGHT_REPLY:
            handleSyncHeightReply(channel, data);
            break;
        case SYNC_MAIN_BLOCKS_REPLY:
            handleSyncMainBlocksReply(channel, data);
            break;
        // ... other reply handlers
    }
}

private void handleSyncHeightReply(Channel channel, Bytes data) {
    try {
        SyncHeightReplyMessage reply = new SyncHeightReplyMessage(data.toArray());

        // Notify adapter to complete the Future
        hybridSyncAdapter.onHeightReply(reply);

    } catch (Exception e) {
        log.error("Error handling SyncHeightReply", e);
    }
}
```

---

### Step 2: 更新 HybridSyncManager

在`HybridSyncManager`中使用适配器：

```java
public class HybridSyncManager {
    private final HybridSyncP2pAdapter p2pAdapter;

    public HybridSyncManager(Kernel kernel) {
        this.p2pAdapter = new HybridSyncP2pAdapter();
        // ...
    }

    private RemoteHeightInfo queryRemoteHeight(Object channel) {
        try {
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            CompletableFuture<SyncHeightReplyMessage> future =
                p2pAdapter.requestHeight(p2pChannel);

            // Wait for reply (with timeout)
            SyncHeightReplyMessage reply = future.get(30, TimeUnit.SECONDS);

            return new RemoteHeightInfo(
                reply.getMainHeight(),
                reply.getFinalizedHeight(),
                reply.getMainBlockHash()
            );

        } catch (TimeoutException e) {
            log.error("Height query timed out", e);
            return null;
        } catch (Exception e) {
            log.error("Height query failed", e);
            return null;
        }
    }

    private List<Block> requestMainBlocks(Object channel, long from, long to) {
        try {
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            CompletableFuture<SyncMainBlocksReplyMessage> future =
                p2pAdapter.requestMainBlocks(p2pChannel, from, to, 1000, false);

            SyncMainBlocksReplyMessage reply = future.get(30, TimeUnit.SECONDS);

            return reply.getBlocks();

        } catch (Exception e) {
            log.error("Main blocks request failed", e);
            return Collections.emptyList();
        }
    }
}
```

---

### Step 3: 依赖注入

确保`HybridSyncP2pAdapter`实例在各组件间共享：

```java
// In Kernel initialization
public class Kernel {
    private HybridSyncP2pAdapter hybridSyncAdapter;
    private HybridSyncManager hybridSyncManager;
    private XdagP2pEventHandler p2pEventHandler;

    public void init() {
        // Create adapter
        this.hybridSyncAdapter = new HybridSyncP2pAdapter();

        // Create managers with shared adapter
        this.hybridSyncManager = new HybridSyncManager(this, hybridSyncAdapter);

        // Inject adapter into event handler
        this.p2pEventHandler = new XdagP2pEventHandler(this);
        this.p2pEventHandler.setHybridSyncAdapter(hybridSyncAdapter);
    }
}
```

---

## 消息流程示例

### Example 1: 查询高度

```
[HybridSyncManager]
    ↓ queryRemoteHeight(channel)

[HybridSyncP2pAdapter]
    ↓ requestHeight(channel)
    - Create CompletableFuture
    - Store in pendingHeightRequests
    - Send SyncHeightRequestMessage
    ↓

[Network]
    ↓ (request travels over network)

[Remote Peer - XdagP2pEventHandler]
    ↓ onMessage() receives SYNC_HEIGHT_REQUEST
    - handleSyncHeightRequest()
    - Get local chain stats
    - Send SyncHeightReplyMessage
    ↓

[Network]
    ↓ (reply travels back)

[Local - XdagP2pEventHandler]
    ↓ onMessage() receives SYNC_HEIGHT_REPLY
    - handleSyncHeightReply()
    - Deserialize message
    - Call adapter.onHeightReply(reply)
    ↓

[HybridSyncP2pAdapter]
    ↓ onHeightReply(reply)
    - Find pending Future
    - Complete Future with reply
    ↓

[HybridSyncManager]
    ↓ future.get() returns
    - Process RemoteHeightInfo
```

---

## 错误处理

### 1. 超时

```java
try {
    SyncHeightReplyMessage reply = future.get(30, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    log.error("Request timed out");
    // Retry or fail gracefully
}
```

### 2. 网络错误

```java
try {
    channel.send(Bytes.wrap(request.getBody()));
} catch (Exception e) {
    log.error("Failed to send request", e);
    future.completeExceptionally(e);
}
```

### 3. 容量限制

```java
if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
    future.completeExceptionally(
        new IllegalStateException("Too many pending requests"));
}
```

---

## 性能优化建议

### 1. 请求批处理

当前实现是FIFO（先进先出），可以优化为批量请求：

```java
// 批量请求多个epoch
List<CompletableFuture<SyncEpochBlocksReplyMessage>> futures =
    epochList.stream()
        .map(epoch -> adapter.requestEpochBlocks(channel, epoch))
        .collect(Collectors.toList());

// 等待所有完成
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

### 2. 请求ID跟踪

当前使用"第一个pending"策略，可以改进为真正的request-ID匹配：

```java
// 在请求消息中添加requestId字段
SyncHeightRequestMessage request = new SyncHeightRequestMessage(requestId);

// 在响应消息中返回requestId
SyncHeightReplyMessage reply = new SyncHeightReplyMessage(requestId, ...);

// 精确匹配
CompletableFuture<SyncHeightReplyMessage> future =
    pendingHeightRequests.get(reply.getRequestId());
```

### 3. 连接池

支持多个peer并发同步：

```java
Map<Channel, HybridSyncP2pAdapter> adapters = new ConcurrentHashMap<>();

// 为每个peer创建独立的adapter
HybridSyncP2pAdapter adapter = adapters.computeIfAbsent(
    channel, ch -> new HybridSyncP2pAdapter());
```

---

## 待办事项

### Phase 1.6.2 - 扩展XdagP2pEventHandler ✅ 完成

- ✅ 注册10个新消息类型
- ✅ 实现Request处理器（5个）
- ✅ 实现Reply处理器（5个）
- ✅ 注入HybridSyncP2pAdapter

### Phase 1.6.3 - 集成到HybridSyncManager ⏳ 进行中

- [ ] 创建HybridSyncP2pAdapter实例
- [ ] 实现queryRemoteHeight()
- [ ] 实现requestMainBlocks()
- [ ] 实现requestEpochBlocks()
- [ ] 实现requestBlocks()
- [ ] 实现requestTransactions()

### Phase 1.6.4 - 测试和验证 ⏳

- [ ] 单元测试：适配器超时处理
- [ ] 单元测试：并发请求限制
- [ ] 集成测试：完整请求-响应流程
- [ ] 性能测试：延迟和吞吐量

---

## 总结

✅ **Phase 1.6.1-1.6.2 完成**: `HybridSyncP2pAdapter` 和 `XdagP2pEventHandler` 扩展已完成

**核心功能**:
- 请求-响应模式（CompletableFuture）
- 超时处理（30秒默认）
- 并发请求跟踪（最多100个）
- 自动清理和取消
- 10个消息处理器已实现

**下一步**: Phase 1.6.3 - 集成HybridSyncManager与P2P适配器

---

**最后更新**: 2025-11-06 17:00
**作者**: Claude Code
**状态**: Phase 1.6.2 ✅ 完成，Phase 1.6.3 ⏳ 进行中
