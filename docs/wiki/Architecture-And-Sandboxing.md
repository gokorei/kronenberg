# Architecture & Execution Trust Boundary

Kronenberg compiles K2 PSI AST mutants in the host JVM and executes trusted local snippets in fresh class-loader scopes on Java 21 virtual threads. These mechanisms provide class, thread, and lifecycle isolation. They are not a security boundary.

## Supported Threat Model

Kronenberg supports **trusted local project code only**. The operator must trust the audited source, tests, dependencies, and build configuration with the same authority as the Gradle build and test process that normally executes them.

Untrusted repositories, pull-request code, hostile build scripts, and attacker-controlled source or test code are **not supported**. `MutationConfig.executionTrust` defaults to `SnippetExecutionTrust.TRUSTED_LOCAL`. Selecting `SnippetExecutionTrust.UNTRUSTED` returns a structured baseline error before source parsing, compilation, classpath access, or snippet execution.

This mode must be used only in an environment where Kronenberg already has authority to run the project. Use an external disposable VM, container, or CI isolation boundary for untrusted repositories. Keep credentials, signing keys, production configuration, and valuable host data outside that environment.

## Capabilities Exposed in Trusted Mode

Trusted snippets run in the application or Gradle test-worker JVM with the same operating-system identity. They may use capabilities that the host process can use, including:

| Capability | Trusted-mode behavior | Untrusted-mode behavior |
| --- | --- | --- |
| Filesystem | Reads and writes permitted by the host OS identity | Rejected before compilation |
| Network | Opens connections permitted by host firewall and network policy | Rejected before compilation |
| Processes | May start processes permitted by the host OS identity | Rejected before compilation |
| Reflection | May reflect within the worker JVM and its classpath | Rejected before compilation |
| Environment | May read the worker environment | Rejected before compilation |
| Global state | May mutate JVM state that the rollback guard does not restore | Rejected before compilation |

Abuse-case tests cover each row and assert that untrusted requests never invoke the compiler or runner.

## Execution Policy

`DefaultMutationExecutionPipeline` is the supported mutation-audit entrypoint:

1. `SnippetExecutionTrust.UNTRUSTED` is rejected before any project code is parsed or compiled.
2. `SnippetExecutionTrust.TRUSTED_LOCAL` continues through the existing compile, baseline, mutation, and report pipeline.
3. The CLI and Gradle plugin use the trusted-local default. They do not claim to isolate hostile repositories.

`FastSnippetRunner` is a lower-level trusted-local execution interface. Calling it directly bypasses the pipeline policy check, so its contract is restricted to trusted bytecode. It must not be used as an untrusted execution service.

## Why There Is No Untrusted Worker

A worker JVM with a minimal classpath is still host code execution. Process separation prevents direct access to the Gradle or application JVM, but it does not by itself restrict filesystem, network, environment, subprocess, or other host capabilities.

Kronenberg does not currently bundle a portable, OS-enforced sandbox backend. Rather than treating a `URLClassLoader`, AST denylist, virtual thread, or ordinary child JVM as a security boundary, the untrusted mode remains explicitly unsupported.

Consequently, there is no untrusted worker classpath to configure or inherit. A rejected request never receives `classesDir`, `extraClasspath`, Gradle state, application state, or any host object. This is the fail-closed behavior, not a claim that the trusted in-process classpath is minimal or secure.

Any future untrusted worker must be a separate artifact and entrypoint with all of these properties:

- an explicit, minimal worker classpath assembled independently from the Gradle and application host classpath;
- no Gradle daemon, application service, cache, build state, or host JVM objects passed to the worker;
- a structured, size-limited worker protocol;
- OS-enforced filesystem, network, process, environment, and resource restrictions;
- process-tree termination on timeout and on worker protocol failure;
- portable abuse-case tests for filesystem, network, process, reflection, environment, and global state;
- a fail-closed mode when the required OS sandbox is unavailable.

Meeting only the classpath or child-process requirements is insufficient.

## In-Process Mechanisms and Limits

The trusted-local path still uses mechanisms that improve correctness and performance:

- K2 PSI parses and transforms Kotlin source without regular-expression source rewriting.
- `DefaultSnippetCompiler` invokes the embedded Kotlin compiler in the host JVM and does not spawn Gradle or Maven subprocesses.
- `DefaultFastSnippetRunner` creates a fresh `URLClassLoader` for each trusted snippet and runs it on a Java 21 virtual thread.
- Thread-local stdout and stderr capture separates concurrent snippet output.
- Timeout cancellation, class-loader closure, and temporary-directory cleanup bound normal resource lifetime.
- System properties are snapshotted and restored after a trusted snippet returns.
- `SnippetAstSafetyChecker` uses K2 PSI to reject common host-disrupting calls such as process termination, selected subprocess creation, and destructive deletion.

These are reliability controls for trusted code. The AST guard is a small, best-effort denylist and is trivially bypassable through reflection, indirection, aliases, loaded dependencies, or operations not present in the rule set. Neither the class loader nor the virtual thread restricts operating-system authority.

## Operational Guidance

- Run audits only for code you would already compile and test directly.
- Do not expose an audit endpoint to repository authors or other untrusted tenants.
- Do not run untrusted audits on a workstation containing credentials, SSH keys, cloud configuration, or valuable files.
- Run untrusted projects in externally managed disposable isolation with no secrets and no access to internal networks.
- Treat the current `UNTRUSTED` rejection as a security feature and do not remove it without implementing the full worker requirements above.
