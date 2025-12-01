# XDAGJ Architecture & Protocol Guide

This document condenses everything you need to understand the XDAGJ 1.0 release that ships the XDAG 1.0b consensus engine. It replaces the previous scattered notes and should be treated as the single source of truth for design, protocols, and APIs.

## 1. High-Level Architecture

```
+-----------------------------------------------------------+
|                       XDAG Node                           |
|                                                           |
|  +----------------+    +-----------------+    +----------+ |
|  |  DagKernel     | => |   DagChain      | => |  APIs    | |
|  |  (Lifecycle &  |    |   (Consensus)   |    |  (HTTP & | |
|  |  Storage owner)|    |                 |    |  Mining) | |
|  +----------------+    +-----------------+    +----------+ |
|          |                       |                 |       |
|          v                       v                 v       |
|  DagStore (blocks        SyncManager          Stratum/Pool |
|   + pending mgmt)        P2P Adapter          HTTP v1      |
|  TransactionStore        RandomX POW                       |
|  AccountStore                                              |
+-----------------------------------------------------------+
```

* **DagKernel** wires configuration, RocksDB stores, caches and starts/stops every subsystem.
* **DagChainImpl** implements the XDAG 1.0b consensus rules, block validation and chain statistics.
* **SyncManager** drives network synchronization (FastDAG v3.1 with binary search for large gaps) through the Netty-based P2P layer.
* **HTTP API Server** exposes REST endpoints (`/api/v1/**`) plus JSON-RPC compatibility, powered by Netty + Jackson.
* **MiningApiService** exposes pool-facing RandomX candidate generation and submission helpers.

### Module to Library Mapping

| Area | Key Classes | External Libs |
|------|-------------|---------------|
| Networking / HTTP | `io.netty` pipeline, `HttpApiServer`, `HttpApiHandlerV1` | Netty 4, Jackson (JSON/YAML) |
| P2P & Sync | `SyncManager`, `XdagP2pEventHandler`, message classes | Netty, Tuweni Bytes, CompletableFuture |
| Consensus & DAG | `DagChainImpl`, `DagBlockProcessor`, `RandomXPow` | Apache Tuweni UInt256, BouncyCastle, RandomX JNI |
| Storage | `DagStoreImpl`, `TransactionStoreImpl`, `AccountStoreImpl` | RocksDB JNI, Snappy, Typesafe Config |
| Crypto | `io.xdag.crypto.*` | BouncyCastle, Tuweni |

All modules live in this repo; there are no external `xdagj-*` sibling libraries. If you embed XDAGJ, reuse `DagKernel` as the entry point and link to the provided services.

## 2. Consensus: XDAG 1.0b

XDAG uses an epoch-based DAG where every 64 seconds all miners compete to produce main blocks. The 1.0b consensus (implemented in `DagChainImpl`) introduces deterministic rules based on a critical architectural principle:

**Core Principle**: **Epoch is for consensus, Height is for querying**.

- **Epoch** (consensus layer) – Every 64 seconds = 1 epoch. Within each epoch, the block with the **smallest hash wins** and becomes the epoch winner. This is the PRIMARY consensus mechanism. Epochs may be discontinuous (100, 101, 105, 107...) when nodes are offline.
- **Height** (index layer) – Sequential numbering (1, 2, 3...) assigned to epoch winners for convenient querying. Heights are node-local, mutable, and NOT used for consensus decisions. Each node maintains continuous heights even when epochs are discontinuous.

### 2.1 Consensus Rules

1. **Epoch Competition (PRIMARY)** – Within each epoch, blocks compete via **hash comparison**. Smallest hash wins and becomes the epoch winner (main block). Losers become orphan blocks but remain valid and referenceable. Only 16 non-orphan blocks are kept per epoch (see `MAX_BLOCKS_PER_EPOCH` = 16). Extra blocks must beat the weakest block's hash to stay on the main chain.

2. **Height Assignment (SECONDARY)** – After epoch competition, heights are assigned to epoch winners based on epoch order (not cumulative difficulty). This is done by `checkNewMain()` which scans all epoch winners and assigns continuous heights: 1, 2, 3, 4...

3. **Fork Resolution (TERTIARY)** – When better forks are detected (higher cumulative difficulty), chain reorganization happens at the epoch level. Heights are then re-assigned to the new main chain's epoch winners.

4. **Cumulative Difficulty** – Chain selection follows "heaviest total difficulty wins". Each imported block recalculates cumulative work. **Important**: Only cross-epoch references accumulate difficulty; same-epoch references do NOT accumulate (ensures fair competition within each epoch).

5. **Reference depth rules** – When fully synced, blocks may reference parents only within the last 16 epochs. During sync that limit loosens to 1,000 to allow historical imports.

6. **Pending block management** – Any block missing dependencies or losing epoch competition is marked as pending (height=0) in DagStore and retried once parents arrive. This keeps DAG links intact and enables out-of-order sync. Pending blocks are queried via `DagStore.getPendingBlocks()` without requiring separate storage.

7. **PoW** – Devnet/testing uses a relaxed difficulty (accept-all). Mainnet/testnet rely on RandomX (`RandomXPow`) to validate the 32-byte nonce.

8. **Bootstrap** – The first block (genesis) is accepted only on empty databases to prevent replaying genesis on existing chains.

### 2.2 Block Import Flow

See `src/main/java/io/xdag/core/DagChainImpl.java` for the exact validation order:

1. **Validation** – structure → PoW target → epoch admission → link validation → DAG depth checks
2. **Epoch Competition** – Compare hash with current epoch winner; smallest hash wins
3. **Storage** – Save block to DagStore with pending height (height=0 if first in epoch)
4. **Height Assignment** – `checkNewMain()` assigns continuous heights to all epoch winners based on epoch order
5. **Transaction Processing** – Execute transactions only for main blocks (epoch winners)
6. **Chain Reorganization** – If better fork detected, demote old blocks and promote new fork

### 2.3 Example: Discontinuous Epochs with Continuous Heights

```
Scenario: Node offline from epoch 102-104

Before (epochs):  100, 101, [offline], 105, 106
After (heights):  1,   2,              3,   4

Height sequence is continuous (1,2,3,4) even though epochs are discontinuous.
This is NORMAL and EXPECTED behavior for nodes that experience downtime.
```

### 2.4 Atomic Block Processing

**Introduced**: XDAGJ 5.1 (November 2025)
**Component**: `RocksDBTransactionManager`, atomic `tryToConnect()` in `DagChainImpl`

XDAGJ implements **ACID-compliant atomic block processing** using RocksDB WriteBatch transactions. This ensures that block import and transaction execution happen atomically - either both succeed or both are rolled back completely.

#### 2.4.1 Design Goals

1. **Atomicity**: Block save + transaction execution as single atomic operation
2. **Consistency**: No partial state (e.g., block saved but transactions not executed)
3. **Rollback Safety**: Transaction failures trigger complete rollback
4. **Performance**: Minimal overhead (< 100ms per block commit)

#### 2.4.2 Architecture

```
Block Import Flow (Atomic):
┌─────────────────────────────────────────────────────────┐
│ tryToConnect(block)                                     │
│  1. Validation (PoW, links, DAG rules)                  │
│  2. BEGIN transaction (tx-123)                          │
│  3. BUFFER: Save block data to WriteBatch               │
│  4. BUFFER: Save BlockInfo to WriteBatch                │
│  5. BUFFER: Update epoch/height indexes                 │
│  6. BUFFER: Execute transactions (if main block)        │
│     - Update sender balance/nonce                       │
│     - Update receiver balance                           │
│     - Mark transactions as executed                     │
│  7. COMMIT WriteBatch (atomic disk write)               │
│  8. Update L1 cache (AFTER successful commit)           │
│  9. Notify listeners                                    │
└─────────────────────────────────────────────────────────┘

On failure at any step:
  → ROLLBACK WriteBatch (discard all buffered operations)
  → Return error to caller (block not imported)
```

#### 2.4.3 Key Components

**RocksDBTransactionManager** (`io.xdag.store.rocksdb.transaction.RocksDBTransactionManager`):
- Manages transaction lifecycle (BEGIN, COMMIT, ROLLBACK)
- Buffers operations in RocksDB WriteBatch (in-memory)
- Atomic commit writes all operations to disk in single batch
- Thread-safe with ConcurrentHashMap for active transactions

**Transactional Store Methods**:
- `DagStoreImpl.saveBlockInTransaction(txId, info, block)` - Buffer block save
- `TransactionStoreImpl.markTransactionExecutedInTransaction(txId, txHash)` - Buffer tx marking
- `AccountStoreImpl.setBalanceInTransaction(txId, address, balance)` - Buffer balance update
- `AccountStoreImpl.setNonceInTransaction(txId, address, nonce)` - Buffer nonce update

**DagChainImpl Integration** (`tryToConnect()` at line 441-537):
```java
// Get transaction manager
RocksDBTransactionManager txManager = dagKernel.getTransactionManager();

if (txManager != null && isBestChain) {
    // BEGIN atomic transaction
    String txId = txManager.beginTransaction();

    // Buffer all operations (block + transactions)
    dagBlockProcessor.processBlockInTransaction(txId, block);

    // COMMIT atomically
    txManager.commitTransaction(txId);

    // Update cache AFTER commit
    dagStore.updateCacheAfterCommit(block);
}
```

#### 2.4.4 Performance Characteristics

**Write Efficiency**:
- **WITHOUT atomic processing**: 7+ separate RocksDB writes per block
  - Block data: 1 write
  - BlockInfo: 1 write
  - Epoch index: 1 write
  - Height index: 1 write
  - Transaction execution: 2-3 writes per transaction
- **WITH atomic processing**: 1 atomic commit (85% reduction in disk writes)

**Timing Benchmarks** (from `AtomicBlockProcessingTest`):
- Simple block import: ~900ms (excluding 3s DagKernel initialization)
- Block with 1 transaction: ~200ms
- Block with 3 transactions: ~200ms (O(1) batching!)
- **Atomic commit overhead**: < 100ms (4% of total time)

**Memory Overhead**:
- WriteBatch buffer: ~1-5 KB per transaction
- Transaction manager: ~100 bytes per active transaction
- **Total**: < 10 KB for typical block import

#### 2.4.5 Known Limitations

1. **Cross-Database Atomicity**:
   - AccountStore uses separate RocksDB instance (`accountstore/`)
   - Current workaround: Direct writes to AccountStore DB
   - **Impact**: Account updates not included in transaction rollback
   - **Future fix**: Merge all stores into single RocksDB instance

2. **Fallback Mode**:
   - If TransactionManager unavailable, falls back to non-atomic processing
   - Still executes transactions but without rollback safety
   - Logs warning: "TransactionManager not available, using non-atomic transaction processing"

#### 2.4.6 Cache Consistency

**Critical Design Decision**: Cache updates happen AFTER successful commit:

```java
// WRONG (old behavior):
dagStore.saveBlock(block);  // Writes to disk
cache.putBlock(hash, block);  // Updates cache immediately

// Failure between saveBlock and cache update → inconsistent state!

// CORRECT (atomic behavior):
txManager.beginTransaction(txId);
dagStore.saveBlockInTransaction(txId, info, block);  // Buffer in WriteBatch
txManager.commitTransaction(txId);  // Atomic disk write
dagStore.updateCacheAfterCommit(block);  // Update cache AFTER commit
```

This ensures cache always reflects committed data, preventing "phantom reads" where cache returns uncommitted data.

#### 2.4.7 Testing

Comprehensive test suite in `AtomicBlockProcessingTest`:
1. `testAtomicBlockImport_Simple` - Basic block without transactions
2. `testAtomicBlockImport_WithTransactions` - Single transaction execution
3. `testAtomicBlockImport_MultipleTransactions` - Batch transaction execution
4. `testOrphanBlock_NoTransactionExecution` - Orphan blocks don't execute transactions
5. `testCacheConsistency_AfterCommit` - Cache updated after commit only
6. `testTransactionManager_Availability` - TransactionManager initialization
7. `testTransactionManager_Statistics` - Transaction tracking

**Test Results**: 235/237 tests passing (99.2% pass rate) in core test suite.

#### 2.4.8 Future Improvements

1. **Unified RocksDB Instance**: Merge DagStore, AccountStore, TransactionStore into single RocksDB instance for true cross-store atomicity
2. **Optimistic Concurrency**: Support concurrent block imports with conflict detection
3. **Transaction Replay**: Add transaction replay log for auditability
4. **Performance Monitoring**: Add metrics hooks for production monitoring
5. **Test Isolation**: Fix timing-sensitive test flakiness in epoch competition tests

For implementation details, see:
- `src/main/java/io/xdag/db/rocksdb/transaction/RocksDBTransactionManager.java` - Transaction manager
- `src/main/java/io/xdag/core/DagChainImpl.java` (lines 441-537) - Atomic block import
- `src/test/java/io/xdag/core/AtomicBlockProcessingTest.java` - Comprehensive test suite

## 3. Communication Protocols

### 3.1 P2P Transport
* Transport: TCP over Netty with length-prefixed binary frames (see `io.xdag.net`).
* Handshake: Nodes exchange status (network ID, chain height, tip hash) before switching to sync mode.
* Message families:
  - **Linear sync**: `SyncHeightRequest/Reply`, `SyncMainBlocksRequest/Reply` (range based on heights).
  - **DAG sync**: `SyncEpochBlocksRequest/Reply`, `SyncBlocksRequest/Reply` (hash batches).
  - **Announcements**: `NewBlockMessage`, `NewTransactionMessage`.
  - **Mining support**: Stratum-like RPC hosted by `MiningApiService` (out of scope of P2P channel).

All codecs rely on Apache Tuweni Bytes32 for determinism and share the same serialization routines as the HTTP API.

### 3.2 HTTP & JSON-RPC
* Server: `HttpApiServer` + `HttpApiHandlerV1` on `rpc.http.port` (default 10001).
* Authentication: optional API-key based READ/WRITE permissions.
* CORS and HTTPS options configurable via `xdag-*.conf`.
* JSON-RPC compat via `POST /rpc` for tooling that expects Ethereum-style methods.
* RESTful endpoints documented below.

## 4. Synchronization Workflow

`SyncManager` executes a deterministic pipeline:

1. **Height negotiation** – Query remote height/finality to decide if we are behind.
2. **Finalized main chain sync** – Batch download 1,000-block ranges via `requestMainBlocks`, import sequentially.
3. **Active DAG sync** – Pull recent epochs' block hash sets and fill missing bodies.
4. **Solidification** – Scan pending blocks (height=0) via `DagStore.getPendingBlocks()`, resolve missing references, request parents/transactions, and retry imports.
5. **Transaction solidification** – (future) fetch missing transactions referenced by blocks once block dependencies are satisfied.

Progress is exposed through `SyncManager#isSynchronized()` and the HTTP `/api/v1/network/syncing` endpoint.

## 5. Storage Layout

* **DagStore (Blocks)** – RocksDB column families store serialized blocks, block info, epoch indexes, and height indexes. Saving a block writes both the block payload and its metadata.
* **TransactionStore** – Stores serialized transactions keyed by hash plus secondary indexes from block → transactions and address → transactions. Also maintains a transaction count used by pagination.
* **AccountStore** – Stores balances/nonces for XDAG accounts, enabling wallet RPCs and mining payouts.
* **OrphanBlockStore** – Dedicated column family for tracking orphan hashes + timestamps. Supplies retries for both incoming network blocks and sync imports.

All stores are initialized/stopped by `DagKernel` and share a `DagCache` layer for hot entries.

## 6. HTTP API Overview

The REST API lives under `/api/v1`. Highlights:

### 6.1 Accounts
| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/accounts?page&size` | Paginated wallet accounts sorted by balance. |
| `GET /api/v1/accounts/{address}/balance` | Current balance (returns zero when unknown). |
| `GET /api/v1/accounts/{address}/nonce` | Current transaction count for the address. |

### 6.2 Blocks
| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/blocks` | Paginated latest main blocks (newest first). |
| `GET /api/v1/blocks/number` | Current main chain height in hex. |
| `GET /api/v1/blocks/{number}` | Block detail by number (`latest`, `earliest`, `pending` accepted). |
| `GET /api/v1/blocks/hash/{hash}` | Block detail by hash. |

### 6.3 Transactions
| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/transactions` | Paginated confirmed transactions ordered by block height/time. |
| `GET /api/v1/transactions/{hash}` | Transaction detail. |
| `POST /api/v1/transactions` | Broadcast signed transaction payload (`signedTransactionData`). |

### 6.4 Network & Mining
| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/network/chainId` | Network metadata (chainId + network type). |
| `GET /api/v1/network/protocol` | Protocol/implementation version. |
| `GET /api/v1/network/coinbase` | Node’s mining address. |
| `GET /api/v1/network/peers/count` | Connected peers. |
| `GET /api/v1/network/syncing` | Sync progress (false or progress payload). |
| `GET /api/v1/mining/randomx` / `GET /api/v1/mining/candidate` | Mining stats and candidate blocks (requires WRITE permission). |
| `POST /api/v1/mining/submit` | Submit mined block back to the node. |

### Pagination Semantics
* `page` is 1-based, `size` defaults to 20 and clamps to `[1,100]`.
* Responses follow `{ "data": [...], "pagination": { page, size, total, totalPages } }`.

## 7. Protocol Summaries

### 7.1 Consensus Phases
1. **Validation** – block structure → PoW target → epoch limit → link integrity → DAG reference depth.
2. **Epoch Competition** – compute cumulative difficulty → compare hash with current epoch winner → smallest hash wins epoch.
3. **Height Assignment** – `checkNewMain()` scans all epoch winners and assigns continuous heights (1, 2, 3...) based on epoch order.
4. **Import** – persist block info to DagStore → execute transactions (main blocks only) → update chain stats.
5. **Orphan Management** – any missing dependencies move the block into the orphan store and trigger retries once parents arrive.

### 7.2 Communication
* **P2P**: Node handshake, then alternating `NewBlock` gossip and on-demand sync messages. Replay protected via message IDs and height tracking.
* **HTTP**: Single Netty pipeline serving JSON at `/api/v1/*`.
* **Mining**: HTTP endpoints plus local stratum server reused by pools.

### 7.3 Synchronization
* Main chain batches (1000 block windows) minimize round trips.
* DAG solidification iterates orphan hashes in chunks of 100 with timestamp heuristics to avoid starvation.
* Transaction lookups rely on `TransactionStore`’s block index so block replays do not require rescanning addresses.

### 7.4 Storage Guarantees
* RocksDB column families ensure crash-safe, atomic writes (WriteBatch + WAL sync).
* Light caches (Caffeine) accelerate `getBlockByHash`, height lookups and transaction reads.
* Orphan store timestamps allow pruning and retry scheduling without scanning the entire RocksDB keyspace.

## 8. Release Checklist

1. Build with `mvn clean package -DskipTests` (JDK 21).
2. Run API tests: `mvn -Dtest=BlockApiServiceTest,TransactionApiServiceTest test`.
3. Sanity-check HTTP endpoints via `bash test-rpc.sh`.
4. Package `target/xdagj-<version>-executable.jar` alongside `docs/ARCHITECTURE.md`.

This document is the primary reference for the release.
