package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Mutates Kotlin standard library Result recovery and callback expressions.
 *
 * Callee names alone are not enough to identify a `kotlin.Result` member: the standard library
 * itself declares colliding overloads such as `Map.getOrDefault(key, defaultValue)`,
 * `Map.getOrElse(key) { default }` and `List.getOrNull(index)`, and user-defined types can shadow
 * any of the five names with an identical signature. Every candidate therefore has to pass two
 * PSI-only applicability checks before it is rewritten:
 *
 * 1. [ResultCallContracts] verifies that the argument shape matches the `kotlin.Result` overload.
 * 2. [receiverAnalyzer] must *prove* the receiver is a `kotlin.Result`, using
 *    [PsiResultReceiverAnalyzer] by default.
 *
 * Both checks are pure PSI inspection: no symbol resolution, no compiler frontend, no regular
 * expressions. The second check fails closed: a receiver whose type the analysed file does not
 * state, such as a custom class declared in a separate compilation unit, is left alone rather than
 * rewritten into a call that cannot compile. The rewrite always replaces the complete call
 * expression so the resulting source stays compilable (`result.getOrElse { 0 }` becomes
 * `result.getOrThrow()`, never `result.getOrThrow { 0 }`).
 */
public class ResultMutator(
    private val receiverAnalyzer: ResultReceiverAnalyzer = PsiResultReceiverAnalyzer(),
) : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "ResultMutator"
    override val category: MutatorCategory = MutatorCategory.RESULT_ERROR_HANDLING
    override val description: String =
        "Mutates Result and functional error handling calls (getOrElse, getOrDefault, getOrNull, onSuccess, onFailure) " +
            "only when the analysed file proves the receiver is a kotlin.Result"

    override fun canMutateTyped(element: KtCallExpression): Boolean = replacementFor(element) != null

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val replacement = replacementFor(element) ?: return emptyList()
        val original = element.text

        return listOf(
            context.edit(
                target = element,
                replacement = replacement,
                description = "Mutated Result call '$original' to '$replacement'",
            ),
        )
    }

    /**
     * Returns the replacement text when [element] is an applicable `kotlin.Result` call, otherwise
     * `null`. Applicability and mutation share this single decision so the two can never disagree.
     */
    private fun replacementFor(element: KtCallExpression): String? {
        val callee = element.calleeExpression?.text ?: return null
        val replacement = RESULT_MUTATIONS[callee] ?: return null
        if (!ResultCallContracts.accepts(callee, element)) return null
        if (receiverAnalyzer.verdict(element, callee) != ResultReceiverVerdict.RESULT) return null
        return replacement
    }

    public companion object {
        private val RESULT_MUTATIONS =
            mapOf(
                "getOrElse" to "getOrThrow()",
                "getOrDefault" to "getOrThrow()",
                "getOrNull" to "getOrThrow()",
                "onSuccess" to "onFailure {}",
                "onFailure" to "onSuccess {}",
            )
    }
}
