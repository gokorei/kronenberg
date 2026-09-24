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

### Configuration Validation
Kronenberg validates configuration before parsing, compiling, or executing code. Invalid values are returned as structured `ConfigurationError` values containing `field`, `code`, `message`, `actual`, and `limit`. CLI JSON output includes these errors in `configurationErrors`; terminal and Gradle output use the same codes and fields. No mutant result is generated when validation fails.

| Input | Supported limit |
| --- | --- |
| `minScore` | Finite, from `0.0` through `100.0` |
| `baselineTimeoutMs` | From `1` through `300000` milliseconds |
| `timeoutMultiplier` | Finite, from `1.0` through `100.0` |
| `maxMutants` | Optional, from `1` through `10000` |
| Source code | At most `1000000` characters or `2000000` file bytes |
| Test code | At most `1000000` characters or `2000000` file bytes |
| Source files | At most `2000` per audit |
| Test files | At most `2000` per audit |
| Classpath | At most `256` entries; each entry is at most `4096` characters |
| Report results | At most `10000` results |
| Report result text | At most `10000000` characters |

Classpath entries must resolve to existing files or directories. Kronenberg converts them to real absolute paths, normalizes them, removes duplicates, and passes the canonical list to in-process compilation and execution.

The CLI exposes the multiplier as `--timeout-multiplier`:
```bash
kronenberg audit --source src/main/kotlin/Calculator.kt --test src/test/kotlin/CalculatorTest.kt --timeout-multiplier 3.0
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
    minScore.set(80.0)
    baselineTimeoutMs.set(2000L)
    timeoutMultiplier.set(3.0)
    maxMutants.set(10000)
    includeExtreme.set(false)
    enableCache.set(true)
}
```

### Executing Audits
Run mutation testing across the project:
```bash
./gradlew kronenbergCheck
```
Generated reports are automatically saved under `build/reports/kronenberg/`.
