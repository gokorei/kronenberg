# CLI & Gradle Plugin Guide

Kronenberg provides two primary interfaces for mutation audits: the standalone terminal CLI binary (`kronenberg`) and the official Gradle plugin (`:kronenberg-gradle-plugin`).

---

## 💻 CLI Usage (`kronenberg audit`)

### Basic Audit
Audit a single Kotlin source file against its test suite:
```bash
kronenberg audit --source src/main/kotlin/Calculator.kt --test src/test/kotlin/CalculatorTest.kt
```

### Enforce Quality Threshold
Exit with non-zero status code if mutation score falls below threshold:
```bash
kronenberg audit --source src/main/kotlin/OrderService.kt --test src/test/kotlin/OrderServiceTest.kt --threshold 85.0
```

### Git Diff-Aware Auditing
Target only lines modified in uncommitted or staged changes:
```bash
# Audit lines modified against main
kronenberg audit --source src/main/kotlin/Service.kt --test src/test/kotlin/ServiceTest.kt --diff origin/main

# Audit only staged Git changes
kronenberg audit --source src/main/kotlin/Service.kt --test src/test/kotlin/ServiceTest.kt --staged
```

### Git Pre-Commit Hook Mode
Run lightning-fast pre-commit audits on staged changes:
```bash
kronenberg audit --pre-commit --threshold 80.0
```
- Automatically detects staged Kotlin files via `git diff --cached`.
- Enforces First-Order Mutants (FOM) and fast 500ms timeouts.
- Exits `0` if all mutations pass, `1` if mutants survive.

### Report Exporters
Export mutation results into industry-standard formats:
```bash
# Standalone Interactive HTML report
kronenberg audit --source-dir src/main/kotlin --test-dir src/test/kotlin --html-report build/reports/mutation.html

# GitHub Actions SARIF code scanning annotations
kronenberg audit --source-dir src/main/kotlin --test-dir src/test/kotlin --sarif build/reports/kronenberg.sarif

# Standardized JUnit XML report
kronenberg audit --source-dir src/main/kotlin --test-dir src/test/kotlin --junit-xml build/reports/kronenberg-junit.xml

# Code Climate Issue JSON
kronenberg audit --source-dir src/main/kotlin --test-dir src/test/kotlin --codeclimate build/reports/codeclimate.json

# Actionable test skeletons for surviving mutants
kronenberg audit --source src/main/kotlin/Service.kt --test src/test/kotlin/ServiceTest.kt --propose-tests
```

#### JUnit XML and audit coverage
The JUnit XML report (shared by the CLI and the Gradle plugin) makes incomplete audits visible
instead of rendering them as a green suite:

| Element | Meaning |
| --- | --- |
| `<failure type="MutationSurvived">` | A mutant survived the test suite. |
| `<failure type="Timeout">` | A mutant exceeded the calibrated execution timeout. |
| `<error type="CompileError">` | A mutant failed in-memory compilation. |
| `<error type="BaselineError">` | The baseline pre-flight failed, or the audit could not cover every file. Increments `errors`. |
| `<skipped/>` | Files that were out of the audit scope: unchanged in the diff window, or with no mutation opportunity. Increments `skipped`. |

Only coverage gaps fail an audit. Source files with no matching test suite, and audits that
covered no file at all, fail closed; unchanged files in a `--diff`/`--staged` audit are expected
and never fail the build. Every audit prints its per-file coverage summary, for example:

```
 Source Coverage: audited 2 of 51 source file(s); unchanged 48, missing test 0, no mutation opportunity 1
```

---

## 🐘 Gradle Plugin (`com.gokorei.kronenberg`)

### Installation
Add the plugin to `build.gradle.kts`:
```kotlin
plugins {
    id("com.gokorei.kronenberg") version "0.1.0-SNAPSHOT"
}

kronenberg {
    minScore.set(80.0)             // Minimum mutation score threshold (%)
    baselineTimeoutMs.set(2000L)   // Baseline timeout in milliseconds
    includeExtreme.set(false)      // Enable structural/extreme mutators
    enableCache.set(true)          // Cache unchanged mutant results
}
```

### Executing Audits
Run mutation testing across the project:
```bash
./gradlew kronenbergCheck
```
Generated reports are automatically saved under `build/reports/kronenberg/`.
