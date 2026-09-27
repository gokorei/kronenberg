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
 * [executionTrust] is part of the primary constructor so that the generated `copy` carries it and a
 * copied configuration cannot silently revert to a trusted value. Because that appends a parameter
 * to a published data class, this class also re-declares the nine-parameter `copy` and the
 * nine-parameter constructor, which restores the `copy`/`copy$default` descriptors that callers
 * compiled before the trust boundary depend on.
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
     * Defaults to [SnippetExecutionTrust.TRUSTED_LOCAL] so configurations written before the trust
     * boundary existed keep their previous behaviour. [SnippetExecutionTrustPolicy] fails closed for
     * every other value, including values added by a future release.
     */
    val executionTrust: SnippetExecutionTrust = SnippetExecutionTrust.TRUSTED_LOCAL,
) {
    /**
     * Nine-parameter constructor retained so that callers compiled before the trust boundary keep
     * resolving, defaulting to [SnippetExecutionTrust.TRUSTED_LOCAL] as they did previously.
     */
    public constructor(
        minScore: Double,
        timeoutMultiplier: Double,
        baselineTimeoutMs: Long,
        higherOrderMutants: Boolean,
        includeExtreme: Boolean,
        maxMutants: Int?,
        targetLines: List<Int>?,
        enableCache: Boolean,
        extraClasspath: List<String>,
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
