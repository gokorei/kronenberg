# Architecture & Sandboxing

Kronenberg achieves sub-50ms per-mutant evaluation cycles through an entirely in-process compilation and execution architecture. This document details the core lifecycle, sandboxing guarantees, and security inspection mechanisms.

---

## 🏛️ Module Overview

```
                      ┌────────────────────────────┐
                      │      kronenberg-cli        │
                      └─────────────┬──────────────┘
                                    │ depends on
                      ┌─────────────▼──────────────┐
                      │    kronenberg-runner       │
                      └─────────────┬──────────────┘
                                    │ depends on
                      ┌─────────────▼──────────────┐
                      │     kronenberg-core        │
                      └────────────────────────────┘
```

1. **`kronenberg-core`**:
   - `AstMutator`: SPI contract for AST mutations.
   - `MutatorRegistry`: Rule catalog holding 27 standard and extreme mutation operators.
   - `AstMutantGenerator`: Traverses K2 PSI ASTs (`KtTreeVisitorVoid`), generates first-order (FOM) and higher-order (HOM) mutants with strided sampling.
   - `MutationModels`: `AstMutant`, `MutantResult`, `MutationReport`, `MutantStatus`.

2. **`kronenberg-runner`**:
   - `DefaultSnippetCompiler`: In-process K2 compilation using `kotlin-compiler-embeddable` without spawning `kotlinc` or Gradle daemons.
   - `DefaultFastSnippetRunner`: Thread-isolated execution using Java 21 Virtual Threads and isolated `URLClassLoader` sandboxes.
   - `SnippetAstSafetyChecker`: Static AST security pre-flight checker blocking dangerous host operations.
   - `TestHarnessSynthesizer`: Automatic discovery of test methods (`@Test`), call-graph reachability ordering, and per-test kill attribution diagnostics.
   - `DefaultMutationExecutionPipeline`: Orchestrates dynamic baseline calibration, caching, parallel evaluation, and structured report synthesis.

3. **`kronenberg-cli`**:
   - Clikt-based CLI binary providing `kronenberg audit`.
   - Multi-format exporters: ANSI diff terminal output, JSON, JUnit XML, SARIF v2.1.0, Code Climate, and interactive standalone HTML reports.

---

## 🛡️ Sandbox & Security Model

Running mutated code safely in-process requires rigorous isolation to protect the host build environment and test runner:

### 1. Static AST Security Guard (`SnippetAstSafetyChecker`)
Before compiling or executing any code or test snippet, the AST is inspected for destructive host-level operations:
- `System.exit()`, `Runtime.getRuntime().halt()`, and `exitProcess()` calls.
- Subprocess spawning: `ProcessBuilder` and `Runtime.getRuntime().exec()`.
- Destructive recursive directory deletions (`deleteRecursively`).
Any snippet containing these patterns is immediately blocked before compilation.

### 2. Virtual Thread & Timeout Enforcement
Each mutant is evaluated inside a dedicated Java 21 Virtual Thread managed by an isolated executor.
- **Dynamic Baseline Calibration**: The baseline unmutated test suite runs first to measure natural test duration ($T_{base}$).
- **Configurable Multiplier**: Per-mutant timeout is calibrated as $\max(T_{base} \times \text{multiplier}, T_{min})$.
- Infinite loops or hanging mutants are interrupted and classified as `MutantStatus.TIMED_OUT`.

### 3. System Property Rollback
Mutated tests may manipulate global JVM system properties (`System.setProperty`). The runner takes a complete snapshot of `System.getProperties()` prior to snippet execution and restores the exact baseline state upon completion, preventing cross-test state pollution.

### 4. Metaspace & ClassLoader Isolation
To prevent JVM Metaspace exhaustion from repeated snippet compilation:
- Every execution loads classes through a fresh, isolated `URLClassLoader`.
- The runner explicitly closes the classloader and cleans up temporary class files upon test completion.
