package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

private fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

private fun KtExpression.receiverName(): String? = (this as? KtNameReferenceExpression)?.getReferencedName()

private fun KtExpression.isSystemClass(): Boolean = receiverName() == "System" || qualifiedName() == "java.lang.System"

private fun KtExpression.qualifiedName(): String? =
    when (this) {
        is KtNameReferenceExpression -> {
            getReferencedName()
        }

        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression.qualifiedName()
            val selector = (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (receiver != null && selector != null) "$receiver.$selector" else selector
        }

        else -> {
            null
        }
    }

private fun KtExpression.isRuntimeInstance(): Boolean =
    when {
        receiverName() == "Runtime" -> {
            true
        }

        this is KtDotQualifiedExpression -> {
            val call = selectorExpression as? KtCallExpression
            call?.calleeName() == "getRuntime" && receiverExpression.receiverName() == "Runtime"
        }

        this is KtCallExpression && calleeName() == "getRuntime" -> {
            (parent as? KtDotQualifiedExpression)?.receiverExpression?.isRuntimeInstance() == true
        }

        else -> {
            false
        }
    }

private fun KtExpression.isProcessHandleInstance(): Boolean = receiverName() == "ProcessHandle"

private fun KtExpression.isFilesClass(): Boolean = receiverName() == "Files" || qualifiedName() == "java.nio.file.Files"

/**
 * Static AST safety inspector using K2 PSI.
 * Analyzes snippet code to detect operations that would terminate or disrupt
 * the host JVM (e.g. System.exit, exitProcess, Runtime.halt, ProcessBuilder, destructive file deletion).
 */
public object SnippetAstSafetyChecker {
    private val RUNTIME_TERMINAL_METHODS = setOf("halt", "exit", "exec")
    private val PROCESS_DESTRUCTIVE_METHODS = setOf("destroy", "destroyAll", "destroyForcibly")
    private val FILES_DESTRUCTIVE_METHODS = setOf("delete", "deleteIfExists")

    /**
     * Returns true if the code contains dangerous calls capable of killing or corrupting the host JVM.
     */
    public fun containsHostTerminatingCalls(code: String): Boolean {
        if (code.isBlank()) return false
        val psi = K2SnippetFrontend.parsePsi(code)
        var foundDangerous = false

        val imports = psi.importDirectives
        val exitProcessAliases = mutableSetOf("exitProcess")
        val directExitAliases = mutableSetOf("exit")
        val processBuilderAliases = mutableSetOf("ProcessBuilder")

        for (imp in imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            val alias = imp.aliasName
            when (fqn) {
                "kotlin.system.exitProcess" -> {
                    if (alias != null) {
                        exitProcessAliases.add(alias)
                    } else {
                        exitProcessAliases.add("exitProcess")
                    }
                }

                "java.lang.System.exit" -> {
                    if (alias != null) {
                        directExitAliases.add(alias)
                    } else {
                        directExitAliases.add("exit")
                    }
                }

                "java.lang.ProcessBuilder" -> {
                    if (alias != null) {
                        processBuilderAliases.add(alias)
                    } else {
                        processBuilderAliases.add("ProcessBuilder")
                    }
                }
            }
        }

        psi.accept(
            object : KtTreeVisitorVoid() {
                override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                    val receiver = expression.receiverExpression
                    val selector = expression.selectorExpression as? KtCallExpression
                    val method = selector?.calleeName()

                    if (receiver.isSystemClass() && method == "exit") {
                        foundDangerous = true
                    }
                    if (receiver.isRuntimeInstance() && method in RUNTIME_TERMINAL_METHODS) {
                        foundDangerous = true
                    }
                    if (receiver.isProcessHandleInstance() && method in PROCESS_DESTRUCTIVE_METHODS) {
                        foundDangerous = true
                    }
                    if (receiver.isFilesClass() && method in FILES_DESTRUCTIVE_METHODS) {
                        foundDangerous = true
                    }
                    super.visitDotQualifiedExpression(expression)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val calleeName = expression.calleeName()
                    if (calleeName in exitProcessAliases ||
                        calleeName in directExitAliases ||
                        calleeName in processBuilderAliases ||
                        calleeName == "deleteRecursively"
                    ) {
                        foundDangerous = true
                    }
                    super.visitCallExpression(expression)
                }
            },
        )

        return foundDangerous
    }
}
