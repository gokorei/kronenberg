# Architecture & Execution Trust Boundary

Kronenberg compiles K2 PSI AST mutants in the host JVM and executes trusted local snippets in fresh class-loader scopes on Java 21 virtual threads. These mechanisms provide class, thread, and lifecycle isolation. They are not a security boundary.

## Supported Threat Model

Kronenberg supports **trusted local project code only**. The operator must trust the audited source, tests, dependencies, and build configuration with the same authority as the Gradle build and test process that normally executes them.

Untrusted repositories, pull-request code, hostile build scripts, and attacker-controlled source or test code are **not supported**. Selecting `SnippetExecutionTrust.UNTRUSTED` returns a structured baseline error before source parsing, compilation, classpath access, or snippet execution. A trusted-local audit must be run only in an environment where Kronenberg already has authority to run the project. Use an external disposable VM, container, or CI isolation boundary for untrusted repositories, and keep credentials, signing keys, production configuration, and valuable host data outside that environment.

## Absent Trust Is Resolved Explicitly

`executionTrust` has **no default value** on the trust-aware ten-parameter `MutationConfig` constructor, so "absent trust" is not an implicit fallback. It resolves through exactly the two paths below, and the behaviour of each is tested.

| How trust is omitted | Resolves to | Why |
| --- | --- | --- |
| A Kotlin call omits the argument and binds the nine-parameter constructor, for example `MutationConfig()` or `MutationConfig(minScore = 90.0)` | `SnippetExecutionTrust.TRUSTED_LOCAL`, named literally in that constructor's body | The call site is code in the operator's own build, compiled and linked by the operator. It is also the behaviour every configuration written before the trust boundary relied on. |
| A serialized configuration omits the `executionTrust` key | Decoding fails with `MissingFieldException` | Serialized configuration is the only channel where a trust value can arrive from outside the process, so absence is never read as consent. |

An unknown or mis-cased serialized name also fails to decode, and `SnippetExecutionTrustPolicy.resolve` maps any unrecognized name to `SnippetExecutionTrust.UNTRUSTED`. Nothing in the deserialization path can produce a trusted configuration from a value Kronenberg did not positively recognize.

The trusted default is therefore scoped to in-process construction, and it is a named decision in source rather than an inherited property. Restoring a default value on `executionTrust` would silently make absent serialized trust trusted again; `MutationModelsSerializationSpec` and `MutationConfigBinaryCompatibilitySpec` both fail if that happens.

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

`DefaultMutationExecutionPipeline` is the supported mutation-audit entrypoint and the only place the trust decision is made:

1. `SnippetExecutionTrustPolicy` is consulted before any project code is parsed, compiled, given a classpath, or executed.
2. The policy is an allow-list. Only the explicit `SnippetExecutionTrust.TRUSTED_LOCAL` value continues through the compile, baseline, mutation, and report pipeline; every other value produces a structured baseline error.
3. A denylist of `UNTRUSTED` alone would be unsafe, because a trust level added by a future release would fall through to execution on every version that predates it.
4. The CLI and Gradle plugin construct trusted-local configurations. They do not claim to isolate hostile repositories.

`FastSnippetRunner` and `SnippetCompiler` sit below the policy boundary. They are lower-level mechanisms that do not evaluate trust: their contracts are restricted to trusted local code, and calling them directly bypasses the policy check. They are not the policy boundary, not an isolation layer, and must not be used as an untrusted execution service.

## Model Evolution

`MutationConfig` shipped in 0.1.0 as a nine-parameter data class, so its released JVM descriptors are fixed: `<init>()V`, the nine-parameter `<init>`, the nine-parameter synthetic `<init>` that backs omitted default arguments, and the nine-parameter `copy` together with its `copy$default` bridge.

Appending `executionTrust` to the primary constructor without care would have moved all four. Kotlin only generates a synthetic default-argument bridge for a constructor that has default values, and a caller compiled against 0.1.0 links against that bridge directly, so leaving `executionTrust` defaulted would have silently broken every pre-boundary caller. `kotlinx.binary-compatibility-validator` filters synthetic members out of `apiCheck`, so the published `kronenberg-core.api` dump would not have reported the breakage either.

The class therefore carries three declarations whose only purpose is the published ABI:

- an explicit no-argument constructor, because Kotlin generates the synthetic `<init>()V` only for an all-defaulted **primary** constructor and the primary constructor is now trust-aware;
- an all-defaulted nine-parameter secondary constructor, which restores both the nine-parameter `<init>` and its `int`/`DefaultConstructorMarker` synthetic bridge, and which names `SnippetExecutionTrust.TRUSTED_LOCAL` in its body as the documented default for an omitted trust value;
- a nine-parameter `copy`, which restores `copy` and `copy$default` and carries `executionTrust` into the copy so that copying a configuration can never widen its trust.

`MutationConfigBinaryCompatibilitySpec` looks up each of those descriptors by reflection, including one test that drives the legacy synthetic constructor through its bit mask to prove the bridge still substitutes defaults, and it asserts every pre-boundary accessor, so dropping the shim fails the build. The published `kronenberg-core.api` diff against `main` is now purely additive: no entry is removed and no entry changes shape, synthetic or not.

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
