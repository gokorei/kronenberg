package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Inverts higher-order collection methods (filter <-> filterNot, any <-> all, take <-> drop, first <-> last, map <-> mapNotNull, sorted <-> sortedDescending, minOrNull <-> maxOrNull).
 */
public class CollectionOperatorMutator : AstMutator {
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

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val calleeName = element.calleeExpression?.text
        return calleeName in supportedMethods
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val callee = callExpr.calleeExpression ?: return emptyList()
        val calleeName = callee.text
        val replacements =
            when (calleeName) {
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
            val range = callee.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = callee.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates Kotlin Coroutine and Flow operators (delay(x) -> delay(0), flow.filter <-> filterNot, first <-> last).
 */
public class CoroutineFlowMutator : AstMutator {
    override val name: String = "CoroutineFlowMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String = "Mutates Coroutine and Flow operators (delay, flow filter/first/last)"

    private val supportedFlowMethods = setOf("filter", "filterNot", "first", "last")

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val callee = element.calleeExpression?.text ?: return false
        return callee == "delay" || callee in supportedFlowMethods
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val callee = callExpr.calleeExpression ?: return emptyList()
        val calleeName = callee.text

        if (calleeName == "delay") {
            val valueArgs = callExpr.valueArgumentList ?: return emptyList()
            if (valueArgs.arguments.isNotEmpty()) {
                val arg = valueArgs.arguments.first()
                if (arg.text.trim() != "0L" && arg.text.trim() != "0") {
                    val range = arg.textRange
                    val (line, col) = context.lineAndCol(range.startOffset)
                    return listOf(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = "0L",
                            originalText = arg.text,
                            description = "Mutated delay argument '${arg.text}' to '0L'",
                            line = line,
                            column = col,
                        ),
                    )
                }
            }
        }

        return emptyList()
    }
}
