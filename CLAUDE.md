# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

XDAGJ is the Java reference implementation of the XDAG 1.0b blockchain protocol. It implements an epoch-based DAG consensus where the block with the smallest hash wins each 64-second epoch. The project bundles consensus engine, storage layer, P2P networking, HTTP/JSON-RPC APIs, and RandomX mining support in a single Maven-based codebase.

**Key Technologies**: JDK 21, Maven, RocksDB, Netty, Jackson (JSON/YAML), Apache Tuweni, BouncyCastle, RandomX JNI

## Build & Test Commands

```bash
# Clean build (requires JDK 21)
mvn clean package -DskipTests

# Run all unit tests
mvn test

# Run specific test classes
mvn -Dtest=BlockApiServiceTest,TransactionApiServiceTest test

# Run tests with specific pattern
mvn -Dtest=*EpochTest test

# Run full test suite including excluded tests
mvn test -Pfull-test

# Generate code coverage report
mvn test
# Report at: target/site/jacoco/index.html

# Check license headers
mvn license:check

# Format license headers
mvn license:format
```

## Running a Node

```bash
# Launch devnet node (uses src/test/resources/xdag-devnet.conf)
./script/xdag.sh -t -c src/test/resources/xdag-devnet.conf

# Launch with custom config
./script/xdag.sh -c /path/to/xdag.conf

# Note: The -t flag enables test mode (relaxed PoW validation)
```

## Multi-Node Devnet Testing

Use `script/devnet_manager.py` to manage multiple local test nodes:

```bash
# Rebuild all components and update configs
python3 script/devnet_manager.py update --build

# Start all suites (node + pool + miner per suite)
python3 script/devnet_manager.py start

# Check status of all processes
python3 script/devnet_manager.py status

# Compare block heights across nodes
python3 script/devnet_manager.py check

# Stop all processes
python3 script/devnet_manager.py stop
```

Configuration: `test-nodes/devnet-manager.json` defines suite count, ports, and component settings. See `docs/DEVNET_MULTI_NODE.md` for details.

## Architecture & Key Concepts

### Core Architectural Principle: Epoch vs Height

**Critical**: Understand the two-layer architecture:
- **Epoch (共识层)**: 64-second intervals for consensus. Block with smallest hash wins each epoch. Epochs may be discontinuous (100, 101, 105, 107...) when nodes go offline.
- **Height (索引层)**: Sequential numbering (1, 2, 3...) assigned to epoch winners for querying. Heights are continuous even when epochs are discontinuous. Heights are node-local and NOT used for consensus decisions.

Example: Node offline from epoch 102-104
- Epochs: 100, 101, [offline], 105, 106
- Heights: 1, 2, 3, 4 (continuous)

### Consensus Flow (1.0b)

1. **Epoch Competition (PRIMARY)**: Smallest hash wins the epoch. Only 16 non-orphan blocks kept per epoch (`MAX_BLOCKS_PER_EPOCH`).
2. **Height Assignment (SECONDARY)**: `checkNewMain()` assigns continuous heights to epoch winners based on epoch order.
3. **Fork Resolution (TERTIARY)**: Cumulative difficulty determines best chain. Chain reorganization happens at epoch level.
4. **Cumulative Difficulty**: Cross-epoch references accumulate difficulty; same-epoch references do NOT.
5. **Pending Blocks**: Blocks missing dependencies or losing epoch competition are marked pending (height=0) and retried when parents arrive.

### Atomic Block Processing

XDAGJ 5.1+ implements ACID-compliant atomic block processing using RocksDB WriteBatch transactions:
- Block save + transaction execution happen atomically
- Failures trigger complete rollback
- ~85% reduction in disk writes (1 batch vs 7+ separate writes)
- Cache updates happen AFTER successful commit to ensure consistency

**Key Component**: `RocksDBTransactionManager` (`io.xdag.store.rocksdb.transaction.RocksDBTransactionManager`)

**Known Limitation**: AccountStore uses separate RocksDB instance, so account updates not fully atomic. Future fix will merge all stores into single RocksDB instance.

### Module Structure

```
io.xdag
├── Bootstrap              # Main entry point
├── DagKernel             # Lifecycle manager, owns all subsystems
├── core
│   ├── DagChainImpl      # Consensus engine (1.0b rules)
│   ├── BlockImporter     # Block validation & import
│   ├── OrphanManager     # Pending block retry logic
│   └── DagAccountManager # Wallet state management
├── consensus
│   ├── epoch             # Epoch-based consensus
│   │   ├── EpochConsensusManager
│   │   ├── EpochTimer
│   │   └── BackupMiner
│   └── pow               # RandomX proof-of-work
├── p2p
│   ├── SyncManager       # Network synchronization
│   └── XdagP2pEventHandler # P2P message handling
├── store
│   ├── DagStore         # Block storage
│   ├── TransactionStore # Transaction storage
│   ├── AccountStore     # Balance/nonce storage
│   └── rocksdb/transaction # Atomic transaction support
└── api
    ├── http             # HTTP/REST/JSON-RPC server
    └── service          # API implementation
```

### Storage Architecture

All stores use RocksDB with column families:
- **DagStore**: Blocks, block info, epoch indexes, height indexes
- **TransactionStore**: Transactions, block→tx indexes, address→tx indexes
- **AccountStore**: Balances and nonces (separate RocksDB instance)
- **OrphanBlockStore**: Tracks pending blocks with timestamps for retry scheduling

**Caching**: Caffeine-based L1 cache in front of RocksDB for hot entries (`DagCache`)

### Synchronization Pipeline

`SyncManager` (formerly `HybridSyncManager`) executes:
1. **Height negotiation**: Query remote height to detect if behind
2. **Finalized main chain sync**: Batch download 1,000-block ranges
3. **Active DAG sync**: Pull recent epoch block hash sets
4. **Solidification**: Resolve pending blocks by requesting missing parents
5. **Transaction solidification**: Fetch missing transactions (future work)

Progress exposed via HTTP `/api/v1/network/syncing`

## Working with Tests

### Test Structure
- Unit tests: `src/test/java/io/xdag/`
- Test resources: `src/test/resources/`
- Integration tests use `test-nodes/` directories

### Excluded Tests
Two tests excluded from default runs (see pom.xml):
- `BlockProcessingPerformanceTest` - Long-running performance benchmark
- `TwoNodeSyncReorganizationTest` - Multi-node integration test

Run with: `mvn test -Pfull-test`

### Common Test Patterns
```java
// Tests requiring DagKernel initialization take ~3s for setup
@Before
public void setUp() {
    kernel = new DagKernel(config);
    kernel.init();
}

// Tests use ByteBuddy agent for mocking (configured in pom.xml)
// Agent JAR copied to target/agents/ during test phase

// Atomic transaction tests check commit success
RocksDBTransactionManager txManager = kernel.getTransactionManager();
String txId = txManager.beginTransaction();
// ... operations ...
txManager.commitTransaction(txId);
```

## API Reference

HTTP API base: `http://localhost:10001/api/v1/`

### Key Endpoints
- **Blocks**: `GET /blocks`, `GET /blocks/{number}`, `GET /blocks/hash/{hash}`, `GET /blocks/epoch/{epoch}`
- **Transactions**: `GET /transactions`, `GET /transactions/{hash}`, `POST /transactions`
- **Accounts**: `GET /accounts`, `GET /accounts/{address}/balance`, `GET /accounts/{address}/nonce`
- **Network**: `GET /network/syncing`, `GET /network/peers/count`, `GET /network/chainId`
- **Mining**: `GET /mining/randomx`, `GET /mining/candidate`, `POST /mining/submit`

All APIs use Jackson for JSON serialization. Pagination is 1-based with 20 items per page (max 100).

## Important Code Patterns

### Block Import Flow
1. Validate structure → PoW → epoch admission → links → DAG depth
2. Compete within epoch (smallest hash wins)
3. Save to DagStore with pending height (height=0 if first in epoch)
4. Assign heights via `checkNewMain()` to all epoch winners
5. Execute transactions (main blocks only)
6. Check for reorganization

See `DagChainImpl.tryToConnect()` (lines 441-537) for atomic implementation

### Transaction Processing
```java
// Transactions only execute for main blocks (epoch winners)
if (isMainBlock && block.getTransactions() != null) {
    for (Transaction tx : block.getTransactions()) {
        accountStore.updateBalance(tx.sender, -tx.amount);
        accountStore.updateBalance(tx.receiver, tx.amount);
        accountStore.incrementNonce(tx.sender);
        transactionStore.markExecuted(tx.hash);
    }
}
```

### Pending Block Management
```java
// Blocks without parents → pending (height=0)
if (missingDependencies(block)) {
    dagStore.saveBlockWithHeight(block, 0);
    orphanManager.addOrphan(block.getHash());
}

// Retry when parent arrives
orphanManager.getPendingHashes().forEach(hash -> {
    Block block = dagStore.getBlockByHash(hash);
    if (canImportNow(block)) {
        dagChain.tryToConnect(block);
    }
});
```

### Epoch vs Height Usage
```java
// ✓ CORRECT: Use epoch for consensus decisions
if (block.getEpoch() > currentEpoch) {
    return FUTURE_BLOCK;
}

// ✗ WRONG: Never use height for consensus
if (block.getHeight() > currentHeight) {  // BAD!
    return FUTURE_BLOCK;
}

// ✓ CORRECT: Use height for queries only
Block block = dagStore.getBlockByHeight(100);  // Query API
```

## Configuration

Node configuration uses Typesafe Config (HOCON format):
- Devnet: `src/test/resources/xdag-devnet.conf`
- Templates: `test-nodes/templates/xdag-devnet.conf.template`

Key settings:
```hocon
node.ip = 127.0.0.1
node.port = 8001
node.whiteIPs = ["127.0.0.1:8001", "127.0.0.1:8002"]

rpc.enabled = true
rpc.http.host = 127.0.0.1
rpc.http.port = 10001

# Devnet uses relaxed PoW
consensus.powMode = "test"
```

## Troubleshooting

### Common Issues

**"Block import failed: missing dependencies"**
- Expected behavior for out-of-order sync
- Block saved as pending (height=0) and retried when parents arrive
- Check `OrphanManager.getPendingBlockCount()`

**"Height gaps in main chain"**
- Likely discontinuous epochs (node was offline)
- Verify epochs with: `GET /api/v1/blocks/epoch/range`
- Heights should be continuous even if epochs are not

**"Transaction not executed"**
- Only main blocks (epoch winners) execute transactions
- Orphan blocks keep transactions but don't execute them
- Check block's `isMainBlock` flag

**Test failures with "byte-buddy-agent.jar not found"**
- Run `mvn process-test-classes` to copy agent JAR
- Agent required for Mockito mocking

### Logging
- Log config: `src/main/resources/log4j2.xml` (templates in `test-nodes/templates/`)
- Logs: `logs/xdag.log` (main), `logs/xdag-gc-*.log` (GC)
- Change level: Edit log4j2.xml and restart

## Documentation

- Architecture & protocols: `docs/ARCHITECTURE.md` (primary reference)
- Multi-node testing: `docs/DEVNET_MULTI_NODE.md`
- Bug reports: `docs/bugs/`
- Test reports: `docs/test-reports/`
- Refactoring notes: `docs/refactoring/`

## Development Notes

- Entry point: `io.xdag.Bootstrap`
- Main lifecycle manager: `io.xdag.DagKernel`
- Consensus implementation: `io.xdag.core.DagChainImpl`
- Storage layer: `io.xdag.store.rocksdb.*`
- P2P networking: `io.xdag.p2p.*`
- HTTP API: `io.xdag.api.http.*`

When modifying consensus logic, always test with multiple nodes using the devnet manager. Epoch timing and synchronization bugs are difficult to reproduce in single-node tests.
