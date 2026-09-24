# Release Notes

Overview of all notable changes to Kronenberg by version.

---

## Next

### New Features
- Automated AST mutator Markdown documentation generator (`MutatorDocGenerator`, Gradle task `generateMutatorDocs`).
- Code-backed wiki documentation suite (`Home.md`, `Architecture-And-Sandboxing.md`, `CLI-And-Gradle-Plugin-Guide.md`, `Mutators-Reference.md`, `Release-Notes.md`).
- Automated Keep a Changelog generator (`ChangelogGenerator`, Gradle task `generateChangelog`) directly parsing release notes.
- Project version descriptor resource generator (`generateVersionResource`) and semantic version bump task (`bumpVersion`).
- Dynamic runtime CLI version resolution (`Version.CURRENT`) with `--version` and `-v` flags.
- Comprehensive Detekt static code analysis integration (`detektCheck`, `detektBaseline`).
- Kover multi-module test coverage verification enforcing an 80% line coverage threshold (`koverVerify`).
- GitHub Actions CodeQL security scanning workflow (`.github/workflows/codeql.yml`).
- Automated GitHub Wiki synchronization workflow (`.github/workflows/wiki-sync.yml`).

### Security
- Defined a trusted-local-only execution threat model and fail-closed `SnippetExecutionTrust.UNTRUSTED` policy that rejects untrusted project code before parsing, compilation, classpath access, or execution.
- Added abuse-case coverage for filesystem, network, process, reflection, environment, and JVM-global-state capabilities.
- Clarified that class loaders, virtual threads, AST guards, property rollback, and ordinary child processes are not security boundaries and documented requirements for any future OS-isolated worker.

### Bug Fixes

### Improvements

---

## v0.1.0 — 2026-09-12

### New Features
- In-process K2 PSI AST mutation testing engine for Kotlin with sub-50ms execution cycles.
- 27 built-in AST mutation operators across 19 categories covering relational boundaries, arithmetic, boolean inversions, void method calls, return values, literals, collection operators, null safety, ranges, bitwise, smart casts, string templates, coroutines, scope functions, preconditions, functional Result handling, and data classes.
- In-process fresh `URLClassLoader` scopes using Java 21 Virtual Threads and thread-safe stdout/stderr capture for trusted local execution; not a security boundary.
- Standalone CLI (`kronenberg audit`) with ANSI diffs, JSON, JUnit XML, SARIF v2.1.0, Code Climate, and interactive standalone HTML reports.
- First-party Gradle plugin (`:kronenberg-gradle-plugin` / `com.gokorei.kronenberg`) with `kronenbergCheck` task.
- Surviving mutant test proposer and skeleton synthesizer (`--propose-tests`).
- Git diff-aware incremental auditing (`--diff`, `--staged`) and fast pre-commit hook mode (`--pre-commit`).

### Improvements
- Deterministic mutant result caching SPI via `DefaultMutationResultCache`.
- Dynamic baseline timeout calibration and pre-flight baseline validation (`MutantStatus.BASELINE_ERROR`).
- Synthetic test harness with member-method discovery (`@Test`) and per-test kill attribution diagnostics (`Killed by <testFn>()`).
- Best-effort AST host-disruption guard (`SnippetAstSafetyChecker`) for common `System.exit`, `ProcessBuilder`, `Runtime.exec`, and recursive directory deletion patterns; not a security boundary.
- System property snapshot and rollback in the trusted-local runner; not a security boundary.
- Type-aware mutator sampling and discard reduction.
- Multi-module Dokka API documentation pipeline.

### Bug Fixes
- Fixed RangeOperatorMutator `..` syntax whitespace handling using Kotlin 1.9+ `rangeUntil` (`..<`).
- Fixed DestructuringMutator parenthesis token offset slicing safety when whitespace is present.
- Reclassified PreconditionMutator to standard category to ensure defensive assertions run by default.
