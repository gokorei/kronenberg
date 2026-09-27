package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.gokorei.kronenberg.model.AuditCoverage
import com.gokorei.kronenberg.model.AuditOutcome
import com.gokorei.kronenberg.model.AuditSkipReason
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationReport
import com.gokorei.kronenberg.model.MutationReportEvaluator
import com.gokorei.kronenberg.report.JUnitXmlReportWriter
import com.gokorei.kronenberg.runner.DefaultMutationExecutionPipeline
import com.gokorei.kronenberg.runner.SurvivingMutantTestProposer
import com.gokorei.kronenberg.runner.TestStyle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

private val jsonSerializer =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

/**
 * Exporter converting Kronenberg mutation audits into standardized JUnit XML format.
 *
 * Delegates to the shared [JUnitXmlReportWriter] in core so the CLI and the Gradle plugin emit an
 * identical document, including aggregate baseline errors and per-reason coverage skips.
 */
public object JUnitXmlReportExporter {
    public fun export(
        outcome: AuditOutcome,
        targetFile: Path,
        testSuiteName: String = JUnitXmlReportWriter.DEFAULT_SUITE_NAME,
    ) {
        JUnitXmlReportWriter.write(outcome, targetFile, testSuiteName)
    }
}

public class KronenbergCli :
    CliktCommand(
        name = "kronenberg",
    ) {
    init {
        versionOption(
            version = Version.CURRENT,
            names = setOf("--version", "-v"),
            message = { "kronenberg version $it" },
        )
    }

    override fun run() {
        // Root dispatcher
    }
}

public class AuditCommand :
    CliktCommand(
        name = "audit",
    ) {
    private val source: Path? by option(
        "--source",
        "-s",
        help = "Path to single Kotlin source file to mutate",
    ).path(mustExist = true, canBeDir = false)

    private val test: Path? by option(
        "--test",
        "-t",
        help = "Path to single test file verifying the source code",
    ).path(mustExist = true, canBeDir = false)

    private val sourceDir: Path? by option(
        "--source-dir",
        help = "Directory containing Kotlin source files to scan",
    ).path(mustExist = true, canBeFile = false)

    private val testDir: Path? by option(
        "--test-dir",
        help = "Directory containing test suite files",
    ).path(mustExist = true, canBeFile = false)

    private val threshold: Double by option(
        "--threshold",
        help = "Minimum mutation score threshold percentage (0.0 - 100.0, default 80.0)",
    ).double().default(80.0)

    private val extreme: Boolean by option(
        "--extreme",
        help = "Include extreme/structural mutation operators (e.g. constant literals, conditions)",
    ).flag(default = false)

    private val hom: Boolean by option(
        "--hom",
        help = "Generate and evaluate Higher-Order Mutants (HOM)",
    ).flag(default = false)

    private val timeout: Long by option(
        "--timeout",
        help = "Baseline execution timeout in milliseconds (default 2000ms)",
    ).long().default(2000L)

    private val maxMutants: Int? by option(
        "--max-mutants",
        help = "Maximum number of mutants to evaluate",
    ).int()

    private val diff: String? by option(
        "--diff",
        help = "Git ref to diff against for incremental mutation testing (e.g. HEAD~1, origin/main)",
    )

    private val staged: Boolean by option(
        "--staged",
        help = "Only evaluate mutants on lines modified in staged Git changes",
    ).flag(default = false)

    private val preCommit: Boolean by option(
        "--pre-commit",
        help = "Fast staged audit mode designed for Git pre-commit hooks",
    ).flag(default = false)

    private val cache: Boolean by option(
        "--cache",
        help = "Enable deterministic mutant evaluation caching",
    ).flag(default = false)

    private val json: Boolean by option(
        "--json",
        help = "Output structured JSON report to stdout",
    ).flag(default = false)

    private val junitXml: Path? by option(
        "--junit-xml",
        help = "Export standardized JUnit XML mutation test results to target path",
    ).path(canBeDir = false)

    private val htmlReport: Path? by option(
        "--html-report",
        help = "Export interactive standalone HTML mutation report",
    ).path(canBeDir = false)

    private val sarif: Path? by option(
        "--sarif",
        help = "Export SARIF v2.1.0 code scanning results for GitHub PR review",
    ).path(canBeDir = false)

    private val codeclimate: Path? by option(
        "--codeclimate",
        help = "Export Code Climate issue JSON report for surviving mutants",
    ).path(canBeDir = false)

    private val proposeTests: Boolean by option(
        "--propose-tests",
        help = "Synthesize and display template test method skeletons to kill surviving mutants",
    ).flag(default = false)

    private val classpath: String? by option(
        "--classpath",
        "-cp",
        help = "Additional classpath entries (separated by colon, semicolon, or comma) for compilation and execution",
    )

    override fun run() {
        val effectiveStaged = staged || preCommit
        val effectiveTimeout = if (preCommit && timeout == 2000L) 500L else timeout
        val effectiveHom = if (preCommit) false else hom

        val pipeline = DefaultMutationExecutionPipeline()
        val changedLines =
            if (source != null && (diff != null || effectiveStaged)) {
                GitDiffParser.extractChangedLines(source!!, diff, effectiveStaged)
            } else {
                null
            }

        val extraClasspathList =
            classpath
                ?.split(Regex("[:;,]"))
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        val config =
            MutationConfig(
                minScore = threshold,
                includeExtreme = extreme,
                higherOrderMutants = effectiveHom,
                baselineTimeoutMs = effectiveTimeout,
                maxMutants = maxMutants,
                targetLines = changedLines,
                enableCache = cache,
                extraClasspath = extraClasspathList,
            )

        val outcome: AuditOutcome =
            if (sourceDir != null) {
                auditDirectory(pipeline, sourceDir!!, testDir, config, effectiveStaged, diff)
            } else if (source != null && test != null) {
                auditSingleFile(pipeline, source!!, test!!, config)
            } else if (preCommit) {
                val stagedFiles = GitDiffParser.extractStagedKotlinFiles()
                if (stagedFiles.isEmpty()) {
                    echo("\u001B[32m✔ Git pre-commit: No staged Kotlin files to audit.\u001B[0m")
                    return
                }
                echo("Git pre-commit: Found ${stagedFiles.size} staged Kotlin file(s).")
                auditFiles(pipeline, stagedFiles, testDir, config)
            } else {
                echo("\u001B[31mError: Must provide either (--source and --test) or (--source-dir).\u001B[0m")
                throw ProgramResult(1)
            }

        val report = outcome.report

        junitXml?.let { xmlPath ->
            JUnitXmlReportExporter.export(outcome, xmlPath)
        }

        htmlReport?.let { htmlPath ->
            HtmlReportExporter.export(report, htmlPath)
        }

        sarif?.let { sarifPath ->
            SarifReportExporter.export(report, sarifPath, source?.toString() ?: "Snippet.kt")
        }

        codeclimate?.let { codeClimatePath ->
            CodeClimateReportExporter.export(report, codeClimatePath, source?.toString() ?: "Snippet.kt")
        }

        if (json) {
            echo(jsonSerializer.encodeToString(report))
        } else {
            renderTerminalReport(outcome)
        }

        if (!MutationReportEvaluator.passes(report, threshold)) {
            throw ProgramResult(1)
        }
    }

    private fun auditSingleFile(
        pipeline: DefaultMutationExecutionPipeline,
        src: Path,
        tst: Path,
        config: MutationConfig,
    ): AuditOutcome {
        val report =
            runBlocking {
                pipeline.execute(
                    sourceCode = src.readText(),
                    testCode = tst.readText(),
                    config = config,
                    sourceFilePath = src.fileName.toString(),
                )
            }
        val coverage =
            AuditCoverage(
                auditedSourceCount = if (report.results.isEmpty()) 0 else 1,
                noMutationOpportunitySourceCount = if (report.results.isEmpty()) 1 else 0,
            )
        return AuditOutcome(MutationReportEvaluator.aggregate(listOf(report), coverage), coverage)
    }

    private fun auditDirectory(
        pipeline: DefaultMutationExecutionPipeline,
        srcDir: Path,
        tstDir: Path?,
        config: MutationConfig,
        staged: Boolean = false,
        diffRef: String? = null,
    ): AuditOutcome {
        val srcFiles =
            Files
                .walk(srcDir)
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .toList()

        return auditFiles(pipeline, srcFiles, tstDir ?: srcDir, config, staged, diffRef, baseDir = srcDir)
    }

    private fun auditFiles(
        pipeline: DefaultMutationExecutionPipeline,
        srcFiles: List<Path>,
        tstDir: Path?,
        baseConfig: MutationConfig,
        staged: Boolean = false,
        diffRef: String? = null,
        baseDir: Path? = null,
    ): AuditOutcome {
        val reports = mutableListOf<MutationReport>()
        val coverage = AuditCoverageAccumulator()

        for (srcFile in srcFiles) {
            val disposition =
                resolveDisposition(
                    FileScope(
                        srcFile = srcFile,
                        tstDir = tstDir,
                        baseConfig = baseConfig,
                        staged = staged,
                        diffRef = diffRef,
                        baseDir = baseDir,
                    ),
                )
            when (disposition) {
                is SourceDisposition.Skip -> {
                    coverage.skip(disposition.reason)
                }

                is SourceDisposition.Audit -> {
                    val report =
                        runBlocking {
                            pipeline.execute(
                                sourceCode = srcFile.readText(),
                                testCode = disposition.testCode,
                                config = disposition.config,
                                sourceFilePath = disposition.relativePath,
                            )
                        }
                    reports += report
                    if (report.results.isEmpty()) {
                        coverage.skip(AuditSkipReason.NO_MUTATION_OPPORTUNITY)
                    } else {
                        coverage.audit()
                    }
                }
            }
        }

        val resolvedCoverage = coverage.build()
        return AuditOutcome(MutationReportEvaluator.aggregate(reports, resolvedCoverage), resolvedCoverage)
    }

    /**
     * Decides whether [scope]'s source file is in scope and, if so, with which test suite and config.
     *
     * An untouched file inside a diff-scoped audit is an expected skip, while a file in scope with
     * no matching test suite is a coverage gap. Keeping the two apart here is what allows normal
     * multi-file diff audits to pass while still failing closed on missing tests.
     */
    private fun resolveDisposition(scope: FileScope): SourceDisposition {
        val isIncrementalScope = scope.staged || scope.diffRef != null
        val changedLines =
            if (isIncrementalScope) {
                GitDiffParser.extractChangedLines(scope.srcFile, scope.diffRef, scope.staged)
            } else {
                scope.baseConfig.targetLines
            }
        val isUnchanged = isIncrementalScope && changedLines?.isEmpty() == true
        val testCode = if (isUnchanged) null else locateTestCode(scope.srcFile, scope.tstDir)

        return when {
            isUnchanged -> {
                SourceDisposition.Skip(AuditSkipReason.UNCHANGED)
            }

            testCode.isNullOrBlank() -> {
                SourceDisposition.Skip(AuditSkipReason.MISSING_TEST)
            }

            else -> {
                SourceDisposition.Audit(
                    testCode = testCode,
                    config = scope.baseConfig.copy(targetLines = changedLines),
                    relativePath = scope.relativePath(),
                )
            }
        }
    }

    private fun locateTestCode(
        srcFile: Path,
        tstDir: Path?,
    ): String? {
        val baseName = srcFile.nameWithoutExtension
        val matchingTestFile =
            if (tstDir != null && Files.isDirectory(tstDir)) {
                Files
                    .walk(tstDir)
                    .filter {
                        it.isRegularFile() &&
                            (
                                it.nameWithoutExtension == "${baseName}Test" ||
                                    it.nameWithoutExtension == "${baseName}Spec" ||
                                    it.nameWithoutExtension == baseName
                            )
                    }.findFirst()
                    .orElse(null)
            } else {
                findAdjacentTestFile(srcFile)
            }
        return matchingTestFile?.readText()
    }

    private fun findAdjacentTestFile(srcFile: Path): Path? {
        val baseName = srcFile.nameWithoutExtension
        val parent = srcFile.parent ?: return null
        val candidates =
            listOf(
                parent.resolve("${baseName}Test.kt"),
                parent.resolve("${baseName}Spec.kt"),
            )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun renderTerminalReport(outcome: AuditOutcome) {
        val report = outcome.report
        val coverage = outcome.coverage
        val statusSymbol =
            if (MutationReportEvaluator.passes(report, threshold)) {
                "\u001B[32m✔ PASS\u001B[0m"
            } else {
                "\u001B[31m✘ FAIL\u001B[0m"
            }
        echo("\n=======================================================")
        echo("           KRONENBERG MUTATION AUDIT                   ")
        echo("=======================================================")
        echo(" Status:          $statusSymbol")
        echo(" Mutation Score:  ${report.mutationScore}% (Required: $threshold%)")
        echo(" Total Mutants:   ${report.totalMutants}")
        echo("   - Killed:      \u001B[32m${report.killedCount}\u001B[0m")
        echo("   - Survived:    \u001B[31m${report.survivedCount}\u001B[0m")
        echo("   - Timed Out:   \u001B[33m${report.timeoutCount}\u001B[0m")
        echo("   - Compile Err: ${report.compileErrorCount}")
        echo(" Source Coverage: ${coverage.describe()}")
        if (report.baselineError != null) {
            echo("\n\u001B[31m🚨 BASELINE PRE-FLIGHT ERROR:\u001B[0m\n  ${report.baselineError}")
        }
        MutationReportEvaluator.evaluate(report).describe()?.let { violations ->
            echo("\n\u001B[31m✘ AUDIT VIOLATIONS:\u001B[0m\n  $violations")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            echo("\n\u001B[31m🚨 SURVIVED MUTANTS (${survived.size}):\u001B[0m")
            survived.forEachIndexed { idx, res ->
                val m = res.mutant
                val srcLabel = m.filePath ?: source?.fileName?.toString() ?: "source"
                echo(" [$idx] ${m.mutatorName} at $srcLabel:${m.line}:${m.column}")
                echo("     - Original:    ${m.originalText}")
                echo("     + Replacement: ${m.replacementText}")
            }

            if (proposeTests) {
                echo("\n=======================================================")
                echo("   PROPOSED TEST SKELETONS TO KILL SURVIVED MUTANTS    ")
                echo("=======================================================")
                survived.forEachIndexed { idx, res ->
                    val m = res.mutant
                    val srcText =
                        m.filePath?.let { p ->
                            try {
                                Path.of(p).takeIf { Files.isRegularFile(it) }?.readText()
                            } catch (_: Exception) {
                                null
                            }
                        } ?: source?.takeIf { Files.isRegularFile(it) }?.readText() ?: ""
                    val proposal = SurvivingMutantTestProposer.proposeTest(m, srcText, TestStyle.KOTEST)
                    echo("\n--- Proposal #$idx for ${m.mutatorName} (Line ${m.line}) ---")
                    echo(proposal.testMethodCode)
                }
            }
        }
        echo("")
    }
}

public fun main(args: Array<String>) {
    KronenbergCli()
        .subcommands(AuditCommand())
        .main(args)
}

/**
 * Inputs used to decide whether one source file is in scope for a batch audit.
 */
internal data class FileScope(
    val srcFile: Path,
    val tstDir: Path?,
    val baseConfig: MutationConfig,
    val staged: Boolean,
    val diffRef: String?,
    val baseDir: Path?,
) {
    /** Path recorded on every mutant, relative to the audited base directory when there is one. */
    fun relativePath(): String = baseDir?.relativize(srcFile)?.toString() ?: srcFile.fileName.toString()
}

/**
 * Whether a single source file should be audited, and with what inputs.
 */
internal sealed interface SourceDisposition {
    /** The file is out of scope or has no test suite; it is tallied under [reason]. */
    data class Skip(
        val reason: AuditSkipReason,
    ) : SourceDisposition

    /** The file is in scope and can be executed. */
    data class Audit(
        val testCode: String,
        val config: MutationConfig,
        val relativePath: String,
    ) : SourceDisposition
}

/**
 * Mutable tally of per-file audit dispositions collected during a batch audit.
 *
 * Skips are recorded by reason so that expected skips (untouched files) stay separable from
 * coverage gaps (missing tests) when the centralized policy decides whether the audit passed.
 */
internal class AuditCoverageAccumulator {
    private var audited = 0
    private val skipped = mutableMapOf<AuditSkipReason, Int>()

    fun audit() {
        audited++
    }

    fun skip(reason: AuditSkipReason) {
        skipped[reason] = (skipped[reason] ?: 0) + 1
    }

    fun build(): AuditCoverage =
        AuditCoverage(
            auditedSourceCount = audited,
            unchangedSourceCount = skipped[AuditSkipReason.UNCHANGED] ?: 0,
            missingTestSourceCount = skipped[AuditSkipReason.MISSING_TEST] ?: 0,
            noMutationOpportunitySourceCount = skipped[AuditSkipReason.NO_MUTATION_OPPORTUNITY] ?: 0,
        )
}
