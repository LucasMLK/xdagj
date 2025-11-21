# XDAGJ

XDAGJ is the Java reference implementation of the XDAG 1.0b protocol. It bundles the consensus engine, storage layer, P2P stack, HTTP/JSON-RPC APIs, and RandomX mining support in a single Maven project.

## Highlights

- **XDAG 1.0b Consensus** – Epoch-based DAG where smallest hash wins each 64-second epoch (primary consensus), with sequential height indexing for querying and cumulative difficulty for fork resolution.
- **Hybrid Synchronization** – Height negotiation + finalized main-chain download + DAG solidification driven by `HybridSyncManager`.
- **Typed Storage** – RocksDB-backed stores for blocks, transactions, accounts, and orphan metadata with crash-safe batching.
- **Modern APIs** – REST + JSON-RPC served over Netty; OpenAPI spec published in YAML and JSON for SDK generation.

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

- OpenAPI spec: `http://<host>:<port>/openapi.yaml` (JSON mirror at `/openapi.json`).
- Local copy: [docs/api/openapi.yaml](docs/api/openapi.yaml)
- Test script: `bash test-rpc.sh`
- Generate SDKs: `./generate-sdk.sh`

Key REST endpoints:

| Area | Endpoint |
|------|----------|
| Accounts | `GET /api/v1/accounts?page&size`, `GET /api/v1/accounts/{addr}/balance`, `GET /api/v1/accounts/{addr}/nonce` |
| Blocks | `GET /api/v1/blocks`, `GET /api/v1/blocks/number`, `GET /api/v1/blocks/{number}`, `GET /api/v1/blocks/hash/{hash}` |
| Transactions | `GET /api/v1/transactions`, `GET /api/v1/transactions/{hash}`, `POST /api/v1/transactions` |
| Network | `GET /api/v1/network/{chainId,protocol,coinbase,peers/count,syncing}` |
| Mining | `GET /api/v1/mining/{randomx,candidate}`, `POST /api/v1/mining/submit` |

## Developer Notes

- Entry point: `io.xdag.cli.XdagCli`
- Main services: `DagKernel`, `DagChainImpl`, `HybridSyncManager`, `HttpApiServer`, `MiningApiService`
- External libraries: Netty, Jackson (JSON/YAML), RocksDB, Apache Tuweni, BouncyCastle, RandomX JNI

## License

Distributed under the MIT License. See [LICENSE](LICENSE).
