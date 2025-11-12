# XDAG Operations Guide

This directory contains operational documentation for running and maintaining XDAG nodes.

## ⚙️ Operations Documents

### RPC & API

#### [RPC API Reference](./rpc-api.md)
Complete RPC API documentation
- All available RPC methods
- Request/response formats
- Code examples
- Error handling

**Language**: English

---

#### [RPC Service Tutorial](./rpc-service-tutorial.md)
Step-by-step guide for setting up RPC service
- Service configuration
- Security setup
- Integration examples

**Language**: English

---

### Node Management

#### Snapshot Management
Database snapshot and backup procedures

- **中文**: [snapshot-zh.md](./snapshot-zh.md)

**Topics**:
- Creating snapshots
- Restoring from snapshots
- Backup best practices

---

#### Time Synchronization
Node time synchronization guide

- **English**: [time-sync-en.md](./time-sync-en.md)
- **中文**: [time-sync-zh.md](./time-sync-zh.md)

**Topics**:
- NTP configuration
- Clock synchronization
- Troubleshooting time issues

---

## 🎯 For Node Operators

### Initial Setup
1. Configure RPC service: [RPC Service Tutorial](./rpc-service-tutorial.md)
2. Set up time sync: [Time Synchronization](./time-sync-en.md)
3. Plan backup strategy: [Snapshot Management](./snapshot-zh.md)

### Daily Operations
- Monitor RPC endpoints
- Check time synchronization
- Schedule regular snapshots
- Review logs

### Troubleshooting
- RPC connection issues → Check [RPC API Reference](./rpc-api.md)
- Time drift → See [Time Synchronization](./time-sync-en.md)
- Database recovery → Use [Snapshot Management](./snapshot-zh.md)

---

## 📚 Related Documentation

- [Deployment Guide](../deployment/) - Production deployment
- [Setup Guides](../setup/) - Environment configuration
- [Architecture](../architecture/) - System architecture

---

**Last Updated**: 2025-11-11
