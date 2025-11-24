# XDAGJ

XDAGJ is the Java reference implementation of the XDAG 1.0b protocol. It bundles the consensus engine, storage layer, P2P stack, HTTP/JSON-RPC APIs, and RandomX mining support in a single Maven project.

## Highlights

- **XDAG 1.0b Consensus** – Epoch-based DAG where smallest hash wins each 64-second epoch (primary consensus), with sequential height indexing for querying and cumulative difficulty for fork resolution.
- **FastDAG Synchronization** – Epoch hash gossip + block backfill driven by the new `SyncManager`.
- **Typed Storage** – RocksDB-backed stores for blocks, transactions, accounts, and orphan metadata with crash-safe batching.
- **Modern APIs** – REST + JSON-RPC served over Netty for wallet management, block/transaction queries, and mining.

For a deep dive, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick Start

```bash
# Build (requires JDK 21)
mvn clean package -DskipTests

# Run unit tests
mvn -Dtest=BlockApiServiceTest,TransactionApiServiceTest test

# Launch a node (devnet)
./script/xdag.sh -t -c config/xdag-devnet.conf
```

## HTTP API & SDKs

- API documentation: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (Section 6: HTTP API Overview)
- Test scripts: `bash test-rpc.sh`, `bash test-epoch-api.sh`

Key REST endpoints:

| Area | Endpoint |
|------|----------|
| Accounts | `GET /api/v1/accounts?page&size`, `GET /api/v1/accounts/{addr}/balance`, `GET /api/v1/accounts/{addr}/nonce` |
| Blocks | `GET /api/v1/blocks`, `GET /api/v1/blocks/number`, `GET /api/v1/blocks/{number}`, `GET /api/v1/blocks/hash/{hash}`, `GET /api/v1/blocks/epoch/{epoch}`, `GET /api/v1/blocks/epoch/range` |
| Transactions | `GET /api/v1/transactions`, `GET /api/v1/transactions/{hash}`, `POST /api/v1/transactions` |
| Network | `GET /api/v1/network/{chainId,protocol,coinbase,peers/count,syncing}` |
| Mining | `GET /api/v1/mining/{randomx,candidate}`, `POST /api/v1/mining/submit` |

## Developer Notes

- Entry point: `io.xdag.cli.XdagCli`
- Main services: `DagKernel`, `DagChainImpl`, `SyncManager`, `HttpApiServer`, `MiningApiService`
- External libraries: Netty, Jackson (JSON/YAML), RocksDB, Apache Tuweni, BouncyCastle, RandomX JNI

## Devnet Multi-Node Testing

Use `python3 script/devnet_manager.py` to rebuild XDAGJ/xdagj-pool/xdagj-miner (via the sibling repos under `/Users/reymondtu/dev/github`), auto-regenerate each suite’s config files from `test-nodes/templates/`, start/stop multiple local nodes, and compare block heights/hashes across suites. Set the desired suite count in `test-nodes/devnet-manager.json -> suiteTemplate.count`, then follow [docs/DEVNET_MULTI_NODE.md](docs/DEVNET_MULTI_NODE.md) for workflow examples and configuration options.

## License

Distributed under the MIT License. See [LICENSE](LICENSE).
