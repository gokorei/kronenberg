# Kronenberg — Wiki Home

> *"Long live the new flesh."*

**Kronenberg** is a high-performance, in-process **K2 PSI AST mutation testing engine** for Kotlin. Designed from the ground up for speed, deterministic execution, and sandboxed safety, Kronenberg evaluates test suite quality by injecting syntactic mutants directly into Kotlin ASTs and executing tests in-process using Java 21 Virtual Threads.

---

## 📚 Documentation Index

- **[Mutators Reference](Mutators-Reference)** — Authoritative catalog of all 27 built-in AST mutation operators, categorized and described directly from compiler AST rules.
- **[Architecture & Sandboxing](Architecture-And-Sandboxing)** — Deep dive into in-process K2 compilation (`SnippetCompiler`), the virtual-thread sandbox (`FastSnippetRunner`), ClassLoader isolation, and static AST safety checking.
- **[CLI & Gradle Plugin Guide](CLI-And-Gradle-Plugin-Guide)** — Practical developer guide covering `kronenberg audit`, Git diff-aware auditing, pre-commit hook mode, HTML/SARIF/JUnit exporters, and Gradle configuration.
- **[Release Notes](Release-Notes)** — Track upcoming roadmap features (`## Next`), changelogs, and historical releases.

---

## ⚡ Key Differentiators

| Traditional Mutation Tools (e.g. Pitest) | Kronenberg |
| :--- | :--- |
| Operates on compiled JVM bytecode | Pure compiler K2 PSI AST traversal |
| Spawns external build daemons & subprocesses | In-process compilation & virtual-thread sandboxes |
| 10–60 seconds startup overhead | Sub-50ms per-mutant evaluation cycle |
| Generic Java bytecode mutators | 27 Kotlin-idiomatic AST operators (Null safety, coroutines, scope functions, data classes) |

---

## 🔗 Repository Resources

- **Source Code**: [gokorei/kronenberg](https://github.com/gokorei/kronenberg)
- **API Documentation**: [GitHub Pages](https://gokorei.github.io/kronenberg/)
- **License**: [Apache 2.0](https://github.com/gokorei/kronenberg/blob/main/LICENSE)
