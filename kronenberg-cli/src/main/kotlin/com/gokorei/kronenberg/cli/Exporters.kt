package com.gokorei.kronenberg.cli

import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Git diff hunk parser extracting modified line numbers.
 */
public object GitDiffParser {
    public fun extractChangedLines(
        sourceFile: Path,
        gitRef: String? = null,
        staged: Boolean = false,
    ): List<Int> {
        val cmd = mutableListOf("git", "diff", "-U0")
        if (staged) {
            cmd.add("--cached")
        } else if (gitRef != null) {
            cmd.add(gitRef)
        }
        cmd.add("--")
        cmd.add(sourceFile.toAbsolutePath().toString())

        val process =
            runCatching {
                ProcessBuilder(cmd)
                    .directory(sourceFile.parent?.toFile() ?: File("."))
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return emptyList()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.SECONDS)

        return parseHunkLines(output)
    }

    public fun parseHunkLines(diffOutput: String): List<Int> {
        val lines = mutableListOf<Int>()
        val regex = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")
        for (match in regex.findAll(diffOutput)) {
            val startLine = match.groupValues[1].toIntOrNull() ?: continue
            val count = match.groupValues[2].toIntOrNull() ?: 1
            for (line in startLine until (startLine + count)) {
                lines.add(line)
            }
        }
        return lines.distinct().sorted()
    }

    public fun parseStagedKotlinFileNames(gitOutput: String): List<String> =
        gitOutput
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(".kt") && !it.endsWith(".kts") }

    public fun extractStagedKotlinFiles(workingDir: File = File(".")): List<Path> {
        val cmd = listOf("git", "diff", "--cached", "--name-only")
        val process =
            runCatching {
                ProcessBuilder(cmd)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return emptyList()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.SECONDS)

        return parseStagedKotlinFileNames(output).map { workingDir.toPath().resolve(it) }
    }
}

/**
 * Exporter converting MutationReport into self-contained interactive HTML format.
 */
public object HtmlReportExporter {
    public fun export(
        report: MutationReport,
        targetFile: Path,
        title: String = "Kronenberg Mutation Audit Report",
    ) {
        val scoreColor = if (report.mutationScore >= 80.0) "#10b981" else "#ef4444"
        val html =
            buildString {
                appendLine("<!DOCTYPE html>")
                appendLine("<html lang=\"en\">")
                appendLine("<head>")
                appendLine("  <meta charset=\"UTF-8\">")
                appendLine("  <title>$title</title>")
                appendLine(
                    """
                    <style>
                    body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 24px; background: #0f172a; color: #f8fafc; }
                    .container { max-width: 1100px; margin: 0 auto; }
                    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 16px; margin-bottom: 24px; }
                    .card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }
                    .card { background: #1e293b; padding: 20px; border-radius: 10px; border: 1px solid #334155; text-align: center; }
                    .card h3 { margin: 0 0 8px 0; font-size: 14px; color: #94a3b8; text-transform: uppercase; }
                    .card .value { font-size: 28px; font-weight: 700; }
                    table { width: 100%; border-collapse: collapse; background: #1e293b; border-radius: 10px; overflow: hidden; border: 1px solid #334155; }
                    th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid #334155; }
                    th { background: #0f172a; color: #94a3b8; font-size: 13px; text-transform: uppercase; }
                    .badge { display: inline-block; padding: 4px 8px; border-radius: 6px; font-size: 12px; font-weight: 600; }
                    .badge-killed { background: #064e3b; color: #34d399; }
                    .badge-survived { background: #7f1d1d; color: #f87171; }
                     .badge-timeout { background: #78350f; color: #fbbf24; }
                     .badge-infrastructure { background: #4c1d95; color: #c4b5fd; }
                     .badge-runner { background: #7f1d1d; color: #fca5a5; }
                     .badge-error { background: #374151; color: #9ca3af; }

                    code { font-family: ui-monospace, SFMono-Regular, monospace; background: #0f172a; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
                    </style>
                    """.trimIndent(),
                )
                appendLine("</head>")
                appendLine("<body>")
                appendLine("  <div class=\"container\">")
                appendLine("    <div class=\"header\">")
                appendLine("      <h1>🧟 Kronenberg Mutation Audit</h1>")
                appendLine("      <span style=\"font-size:24px;font-weight:bold;color:$scoreColor\">${report.mutationScore}% Score</span>")
                appendLine("    </div>")
                appendLine("    <div class=\"card-grid\">")
                appendLine("      <div class=\"card\"><h3>Total Mutants</h3><div class=\"value\">${report.totalMutants}</div></div>")
                appendLine(
                    "      <div class=\"card\"><h3>Killed</h3><div class=\"value\" style=\"color:#34d399\">${report.killedCount}</div></div>",
                )
                appendLine(
                    "      <div class=\"card\"><h3>Survived</h3><div class=\"value\" style=\"color:#f87171\">${report.survivedCount}</div></div>",
                )
                appendLine(
                    "      <div class=\"card\"><h3>Timed Out</h3><div class=\"value\" style=\"color:#fbbf24\">${report.timeoutCount}</div></div>",
                )
                appendLine("      <div class=\"card\"><h3>Compile Errors</h3><div class=\"value\">${report.compileErrorCount}</div></div>")
                appendLine(
                    "      <div class=\"card\"><h3>Infrastructure Errors</h3>" +
                        "<div class=\"value\">${report.infrastructureErrorCount}</div></div>",
                )
                appendLine("      <div class=\"card\"><h3>Runner Errors</h3><div class=\"value\">${report.runnerErrorCount}</div></div>")
                appendLine("    </div>")

                appendLine("    <table>")
                appendLine("      <thead>")
                appendLine(
                    "        <tr><th>Status</th><th>Mutator</th><th>Location</th><th>Original</th><th>Replacement</th><th>Details</th></tr>",
                )
                appendLine("      </thead>")
                appendLine("      <tbody>")
                for (res in report.results) {
                    val m = res.mutant
                    val (badgeClass, badgeText) =
                        when (res.status) {
                            MutantStatus.KILLED -> "badge-killed" to "KILLED"
                            MutantStatus.SURVIVED -> "badge-survived" to "SURVIVED"
                            MutantStatus.TIMED_OUT -> "badge-timeout" to "TIMED_OUT"
                            MutantStatus.COMPILE_ERROR -> "badge-error" to "COMPILE_ERROR"
                            MutantStatus.BASELINE_ERROR -> "badge-error" to "BASELINE_ERROR"
                            MutantStatus.INFRASTRUCTURE_ERROR -> "badge-infrastructure" to "INFRASTRUCTURE_ERROR"
                            MutantStatus.RUNNER_ERROR -> "badge-runner" to "RUNNER_ERROR"
                        }
                    val locationText = if (m.filePath != null) "${m.filePath}:${m.line}:${m.column}" else "L${m.line}:C${m.column}"
                    appendLine("        <tr>")
                    appendLine("          <td><span class=\"badge $badgeClass\">$badgeText</span></td>")
                    appendLine("          <td>${m.mutatorName}</td>")
                    appendLine("          <td>${escapeHtml(locationText)}</td>")
                    appendLine("          <td><code>${escapeHtml(m.originalText)}</code></td>")
                    appendLine("          <td><code>${escapeHtml(m.replacementText)}</code></td>")
                    appendLine("          <td>${escapeHtml(res.failureMessage.orEmpty())}</td>")
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

    private fun escapeHtml(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}

/**
 * Exporter converting MutationReport into SARIF v2.1.0 format for GitHub code scanning / PR review annotations.
 */
public object SarifReportExporter {
    private val json = Json { prettyPrint = true }

    public fun export(
        report: MutationReport,
        targetFile: Path,
        sourceFilePath: String = "src/main/kotlin/Snippet.kt",
    ) {
        val rules =
            report.results
                .map { it.mutant.mutatorName }
                .distinct()
                .map { name ->
                    buildJsonObject {
                        put("id", name)
                        putJsonObject("shortDescription") {
                            put("text", "Kronenberg AST Mutator: $name")
                        }
                    }
                }

        val sarifResults =
            report.results
                .filter { it.status != MutantStatus.KILLED }
                .map { res ->
                    val m = res.mutant
                    val isError = res.status != MutantStatus.SURVIVED
                    buildJsonObject {
                        put("ruleId", m.mutatorName)
                        put("level", if (isError) "error" else "warning")
                        putJsonObject("message") {
                            val message =
                                when (res.status) {
                                    MutantStatus.SURVIVED -> {
                                        "Surviving Mutant: Replaced '${m.originalText}' with '${m.replacementText}' (line ${m.line})"
                                    }

                                    MutantStatus.TIMED_OUT -> {
                                        "Timed out mutant at line ${m.line}: ${res.failureMessage.orEmpty()}"
                                    }

                                    MutantStatus.COMPILE_ERROR -> {
                                        "Compilation error at line ${m.line}: ${res.failureMessage.orEmpty()}"
                                    }

                                    MutantStatus.BASELINE_ERROR -> {
                                        "Baseline error at line ${m.line}: ${res.failureMessage.orEmpty()}"
                                    }

                                    MutantStatus.INFRASTRUCTURE_ERROR -> {
                                        "Infrastructure error at line ${m.line}: ${res.failureMessage.orEmpty()}"
                                    }

                                    MutantStatus.RUNNER_ERROR -> {
                                        "Runner error at line ${m.line}: ${res.failureMessage.orEmpty()}"
                                    }

                                    MutantStatus.KILLED -> {
                                        ""
                                    }
                                }
                            put("text", message)
                        }
                        putJsonObject("properties") {
                            put("status", res.status.name)
                        }
                        putJsonArray("locations") {
                            add(
                                buildJsonObject {
                                    putJsonObject("physicalLocation") {
                                        putJsonObject("artifactLocation") {
                                            put("uri", m.filePath ?: sourceFilePath)
                                        }
                                        putJsonObject("region") {
                                            put("startLine", m.line)
                                            put("startColumn", m.column)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

        val sarifSchemaUri =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
        val sarifObj =
            buildJsonObject {
                put("\$schema", sarifSchemaUri)
                put("version", "2.1.0")
                putJsonArray("runs") {
                    add(
                        buildJsonObject {
                            putJsonObject("tool") {
                                putJsonObject("driver") {
                                    put("name", "Kronenberg")
                                    put("informationUri", "https://github.com/gokorei/kronenberg")
                                    put("semanticVersion", "0.1.0")
                                    put("rules", JsonArray(rules))
                                }
                            }
                            put("results", JsonArray(sarifResults))
                        },
                    )
                }
            }

        targetFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(targetFile, json.encodeToString(JsonObject.serializer(), sarifObj))
    }
}

/**
 * Exporter converting MutationReport into Code Climate issue JSON format for surviving mutants.
 * Spec documentation: https://github.com/codeclimate/platform/blob/master/spec/analyzers/SPEC.md
 */
public object CodeClimateReportExporter {
    private val json = Json { prettyPrint = true }

    public fun export(
        report: MutationReport,
        targetFile: Path,
        sourceFilePath: String = "src/main/kotlin/Snippet.kt",
    ) {
        val issues =
            report.results
                .filter { it.status != MutantStatus.KILLED }
                .map { res -> buildIssue(res, sourceFilePath) }

        targetFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(targetFile, json.encodeToString(JsonArray(issues)))
    }

    private fun buildIssue(
        result: MutantResult,
        sourceFilePath: String,
    ) = buildJsonObject {
        val mutant = result.mutant
        val path = mutant.filePath ?: sourceFilePath
        val description = issueDescription(result)
        val fingerprint = "$path:${mutant.line}:${mutant.mutatorName}:${mutant.id}:${result.status.name}".hashCode().toString()

        put("type", "issue")
        put("check_name", checkName(result.status))
        put("description", description)
        put(
            "content",
            buildJsonObject {
                put(
                    "body",
                    "Mutant ID: ${mutant.id}\nStatus: ${result.status.name}\nMutator: ${mutant.mutatorName}\n" +
                        "Original:\n${mutant.originalText}\nReplacement:\n${mutant.replacementText}",
                )
            },
        )
        putJsonArray("categories") {
            add("Bug Risk")
        }
        putJsonObject("location") {
            put("path", path)
            putJsonObject("lines") {
                put("begin", mutant.line)
                put("end", mutant.line)
            }
        }
        putJsonObject("remediation_points") {
            put("cost", 50000)
        }
        put("severity", severity(result.status))
        put("fingerprint", fingerprint)
    }

    private fun issueDescription(result: MutantResult): String {
        val mutant = result.mutant
        return when (result.status) {
            MutantStatus.SURVIVED -> {
                "Surviving mutation (${mutant.mutatorName}): replaced '${mutant.originalText}' with '${mutant.replacementText}'. " +
                    "Verify test coverage for this condition."
            }

            MutantStatus.INFRASTRUCTURE_ERROR -> {
                "Infrastructure error (${mutant.mutatorName}): ${result.failureMessage.orEmpty()}"
            }

            MutantStatus.RUNNER_ERROR -> {
                "Runner error (${mutant.mutatorName}): ${result.failureMessage.orEmpty()}"
            }

            MutantStatus.TIMED_OUT -> {
                "Timed out mutation (${mutant.mutatorName}): ${result.failureMessage.orEmpty()}"
            }

            MutantStatus.COMPILE_ERROR -> {
                "Compilation error (${mutant.mutatorName}): ${result.failureMessage.orEmpty()}"
            }

            MutantStatus.BASELINE_ERROR -> {
                "Baseline error (${mutant.mutatorName}): ${result.failureMessage.orEmpty()}"
            }

            MutantStatus.KILLED -> {
                ""
            }
        }
    }

    private fun checkName(status: MutantStatus): String =
        when (status) {
            MutantStatus.SURVIVED -> "KronenbergMutationCheck"
            MutantStatus.INFRASTRUCTURE_ERROR -> "KronenbergInfrastructureCheck"
            MutantStatus.RUNNER_ERROR -> "KronenbergRunnerCheck"
            else -> "Kronenberg${status.name}"
        }

    private fun severity(status: MutantStatus): String =
        if (status == MutantStatus.INFRASTRUCTURE_ERROR || status == MutantStatus.RUNNER_ERROR) "critical" else "minor"
}
