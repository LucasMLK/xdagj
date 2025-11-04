# Phase 7.2 - SyncManager BlockV5 Migration Plan

**Date**: 2025-10-31
**Branch**: refactor/core-v5.1
**Status**: 🚧 IN PROGRESS

## 1. Current State Analysis

### 1.1 SyncManager Architecture

**Key Components:**
1. **SyncBlock wrapper** - Wraps legacy Block objects with metadata (ttl, time, remotePeer, old flag)
2. **importBlock()** - Imports Block using deprecated tryToConnect(Block) ❌ BROKEN
3. **validateAndAddNewBlock()** - Entry point for new blocks from network
4. **syncMap** - Stores blocks waiting for missing parents (ConcurrentHashMap<Bytes32, Queue<SyncBlock>>)
5. **distributeBlock()** - Broadcasts blocks to network peers

**Current Issues:**
- ❌ `importBlock()` uses deleted `tryToConnect(Block)` → returns INVALID_BLOCK
- ❌ Cannot import any blocks from network
- ❌ Sync completely broken

### 1.2 BlockV5 Support Already Available

**Blockchain Layer:**
- ✅ `blockchain.tryToConnect(BlockV5)` - Fully functional (BlockchainImpl.java:259)
- ✅ BlockV5 storage and retrieval working
- ✅ BlockV5 validation logic implemented

**Network Layer:**
- ✅ `kernel.broadcastBlockV5()` - Broadcasts BlockV5 to peers (Kernel.java:137)
- ⚠️ Network message handlers need update for BlockV5 reception

---

## 2. Migration Strategy

### 2.1 Create Parallel BlockV5 Infrastructure

**Approach**: Add new BlockV5 methods alongside legacy methods (not replace)
- Preserve legacy code path for backward compatibility (if needed)
- Mark legacy methods as @Deprecated
- New methods: `importBlockV5()`, `validateAndAddNewBlockV5()`, etc.

**Rationale**: Safer migration, can fall back if issues arise

### 2.2 Phase 7.2 Tasks

#### Task 1: Create SyncBlockV5 Wrapper Class
```java
public static class SyncBlockV5 {
    private BlockV5 block;
    private int ttl;
    private long time;
    private Peer remotePeer;
    private boolean old;

    // Constructors similar to SyncBlock
}
```

#### Task 2: Implement importBlockV5()
```java
public ImportResult importBlockV5(SyncBlockV5 syncBlock) {
    log.debug("importBlockV5:{}", syncBlock.getBlock().getHash());

    // Call BlockV5 import
    ImportResult importResult = blockchain.tryToConnect(syncBlock.getBlock());

    if (importResult == EXIST) {
        log.debug("BlockV5 already exists: {}", syncBlock.getBlock().getHash());
    }

    // Handle broadcast logic
    if (!syncBlock.isOld() &&
        (importResult == IMPORTED_BEST || importResult == IMPORTED_NOT_BEST)) {
        Peer blockPeer = syncBlock.getRemotePeer();
        Node node = kernel.getClient().getNode();
        if (blockPeer == null ||
            !Strings.CS.equals(blockPeer.getIp(), node.getIp()) ||
            blockPeer.getPort() != node.getPort()) {
            if (syncBlock.getTtl() > 0) {
                distributeBlockV5(syncBlock);
            }
        }
    }

    return importResult;
}
```

#### Task 3: Implement validateAndAddNewBlockV5()
```java
public synchronized ImportResult validateAndAddNewBlockV5(SyncBlockV5 syncBlock) {
    // No parse() needed for BlockV5 (immutable, pre-validated)
    ImportResult result = importBlockV5(syncBlock);
    log.debug("validateAndAddNewBlockV5: {}, {}",
             syncBlock.getBlock().getHash(), result);

    switch (result) {
        case EXIST, IMPORTED_BEST, IMPORTED_NOT_BEST, IN_MEM ->
            syncPopBlockV5(syncBlock);
        case NO_PARENT ->
            doNoParentV5(syncBlock, result);
        case INVALID_BLOCK -> {
            log.debug("Invalid BlockV5: {}", syncBlock.getBlock().getHash());
        }
        default -> {}
    }

    return result;
}
```

#### Task 4: Update Sync Data Structures

**Current:**
```java
private ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMap;
```

**New - Add parallel structure:**
```java
// Keep legacy for backward compatibility
private ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMap;

// Add BlockV5 version
private ConcurrentHashMap<Bytes32, Queue<SyncBlockV5>> syncMapV5;
```

**Or - Unified structure (recommended):**
```java
// Use unified queue for both types
private ConcurrentHashMap<Bytes32, Queue<Object>> syncMapUnified;
// Check instance type: if (item instanceof SyncBlock) vs (item instanceof SyncBlockV5)
```

**Decision**: Use **parallel structures** for clarity and type safety.

#### Task 5: Implement BlockV5 Queue Management

**Methods needed:**
- `syncPushBlockV5(SyncBlockV5, Bytes32 hash)` - Add BlockV5 to waiting queue
- `syncPopBlockV5(SyncBlockV5)` - Process child blocks when parent arrives
- `doNoParentV5(SyncBlockV5, ImportResult)` - Handle missing parent blocks

#### Task 6: Implement distributeBlockV5()
```java
public void distributeBlockV5(SyncBlockV5 syncBlock) {
    // Use Kernel's BlockV5 broadcast method
    kernel.broadcastBlockV5(syncBlock.getBlock(), syncBlock.getTtl());
}
```

---

## 3. Implementation Order

### Step 1: Add SyncBlockV5 Class ✅ NEXT
- Location: Inside SyncManager.java (nested class)
- Keep same structure as SyncBlock
- Use BlockV5 instead of Block

### Step 2: Implement Core BlockV5 Import Methods
- `importBlockV5()`
- `validateAndAddNewBlockV5()`
- Test with manually created BlockV5 objects

### Step 3: Add BlockV5 Queue Management
- Create `syncMapV5` data structure
- Implement `syncPushBlockV5()`, `syncPopBlockV5()`
- Implement `doNoParentV5()`

### Step 4: Implement Block Distribution
- `distributeBlockV5()` method
- Test broadcast to network peers

### Step 5: Network Layer Integration (Future)
- Update XdagP2pEventHandler to receive BlockV5 messages
- Update message handlers to call `validateAndAddNewBlockV5()`
- Create BlockV5 message classes if needed

### Step 6: Testing
- Unit tests for importBlockV5()
- Integration tests for full sync flow
- Network sync testing with multiple nodes

---

## 4. Code Structure

### 4.1 SyncManager.java Organization

```
SyncManager {
    // ===== Nested Classes =====
    @Deprecated
    static class SyncBlock { ... }  // Legacy

    static class SyncBlockV5 { ... }  // NEW

    // ===== Legacy Data Structures =====
    @Deprecated
    ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMap;

    // ===== BlockV5 Data Structures =====
    ConcurrentHashMap<Bytes32, Queue<SyncBlockV5>> syncMapV5;  // NEW

    // ===== Legacy Methods (Deprecated) =====
    @Deprecated
    ImportResult importBlock(SyncBlock) { return INVALID_BLOCK; }

    @Deprecated
    ImportResult validateAndAddNewBlock(SyncBlock) { ... }

    // ===== BlockV5 Methods (Active) =====
    ImportResult importBlockV5(SyncBlockV5) { ... }  // NEW

    ImportResult validateAndAddNewBlockV5(SyncBlockV5) { ... }  // NEW

    void syncPushBlockV5(SyncBlockV5, Bytes32) { ... }  // NEW

    void syncPopBlockV5(SyncBlockV5) { ... }  // NEW

    void doNoParentV5(SyncBlockV5, ImportResult) { ... }  // NEW

    void distributeBlockV5(SyncBlockV5) { ... }  // NEW
}
```

---

## 5. Testing Strategy

### 5.1 Unit Testing

**Test Cases:**
1. ✅ `importBlockV5()` with valid BlockV5 → IMPORTED_BEST
2. ✅ `importBlockV5()` with duplicate BlockV5 → EXIST
3. ✅ `importBlockV5()` with invalid BlockV5 → INVALID_BLOCK
4. ✅ `importBlockV5()` with missing parent → NO_PARENT
5. ✅ `syncPushBlockV5()` adds to queue correctly
6. ✅ `syncPopBlockV5()` processes children when parent arrives
7. ✅ `distributeBlockV5()` broadcasts to network

### 5.2 Integration Testing

**Scenario 1: Single Block Import**
1. Create BlockV5 candidate block
2. Wrap in SyncBlockV5
3. Call `validateAndAddNewBlockV5()`
4. Verify ImportResult = IMPORTED_BEST or IMPORTED_NOT_BEST
5. Verify block stored in blockchain

**Scenario 2: Missing Parent Handling**
1. Create child BlockV5 (links to non-existent parent)
2. Import child → should get NO_PARENT
3. Verify child added to `syncMapV5`
4. Import parent BlockV5
5. Verify child automatically processed

**Scenario 3: Network Broadcast**
1. Import new BlockV5
2. Verify `distributeBlockV5()` called
3. Check `kernel.broadcastBlockV5()` invoked
4. Verify TTL decremented

### 5.3 Network Sync Testing

**Scenario: Multi-Node Sync**
1. Start 3 nodes
2. Node A mines BlockV5
3. Node A broadcasts to Node B, Node C
4. Verify Node B and C receive and import BlockV5
5. Verify all nodes have same blockchain state

---

## 6. Risks and Mitigation

### Risk 1: Network Layer Not Ready for BlockV5
**Issue**: Network message handlers may not parse BlockV5 correctly
**Mitigation**:
- Test with `kernel.broadcastBlockV5()` first
- Check XdagP2pEventHandler for BlockV5 receive logic
- May need to create BlockV5 message classes

### Risk 2: Legacy Code Interference
**Issue**: Legacy sync methods may conflict with new BlockV5 methods
**Mitigation**:
- Keep data structures separate (`syncMap` vs `syncMapV5`)
- Clear deprecation warnings
- Disable legacy methods (return early with error)

### Risk 3: BlockV5 Validation Gaps
**Issue**: BlockV5 may have different validation requirements than Block
**Mitigation**:
- Review `blockchain.tryToConnect(BlockV5)` validation logic
- Ensure all BlockV5.isValid() checks are comprehensive
- Test with malformed BlockV5 objects

---

## 7. Success Criteria

**Phase 7.2 Complete When:**
1. ✅ `SyncBlockV5` class created and functional
2. ✅ `importBlockV5()` successfully imports valid BlockV5 objects
3. ✅ `validateAndAddNewBlockV5()` handles all ImportResult cases
4. ✅ Missing parent handling works (syncMapV5, syncPushBlockV5, syncPopBlockV5)
5. ✅ Block broadcast works via `distributeBlockV5()`
6. ✅ Unit tests pass for all new methods
7. ✅ Integration test: Can import BlockV5 from network (when network layer ready)
8. ✅ Project compiles with 0 errors

**Post-Phase 7.2:**
- ⏳ Phase 7.3: Network layer update for BlockV5 message reception
- ⏳ Phase 7.4: Full multi-node sync testing
- ⏳ Phase 7.5: Remove legacy Block sync code

---

## 8. Next Immediate Action

**Start Implementation:**
1. Add `SyncBlockV5` nested class to SyncManager.java
2. Implement `importBlockV5()` method
3. Test with manually created BlockV5 object

**Expected Time**: ~2-3 hours for core implementation
**Code Review**: Required before network layer integration

---

**End of Phase 7.2 Migration Plan**
