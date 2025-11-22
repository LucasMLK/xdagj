# GEMINI.md - Context & Instructions for XDAGJ

## Project Overview
**XDAGJ** is the Java reference implementation of the **XDAG 1.0b protocol**. It is a high-performance blockchain node implementation featuring a DAG (Directed Acyclic Graph) consensus engine, RocksDB storage, and Netty-based P2P/API networking.

- **Protocol**: XDAG 1.0b (Epoch-based DAG, smallest hash wins).
- **Language**: Java 21.
- **Build System**: Maven.
- **Key Libraries**: Netty, RocksDB JNI, Apache Tuweni, BouncyCastle, Jackson, RandomX JNI.
- **Current State**: Active development, focusing on stability, concurrency safety, and code cleanup (see `CODE_REVIEW_PLAN.md`).

## Architectural Highlights
*   **Entry Point**: `io.xdag.cli.XdagCli` triggers `DagKernel`.
*   **Core Kernel**: `DagKernel` manages the lifecycle of all components (Storage, Networking, Consensus).
*   **Consensus**: `DagChainImpl` implements XDAG 1.0b rules.
    *   **Primary**: Epoch-based competition (smallest hash wins).
    *   **Secondary**: Height assignment for querying (not consensus).
    *   **Atomic Processing**: Uses `RocksDBTransactionManager` for ACID-compliant block import and transaction execution.
*   **Storage**: RocksDB with column families (`DagStore`, `TransactionStore`, `AccountStore`, `OrphanBlockStore`).
*   **Sync**: `HybridSyncManager` handles linear history download + DAG solidification.

## Development & Build
**Requirements**: JDK 21+.

### Common Commands
- **Build (Fast)**: `mvn clean package -DskipTests`
- **Run Tests**: `mvn test`
- **Run Specific Test**: `mvn -Dtest=AtomicBlockProcessingTest test`
- **Launch Devnet Node**: `./script/xdag.sh -t -c config/xdag-devnet.conf`

## Codebase Conventions
1.  **Style**: Adhere strictly to the existing Java coding style.
    -   Check `misc/code-style/` for formatter configs (IntelliJ/Eclipse).
    -   Use `Lombok` for boilerplate (`@Getter`, `@Setter`, `@Value`).
2.  **Logging**: Use SLF4J/Log4j2.
3.  **Concurrency**:
    -   **Critical**: Block processing is now atomic. Any changes to block import logic *must* use `RocksDBTransactionManager` to maintain ACID guarantees.
    -   **Caution**: `AccountStore` and `TransactionStore` indexes currently have known concurrency limitations (see "Technical Debt" in `CODE_REVIEW_PLAN.md`).
4.  **Testing**:
    -   New features *must* include unit tests.
    -   Refer to `AtomicBlockProcessingTest.java` for testing complex state changes.

## Critical Context for AI Agent
*   **Atomic Block Processing**: This is a recently introduced feature (Nov 2025). When modifying `DagChainImpl` or storage logic, ALWAYS verify if the operation is part of a transaction (`writeBatch`).
*   **Code Review Plan**: The project is undergoing a systematic review. Before refactoring, check `CODE_REVIEW_PLAN.md` to ensure alignment with ongoing efforts (e.g., dead code removal, specific technical debt items).
*   **Configuration**: Logic has been simplified. `AbstractConfig` and `Config` have recently been cleaned up.

## Key Files
*   `docs/ARCHITECTURE.md`: **The Single Source of Truth** for protocol and design.
*   `CODE_REVIEW_PLAN.md`: Tracks current bugs, fixes, and active refactoring tasks.
*   `src/main/java/io/xdag/core/DagChainImpl.java`: Core consensus logic.
*   `src/main/java/io/xdag/db/rocksdb/transaction/RocksDBTransactionManager.java`: Transaction management.
