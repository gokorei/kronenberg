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
import com.gokorei.kronenberg.discovery.DefaultSourceTestMatcher
import com.gokorei.kronenberg.discovery.SourceTestMatcher
import com.gokorei.kronenberg.discovery.TestMatchResult
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationReport
import com.gokorei.kronenberg.runner.DefaultMutationExecutionPipeline
import com.gokorei.kronenberg.runner.SurvivingMutantTestProposer
import com.gokorei.kronenberg.runner.TestStyle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val jsonSerializer =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

private val testMatcher: SourceTestMatcher = DefaultSourceTestMatcher

private data class AuditFilesRequest(
    val sourceFiles: List<Path>,
    val testFiles: List<Path>,
    val baseConfig: MutationConfig,
    val staged: Boolean,
    val diffRef: String?,
    val baseDir: Path?,
    val testRoot: Path?,
)

/**
 * Exporter converting Kronenberg MutationReports into standardized JUnit XML format.
 */
public object JUnitXmlReportExporter {
    public fun export(
        report: MutationReport,
        targetFile: Path,
        testSuiteName: String = "Kronenberg Mutation Audit",
    ) {
        val totalTests = report.totalMutants
        val failures = report.survivedCount + report.timeoutCount
        val errors = report.compileErrorCount
        val totalTimeSeconds = report.results.sumOf { it.executionTimeMs } / 1000.0

        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine(
            "<testsuite name=\"$testSuiteName\" tests=\"$totalTests\" failures=\"$failures\" errors=\"$errors\" time=\"$totalTimeSeconds\">",
        )

        for (result in report.results) {
            val mutant = result.mutant
            val durationSec = result.executionTimeMs / 1000.0
            val className = "com.gokorei.kronenberg.mutant.${mutant.category.name.lowercase()}"
            val testName = "${mutant.mutatorName}_line${mutant.line}_col${mutant.column}_${mutant.id.take(8)}"

            sb.appendLine("    <testcase classname=\"$className\" name=\"$testName\" time=\"$durationSec\">")
            when (result.status) {
                MutantStatus.SURVIVED -> {
                    val msg = escapeXml("Mutant survived: replaced '${mutant.originalText}' with '${mutant.replacementText}'")
                    val body =
                        escapeXml(
                            "Mutant ID: ${mutant.id}\nMutator: ${mutant.mutatorName}\nLocation: line ${mutant.line}, column ${mutant.column}\nOriginal:\n${mutant.originalText}\nMutated:\n${mutant.replacementText}",
                        )
                    sb.appendLine("        <failure message=\"$msg\" type=\"MutationSurvived\">$body</failure>")
                }

                MutantStatus.TIMED_OUT -> {
                    val msg = escapeXml(result.failureMessage ?: "Mutant execution timed out")
                    sb.appendLine("        <failure message=\"$msg\" type=\"Timeout\">$msg</failure>")
                }

                MutantStatus.COMPILE_ERROR -> {
                    val msg = escapeXml(result.failureMessage ?: "Mutant failed to compile")
                    sb.appendLine("        <error message=\"$msg\" type=\"CompileError\">$msg</error>")
                }

                MutantStatus.BASELINE_ERROR -> {
                    val msg = escapeXml(result.failureMessage ?: "Baseline execution failed before mutation")
                    sb.appendLine("        <error message=\"$msg\" type=\"BaselineError\">$msg</error>")
                }

                MutantStatus.KILLED -> {
                    // Passing test case
                }
            }
            sb.appendLine("    </testcase>")
        }
        sb.appendLine("</testsuite>")

        targetFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(targetFile, sb.toString())
    }

    private fun escapeXml(str: String): String =
        str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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

        val report: MutationReport =
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
                val testRoot =
                    testDir ?: stagedFiles.asSequence().mapNotNull { DefaultSourceTestMatcher.conventionalTestRoot(it) }.firstOrNull()
                val testFiles = testRoot?.let { collectKotlinFiles(it) }.orEmpty()
                auditFiles(
                    pipeline,
                    AuditFilesRequest(
                        sourceFiles = stagedFiles,
                        testFiles = testFiles,
                        baseConfig = config,
                        staged = true,
                        diffRef = diff,
                        baseDir = null,
                        testRoot = testRoot,
                    ),
                )
            } else {
                echo("\u001B[31mError: Must provide either (--source and --test) or (--source-dir).\u001B[0m")
                throw ProgramResult(1)
            }

        junitXml?.let { xmlPath ->
            JUnitXmlReportExporter.export(report, xmlPath)
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
            renderTerminalReport(report)
        }

        if (report.mutationScore < threshold) {
            throw ProgramResult(1)
        }
    }

    private fun auditSingleFile(
        pipeline: DefaultMutationExecutionPipeline,
        src: Path,
        tst: Path,
        config: MutationConfig,
    ): MutationReport =
        runBlocking {
            pipeline.execute(
                sourceCode = src.readText(),
                testCode = tst.readText(),
                config = config,
                sourceFilePath = src.fileName.toString(),
            )
        }

    private fun auditDirectory(
        pipeline: DefaultMutationExecutionPipeline,
        srcDir: Path,
        tstDir: Path?,
        config: MutationConfig,
        staged: Boolean = false,
        diffRef: String? = null,
    ): MutationReport {
        val testRoot = tstDir ?: DefaultSourceTestMatcher.conventionalTestRoot(srcDir)
        return auditFiles(
            pipeline,
            AuditFilesRequest(
                sourceFiles = collectKotlinFiles(srcDir),
                testFiles = testRoot?.let { collectKotlinFiles(it) }.orEmpty(),
                baseConfig = config,
                staged = staged,
                diffRef = diffRef,
                baseDir = srcDir,
                testRoot = testRoot,
            ),
        )
    }

    private fun auditFiles(
        pipeline: DefaultMutationExecutionPipeline,
        request: AuditFilesRequest,
    ): MutationReport {
        val fileReports = request.sourceFiles.mapNotNull { auditFile(pipeline, it, request) }
        val totalEffective = fileReports.sumOf { it.killedCount + it.survivedCount + it.timeoutCount }
        val score =
            if (totalEffective > 0) {
                (fileReports.sumOf { it.killedCount + it.timeoutCount }.toDouble() / totalEffective.toDouble()) * 100.0
            } else {
                100.0
            }
        return MutationReport(
            totalMutants = fileReports.sumOf { it.totalMutants },
            killedCount = fileReports.sumOf { it.killedCount },
            survivedCount = fileReports.sumOf { it.survivedCount },
            timeoutCount = fileReports.sumOf { it.timeoutCount },
            compileErrorCount = fileReports.sumOf { it.compileErrorCount },
            mutationScore = (score * 10.0).toInt() / 10.0,
            results = fileReports.flatMap { it.results },
        )
    }

    private fun auditFile(
        pipeline: DefaultMutationExecutionPipeline,
        srcFile: Path,
        request: AuditFilesRequest,
    ): MutationReport? {
        val fileChangedLines =
            if (request.staged || request.diffRef != null) {
                GitDiffParser.extractChangedLines(srcFile, request.diffRef, request.staged)
            } else {
                request.baseConfig.targetLines
            }
        if ((request.staged || request.diffRef != null) && fileChangedLines?.isEmpty() == true) {
            return null
        }
        val testFile = matchingTestFile(srcFile, request)
        val relPath =
            request.baseDir
                ?.toAbsolutePath()
                ?.normalize()
                ?.relativize(srcFile.toAbsolutePath().normalize())
                ?.toString()
                ?: srcFile.fileName.toString()
        return runBlocking {
            pipeline.execute(
                sourceCode = srcFile.readText(),
                testCode = testFile.readText(),
                config = request.baseConfig.copy(targetLines = fileChangedLines),
                sourceFilePath = relPath,
            )
        }
    }

    private fun matchingTestFile(
        srcFile: Path,
        request: AuditFilesRequest,
    ): Path =
        when (
            val matchResult = testMatcher.match(srcFile, request.testFiles, request.baseDir, request.testRoot)
        ) {
            is TestMatchResult.Matched -> {
                matchResult.testFile
            }

            is TestMatchResult.Missing -> {
                echo("Error: No matching test file found for source $srcFile.")
                throw ProgramResult(1)
            }

            is TestMatchResult.Ambiguous -> {
                echo(
                    "Error: Multiple matching test files found for source $srcFile: " +
                        matchResult.candidates.joinToString { it.toString() },
                )
                throw ProgramResult(1)
            }
        }

    private fun collectKotlinFiles(directory: Path): List<Path> =
        Files
            .walk(directory)
            .use { paths -> paths.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList() }

    private fun renderTerminalReport(report: MutationReport) {
        val statusSymbol = if (report.mutationScore >= threshold) "\u001B[32m✔ PASS\u001B[0m" else "\u001B[31m✘ FAIL\u001B[0m"
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
        if (report.baselineError != null) {
            echo("\n\u001B[31m🚨 BASELINE PRE-FLIGHT ERROR:\u001B[0m\n  ${report.baselineError}")
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
