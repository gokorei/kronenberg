package com.gokorei.kronenberg.model

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
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
    fun `rejects contradictory serialized reports in a table-driven manner`() {
        val scenarios =
            listOf(
                Contradiction(
                    name = "results outnumber the killed counter",
                    report = report(killed = 1).copy(results = results(MutantStatus.KILLED, MutantStatus.KILLED)),
                    expected = AuditViolation.RESULT_COUNT_MISMATCH,
                ),
                Contradiction(
                    name = "results undercount the status counters",
                    report = report(killed = 2).copy(results = results(MutantStatus.KILLED)),
                    expected = AuditViolation.RESULT_COUNT_MISMATCH,
                ),
                Contradiction(
                    name = "results present while every counter is zero",
                    report = report().copy(results = results(MutantStatus.KILLED)),
                    expected = AuditViolation.RESULT_COUNT_MISMATCH,
                ),
                Contradiction(
                    name = "total mutants inflated beyond the reported results",
                    report = report(killed = 1).copy(totalMutants = 2, results = results(MutantStatus.KILLED, MutantStatus.KILLED)),
                    expected = AuditViolation.TOTAL_MUTANT_COUNT_MISMATCH,
                ),
                Contradiction(
                    name = "total mutants excludes a reported compile error",
                    report = report(killed = 1, compileErrors = 1).copy(totalMutants = 1),
                    expected = AuditViolation.TOTAL_MUTANT_COUNT_MISMATCH,
                ),
                Contradiction(
                    name = "declared score contradicts the counters",
                    report = report(killed = 1).copy(mutationScore = 42.0),
                    expected = AuditViolation.SCORE_MISMATCH,
                ),
                Contradiction(
                    name = "baseline error result without a baseline error message",
                    report = report().copy(results = results(MutantStatus.BASELINE_ERROR)),
                    expected = AuditViolation.BASELINE_ERROR_RESULT_WITHOUT_MESSAGE,
                ),
                Contradiction(
                    name = "negative survived counter",
                    report = report(killed = 1).copy(survivedCount = -1),
                    expected = AuditViolation.NEGATIVE_COUNTER,
                ),
            )

        scenarios.forEach { scenario ->
            val evaluation = MutationReportEvaluator.evaluate(scenario.report)

            evaluation.isContradictory shouldBe true
            evaluation.isValid shouldBe false
            evaluation.isPassed shouldBe false
            MutationReportEvaluator.passes(scenario.report, minScore = 0.0) shouldBe false
            evaluation.violations shouldContain scenario.expected
            evaluation.describe().shouldNotBeNull() shouldContain scenario.expected.description
        }
    }

    @Test
    fun `reconciles non-empty results with the summary counters`() {
        val consistent =
            listOf(
                report(killed = 2).copy(results = results(MutantStatus.KILLED, MutantStatus.KILLED)),
                report(killed = 1, survived = 1).copy(results = results(MutantStatus.KILLED, MutantStatus.SURVIVED)),
                report(compileErrors = 1).copy(results = results(MutantStatus.COMPILE_ERROR)),
            )

        consistent.forEach { report ->
            val evaluation = MutationReportEvaluator.evaluate(report)

            evaluation.isContradictory shouldBe false
            evaluation.violations shouldNotContain AuditViolation.RESULT_COUNT_MISMATCH
            evaluation.violations shouldNotContain AuditViolation.TOTAL_MUTANT_COUNT_MISMATCH
            evaluation.violations shouldNotContain AuditViolation.SCORE_MISMATCH
        }

        // Counter totals that are internally consistent but unmeasured still fail closed.
        MutationReportEvaluator.evaluate(consistent[2]).isValid shouldBe false
        MutationReportEvaluator.evaluate(consistent[1]).isPassed shouldBe false
        MutationReportEvaluator.evaluate(consistent[0]).isPassed shouldBe true
    }

    @Test
    fun `fromResults always produces a report that reconciles with itself`() {
        val report =
            MutationReportEvaluator.fromResults(
                results = results(MutantStatus.KILLED, MutantStatus.SURVIVED, MutantStatus.COMPILE_ERROR),
            )

        report.totalMutants shouldBe 3
        report.mutationScore shouldBe 50.0
        MutationReportEvaluator.evaluate(report).isContradictory shouldBe false
    }

    @Test
    fun `unchanged and no-opportunity skips never fail a multi-file audit`() {
        val audited = MutationReportEvaluator.fromResults(results(MutantStatus.KILLED))
        val coverage =
            AuditCoverage(
                auditedSourceCount = 1,
                unchangedSourceCount = 48,
                noMutationOpportunitySourceCount = 2,
            )

        val aggregate = MutationReportEvaluator.aggregate(listOf(audited), coverage)

        aggregate.baselineError shouldBe null
        coverage.isIncomplete shouldBe false
        aggregate.isPassed shouldBe true
        MutationReportEvaluator.passes(aggregate, minScore = 100.0) shouldBe true
    }

    @Test
    fun `missing tests and empty audits fail through the same centralized policy`() {
        val audited = MutationReportEvaluator.fromResults(results(MutantStatus.KILLED))

        val missingTest =
            MutationReportEvaluator.aggregate(
                listOf(audited),
                AuditCoverage(auditedSourceCount = 1, missingTestSourceCount = 3),
            )
        missingTest.baselineError shouldBe
            "3 source file(s) were not audited because no matching test was found"
        MutationReportEvaluator.passes(missingTest, minScore = 0.0) shouldBe false
        MutationReportEvaluator.evaluate(missingTest).violations shouldContain AuditViolation.BASELINE_ERROR_PRESENT

        val nothingAudited =
            MutationReportEvaluator.aggregate(
                listOf(audited),
                AuditCoverage(unchangedSourceCount = 10),
            )
        nothingAudited.baselineError.shouldNotBeNull() shouldContain "No source file could be audited"
        MutationReportEvaluator.passes(nothingAudited, minScore = 0.0) shouldBe false

        MutationReportEvaluator.aggregate(emptyList()).baselineError shouldBe
            "No source/test pairs were available to audit"
    }

    @Test
    fun `aggregate preserves a per-file baseline error and reconciles the merged counters`() {
        val failed = MutationReportEvaluator.fromResults(emptyList(), baselineError = "Baseline compilation failed")
        val passed = MutationReportEvaluator.fromResults(results(MutantStatus.KILLED))

        val aggregate = MutationReportEvaluator.aggregate(listOf(passed, failed), AuditCoverage(auditedSourceCount = 2))

        aggregate.baselineError shouldBe "Baseline compilation failed"
        aggregate.totalMutants shouldBe 1
        aggregate.killedCount shouldBe 1
        MutationReportEvaluator.evaluate(aggregate).isContradictory shouldBe false
        MutationReportEvaluator.passes(aggregate, minScore = 0.0) shouldBe false
    }

    @Test
    fun `single argument aggregate assumes every file was audited`() {
        val reports = listOf(report(killed = 1), report(killed = 1))

        MutationReportEvaluator.aggregate(reports).baselineError shouldBe null
        MutationReportEvaluator.aggregate(emptyList()).baselineError shouldBe
            "No source/test pairs were available to audit"
    }

    @Test
    fun `coverage exposes per-reason skip counts and a readable summary`() {
        val coverage =
            AuditCoverage(
                auditedSourceCount = 2,
                unchangedSourceCount = 5,
                missingTestSourceCount = 1,
                noMutationOpportunitySourceCount = 3,
            )

        coverage.totalSourceCount shouldBe 11
        coverage.skipped(AuditSkipReason.UNCHANGED) shouldBe 5
        coverage.skipped(AuditSkipReason.MISSING_TEST) shouldBe 1
        coverage.skipped(AuditSkipReason.NO_MUTATION_OPPORTUNITY) shouldBe 3
        coverage.isIncomplete shouldBe true
        coverage.describe() shouldBe
            "audited 2 of 11 source file(s); unchanged 5, missing test 1, no mutation opportunity 3"
    }

    private data class Scenario(
        val name: String,
        val report: MutationReport,
        val expectedScore: Double,
        val expectedPassed: Boolean,
    )

    private data class Contradiction(
        val name: String,
        val report: MutationReport,
        val expected: AuditViolation,
    )

    private fun results(vararg statuses: MutantStatus): List<MutantResult> =
        statuses.mapIndexed { index, status ->
            MutantResult(
                mutant =
                    AstMutant(
                        id = "mutant-$index",
                        mutatorName = "ArithmeticOperator",
                        category = MutatorCategory.ARITHMETIC_OPERATOR,
                        line = index + 1,
                        column = 1,
                        originalText = "a + b",
                        replacementText = "a - b",
                        mutatedSource = "fun f() = a - b",
                    ),
                status = status,
                executionTimeMs = 1L,
            )
        }

    private fun report(
        killed: Int = 0,
        survived: Int = 0,
        timeouts: Int = 0,
        compileErrors: Int = 0,
    ): MutationReport {
        val effective = killed + survived + timeouts
        val score = if (effective > 0) (killed * 1000.0 / effective).toInt() / 10.0 else 0.0
        return MutationReport(
            totalMutants = killed + survived + timeouts + compileErrors,
            killedCount = killed,
            survivedCount = survived,
            timeoutCount = timeouts,
            compileErrorCount = compileErrors,
            mutationScore = score,
        )
    }
}
