# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Fixed `RangeOperatorMutator` to mutate `..` to `..<` (Kotlin 1.9+ `rangeUntil`) rather than `until`, preventing syntax errors caused by missing whitespace in expressions like `0..10`.
- Fixed `DestructuringMutator` to use exact PSI parenthesis tokens (`lPar` and `rPar`), preventing syntax corruption when whitespace is present inside destructuring declarations.
- Reclassified `PreconditionMutator` from `CONDITION_REPLACEMENT` to `PRECONDITION` so defensive precondition assertions (`require`, `check`, `requireNotNull`, `checkNotNull`) are active during standard audit runs.

### Changed
- Added `COROUTINE`, `SCOPE_FUNCTION`, and `RESULT_ERROR_HANDLING` to `MutatorCategory`, and introduced `EqualityMutator` covering structural and referential equality comparisons (`==` $\leftrightarrow$ `!=`, `===` $\leftrightarrow$ `!==`).
- Reclassified `ScopeFunctionMutator` and `TakeIfMutator` to `SCOPE_FUNCTION`, `CoroutineFlowMutator` and `CoroutineConcurrencyMutator` to `COROUTINE`, and `ResultMutator` to `RESULT_ERROR_HANDLING`.
- Added `mutatorsForCategory` and `mutatorsForCategories` filtering APIs to `MutatorRegistry`.
- Strengthened `DogfoodMutationAuditSpec` assertions across all 7 scenarios to assert non-trivial mutant generation (`totalMutants > 0`), kill rate (`killedCount > 0`), zero survival (`survivedCount == 0`), and threshold passage (`isPassed == true`).

### Added
- Preserved source file path (`filePath: String? = null`) on `AstMutant`, `AstEdit`, and `MutationContext`, propagating relative file paths throughout directory audits into terminal output, HTML reports, and SARIF code scanning alerts.
- Initial project scaffolding for multi-module Gradle layout (`kronenberg-core`, `kronenberg-runner`, `kronenberg-cli`).
- Open source community standards, governance, and documentation (Apache 2.0 License, Contributing Guide, Code of Conduct, Security Policy).
- GitHub Actions CI matrix and release automation workflows.
- Kotlin 2.3+ and Java 21 LTS toolchain baseline.
- Code quality automation with Spotless (ktlint) and ABI tracking via Binary Compatibility Validator (`apiCheck`).
- Dokka V2 documentation generation.
- `kronenberg-core`:
  - Pluggable `AstMutator` SPI and `MutatorRegistry` with dynamic SPI extension support.
  - 26 AST mutation rules powered by pure K2 PSI AST visitors:
    - `RelationalBoundaryMutator`: `<` $\leftrightarrow$ `<=`, `>` $\leftrightarrow$ `>=`, `==` $\leftrightarrow$ `!=`
    - `ArithmeticOperatorMutator`: `+` $\leftrightarrow$ `-`, `*` $\leftrightarrow$ `/`, `%` $\leftrightarrow$ `*`
    - `CompoundAssignmentMutator`: `+=` $\leftrightarrow$ `-=`, `*=` $\leftrightarrow$ `/=`, `%=` $\leftrightarrow$ `*=`
    - `UnaryOperatorMutator`: `+x` $\leftrightarrow$ `-x`, `++x` $\leftrightarrow$ `--x`, `x++` $\leftrightarrow$ `x--`
    - `BooleanInversionMutator`: `&&` $\leftrightarrow$ `||`, `!flag` $\to$ `flag`, `true` $\leftrightarrow$ `false`
    - `ReturnValueMutator`: `return true` $\to$ `false`, dual string return substitutions (`""` and `"mutated"`), number returns $\to$ `0`, `return emptyList()`, `return null`
    - `VoidMethodCallMutator`: Standalone void calls $\to$ `Unit`
    - `LiteralMutationMutator`: Numeric constants (`Int`, `Double`, `Float`, `Long`)
    - `CollectionOperatorMutator`: `filter` $\leftrightarrow$ `filterNot`, `any` $\leftrightarrow$ `all`, `take` $\leftrightarrow$ `drop`, `first` $\leftrightarrow$ `last`, `map` $\leftrightarrow$ `mapNotNull`, `sorted` $\leftrightarrow$ `sortedDescending`, `minOrNull` $\leftrightarrow$ `maxOrNull`, `find` $\leftrightarrow$ `findLast`, `associate` $\leftrightarrow$ `associateBy`
    - `ConditionReplacementMutator`: If-conditions $\to$ `true`, `false`
    - `NullSafetyMutator`: Elvis expressions `a ?: b` $\to$ default `b`
    - `ElvisLeftHandMutator`: Elvis expressions `a ?: b` $\to$ left-hand `a`
    - `NonNullAssertionMutator`: Non-null assertions `s!!` $\to$ `s`
    - `RangeOperatorMutator`: `0 until n` $\leftrightarrow$ `0..n`, `downTo` $\leftrightarrow$ `..`, `0..<n` $\leftrightarrow$ `0..n` (Kotlin 1.9+ rangeUntil)
    - `BitwiseOperatorMutator`: `a and b` $\leftrightarrow$ `a or b`, `a xor b` $\leftrightarrow$ `a and b`
    - `SafeCallMutator`: `a?.b` $\to$ `a!!.b`
    - `SmartCastMutator`: `is` $\leftrightarrow$ `!is`, `as` $\leftrightarrow$ `as?`
    - `StringTemplateMutator`: Interpolated expressions `${expr}` $\to$ `""`
    - `CoroutineFlowMutator`: `delay(x)` $\to$ `delay(0L)` and Flow stream operator mutations
    - `TakeIfMutator`: `takeIf` $\leftrightarrow$ `takeUnless` predicate inversions
    - `ScopeFunctionMutator`: `apply` $\leftrightarrow$ `also`, `let` $\leftrightarrow$ `run` scope function swapping
    - `PreconditionMutator`: `require`/`check` condition negation and bypass, `requireNotNull`/`checkNotNull` unwrapping
    - `ResultMutator`: `Result.getOrElse`/`getOrDefault`/`getOrNull` $\to$ `getOrThrow`, `onSuccess` $\leftrightarrow$ `onFailure`
    - `DataClassCopyMutator`: Data class `copy()` parameter override stripping and single-argument omission
    - `DestructuringMutator`: Multi-variable destructuring declaration positional variable swapping
    - `CoroutineConcurrencyMutator`: `Dispatchers.IO` $\leftrightarrow$ `Default`, `SupervisorJob()` $\leftrightarrow$ `Job()`, `supervisorScope` $\leftrightarrow$ `coroutineScope`, `async` $\to$ `launch`
  - `AstMutantGenerator` for First-Order (FOM) and Higher-Order (HOM) compound mutant generation with strided sampling and line-range targeting.
  - Pure in-memory AST parsing via `K2SnippetFrontend.parsePsi`.
- `kronenberg-runner`:
  - In-process K2 compilation with structured diagnostic reporting via `DefaultSnippetCompiler`.
  - In-process isolated `URLClassLoader` execution sandbox with Java 21 Virtual Threads and thread-safe `ThreadLocalPrintStream` via `DefaultFastSnippetRunner`.
  - `DefaultMutationExecutionPipeline` with dynamic baseline timeout calibration and pre-parsed test caching.
  - Static AST security inspector `SnippetAstSafetyChecker` intercepting `System.exit`, `exitProcess`, `Runtime.halt`, `ProcessBuilder`, `Runtime.exec`, and recursive file deletion calls.
  - `System.getProperties()` snapshot and rollback guard in `FastSnippetRunner` for complete thread and property isolation.
  - Explicit baseline pre-flight validation and diagnostic reporting via `MutantStatus.BASELINE_ERROR` and `MutationReport.baselineError`.
  - Automatic `fun main()` test dispatcher synthesizer with per-test kill attribution diagnostics (`Killed by <testFn>()`) and static call-graph reachability ordering.
  - Structured concurrency using Kotlin Coroutines (`coroutineScope`, `async`, `awaitAll`) for parallel mutant evaluation.
  - Deterministic evaluation result caching SPI via `MutationResultCache` and `DefaultMutationResultCache`.
  - Metaspace leak prevention and hardened `URLClassLoader` resource closing.
  - Comprehensive self-testing dogfooding mutation audit test suite (`DogfoodMutationAuditSpec`).
- `kronenberg-cli`:
  - Command-line interface with Clikt (`kronenberg audit --source <path> --test <path>`).
  - Standardized JUnit XML mutation report exporter (`--junit-xml <path>`).
  - Interactive standalone HTML mutation report exporter (`--html-report <path>`).
  - SARIF v2.1.0 report exporter (`--sarif <path>`) and GitHub Actions PR workflow review annotation emitter (`--github-annotations`).
  - Git diff-aware incremental auditing (`--diff <ref>` and `--staged`) using `GitDiffParser`.
  - Evaluation result caching flag (`--cache`).
  - Directory and multi-file batch scanning support (`--source-dir <dir> --test-dir <dir>`).
  - Baseline error diagnosis in ANSI colored terminal output.
  - Threshold status code exit gates.
