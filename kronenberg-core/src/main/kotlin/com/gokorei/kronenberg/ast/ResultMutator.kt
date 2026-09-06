package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Mutates Kotlin standard library Result recovery and callback expressions.
 */
public class ResultMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "ResultMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String =
        "Mutates Result and functional error handling calls (getOrElse, getOrDefault, getOrNull, onSuccess, onFailure)"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val callee = element.calleeExpression?.text ?: return false
        return callee in RESULT_MUTATIONS
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression ?: return emptyList()
        val original = callee.text
        val replacement = RESULT_MUTATIONS[original] ?: return emptyList()

        return listOf(
            context.edit(
                target = callee,
                replacement = replacement,
                description = "Mutated Result operation '$original' to '$replacement'",
            ),
        )
    }

    public companion object {
        private val RESULT_MUTATIONS =
            mapOf(
                "getOrElse" to "getOrThrow",
                "getOrDefault" to "getOrThrow",
                "getOrNull" to "getOrThrow",
                "onSuccess" to "onFailure",
                "onFailure" to "onSuccess",
            )
    }
}
