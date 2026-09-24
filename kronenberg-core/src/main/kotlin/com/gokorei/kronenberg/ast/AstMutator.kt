package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import kotlin.reflect.KClass
import kotlin.reflect.cast

internal data class EnclosingFunctionRange(
    val name: String,
    val startLine: Int,
    val endLine: Int,
)

public class SourceMetadata internal constructor(
    private val lineStarts: IntArray,
    private val enclosingFunctions: List<EnclosingFunctionRange>,
) {
    public fun lineAndColumn(offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, lineStarts.last())
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= safeOffset) {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        val lineIndex = high.coerceAtLeast(0)
        return Pair(lineIndex + 1, safeOffset - lineStarts[lineIndex] + 1)
    }

    public fun enclosingFunctionName(line: Int): String? =
        enclosingFunctions
            .asSequence()
            .filter { line in it.startLine..it.endLine }
            .minByOrNull { it.endLine - it.startLine }
            ?.name
}

public fun buildSourceMetadata(
    sourceCode: String,
    file: KtFile,
): SourceMetadata {
    val lineStarts = mutableListOf(0)
    sourceCode.forEachIndexed { index, character ->
        if (character == '\n') lineStarts.add(index + 1)
    }
    val ranges = mutableListOf<EnclosingFunctionRange>()
    val metadata =
        SourceMetadata(
            lineStarts = lineStarts.toIntArray(),
            enclosingFunctions = emptyList(),
        )
    file.accept(
        object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                val name = function.name
                if (name != null) {
                    val startLine = metadata.lineAndColumn(function.textRange.startOffset).first
                    val endLine = metadata.lineAndColumn(function.textRange.endOffset).first
                    ranges.add(EnclosingFunctionRange(name, startLine, endLine))
                }
                super.visitNamedFunction(function)
            }
        },
    )
    return SourceMetadata(lineStarts.toIntArray(), ranges)
}

/**
 * Context provided to AST mutators during PSI traversal.
 */
public data class MutationContext(
    val code: String,
    val file: KtFile,
    val filePath: String? = null,
    val metadata: SourceMetadata = buildSourceMetadata(code, file),
) {
    public constructor(
        code: String,
        file: KtFile,
        filePath: String?,
    ) : this(code, file, filePath, buildSourceMetadata(code, file))

    public fun lineAndCol(offset: Int): Pair<Int, Int> = metadata.lineAndColumn(offset)

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
            filePath = filePath,
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
