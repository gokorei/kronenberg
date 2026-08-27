# Contributing to Kronenberg

Thank you for your interest in contributing to **Kronenberg**! We welcome bug fixes, improvements, new AST mutator rules, documentation updates, and feature proposals.

---

## Code of Conduct

All contributors and maintainers are expected to adhere to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

---

## Architectural Principles & Philosophy

1. **Static AST Traversal Over Regex**: NEVER use regex or string pattern matching to parse or modify Kotlin code. Always use Kotlin K2 PSI AST visitors (`KtTreeVisitorVoid`).
2. **Deterministic & In-Process**: Never spawn external Gradle/JVM daemons at runtime. All compilation and execution must remain sandboxed inside in-process Virtual Threads and isolated `URLClassLoader` instances.
3. **Explicit Interfaces & Errors as Values**: Public APIs must be defined as explicit Kotlin interfaces, returning typed result models (`Result<T>` or sealed outcome hierarchies).
4. **Outcome-Driven TDD**: Write tests first to specify functional behavior before writing implementations. Every feature and mutator rule requires comprehensive unit tests verifying that mutants are killed appropriately.

---

## Development Setup

### Prerequisites
- JDK 21+
- Gradle (the repo provides `./gradlew`)

```bash
git clone https://github.com/gokorei/kronenberg.git
cd kronenberg

# Verify the build and run existing tests
./gradlew check test
```

---

## Contribution Workflow

1. **Fork & Branch**: Create a feature branch from `main`:
   ```bash
   git checkout -b feat/my-new-mutator
   ```
2. **Write Tests First**: Create unit tests in the appropriate module (`kronenberg-core`, `kronenberg-runner`, `kronenberg-cli`) capturing expected outcomes.
3. **Implement**: Keep changes minimal, focused, and idiomatic Kotlin.
4. **Verify Locally**:
   ```bash
   # Run all tests
   ./gradlew test

   # Run linters and checks
   ./gradlew check
   ```
5. **Update Release Notes**: Note your change in `CHANGELOG.md` under `## [Unreleased]`.
6. **Open a Pull Request**: Submit your PR with a clear summary of what was changed and why.

---

## Kotlin Coding Conventions

- Target **Kotlin 2.3+** and **Java 21**.
- Prefer functional programming styles: immutability (`val`, data classes, immutable collections) and predictability of inputs/outputs.
- Minimize side effects. Logging should always be directed to `stderr` or structured loggers, never unformatted `println()`.
- Maintain public KDoc documentation for all public types, interfaces, and methods in `kronenberg-core` and `kronenberg-runner`.
