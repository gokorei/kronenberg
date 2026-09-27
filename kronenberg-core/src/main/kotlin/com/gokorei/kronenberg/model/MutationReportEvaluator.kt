package com.gokorei.kronenberg.model

import kotlin.math.abs

private const val SCORE_PERCENT = 100.0
private const val SCORE_PRECISION = 10.0
private const val SCORE_EPSILON = 1e-9

/**
 * Every reason a [MutationReport] cannot be trusted or cannot pass the fail-closed gate.
 *
 * Violations fall into three groups:
 * - *Contradictions*: two serialized fields of the same report disagree, so the report cannot be
 *   reconciled with the results it claims to summarize. These are hard errors regardless of score.
 * - *Validity blockers*: the report is internally consistent but describes an audit that did not
 *   measure what a mutation score is supposed to measure (no mutants, compile errors, no scorable
 *   mutant, or a failed baseline pre-flight).
 * - *Pass blockers*: the audit measured something, but surviving mutants or timeouts remain.
 */
public enum class AuditViolation(
    public val description: String,
) {
    /** A summary counter is negative, so the report cannot describe any real audit. */
    NEGATIVE_COUNTER("a summary counter is negative"),

    /** `totalMutants` does not equal the sum of the per-status counters. */
    TOTAL_MUTANT_COUNT_MISMATCH("totalMutants does not match the sum of the status counters"),

    /** A non-empty `results` list does not reconcile with the per-status counters. */
    RESULT_COUNT_MISMATCH("the reported results do not match the status counters"),

    /** The serialized `mutationScore` contradicts the per-status counters. */
    SCORE_MISMATCH("the reported mutation score does not match the status counters"),

    /** A result carries [MutantStatus.BASELINE_ERROR] but the report carries no `baselineError` message. */
    BASELINE_ERROR_RESULT_WITHOUT_MESSAGE("a baseline error result has no baseline error message"),

    /** The audit reported no mutants at all, so no score is meaningful. */
    NO_MUTANTS("the audit produced no mutants"),

    /** Some mutants failed to compile, so the measured score hides unmeasured mutants. */
    COMPILE_ERRORS_PRESENT("some mutants failed to compile"),

    /** No mutant reached a scorable outcome (killed, survived, or timed out). */
    NO_EFFECTIVE_MUTANTS("no mutant produced a scorable outcome"),

    /** The baseline pre-flight failed, so the audit is incomplete. */
    BASELINE_ERROR_PRESENT("the baseline pre-flight failed"),

    /** At least one mutant survived the test suite. */
    SURVIVING_MUTANTS_PRESENT("at least one mutant survived"),

    /** At least one mutant exceeded the calibrated execution timeout. */
    TIMEOUTS_PRESENT("at least one mutant timed out"),
    ;

    /** True when this violation means the report contradicts itself. */
    public val isContradiction: Boolean
        get() = this in CONTRADICTIONS

    /** True when this violation alone makes the report structurally untrustworthy. */
    public val invalidatesReport: Boolean
        get() = this in INVALIDATORS

    /** True when this violation alone prevents the audit from passing. */
    public val blocksPass: Boolean
        get() = this in INVALIDATORS || this == SURVIVING_MUTANTS_PRESENT || this == TIMEOUTS_PRESENT

    private companion object {
        private val CONTRADICTIONS =
            setOf(
                NEGATIVE_COUNTER,
                TOTAL_MUTANT_COUNT_MISMATCH,
                RESULT_COUNT_MISMATCH,
                SCORE_MISMATCH,
                BASELINE_ERROR_RESULT_WITHOUT_MESSAGE,
            )

        private val INVALIDATORS =
            CONTRADICTIONS +
                setOf(
                    NO_MUTANTS,
                    COMPILE_ERRORS_PRESENT,
                    NO_EFFECTIVE_MUTANTS,
                    BASELINE_ERROR_PRESENT,
                )
    }
}

/**
 * Reconciled verdict for a [MutationReport]: the recomputed score plus every audit violation.
 */
public data class MutationReportEvaluation(
    val mutationScore: Double,
    val effectiveMutantCount: Int,
    val isValid: Boolean,
    val isPassed: Boolean,
    val violations: List<AuditViolation> = emptyList(),
) {
    /** True when two serialized fields of the report disagree with each other. */
    public val isContradictory: Boolean
        get() = violations.any { it.isContradiction }

    public fun passes(minScore: Double): Boolean = isPassed && mutationScore >= minScore

    /** Comma separated violation descriptions, or `null` when the report has no violations. */
    public fun describe(): String? = violations.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.description }
}

/**
 * The single fail-closed policy for Kronenberg mutation audits.
 *
 * Every entry point (pipeline, CLI, Gradle plugin) scores, reconciles and gates through this
 * object so that a report cannot be judged by one rule in one module and another rule elsewhere.
 */
public object MutationReportEvaluator {
    /**
     * Reconciles [report] against its own results and returns the recomputed score and violations.
     *
     * Reconciliation is intentionally one-directional: a report that carries results must have
     * counters matching them, but a summary-only report with counters and no results is allowed
     * because aggregated and summarized reports legitimately omit per-mutant detail.
     */
    public fun evaluate(report: MutationReport): MutationReportEvaluation {
        val effectiveMutantCount = report.killedCount + report.survivedCount + report.timeoutCount
        val statusCount = effectiveMutantCount + report.compileErrorCount
        val mutationScore = calculateScore(report.killedCount, effectiveMutantCount)

        val violations =
            buildList {
                val counters =
                    listOf(
                        report.totalMutants,
                        report.killedCount,
                        report.survivedCount,
                        report.timeoutCount,
                        report.compileErrorCount,
                    )
                if (counters.any { it < 0 }) {
                    add(AuditViolation.NEGATIVE_COUNTER)
                }
                if (report.totalMutants != statusCount) {
                    add(AuditViolation.TOTAL_MUTANT_COUNT_MISMATCH)
                }
                if (report.results.isNotEmpty() && report.results.size != statusCount) {
                    add(AuditViolation.RESULT_COUNT_MISMATCH)
                }
                if (abs(report.mutationScore - mutationScore) > SCORE_EPSILON) {
                    add(AuditViolation.SCORE_MISMATCH)
                }
                if (report.results.any { it.status == MutantStatus.BASELINE_ERROR } && report.baselineError == null) {
                    add(AuditViolation.BASELINE_ERROR_RESULT_WITHOUT_MESSAGE)
                }
                if (report.baselineError != null) {
                    add(AuditViolation.BASELINE_ERROR_PRESENT)
                }
                if (report.totalMutants <= 0) {
                    add(AuditViolation.NO_MUTANTS)
                }
                if (report.compileErrorCount > 0) {
                    add(AuditViolation.COMPILE_ERRORS_PRESENT)
                }
                if (effectiveMutantCount <= 0) {
                    add(AuditViolation.NO_EFFECTIVE_MUTANTS)
                }
                if (report.survivedCount > 0) {
                    add(AuditViolation.SURVIVING_MUTANTS_PRESENT)
                }
                if (report.timeoutCount > 0) {
                    add(AuditViolation.TIMEOUTS_PRESENT)
                }
            }

        val isValid = violations.none { it.invalidatesReport }
        val isPassed = violations.none { it.blocksPass }

        return MutationReportEvaluation(
            mutationScore = mutationScore,
            effectiveMutantCount = effectiveMutantCount,
            isValid = isValid,
            isPassed = isPassed,
            violations = violations,
        )
    }

    public fun passes(
        report: MutationReport,
        minScore: Double,
    ): Boolean = evaluate(report).passes(minScore)

    /**
     * Builds a report whose counters and score are derived from [results], so the result is always
     * self-consistent and never trips the reconciliation rules above.
     */
    public fun fromResults(
        results: List<MutantResult>,
        totalMutants: Int? = null,
        baselineError: String? = null,
    ): MutationReport {
        val killedCount = results.count { it.status == MutantStatus.KILLED }
        val survivedCount = results.count { it.status == MutantStatus.SURVIVED }
        val timeoutCount = results.count { it.status == MutantStatus.TIMED_OUT }
        val compileErrorCount = results.count { it.status == MutantStatus.COMPILE_ERROR }
        val effectiveMutantCount = killedCount + survivedCount + timeoutCount
        val mutationScore = calculateScore(killedCount, effectiveMutantCount)

        return MutationReport(
            totalMutants = totalMutants ?: results.size,
            killedCount = killedCount,
            survivedCount = survivedCount,
            timeoutCount = timeoutCount,
            compileErrorCount = compileErrorCount,
            mutationScore = mutationScore,
            results = results,
            baselineError = baselineError,
        )
    }

    /**
     * Aggregates per-file reports into a single fail-closed report, assuming every input file was
     * audited successfully.
     */
    public fun aggregate(reports: List<MutationReport>): MutationReport =
        aggregate(reports, AuditCoverage(auditedSourceCount = reports.size))

    /**
     * Aggregates per-file reports and folds [coverage] gaps into the report's baseline error.
     *
     * This is the only place that decides whether a partial audit is acceptable. Unchanged files
     * recorded in [AuditCoverage.unchangedSourceCount] are informational and never fail the audit;
     * missing tests and audits that covered nothing do.
     */
    public fun aggregate(
        reports: List<MutationReport>,
        coverage: AuditCoverage,
    ): MutationReport {
        val merged =
            fromResults(
                results = reports.flatMap { it.results },
                totalMutants = reports.sumOf { it.totalMutants },
                baselineError = reports.firstNotNullOfOrNull { it.baselineError },
            )
        val incompleteness = coverage.incompleteAuditReason
        return if (incompleteness == null) {
            merged
        } else {
            merged.copy(baselineError = merged.baselineError ?: incompleteness)
        }
    }

    private fun calculateScore(
        killedCount: Int,
        effectiveMutantCount: Int,
    ): Double =
        if (effectiveMutantCount > 0) {
            ((killedCount.toDouble() / effectiveMutantCount.toDouble()) * SCORE_PERCENT * SCORE_PRECISION).toInt() /
                SCORE_PRECISION
        } else {
            0.0
        }
}
