package com.gokorei.kronenberg.gradle

import com.gokorei.kronenberg.model.AuditCoverage
import com.gokorei.kronenberg.model.AuditOutcome
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationReport
import com.gokorei.kronenberg.model.MutationReportEvaluator
import com.gokorei.kronenberg.report.JUnitXmlReportWriter
import com.gokorei.kronenberg.runner.DefaultMutationExecutionPipeline
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Gradle task executing in-process K2 AST mutation tests against Kotlin source and test sets.
 */
@CacheableTask
public abstract class KronenbergAuditTask
    @Inject
    constructor(
        objects: ObjectFactory,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public val sourceFiles: ConfigurableFileCollection = objects.fileCollection()

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public val testFiles: ConfigurableFileCollection = objects.fileCollection()

        @get:Classpath
        public val classpath: ConfigurableFileCollection = objects.fileCollection()

        @get:Input
        public val minScore: Property<Double> = objects.property(Double::class.java).convention(80.0)

        @get:Input
        public val baselineTimeoutMs: Property<Long> = objects.property(Long::class.java).convention(2000L)

        @get:Input
        public val includeExtreme: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

        @get:Input
        public val higherOrderMutants: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

        @get:Input
        @get:Optional
        public val maxMutants: Property<Int> = objects.property(Int::class.java)

        @get:Input
        public val enableCache: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

        @get:OutputDirectory
        public val reportsDir: DirectoryProperty = objects.directoryProperty()

        init {
            group = "verification"
            description = "Runs in-process K2 AST mutation testing on Kotlin code."
        }

        @TaskAction
        public fun audit() {
            val srcList = sourceFiles.files.filter { it.extension == "kt" }
            val tstList = testFiles.files.filter { it.extension == "kt" }
            val extraClasspathList = classpath.files.map { it.absolutePath }

            logger.lifecycle("🧟 Kronenberg: Auditing ${srcList.size} Kotlin source file(s) against ${tstList.size} test file(s)...")

            if (srcList.isEmpty()) {
                throw GradleException("No Kotlin source files found to audit")
            }

            val config =
                MutationConfig(
                    minScore = minScore.get(),
                    includeExtreme = includeExtreme.get(),
                    higherOrderMutants = higherOrderMutants.get(),
                    baselineTimeoutMs = baselineTimeoutMs.get(),
                    maxMutants = if (maxMutants.isPresent) maxMutants.get() else null,
                    enableCache = enableCache.get(),
                    extraClasspath = extraClasspathList,
                )

            val pipeline = DefaultMutationExecutionPipeline()
            val reports = mutableListOf<MutationReport>()
            var missingTestSourceCount = 0
            var noMutationOpportunitySourceCount = 0

            for (srcFile in srcList) {
                val baseName = srcFile.nameWithoutExtension
                val matchingTestFile =
                    tstList.firstOrNull {
                        val tstName = it.nameWithoutExtension
                        tstName == "${baseName}Test" || tstName == "${baseName}Spec" || tstName == baseName
                    }

                val testCode = matchingTestFile?.readText() ?: ""
                if (testCode.isBlank()) {
                    logger.info("Skipping ${srcFile.name}: No matching test suite found.")
                    missingTestSourceCount++
                    continue
                }

                val report =
                    runBlocking {
                        pipeline.execute(
                            sourceCode = srcFile.readText(),
                            testCode = testCode,
                            config = config,
                            sourceFilePath = srcFile.name,
                        )
                    }
                reports += report
                if (report.results.isEmpty()) {
                    noMutationOpportunitySourceCount++
                }
            }

            val coverage =
                AuditCoverage(
                    auditedSourceCount = reports.count { it.results.isNotEmpty() },
                    missingTestSourceCount = missingTestSourceCount,
                    noMutationOpportunitySourceCount = noMutationOpportunitySourceCount,
                )
            val finalReport = MutationReportEvaluator.aggregate(reports, coverage)
            val outcome = AuditOutcome(finalReport, coverage)

            val outDir = reportsDir.get().asFile.toPath()
            Files.createDirectories(outDir)

            exportHtmlReport(finalReport, outDir.resolve("mutation-report.html"))
            JUnitXmlReportWriter.write(outcome, outDir.resolve("mutation-results.xml"))

            logger.lifecycle("=======================================================")
            logger.lifecycle("           KRONENBERG MUTATION AUDIT                   ")
            logger.lifecycle("=======================================================")
            logger.lifecycle("  Total Mutants : ${finalReport.totalMutants}")
            logger.lifecycle("  Killed        : ${finalReport.killedCount}")
            logger.lifecycle("  Survived      : ${finalReport.survivedCount}")
            logger.lifecycle("  Timed Out     : ${finalReport.timeoutCount}")
            logger.lifecycle("  Compile Errors: ${finalReport.compileErrorCount}")
            logger.lifecycle("  Source Files  : ${coverage.describe()}")
            logger.lifecycle("  Mutation Score: ${finalReport.mutationScore}% (Threshold: ${minScore.get()}%)")
            logger.lifecycle("  Reports       : $outDir")
            logger.lifecycle("=======================================================")

            if (!MutationReportEvaluator.passes(finalReport, minScore.get())) {
                val reason =
                    finalReport.baselineError
                        ?: MutationReportEvaluator.evaluate(finalReport).describe()
                        ?: "Mutation audit did not satisfy the fail-closed quality policy"
                throw GradleException(
                    "$reason. Mutation score ${finalReport.mutationScore}% did not pass threshold ${minScore.get()}%. " +
                        "See reports at ${outDir.resolve("mutation-report.html")}",
                )
            }
        }

        private fun exportHtmlReport(
            report: MutationReport,
            targetFile: Path,
        ) {
            val scoreColor = if (MutationReportEvaluator.passes(report, minScore.get())) "#10b981" else "#ef4444"
            val html =
                buildString {
                    appendLine("<!DOCTYPE html>")
                    appendLine("<html lang=\"en\">")
                    appendLine("<head>")
                    appendLine("  <meta charset=\"UTF-8\">")
                    appendLine("  <title>Kronenberg Mutation Audit Report</title>")
                    appendLine("  <style>")
                    appendLine("    body { font-family: sans-serif; margin: 0; padding: 24px; background: #0f172a; color: #f8fafc; }")
                    appendLine("    .container { max-width: 1100px; margin: 0 auto; }")
                    appendLine("    .header { display: flex; justify-content: space-between; align-items: center; }")
                    appendLine("    .card-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }")
                    appendLine("    .card { background: #1e293b; padding: 20px; border-radius: 10px; }")
                    appendLine("    table { width: 100%; border-collapse: collapse; background: #1e293b; }")
                    appendLine("    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #334155; }")
                    appendLine("    .badge { display: inline-block; padding: 4px 8px; border-radius: 6px; font-weight: 600; }")
                    appendLine("    .badge-killed { background: #064e3b; color: #34d399; }")
                    appendLine("    .badge-survived { background: #7f1d1d; color: #f87171; }")
                    appendLine("    .badge-timeout { background: #78350f; color: #fbbf24; }")
                    appendLine("    .badge-error { background: #374151; color: #9ca3af; }")
                    appendLine("    code { font-family: monospace; background: #0f172a; padding: 2px 6px; }")
                    appendLine("  </style>")
                    appendLine("</head>")
                    appendLine("<body>")
                    appendLine("  <div class=\"container\">")
                    appendLine("    <div class=\"header\">")
                    appendLine("      <h1>🧟 Kronenberg Mutation Audit</h1>")
                    appendLine("      <span style=\"color:$scoreColor\">${report.mutationScore}% Score</span>")
                    appendLine("    </div>")
                    appendLine("    <div class=\"card-grid\">")
                    appendLine("      <div class=\"card\"><h3>Total</h3><div>${report.totalMutants}</div></div>")
                    appendLine("      <div class=\"card\"><h3>Killed</h3><div>${report.killedCount}</div></div>")
                    appendLine("      <div class=\"card\"><h3>Survived</h3><div>${report.survivedCount}</div></div>")
                    appendLine("      <div class=\"card\"><h3>Timed Out</h3><div>${report.timeoutCount}</div></div>")
                    appendLine("    </div>")
                    appendLine("    <table>")
                    appendLine("      <thead>")
                    appendLine("        <tr><th>Status</th><th>Location</th><th>Mutator</th><th>Original</th><th>Replacement</th></tr>")
                    appendLine("      </thead>")
                    appendLine("      <tbody>")
                    for (res in report.results) {
                        val badgeClass =
                            when (res.status) {
                                MutantStatus.KILLED -> "badge-killed"
                                MutantStatus.SURVIVED -> "badge-survived"
                                MutantStatus.TIMED_OUT -> "badge-timeout"
                                else -> "badge-error"
                            }
                        val loc = "${res.mutant.filePath ?: "unknown"}:${res.mutant.line}:${res.mutant.column}"
                        appendLine("        <tr>")
                        appendLine("          <td><span class=\"badge $badgeClass\">${res.status}</span></td>")
                        appendLine("          <td>$loc</td>")
                        appendLine("          <td>${res.mutant.mutatorName}</td>")
                        appendLine("          <td><code>${escapeHtml(res.mutant.originalText)}</code></td>")
                        appendLine("          <td><code>${escapeHtml(res.mutant.replacementText)}</code></td>")
                        appendLine("        </tr>")
                    }
                    appendLine("      </tbody>")
                    appendLine("    </table>")
                    appendLine("  </div>")
                    appendLine("</body>")
                    appendLine("</html>")
                }
            targetFile.parent?.let { Files.createDirectories(it) }
            Files.writeString(targetFile, html)
        }

        private fun escapeHtml(str: String): String =
            str
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
    }
