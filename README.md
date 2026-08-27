# Kronenberg

[![CI](https://github.com/gokorei/kronenberg/actions/workflows/ci.yml/badge.svg)](https://github.com/gokorei/kronenberg/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.x-purple.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21%2B-orange.svg)](https://adoptium.net)

> *"Long live the new flesh."*

**Kronenberg** is a high-performance, in-process **K2 PSI AST mutation testing engine** for Kotlin. Designed from the ground up for speed and deterministic execution, Kronenberg evaluates test suite quality by injecting precise syntactic mutants directly into Kotlin ASTs and executing tests within an in-memory virtual-thread sandbox—with zero external build daemons and sub-50ms mutation cycles.

---

## ⚡ Highlights

- **Pure K2 PSI Traversal**: Mutates code via compiler AST elements (`KtTreeVisitorVoid`), avoiding brittle regexes or byte-code transforms.
- **In-Memory Compilation & Execution**: Compiles mutated snippets in-process and runs tests inside isolated `URLClassLoader` sandboxes using Java 21 Virtual Threads.
- **First-Order (FOM) & Higher-Order (HOM) Mutants**: Supports traditional single-point mutations as well as complex multi-operator mutations with strided sampling.
- **Sub-50ms Mutant Execution**: Designed for instant feedback during local development, pre-commit hooks, and AI agent test verification loops.
- **Pluggable Mutator SPI**: Extensible rule engine covering relational boundaries, arithmetic operators, boolean inversions, collection operations, null safety, and extreme body-horror mutations.
- **Standalone CLI & Library Integrations**: Run `kronenberg audit` directly from your terminal, integrate into CI/CD pipelines, or embed in MCP servers, Kotest, and JUnit runners.

---

## 🏛️ Architecture & Modules

Kronenberg is organized into a clean, multi-module architecture:

```
                  ┌──────────────────────┐
                  │    kronenberg-cli    │  (Terminal Auditor & CI Exporter)
                  └──────────┬───────────┘
                             │ depends on
                  ┌──────────▼───────────┐
                  │  kronenberg-runner   │  (In-Process Compiler & Sandboxed Runner)
                  └──────────┬───────────┘
                             │ depends on
                  ┌──────────▼───────────┐
                  │   kronenberg-core    │  (AST Mutators, SPI & Mutation Models)
                  └──────────────────────┘
```

| Module | Description | Key Dependencies |
| :--- | :--- | :--- |
| **`kronenberg-core`** | Domain models (`AstMutant`, `MutantResult`, `MutationReport`), AST mutator SPI (`AstMutator`, `MutatorRegistry`), and standard/extreme K2 PSI mutation rules. | `kotlin-compiler-embeddable`, `kotlinx-serialization-json` |
| **`kronenberg-runner`** | In-process K2 compilation (`SnippetCompiler`), virtual-thread sandbox (`FastSnippetRunner`), and `MutationExecutionPipeline`. | `kronenberg-core`, `kotlinx-coroutines-core` |
| **`kronenberg-cli`** | Standalone CLI binary providing `kronenberg audit` with ANSI terminal diffs, JUnit XML, and JSON export. | `kronenberg-runner`, `clikt` |

---

## 🚀 Quickstart

### Using the CLI

```bash
# Run a mutation audit against a Kotlin source file and its test suite
kronenberg audit --source src/main/kotlin/Calculator.kt --test src/test/kotlin/CalculatorTest.kt

# Enforce a minimum mutation score threshold (exits with non-zero on failure)
kronenberg audit --source src/main/kotlin/OrderService.kt --test src/test/kotlin/OrderServiceTest.kt --threshold 85.0

# Export structured results for CI/CD
kronenberg audit --source src/main/kotlin/Engine.kt --test src/test/kotlin/EngineTest.kt --json --output report.json
```

### Using as a Gradle Dependency

Add Kronenberg to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.gokorei.kronenberg:kronenberg-core:0.1.0")
    implementation("com.gokorei.kronenberg:kronenberg-runner:0.1.0")
}
```

```kotlin
import com.gokorei.kronenberg.runner.MutationExecutionPipeline
import com.gokorei.kronenberg.model.MutationConfig

val pipeline = MutationExecutionPipeline()
val report = pipeline.execute(
    sourceCode = sourceText,
    testCode = testText,
    config = MutationConfig(minScore = 80.0)
)

println("Mutation Score: ${report.mutationScore}% (${report.killedCount}/${report.totalMutants} killed)")
```

---

## 🧬 Built-in Mutators

| Mutator Category | Target Syntax | Transformation Examples |
| :--- | :--- | :--- |
| **Relational Boundary** | `<`, `<=`, `>`, `>=` | `<` $\to$ `<=`, `>=` $\to$ `>` |
| **Equality & Identity** | `==`, `!=`, `===`, `!==` | `==` $\to$ `!=`, `!=` $\to$ `==` |
| **Arithmetic Operators** | `+`, `-`, `*`, `/`, `%` | `+` $\to$ `-`, `*` $\to$ `/` |
| **Boolean Inversions** | `&&`, `\|\|`, `!` | `&&` $\to$ `\|\|`, `!condition` $\to$ `condition` |
| **Return Values** | `return x`, `return true` | `return 0`, `return false`, `return null` |
| **Void / Unit Method Calls**| `doSideEffect()` | Removed / replaced with `Unit` |
| **Collection Operators** | `.map`, `.filter`, `.take` | `.take` $\to$ `.drop`, `.firstOrNull` $\to$ `null` |
| **Null Safety & Elvis** | `?:`, `?.`, `!!` | `x ?: y` $\to$ `y`, `x?.let` $\to$ `null` |
| **Range Operators** | `..`, `until`, `downTo` | `0 until n` $\to$ `0..n` |

---

## 🛠️ Building from Source

### Prerequisites
- JDK 21+ (Java 21 LTS toolchain configured)
- Git

```bash
git clone https://github.com/gokorei/kronenberg.git
cd kronenberg

# Build and run all tests
./gradlew check test

# Run the CLI tool
./gradlew :kronenberg-cli:run --args="--help"
```

---

## 🤝 Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md) before opening a pull request.

---

## 📄 License

Kronenberg is licensed under the [Apache License, Version 2.0](LICENSE).
