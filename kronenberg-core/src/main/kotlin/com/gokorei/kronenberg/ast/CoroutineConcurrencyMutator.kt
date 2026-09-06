package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Mutates coroutine concurrency primitives (Dispatchers, SupervisorJob/Job, supervisorScope/coroutineScope, async/launch).
 */
public class CoroutineConcurrencyMutator : AstMutator {
    override val name: String = "CoroutineConcurrencyMutator"
    override val category: MutatorCategory = MutatorCategory.COROUTINE
    override val description: String = "Mutates Kotlin coroutine dispatchers, cancellation hierarchies, and builders"

    override fun canMutate(element: PsiElement): Boolean {
        if (element is KtDotQualifiedExpression) {
            val text = element.text
            return text in DISPATCHER_SWAPS
        }
        if (element is KtCallExpression) {
            val callee = element.calleeExpression?.text ?: return false
            return callee in COROUTINE_CALL_SWAPS
        }
        return false
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (element is KtDotQualifiedExpression) {
            val text = element.text
            val replacement = DISPATCHER_SWAPS[text] ?: return emptyList()
            return listOf(
                context.edit(
                    target = element,
                    replacement = replacement,
                    description = "Mutated coroutine dispatcher '$text' to '$replacement'",
                ),
            )
        }

        if (element is KtCallExpression) {
            val callee = element.calleeExpression ?: return emptyList()
            val text = callee.text
            val replacement = COROUTINE_CALL_SWAPS[text] ?: return emptyList()
            return listOf(
                context.edit(
                    target = callee,
                    replacement = replacement,
                    description = "Mutated coroutine primitive '$text' to '$replacement'",
                ),
            )
        }

        return emptyList()
    }

    public companion object {
        private val DISPATCHER_SWAPS =
            mapOf(
                "Dispatchers.IO" to "Dispatchers.Default",
                "Dispatchers.Default" to "Dispatchers.IO",
                "Dispatchers.Main" to "Dispatchers.Unconfined",
                "Dispatchers.Unconfined" to "Dispatchers.Default",
            )

        private val COROUTINE_CALL_SWAPS =
            mapOf(
                "SupervisorJob" to "Job",
                "Job" to "SupervisorJob",
                "supervisorScope" to "coroutineScope",
                "coroutineScope" to "supervisorScope",
                "async" to "launch",
            )
    }
}
