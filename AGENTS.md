# Project Rules & Guidance for AI Coding Agents

Welcome to **Kronenberg** ("*Long live the new flesh*"). When contributing to or generating code in this repository, you must adhere strictly to these architectural boundaries and rules:

---

## 1. Pure AST Traversal Over Regular Expressions
- **NEVER** use regular expressions (`Regex`), multiline substring matching, or index splitting to inspect or transform Kotlin source code.
- **ALWAYS** use Kotlin K2 PSI AST visitors (`KtTreeVisitorVoid`, `KtVisitorVoid`, or `KtElement` visitors) and compiler PSI elements (`KtBinaryExpression`, `KtCallExpression`, `KtProperty`, `KtReturnExpression`, etc.).
- **Rationale**: Regex is brittle against multiline formatting, comments, nested string templates, default arguments, and type hierarchies.

---

## 2. Trusted-Local In-Process Execution & Zero Build Daemons
- **NEVER** spawn external Gradle sub-processes, Maven daemons, or OS subprocesses to execute trusted mutation test passes.
- **ALWAYS** use in-process compilation via `SnippetCompiler` and fresh `URLClassLoader` scopes via `FastSnippetRunner` with Java 21 Virtual Threads for trusted local code.
- **NEVER** describe in-process execution, class loaders, virtual threads, or AST guards as a security sandbox or boundary.
- **ALWAYS** reject untrusted project code through the supported trust policy unless a separate process has OS-enforced isolation and a minimal, host-independent worker classpath.
- **Rationale**: Spawning external build tools introduces multi-second daemon startup penalties for trusted local evaluation, while process separation without OS restrictions is not a security boundary.

---

## 3. Explicit Interfaces & Errors as Values
- Service contracts and mutators must be defined via explicit Kotlin interfaces (`AstMutator`, `SnippetCompiler`, `FastSnippetRunner`, `MutationPipeline`).
- Return structured domain results and sealed status hierarchies (`MutantStatus`: `KILLED`, `SURVIVED`, `TIMED_OUT`, `COMPILE_ERROR`). Never throw unhandled exceptions across module boundaries.

---

## 4. Test-Driven Development (TDD)
- When implementing new mutators or runtime features, define expected behavior in unit/integration tests **first**.
- Verify that every AST mutation rule has corresponding test cases proving both positive mutation generation and correct replacement semantics.
- Compile and run tests (`./gradlew test --no-daemon`) after finishing any functional component.

---

## 5. Documentation & Release Notes Integrity
- Document all non-obvious design decisions in code comments and KDocs.
- Update `CHANGELOG.md` under `## [Unreleased]` for every new feature, bug fix, or AST rule addition.
