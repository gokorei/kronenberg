package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * Context provided to AST mutators during PSI traversal.
 */
public data class MutationContext(
    val code: String,
    val file: KtFile,
) {
    public fun lineAndCol(offset: Int): Pair<Int, Int> = computeLineAndColumn(code, offset)

    /**
     * Ergonomic factory method creating an [AstEdit] directly from a target [PsiElement].
     */
    public fun edit(
        target: PsiElement,
        replacement: String,
        description: String,
        originalText: String = target.text,
    ): AstEdit {
        val range = target.textRange
        val (line, col) = lineAndCol(range.startOffset)
        return AstEdit(
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            replacement = replacement,
            originalText = originalText,
            description = description,
            line = line,
            column = col,
        )
    }
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

/**
 * Base class for strongly typed AST mutators targeting a specific [KtElement] subtype,
 * removing boilerplate type checks and manual casts.
 */
public abstract class TypedAstMutator<T : KtElement>(
    private val targetClass: KClass<T>,
) : AstMutator {
    final override fun canMutate(element: PsiElement): Boolean =
        targetClass.isInstance(element) && canMutateTyped(targetClass.cast(element))

    final override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (!targetClass.isInstance(element)) return emptyList()
        return mutateTyped(targetClass.cast(element), context)
    }

    /**
     * Predicate determining if this specific typed element can be mutated.
     */
    protected open fun canMutateTyped(element: T): Boolean = true

    /**
     * Produces discrete AST replacements for the strongly typed element.
     */
    protected abstract fun mutateTyped(
        element: T,
        context: MutationContext,
    ): List<AstEdit>
}
