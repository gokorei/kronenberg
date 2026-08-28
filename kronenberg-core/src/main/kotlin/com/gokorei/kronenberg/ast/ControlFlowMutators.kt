package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Mutates boolean prefixes (!flag -> flag), boolean literals (true <-> false), and logical operators (&& <-> ||).
 */
public class BooleanInversionMutator : AstMutator {
    override val name: String = "BooleanInversionMutator"
    override val category: MutatorCategory = MutatorCategory.BOOLEAN_INVERSION
    override val description: String = "Inverts boolean operators (&& <-> ||, !flag <-> flag)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element is KtPrefixExpression && element.operationToken == KtTokens.EXCL && element.baseExpression != null) {
            return true
        }
        if (element is KtConstantExpression && (element.text == "true" || element.text == "false")) {
            return true
        }
        if (element is KtBinaryExpression) {
            val sign = element.operationReference.operationSignTokenType
            return sign == KtTokens.ANDAND || sign == KtTokens.OROR
        }
        return false
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        return when (element) {
            is KtPrefixExpression -> {
                val base = element.baseExpression ?: return emptyList()
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = base.text,
                        originalText = element.text,
                        description = "Negation inverted (removed '!')",
                        line = line,
                        column = col,
                    ),
                )
            }

            is KtConstantExpression -> {
                val text = element.text
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                val replacement = if (text == "true") "false" else "true"
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = text,
                        description = "Inverted boolean literal from '$text' to '$replacement'",
                        line = line,
                        column = col,
                    ),
                )
            }

            is KtBinaryExpression -> {
                val opRef = element.operationReference
                val sign = opRef.operationSignTokenType
                val (replacement, desc) =
                    if (sign == KtTokens.ANDAND) {
                        "||" to "Replaced && with ||"
                    } else {
                        "&&" to "Replaced || with &&"
                    }
                val range = opRef.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = element.text,
                        description = desc,
                        line = line,
                        column = col,
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }
    }
}

/**
 * Replaces boolean conditions in if-expressions with constant true / false.
 */
public class ConditionReplacementMutator : AstMutator {
    override val name: String = "ConditionReplacementMutator"
    override val category: MutatorCategory = MutatorCategory.CONDITION_REPLACEMENT
    override val description: String = "Replaces boolean if-conditions with constant true and false"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtIfExpression) return false
        val cond = element.condition ?: return false
        return cond.text != "true" && cond.text != "false"
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val ifExpr = element as? KtIfExpression ?: return emptyList()
        val cond = ifExpr.condition ?: return emptyList()
        val range = cond.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf("true", "false").map { boolRep ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = boolRep,
                originalText = cond.text,
                description = "Replaced condition '${cond.text}' with '$boolRep'",
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates return expressions by substituting default values (0, false, empty string, collections, null).
 */
public class ReturnValueMutator : AstMutator {
    override val name: String = "ReturnValueMutator"
    override val category: MutatorCategory = MutatorCategory.RETURN_VALUE
    override val description: String = "Mutates return values (return true -> false, return x -> 0, return str -> \"\", emptyList, null)"

    override fun canMutate(element: PsiElement): Boolean = element is KtReturnExpression && element.returnedExpression != null

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val returnExpr = element as? KtReturnExpression ?: return emptyList()
        val returned = returnExpr.returnedExpression ?: return emptyList()
        val range = returned.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        val text = returned.text.trim()
        val isStringExpr = returned is KtStringTemplateExpression || (returned is KtConstantExpression && text.startsWith("\""))

        val replacements = mutableListOf<Pair<String, String>>()
        if (isStringExpr) {
            replacements.add("\"\"" to "Replaced return string with empty string")
            replacements.add("\"mutated\"" to "Replaced return string with altered string")
        } else if (text == "true") {
            replacements.add("false" to "Replaced return value with false")
        } else if (text == "false") {
            replacements.add("true" to "Replaced return value with true")
        } else if (text.startsWith("listOf(") || text.startsWith("mutableListOf(")) {
            replacements.add("emptyList()" to "Replaced list return with emptyList()")
        } else if (text.startsWith("setOf(") || text.startsWith("mutableSetOf(")) {
            replacements.add("emptySet()" to "Replaced set return with emptySet()")
        } else if (text.startsWith("mapOf(") || text.startsWith("mutableMapOf(")) {
            replacements.add("emptyMap()" to "Replaced map return with emptyMap()")
        } else {
            replacements.add("0" to "Replaced return value with 0")
            replacements.add("false" to "Replaced return value with false")
        }

        // Check if enclosing function return type is nullable
        var parent = returnExpr.parent
        while (parent != null && parent !is KtNamedFunction) {
            parent = parent.parent
        }
        val fn = parent
        if (fn?.typeReference?.text?.endsWith("?") == true && text != "null") {
            replacements.add("null" to "Replaced nullable return value with null")
        }

        return replacements.filter { text != it.first }.map { (replacement, desc) ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = returnExpr.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates standalone void statements by replacing them with Unit.
 */
public class VoidMethodCallMutator : AstMutator {
    override val name: String = "VoidMethodCallMutator"
    override val category: MutatorCategory = MutatorCategory.VOID_METHOD_CALL
    override val description: String = "Omits side-effect method calls by replacing statement with Unit"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val parent = element.parent
        return parent is KtBlockExpression || (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression)
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val parent = callExpr.parent
        val targetElement: PsiElement =
            if (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression) {
                parent
            } else {
                callExpr
            }

        val range = targetElement.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = "Unit",
                originalText = targetElement.text,
                description = "Omitted statement '${targetElement.text.take(30)}'",
                line = line,
                column = col,
            ),
        )
    }
}
