package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Inverts higher-order collection methods (filter <-> filterNot, any <-> all, take <-> drop, first <-> last, map <-> mapNotNull, sorted <-> sortedDescending, minOrNull <-> maxOrNull).
 */
public class CollectionOperatorMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "CollectionOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String =
        "Inverts collection methods (filter <-> filterNot, any <-> all, map <-> mapNotNull, sorted <-> sortedDescending)"

    private val supportedMethods =
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
        val calleeName = element.typedCalleeName()
        return calleeName in supportedMethods
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression ?: return emptyList()
        val replacements =
            when (element.typedCalleeName()) {
                "filter" -> listOf("filterNot" to "Inverted filter -> filterNot")
                "filterNot" -> listOf("filter" to "Inverted filterNot -> filter")
                "any" -> listOf("all" to "Inverted any -> all")
                "all" -> listOf("any" to "Inverted all -> any")
                "take" -> listOf("drop" to "Inverted take -> drop")
                "drop" -> listOf("take" to "Inverted drop -> take")
                "first" -> listOf("last" to "Inverted first -> last")
                "last" -> listOf("first" to "Inverted last -> first")
                "map" -> listOf("mapNotNull" to "Inverted map -> mapNotNull")
                "mapNotNull" -> listOf("map" to "Inverted mapNotNull -> map")
                "sorted" -> listOf("sortedDescending" to "Inverted sorted -> sortedDescending")
                "sortedDescending" -> listOf("sorted" to "Inverted sortedDescending -> sorted")
                "minOrNull" -> listOf("maxOrNull" to "Inverted minOrNull -> maxOrNull")
                "maxOrNull" -> listOf("minOrNull" to "Inverted maxOrNull -> minOrNull")
                "find" -> listOf("findLast" to "Inverted find -> findLast")
                "findLast" -> listOf("find" to "Inverted findLast -> find")
                "associate" -> listOf("associateBy" to "Inverted associate -> associateBy")
                "associateBy" -> listOf("associate" to "Inverted associateBy -> associate")
                else -> emptyList()
            }

        return replacements.map { (rep, desc) ->
            context.edit(callee, rep, desc, originalText = callee.text)
        }
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
        val callee = element.typedCalleeName() ?: return false
        return callee == "delay" || callee in supportedFlowMethods
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val calleeName = element.typedCalleeName() ?: return emptyList()

        if (calleeName == "delay") {
            val valueArgs = element.valueArgumentList ?: return emptyList()
            if (valueArgs.arguments.isNotEmpty()) {
                val argument = valueArgs.arguments.first().getArgumentExpression() ?: return emptyList()
                if (!argument.isZeroIntegerLiteral()) {
                    return listOf(
                        context.edit(argument, "0L", "Mutated delay argument '${argument.text}' to '0L'"),
                    )
                }
            }
        }

        return emptyList()
    }
}
