package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.psi.KtFile

public data class PsiSourceEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
)

public enum class PsiReplacementFailure {
    INVALID_RANGE,
    NON_PSI_BOUNDARY,
    OVERLAPPING_RANGES,
}

public sealed interface PsiReplacementResult {
    public data class Applied(
        val source: String,
    ) : PsiReplacementResult

    public data class Rejected(
        val edit: PsiSourceEdit,
        val reason: PsiReplacementFailure,
    ) : PsiReplacementResult
}

public interface PsiSourceEditor {
    public fun replace(
        source: String,
        edits: List<PsiSourceEdit>,
    ): PsiReplacementResult

    public fun remove(
        source: String,
        ranges: List<Pair<Int, Int>>,
    ): PsiReplacementResult
}

@Suppress("ReturnCount")
public object DefaultPsiSourceEditor : PsiSourceEditor {
    override fun replace(
        source: String,
        edits: List<PsiSourceEdit>,
    ): PsiReplacementResult {
        if (edits.isEmpty()) return PsiReplacementResult.Applied(source)
        val orderedEdits =
            edits.sortedWith(
                compareByDescending<PsiSourceEdit> { it.startOffset }.thenByDescending { it.endOffset },
            )
        validate(orderedEdits)?.let { failure -> return rejected(failure.first, failure.second) }
        val file = K2SnippetFrontend.parsePsi(source)
        for (edit in orderedEdits) {
            if (!hasPsiBoundaries(file, edit.startOffset, edit.endOffset)) {
                return rejected(edit, PsiReplacementFailure.NON_PSI_BOUNDARY)
            }
        }
        val rendered =
            PsiSourceRenderer.render(
                owner = file,
                startOffset = 0,
                endOffset = file.textLength,
                edits = orderedEdits,
            )
        return PsiReplacementResult.Applied(rendered)
    }

    override fun remove(
        source: String,
        ranges: List<Pair<Int, Int>>,
    ): PsiReplacementResult = replace(source, ranges.map { (start, end) -> PsiSourceEdit(start, end, "") })

    private fun validate(orderedEdits: List<PsiSourceEdit>): Pair<PsiSourceEdit, PsiReplacementFailure>? {
        for (index in orderedEdits.indices) {
            val edit = orderedEdits[index]
            if (edit.startOffset < 0 || edit.endOffset <= edit.startOffset) {
                return edit to PsiReplacementFailure.INVALID_RANGE
            }
            if (index > 0 && orderedEdits[index - 1].startOffset < edit.endOffset) {
                return edit to PsiReplacementFailure.OVERLAPPING_RANGES
            }
        }
        return null
    }

    private fun hasPsiBoundaries(
        file: KtFile,
        start: Int,
        end: Int,
    ): Boolean {
        if (end > file.textLength) return false
        val startElement = file.findElementAt(start) ?: return false
        val endElement = file.findElementAt(end - 1) ?: return false
        return startElement.textRange.startOffset == start && endElement.textRange.endOffset == end
    }

    private fun rejected(
        edit: PsiSourceEdit,
        reason: PsiReplacementFailure,
    ): PsiReplacementResult = PsiReplacementResult.Rejected(edit, reason)
}

internal object PsiSourceRenderer {
    @Suppress("ComplexCondition")
    fun render(
        owner: PsiElement,
        startOffset: Int,
        endOffset: Int,
        edits: List<PsiSourceEdit>,
    ): String {
        val leaves = mutableListOf<ASTNode>()
        collectLeaves(owner.node, leaves)
        val orderedEdits = edits.sortedBy { it.startOffset }
        val output = StringBuilder()
        var editIndex = 0
        for (leaf in leaves) {
            val leafStart = leaf.startOffset
            val leafEnd = leaf.textRange.endOffset
            if (leafEnd <= startOffset || leafStart >= endOffset) continue
            while (editIndex < orderedEdits.size && orderedEdits[editIndex].endOffset <= leafStart) {
                editIndex++
            }
            val edit = orderedEdits.getOrNull(editIndex)
            if (edit != null && edit.startOffset >= startOffset && edit.endOffset <= endOffset &&
                leafStart >= edit.startOffset && leafEnd <= edit.endOffset
            ) {
                if (leafStart == edit.startOffset) output.append(edit.replacement)
            } else {
                output.append(leaf.text)
            }
        }
        return output.toString()
    }

    private fun collectLeaves(
        node: ASTNode,
        output: MutableList<ASTNode>,
    ) {
        val children = node.getChildren(TokenSet.ANY)
        if (children.isEmpty()) {
            output.add(node)
            return
        }
        children.forEach { child -> collectLeaves(child, output) }
    }
}
