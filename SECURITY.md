# Security Policy

## Supported Versions

We release patches for security vulnerabilities on the following versions:

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1.0 | :x:                |

## Execution Trust Boundary

Kronenberg supports trusted local project code only. Source, tests, dependencies, and build configuration must be trusted because snippet execution occurs in the Gradle or application JVM with that process's operating-system authority.

Kronenberg's `URLClassLoader`, virtual threads, AST guard, and global-state rollback are reliability controls, not security boundaries. They do not isolate project code from the host filesystem, network, subprocesses, environment, reflection, or JVM-global state.

The policy boundary is the audit entrypoint, `DefaultMutationExecutionPipeline`, which evaluates `MutationConfig.executionTrust` before any project code is parsed, compiled, given a classpath, or executed. `SnippetExecutionTrustPolicy` is an allow-list: only the explicit `SnippetExecutionTrust.TRUSTED_LOCAL` value authorizes execution, and `null`, an unrecognized serialized name, and a trust level added by a future release are all rejected.

`executionTrust` has no default value on the trust-aware `MutationConfig` constructor, so the ways of omitting it are resolved explicitly rather than by an implicit fallback:

- A Kotlin call that omits the argument binds the nine-parameter constructor, whose body names `SnippetExecutionTrust.TRUSTED_LOCAL`. That call site is code in your own build, compiled and linked by you, and it reproduces the pre-boundary behaviour. This is the only source of the trusted default.
- A serialized configuration that omits the `executionTrust` key fails to decode with `MissingFieldException`. Absence is never read as consent on the one channel where a trust value can arrive from outside the process.
- A serialized configuration that carries an unknown or mis-cased name fails to decode, and `SnippetExecutionTrustPolicy.resolve` maps such a name to `SnippetExecutionTrust.UNTRUSTED`.

`FastSnippetRunner` and `SnippetCompiler` sit **below** that boundary. They are lower-level mechanisms that do not evaluate trust: their contracts are restricted to trusted local code, and calling them directly bypasses the policy check entirely. They are not the policy boundary and must not be used as an untrusted execution service or as an isolation layer.

Untrusted repository execution is unsupported and rejected before parsing, compilation, classpath access, or execution. Run hostile repositories only in an external disposable VM, container, or isolated CI environment without secrets or internal-network access. See [Architecture & Execution Trust Boundary](docs/wiki/Architecture-And-Sandboxing.md) for the complete model.

## Reporting a Vulnerability

If you discover a security vulnerability within Kronenberg, please **do not open a public issue**. Instead, please report security issues responsibly.

### How to Report
1. Report security vulnerabilities confidentially by creating a [Private Security Advisory](https://github.com/gokorei/kronenberg/security/advisories/new).
2. Include reproduction steps, sample code, and potential impact.
3. You will receive an acknowledgment within 48 hours.
4. We will coordinate a patched release and appropriate disclosure timeline.

Thank you for improving the security of Kronenberg and its users.
