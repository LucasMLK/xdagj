# Phase 7.4 - Historical BlockV5 Synchronization: Implementation Plan

**Date**: 2025-10-31
**Branch**: refactor/core-v5.1
**Status**: 🚧 IN PROGRESS

## 1. Current State Analysis

### 1.1 Existing Sync System (XdagSync.java)

**Time-Based Sync (Legacy Block)**:
- Uses BLOCKS_REQUEST/SUMS_REQUEST messages
- Syncs blocks by time ranges (not height)
- Recursive binary search to find missing time periods
- Works with legacy Block objects ❌ (broken after Phase 7.1)

**Key Methods**:
- `syncLoop()` - Runs every 10 seconds
- `requestBlocks(t, dt)` - Request blocks in time range [t, t+dt]
- `findGetBlocks()` - Binary search for missing blocks using sums
- `sendGetBlocks()` - Send BLOCKS_REQUEST to peer

**Problems**:
1. ❌ Uses legacy Block messages (broken)
2. ❌ Time-based sync doesn't work well with BlockV5
3. ❌ No height tracking
4. ❌ No peer height exchange

### 1.2 BlockV5 Capabilities (Phase 7.3)

**Working Features**:
- ✅ NEW_BLOCK_V5 message reception
- ✅ SYNC_BLOCK_V5 message reception
- ✅ BLOCKV5_REQUEST for specific blocks
- ✅ Missing parent auto-request
- ✅ Recursive child processing

**Missing Features**:
- ❌ Height-based sync
- ❌ Batch block requests
- ❌ Peer height exchange
- ❌ Automatic historical sync on startup

---

## 2. Phase 7.4 Design

### 2.1 Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│ Current: Time-Based Sync (Legacy)                   │
│                                                      │
│ 1. Request blocks by time ranges                    │
│ 2. Use SUMS to find differences                     │
│ 3. Binary search for missing periods                │
│ 4. Works with Block objects                         │
│                                                      │
│ Status: ❌ BROKEN (Phase 7.1 deleted tryToConnect)  │
└─────────────────────────────────────────────────────┘

                         │
                         ▼ Phase 7.4

┌─────────────────────────────────────────────────────┐
│ New: Height-Based BlockV5 Sync                      │
│                                                      │
│ 1. Exchange blockchain height with peers            │
│ 2. Request blocks by height ranges                  │
│ 3. Batch request missing blocks                     │
│ 4. Works with BlockV5 objects                       │
│                                                      │
│ Status: ✅ TO BE IMPLEMENTED                         │
└─────────────────────────────────────────────────────┘
```

### 2.2 Height-Based Sync Flow

```
Node Startup
    │
    ├─> Get local height (blockchain.getHeight())
    │   Example: Local height = 100
    │
    ├─> Connect to peers
    │   Get peer heights via handshake
    │   Example: Peer A = 500, Peer B = 600, Peer C = 550
    │
    ├─> Determine max peer height
    │   max_peer_height = 600
    │
    ├─> Calculate sync range
    │   need_blocks = [101, 600]  (500 blocks to sync)
    │
    ├─> Request blocks in batches
    │   Batch 1: Request blocks [101, 200] from Peer B
    │   Batch 2: Request blocks [201, 300] from Peer A
    │   Batch 3: Request blocks [301, 400] from Peer C
    │   ... continue until [501, 600]
    │
    ├─> Receive and import blocks
    │   For each block:
    │     - Receive SYNC_BLOCK_V5 message
    │     - Import via syncManager.validateAndAddNewBlockV5()
    │     - Missing parent? → Auto-request via BLOCKV5_REQUEST
    │
    └─> Sync complete
        local_height == max_peer_height
        Status: SYNC_DONE
```

### 2.3 Key Components to Implement

**1. Peer Height Exchange**
- Add height field to handshake messages
- Track peer heights in PeerInfo
- Get max peer height for sync

**2. Height-Based Block Requests**
- New message: BLOCKS_BY_HEIGHT_REQUEST
- Request format: (startHeight, endHeight, limit)
- Response: Multiple SYNC_BLOCK_V5 messages

**3. Batch Sync Manager**
- Track sync progress: (local_height, target_height)
- Batch requests: 100 blocks per batch
- Load balancing: Rotate peers for requests

**4. Progress Monitoring**
- Log sync progress: "Syncing: 250/600 blocks (41%)"
- Estimate time remaining
- Handle stalled sync (timeout & retry)

---

## 3. Implementation Strategy

### 3.1 Approach Decision

**Option A**: Extend XdagSync with BlockV5 support
- Pros: Reuse existing sync infrastructure
- Cons: Legacy time-based logic complex, hard to adapt

**Option B**: Create new BlockV5SyncManager
- Pros: Clean separation, easier to implement
- Cons: Duplicate some code, more files

**Decision**: **Option B** - Create BlockV5SyncManager
- Cleaner design
- Easier to understand and maintain
- Can coexist with legacy sync during transition

### 3.2 Implementation Plan

**Step 1**: Add height tracking to blockchain
```java
// In BlockchainImpl.java:
public long getHeight() {
    return getXdagStats().getNmain();  // Already exists!
}

public long getMaxKnownHeight() {
    return getXdagStats().getTotalnmain();  // Peer's max height
}
```

**Step 2**: Add height exchange to handshake
```java
// In HelloMessage.java:
private long blockchainHeight;

// In XdagP2pEventHandler.java:
public void onConnect(Channel channel) {
    long localHeight = blockchain.getHeight();
    // Send height in handshake
}
```

**Step 3**: Create BlockV5SyncManager
```java
public class BlockV5SyncManager extends AbstractXdagLifecycle {

    private long localHeight;
    private long targetHeight;
    private int batchSize = 100;

    public void startSync() {
        localHeight = blockchain.getHeight();
        targetHeight = getMaxPeerHeight();

        while (localHeight < targetHeight) {
            requestBatch(localHeight + 1, localHeight + batchSize);
            localHeight += batchSize;
        }
    }

    private void requestBatch(long startHeight, long endHeight) {
        // Request blocks [startHeight, endHeight] from peers
    }
}
```

**Step 4**: Implement batch block request
```java
// New message: BlocksByHeightRequestMessage
public class BlocksByHeightRequestMessage extends XdagMessage {
    private long startHeight;
    private long endHeight;

    // Constructor...
}

// In XdagP2pEventHandler:
public void handleBlocksByHeightRequest(Channel channel, Bytes data) {
    BlocksByHeightRequestMessage msg = ...;

    // Get blocks by height range
    for (long h = msg.getStartHeight(); h <= msg.getEndHeight(); h++) {
        BlockV5 block = blockchain.getBlockV5ByHeight(h);
        if (block != null) {
            SyncBlockV5Message response = new SyncBlockV5Message(block, 1);
            channel.send(Bytes.wrap(response.getBody()));
        }
    }
}
```

---

## 4. Alternative Approach (Simpler)

Given the complexity, let's consider a **simpler approach** that works with what we have:

### 4.1 Leverage Existing Missing Parent System

**Insight**: We already have a working system for missing parents!

**Strategy**: Trigger sync by requesting the **latest block**

```
1. Get latest block hash from peer (via SUMS or STATUS message)
2. Request that block via BLOCKV5_REQUEST
3. Block arrives → Import → Missing parent → Auto-request parent
4. Recursive chain: Request 600 → 599 → 598 → ... → 101
5. Sync complete when all parents imported
```

**Pros**:
- ✅ Zero new code needed (leverages Phase 7.3)
- ✅ Auto-recovery from missing parents
- ✅ Simple to implement

**Cons**:
- ❌ Requests blocks one-by-one (slow)
- ❌ No batch optimization
- ❌ Inefficient for large sync (1000+ blocks)

**Verdict**: Good for **small gaps** (< 100 blocks), need better solution for **full sync**

---

## 5. Recommended Implementation (Hybrid)

### 5.1 Two-Phase Sync

**Phase A: Bulk Sync (Large Gaps)**
- Use time-based BLOCKS_REQUEST (if legacy sync can be fixed)
- OR implement height-based batch requests
- For gaps > 100 blocks

**Phase B: Tail Sync (Small Gaps)**
- Use existing BLOCKV5_REQUEST + missing parent system
- For gaps < 100 blocks
- Already working from Phase 7.3!

### 5.2 Immediate Priority

**Quick Win**: Fix legacy Block reception temporarily
- Goal: Restore time-based sync for bulk sync
- Keep BlockV5 for tail sync
- Full migration to BlockV5 sync can come later

**OR**

**Better Long-Term**: Implement height-based BlockV5 sync
- More work upfront
- Cleaner architecture
- No dependency on legacy Block

**Decision for Phase 7.4**: Implement **height-based BlockV5 sync** (better long-term)

---

## 6. Phase 7.4 Tasks

### Task 1: Add Height Exchange to Handshake ✅ **START HERE**

**Files to Modify**:
- `HelloMessage.java` - Add blockchainHeight field
- `XdagP2pEventHandler.java` - Send/receive height in handshake
- Track peer heights in channel metadata

**Implementation**:
```java
// In HelloMessage:
private long blockchainHeight;

// In XdagP2pEventHandler.onConnect():
long localHeight = blockchain.getHeight();
// Include in handshake

// Store peer height:
channel.setPeerHeight(peerHeight);
```

### Task 2: Implement BlocksByHeightRequest Message

**Files to Create**:
- `BlocksByHeightRequestMessage.java`
- Add BLOCKS_BY_HEIGHT_REQUEST to MessageCode

**Files to Modify**:
- `XdagP2pEventHandler.java` - Add handler
- `MessageFactory.java` - Add case

### Task 3: Create BlockV5SyncService

**Files to Create**:
- `BlockV5SyncService.java` - Height-based sync manager

**Implementation**:
```java
public class BlockV5SyncService {

    public void syncToHeight(long targetHeight) {
        long currentHeight = blockchain.getHeight();

        while (currentHeight < targetHeight) {
            long batchEnd = Math.min(currentHeight + 100, targetHeight);
            requestBlockRange(currentHeight + 1, batchEnd);
            currentHeight = batchEnd;
        }
    }

    private void requestBlockRange(long start, long end) {
        List<Channel> peers = kernel.getActiveP2pChannels();
        if (peers.isEmpty()) return;

        // Round-robin peers
        Channel peer = peers.get((int)(start % peers.size()));
        sendBlocksByHeightRequest(peer, start, end);
    }
}
```

### Task 4: Integrate with Kernel

**Files to Modify**:
- `Kernel.java` - Initialize BlockV5SyncService
- Start sync automatically on startup

---

## 7. Testing Plan

### 7.1 Unit Tests

```java
@Test
public void testHeightExchange() {
    // Test handshake includes height
}

@Test
public void testBlocksByHeightRequest() {
    // Request blocks [100, 200]
    // Verify received all 101 blocks
}

@Test
public void testSyncToHeight() {
    // Local: 0 blocks, Peer: 500 blocks
    // Sync to height 500
    // Verify all blocks imported
}
```

### 7.2 Integration Tests

**Scenario 1**: Fresh node sync
```
1. Start Node A (has 1000 blocks)
2. Start Node B (empty)
3. Node B connects to Node A
4. Wait for sync
5. Verify Node B has 1000 blocks
```

**Scenario 2**: Partial sync
```
1. Node A: 1000 blocks
2. Node B: 500 blocks (behind)
3. Connect
4. Node B syncs blocks 501-1000
5. Verify both at height 1000
```

---

## 8. Success Criteria

Phase 7.4 complete when:
- ✅ Height exchange in handshake working
- ✅ BlocksByHeightRequest message implemented
- ✅ BlockV5SyncService can sync from 0 to N blocks
- ✅ Fresh node can sync automatically
- ✅ Sync progress logged (e.g., "Syncing: 450/1000 (45%)")
- ✅ Project compiles with 0 errors
- ✅ Integration test passes (2-node full sync)

---

## 9. Next Immediate Actions

**Step 1**: Analyze HelloMessage and handshake flow
**Step 2**: Add height field to handshake
**Step 3**: Test height exchange with 2 nodes
**Step 4**: Implement BlocksByHeightRequest
**Step 5**: Create BlockV5SyncService
**Step 6**: Test full sync scenario

**Estimated Time**: 4-6 hours for core implementation

---

**End of Phase 7.4 Implementation Plan**
