# XDAG Architecture Documentation

This directory contains high-level architecture documentation for XDAG.

## 🏗️ Architecture Documents

### [XDAG v5.1 Architecture](./ARCHITECTURE_V5.1.md)
Complete architecture overview for XDAG v5.1

**Topics Covered**:
- System architecture overview
- Core components and their interactions
- Data flow and processing
- Consensus mechanism
- Network layer design
- Storage architecture
- Performance characteristics
- Security considerations

**Key Features**:
- Block + Transaction + Link design
- TPS: 23,200 (96.7% of Visa level)
- Block capacity: 48MB
- Epoch-based consensus
- Hybrid sync protocol

---

### [Address Structure](./New_Address_Structure.md)
XDAG address structure and design

**Topics**:
- Address format specification
- Address generation algorithm
- Checksum and validation
- Address types and use cases
- Backward compatibility

---

## 🎯 For Different Audiences

### System Architects
1. Start with [XDAG v5.1 Architecture](./ARCHITECTURE_V5.1.md)
2. Deep dive into [Design Documentation](../design/)
3. Review [Protocol Specifications](../protocols/)

### Developers
1. Read [v5.1 Architecture](./ARCHITECTURE_V5.1.md) overview
2. Understand [Address Structure](./New_Address_Structure.md)
3. Study [Core Data Structures](../design/CORE_DATA_STRUCTURES.md)
4. Review [Quick Start Guide](../design/QUICK_START.md)

### Security Auditors
1. Study [v5.1 Architecture](./ARCHITECTURE_V5.1.md)
2. Review security features
3. Analyze [Design Decisions](../design/DESIGN_DECISIONS.md)
4. Check [Test Results](../testing/TEST-RESULTS-SUMMARY.md)

---

## 📊 Key Metrics (v5.1)

| Metric | Value | Improvement |
|--------|-------|-------------|
| **TPS** | 23,200 | 232x vs v4 |
| **Block Size** | 48MB | 97,656x vs v4 |
| **Network Efficiency** | -63% bandwidth | vs v4 |
| **Storage Efficiency** | -38.7% | vs Kryo |

---

## 📚 Related Documentation

- [Design Documentation](../design/) - Detailed technical design (14 core docs)
- [White Paper](../introduction/) - Project introduction
- [Protocol Specs](../protocols/) - Network protocols
- [Testing](../testing/) - Test results and validation

---

## 🔑 Core Concepts

### DAG-Based Architecture
- Directed Acyclic Graph structure
- Parallel transaction processing
- No traditional blockchain limitations

### Epoch-Based Consensus
- 64-second epochs
- Smallest-hash winner selection
- Deterministic consensus

### Hybrid Sync Protocol
- Fast initial sync
- Efficient state synchronization
- 12-day finality boundary

---

**Last Updated**: 2025-11-11
**Architecture Version**: v5.1
