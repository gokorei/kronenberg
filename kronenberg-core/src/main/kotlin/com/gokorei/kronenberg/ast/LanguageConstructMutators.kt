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
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Mutates null safety expressions (a ?: b -> b).
 */
public class NullSafetyMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "NullSafetyMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> default b)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean =
        element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.right != null

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val right = element.right ?: return emptyList()
        return listOf(
            context.edit(element, right.text, "Replaced elvis '${element.text}' with default '${right.text}'"),
        )
    }
}

/**
 * Mutates elvis expressions (a ?: b -> fallback-bypassing left value a).
 */
public class ElvisLeftHandMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "ElvisLeftHandMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> left-hand a)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean =
        element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.left != null

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val left = element.left ?: return emptyList()
        return listOf(
            context.edit(element, left.text, "Replaced elvis '${element.text}' with left-hand '${left.text}'"),
        )
    }
}

/**
 * Mutates non-null assertion expressions (s!! -> s).
 */
public class NonNullAssertionMutator : TypedAstMutator<KtPostfixExpression>(KtPostfixExpression::class) {
    override val name: String = "NonNullAssertionMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates non-null assertions (s!! -> s)"

    override fun canMutateTyped(element: KtPostfixExpression): Boolean =
        element.operationToken == KtTokens.EXCLEXCL && element.baseExpression != null

    override fun mutateTyped(
        element: KtPostfixExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val base = element.baseExpression ?: return emptyList()
        return listOf(
            context.edit(element, base.text, "Removed non-null assertion '${element.text}' -> '${base.text}'"),
        )
    }
}

/**
 * Mutates safe-calls (a?.b -> a!!.b).
 */
public class SafeCallMutator : TypedAstMutator<KtSafeQualifiedExpression>(KtSafeQualifiedExpression::class) {
    override val name: String = "SafeCallMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates safe call operator (?. -> !!)"

    override fun canMutateTyped(element: KtSafeQualifiedExpression): Boolean = element.selectorExpression != null

    override fun mutateTyped(
        element: KtSafeQualifiedExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val receiver = element.receiverExpression
        val selector = element.selectorExpression ?: return emptyList()
        val replacement = "${receiver.text}!!.${selector.text}"
        return listOf(
            context.edit(element, replacement, "Mutated safe call '${element.text}' to '$replacement'"),
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
            val rep = if (element.isNegated) "is" else "!is"
            return listOf(
                context.edit(opRef, rep, "Mutated type check '${opRef.text}' to '$rep'", originalText = element.text),
            )
        }

        if (element is KtBinaryExpressionWithTypeRHS) {
            val opRef = element.operationReference
            val isSafeCast = KtPsiUtil.isSafeCast(element)
            val sign = if (isSafeCast) "as?" else "as"
            val rep = if (isSafeCast) "as" else "as?"
            return listOf(
                context.edit(opRef, rep, "Mutated cast operator '$sign' to '$rep'", originalText = element.text),
            )
        }

        return emptyList()
    }
}

/**
 * Mutates range expressions (until <-> .., downTo <-> .., ..< <-> ..).
 */
public class RangeOperatorMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "RangeOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.RANGE_OPERATOR
    override val description: String = "Mutates range expressions (0 until n <-> 0..n, downTo <-> .., 0..<n <-> 0..n)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean {
        val operation = element.operationReference
        val name = operation.getReferencedName()
        val sign = operation.operationSignTokenType
        return name == "until" || name == "downTo" || sign == KtTokens.RANGE_UNTIL || sign == KtTokens.RANGE
    }

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val name = opRef.getReferencedName()
        val sign = opRef.operationSignTokenType
        val replacement = if (name == "until" || name == "downTo" || sign == KtTokens.RANGE_UNTIL) ".." else "..<"
        return listOf(
            context.edit(opRef, replacement, "Mutated range operator '$sign' to '$replacement'", originalText = element.text),
        )
    }
}

/**
 * Modifies numeric constant literals (Int, Double, Float, Long).
 */
public class LiteralMutationMutator : TypedAstMutator<KtConstantExpression>(KtConstantExpression::class) {
    override val name: String = "LiteralMutationMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates numeric constant literals (x -> x+1, x-1)"

    override fun canMutateTyped(element: KtConstantExpression): Boolean =
        when (element.numericLiteralKind()) {
            KtNumericLiteralKind.INTEGER -> element.text.toIntOrNull() != null
            KtNumericLiteralKind.FLOAT -> element.text.toDoubleOrNull() != null
            else -> false
        }

    override fun mutateTyped(
        element: KtConstantExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val text = element.text
        if (element.numericLiteralKind() == KtNumericLiteralKind.INTEGER) {
            val intVal = text.toIntOrNull() ?: return emptyList()
            return listOf((intVal + 1).toString(), (intVal - 1).toString()).map { mutatedNum ->
                context.edit(element, mutatedNum, "Altered integer constant $text -> $mutatedNum")
            }
        }

        if (element.numericLiteralKind() == KtNumericLiteralKind.FLOAT) {
            val doubleVal = text.toDoubleOrNull() ?: return emptyList()
            val mutatedNum = (doubleVal + 1.0).toString()
            return listOf(
                context.edit(element, mutatedNum, "Altered double constant $text -> $mutatedNum"),
            )
        }

        return emptyList()
    }
}

/**
 * Mutates string template expressions by removing or emptying individual interpolated entries.
 */
public class StringTemplateMutator : TypedAstMutator<KtStringTemplateExpression>(KtStringTemplateExpression::class) {
    override val name: String = "StringTemplateMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates interpolated expressions within string templates"

    override fun canMutateTyped(element: KtStringTemplateExpression): Boolean = element.entries.any { it !is KtLiteralStringTemplateEntry }

    override fun mutateTyped(
        element: KtStringTemplateExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val edits = mutableListOf<AstEdit>()
        for (entry in element.entries) {
            if (entry !is KtLiteralStringTemplateEntry) {
                edits.add(
                    context.edit(entry, "", "Omitted string template interpolation '${entry.text}'"),
                )
            }
        }
        return edits
    }
}
