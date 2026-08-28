package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Static call-graph analysis utilities for inspecting reachability and enclosing functions via K2 PSI.
 */
public object CallGraphReachability {
    /**
     * Extracts all callee function names directly invoked inside the given function body.
     */
    public fun extractCalledFunctionNames(function: KtNamedFunction): Set<String> {
        val calledNames = mutableSetOf<String>()
        function.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    expression.calleeExpression?.text?.let { calledNames.add(it) }
                    super.visitCallExpression(expression)
                }
            },
        )
        return calledNames
    }

    /**
     * Identifies the enclosing top-level or member function name surrounding a given 1-indexed source line.
     */
    public fun findEnclosingFunctionName(
        sourceCode: String,
        line: Int,
    ): String? {
        val psi = K2SnippetFrontend.parsePsi(sourceCode)
        var enclosingName: String? = null
        psi.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    val range = function.textRange
                    val (startLine, _) = computeLineAndColumn(sourceCode, range.startOffset)
                    val (endLine, _) = computeLineAndColumn(sourceCode, range.endOffset)
                    if (line in startLine..endLine) {
                        enclosingName = function.name
                    }
                    super.visitNamedFunction(function)
                }
            },
        )
        return enclosingName
    }

    /**
     * Computes 1-indexed (line, col) coordinates from character offset.
     */
    public fun computeLineAndColumn(
        source: String,
        offset: Int,
    ): Pair<Int, Int> {
        var line = 1
        var lastLineBreak = -1
        for (i in 0 until offset.coerceAtMost(source.length)) {
            if (source[i] == '\n') {
                line++
                lastLineBreak = i
            }
        }
        val col = offset - lastLineBreak
        return Pair(line, col)
    }
}
