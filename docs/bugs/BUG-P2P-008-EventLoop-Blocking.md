# BUG-P2P-008: Netty EventLoop Blocking Causes ReadTimeout Disconnections

## Status: FIXED

## Summary

Block import operations (`tryToConnect`) executed synchronously on the Netty EventLoop thread, causing the EventLoop to block for extended periods. This prevented PING/PONG message processing, triggering ReadTimeoutHandler (60s) and causing connection drops.

## Root Cause

`XdagP2pEventHandler.handleBlocksReply()` was called directly on the Netty EventLoop thread (`peerClient-1`). Inside this method, `dagChain.tryToConnect(block)` - a `synchronized` method that performs:
- RocksDB writes
- Listener notifications (including `onNewBlock` broadcasts)
- Cache updates

When this method took longer than expected, the EventLoop thread was blocked and could not:
1. Process incoming messages (including PING)
2. Send outgoing messages (including PONG)
3. Handle timeout events

After 60 seconds of no activity, `ReadTimeoutHandler` triggered, closing the connection.

## Evidence from Logs

```
14:54:14.347 [peerClient-1] - Decode message: type=0x32 (BLOCKS_REPLY)
[63 seconds of complete silence - peerClient-1 thread blocked]
14:55:14.348 [peerWorker-1] - ReadTimeoutException
14:55:14.350 - Connection disconnected
14:55:15.075 - hasActiveConnectionTo() = FALSE (size=0)
```

## Fix

Added a dedicated `ExecutorService` for block import operations in `XdagP2pEventHandler`:

```java
// BUG-P2P-008 FIX: Dedicated thread pool for block import operations
private final ExecutorService blockImportExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "BlockImporter");
    t.setDaemon(true);
    return t;
});
```

Modified `handleBlocksReply()` to execute imports asynchronously:

```java
// BUG-P2P-008 FIX: Execute block imports asynchronously
blockImportExecutor.submit(() -> {
    for (Block block : blocks) {
        importBlockAsync(channel, block);
    }
});
```

Also fixed `handleEpochHashesReply()` which had a similar synchronous `tryToConnect` call for BUG-SYNC-003 re-imports.

## Files Modified

- `src/main/java/io/xdag/p2p/XdagP2pEventHandler.java`
  - Added `blockImportExecutor` thread pool
  - Added `shutdown()` method for cleanup
  - Modified `handleBlocksReply()` for async import
  - Modified `handleEpochHashesReply()` for async re-import

## Verification

After fix deployment:
- **Before**: ReadTimeoutException every few minutes, unstable connections
- **After**: 40+ minutes running with 0 ReadTimeoutException, stable connections

## Related Issues

- This fix supersedes previous attempts (BUG-P2P-006, BUG-P2P-007) that focused on duplicate connection detection logic, which was not the actual root cause.

## Date Fixed

2025-12-08
