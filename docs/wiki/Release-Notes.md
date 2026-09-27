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

### Bug Fixes
- Mutation audits now fail closed when no mutants are generated, when results are incomplete, or when compile errors, timeouts, baseline failures, or surviving mutants are present; scoring and gate evaluation are centralized in `MutationReportEvaluator` in core.
- Incremental (`--diff`, `--staged`, `--pre-commit`) and multi-file directory audits no longer fail because of untouched source files. Unchanged files, files without a matching test, and files without a mutation opportunity are now tallied separately via `AuditCoverage`/`AuditSkipReason`, and only missing tests or an audit that covered nothing fail closed.
- `MutationReportEvaluator` now reconciles a report's `results` list against its summary counters and rejects contradictory status/count combinations (`totalMutants`, per-status counters, serialized `mutationScore`, negative counters, and `BASELINE_ERROR` results without a message) through the new `AuditViolation` hierarchy.
- JUnit XML reports now render an aggregate `baselineError` as an `<error type="BaselineError">` test case that increments the `errors` attribute, and per-reason coverage skips as `<skipped/>` test cases, so incomplete audits can no longer render as a green suite in CI dashboards.
- The CLI and the Gradle plugin no longer duplicate the JUnit XML writer: both now delegate to the shared `JUnitXmlReportWriter` in core, keeping a single report and gate policy across entry points.

### Improvements
- Audit failures now print the concrete `AuditViolation` reasons (CLI terminal output and the Gradle exception message) instead of a generic policy message, and every audit prints a per-file coverage summary line.

---

## v0.1.0 — 2026-09-12

### New Features
- In-process K2 PSI AST mutation testing engine for Kotlin with sub-50ms execution cycles.
- 27 built-in AST mutation operators across 19 categories covering relational boundaries, arithmetic, boolean inversions, void method calls, return values, literals, collection operators, null safety, ranges, bitwise, smart casts, string templates, coroutines, scope functions, preconditions, functional Result handling, and data classes.
- In-process isolated `URLClassLoader` execution sandbox using Java 21 Virtual Threads and thread-safe stdout/stderr capture.
- Standalone CLI (`kronenberg audit`) with ANSI diffs, JSON, JUnit XML, SARIF v2.1.0, Code Climate, and interactive standalone HTML reports.
- First-party Gradle plugin (`:kronenberg-gradle-plugin` / `com.gokorei.kronenberg`) with `kronenbergCheck` task.
- Surviving mutant test proposer and skeleton synthesizer (`--propose-tests`).
- Git diff-aware incremental auditing (`--diff`, `--staged`) and fast pre-commit hook mode (`--pre-commit`).

### Improvements
- Deterministic mutant result caching SPI via `DefaultMutationResultCache`.
- Dynamic baseline timeout calibration and pre-flight baseline validation (`MutantStatus.BASELINE_ERROR`).
- Synthetic test harness with member-method discovery (`@Test`) and per-test kill attribution diagnostics (`Killed by <testFn>()`).
- Static AST security guard (`SnippetAstSafetyChecker`) blocking `System.exit`, `ProcessBuilder`, `Runtime.exec`, and recursive directory deletion.
- System property snapshot and rollback guard in runner sandbox.
- Type-aware mutator sampling and discard reduction.
- Multi-module Dokka API documentation pipeline.

### Bug Fixes
- Fixed RangeOperatorMutator `..` syntax whitespace handling using Kotlin 1.9+ `rangeUntil` (`..<`).
- Fixed DestructuringMutator parenthesis token offset slicing safety when whitespace is present.
- Reclassified PreconditionMutator to standard category to ensure defensive assertions run by default.
