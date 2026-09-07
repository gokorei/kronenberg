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
                listOf(context.edit(element, base.text, "Negation inverted (removed '!')"))
            }

            is KtConstantExpression -> {
                val text = element.text
                val replacement = if (text == "true") "false" else "true"
                listOf(context.edit(element, replacement, "Inverted boolean literal from '$text' to '$replacement'"))
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
                listOf(context.edit(opRef, replacement, desc, originalText = element.text))
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
public class ConditionReplacementMutator : TypedAstMutator<KtIfExpression>(KtIfExpression::class) {
    override val name: String = "ConditionReplacementMutator"
    override val category: MutatorCategory = MutatorCategory.CONDITION_REPLACEMENT
    override val description: String = "Replaces boolean if-conditions with constant true and false"

    override fun canMutateTyped(element: KtIfExpression): Boolean {
        val cond = element.condition ?: return false
        return cond.text != "true" && cond.text != "false"
    }

    override fun mutateTyped(
        element: KtIfExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val cond = element.condition ?: return emptyList()
        return listOf("true", "false").map { boolRep ->
            context.edit(cond, boolRep, "Replaced condition '${cond.text}' with '$boolRep'")
        }
    }
}

/**
 * Mutates return expressions by substituting default values (0, false, empty string, collections, null).
 */
public class ReturnValueMutator : TypedAstMutator<KtReturnExpression>(KtReturnExpression::class) {
    override val name: String = "ReturnValueMutator"
    override val category: MutatorCategory = MutatorCategory.RETURN_VALUE
    override val description: String = "Mutates return values (return true -> false, return x -> 0, return str -> \"\", emptyList, null)"

    override fun canMutateTyped(element: KtReturnExpression): Boolean = element.returnedExpression != null

    override fun mutateTyped(
        element: KtReturnExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val returned = element.returnedExpression ?: return emptyList()
        val text = returned.text.trim()
        val isStringExpr = returned is KtStringTemplateExpression || (returned is KtConstantExpression && text.startsWith("\""))

        val replacements = mutableListOf<Pair<String, String>>()

        // Resolve enclosing function return type if available
        var parent = element.parent
        while (parent != null && parent !is KtNamedFunction) {
            parent = parent.parent
        }
        val fn = parent
        val declaredType = fn?.typeReference?.text?.trim()
        val isNullable = declaredType?.endsWith("?") == true

        if (isStringExpr || declaredType == "String" || declaredType == "CharSequence") {
            replacements.add("\"\"" to "Replaced return string with empty string")
            replacements.add("\"mutated\"" to "Replaced return string with altered string")
        } else if (text == "true" || text == "false" || declaredType == "Boolean") {
            if (text == "true") {
                replacements.add("false" to "Replaced return value with false")
            } else if (text == "false") {
                replacements.add("true" to "Replaced return value with true")
            } else {
                replacements.add("false" to "Replaced return value with false")
                replacements.add("true" to "Replaced return value with true")
            }
        } else if (text.startsWith("listOf(") || text.startsWith("mutableListOf(") || declaredType?.startsWith("List") == true) {
            replacements.add("emptyList()" to "Replaced list return with emptyList()")
        } else if (text.startsWith("setOf(") || text.startsWith("mutableSetOf(") || declaredType?.startsWith("Set") == true) {
            replacements.add("emptySet()" to "Replaced set return with emptySet()")
        } else if (text.startsWith("mapOf(") || text.startsWith("mutableMapOf(") || declaredType?.startsWith("Map") == true) {
            replacements.add("emptyMap()" to "Replaced map return with emptyMap()")
        } else {
            // General or unknown type
            val isNumeric = declaredType in setOf("Int", "Long", "Short", "Byte", "Double", "Float", "Number")
            if (isNumeric || declaredType == null) {
                replacements.add("0" to "Replaced return value with 0")
            }
            if (declaredType == null) {
                replacements.add("false" to "Replaced return value with false")
            }
        }

        if (isNullable && text != "null") {
            replacements.add("null" to "Replaced nullable return value with null")
        }

        return replacements.filter { text != it.first }.map { (replacement, desc) ->
            context.edit(returned, replacement, desc, originalText = element.text)
        }
    }
}

/**
 * Mutates standalone void statements by replacing them with Unit.
 */
public class VoidMethodCallMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "VoidMethodCallMutator"
    override val category: MutatorCategory = MutatorCategory.VOID_METHOD_CALL
    override val description: String = "Omits side-effect method calls by replacing statement with Unit"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val parent = element.parent
        return parent is KtBlockExpression || (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression)
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val parent = element.parent
        val targetElement: PsiElement =
            if (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression) {
                parent
            } else {
                element
            }

        return listOf(
            context.edit(targetElement, "Unit", "Omitted statement '${targetElement.text.take(30)}'"),
        )
    }
}
