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

### Changed
- Every top-level source `main` is now removed rather than only the first one, and object-scoped `main` members are left untouched because they are not part of the merged top-level scope.
- `ParsedTestCode` now exposes `testHasMain` and `sourceHasMain` separately plus `requiresSynthesizedMain`; the ambiguous `hasMain` property and its single-flag constructor are deprecated in favour of those.

### Fixed
- Fixed test-harness discovery and execution when source code defines a top-level `main`; source and test entry points are now detected independently, so discovered tests are no longer suppressed and the merged program never declares two top-level `main` functions.
- Fixed source entry-point removal to strip only the declaration itself: file-level annotations, the leading file comment, and every other declaration are preserved, and the merged program now emits file annotations ahead of the package directive as the grammar requires.
- Fixed permanent false survivors by excluding mutations that land inside a top-level `main` the harness removes, because that code is unreachable in the executed program. The excluded line ranges are reported by `TestHarnessSynthesizer.removedSourceMainLineRanges` so callers can apply the same policy.

## [0.1.0] - 2026-09-12

### Added
- In-process K2 PSI AST mutation testing engine for Kotlin with sub-50ms execution cycles.
- 27 built-in AST mutation operators across 19 categories covering relational boundaries, arithmetic, boolean inversions, void method calls, return values, literals, collection operators, null safety, ranges, bitwise, smart casts, string templates, coroutines, scope functions, preconditions, functional Result handling, and data classes.
- In-process isolated `URLClassLoader` execution sandbox using Java 21 Virtual Threads and thread-safe stdout/stderr capture.
- Standalone CLI (`kronenberg audit`) with ANSI diffs, JSON, JUnit XML, SARIF v2.1.0, Code Climate, and interactive standalone HTML reports.
- First-party Gradle plugin (`:kronenberg-gradle-plugin` / `com.gokorei.kronenberg`) with `kronenbergCheck` task.
- Surviving mutant test proposer and skeleton synthesizer (`--propose-tests`).
- Git diff-aware incremental auditing (`--diff`, `--staged`) and fast pre-commit hook mode (`--pre-commit`).

### Changed
- Deterministic mutant result caching SPI via `DefaultMutationResultCache`.
- Dynamic baseline timeout calibration and pre-flight baseline validation (`MutantStatus.BASELINE_ERROR`).
- Synthetic test harness with member-method discovery (`@Test`) and per-test kill attribution diagnostics (`Killed by <testFn>()`).
- Static AST security guard (`SnippetAstSafetyChecker`) blocking `System.exit`, `ProcessBuilder`, `Runtime.exec`, and recursive directory deletion.
- System property snapshot and rollback guard in runner sandbox.
- Type-aware mutator sampling and discard reduction.
- Multi-module Dokka API documentation pipeline.

### Fixed
- Fixed RangeOperatorMutator `..` syntax whitespace handling using Kotlin 1.9+ `rangeUntil` (`..<`).
- Fixed DestructuringMutator parenthesis token offset slicing safety when whitespace is present.
- Reclassified PreconditionMutator to standard category to ensure defensive assertions run by default.

[Unreleased]: https://github.com/gokorei/kronenberg/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gokorei/kronenberg/releases/tag/v0.1.0
