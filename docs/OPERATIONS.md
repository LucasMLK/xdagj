# XDAGJ Node Operations Manual

## Requirements

- JDK 21+
- Maven 3.8+
- 4GB+ RAM
- 50GB+ disk space

## Build

```bash
# Clean build
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Check only
mvn compile
```

## Configuration

Configuration file: `xdag.conf` (HOCON format)

### Minimal Configuration

```hocon
node {
  ip = "0.0.0.0"
  port = 8001
}

rpc {
  enabled = true
  http {
    host = "127.0.0.1"
    port = 10001
  }
}

store {
  dataDir = "./data"
}
```

### Full Configuration

```hocon
node {
  ip = "0.0.0.0"
  port = 8001

  # Peer discovery
  whiteIPs = [
    "192.168.1.10:8001",
    "192.168.1.11:8001"
  ]

  # Network
  network = "mainnet"  # mainnet | testnet | devnet
}

rpc {
  enabled = true
  http {
    host = "127.0.0.1"
    port = 10001
  }
}

store {
  dataDir = "./data"

  # RocksDB tuning
  cacheSize = 128  # MB
  writeBufferSize = 64  # MB
}

consensus {
  # PoW mode: "real" for mainnet, "test" for devnet
  powMode = "real"

  # Mining
  miningEnabled = true
  coinbase = "0x..."  # Mining reward address
}

pool {
  enabled = false
  port = 7001
}
```

## Running a Node

### Start Node

```bash
# Using script (defaults to devnet/test mode)
./script/xdag.sh

# Direct Java with custom config file
java -Dconfig.file=/path/to/xdag.conf -jar target/xdagj-*-executable.jar

# Devnet mode (relaxed PoW)
java -Dconfig.file=xdag-devnet.conf -jar target/xdagj-*-executable.jar -d

# Testnet mode
java -Dconfig.file=xdag-testnet.conf -jar target/xdagj-*-executable.jar -t

# Mainnet mode (default, no flag needed)
java -Dconfig.file=xdag.conf -jar target/xdagj-*-executable.jar
```

### Command Line Options

| Option | Description |
|--------|-------------|
| `-d` | Devnet mode (local testing) |
| `-t` | Testnet mode |
| (none) | Mainnet mode (default) |
| `-f <dir>` | Root directory for data |
| `-p <host:port>` | Override node IP:port |
| `--help` | Show help |
| `--version` | Show version |
| `--password <pwd>` | Wallet password |
| `--account <action>` | Account management (init/create/list) |

**Note**: Config file is specified via Java system property `-Dconfig.file=path`, not a CLI option.

### Wallet Operations

On first start, you'll be prompted to:

1. Create wallet password
2. Generate HD seed (write down mnemonic!)
3. Create first account

```
Enter new password: ********
Confirm password: ********

HD Wallet Mnemonic (SAVE THIS!):
word1 word2 word3 ... word24

First account created: 0x...
```

## Multi-Node Devnet

Use `script/devnet_manager.py` for local testing:

```bash
# Build and configure
python3 script/devnet_manager.py update --build

# Start all nodes
python3 script/devnet_manager.py start

# Check status
python3 script/devnet_manager.py status

# Compare heights
python3 script/devnet_manager.py check

# Stop all
python3 script/devnet_manager.py stop
```

Configuration: `test-nodes/devnet-manager.json`

## Monitoring

### Logs

- Main log: `logs/xdag.log`
- GC log: `logs/xdag-gc-*.log`
- Log config: `log4j2.xml`

### Log Levels

Edit `log4j2.xml`:
```xml
<Logger name="io.xdag" level="INFO"/>
<Logger name="io.xdag.core" level="DEBUG"/>
```

### HTTP Health Check

```bash
# Sync status
curl http://localhost:10001/api/v1/network/syncing

# Chain stats
curl http://localhost:10001/api/v1/chain/stats

# Peer count
curl http://localhost:10001/api/v1/network/peers/count
```

## Troubleshooting

### Node Won't Start

**"Port already in use"**
```bash
# Find process
lsof -i :8001
# Kill if needed
kill -9 <PID>
```

**"Database locked"**
```bash
# Check for zombie process
ps aux | grep xdag
# Remove lock file
rm -f data/dagstore/LOCK
```

### Sync Issues

**"Node stuck syncing"**
1. Check peer connections: `GET /network/peers/count`
2. Verify whiteIPs in config
3. Check firewall (port 8001)

**"Height not advancing"**
1. Check sync status: `GET /network/syncing`
2. Review logs for errors: `tail -f logs/xdag.log`
3. Restart node if necessary

### Mining Issues

**"No blocks produced"**
1. Verify sync complete: `syncing: false`
2. Check coinbase address configured
3. Verify miningEnabled = true

**"Block rejected"**
1. Check PoW difficulty target
2. Verify epoch timing (±64 seconds)

### Memory Issues

**"OutOfMemoryError"**
```bash
# Increase heap
java -Xmx4g -jar xdagj.jar -c xdag.conf
```

**"GC overhead"**
- Reduce cache size in config
- Add GC flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200`

## Backup & Recovery

### Backup Data

```bash
# Stop node first!
./script/xdag.sh stop

# Backup database
tar -czf xdag-backup-$(date +%Y%m%d).tar.gz data/

# Backup wallet
cp data/wallet.json wallet-backup.json
```

### Restore Data

```bash
# Stop node
./script/xdag.sh stop

# Restore database
rm -rf data/
tar -xzf xdag-backup-20241209.tar.gz

# Start node
./script/xdag.sh -c xdag.conf
```

### Reset Database

```bash
# Stop node
./script/xdag.sh stop

# Remove all data (keeps wallet)
rm -rf data/dagstore data/txstore data/accountstore

# Start fresh
./script/xdag.sh -c xdag.conf
```

## Performance Tuning

### RocksDB

```hocon
store {
  cacheSize = 256       # Increase for more RAM
  writeBufferSize = 128 # Increase for write-heavy
}
```

### JVM

```bash
java \
  -Xmx8g \
  -Xms4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -jar xdagj.jar -c xdag.conf
```

### Network

- Increase `maxPeers` for better sync
- Use SSD for `dataDir`
- Ensure stable network connection

## Security

### Firewall

```bash
# Allow P2P
ufw allow 8001/tcp

# Restrict RPC to localhost only
# (already default in config)
```

### Wallet Protection

- Never share mnemonic
- Backup wallet.json securely
- Use strong password

### RPC Security

```hocon
rpc {
  http {
    host = "127.0.0.1"  # Localhost only
    # For remote access, use reverse proxy with auth
  }
}
```
