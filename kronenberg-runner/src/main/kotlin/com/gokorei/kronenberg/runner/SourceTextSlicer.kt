package com.gokorei.kronenberg.runner

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Range-based source text slicing used to reassemble Kotlin snippets.
 *
 * Every slice is expressed as a PSI text range removal so that everything outside the requested
 * ranges survives verbatim, including leading comments, file-level annotations, and doc comments.
 */
internal object SourceTextSlicer {
    /**
     * Returns [source] without its package directive and import list.
     */
    fun stripPackageAndImports(
        source: String,
        file: KtFile,
    ): String =
        stripRanges(
            source,
            listOfNotNull(
                file.packageDirective?.takeIf { it.text.isNotBlank() }?.textRange,
                file.importList?.takeIf { it.text.isNotBlank() }?.textRange,
            ),
        )

    /**
     * Returns the declaration body of [file] without the package directive, import list, file-level
     * annotations, and any declaration in [removedDeclarations].
     */
    fun bodyExcluding(
        source: String,
        file: KtFile,
        removedDeclarations: List<KtNamedFunction>,
    ): String =
        stripRanges(
            source,
            buildList {
                file.packageDirective?.takeIf { it.text.isNotBlank() }?.let { add(it.textRange) }
                file.importList?.takeIf { it.text.isNotBlank() }?.let { add(it.textRange) }
                file.annotationEntries.forEach { add(it.textRange) }
                removedDeclarations.forEach { add(TextRange(declarationStartOffset(it), it.textRange.endOffset)) }
            },
        )

    /**
     * Returns the inclusive 1-indexed line range [declaration] occupies in [source], measured from the
     * first declaration token so that leading comments stay outside the range.
     */
    fun lineRangeOf(
        source: String,
        declaration: KtNamedFunction,
    ): IntRange {
        val start = declarationStartOffset(declaration)
        val end = declaration.textRange.endOffset.coerceIn(start, source.length)
        return CallGraphReachability
            .computeLineAndColumn(
                source,
                start,
            ).first..CallGraphReachability.computeLineAndColumn(source, end).first
    }

    private fun stripRanges(
        source: String,
        ranges: List<TextRange>,
    ): String {
        if (ranges.isEmpty()) return source.trim()

        var result = source
        for (range in ranges.sortedByDescending { it.startOffset }) {
            val start = range.startOffset.coerceIn(0, result.length)
            val end = range.endOffset.coerceIn(start, result.length)
            if (start < end) {
                result = result.substring(0, start) + result.substring(end)
            }
        }
        return result.trim()
    }

    private fun declarationStartOffset(function: KtNamedFunction): Int =
        function.node
            .getChildren(null)
            .firstOrNull { it.psi !is PsiComment && it.text.isNotBlank() }
            ?.startOffset
            ?: function.textRange.startOffset
}
