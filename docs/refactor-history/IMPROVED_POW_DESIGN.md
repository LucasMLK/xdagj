# Improved POW Design for v5.1

**Date**: 2025-11-07
**Phase**: 12.4
**Status**: Design Phase

---

## Problems with Current POW Implementation

### 1. **Architectural Mismatch**
- Depends on legacy `io.xdag.Kernel` instead of `DagKernel`
- Uses `Blockchain` interface instead of `DagChain`
- Calls `blockchain.createMainBlock()` instead of `dagChain.createCandidateBlock()`

### 2. **Mixed Responsibilities**
Current `XdagPow` handles:
- Block generation
- Pool communication
- Share validation
- Block broadcasting
- RandomX management
- Timer/timeout management
- Event processing

**This violates Single Responsibility Principle (SRP)**

### 3. **Tight Coupling**
- Directly depends on `Wallet`, `PoolAwardManager`, `ChannelSupervise`
- Hard to mock/test individual components
- Difficult to maintain

### 4. **Resource Management**
- 4 separate ExecutorServices
- Complex lifecycle management
- No clear shutdown order

---

## Improved Architecture

### Core Principle: Separation of Concerns

```
DagKernel
  └─> MiningManager (new)
        ├─> BlockGenerator (generates candidate blocks)
        ├─> ShareValidator (validates shares from pools)
        ├─> BlockBroadcaster (broadcasts mined blocks)
        └─> PoolInterface (optional, for pool mode)
```

### Component Design

#### 1. **MiningManager** (Replaces XdagPow)
**Responsibility**: Coordinate mining process

```java
public class MiningManager implements XdagLifecycle {
    private final DagKernel dagKernel;
    private final DagChain dagChain;
    private final BlockGenerator blockGenerator;
    private final ShareValidator shareValidator;
    private final BlockBroadcaster blockBroadcaster;

    // Mining configuration
    private final long blockInterval; // 64 seconds
    private ScheduledExecutorService miningScheduler;

    // Current mining task
    private volatile MiningTask currentTask;

    public void start() {
        // Start periodic mining (every 64 seconds)
        miningScheduler = Executors.newSingleThreadScheduledExecutor();
        miningScheduler.scheduleAtFixedRate(
            this::mineBlock,
            0, blockInterval, TimeUnit.SECONDS
        );
    }

    private void mineBlock() {
        // 1. Generate candidate block
        Block candidate = blockGenerator.generateCandidate();

        // 2. Create mining task
        currentTask = new MiningTask(candidate);

        // 3. Start local mining or send to pools
        if (poolMode) {
            poolInterface.sendTask(currentTask);
        } else {
            localMiner.mine(currentTask);
        }

        // 4. Wait for best solution
        // 5. Import and broadcast
    }
}
```

#### 2. **BlockGenerator**
**Responsibility**: Generate candidate blocks

```java
public class BlockGenerator {
    private final DagChain dagChain;
    private final Wallet wallet;
    private final RandomX randomX;

    public Block generateCandidate() {
        ECKeyPair coinbase = wallet.getDefKey();
        long timestamp = XdagTime.getMainTime();

        // v5.1: Use DagChain API
        return dagChain.createCandidateBlock(coinbase, timestamp);
    }
}
```

#### 3. **ShareValidator**
**Responsibility**: Validate mining shares

```java
public class ShareValidator {
    private final RandomX randomX;
    private final AtomicReference<Bytes32> bestShare = new AtomicReference<>();

    public boolean validateShare(Bytes32 share, MiningTask task) {
        Bytes32 hash = calculateHash(share, task);

        // Check if better than current best
        synchronized (bestShare) {
            if (hash.compareTo(bestShare.get()) < 0) {
                bestShare.set(share);
                return true;
            }
        }
        return false;
    }

    private Bytes32 calculateHash(Bytes32 share, MiningTask task) {
        if (randomX.isRandomXFork(task.getTimestamp())) {
            return randomX.hash(task.getPreHash(), share);
        } else {
            return sha256(task.getPreHash(), share);
        }
    }
}
```

#### 4. **BlockBroadcaster**
**Responsibility**: Broadcast mined blocks

```java
public class BlockBroadcaster {
    private final DagKernel dagKernel;
    private final int ttl;

    public void broadcast(Block block) {
        // Import to local chain first
        DagImportResult result = dagKernel.getDagChain().tryToConnect(block);

        if (result.isMainBlock()) {
            // Broadcast to network (P2P layer)
            // TODO: Integrate with P2P layer
            log.info("Mined block {} imported and broadcast",
                block.getHash().toHexString());
        }
    }
}
```

---

## Migration Strategy

### Phase 1: Extract Components (Current Phase)
1. Create `BlockGenerator` class
2. Create `ShareValidator` class
3. Create `BlockBroadcaster` class
4. Keep old `XdagPow` for compatibility

### Phase 2: Create MiningManager
1. Implement `MiningManager` using new components
2. Integrate with `DagKernel`
3. Test side-by-side with old POW

### Phase 3: Migration
1. Switch `DagKernel` to use `MiningManager`
2. Deprecate `XdagPow`
3. Update pool integration if needed

---

## Benefits

### 1. **Clear Separation of Concerns**
- Each component has one responsibility
- Easy to understand and maintain

### 2. **Better Testability**
- Components can be tested independently
- Easy to mock dependencies

### 3. **v5.1 Alignment**
- Uses `DagKernel` and `DagChain`
- Follows v5.1 architecture patterns

### 4. **Simplified Lifecycle**
- One ScheduledExecutorService instead of 4
- Clear start/stop semantics

### 5. **Flexibility**
- Easy to add local mining
- Easy to switch between pool and solo mining
- Easy to add different POW algorithms

---

## Implementation Plan

### Step 1: Create BlockGenerator (30 min)
- Extract block generation logic from XdagPow
- Use `dagChain.createCandidateBlock()`
- Handle RandomX fork logic

### Step 2: Create ShareValidator (20 min)
- Extract share validation logic
- Clean up hash calculation

### Step 3: Create BlockBroadcaster (15 min)
- Extract broadcasting logic
- Integrate with DagChain

### Step 4: Create MiningManager (45 min)
- Coordinate all components
- Implement mining loop
- Add lifecycle management

### Step 5: Integration with DagKernel (30 min)
- Add MiningManager to DagKernel
- Start/stop in kernel lifecycle
- Test basic functionality

### Total Estimated Time: 2-3 hours

---

## Decision

**Recommendation**: Implement the improved POW architecture

**Rationale**:
1. Current POW is not aligned with v5.1
2. Hard to maintain and test
3. Mixed responsibilities violate good design
4. Migration is manageable (2-3 hours)

**Next Steps**:
1. Get user approval
2. Implement BlockGenerator
3. Implement ShareValidator
4. Implement BlockBroadcaster
5. Implement MiningManager
6. Integrate with DagKernel

---

**Author**: Claude Code
**Review Status**: Pending User Approval
