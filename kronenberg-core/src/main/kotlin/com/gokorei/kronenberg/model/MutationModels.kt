package com.gokorei.kronenberg.model

import kotlinx.serialization.Serializable

/**
 * Status outcome of an evaluated mutant.
 */
@Serializable
public enum class MutantStatus {
    /** The mutant caused at least one test assertion to fail or throw an exception. */
    KILLED,

    /** All tests passed despite the syntactic mutation; indicates potential test gap. */
    SURVIVED,

    /** The mutated code caused an infinite loop or exceeded the timeout threshold. */
    TIMED_OUT,

    /** The mutation failed in-memory compilation. */
    COMPILE_ERROR,

    /** The baseline code or test suite failed before any mutation was applied. */
    BASELINE_ERROR,
}

/**
 * Trust classification for project code that Kronenberg is asked to compile and execute.
 */
@Serializable
public enum class SnippetExecutionTrust {
    /** Code the operator trusts with the same authority as the build and test process that runs it. */
    TRUSTED_LOCAL,

    /** Code that must never be parsed, compiled, or executed by this process. Rejected, not isolated. */
    UNTRUSTED,
}

/**
 * Fail-closed policy for [SnippetExecutionTrust].
 *
 * [isTrusted] is an allow-list rather than a denylist: only the explicit
 * [SnippetExecutionTrust.TRUSTED_LOCAL] value authorizes execution. `null` and any value added
 * to the enum by a future release are therefore untrusted by construction, so a new trust level
 * cannot be executed by an older Kronenberg that has never heard of it.
 */
public object SnippetExecutionTrustPolicy {
    /**
     * Returns true only for [SnippetExecutionTrust.TRUSTED_LOCAL].
     */
    public fun isTrusted(trust: SnippetExecutionTrust?): Boolean = trust == SnippetExecutionTrust.TRUSTED_LOCAL

    /**
     * Resolves a trust value by its exact serialized name.
     *
     * `null`, blank, mis-cased, and unrecognized names, including names introduced by a newer
     * Kronenberg release, resolve to [SnippetExecutionTrust.UNTRUSTED] so that an unparsable
     * configuration cannot silently become trusted.
     */
    public fun resolve(name: String?): SnippetExecutionTrust =
        SnippetExecutionTrust.entries.firstOrNull { it.name == name } ?: SnippetExecutionTrust.UNTRUSTED

    /**
     * Returns true only when [name] resolves to [SnippetExecutionTrust.TRUSTED_LOCAL].
     */
    public fun isTrustedName(name: String?): Boolean = isTrusted(resolve(name))
}

/**
 * Functional category of an AST mutator rule.
 */
@Serializable
public enum class MutatorCategory {
    RELATIONAL_BOUNDARY,
    EQUALITY,
    ARITHMETIC_OPERATOR,
    COMPOUND_ASSIGNMENT,
    UNARY_OPERATOR,
    BOOLEAN_INVERSION,
    BITWISE_OPERATOR,
    RETURN_VALUE,
    VOID_METHOD_CALL,
    LITERAL_MUTATION,
    COLLECTION_OPERATOR,
    CONDITION_REPLACEMENT,
    NULL_SAFETY,
    RANGE_OPERATOR,
    EXTREME,
    PRECONDITION,
    COROUTINE,
    SCOPE_FUNCTION,
    RESULT_ERROR_HANDLING,
}

/**
 * Descriptor of a discrete AST replacement edit before full file transformation.
 */
@Serializable
public data class AstEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
    val originalText: String,
    val description: String,
    val line: Int,
    val column: Int,
    val filePath: String? = null,
)

/**
 * Represents a single syntactic mutation injected into Kotlin source AST.
 */
@Serializable
public data class AstMutant(
    val id: String,
    val mutatorName: String,
    val category: MutatorCategory,
    val line: Int,
    val column: Int,
    val originalText: String,
    val replacementText: String,
    val mutatedSource: String,
    val filePath: String? = null,
)

/**
 * Execution result for a single mutant.
 */
@Serializable
public data class MutantResult(
    val mutant: AstMutant,
    val status: MutantStatus,
    val executionTimeMs: Long,
    val failureMessage: String? = null,
)

/**
 * Aggregated mutation audit report across all evaluated mutants.
 */
@Serializable
public data class MutationReport(
    val totalMutants: Int,
    val killedCount: Int,
    val survivedCount: Int,
    val timeoutCount: Int,
    val compileErrorCount: Int,
    val mutationScore: Double,
    val results: List<MutantResult> = emptyList(),
    val baselineError: String? = null,
) {
    public val isPassed: Boolean get() = survivedCount == 0 && baselineError == null
}

/**
 * Configuration options for mutation audit executions.
 *
 * # Trust is always explicit on the trust-aware shape
 *
 * [executionTrust] is part of the primary constructor so that the generated `copy` carries it and a
 * copied configuration cannot silently revert to a trusted value. It deliberately has **no default
 * value**: the ten-parameter primary constructor is the trust-aware shape, and it forces every
 * caller to state the trust classification instead of inheriting one by accident. Omitting
 * [executionTrust] therefore resolves to the pre-trust-boundary shape, whose documented behaviour is
 * the trusted-local default.
 *
 * # Binary compatibility with the pre-boundary ABI
 *
 * `MutationConfig` shipped in 0.1.0 as a nine-parameter data class, so the released JVM descriptors
 * are fixed:
 *
 * - `<init>()V`
 * - `<init>(DDJZZLjava/lang/Integer;Ljava/util/List;ZLjava/util/List;)V`
 * - `<init>(DDJZZLjava/lang/Integer;Ljava/util/List;ZLjava/util/List;ILkotlin/jvm/internal/DefaultConstructorMarker;)V`
 * - `copy(DDJZZLjava/lang/Integer;Ljava/util/List;ZLjava/util/List;)` and its `copy$default` bridge
 *
 * The third descriptor is the synthetic constructor that backs omitted default arguments. Leaving
 * `executionTrust` defaulted on the primary constructor would have moved that synthetic descriptor
 * to ten parameters and broken every caller compiled against 0.1.0, even though
 * `kotlinx.binary-compatibility-validator` ignores synthetic members. This class therefore restores
 * all four descriptors:
 *
 * - the explicit no-argument constructor re-declares `<init>()V`;
 * - the all-defaulted nine-parameter secondary constructor re-declares both the nine-parameter
 *   constructor and its `int`/`DefaultConstructorMarker` synthetic bridge, and it is the documented
 *   trusted-local default for a configuration that omits a trust value;
 * - the nine-parameter `copy` re-declares `copy` and `copy$default`, carrying [executionTrust] into
 *   the copy so copying can never widen trust.
 *
 * The two sets of default values must stay identical, because `MutationConfig()` resolves through the
 * no-argument constructor while `MutationConfig(minScore = 90.0)` resolves through the nine-parameter
 * one. `MutationConfigBinaryCompatibilitySpec` asserts the agreement, asserts each of the descriptors
 * above through reflection, and drives the legacy synthetic constructor through its bit mask, so
 * dropping or desynchronising the shim fails the build rather than silently breaking downstream
 * linkage.
 */
@Serializable
public data class MutationConfig(
    val minScore: Double = 80.0,
    val timeoutMultiplier: Double = 3.0,
    val baselineTimeoutMs: Long = 1000L,
    val higherOrderMutants: Boolean = false,
    val includeExtreme: Boolean = false,
    val maxMutants: Int? = null,
    val targetLines: List<Int>? = null,
    val enableCache: Boolean = false,
    val extraClasspath: List<String> = emptyList(),
    /**
     * Trust classification for the code this configuration is about to execute.
     *
     * No default value: see the class documentation. Omitting this argument binds to the
     * nine-parameter constructor, which is [SnippetExecutionTrust.TRUSTED_LOCAL].
     */
    val executionTrust: SnippetExecutionTrust,
) {
    /**
     * No-argument constructor retained for the pre-trust-boundary `<init>()V` descriptor.
     *
     * Kotlin only generates a synthetic no-argument constructor for an all-defaulted *primary*
     * constructor, and the primary constructor is trust-aware. Without this declaration, callers
     * compiled against 0.1.0 would fail to link on `MutationConfig()`.
     */
    public constructor() : this(executionTrust = SnippetExecutionTrust.TRUSTED_LOCAL)

    /**
     * Nine-parameter constructor retained so that callers compiled before the trust boundary keep
     * resolving, including the synthetic default-argument bridge they were compiled against.
     *
     * Omitting [executionTrust] therefore resolves here and yields
     * [SnippetExecutionTrust.TRUSTED_LOCAL], the pre-boundary behaviour. This is the single, explicit
     * home of that default; see the class documentation.
     *
     * The default values below must stay identical to the primary constructor's, or
     * `MutationConfig(minScore = 90.0)` would silently differ from `MutationConfig()`.
     */
    @Suppress("LongParameterList")
    public constructor(
        minScore: Double = 80.0,
        timeoutMultiplier: Double = 3.0,
        baselineTimeoutMs: Long = 1000L,
        higherOrderMutants: Boolean = false,
        includeExtreme: Boolean = false,
        maxMutants: Int? = null,
        targetLines: List<Int>? = null,
        enableCache: Boolean = false,
        extraClasspath: List<String> = emptyList(),
    ) : this(
        minScore,
        timeoutMultiplier,
        baselineTimeoutMs,
        higherOrderMutants,
        includeExtreme,
        maxMutants,
        targetLines,
        enableCache,
        extraClasspath,
        SnippetExecutionTrust.TRUSTED_LOCAL,
    )

    /**
     * Nine-parameter `copy` retained so that binaries compiled before the trust boundary, which
     * resolved `copy` and `copy$default` against nine parameters, keep linking. It carries
     * [executionTrust] over to the copy, so copying a configuration can never widen its trust.
     *
     * The arity is fixed by the published ABI, not by taste.
     */
    @Suppress("LongParameterList")
    public fun copy(
        minScore: Double = this.minScore,
        timeoutMultiplier: Double = this.timeoutMultiplier,
        baselineTimeoutMs: Long = this.baselineTimeoutMs,
        higherOrderMutants: Boolean = this.higherOrderMutants,
        includeExtreme: Boolean = this.includeExtreme,
        maxMutants: Int? = this.maxMutants,
        targetLines: List<Int>? = this.targetLines,
        enableCache: Boolean = this.enableCache,
        extraClasspath: List<String> = this.extraClasspath,
    ): MutationConfig =
        MutationConfig(
            minScore,
            timeoutMultiplier,
            baselineTimeoutMs,
            higherOrderMutants,
            includeExtreme,
            maxMutants,
            targetLines,
            enableCache,
            extraClasspath,
            executionTrust,
        )
}
