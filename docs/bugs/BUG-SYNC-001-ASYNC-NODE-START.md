# BUG-SYNC-001: Inconsistent heights when nodes start asynchronously

## Overview

| Key | Value |
| --- | ----- |
| Bug ID | BUG-SYNC-001 |
| Severity | Critical |
| Impact | Multi-node sync & consensus consistency |
| Discovered | 2025-11-30 |
| Status | Analysis complete, fix pending |

## Problem Statement

When nodes start at different times, the later node begins mining before catching up with the earlier node, so the height-2 block ends up with different hashes on each node.

### Reproduction Steps
1. Start Node1 and wait until it reaches height 2.
2. Start Node2 roughly 20 seconds later.
3. Compare the height/hash on both nodes.

### Actual Result
- Node1 imports epoch 27570384 (height 2, hash `0x0e19f8bcce3c60...`).
- Node2 connects and requests epochs `[27555274, 27555529]`.
- Node1 returns an empty set (that range has no blocks yet).
- Node2 assumes the network is empty and begins mining, producing `0x2c110eac6706aa...` at height 2.
- The chain forks at height 2.

### Expected Result
Node2 should fully sync before mining so its height-2 block matches Node1.

## Root Cause

1. **SyncManager lacks a peer tip reference**
   ```java
   long startEpoch = Math.max(localTipEpoch + 1, lastRequestedEpoch.get() + 1);
   ```
   Without knowing the peer tip, a new node always requests from genesis even if the remote tip is thousands of epochs ahead.

2. **No status exchange on connection**
   ```java
   public void onConnect(Channel channel) {
       log.info("Peer connected: {}", channel.getRemoteAddress());
       // tip not requested
   }
   ```
   The new node cannot determine where to stop syncing or when it has caught up.

3. **EpochConsensusManager starts before sync completes**
   ```java
   startP2pService();
   epochConsensusManager.start();
   ```
   Backup mining triggers 59s after startup even if no blocks were synced, so the node mines on top of its own empty view.

## Proposed Fix

1. **Lightweight status handshake** – introduce `GET_STATUS (0x35)` and `STATUS_REPLY (0x36)` messages returning at least `tipEpoch` (height/difficulty optional for future use).
2. **SyncManager tip awareness** – keep syncing forward in order, but track `remoteTipEpoch = max(peer tips)` and treat `localTip >= remoteTip - tolerance` as synchronized (tolerance ≈ 2 epochs).
3. **Backup miner gating** – before triggering backup mining, skip if `syncManager.isSynchronized()` is false.

### Connection Flow
```java
@Override
public void onConnect(Channel channel) {
    channel.send(new GetStatusMessage());
}

private void handleStatusReply(...) {
    syncManager.updateRemoteTipEpoch(reply.getTipEpoch());
}
```

### SyncManager Additions
```java
private final AtomicLong remoteTipEpoch = new AtomicLong(0);

void updateRemoteTipEpoch(long epoch) {
    remoteTipEpoch.updateAndGet(prev -> Math.max(prev, epoch));
}

boolean isSynchronized() {
    long remote = remoteTipEpoch.get();
    if (remote == 0) {
        return true; // no peers yet
    }
    return getLocalTipEpoch() >= remote - 2;
}
```

With this minimal addition, a new node still syncs forward in order but knows when it has reached the peer tip and when it is safe to mine.
