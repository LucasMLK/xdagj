# XDAGJ Design Document

## Overview

XDAGJ is the Java implementation of the XDAG 1.0 blockchain protocol - an epoch-based DAG consensus system where the block with the smallest hash wins each 64-second epoch.

## Dependencies

XDAGJ is built on modular libraries:

| Module | Description |
|--------|-------------|
| **xdagj-crypto** | Cryptographic primitives (ECDSA/secp256k1, BIP32/39/44, AES-CBC, Schnorr) |
| **xdagj-native-randomx** | RandomX PoW via JNA (92% of native C++ performance) |
| **xdagj-p2p** | P2P networking (Kademlia DHT, Netty transport, message routing) |

## Architecture

```
+-----------------------------------------------------------+
|                       XDAG Node                           |
|                                                           |
|  +----------------+    +-----------------+    +----------+ |
|  |  DagKernel     | => |   DagChain      | => |  APIs    | |
|  |  (Lifecycle)   |    |   (Consensus)   |    |  (HTTP)  | |
|  +----------------+    +-----------------+    +----------+ |
|          |                       |                 |       |
|          v                       v                 v       |
|     DagStore              SyncManager         HTTP API     |
|     TransactionStore      P2P Handler         Mining API   |
|     AccountStore          RandomX PoW                      |
+-----------------------------------------------------------+
```

### Core Components

| Component | Responsibility |
|-----------|---------------|
| `DagKernel` | Lifecycle management, dependency wiring |
| `DagChainImpl` | Consensus rules, block validation, chain management |
| `SyncManager` | Network synchronization, peer communication |
| `EpochConsensusManager` | Epoch timing, backup mining |
| `DagStore` | Block persistence (RocksDB) |
| `AccountStore` | Balance/nonce state |

## Consensus: XDAG 1.0

### Core Principle

**Epoch is for consensus, Height is for querying.**

- **Epoch** - 64-second time window. Blocks compete within epochs. Smallest hash wins.
- **Height** - Sequential index (1, 2, 3...) assigned to epoch winners. Node-local, not used for consensus.

### Consensus Rules

1. **Epoch Competition** - Smallest hash wins the epoch
2. **Height Assignment** - Winners get continuous heights based on epoch order
3. **Cumulative Difficulty** - Cross-epoch references accumulate; same-epoch do NOT
4. **Fork Resolution** - Highest cumulative difficulty wins
5. **Block Limit** - Max 16 blocks per epoch (weakest hash evicted)

### Block Import Flow

```
Block Received
    │
    ▼
┌─────────────────┐
│ 1. Validation   │  Structure, PoW, links, time window
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 2. Epoch Check  │  Compare hash with current winner
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 3. Atomic Save  │  Block + indexes in single WriteBatch
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 4. Height Assign│  checkNewMain() for epoch winners
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 5. Execute Txs  │  Only for main blocks (height > 0)
└─────────────────┘
```

## Storage Architecture

### RocksDB Layout

```
DagStore (Column Families):
  0xa0: Block data       (hash → Block)
  0xa1: Block info       (hash → BlockInfo)
  0xa2: Chain stats      (singleton)
  0xb1: Epoch index      (epoch → List<hash>)
  0xb2: Height index     (height → hash)
  0xb3: Block references (hash → List<referencing>)
  0xb4: Orphan reason    (hash → reason)
  0xc0: Missing dep block     (hash → Block)
  0xc1: Missing parent index  (parent → List<dependent>)
  0xc2: Missing parents list  (hash → List<missing parents>)

TransactionStore:
  0xe0: Transaction data       (hash → Transaction)
  0xe1: Block→Tx index         (blockHash → List<txHash>)
  0xe2: Address→Tx index       (address → List<txHash>)
  0xe3: Tx→Block index         (txHash → blockHash)
  0xe4: Execution status       (txHash → status)

AccountStore (Separate DB):
  0x01: Account data     (address → Account)
  0x02: Contract code    (codeHash → code)
  0x03: Account count    (singleton)
  0x04: Total balance    (singleton)
```

### Caching

- **L1 Cache**: Caffeine in-memory cache (DagCache)
- **L2 Cache**: RocksDB block cache
- Cache invalidation after successful WriteBatch commit

## P2P Synchronization

### Sync Protocol (FastDAG v3.1)

```
New Peer Connected
    │
    ├─► GET_STATUS → STATUS_REPLY (exchange tip epoch/height)
    │
    ▼
┌─────────────────┐
│ Gap Detection   │  Compare local vs remote tip
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 Small Gap   Large Gap (>1024 epochs)
    │             │
    ▼             ▼
 Forward      Binary Search
 Sync         (find start epoch)
    │             │
    └──────┬──────┘
           ▼
    GET_EPOCH_HASHES → EPOCH_HASHES_REPLY
           │
           ▼
    GET_BLOCKS → BLOCKS_REPLY
           │
           ▼
    Import blocks (async, off EventLoop)
```

### Message Types

| Code | Message | Description |
|------|---------|-------------|
| 0x30 | NEW_BLOCK_HASH | Broadcast new block hash |
| 0x31 | GET_BLOCKS | Request block data |
| 0x32 | BLOCKS_REPLY | Block data response |
| 0x33 | GET_EPOCH_HASHES | Request epoch block hashes |
| 0x34 | EPOCH_HASHES_REPLY | Epoch hashes response |
| 0x35 | GET_STATUS | Request peer status |
| 0x36 | STATUS_REPLY | Peer status response |
| 0x27 | NEW_TRANSACTION | Broadcast transaction |

## Epoch Consensus Manager

### Timing

```
Epoch N: T=0s ──────────────────────────────────────► T=64s
         │                                    │        │
         │  Collect solutions                 │        │
         │  from miners/pools                 │        │
         │                                    │        │
         │                              T=59s │        │
         │                              Backup│        │
         │                              Miner │        │
         │                              Start │        │
         │                                    │        │
         └────────────────────────────────────┴────────┘
                                               Select best
                                               Import block
                                               Start Epoch N+1
```

### Components

- `EpochTimer` - Precise 64-second boundary scheduling (HashedWheelTimer)
- `SolutionCollector` - Gather candidate blocks from miners
- `BestSolutionSelector` - Choose smallest hash
- `BackupMiner` - Fallback if no external solutions

## Transaction Processing

Transactions execute **only for main blocks** (epoch winners):

```java
if (isMainBlock) {
    for (Transaction tx : block.getTransactions()) {
        accountStore.subtractBalance(sender, amount + fee);
        accountStore.addBalance(receiver, amount);
        accountStore.incrementNonce(sender);
    }
}
```

Blocks that lose epoch competition keep their transactions but don't execute them.

## Performance Characteristics

| Operation | Target | Notes |
|-----------|--------|-------|
| Block import | < 50ms P99 | Async off EventLoop |
| Block read (cache) | < 5ms | L1 Caffeine hit |
| Block read (disk) | < 20ms | RocksDB |
| WriteBatch commit | < 100ms | Atomic, ~85% fewer writes |
| Epoch duration | 64 seconds | Network-wide |
