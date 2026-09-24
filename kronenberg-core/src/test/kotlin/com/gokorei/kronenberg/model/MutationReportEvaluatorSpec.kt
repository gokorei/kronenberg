package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
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
    ): MutationReport =
        MutationReport(
            totalMutants = killed + survived + timeouts + compileErrors,
            killedCount = killed,
            survivedCount = survived,
            timeoutCount = timeouts,
            compileErrorCount = compileErrors,
            mutationScore = 100.0,
        )
}
