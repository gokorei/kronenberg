package com.gokorei.kronenberg.model

private const val SCORE_PERCENT = 100.0
private const val SCORE_PRECISION = 10.0

public data class MutationReportEvaluation(
    val mutationScore: Double,
    val effectiveMutantCount: Int,
    val isValid: Boolean,
    val isPassed: Boolean,
) {
    public fun passes(minScore: Double): Boolean = isPassed && mutationScore >= minScore
}

public object MutationReportEvaluator {
    public fun evaluate(report: MutationReport): MutationReportEvaluation {
        val effectiveMutantCount = report.killedCount + report.survivedCount + report.timeoutCount
        val statusCount =
            report.killedCount +
                report.survivedCount +
                report.timeoutCount +
                report.compileErrorCount
        val isValid =
            report.baselineError == null &&
                report.fileDiagnostics.all { it.status == AuditFileStatus.AUDITED } &&
                report.totalMutants > 0 &&
                report.totalMutants == statusCount &&
                effectiveMutantCount > 0 &&
                report.compileErrorCount == 0
        val mutationScore = calculateScore(report.killedCount, effectiveMutantCount)
        val isPassed = isValid && report.survivedCount == 0 && report.timeoutCount == 0

        return MutationReportEvaluation(
            mutationScore = mutationScore,
            effectiveMutantCount = effectiveMutantCount,
            isValid = isValid,
            isPassed = isPassed,
        )
    }

    public fun passes(
        report: MutationReport,
        minScore: Double,
    ): Boolean = evaluate(report).passes(minScore)

    public fun fromResults(
        results: List<MutantResult>,
        totalMutants: Int? = null,
        baselineError: String? = null,
        sourceFilePath: String? = null,
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
            sourceFilePath = sourceFilePath,
        )
    }

    public fun aggregate(reports: List<MutationReport>): MutationReport {
        val outcomes =
            reports.map { report ->
                AuditFileOutcome.audited(
                    sourceFile = report.sourceFilePath ?: "unknown",
                    report = report,
                )
            }
        return aggregate(outcomes)
    }

    public fun aggregate(outcomes: Collection<AuditFileOutcome>): MutationReport {
        val reports = outcomes.mapNotNull { it.report }
        val diagnostics =
            outcomes.map { outcome ->
                val status =
                    when {
                        outcome.report?.baselineError != null -> AuditFileStatus.BASELINE_ERROR
                        outcome.report != null -> AuditFileStatus.AUDITED
                        else -> outcome.status
                    }
                val diagnostic =
                    when {
                        outcome.report?.baselineError != null -> outcome.report.baselineError
                        outcome.report == null -> outcome.diagnostic
                        else -> null
                    }
                AuditFileDiagnostic(
                    sourceFile = outcome.sourceFile,
                    testFile = outcome.testFile,
                    status = status,
                    diagnostic = diagnostic,
                )
            }
        val baselineError =
            if (outcomes.isEmpty()) {
                "No source files were available to audit"
            } else {
                diagnostics
                    .filter { it.status != AuditFileStatus.AUDITED }
                    .joinToString(separator = "\n") { diagnostic ->
                        val message = diagnostic.diagnostic ?: diagnostic.status.name
                        "${diagnostic.sourceFile}: $message"
                    }.ifEmpty { null }
            }

        return fromResults(
            results = reports.flatMap { it.results },
            totalMutants = reports.sumOf { it.totalMutants },
            baselineError = baselineError,
        ).copy(fileDiagnostics = diagnostics)
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
