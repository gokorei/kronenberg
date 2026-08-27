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
)
