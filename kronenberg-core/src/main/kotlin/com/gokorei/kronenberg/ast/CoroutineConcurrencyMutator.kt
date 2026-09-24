package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Mutates coroutine concurrency primitives (Dispatchers, SupervisorJob/Job, supervisorScope/coroutineScope, async/launch).
 */
public class CoroutineConcurrencyMutator : AstMutator {
    override val name: String = "CoroutineConcurrencyMutator"
    override val category: MutatorCategory = MutatorCategory.COROUTINE
    override val description: String = "Mutates Kotlin coroutine dispatchers, cancellation hierarchies, and builders"

    override fun canMutate(element: PsiElement): Boolean =
        when (element) {
            is KtDotQualifiedExpression -> element.dispatcherKey()?.let(DISPATCHER_SWAPS::containsKey) == true
            is KtCallExpression -> element.typedCalleeName()?.let(COROUTINE_CALL_SWAPS::containsKey) == true
            else -> false
        }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (element is KtDotQualifiedExpression) {
            val key = element.dispatcherKey() ?: return emptyList()
            val replacement = DISPATCHER_SWAPS[key] ?: return emptyList()
            return listOf(
                context.edit(
                    target = element,
                    replacement = "Dispatchers.$replacement",
                    description = "Mutated coroutine dispatcher '$key' to 'Dispatchers.$replacement'",
                ),
            )
        }

        if (element is KtCallExpression) {
            val callee = element.calleeExpression ?: return emptyList()
            val name = element.typedCalleeName() ?: return emptyList()
            val replacement = COROUTINE_CALL_SWAPS[name] ?: return emptyList()
            return listOf(
                context.edit(
                    target = callee,
                    replacement = replacement,
                    description = "Mutated coroutine primitive '$name' to '$replacement'",
                ),
            )
        }

        return emptyList()
    }

    private fun KtDotQualifiedExpression.dispatcherKey(): Pair<String, String>? {
        val receiver = (receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
        val selector = (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        return if (receiver != null && selector != null) receiver to selector else null
    }

    public companion object {
        private val DISPATCHER_SWAPS =
            mapOf(
                ("Dispatchers" to "IO") to "Default",
                ("Dispatchers" to "Default") to "IO",
                ("Dispatchers" to "Main") to "Unconfined",
                ("Dispatchers" to "Unconfined") to "Default",
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
