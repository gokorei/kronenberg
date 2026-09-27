package com.gokorei.kronenberg.report

import com.gokorei.kronenberg.model.AuditOutcome
import com.gokorei.kronenberg.model.AuditSkipReason
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders mutation audits as JUnit XML so CI dashboards surface failures, errors and gaps.
 *
 * This is the single JUnit XML implementation shared by the CLI and the Gradle plugin. Beyond the
 * per-mutant test cases it makes incomplete audits visible:
 * - a report level [com.gokorei.kronenberg.model.MutationReport.baselineError] becomes an extra
 *   `<error type="BaselineError">` test case and increments the `errors` attribute, so an audit
 *   that verified nothing can never render as a green suite;
 * - files skipped for a coverage reason become `<skipped/>` test cases and increment the
 *   `skipped` attribute, so unchanged files are visibly distinct from missing tests.
 */
public object JUnitXmlReportWriter {
    /** Default JUnit suite name used by the CLI and the Gradle plugin. */
    public const val DEFAULT_SUITE_NAME: String = "Kronenberg Mutation Audit"

    private const val MUTANT_PACKAGE = "com.gokorei.kronenberg.mutant"
    private const val AUDIT_PACKAGE = "com.gokorei.kronenberg.audit"
    private const val BASELINE_ERROR_TEST_NAME = "audit_baseline"
    private const val MILLIS_PER_SECOND = 1000.0
    private const val ID_PREFIX_LENGTH = 8

    /**
     * Renders [outcome] as a JUnit XML document.
     */
    public fun render(
        outcome: AuditOutcome,
        testSuiteName: String = DEFAULT_SUITE_NAME,
    ): String {
        val report = outcome.report
        val coverage = outcome.coverage
        val baselineError = report.baselineError
        val skipped = AuditSkipReason.entries.filter { coverage.skipped(it) > 0 }

        val failures = report.survivedCount + report.timeoutCount
        val errors = report.compileErrorCount + if (baselineError != null) 1 else 0
        // One test case per skip reason, not per skipped file; the file count is in the message.
        val skippedCount = skipped.size
        val tests = report.results.size + skippedCount + if (baselineError != null) 1 else 0
        val totalTimeSeconds = report.results.sumOf { it.executionTimeMs } / MILLIS_PER_SECOND

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine(
                "<testsuite name=\"$testSuiteName\" tests=\"$tests\" failures=\"$failures\" " +
                    "errors=\"$errors\" skipped=\"$skippedCount\" time=\"$totalTimeSeconds\">",
            )
            report.results.forEach { result -> appendResultTestCase(result) }
            if (baselineError != null) {
                appendBaselineTestCase(baselineError)
            }
            skipped.forEach { reason -> appendSkippedTestCase(reason, coverage.skipped(reason)) }
            appendLine("</testsuite>")
        }
    }

    /**
     * Renders [outcome] as JUnit XML and writes it to [targetFile], creating parent directories.
     */
    public fun write(
        outcome: AuditOutcome,
        targetFile: Path,
        testSuiteName: String = DEFAULT_SUITE_NAME,
    ) {
        val content = render(outcome, testSuiteName)
        targetFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(targetFile, content)
    }

    private fun StringBuilder.appendResultTestCase(result: MutantResult) {
        val mutant = result.mutant
        val durationSec = result.executionTimeMs / MILLIS_PER_SECOND
        val className = "$MUTANT_PACKAGE.${mutant.category.name.lowercase()}"
        val testName = "${mutant.mutatorName}_line${mutant.line}_col${mutant.column}_${mutant.id.take(ID_PREFIX_LENGTH)}"

        appendLine("    <testcase classname=\"$className\" name=\"$testName\" time=\"$durationSec\">")
        when (result.status) {
            MutantStatus.SURVIVED -> {
                val msg = escapeXml("Mutant survived: replaced '${mutant.originalText}' with '${mutant.replacementText}'")
                val body =
                    escapeXml(
                        "Mutant ID: ${mutant.id}\nMutator: ${mutant.mutatorName}\nLocation: line ${mutant.line}, " +
                            "column ${mutant.column}\nOriginal:\n${mutant.originalText}\nMutated:\n${mutant.replacementText}",
                    )
                appendLine("        <failure message=\"$msg\" type=\"MutationSurvived\">$body</failure>")
            }

            MutantStatus.TIMED_OUT -> {
                val msg = escapeXml(result.failureMessage ?: "Mutant execution timed out")
                appendLine("        <failure message=\"$msg\" type=\"Timeout\">$msg</failure>")
            }

            MutantStatus.COMPILE_ERROR -> {
                val msg = escapeXml(result.failureMessage ?: "Mutant failed to compile")
                appendLine("        <error message=\"$msg\" type=\"CompileError\">$msg</error>")
            }

            MutantStatus.BASELINE_ERROR -> {
                val msg = escapeXml(result.failureMessage ?: "Baseline execution failed before mutation")
                appendLine("        <error message=\"$msg\" type=\"BaselineError\">$msg</error>")
            }

            MutantStatus.KILLED -> {
                // Passing test case
            }
        }
        appendLine("    </testcase>")
    }

    private fun StringBuilder.appendBaselineTestCase(baselineError: String) {
        val message = escapeXml(baselineError)
        appendLine("    <testcase classname=\"$AUDIT_PACKAGE\" name=\"$BASELINE_ERROR_TEST_NAME\" time=\"0.0\">")
        appendLine("        <error message=\"$message\" type=\"BaselineError\">$message</error>")
        appendLine("    </testcase>")
    }

    private fun StringBuilder.appendSkippedTestCase(
        reason: AuditSkipReason,
        count: Int,
    ) {
        val message = escapeXml(skipMessage(reason, count))
        appendLine("    <testcase classname=\"$AUDIT_PACKAGE\" name=\"skipped_${reason.name.lowercase()}\" time=\"0.0\">")
        appendLine("        <skipped message=\"$message\"/>")
        appendLine("    </testcase>")
    }

    private fun skipMessage(
        reason: AuditSkipReason,
        count: Int,
    ): String =
        when (reason) {
            AuditSkipReason.UNCHANGED -> "$count source file(s) had no changed lines in the requested diff window"
            AuditSkipReason.MISSING_TEST -> "$count source file(s) had no matching test suite"
            AuditSkipReason.NO_MUTATION_OPPORTUNITY -> "$count source file(s) had no applicable mutation site"
        }

    private fun escapeXml(str: String): String =
        str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
