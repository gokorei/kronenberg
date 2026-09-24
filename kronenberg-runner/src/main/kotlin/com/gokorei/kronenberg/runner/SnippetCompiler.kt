package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Diagnostic emitted by in-process K2 compilation.
 */
public data class CompilerDiagnostic(
    val severity: String,
    val line: Int?,
    val column: Int?,
    val message: String,
)

public enum class EntrypointReceiver {
    STATIC,
    CLASS,
    OBJECT,
    COMPANION,
}

public sealed interface CompilationEntrypoint {
    public data class Resolved(
        val className: String,
        val parameterCount: Int? = null,
        val receiver: EntrypointReceiver = EntrypointReceiver.STATIC,
        val ownerClassName: String? = null,
    ) : CompilationEntrypoint

    public data class Missing(
        val candidateClassNames: List<String> = emptyList(),
    ) : CompilationEntrypoint

    public data class Ambiguous(
        val candidates: List<Resolved>,
    ) : CompilationEntrypoint
}

/**
 * Structured outcome of in-process snippet compilation.
 */
public sealed class CompileResult {
    public data class Compiled(
        val outDir: Path,
        val diagnostics: List<CompilerDiagnostic> = emptyList(),
        val tempRoot: Path,
        val entrypoint: CompilationEntrypoint,
    ) : CompileResult()

    public data class Failed(
        val message: String,
        val diagnostics: List<CompilerDiagnostic> = emptyList(),
    ) : CompileResult()
}

/**
 * In-process Kotlin K2 compiler contract.
 */
public interface SnippetCompiler {
    /**
     * Compiles Kotlin source text into bytecode at a target directory in-process.
     */
    public fun compile(
        sourceCode: String,
        extraClasspath: List<String> = emptyList(),
    ): CompileResult

    /**
     * Cleans up temporary artifacts from a compilation pass.
     */
    public fun cleanup(result: CompileResult)
}

/**
 * Default in-process compiler implementation using embedded K2 compiler.
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
                collector.reports.mapNotNull { report ->
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
                tempDir.toFile().deleteRecursively()
                CompileResult.Failed(errorMessages.ifBlank { "Compilation failed with errors" }, diagnostics)
            } else {
                CompileResult.Compiled(outDir, diagnostics, tempDir, resolveEntrypoint(sourceCode))
            }
        } catch (e: Throwable) {
            tempDir.toFile().deleteRecursively()
            CompileResult.Failed("Embedded compiler failed to execute: ${e.message}")
        }
    }

    override fun cleanup(result: CompileResult) {
        if (result is CompileResult.Compiled) {
            runCatching { result.tempRoot.toFile().deleteRecursively() }
        }
    }

    private fun resolveEntrypoint(sourceCode: String): CompilationEntrypoint {
        val file = K2SnippetFrontend.parsePsi(sourceCode)
        val packageName =
            file.packageDirective
                ?.fqName
                ?.asString()
                .orEmpty()
        val visitor = SourceEntrypointVisitor(packageName)
        file.accept(visitor)
        val candidates =
            visitor.candidates
                .distinct()
                .sortedWith(
                    compareBy<CompilationEntrypoint.Resolved>(
                        { it.className },
                        { it.parameterCount },
                        { it.receiver.name },
                        { it.ownerClassName.orEmpty() },
                    ),
                )
        return when (candidates.size) {
            0 -> CompilationEntrypoint.Missing(visitor.classNames.toList())
            1 -> candidates.single()
            else -> CompilationEntrypoint.Ambiguous(candidates)
        }
    }

    private class SourceEntrypointVisitor(
        private val packageName: String,
    ) : KtTreeVisitorVoid() {
        val candidates = mutableListOf<CompilationEntrypoint.Resolved>()
        val classNames = sortedSetOf<String>()

        override fun visitClass(kClass: KtClass) {
            classNames.add(classBinaryName(kClass, packageName))
            super.visitClass(kClass)
        }

        override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
            classNames.add(classBinaryName(declaration, packageName))
            super.visitObjectDeclaration(declaration)
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            if (function.name == "main" && function.valueParameters.size <= 1) {
                candidates.add(resolveFunction(function))
            }
            super.visitNamedFunction(function)
        }

        private fun resolveFunction(function: KtNamedFunction): CompilationEntrypoint.Resolved {
            val parameterCount = function.valueParameters.size
            val owner = function.getStrictParentOfType<KtClassOrObject>()
            val isStatic = function.annotationEntries.any { it.shortName?.asString() == "JvmStatic" }
            return when {
                owner == null -> {
                    CompilationEntrypoint.Resolved(
                        className = fileFacadeClassName(packageName),
                        parameterCount = parameterCount,
                    )
                }

                owner is KtObjectDeclaration && owner.isCompanion() -> {
                    resolveCompanion(owner, parameterCount, isStatic)
                }

                else -> {
                    val receiver =
                        when {
                            isStatic -> EntrypointReceiver.STATIC
                            owner is KtObjectDeclaration -> EntrypointReceiver.OBJECT
                            else -> EntrypointReceiver.CLASS
                        }
                    CompilationEntrypoint.Resolved(classBinaryName(owner, packageName), parameterCount, receiver)
                }
            }
        }

        private fun resolveCompanion(
            companion: KtObjectDeclaration,
            parameterCount: Int,
            isStatic: Boolean,
        ): CompilationEntrypoint.Resolved {
            val companionClassName = classBinaryName(companion, packageName)
            val ownerClassName = companion.getStrictParentOfType<KtClassOrObject>()?.let { classBinaryName(it, packageName) }
            return when {
                ownerClassName == null -> {
                    CompilationEntrypoint.Resolved(
                        className = companionClassName,
                        parameterCount = parameterCount,
                        receiver = EntrypointReceiver.COMPANION,
                        ownerClassName = companionClassName,
                    )
                }

                isStatic -> {
                    CompilationEntrypoint.Resolved(
                        className = ownerClassName,
                        parameterCount = parameterCount,
                    )
                }

                else -> {
                    CompilationEntrypoint.Resolved(
                        className = companionClassName,
                        parameterCount = parameterCount,
                        receiver = EntrypointReceiver.COMPANION,
                        ownerClassName = ownerClassName,
                    )
                }
            }
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

private fun classBinaryName(
    declaration: KtClassOrObject,
    packageName: String,
): String {
    val names = mutableListOf<String>()
    var current: PsiElement? = declaration
    while (current != null) {
        if (current is KtClassOrObject) {
            current.name?.let { names.add(it) }
        }
        current = current.parent
    }
    val nestedClassName = names.asReversed().joinToString("$")
    return when {
        packageName.isBlank() -> nestedClassName
        nestedClassName.isBlank() -> packageName
        else -> "$packageName.$nestedClassName"
    }
}

private fun fileFacadeClassName(packageName: String): String =
    if (packageName.isBlank()) DefaultSnippetCompiler.MAIN_CLASS else "$packageName.${DefaultSnippetCompiler.MAIN_CLASS}"
