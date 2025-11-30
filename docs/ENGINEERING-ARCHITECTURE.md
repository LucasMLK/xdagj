# XDAGJ Engineering Architecture

**Version**: 1.0.0  
**Last Updated**: 2025-11-21  
**Audience**: project managers, contributors, investors, community members

---

## 1. Overview

XDAGJ is the Java implementation of the XDAG blockchain protocol. Similar to how Bitcoin has
multiple clients, XDAG also supports several implementations and XDAGJ is the most complete Java
version. The project embraces a modular design so that each subsystem can evolve independently.

### Why modularize?
- Faster iteration – work on one subsystem without risking regressions in others.
- Easier maintenance – independent versioning and simpler troubleshooting.
- Reusability – crypto, P2P, and RandomX components can be reused by other JVM projects.

---

## 2. Layered Architecture

```
┌───────────────────────────────┐
│ User Layer                    │  Wallets, explorers, pools
├───────────────────────────────┤
│ Core Layer (xdagj)            │  Consensus, storage, mining
├───────────────────────────────┤
│ Foundation Modules            │  crypto / native-randomx / p2p
└───────────────────────────────┘
```

### Foundation modules
- **xdagj-crypto** – wallet/key management, signatures (ECDSA, Schnorr), AES encryption, mnemonic
  handling, Dilithium experiments for post-quantum research.
- **xdagj-native-randomx** – RandomX mining engine with 92% of the C++ implementation performance,
  multi-threaded, cross-platform (Windows/macOS/Linux, Intel/AMD/Apple Silicon).
- **xdagj-p2p** – Netty-based networking stack with Kademlia discovery, throughput beyond
  17k msgs/sec, latency < 8 ms (p95), full telemetry.

### Core module (xdagj)
- Epoch-based consensus engine with RandomX PoW
- RocksDB-backed storage with atomic transactions
- Hybrid sync (batch main-chain sync + DAG completion)
- HTTP/JSON-RPC APIs and mining endpoints

---

## 3. Module Deep Dive

### 3.1 xdagj-crypto
- Wallet/account management (BIP32/39/44 compatible)
- Transaction signing and verification
- AES-protected wallet files
- Libraries are reusable by other blockchain or enterprise apps

### 3.2 xdagj-native-randomx
- Implements RandomX mining (formal mode) and verification mode
- Reaches 369 H/s vs 402 H/s on the reference C++ implementation
- Supports Windows, Linux, macOS, Intel, AMD, and Apple Silicon

### 3.3 xdagj-p2p
- Peer discovery via Kademlia DHT
- Message throughput: 17k+ messages/sec (8-node cluster), latency p95 < 8 ms
- Reputation, blacklist, and connection-pool management
- Production-ready telemetry and tracing hooks

### 3.4 xdagj (core)
- Implements the XDAG 1.0b epoch-first consensus
- Uses RocksDB with write batches for atomicity (+85% fewer writes)
- Hybrid sync: 1,000-block batches + demand-driven DAG completion
- JSON-RPC + HTTP APIs, extensible to WebSocket/GraphQL

---

## 4. How modules collaborate

### Block import pipeline
1. **P2P** receives a block and forwards it to the core.
2. **BlockValidator** checks structure, signatures, references, and PoW.
3. **Consensus engine** performs epoch competition and height assignment.
4. **Storage layer** persists the block atomically with account updates.
5. **P2P** announces the new block to peers.

### Mining workflow
1. Miner requests a candidate block via `MiningApiService`.
2. **BlockBuilder** collects the top 16 references and up to 1024 transactions.
3. Miner solves RandomX offline.
4. On success, the block returns to the core, passes validation, is persisted, and P2P broadcasts it.

---

## 5. Advantages

### Development efficiency
- Independent modules reduce rebuild/test scope.
- Each module has its own versioning and release cadence.

### Maintainability
- Clear ownership per module.
- Diagnosing issues is simpler (logs and metrics isolate components).

### Reusability
- Crypto/p2p/mining modules can be embedded in other blockchain or enterprise projects.

### Quality
- 1,300+ automated tests, majority with >75% coverage.
- RandomX module includes full benchmark suite; P2P has 873 dedicated tests.

### Performance highlights
- Atomic RocksDB transactions reduce disk writes by 85% and increase block import throughput 5x.
- Hybrid sync cuts initial sync for 1M blocks to roughly two hours.
- P2P throughput outperforms Bitcoin (~1k msgs/sec) and Ethereum (~5k msgs/sec).

---

## 6. Scalability Roadmap

### Horizontal scaling
- Storage: pluggable backends (MySQL/PostgreSQL) planned for large mining pools.
- P2P: QUIC/WebSocket/libp2p connectors can be added without touching the core.
- APIs: GraphQL, gRPC, and WebSocket streaming are staged additions.

### Vertical features
- Smart contract layer (EVM-compatible) as an optional fourth tier.
- Light clients (SPV) for mobile/IoT scenarios.
- Cross-chain bridges to Ethereum/Bitcoin ecosystems.

### Future milestones
- Short term (1–3 months): memory optimization, faster sync (<1h for 1M blocks),
  Prometheus metrics, richer explorer/pool tooling.
- Mid term (3–6 months): EVM integration, Solidity tooling, SPV client, browser wallet.
- Long term (6–12 months): Layer 2 solutions (channels/Rollups), zk-proof experiments,
  DAO governance mechanisms.

---

## 7. Comparison snapshot

| Feature            | XDAGJ | Bitcoin Core | Ethereum Geth |
|--------------------|-------|--------------|---------------|
| Language           | Java 21 | C++        | Go            |
| Modularity         | ★★★★★ | ★★☆☆☆       | ★★★☆☆        |
| Reusability        | ★★★★★ | ★★☆☆☆       | ★★★★☆        |
| Performance        | ★★★★☆ | ★★★★★       | ★★★★☆        |
| Scalability hooks  | ★★★★★ | ★★☆☆☆       | ★★★★☆        |

---

## 8. Contact
- **Website**: https://xdag.io  
- **Forum**: https://forum.xdag.io  
- **Support**: dev@xdag.io  
- **Business**: business@xdag.io  
- **Community**: community@xdag.io

---

**License**: MIT  
**Repository**: https://github.com/XDagger/xdagj  
**Maintainers**: XDAGJ Core Team  
**Review status**: ✅ Reviewed
