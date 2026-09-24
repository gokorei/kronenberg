# Kronenberg — Wiki Home

> *"Long live the new flesh."*

**Kronenberg** is a high-performance, in-process **K2 PSI AST mutation testing engine** for Kotlin. It evaluates trusted local test suites by injecting syntactic mutants directly into Kotlin ASTs and executing them in fresh virtual-thread contexts. Its in-process execution is not a security boundary.

---

## 📚 Documentation Index

- **[Mutators Reference](Mutators-Reference)** — Authoritative catalog of all 27 built-in AST mutation operators, categorized and described directly from compiler AST rules.
- **[Architecture & Execution Trust Boundary](Architecture-And-Sandboxing)** — Supported trusted-local threat model, fail-closed untrusted mode, in-process execution limits, and requirements for any future OS-isolated worker.
- **[CLI & Gradle Plugin Guide](CLI-And-Gradle-Plugin-Guide)** — Practical developer guide covering `kronenberg audit`, Git diff-aware auditing, pre-commit hook mode, HTML/SARIF/JUnit exporters, and Gradle configuration.
- **[Release Notes](Release-Notes)** — Track upcoming roadmap features (`## Next`), changelogs, and historical releases.

---

## ⚡ Key Differentiators

| Traditional Mutation Tools (e.g. Pitest) | Kronenberg |
| :--- | :--- |
| Operates on compiled JVM bytecode | Pure compiler K2 PSI AST traversal |
| Spawns external build daemons & subprocesses | In-process compilation & trusted-local virtual-thread execution |
| 10–60 seconds startup overhead | Sub-50ms per-mutant evaluation cycle |
| Generic Java bytecode mutators | 27 Kotlin-idiomatic AST operators (Null safety, coroutines, scope functions, data classes) |

---

## 🔗 Repository Resources

- **Source Code**: [gokorei/kronenberg](https://github.com/gokorei/kronenberg)
- **API Documentation**: [GitHub Pages](https://gokorei.github.io/kronenberg/)
- **License**: [Apache 2.0](https://github.com/gokorei/kronenberg/blob/main/LICENSE)
