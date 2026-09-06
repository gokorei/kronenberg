package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Mutates Kotlin takeIf <-> takeUnless predicates.
 */
public class TakeIfMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "TakeIfMutator"
    override val category: MutatorCategory = MutatorCategory.SCOPE_FUNCTION
    override val description: String = "Inverts predicate filtering calls (takeIf <-> takeUnless)"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val calleeName = element.calleeExpression?.text
        return calleeName == "takeIf" || calleeName == "takeUnless"
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression ?: return emptyList()
        val original = callee.text
        val replacement = if (original == "takeIf") "takeUnless" else "takeIf"
        return listOf(
            context.edit(
                target = callee,
                replacement = replacement,
                description = "Inverted predicate call '$original' to '$replacement'",
            ),
        )
    }
}

/**
 * Mutates standard library scope functions (apply <-> also, let <-> run).
 */
public class ScopeFunctionMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "ScopeFunctionMutator"
    override val category: MutatorCategory = MutatorCategory.SCOPE_FUNCTION
    override val description: String = "Mutates Kotlin standard library scope functions (apply <-> also, let <-> run)"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val calleeName = element.calleeExpression?.text
        return calleeName in SCOPE_SWAPS
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression ?: return emptyList()
        val original = callee.text
        val replacement = SCOPE_SWAPS[original] ?: return emptyList()
        return listOf(
            context.edit(
                target = callee,
                replacement = replacement,
                description = "Mutated scope function '$original' to '$replacement'",
            ),
        )
    }

    public companion object {
        private val SCOPE_SWAPS =
            mapOf(
                "apply" to "also",
                "also" to "apply",
                "let" to "run",
                "run" to "let",
            )
    }
}
