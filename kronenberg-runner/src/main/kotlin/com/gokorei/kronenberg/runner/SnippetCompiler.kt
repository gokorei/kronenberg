package com.gokorei.kronenberg.runner

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Diagnostic emitted by worker-isolated K2 compilation.
 */
public data class CompilerDiagnostic(
    val severity: String,
    val line: Int?,
    val column: Int?,
    val message: String,
)

/**
 * Structured outcome of worker-isolated snippet compilation.
 */
public sealed class CompileResult {
    public data class Compiled(
        val outDir: Path,
        val diagnostics: List<CompilerDiagnostic> = emptyList(),
        val tempRoot: Path,
    ) : CompileResult()

    public data class Failed(
        val message: String,
        val diagnostics: List<CompilerDiagnostic> = emptyList(),
    ) : CompileResult()

    public data class TimedOut(
        val message: String,
        val diagnostics: List<CompilerDiagnostic> = emptyList(),
    ) : CompileResult()
}

/**
 * Worker-isolated Kotlin K2 compiler contract.
 */
public interface SnippetCompiler {
    /**
     * Compiles Kotlin source text into bytecode at a target directory in a killable worker process.
     */
    public fun compile(
        sourceCode: String,
        extraClasspath: List<String> = emptyList(),
        timeoutMs: Long = 30_000L,
    ): CompileResult

    /**
     * Cleans up temporary artifacts from a compilation pass.
     */
    public fun cleanup(result: CompileResult)
}

/**
 * Default worker-isolated compiler implementation using embedded K2 compiler.
 */
public class DefaultSnippetCompiler : SnippetCompiler {
    public companion object {
        public const val SOURCE_FILE_NAME: String = "Snippet.kt"
        public const val MAIN_CLASS: String = "SnippetKt"

        private val defaultClasspath: String by lazy {
            val cp = System.getProperty("java.class.path").orEmpty()
            cp
                .split(File.pathSeparator)
                .filter { entry ->
                    val lower = entry.lowercase()
                    lower.contains("kotlin-stdlib") ||
                        lower.contains("kotlinx-coroutines") ||
                        lower.contains("kotlinx-serialization") ||
                        lower.contains("junit") ||
                        lower.contains("kotest") ||
                        lower.contains("kronenberg")
                }.joinToString(File.pathSeparator)
        }
    }

    override fun compile(
        sourceCode: String,
        extraClasspath: List<String>,
        timeoutMs: Long,
    ): CompileResult {
        val tempDir: Path
        val sourceFile: Path
        val outDir: Path
        try {
            tempDir = Files.createTempDirectory("kronenberg-compile")
            sourceFile = tempDir.resolve(SOURCE_FILE_NAME)
            outDir = tempDir.resolve("out")
            Files.createDirectories(outDir)
            Files.writeString(sourceFile, sourceCode)
        } catch (e: Exception) {
            return CompileResult.Failed("Failed to prepare snippet for compilation: ${e.message}")
        }

        val requestFile = tempDir.resolve("request.properties")
        val responseFile = tempDir.resolve("response.properties")
        val request =
            java.util.Properties().apply {
                setProperty("mode", "compile")
                setProperty("sourceFile", sourceFile.toString())
                setProperty("outDir", outDir.toString())
                setProperty("tempRoot", tempDir.toString())
                setProperty("responseFile", responseFile.toString())
                extraClasspath.filter { it.isNotBlank() }.forEachIndexed { index, entry ->
                    setProperty("extraClasspath.$index", entry)
                }
            }
        return try {
            Files.newOutputStream(requestFile).use { request.store(it, null) }
            when (val workerResult = runWorkerProcess(requestFile, responseFile, timeoutMs)) {
                is WorkerProcessResult.TimedOut -> {
                    CompileResult.TimedOut(
                        "Compilation timed out after ${timeoutMs.coerceAtLeast(1L)}ms; worker terminated",
                    )
                }

                is WorkerProcessResult.Completed -> {
                    compileResultFromResponse(workerResult.response, tempDir, workerResult.exitCode)
                }
            }
        } catch (e: Throwable) {
            CompileResult.Failed("Failed to execute compilation worker: ${e.message}")
        }.also { result ->
            if (result !is CompileResult.Compiled) {
                runCatching { tempDir.toFile().deleteRecursively() }
            }
        }
    }

    internal fun compileInProcess(
        sourceFile: Path,
        outDir: Path,
        tempRoot: Path,
        extraClasspath: List<String>,
    ): CompileResult {
        val effectiveClasspath =
            (extraClasspath + listOf(defaultClasspath))
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
        val args =
            K2JVMCompilerArguments().apply {
                destination = outDir.toString()
                classpath = effectiveClasspath
                freeArgs = listOf(sourceFile.toString())
                jvmTarget = resolveTargetJvmVersion()
            }
        return try {
            val collector = CapturingMessageCollector()
            val compiler = K2JVMCompiler()
            compiler.exec(collector, Services.EMPTY, args)
            val diagnostics =
                collector.reports.map { report ->
                    val loc = report.location
                    CompilerDiagnostic(
                        severity = report.severity,
                        line = loc?.line,
                        column = loc?.column,
                        message = report.message,
                    )
                }
            if (collector.hasErrors()) {
                val errorMessages = diagnostics.filter { it.severity == "error" }.joinToString("; ") { it.message }
                CompileResult.Failed(errorMessages.ifBlank { "Compilation failed with errors" }, diagnostics)
            } else {
                CompileResult.Compiled(outDir, diagnostics, tempRoot)
            }
        } catch (e: Throwable) {
            CompileResult.Failed("Embedded compiler failed to execute: ${e.message}")
        }
    }

    private fun compileResultFromResponse(
        response: java.util.Properties,
        tempRoot: Path,
        exitCode: Int,
    ): CompileResult {
        val diagnostics = response.diagnostics()
        return when (response.getProperty("status")) {
            "compiled" -> {
                val outDir = response.getProperty("outDir")
                if (outDir == null) {
                    CompileResult.Failed("Compilation worker exited without an output directory (code $exitCode)", diagnostics)
                } else {
                    CompileResult.Compiled(Path.of(outDir), diagnostics, tempRoot)
                }
            }

            "failed" -> {
                CompileResult.Failed(
                    response.getProperty("message") ?: "Compilation failed (worker exit code $exitCode)",
                    diagnostics,
                )
            }

            else -> {
                CompileResult.Failed(
                    "Compilation worker returned an invalid result (exit code $exitCode)",
                    diagnostics,
                )
            }
        }
    }

    override fun cleanup(result: CompileResult) {
        if (result is CompileResult.Compiled) {
            runCatching { result.tempRoot.toFile().deleteRecursively() }
        }
    }

    private class CapturingMessageCollector : MessageCollector {
        data class Report(
            val severity: String,
            val message: String,
            val location: CompilerMessageSourceLocation?,
        )

        val reports = mutableListOf<Report>()

        override fun clear(): Unit = reports.clear()

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) {
                reports.add(Report("error", message, location))
            } else if (severity.isWarning) {
                reports.add(Report("warning", message, location))
            }
        }

        override fun hasErrors(): Boolean = reports.any { it.severity == "error" }
    }

    private fun resolveTargetJvmVersion(): String {
        val javaVer = System.getProperty("java.specification.version") ?: "21"
        val major = javaVer.removePrefix("1.").toIntOrNull() ?: 21
        return if (major in 8..21) major.toString() else "21"
    }
}

private fun java.util.Properties.diagnostics(): List<CompilerDiagnostic> {
    val diagnostics = mutableListOf<CompilerDiagnostic>()
    var index = 0
    while (true) {
        val severity = getProperty("diagnostic.$index.severity") ?: break
        diagnostics.add(
            CompilerDiagnostic(
                severity = severity,
                line = getProperty("diagnostic.$index.line")?.toIntOrNull(),
                column = getProperty("diagnostic.$index.column")?.toIntOrNull(),
                message = getProperty("diagnostic.$index.message").orEmpty(),
            ),
        )
        index++
    }
    return diagnostics
}
