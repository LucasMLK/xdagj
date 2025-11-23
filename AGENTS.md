# Repository Guidelines

## Project Structure & Module Organization
Core runtime lives in `src/main/java/io/xdag`, split into focused packages (`consensus`, `core`, `p2p`, `api`, `store`, `utils`). Shared configs, genesis assets, and logging templates sit in `src/main/resources`; copy `xdag-devnet.conf` when you need custom settings. Tests mirror that structure in `src/test/java` with fixtures under `src/test/resources`. `docs/` tracks architecture and protocol notes, `script/xdag.sh` wraps the CLI launcher, `devnet/` holds seed wallets and bootstrap data, and `test-nodes/` contains multi-node suites for manual sync validation.

## Build, Test, and Development Commands
- `mvn clean package -DskipTests` compiles against JDK 21, runs license checks, and emits the fat jar in `target/`.
- `mvn test` executes the full JUnit 4 suite with Surefire + Byte Buddy agent; narrow scope via `-Dtest=BlockApiServiceTest` or any comma-separated list.
- `./script/xdag.sh -t -c src/main/resources/xdag-devnet.conf` launches a local devnet node; override `-c` with a copy stored outside `src/main/resources` when experimenting.
- `mvn license:check` validates MIT headers before review.

## Coding Style & Naming Conventions
Java classes use two-space indentation, same-line braces, and Lombok annotations (`@Slf4j`, `@Getter`) to minimize boilerplate. Keep package names aligned to domain boundaries (e.g., DAG logic in `io.xdag.core`, networking in `io.xdag.p2p`). Log through SLF4J placeholders instead of `System.out`, and never remove the license block at the top of files.

## Testing Guidelines
JUnit 4.13 and Mockito 5 power the test suite. Name files `*Test.java` and methods `shouldDoXWhenCondition`. New behavior needs regression coverage plus a note about the executed `mvn test` snippet in the PR description. Use `test-nodes/suite*/` when validating synchronization flows or config permutations, and commit deterministic fixtures only.

## Commit & Pull Request Guidelines
Commits follow the existing `type: summary - context (TAG-ID)` style, such as `fix: Phase 12 - Repair orphan queue (BUG-079)` or `docs: Update CODE_REVIEW_PLAN.md`. Keep each commit focused on one concern and mention related issue IDs or debt tags. Pull requests should summarize the subsystem touched, list verification commands/output, link issues, and call out config or API changes (attach `curl` samples or screenshots for REST/UI work). Update relevant docs/configs alongside code, and coordinate breaking changes with the maintainers before requesting review.

## Security & Configuration Tips
Load wallet passwords via the `XDAGJ_WALLET_PASSWORD` environment variable rather than hard-coding. Store experimental configs under `misc/` or your branch-specific folders to avoid overwriting shared resources, and never commit private keys or mainnet secrets to the repo.
