package com.gokorei.kronenberg.report

import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.AuditCoverage
import com.gokorei.kronenberg.model.AuditOutcome
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationReportEvaluator
import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JUnitXmlReportWriterSpec {
    @Test
    fun `clean audit renders only mutant test cases`() {
        val xml = JUnitXmlReportWriter.render(outcome(report(MutantStatus.KILLED), AuditCoverage(auditedSourceCount = 1)))

        xml shouldContain "<testsuite name=\"Kronenberg Mutation Audit\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\""
        xml shouldContain "classname=\"com.gokorei.kronenberg.mutant.arithmetic_operator\""
        xml shouldContain "name=\"ArithmeticOperator_line1_col1_mutant-0\""
        xml shouldNotContain "<error"
        xml shouldNotContain "<skipped"
    }

    @Test
    fun `aggregate baseline errors are represented as junit errors`() {
        val report = MutationReportEvaluator.fromResults(emptyList(), baselineError = "Baseline compilation failed: <boom>")
        val xml = JUnitXmlReportWriter.render(outcome(report, AuditCoverage()))

        xml shouldContain "tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\""
        xml shouldContain "classname=\"com.gokorei.kronenberg.audit\" name=\"audit_baseline\""
        xml shouldContain "type=\"BaselineError\""
        xml shouldContain "Baseline compilation failed: &lt;boom&gt;"
    }

    @Test
    fun `aggregate baseline errors stack on top of compile errors`() {
        val report =
            MutationReportEvaluator
                .fromResults(result(MutantStatus.COMPILE_ERROR))
                .copy(baselineError = "2 source file(s) were not audited because no matching test was found")
        val xml = JUnitXmlReportWriter.render(outcome(report, AuditCoverage(auditedSourceCount = 1, missingTestSourceCount = 2)))

        xml shouldContain "tests=\"3\" failures=\"0\" errors=\"2\" skipped=\"1\""
        xml shouldContain "no matching test was found"
    }

    @Test
    fun `coverage skips render as skipped test cases and never as errors`() {
        val coverage =
            AuditCoverage(
                auditedSourceCount = 1,
                unchangedSourceCount = 48,
                noMutationOpportunitySourceCount = 2,
            )
        val xml = JUnitXmlReportWriter.render(outcome(report(MutantStatus.KILLED), coverage))

        xml shouldContain "tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"2\""
        xml shouldContain "name=\"skipped_unchanged\""
        xml shouldContain "48 source file(s) had no changed lines in the requested diff window"
        xml shouldContain "name=\"skipped_no_mutation_opportunity\""
        xml shouldContain "2 source file(s) had no applicable mutation site"
        xml shouldNotContain "name=\"skipped_missing_test\""
        xml shouldContain "<skipped message="
        xml shouldNotContain "type=\"BaselineError\""
    }

    @Test
    fun `surviving and timed out mutants are counted as failures`() {
        val report =
            MutationReportEvaluator.fromResults(
                result(MutantStatus.SURVIVED, 0) + result(MutantStatus.TIMED_OUT, 1) + result(MutantStatus.KILLED, 2),
            )
        val xml = JUnitXmlReportWriter.render(outcome(report, AuditCoverage(auditedSourceCount = 1)))

        xml shouldContain "tests=\"3\" failures=\"2\" errors=\"0\""
        xml shouldContain "type=\"MutationSurvived\""
        xml shouldContain "type=\"Timeout\""
    }

    @Test
    fun `custom suite names are honoured and output is written to disk`() {
        val target = Files.createTempDirectory("kronenberg-junit").resolve("nested/mutation-results.xml")
        JUnitXmlReportWriter.write(
            outcome(report(MutantStatus.KILLED), AuditCoverage(auditedSourceCount = 1)),
            target,
            testSuiteName = "Custom Suite",
        )

        val written = Files.readString(target)
        written shouldContain "<testsuite name=\"Custom Suite\""
        written shouldContain "</testsuite>"
        target.toFile().deleteRecursively()
    }

    @Test
    fun `empty report still renders a well formed suite`() {
        val xml = JUnitXmlReportWriter.render(AuditOutcome(MutationReportEvaluator.fromResults(emptyList())))

        xml shouldContain "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml shouldContain "tests=\"0\""
        xml shouldContain "</testsuite>"
    }

    private fun outcome(
        report: com.gokorei.kronenberg.model.MutationReport,
        coverage: AuditCoverage,
    ) = AuditOutcome(report, coverage)

    private fun report(status: MutantStatus): com.gokorei.kronenberg.model.MutationReport =
        MutationReportEvaluator.fromResults(result(status))

    private fun result(
        status: MutantStatus,
        index: Int = 0,
    ): List<MutantResult> =
        listOf(
            MutantResult(
                mutant =
                    AstMutant(
                        id = "mutant-$index-0123456789",
                        mutatorName = "ArithmeticOperator",
                        category = MutatorCategory.ARITHMETIC_OPERATOR,
                        line = index + 1,
                        column = 1,
                        originalText = "a + b",
                        replacementText = "a - b",
                        mutatedSource = "fun f() = a - b",
                    ),
                status = status,
                executionTimeMs = 12L,
                failureMessage = if (status == MutantStatus.COMPILE_ERROR) "unresolved reference: b" else null,
            ),
        )
}
