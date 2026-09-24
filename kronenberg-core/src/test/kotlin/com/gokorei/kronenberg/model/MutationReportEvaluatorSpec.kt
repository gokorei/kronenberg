package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MutationReportEvaluatorSpec {
    @Test
    fun `applies fail-closed mutation report policy to table-driven scenarios`() {
        val scenarios =
            listOf(
                Scenario("no mutants", report(), expectedScore = 0.0, expectedPassed = false),
                Scenario("all compile errors", report(compileErrors = 3), expectedScore = 0.0, expectedPassed = false),
                Scenario("all timeouts", report(timeouts = 2), expectedScore = 0.0, expectedPassed = false),
                Scenario(
                    "mixed statuses",
                    report(killed = 1, survived = 1, timeouts = 1, compileErrors = 1),
                    expectedScore = 33.3,
                    expectedPassed = false,
                ),
                Scenario("all killed", report(killed = 1), expectedScore = 100.0, expectedPassed = true),
                Scenario(
                    "compile error with killed mutant",
                    report(killed = 1, compileErrors = 1),
                    expectedScore = 100.0,
                    expectedPassed = false,
                ),
            )

        scenarios.forEach { scenario ->
            val evaluation = MutationReportEvaluator.evaluate(scenario.report)

            evaluation.mutationScore shouldBe scenario.expectedScore
            evaluation.isPassed shouldBe scenario.expectedPassed
            scenario.report.isPassed shouldBe scenario.expectedPassed
        }
    }

    @Test
    fun `threshold zero never bypasses invalid report policy`() {
        val invalidReports =
            listOf(
                report(),
                report(compileErrors = 1),
                report(timeouts = 1),
                report(killed = 1, compileErrors = 1),
                report(killed = 1, survived = 1),
            )

        invalidReports.forEach { report ->
            MutationReportEvaluator.passes(report, minScore = 0.0) shouldBe false
        }

        MutationReportEvaluator.passes(report(killed = 1), minScore = 0.0) shouldBe true
    }

    @Test
    fun `all baseline failure batch preserves every per-file diagnostic and fails closed`() {
        val outcomes =
            listOf(
                AuditFileOutcome.audited(
                    "Compile.kt",
                    "CompileTest.kt",
                    report(baselineError = "Baseline compilation failed: unresolved reference"),
                ),
                AuditFileOutcome.audited(
                    "Safety.kt",
                    "SafetyTest.kt",
                    report(baselineError = "Code contains forbidden host-terminating calls"),
                ),
                AuditFileOutcome.audited(
                    "Execution.kt",
                    "ExecutionTest.kt",
                    report(baselineError = "Baseline test failed before mutation: assertion failed"),
                ),
                AuditFileOutcome.audited(
                    "Timeout.kt",
                    "TimeoutTest.kt",
                    report(baselineError = "Baseline test execution timed out after 2000ms"),
                ),
            )

        val aggregate = MutationReportEvaluator.aggregate(outcomes)

        aggregate.fileDiagnostics.map { it.sourceFile } shouldBe outcomes.map { it.sourceFile }
        aggregate.fileDiagnostics.map { it.status }.distinct() shouldBe listOf(AuditFileStatus.BASELINE_ERROR)
        outcomes.forEach { outcome ->
            aggregate.baselineError.orEmpty() shouldContain "${outcome.sourceFile}: ${outcome.report!!.baselineError}"
        }
        MutationReportEvaluator.passes(aggregate, minScore = 0.0) shouldBe false
    }

    @Test
    fun `mixed batch preserves mapping diagnostics and fails closed`() {
        val outcomes =
            listOf(
                AuditFileOutcome.audited("Valid.kt", "ValidTest.kt", report(killed = 1)),
                AuditFileOutcome.skipped(
                    "Missing.kt",
                    status = AuditFileStatus.MISSING_TEST,
                    diagnostic = "No matching test file found",
                ),
                AuditFileOutcome.skipped(
                    "Ambiguous.kt",
                    testFile = "AmbiguousTest.kt, AmbiguousSpec.kt",
                    status = AuditFileStatus.AMBIGUOUS_TEST,
                    diagnostic = "Multiple matching test files found",
                ),
            )

        val aggregate = MutationReportEvaluator.aggregate(outcomes)

        aggregate.totalMutants shouldBe 1
        aggregate.fileDiagnostics shouldBe
            listOf(
                AuditFileDiagnostic("Valid.kt", "ValidTest.kt", AuditFileStatus.AUDITED, null),
                AuditFileDiagnostic("Missing.kt", null, AuditFileStatus.MISSING_TEST, "No matching test file found"),
                AuditFileDiagnostic(
                    "Ambiguous.kt",
                    "AmbiguousTest.kt, AmbiguousSpec.kt",
                    AuditFileStatus.AMBIGUOUS_TEST,
                    "Multiple matching test files found",
                ),
            )
        MutationReportEvaluator.passes(aggregate, minScore = 0.0) shouldBe false
    }

    private data class Scenario(
        val name: String,
        val report: MutationReport,
        val expectedScore: Double,
        val expectedPassed: Boolean,
    )

    private fun report(
        killed: Int = 0,
        survived: Int = 0,
        timeouts: Int = 0,
        compileErrors: Int = 0,
        baselineError: String? = null,
    ): MutationReport =
        MutationReport(
            totalMutants = killed + survived + timeouts + compileErrors,
            killedCount = killed,
            survivedCount = survived,
            timeoutCount = timeouts,
            compileErrorCount = compileErrors,
            mutationScore = 100.0,
            baselineError = baselineError,
        )
}
