package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Inverts higher-order collection methods (filter <-> filterNot, any <-> all, take <-> drop, first <-> last, map <-> mapNotNull, sorted <-> sortedDescending, minOrNull <-> maxOrNull).
 */
public class CollectionOperatorMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "CollectionOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String =
        "Inverts collection methods (filter <-> filterNot, any <-> all, map <-> mapNotNull, sorted <-> sortedDescending)"

    internal val supportedMethods =
        setOf(
            "filter",
            "filterNot",
            "any",
            "all",
            "take",
            "drop",
            "first",
            "last",
            "map",
            "mapNotNull",
            "sorted",
            "sortedDescending",
            "minOrNull",
            "maxOrNull",
            "find",
            "findLast",
            "associate",
            "associateBy",
        )

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val calleeName = element.calleeExpression?.text
        return calleeName in supportedMethods
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression ?: return emptyList()
        return mutateName(callee.text, callee, context)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    internal fun mutateResolved(
        element: PsiElement,
        context: MutationContext,
        target: ResolvedSemanticTarget,
    ): List<AstEdit> {
        if (element !is KtCallExpression || target.name !in supportedMethods) return emptyList()
        val callee = element.calleeExpression ?: return emptyList()
        return mutateName(target.name, callee, context)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun mutateName(
        name: String,
        callee: org.jetbrains.kotlin.psi.KtExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val replacement =
            when (name) {
                "filter" -> "filterNot"
                "filterNot" -> "filter"
                "any" -> "all"
                "all" -> "any"
                "take" -> "drop"
                "drop" -> "take"
                "first" -> "last"
                "last" -> "first"
                "map" -> "mapNotNull"
                "mapNotNull" -> "map"
                "sorted" -> "sortedDescending"
                "sortedDescending" -> "sorted"
                "minOrNull" -> "maxOrNull"
                "maxOrNull" -> "minOrNull"
                "find" -> "findLast"
                "findLast" -> "find"
                "associate" -> "associateBy"
                "associateBy" -> "associate"
                else -> return emptyList()
            }
        return listOf(
            context.edit(
                target = callee,
                replacement = replacement,
                description = "Inverted $name -> $replacement",
                originalText = callee.text,
            ),
        )
    }
}

/**
 * Mutates Kotlin Coroutine and Flow operators (delay(x) -> delay(0), flow.filter <-> filterNot, first <-> last).
 */
public class CoroutineFlowMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "CoroutineFlowMutator"
    override val category: MutatorCategory = MutatorCategory.COROUTINE
    override val description: String = "Mutates Coroutine and Flow operators (delay, flow filter/first/last)"

    private val supportedFlowMethods = setOf("filter", "filterNot", "first", "last")

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val callee = element.calleeExpression?.text ?: return false
        return callee == "delay" || callee in supportedFlowMethods
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> = mutateDelay(element, context)

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    internal fun mutateResolved(
        element: PsiElement,
        context: MutationContext,
        target: ResolvedSemanticTarget,
    ): List<AstEdit> {
        if (element !is KtCallExpression || target.name != "delay") return emptyList()
        return mutateDelay(element, context)
    }

    @Suppress("ReturnCount")
    private fun mutateDelay(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val valueArgs = element.valueArgumentList ?: return emptyList()
        val arg = valueArgs.arguments.firstOrNull() ?: return emptyList()
        if (arg.text.trim() == "0L" || arg.text.trim() == "0") return emptyList()
        return listOf(
            context.edit(arg, "0L", "Mutated delay argument '${arg.text}' to '0L'"),
        )
    }
}
