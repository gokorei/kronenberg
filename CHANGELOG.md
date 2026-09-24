# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Automated AST mutator Markdown documentation generator (`MutatorDocGenerator`, Gradle task `generateMutatorDocs`).
- Code-backed wiki documentation suite (`Home.md`, `Architecture-And-Sandboxing.md`, `CLI-And-Gradle-Plugin-Guide.md`, `Mutators-Reference.md`, `Release-Notes.md`).
- Automated Keep a Changelog generator (`ChangelogGenerator`, Gradle task `generateChangelog`) directly parsing release notes.
- Project version descriptor resource generator (`generateVersionResource`) and semantic version bump task (`bumpVersion`).
- Dynamic runtime CLI version resolution (`Version.CURRENT`) with `--version` and `-v` flags.
- Comprehensive Detekt static code analysis integration (`detektCheck`, `detektBaseline`).
- Kover multi-module test coverage verification enforcing an 80% line coverage threshold (`koverVerify`).
- GitHub Actions CodeQL security scanning workflow (`.github/workflows/codeql.yml`).
- Automated GitHub Wiki synchronization workflow (`.github/workflows/wiki-sync.yml`).
- Configurable hard compilation deadlines with structured timeout results for trusted snippet compilation.
- Killable process workers for trusted compilation and execution with process-tree termination and cleanup ordering guarantees.

### Security
- Defined a trusted-local-only execution threat model and fail-closed `SnippetExecutionTrust.UNTRUSTED` policy that rejects untrusted project code before parsing, compilation, classpath access, or execution.
- Added abuse-case coverage for filesystem, network, process, reflection, environment, and JVM-global-state capabilities.
- Clarified that `URLClassLoader`, AST guards, property rollback, and process workers are not security boundaries and documented requirements for any future OS-isolated worker.

### Fixed
- Ensured execution timeouts are reported only after the worker process tree is confirmed stopped.
- Prevented compiled output cleanup from racing active trusted execution or compilation workers.
- Closed CLI and Gradle pipelines, directory streams, subprocess readers, and temporary execution trees on every path.
- Added atomic report replacement with explicit target and parent symlink rejection and structured cleanup diagnostics.
- Added repeated CLI and Gradle audit invocation coverage for lifecycle stability.

## [0.1.0] - 2026-09-12

### Added
- In-process K2 PSI AST mutation testing engine for Kotlin with sub-50ms execution cycles.
- 27 built-in AST mutation operators across 19 categories covering relational boundaries, arithmetic, boolean inversions, void method calls, return values, literals, collection operators, null safety, ranges, bitwise, smart casts, string templates, coroutines, scope functions, preconditions, functional Result handling, and data classes.
- In-process fresh `URLClassLoader` scopes using Java 21 Virtual Threads and thread-safe stdout/stderr capture for trusted local execution; not a security boundary.
- Standalone CLI (`kronenberg audit`) with ANSI diffs, JSON, JUnit XML, SARIF v2.1.0, Code Climate, and interactive standalone HTML reports.
- First-party Gradle plugin (`:kronenberg-gradle-plugin` / `com.gokorei.kronenberg`) with `kronenbergCheck` task.
- Surviving mutant test proposer and skeleton synthesizer (`--propose-tests`).
- Git diff-aware incremental auditing (`--diff`, `--staged`) and fast pre-commit hook mode (`--pre-commit`).

### Changed
- Deterministic mutant result caching SPI via `DefaultMutationResultCache`.
- Dynamic baseline timeout calibration and pre-flight baseline validation (`MutantStatus.BASELINE_ERROR`).
- Synthetic test harness with member-method discovery (`@Test`) and per-test kill attribution diagnostics (`Killed by <testFn>()`).
- Best-effort AST host-disruption guard (`SnippetAstSafetyChecker`) for common `System.exit`, `ProcessBuilder`, `Runtime.exec`, and recursive directory deletion patterns; not a security boundary.
- System property snapshot and rollback in the trusted-local runner; not a security boundary.
- Type-aware mutator sampling and discard reduction.
- Multi-module Dokka API documentation pipeline.

### Fixed
- Fixed RangeOperatorMutator `..` syntax whitespace handling using Kotlin 1.9+ `rangeUntil` (`..<`).
- Fixed DestructuringMutator parenthesis token offset slicing safety when whitespace is present.
- Reclassified PreconditionMutator to standard category to ensure defensive assertions run by default.

[Unreleased]: https://github.com/gokorei/kronenberg/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gokorei/kronenberg/releases/tag/v0.1.0
