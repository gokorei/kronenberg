package com.gokorei.kronenberg.model

/**
 * Reason a source file contributed no mutant results to a batch audit.
 *
 * The distinction is load bearing for the fail-closed policy: an [UNCHANGED] file is the expected
 * outcome of an incremental `--diff`/`--staged` audit, whereas [MISSING_TEST] means the requested
 * coverage was silently absent and must fail the build.
 */
public enum class AuditSkipReason {
    /** The file has no lines inside the requested Git diff window, so there was nothing to audit. */
    UNCHANGED,

    /** The file was in scope but no matching test suite could be located. */
    MISSING_TEST,

    /** The file was audited but the mutator pipeline found no applicable AST mutation site. */
    NO_MUTATION_OPPORTUNITY,
}

/**
 * Tally of per-source-file dispositions collected while auditing one or more files.
 *
 * [unchangedSourceCount] and [noMutationOpportunitySourceCount] are informational: they record
 * files that legitimately produced nothing. [missingTestSourceCount] is a coverage gap, and an
 * audit that covered no file at all is an audit that proved nothing.
 */
public data class AuditCoverage(
    val auditedSourceCount: Int = 0,
    val unchangedSourceCount: Int = 0,
    val missingTestSourceCount: Int = 0,
    val noMutationOpportunitySourceCount: Int = 0,
) {
    /** Total number of source files considered by the audit. */
    public val totalSourceCount: Int
        get() = auditedSourceCount + unchangedSourceCount + missingTestSourceCount + noMutationOpportunitySourceCount

    /** Number of source files skipped for the given [reason]. */
    public fun skipped(reason: AuditSkipReason): Int =
        when (reason) {
            AuditSkipReason.UNCHANGED -> unchangedSourceCount
            AuditSkipReason.MISSING_TEST -> missingTestSourceCount
            AuditSkipReason.NO_MUTATION_OPPORTUNITY -> noMutationOpportunitySourceCount
        }

    /** True when the audit either proved nothing or left a requested file uncovered. */
    public val isIncomplete: Boolean
        get() = auditedSourceCount == 0 || missingTestSourceCount > 0

    /**
     * Human readable reason the audit is incomplete, or `null` when coverage is acceptable.
     *
     * Unchanged files are deliberately absent from this message: an incremental audit that skips
     * untouched files is the normal, healthy case.
     */
    public val incompleteAuditReason: String?
        get() =
            when {
                auditedSourceCount == 0 && totalSourceCount == 0 -> {
                    "No source/test pairs were available to audit"
                }

                auditedSourceCount == 0 -> {
                    "No source file could be audited: $unchangedSourceCount unchanged, " +
                        "$missingTestSourceCount without a matching test, " +
                        "$noMutationOpportunitySourceCount without a mutation opportunity"
                }

                missingTestSourceCount > 0 -> {
                    "$missingTestSourceCount source file(s) were not audited because no matching test was found"
                }

                else -> {
                    null
                }
            }

    /** Compact one-line summary describing how many files were audited and how many were skipped. */
    public fun describe(): String =
        "audited $auditedSourceCount of $totalSourceCount source file(s); " +
            "unchanged $unchangedSourceCount, missing test $missingTestSourceCount, " +
            "no mutation opportunity $noMutationOpportunitySourceCount"
}

/**
 * A mutation report paired with the coverage tally that produced it.
 */
public data class AuditOutcome(
    val report: MutationReport,
    val coverage: AuditCoverage = AuditCoverage(),
)
