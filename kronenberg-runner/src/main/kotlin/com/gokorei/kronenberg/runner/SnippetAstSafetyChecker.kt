package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Static AST safety inspector using K2 PSI.
 * Analyzes snippet code to detect operations that would terminate or disrupt
 * the host JVM (e.g. System.exit, exitProcess, Runtime.halt, ProcessBuilder, destructive file deletion).
 */
public object SnippetAstSafetyChecker {
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
                    val receiver = expression.receiverExpression.text.trim()
                    val selector =
                        expression.selectorExpression
                            ?.text
                            ?.trim()
                            .orEmpty()

                    if ((receiver == "System" || receiver == "java.lang.System") && selector.startsWith("exit(")) {
                        foundDangerous = true
                    }
                    if (receiver.contains("Runtime") &&
                        (
                            selector.startsWith("halt(") ||
                                selector.startsWith("exit(") ||
                                selector.startsWith("exec(")
                        )
                    ) {
                        foundDangerous = true
                    }
                    if (receiver.contains("ProcessHandle") && selector.startsWith("destroy")) {
                        foundDangerous = true
                    }
                    if ((receiver == "Files" || receiver == "java.nio.file.Files") &&
                        (
                            selector.startsWith("delete(") ||
                                selector.startsWith("deleteIfExists(")
                        )
                    ) {
                        foundDangerous = true
                    }
                    if (selector.startsWith("deleteRecursively(")) {
                        foundDangerous = true
                    }
                    super.visitDotQualifiedExpression(expression)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val calleeName = expression.calleeExpression?.text
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
