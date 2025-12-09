# XDAGJ

XDAGJ is the Java reference implementation of the XDAG 1.0 protocol. It bundles the consensus engine, storage layer, P2P stack, HTTP/JSON-RPC APIs, and RandomX mining support in a single Maven project.

## Highlights

- **XDAG 1.0 Consensus** – Epoch-based DAG where smallest hash wins each 64-second epoch, with sequential height indexing for querying and cumulative difficulty for fork resolution.
- **FastDAG Synchronization** – Epoch hash gossip + block backfill driven by `SyncManager`.
- **Typed Storage** – RocksDB-backed stores for blocks, transactions, accounts with crash-safe batching.
- **Modern APIs** – REST + JSON-RPC served over Netty for wallet management, block/transaction queries, and mining.

## Quick Start

```bash
# Build (requires JDK 21)
mvn clean package -DskipTests

# Run unit tests
mvn test

# Launch a devnet node
./script/xdag.sh
```

## XDAGJ Ecosystem

XDAGJ depends on several modular libraries:

| Module | Version | Description |
|--------|---------|-------------|
| [xdagj-crypto](https://github.com/XDagger/xdagj-crypto) | 0.1.5 | Cryptographic library (ECDSA, BIP32/39/44 HD wallets, AES, Schnorr, Dilithium) |
| [xdagj-native-randomx](https://github.com/XDagger/xdagj-native-randomx) | 0.2.6 | RandomX PoW native bindings via JNA (92% of C++ performance) |
| [xdagj-p2p](https://github.com/XDagger/xdagj-p2p) | 0.1.6 | P2P networking (Kademlia DHT, reputation system, message routing) |

Related projects:

| Project | Description |
|---------|-------------|
| [xdagj-pool](https://github.com/XDagger/xdagj-pool) | Mining pool server |
| [xdagj-miner](https://github.com/XDagger/xdagj-miner) | Mining client |
| [xdagj-explorer](https://github.com/XDagger/xdagj-explorer) | Block explorer |

## Documentation

| Document | Description |
|----------|-------------|
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture and consensus design |
| [docs/API.md](docs/API.md) | HTTP API reference |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | P2P network protocol |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Node operations manual |
| [docs/DEVNET_MULTI_NODE.md](docs/DEVNET_MULTI_NODE.md) | Multi-node testing guide |

## HTTP API

Base URL: `http://localhost:10001/api/v1/`

| Area | Endpoints |
|------|-----------|
| Blocks | `/blocks`, `/blocks/number`, `/blocks/{height}`, `/blocks/hash/{hash}`, `/blocks/epoch/{epoch}` |
| Transactions | `/transactions`, `/transactions/{hash}`, `POST /transactions` |
| Accounts | `/accounts/{address}/balance`, `/accounts/{address}/nonce` |
| Network | `/network/syncing`, `/network/peers/count`, `/network/chainId` |
| Mining | `/mining/randomx`, `/mining/candidate`, `POST /mining/submit` |

## Developer Notes

- Entry point: `io.xdag.Bootstrap`
- Core components: `DagKernel`, `DagChainImpl`, `SyncManager`, `HttpApiServer`
- Dependencies: Netty, Jackson, RocksDB, Apache Tuweni, BouncyCastle

## Multi-Node Testing

Use `script/devnet_manager.py` to manage local test networks:

```bash
python3 script/devnet_manager.py update --build  # Build and configure
python3 script/devnet_manager.py start           # Start all nodes
python3 script/devnet_manager.py check           # Compare block heights
python3 script/devnet_manager.py stop            # Stop all nodes
```

See [docs/DEVNET_MULTI_NODE.md](docs/DEVNET_MULTI_NODE.md) for details.

## License

Distributed under the MIT License. See [LICENSE](LICENSE).
