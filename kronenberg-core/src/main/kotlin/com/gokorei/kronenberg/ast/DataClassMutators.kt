package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Mutates data class copy() invocations by dropping override arguments.
 */
public class DataClassCopyMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "DataClassCopyMutator"
    override val category: MutatorCategory = MutatorCategory.EXTREME
    override val description: String = "Mutates data class copy() calls by stripping parameter overrides"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val callee = element.typedCalleeName() ?: return false
        if (callee != "copy") return false
        val argList = element.valueArgumentList ?: return false
        return argList.arguments.isNotEmpty()
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val argList = element.valueArgumentList ?: return emptyList()
        val args = argList.arguments
        if (args.isEmpty()) return emptyList()

        val edits = mutableListOf<AstEdit>()

        // 1. Strip all arguments
        edits.add(
            context.edit(
                target = argList,
                replacement = "()",
                description = "Stripped all arguments in copy() invocation",
                originalText = argList.text,
            ),
        )

        // 2. If multiple arguments, drop individual arguments one by one
        if (args.size > 1) {
            for (i in args.indices) {
                val remainingArgs = args.filterIndexed { index, _ -> index != i }.joinToString(", ") { it.text }
                val rep = "($remainingArgs)"
                edits.add(
                    context.edit(
                        target = argList,
                        replacement = rep,
                        description = "Dropped argument '${args[i].text}' from copy() call",
                        originalText = argList.text,
                    ),
                )
            }
        }

        return edits
    }
}

/**
 * Mutates destructuring declarations by swapping the first two assigned variable entries.
 */
public class DestructuringMutator : TypedAstMutator<KtDestructuringDeclaration>(KtDestructuringDeclaration::class) {
    override val name: String = "DestructuringMutator"
    override val category: MutatorCategory = MutatorCategory.EXTREME
    override val description: String = "Mutates destructuring declarations by swapping positional variable bindings"

    override fun canMutateTyped(element: KtDestructuringDeclaration): Boolean = element.entries.size >= 2

    @Suppress("ComplexCondition")
    override fun mutateTyped(
        element: KtDestructuringDeclaration,
        context: MutationContext,
    ): List<AstEdit> {
        val entries = element.entries
        val first = entries.getOrNull(0)
        val second = entries.getOrNull(1)
        val leftParenthesis = element.lPar
        val rightParenthesis = element.rPar
        if (first == null || second == null || leftParenthesis == null || rightParenthesis == null) {
            return emptyList()
        }
        val startOffset = leftParenthesis.textRange.startOffset
        val endOffset = rightParenthesis.textRange.endOffset
        val entryEdits =
            listOf(
                PsiSourceEdit(first.textRange.startOffset, first.textRange.endOffset, second.text),
                PsiSourceEdit(second.textRange.startOffset, second.textRange.endOffset, first.text),
            )
        val replacement = PsiSourceRenderer.render(element, startOffset, endOffset, entryEdits)
        val originalText = PsiSourceRenderer.render(element, startOffset, endOffset, emptyList())
        val (line, column) = context.lineAndCol(startOffset)
        return listOf(
            AstEdit(
                startOffset = startOffset,
                endOffset = endOffset,
                replacement = replacement,
                originalText = originalText,
                description = "Swapped destructuring entries '${first.text}' and '${second.text}'",
                line = line,
                column = column,
                filePath = context.filePath,
            ),
        )
    }
}
