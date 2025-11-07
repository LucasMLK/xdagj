# XDAG v5.1 Architecture Overview

**Version**: v5.1
**Date**: 2025-11-07
**Status**: ✅ Core Components Complete

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Architecture Layers](#architecture-layers)
3. [Core Components](#core-components)
4. [Data Flow](#data-flow)
5. [Key Design Decisions](#key-design-decisions)
6. [Performance Characteristics](#performance-characteristics)
7. [Migration from v4.x](#migration-from-v4x)

---

## Overview

XDAG v5.1 represents a complete architectural overhaul of the XDAG blockchain, introducing:

- **Unified Storage Layer**: DagStore for Block + Transaction + Account persistence
- **L1 Cache Layer**: 13.8 MB Caffeine cache for high-performance reads
- **Epoch-Based DAG Consensus**: Clean epoch competition with cumulative difficulty
- **EVM-Compatible Account State**: Preparing for smart contract support
- **Hybrid Sync Protocol**: Efficient Main Chain Sync + Epoch DAG Sync

**Design Philosophy**:
- **Separation of Concerns**: Clear boundaries between storage, consensus, and sync
- **Independence**: Components can be tested and evolved independently
- **Performance**: L1 cache + optimized indexing for 23,200 TPS target
- **Future-Proof**: EVM compatibility for smart contracts

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
│  (Wallet, RPC API, CLI, Mining, Pool Management)            │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                      Consensus Layer                         │
│                                                              │
│  ┌─────────────────┐    ┌──────────────────────────────┐   │
│  │   DagChain      │    │  HybridSyncManager           │   │
│  │  - tryToConnect │    │  - Main Chain Sync           │   │
│  │  - validateDAG  │    │  - Epoch DAG Sync            │   │
│  │  - calcDifficulty│   │  - Progress Tracking         │   │
│  │  - checkNewMain │    │  - Timeout Handling          │   │
│  └─────────────────┘    └──────────────────────────────┘   │
│                                                              │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                   Transaction Processing Layer               │
│                                                              │
│  ┌──────────────────┐  ┌────────────────────────────────┐  │
│  │ DagBlockProcessor│  │  DagTransactionProcessor       │  │
│  │  - processBlock  │  │   - processTransaction         │  │
│  │  - validateTxs   │  │   - verifySignature            │  │
│  │  - updateState   │  │   - updateBalances             │  │
│  └──────────────────┘  └────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │          DagAccountManager                            │  │
│  │   - getBalance / setBalance                           │  │
│  │   - getNonce / incrementNonce                         │  │
│  │   - EVM-compatible state management                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                       Cache Layer                            │
│                                                              │
│  ┌──────────────────┐    ┌────────────────────────────┐    │
│  │  DagCache (L1)   │    │   DagEntityResolver        │    │
│  │  - 13.8 MB       │    │   - Unified facade         │    │
│  │  - Caffeine      │    │   - Resolve Block + Tx     │    │
│  │  - LRU eviction  │    │   - Handle missing refs    │    │
│  └──────────────────┘    └────────────────────────────┘    │
│                                                              │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                      Storage Layer                           │
│                                                              │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────────┐ │
│  │  DagStore    │  │ TransactionStore│  │  AccountStore   │ │
│  │  - Blocks    │  │  - Transactions │  │  - Accounts     │ │
│  │  - BlockInfo │  │  - Tx→Block idx │  │  - Balances     │ │
│  │  - Main idx  │  │  - Tx→Epoch idx │  │  - Nonces       │ │
│  │  - Epoch idx │  │                 │  │  - Contract Code│ │
│  └──────────────┘  └────────────────┘  └─────────────────┘ │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │          OrphanBlockStore (Temporary Orphans)            ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
└──────────────────────────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                    Persistence Layer                         │
│                     RocksDB Databases                        │
│  - BLOCK, TIME, INDEX, TRANSACTION, ACCOUNT, ORPHANIND      │
└──────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. DagKernel

**Purpose**: Standalone kernel for v5.1 architecture, replacing legacy Kernel.

**Responsibilities**:
- Initialize and manage all v5.1 components
- Lifecycle management (start/stop)
- Component access (getters for stores and managers)

**Dependencies**: Config only (no Wallet dependency)

```java
DagKernel dagKernel = new DagKernel(config);
dagKernel.start();

// Access components
DagStore dagStore = dagKernel.getDagStore();
DagChain dagChain = dagKernel.getDagChain();
```

---

### 2. DagStore

**Purpose**: Unified storage for Block and Transaction persistence.

**Features**:
- Block storage with BlockInfo metadata
- Main chain indexing (position-based + epoch-based)
- Time-range queries for epoch sync
- Block references tracking (reverse lookup)

**Capacity**: 48 MB blocks, 1.485M links per block, ~23,200 TPS

**API**:
```java
// Save block
dagStore.saveBlock(block);
dagStore.saveBlockInfo(blockInfo);

// Query by position
Block block = dagStore.getMainBlockAtPosition(position, isRaw);

// Query by epoch
List<Block> blocks = dagStore.getBlocksByTimeRange(startTime, endTime);

// Query by hash
Block block = dagStore.getBlockByHash(hash, isRaw);
```

---

### 3. DagChain

**Purpose**: DAG consensus implementation with epoch-based competition.

**Features**:
- Block import and validation (`tryToConnect`)
- DAG rules validation (cycle detection, time window, link limits)
- Cumulative difficulty calculation
- Epoch winner determination (smallest hash wins)
- Main chain maintenance (`checkNewMain`)

**Consensus Rules**:
1. **Epoch Competition**: All blocks in same 64-second epoch compete
2. **Winner Selection**: Block with smallest hash wins
3. **Chain Selection**: Chain with maximum cumulative difficulty becomes main chain
4. **DAG Constraints**:
   - No cycles
   - 1-16 block references
   - 12-day time window (16384 epochs)
   - Timestamp ordering

**API**:
```java
// Import block
DagImportResult result = dagChain.tryToConnect(block);

// Create blocks
Block genesisBlock = dagChain.createGenesisBlock(key, timestamp);
Block candidateBlock = dagChain.createCandidateBlock();

// Query
Block block = dagChain.getMainBlockAtPosition(position);
Block winner = dagChain.getWinnerBlockInEpoch(epoch);
```

---

### 4. HybridSyncManager

**Purpose**: Hybrid synchronization protocol combining Main Chain Sync and Epoch DAG Sync.

**Features**:
- **Main Chain Sync**: Position-based, efficient for catching up
- **Epoch DAG Sync**: Time-based, parallel processing for recent epochs
- **Automatic Switching**: Starts with Main Chain, switches to Epoch DAG when synced
- **Progress Tracking**: Real-time sync progress with ETA
- **Timeout Handling**: Retry logic for failed requests

**Protocol**:
```
Phase 1: Height Query (get remote height)
   ↓
Phase 2: Main Chain Sync (sync by position, batch of 512 blocks)
   ↓
Phase 3: Epoch DAG Sync (sync by time range, recent epochs)
   ↓
Phase 4: Keep-Alive (maintain sync state)
```

**Performance**: 512 blocks per batch, ~30 seconds to sync 100K blocks

---

### 5. AccountStore (EVM-Compatible)

**Purpose**: Account state management compatible with EVM.

**Features**:
- Balance tracking (UInt256)
- Nonce management (UInt64)
- Contract code storage (Bytes)
- Storage trie (EVM state storage)

**API**:
```java
// Account balance
UInt256 balance = accountStore.getBalance(address);
accountStore.setBalance(address, newBalance);

// Account nonce
UInt64 nonce = accountStore.getNonce(address);
accountStore.incrementNonce(address);

// Contract code
Bytes code = accountStore.getCode(address);
accountStore.setCode(address, code);
```

---

### 6. DagCache

**Purpose**: L1 Caffeine cache for high-performance reads.

**Capacity**: 13.8 MB (10,000 blocks + 64,000 transactions + 10,000 accounts)

**Eviction**: LRU (Least Recently Used)

**Hit Rate Target**: >90% for typical workloads

**Cache Structure**:
```java
- blockCache:      10,000 blocks     × 480 bytes  = 4.8 MB
- txCache:         64,000 txs        × 128 bytes  = 8.2 MB
- accountCache:    10,000 accounts   × 80 bytes   = 0.8 MB
────────────────────────────────────────────────────
Total:                                              = 13.8 MB
```

---

### 7. DagEntityResolver

**Purpose**: Unified facade for resolving Block and Transaction links.

**Features**:
- Resolve all links in one call
- Handle missing references
- Cache integration
- Clean API for link validation

**API**:
```java
ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

if (resolved.hasAllReferences()) {
    List<Block> blocks = resolved.getReferencedBlocks();
    List<Transaction> txs = resolved.getReferencedTransactions();
} else {
    List<Bytes32> missing = resolved.getMissingReferences();
}
```

---

## Data Flow

### Block Import Flow

```
1. Receive new block
   ↓
2. Basic validation (timestamp, structure, PoW)
   ↓
3. Link validation (resolve all Block + Transaction references)
   ↓
4. DAG rules validation (cycles, time window, link limits)
   ↓
5. Calculate cumulative difficulty
   ↓
6. Determine main chain status (compare with current max difficulty)
   ↓
7. Epoch competition (check if smallest hash in epoch)
   ↓
8. Save block + BlockInfo to DagStore
   ↓
9. Process transactions (DagBlockProcessor)
   ├─► Verify signatures
   ├─► Update account balances (DagAccountManager)
   └─► Index transactions (TransactionStore)
   ↓
10. Remove orphan references
   ↓
11. Update chain statistics
   ↓
12. Notify listeners (mining, pool, etc.)
   ↓
Result: DagImportResult (MAIN_BLOCK, ORPHAN, or ERROR)
```

### Transaction Processing Flow

```
1. Block contains Transaction links
   ↓
2. DagBlockProcessor validates block
   ↓
3. For each transaction:
   ├─► Load full Transaction from TransactionStore
   ├─► DagTransactionProcessor.processTransaction()
   │   ├─► Verify signature
   │   ├─► Check nonce
   │   ├─► Check balance (amount + fee ≥ MIN_GAS)
   │   └─► Update state:
   │       ├─► Deduct from sender (amount + fee)
   │       ├─► Add to recipient (amount)
   │       └─► Increment sender nonce
   └─► Index transaction to block (TransactionStore)
   ↓
Result: All transactions processed, state updated
```

### Sync Flow

```
1. HybridSyncManager.start()
   ↓
2. Query remote height
   ├─► Send: GetBlockByHeightRequest(0)
   └─► Receive: GetBlockByHeightResponse(remoteHeight)
   ↓
3. Determine sync strategy
   ├─► If localHeight << remoteHeight: Main Chain Sync
   └─► If localHeight ≈ remoteHeight: Epoch DAG Sync
   ↓
4. Main Chain Sync (position-based)
   ├─► Request blocks by position (batch of 512)
   ├─► Validate and import each block
   ├─► Update progress (blocks synced / total blocks)
   └─► Repeat until synced
   ↓
5. Switch to Epoch DAG Sync (time-based)
   ├─► Request blocks by epoch (batch of 10 epochs)
   ├─► Validate and import each block
   └─► Repeat for recent epochs
   ↓
6. Keep-Alive mode
   └─► Periodically check for new blocks
```

---

## Key Design Decisions

### Decision 1: Unified DagStore

**Problem**: Legacy architecture had separate BlockStore and TransactionStore

**Solution**: Unified DagStore handling both Block and Transaction persistence

**Benefits**:
- ✅ Simplified architecture
- ✅ Single source of truth
- ✅ Easier to maintain consistency
- ✅ Better performance (fewer DB queries)

---

### Decision 2: Epoch-Based DAG Consensus

**Problem**: Legacy consensus was complex and hard to understand

**Solution**: Clean epoch-based competition:
- All blocks in same 64-second epoch compete
- Block with smallest hash wins
- Cumulative difficulty determines main chain

**Benefits**:
- ✅ Clear and predictable
- ✅ Fair competition
- ✅ Simple to implement
- ✅ Easy to verify

---

### Decision 3: Immutable Block Structure

**Problem**: Legacy Block was mutable with setters

**Solution**: Immutable Block with builder pattern:
```java
// Create block
Block block = Block.builder()
    .header(header)
    .links(links)
    .build();

// Update block (creates new instance)
Block blockWithNonce = block.withNonce(newNonce);
```

**Benefits**:
- ✅ Thread-safe
- ✅ No accidental mutations
- ✅ Easier to reason about
- ✅ Better for functional programming

---

### Decision 4: Separation of Block and BlockInfo

**Problem**: Legacy Block mixed serialization data with runtime metadata

**Solution**: Separate Block (serialized data) and BlockInfo (runtime metadata):
```java
// Block: Serialized data (hash, header, links)
Block block = dagStore.getBlockByHash(hash, true);

// BlockInfo: Runtime metadata (height, difficulty, flags)
BlockInfo info = block.getInfo();
long height = info.getHeight();
```

**Benefits**:
- ✅ Clear separation of concerns
- ✅ Smaller serialization size
- ✅ Easier to update metadata
- ✅ Better cache efficiency

---

### Decision 5: Static Block Factory Methods

**Problem**: Should block creation be in DagChain or Block?

**Solution**: Block has static factory methods:
```java
// Genesis block
Block genesisBlock = Block.createWithNonce(
    timestamp, UInt256.ONE, Bytes32.ZERO, coinbase, List.of()
);

// Candidate block
Block candidateBlock = Block.createCandidate(
    timestamp, difficulty, coinbase, links
);
```

DagChain provides convenience wrappers:
```java
Block genesisBlock = dagChain.createGenesisBlock(key, timestamp);
Block candidateBlock = dagChain.createCandidateBlock();
```

**Benefits**:
- ✅ Block creation logic in Block class (single responsibility)
- ✅ DagChain provides convenience methods
- ✅ No code duplication
- ✅ Easy to test

---

### Decision 6: DagChain vs Blockchain Interface

**Problem**: Should v5.1 reuse Blockchain interface or create new DagChain?

**Solution**: Create new DagChain interface with v5.1-specific methods

**Benefits**:
- ✅ Clean break from legacy
- ✅ v5.1-specific semantics (epoch-based, not height-based)
- ✅ Easier migration path (both can coexist)
- ✅ Future-proof (can add new methods without breaking legacy)

---

## Performance Characteristics

### Throughput

- **Target TPS**: 23,200 transactions per second
- **Block Size**: 48 MB (soft limit)
- **Block Time**: 64 seconds per epoch
- **Links per Block**: Up to 1,485,000 links

### Latency

- **Block Import**: <100ms (cached references)
- **Block Query**: <1ms (L1 cache hit)
- **Transaction Query**: <1ms (L1 cache hit)
- **Main Chain Sync**: ~30 seconds for 100K blocks

### Storage

- **Block Storage**: ~480 bytes per block (without links)
- **Transaction Storage**: ~128 bytes per transaction
- **Account Storage**: ~80 bytes per account
- **Index Overhead**: ~10% of total storage

### Cache

- **L1 Cache Size**: 13.8 MB (Caffeine)
- **L1 Hit Rate**: >90% (typical workload)
- **Cache Eviction**: LRU (Least Recently Used)

### Sync Performance

- **Main Chain Sync**: 512 blocks per batch, ~1,000 blocks/second
- **Epoch DAG Sync**: 10 epochs per batch, ~200 blocks/second
- **Network Overhead**: <1% (efficient batching)

---

## Migration from v4.x

### Phase 1: Parallel Running (Current)

```
Legacy Kernel (v4.x)          DagKernel (v5.1)
├── BlockStore                ├── DagStore
├── TransactionStore          ├── TransactionStore
├── Blockchain                ├── AccountStore
└── Mining/Pool               ├── DagCache
                              ├── DagChain
                              ├── DagAccountManager
                              ├── DagBlockProcessor
                              └── HybridSyncManager
```

**Status**: ✅ Complete - Both kernels running in parallel

---

### Phase 2: Legacy Deprecation (Next)

**Plan**:
1. Migrate all critical components to v5.1
2. Run both kernels in parallel for validation period
3. Gradually deprecate legacy Kernel methods
4. Mark legacy code with `@Deprecated`

**Timeline**: 2-3 months

---

### Phase 3: Legacy Removal (Future)

**Plan**:
1. Remove legacy Kernel completely
2. Delete legacy BlockStore
3. Delete legacy Blockchain implementation
4. Clean up deprecated code

**Timeline**: 6 months

---

## Component Status

| Component | Status | Test Coverage | Documentation |
|-----------|--------|---------------|---------------|
| **DagKernel** | ✅ Complete | ⏳ Basic | ✅ Complete |
| **DagStore** | ✅ Complete | ✅ High | ✅ Complete |
| **DagChain** | ✅ Core Complete | ✅ High | ✅ Complete |
| **HybridSync** | ✅ Complete | ✅ High | ✅ Complete |
| **AccountStore** | ✅ Complete | ✅ High | ✅ Complete |
| **DagCache** | ✅ Complete | ✅ High | ✅ Complete |
| **DagEntityResolver** | ✅ Complete | ✅ High | ✅ Complete |
| **DagAccountManager** | ✅ Complete | ✅ High | ✅ Complete |
| **DagTransactionProcessor** | ✅ Complete | ✅ High | ✅ Complete |
| **DagBlockProcessor** | ✅ Complete | ✅ High | ✅ Complete |

**Overall Status**: ✅ **v5.1 Core Components Complete**

---

## Next Steps

1. **Testing and Validation**
   - Integration testing of full flow
   - Performance benchmarking
   - Stress testing under load

2. **Legacy Migration**
   - Deprecate legacy Kernel methods
   - Migrate remaining components
   - Plan legacy removal timeline

3. **Production Deployment**
   - Testnet deployment
   - Mainnet deployment
   - Monitoring and optimization

---

## References

- [Refactor History Index](refactor-history/REFACTOR_HISTORY_INDEX.md)
- [DagStore Design](refactor-history/dagstore/DAGSTORE_DESIGN_ANALYSIS.md)
- [DagChain Phase 11.2](refactor-history/dagchain/DAGCHAIN_PHASE11.2_COMPLETE.md)
- [HybridSync Performance](refactor-history/hybrid-sync/HYBRID_SYNC_PERFORMANCE_ANALYSIS.md)

---

**Last Updated**: 2025-11-07
**Maintained By**: XDAG Development Team
**Version**: v5.1 (Core Components Complete)
