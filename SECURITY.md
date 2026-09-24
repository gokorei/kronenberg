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

Untrusted repository execution is unsupported and rejected before parsing, compilation, classpath access, or execution. Run hostile repositories only in an external disposable VM, container, or isolated CI environment without secrets or internal-network access. See [Architecture & Execution Trust Boundary](docs/wiki/Architecture-And-Sandboxing.md) for the complete model.

## Reporting a Vulnerability

If you discover a security vulnerability within Kronenberg, please **do not open a public issue**. Instead, please report security issues responsibly.

### How to Report
1. Report security vulnerabilities confidentially by creating a [Private Security Advisory](https://github.com/gokorei/kronenberg/security/advisories/new).
2. Include reproduction steps, sample code, and potential impact.
3. You will receive an acknowledgment within 48 hours.
4. We will coordinate a patched release and appropriate disclosure timeline.

Thank you for improving the security of Kronenberg and its users.
