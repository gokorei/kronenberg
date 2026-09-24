package com.gokorei.kronenberg.cli

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class GitFailureKind {
    STARTUP_FAILED,
    NON_ZERO_EXIT,
    TIMED_OUT,
    OUTPUT_LIMIT_EXCEEDED,
    OUTPUT_READ_FAILED,
    MALFORMED_OUTPUT,
    INVALID_REFERENCE,
    INTERRUPTED,
}

internal sealed interface GitProcessResult {
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) : GitProcessResult

    data class Failure(
        val kind: GitFailureKind,
        val message: String,
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
    ) : GitProcessResult
}

internal sealed interface GitOperationResult<out T> {
    data class Success<T>(
        val value: T,
    ) : GitOperationResult<T>

    data class Failure(
        val kind: GitFailureKind,
        val message: String,
        val exitCode: Int? = null,
        val stderr: String = "",
    ) : GitOperationResult<Nothing>
}

internal interface GitProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path,
        timeout: Duration = Duration.ofSeconds(2),
        outputLimitBytes: Int = DEFAULT_GIT_OUTPUT_LIMIT_BYTES,
    ): GitProcessResult
}

internal const val DEFAULT_GIT_OUTPUT_LIMIT_BYTES: Int = 1_048_576

internal data class GitProcessOptions(
    val runner: GitProcessRunner = DefaultGitProcessRunner,
    val timeout: Duration = Duration.ofSeconds(2),
    val outputLimitBytes: Int = DEFAULT_GIT_OUTPUT_LIMIT_BYTES,
)

internal object DefaultGitProcessRunner : GitProcessRunner {
    override fun run(
        command: List<String>,
        workingDirectory: Path,
        timeout: Duration,
        outputLimitBytes: Int,
    ): GitProcessResult {
        require(outputLimitBytes > 0)
        val deadline = System.nanoTime() + timeout.toNanos()
        return when (val start = startProcess(command, workingDirectory)) {
            is ProcessStart.Started -> {
                executeProcess(start.process, deadline, timeout, outputLimitBytes)
            }

            is ProcessStart.Failed -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.STARTUP_FAILED,
                    message = start.message,
                )
            }
        }
    }

    private fun startProcess(
        command: List<String>,
        workingDirectory: Path,
    ): ProcessStart =
        runCatching {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false)
                .start()
        }.fold(
            onSuccess = ProcessStart::Started,
            onFailure = { error -> ProcessStart.Failed(error.message ?: error.javaClass.simpleName) },
        )

    private fun executeProcess(
        process: Process,
        deadline: Long,
        timeout: Duration,
        outputLimitBytes: Int,
    ): GitProcessResult {
        val collector = BoundedOutputCollector(outputLimitBytes)
        val stdoutReader = StreamReader(process.inputStream, collector, isStdout = true)
        val stderrReader = StreamReader(process.errorStream, collector, isStdout = false)
        stdoutReader.start()
        stderrReader.start()
        val readers = ProcessReaders(stdoutReader, stderrReader, collector)
        val completion = waitForProcess(process, deadline)
        return when (completion) {
            is ProcessCompletion.Exited -> completedResult(completion.exitCode, readers, outputLimitBytes)
            is ProcessCompletion.Terminal -> terminalFailure(process, completion, timeout, readers)
        }
    }

    private fun waitForProcess(
        process: Process,
        deadline: Long,
    ): ProcessCompletion {
        var interruptionMessage: String? = null
        val completed =
            try {
                process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                interruptionMessage = error.message ?: "Git process wait was interrupted"
                false
            }
        return when {
            interruptionMessage != null -> ProcessCompletion.Terminal.Interrupted(interruptionMessage)
            completed -> ProcessCompletion.Exited(process.exitValue())
            else -> ProcessCompletion.Terminal.TimedOut
        }
    }

    private fun completedResult(
        exitCode: Int,
        readers: ProcessReaders,
        outputLimitBytes: Int,
    ): GitProcessResult {
        val stdout = readers.stdout.finish()
        val stderr = readers.stderr.finish()
        val readFailure = stdout.failure ?: stderr.failure
        return when {
            readFailure != null -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.OUTPUT_READ_FAILED,
                    message = readFailure,
                    exitCode = exitCode,
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                )
            }

            readers.collector.exceededLimit -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.OUTPUT_LIMIT_EXCEEDED,
                    message = "Git process output exceeded $outputLimitBytes bytes",
                    exitCode = exitCode,
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                )
            }

            exitCode != 0 -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.NON_ZERO_EXIT,
                    message = "Git process exited with code $exitCode",
                    exitCode = exitCode,
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                )
            }

            else -> {
                GitProcessResult.Success(
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                    exitCode = exitCode,
                )
            }
        }
    }

    private fun terminalFailure(
        process: Process,
        completion: ProcessCompletion.Terminal,
        timeout: Duration,
        readers: ProcessReaders,
    ): GitProcessResult.Failure {
        hardKill(process)
        readers.stdout.finish()
        readers.stderr.finish()
        return when (completion) {
            ProcessCompletion.Terminal.TimedOut -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.TIMED_OUT,
                    message = "Git process exceeded ${timeout.toMillis()} ms",
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                )
            }

            is ProcessCompletion.Terminal.Interrupted -> {
                GitProcessResult.Failure(
                    kind = GitFailureKind.INTERRUPTED,
                    message = completion.message,
                    stdout = readers.collector.stdout(),
                    stderr = readers.collector.stderr(),
                )
            }
        }
    }

    private fun hardKill(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        process.destroyForcibly()
        runCatching { process.waitFor(PROCESS_REAP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    private class StreamReader(
        private val stream: InputStream,
        private val collector: BoundedOutputCollector,
        private val isStdout: Boolean,
    ) {
        private val result = AtomicReference<BoundedReadResult?>()
        private lateinit var thread: Thread

        fun start() {
            thread =
                Thread.ofVirtual().start {
                    result.set(readBounded(stream, collector, isStdout))
                }
        }

        fun finish(): BoundedReadResult {
            thread.join(OUTPUT_READER_JOIN_TIMEOUT_MS)
            return result.get() ?: BoundedReadResult("Output reader did not complete")
        }

        private fun readBounded(
            input: InputStream,
            boundedCollector: BoundedOutputCollector,
            stdout: Boolean,
        ): BoundedReadResult =
            runCatching {
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        return@runCatching BoundedReadResult()
                    }
                    if (!boundedCollector.append(buffer, count, stdout)) {
                        input.close()
                        return@runCatching BoundedReadResult()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                BoundedReadResult()
            }.getOrElse { error ->
                BoundedReadResult(failure = error.message ?: error.javaClass.simpleName)
            }
    }

    private data class BoundedReadResult(
        val failure: String? = null,
    )

    private class BoundedOutputCollector(
        private val limit: Int,
    ) {
        private val stdout = ByteArrayOutputStream(limit.coerceAtMost(READ_BUFFER_SIZE))
        private val stderr = ByteArrayOutputStream(limit.coerceAtMost(READ_BUFFER_SIZE))
        private val lock = Any()
        private var outputExceeded = false

        fun append(
            bytes: ByteArray,
            count: Int,
            isStdout: Boolean,
        ): Boolean =
            synchronized(lock) {
                val accepted = count.coerceAtMost(limit - stdout.size() - stderr.size())
                if (accepted > 0) {
                    if (isStdout) {
                        stdout.write(bytes, 0, accepted)
                    } else {
                        stderr.write(bytes, 0, accepted)
                    }
                }
                if (accepted < count) {
                    outputExceeded = true
                    false
                } else {
                    true
                }
            }

        fun stdout(): String = synchronized(lock) { stdout.toString(StandardCharsets.UTF_8) }

        fun stderr(): String = synchronized(lock) { stderr.toString(StandardCharsets.UTF_8) }

        val exceededLimit: Boolean
            get() = synchronized(lock) { outputExceeded }
    }

    private data class ProcessReaders(
        val stdout: StreamReader,
        val stderr: StreamReader,
        val collector: BoundedOutputCollector,
    )

    private sealed interface ProcessStart {
        data class Started(
            val process: Process,
        ) : ProcessStart

        data class Failed(
            val message: String,
        ) : ProcessStart
    }

    private sealed interface ProcessCompletion {
        data class Exited(
            val exitCode: Int,
        ) : ProcessCompletion

        sealed interface Terminal : ProcessCompletion {
            data class Interrupted(
                val message: String,
            ) : Terminal

            data object TimedOut : Terminal
        }
    }

    private const val READ_BUFFER_SIZE: Int = 8_192
    private const val OUTPUT_READER_JOIN_TIMEOUT_MS: Long = 1_000
    private const val PROCESS_REAP_TIMEOUT_SECONDS: Long = 1
}

/**
 * Git diff hunk parser extracting modified line numbers.
 */
public object GitDiffParser {
    public fun extractChangedLines(
        sourceFile: Path,
        gitRef: String? = null,
        staged: Boolean = false,
    ): List<Int> =
        when (val result = diffResult(sourceFile, gitRef, staged)) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Failure -> emptyList()
        }

    internal fun diffResult(
        sourceFile: Path,
        gitRef: String? = null,
        staged: Boolean = false,
        options: GitProcessOptions = GitProcessOptions(),
    ): GitOperationResult<List<Int>> {
        if (gitRef != null && !GitOutputParser.isSafeRef(gitRef)) {
            return GitOperationResult.Failure(
                kind = GitFailureKind.INVALID_REFERENCE,
                message = "Invalid Git ref: $gitRef",
            )
        }
        val command =
            buildList {
                add("git")
                add("diff")
                add("-U0")
                if (staged) {
                    add("--cached")
                }
                add("--end-of-options")
                if (!staged && gitRef != null) {
                    add(gitRef)
                }
                add("--")
                add(sourceFile.toAbsolutePath().toString())
            }
        val processResult =
            options.runner.run(
                command,
                sourceFile.parent ?: Path.of("."),
                options.timeout,
                options.outputLimitBytes,
            )
        return when (processResult) {
            is GitProcessResult.Failure -> GitOutputParser.operationFailure(processResult)
            is GitProcessResult.Success -> GitOutputParser.hunkLines(processResult.stdout)
        }
    }

    public fun parseHunkLines(diffOutput: String): List<Int> =
        when (val result = GitOutputParser.hunkLines(diffOutput)) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Failure -> emptyList()
        }

    public fun parseStagedKotlinFileNames(gitOutput: String): List<String> =
        gitOutput
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(".kt") && !it.endsWith(".kts") }

    public fun extractStagedKotlinFiles(workingDir: File = File(".")): List<Path> =
        when (val result = stagedKotlinFilesResult(workingDir)) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Failure -> emptyList()
        }

    internal fun stagedKotlinFilesResult(
        workingDir: File = File("."),
        options: GitProcessOptions = GitProcessOptions(),
    ): GitOperationResult<List<Path>> {
        val command =
            listOf(
                "git",
                "diff",
                "--cached",
                "--name-only",
                "-z",
                "--end-of-options",
                "--",
            )
        val processResult =
            options.runner.run(
                command,
                workingDir.toPath(),
                options.timeout,
                options.outputLimitBytes,
            )
        return when (processResult) {
            is GitProcessResult.Failure -> GitOutputParser.operationFailure(processResult)
            is GitProcessResult.Success -> GitOutputParser.stagedKotlinFiles(processResult.stdout, workingDir.toPath())
        }
    }
}

internal object GitOutputParser {
    fun hunkLines(diffOutput: String): GitOperationResult<List<Int>> {
        val lines = mutableListOf<Int>()
        for (line in diffOutput.lineSequence()) {
            if (!line.startsWith("@@")) {
                continue
            }
            val hunk = parseHunk(line)
            if (hunk == null) {
                return malformedHunk(line)
            }
            for (number in hunk.start until hunk.start + hunk.count) {
                lines += number
            }
        }
        return GitOperationResult.Success(lines.distinct().sorted())
    }

    fun stagedKotlinFiles(
        gitOutput: String,
        workingDir: Path,
    ): GitOperationResult<List<Path>> {
        val names = parseStagedNames(gitOutput)
        return when {
            gitOutput.isEmpty() -> {
                GitOperationResult.Success(emptyList())
            }

            names == null -> {
                malformedStagedOutput()
            }

            else -> {
                GitOperationResult.Success(
                    names
                        .filter { it.endsWith(".kt") && !it.endsWith(".kts") }
                        .map { workingDir.resolve(it) },
                )
            }
        }
    }

    fun isSafeRef(gitRef: String): Boolean =
        gitRef.isNotEmpty() &&
            !gitRef.startsWith('-') &&
            gitRef.none { it == '\u0000' || it == '\n' || it == '\r' }

    fun operationFailure(failure: GitProcessResult.Failure): GitOperationResult.Failure =
        GitOperationResult.Failure(
            kind = failure.kind,
            message = failure.message,
            exitCode = failure.exitCode,
            stderr = failure.stderr,
        )

    private fun malformedHunk(line: String): GitOperationResult.Failure =
        GitOperationResult.Failure(
            kind = GitFailureKind.MALFORMED_OUTPUT,
            message = "Malformed Git diff hunk header: $line",
        )

    private fun malformedStagedOutput(): GitOperationResult.Failure =
        GitOperationResult.Failure(
            kind = GitFailureKind.MALFORMED_OUTPUT,
            message = "Malformed staged file output",
        )

    private fun parseStagedNames(gitOutput: String): List<String>? =
        if (gitOutput.isEmpty() || !gitOutput.endsWith('\u0000')) {
            null
        } else {
            gitOutput.removeSuffix("\u0000").split('\u0000').takeUnless { it.any(String::isEmpty) }
        }

    private fun parseHunk(line: String): Hunk? {
        val tokens = line.split(' ', limit = HUNK_TOKEN_LIMIT)
        val oldRange =
            if (tokens.getOrNull(0) == "@@" && tokens.getOrNull(HUNK_CLOSING_TOKEN_INDEX) == "@@") {
                parseRange(tokens[1], '-')
            } else {
                null
            }
        val newRange = if (oldRange != null) parseRange(tokens[2], '+') else null
        return when {
            tokens.size < HUNK_REQUIRED_TOKEN_COUNT -> null
            newRange == null || oldRange == null || oldRange.first < 0 || newRange.first < 0 -> null
            else -> Hunk(newRange.first, newRange.second - newRange.first)
        }
    }

    private fun parseRange(
        value: String,
        marker: Char,
    ): Pair<Int, Int>? {
        val parts = value.takeIf { it.startsWith(marker) }?.drop(1)?.split(',', limit = RANGE_PART_LIMIT)
        val start = parts?.getOrNull(0)?.toIntOrNull()
        val count = parts?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_HUNK_COUNT
        return when {
            start == null -> null
            count < 0 || start.toLong() + count > Int.MAX_VALUE -> null
            else -> start to (start.toLong() + count).toInt()
        }
    }

    private data class Hunk(
        val start: Int,
        val count: Int,
    )

    private const val HUNK_TOKEN_LIMIT: Int = 5
    private const val HUNK_REQUIRED_TOKEN_COUNT: Int = 4
    private const val HUNK_CLOSING_TOKEN_INDEX: Int = 3
    private const val RANGE_PART_LIMIT: Int = 2
    private const val DEFAULT_HUNK_COUNT: Int = 1
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
                .filter { it.status == MutantStatus.SURVIVED }
                .map { res ->
                    val m = res.mutant
                    buildJsonObject {
                        put("ruleId", m.mutatorName)
                        put("level", "warning")
                        putJsonObject("message") {
                            val msg = "Surviving Mutant: Replaced '${m.originalText}' with '${m.replacementText}' (line ${m.line})"
                            put("text", msg)
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
                .filter { it.status == MutantStatus.SURVIVED }
                .map { res ->
                    val m = res.mutant
                    val path = m.filePath ?: sourceFilePath
                    val description =
                        "Surviving mutation (${m.mutatorName}): replaced '${m.originalText}' with '${m.replacementText}'. " +
                            "Verify test coverage for this condition."
                    val fingerprint = "$path:${m.line}:${m.mutatorName}:${m.id}".hashCode().toString()

                    buildJsonObject {
                        put("type", "issue")
                        put("check_name", "KronenbergMutationCheck")
                        put("description", description)
                        put(
                            "content",
                            buildJsonObject {
                                put(
                                    "body",
                                    "Mutant ID: ${m.id}\nMutator: ${m.mutatorName}\nOriginal:\n${m.originalText}\nReplacement:\n${m.replacementText}",
                                )
                            },
                        )
                        putJsonArray("categories") {
                            add("Bug Risk")
                        }
                        putJsonObject("location") {
                            put("path", path)
                            putJsonObject("lines") {
                                put("begin", m.line)
                                put("end", m.line)
                            }
                        }
                        putJsonObject("remediation_points") {
                            put("cost", 50000)
                        }
                        put("severity", "minor")
                        put("fingerprint", fingerprint)
                    }
                }

        targetFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(targetFile, json.encodeToString(JsonArray(issues)))
    }
}
