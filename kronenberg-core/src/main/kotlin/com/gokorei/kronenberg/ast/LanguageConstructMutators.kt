package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Mutates null safety expressions (a ?: b -> b).
 */
public class NullSafetyMutator : AstMutator {
    override val name: String = "NullSafetyMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> default b)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        return element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.right != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val right = binaryExpr.right ?: return emptyList()
        val range = binaryExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = right.text,
                originalText = binaryExpr.text,
                description = "Replaced elvis '${binaryExpr.text}' with default '${right.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates elvis expressions (a ?: b -> fallback-bypassing left value a).
 */
public class ElvisLeftHandMutator : AstMutator {
    override val name: String = "ElvisLeftHandMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> left-hand a)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        return element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.left != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val left = binaryExpr.left ?: return emptyList()
        val range = binaryExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = left.text,
                originalText = binaryExpr.text,
                description = "Replaced elvis '${binaryExpr.text}' with left-hand '${left.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates non-null assertion expressions (s!! -> s).
 */
public class NonNullAssertionMutator : AstMutator {
    override val name: String = "NonNullAssertionMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates non-null assertions (s!! -> s)"

    override fun canMutate(element: PsiElement): Boolean =
        element is KtPostfixExpression && element.operationToken == KtTokens.EXCLEXCL && element.baseExpression != null

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val postfixExpr = element as? KtPostfixExpression ?: return emptyList()
        val base = postfixExpr.baseExpression ?: return emptyList()
        val range = postfixExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = base.text,
                originalText = postfixExpr.text,
                description = "Removed non-null assertion '${postfixExpr.text}' -> '${base.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates safe-calls (a?.b -> a!!.b).
 */
public class SafeCallMutator : AstMutator {
    override val name: String = "SafeCallMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates safe call operator (?. -> !!)"

    override fun canMutate(element: PsiElement): Boolean = element is KtSafeQualifiedExpression

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val safeExpr = element as? KtSafeQualifiedExpression ?: return emptyList()
        val receiver = safeExpr.receiverExpression
        val selector = safeExpr.selectorExpression ?: return emptyList()
        val range = safeExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        val rep = "${receiver.text}!!.${selector.text}"

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = safeExpr.text,
                description = "Mutated safe call '${safeExpr.text}' to '$rep'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates smart casts and type checks (x is Type <-> x !is Type, x as Type <-> x as? Type).
 */
public class SmartCastMutator : AstMutator {
    override val name: String = "SmartCastMutator"
    override val category: MutatorCategory = MutatorCategory.EXTREME
    override val description: String = "Mutates type checks and casts (is <-> !is, as <-> as?)"

    override fun canMutate(element: PsiElement): Boolean = element is KtIsExpression || element is KtBinaryExpressionWithTypeRHS

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (element is KtIsExpression) {
            val opRef = element.operationReference
            val isNegated = element.isNegated
            val rep = if (isNegated) "is" else "!is"
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated type check '${opRef.text}' to '$rep'",
                    line = line,
                    column = col,
                ),
            )
        }

        if (element is KtBinaryExpressionWithTypeRHS) {
            val opRef = element.operationReference
            val sign = opRef.text
            val rep = if (sign == "as") "as?" else "as"
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated cast operator '$sign' to '$rep'",
                    line = line,
                    column = col,
                ),
            )
        }

        return emptyList()
    }
}

/**
 * Mutates range expressions (until <-> .., downTo <-> .., ..< <-> ..).
 */
public class RangeOperatorMutator : AstMutator {
    override val name: String = "RangeOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.RANGE_OPERATOR
    override val description: String = "Mutates range expressions (0 until n <-> 0..n, downTo <-> .., 0..<n <-> 0..n)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.text
        return sign == "until" ||
            sign == "downTo" ||
            sign == "..<" ||
            element.operationReference.operationSignTokenType == KtTokens.RANGE ||
            element.operationReference.operationSignTokenType == KtTokens.RANGE_UNTIL
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = binaryExpr.operationReference
        val sign = opRef.text
        val replacement = if (sign == "until" || sign == "downTo" || sign == "..<") ".." else "until"
        val range = opRef.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = binaryExpr.text,
                description = "Mutated range operator '$sign' to '$replacement'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Modifies numeric constant literals (Int, Double, Float, Long).
 */
public class LiteralMutationMutator : AstMutator {
    override val name: String = "LiteralMutationMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates numeric constant literals (x -> x+1, x-1)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtConstantExpression) return false
        val text = element.text
        if (text == "true" || text == "false") return false
        return text.toIntOrNull() != null || text.toDoubleOrNull() != null || text.toLongOrNull() != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val constExpr = element as? KtConstantExpression ?: return emptyList()
        val text = constExpr.text
        val range = constExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        val intVal = text.toIntOrNull()
        if (intVal != null) {
            return listOf((intVal + 1).toString(), (intVal - 1).toString()).map { mutatedNum ->
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = mutatedNum,
                    originalText = text,
                    description = "Altered integer constant $text -> $mutatedNum",
                    line = line,
                    column = col,
                )
            }
        }

        val doubleVal = text.toDoubleOrNull()
        if (doubleVal != null && !text.contains("f") && !text.contains("F") && !text.contains("L")) {
            val mutatedNum = (doubleVal + 1.0).toString()
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = mutatedNum,
                    originalText = text,
                    description = "Altered double constant $text -> $mutatedNum",
                    line = line,
                    column = col,
                ),
            )
        }

        return emptyList()
    }
}

/**
 * Mutates string template expressions by removing or emptying individual interpolated entries.
 */
public class StringTemplateMutator : AstMutator {
    override val name: String = "StringTemplateMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates interpolated expressions within string templates"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtStringTemplateExpression) return false
        return element.entries.any { it !is KtLiteralStringTemplateEntry }
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val strExpr = element as? KtStringTemplateExpression ?: return emptyList()
        val edits = mutableListOf<AstEdit>()

        for (entry in strExpr.entries) {
            if (entry !is KtLiteralStringTemplateEntry) {
                val range = entry.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                edits.add(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = "",
                        originalText = entry.text,
                        description = "Omitted string template interpolation '${entry.text}'",
                        line = line,
                        column = col,
                    ),
                )
            }
        }

        return edits
    }
}
