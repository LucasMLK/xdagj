# Devnet Multi-Node Testing Tool

Use `script/devnet_manager.py` to spin up multiple local suites (XDAGJ node + pool + miner) on a single machine. Each suite maps to `test-nodes/<suite>` and contains three processes bound to loopback-only ports so you can validate synchronization, pool connectivity, and miner behavior without leaving your workstation. Running `update` will also regenerate `xdag-devnet.conf`, `pool-config.conf`, `miner-config.conf`, and the Log4j2 files from the templates stored in `test-nodes/templates/`, ensuring each suite stays aligned with the latest code/config structure.

## Prerequisites

- JDK 21+ available on `PATH`
- Maven for rebuilding XDAGJ
- Java-compatible xdagj-pool and xdagj-miner JARs that can be launched with `java -jar <jar> -c <config>`

The default topology includes two suites (`suite1`, `suite2`). Their ports, artifact names, launch arguments, artifact sources, and rendered config files live in `test-nodes/devnet-manager.json`. Each suite is auto-generated from the `suiteTemplate` block, which defines `count`, `namePrefix`, and per-suite variables (node port, HTTP port, Stratum port, worker name, wallet address, etc.) pointing to sibling repos under `/Users/reymondtu/dev/github/{xdagj,xdagj-pool,xdagj-miner}`; adjust those paths if your workspace differs or if you keep custom build commands.

## Typical Workflow

```bash
# 1. Update binaries (rebuild xdagj/xdagj-pool/xdagj-miner via sibling repos and copy jars)
python3 script/devnet_manager.py update --build

# 2. Boot everything
python3 script/devnet_manager.py start

# 3. Inspect process health and exposed ports
python3 script/devnet_manager.py status

# 4. Compare block height + tip hash across nodes
python3 script/devnet_manager.py check

# 5. Tear down
python3 script/devnet_manager.py stop
```

## Commands in Detail

- `update`: With `--build`, runs the configured Maven commands for XDAGJ, `xdagj-pool`, and `xdagj-miner` (see `test-nodes/devnet-manager.json -> artifacts`) and copies the newest jars into each suite. Afterwards it renders all config/log templates (`test-nodes/templates/**`) with the computed ports and suite metadata so `xdag-devnet.conf`, `pool-config.conf`, `miner-config.conf`, and Log4j2 files always match the running code. You can override the detected jars with `--xdagj-jar`, `--pool-jar`, or `--miner-jar` if you built elsewhere.
- `start` / `stop`: Launch or terminate components defined per suite. Log output lands in the component’s working directory (for example, `test-nodes/suite1/node/node.log`). PID files are tracked automatically.
- `status`: Lists every suite/component with PID, key ports, and quick health checks (HTTP for nodes, Stratum TCP for pools).
- `check`: Calls each node’s HTTP API (`/api/v1/blocks/number` + `/api/v1/blocks/{height}`) and prints a comparison table so you can spot divergence at a glance.

Adjust `startArgs`, passwords, extra environment variables, or template files in `test-nodes/devnet-manager.json` if you need custom JVM flags, alternate configs, or more suites.

## Scaling to Additional Suites

- Update `suiteTemplate.count` in `test-nodes/devnet-manager.json` to the desired number (e.g., `4` for `suite1` … `suite4`). The manager uses the base ports defined under `suiteTemplate.variables` (default mapping: `node.port = 8000 + index`, `rpc.http.port = 10000 + index`, `Stratum port = 3332 + index`).
- Run `python3 script/devnet_manager.py update --build` once. The command creates any missing `test-nodes/suiteN/{node,pool,miner}` directories, copies the artifacts, and renders all configs/logs for the new suites using the templates. (If you need custom `devnet/` data or wallets per suite, drop the files into the generated folders after the update step.)
- Launch the extra suites with `start`, inspect via `status`, and confirm chain consistency with `check`. The tool automatically keeps every suite’s `node.whiteIPs` list in sync so peers can connect without hand-editing configs.
