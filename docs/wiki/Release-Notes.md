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
- Added `SnippetExecutionTrustPolicy`, an allow-list gate that authorizes only the explicit `SnippetExecutionTrust.TRUSTED_LOCAL` value, so `null`, unrecognized serialized names, and trust levels added by future releases all fail closed instead of falling through to execution.
- Removed the default value from `MutationConfig.executionTrust` and made a serialized configuration that omits the `executionTrust` key fail to decode with `MissingFieldException`, so an absent trust value can no longer be read as consent on the only channel where a trust value can arrive from outside the process.
- Documented absent-trust resolution consistently in `README.md`, `SECURITY.md`, and the architecture wiki: an omitted Kotlin argument binds the nine-parameter constructor, whose body names `TRUSTED_LOCAL`, while an omitted serialized key is rejected.
- Added abuse-case coverage for filesystem, network, process, reflection, environment, and JVM-global-state capabilities, plus a positive control proving explicitly trusted code still reaches the compiler.
- Clarified that class loaders, virtual threads, AST guards, property rollback, and ordinary child processes are not security boundaries and documented requirements for any future OS-isolated worker.
- Documented that the policy boundary is the `DefaultMutationExecutionPipeline` entrypoint and that `FastSnippetRunner` and `SnippetCompiler` sit below it, do not evaluate trust, and must only be used with trusted local code.

### Bug Fixes
- Restored the legacy nine-argument synthetic default-argument constructor descriptor `(DDJZZLjava/lang/Integer;Ljava/util/List;ZLjava/util/List;ILkotlin/jvm/internal/DefaultConstructorMarker;)V` on `MutationConfig`, which callers compiled against 0.1.0 link against and which `kotlinx.binary-compatibility-validator` filters out of `apiCheck`.
- Restored the legacy `MutationConfig()` no-argument descriptor, which Kotlin only generates for an all-defaulted primary constructor and which the trust-aware primary constructor had displaced.
- Preserved the published `MutationConfig` ABI by re-declaring the nine-parameter constructor and `copy` alongside the new `executionTrust` field, keeping the pre-boundary `copy` and `copy$default` descriptors resolvable for previously compiled callers.
- Ensured every `MutationConfig.copy` form carries `executionTrust` into the copy, so copying a configuration can no longer silently revert an untrusted request to trusted.
- Made the `kronenberg-core.api` diff against the previous release purely additive: no entry is removed and no entry changes shape, synthetic or not.

### Improvements
- Covered the trust boundary with reflection-based binary-compatibility tests for every pre-boundary `MutationConfig` descriptor, including a test that drives the legacy synthetic constructor through its bit mask to prove the bridge still substitutes defaults, plus copy-preservation and fail-closed policy tests covering absent, future, and unknown trust values.

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
