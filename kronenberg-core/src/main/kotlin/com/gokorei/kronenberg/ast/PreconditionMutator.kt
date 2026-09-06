package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/**
 * Mutates Kotlin precondition and defensive assertion calls (require, check, requireNotNull, checkNotNull).
 */
public class PreconditionMutator : TypedAstMutator<KtCallExpression>(KtCallExpression::class) {
    override val name: String = "PreconditionMutator"
    override val category: MutatorCategory = MutatorCategory.PRECONDITION
    override val description: String = "Mutates defensive precondition assertions (require, check, requireNotNull, checkNotNull)"

    override fun canMutateTyped(element: KtCallExpression): Boolean {
        val callee = element.calleeExpression?.text ?: return false
        val args = element.valueArguments
        if (args.isEmpty()) return false
        return callee in PRECONDITION_NAMES
    }

    override fun mutateTyped(
        element: KtCallExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val callee = element.calleeExpression?.text ?: return emptyList()
        val args = element.valueArguments
        val firstArg = args.firstOrNull()?.getArgumentExpression() ?: return emptyList()

        if (callee == "requireNotNull" || callee == "checkNotNull") {
            return listOf(
                context.edit(
                    target = element,
                    replacement = firstArg.text,
                    description = "Stripped defensive non-null assertion '$callee' -> '${firstArg.text}'",
                ),
            )
        }

        if (callee == "require" || callee == "check") {
            val edits = mutableListOf<AstEdit>()
            val argText = firstArg.text

            val negatedReplacement =
                if (firstArg is KtPrefixExpression && firstArg.operationReference.text == "!") {
                    firstArg.baseExpression?.text ?: "!($argText)"
                } else {
                    "!($argText)"
                }

            edits.add(
                context.edit(
                    target = firstArg,
                    replacement = negatedReplacement,
                    description = "Negated precondition condition in '$callee($argText)' -> '$negatedReplacement'",
                    originalText = firstArg.text,
                ),
            )

            edits.add(
                context.edit(
                    target = firstArg,
                    replacement = "true",
                    description = "Bypassed precondition check in '$callee($argText)' -> 'true'",
                    originalText = firstArg.text,
                ),
            )

            return edits
        }

        return emptyList()
    }

    public companion object {
        private val PRECONDITION_NAMES =
            setOf(
                "require",
                "check",
                "requireNotNull",
                "checkNotNull",
            )
    }
}
