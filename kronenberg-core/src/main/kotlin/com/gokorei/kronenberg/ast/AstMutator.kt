package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * Context provided to AST mutators during PSI traversal.
 */
public data class MutationContext(
    val code: String,
    val file: KtFile,
) {
    public fun lineAndCol(offset: Int): Pair<Int, Int> = computeLineAndColumn(code, offset)
}

/**
 * Computes 1-indexed line and column coordinates from character offset.
 */
public fun computeLineAndColumn(
    source: String,
    offset: Int,
): Pair<Int, Int> {
    var line = 1
    var lastLineBreak = -1
    val safeOffset = offset.coerceIn(0, source.length)
    for (i in 0 until safeOffset) {
        if (source[i] == '\n') {
            line++
            lastLineBreak = i
        }
    }
    val col = safeOffset - lastLineBreak
    return Pair(line, col)
}

/**
 * Service Provider Interface (SPI) for individual K2 PSI AST mutation rules.
 */
public interface AstMutator {
    /** Unique identifier for this mutator rule. */
    public val name: String

    /** Categorization of this mutator. */
    public val category: MutatorCategory

    /** Human-readable explanation of the mutation rule. */
    public val description: String

    /**
     * Determines whether this mutator can apply transformations to the given PSI element.
     */
    public fun canMutate(element: PsiElement): Boolean

    /**
     * Produces discrete AST replacements for the given element within its file context.
     */
    public fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit>
}
