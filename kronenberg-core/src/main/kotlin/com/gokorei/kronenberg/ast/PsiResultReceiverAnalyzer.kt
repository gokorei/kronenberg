package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * PSI-only [ResultReceiverAnalyzer] deciding whether a call really targets `kotlin.Result`.
 *
 * The analysis is a proof obligation, not a guess. A receiver is reported as
 * [ResultReceiverVerdict.RESULT] only when the parsed file itself states the fact:
 *
 * * a declared type reference written on a parameter, property, local variable or member, such as
 *   `result: Result<Int>` or `cache.last: Result<Int>`;
 * * a return type the same file states for a function whose arity matches the call, written out
 *   explicitly as in `fun parse(input: String): Result<Int>` or produced by a direct factory as in
 *   `fun parse(input: String) = runCatching { }`;
 * * a direct `kotlin.Result` factory such as `Result.success(1)`, `Result.failure(e)` or
 *   `runCatching { }`, and any chain of [ResultTypeHeuristics.RESULT_PRESERVING_MEMBERS] applied
 *   to such a factory, so `runCatching { }.map { }.getOrNull()` stays provable.
 *
 * Everything else is rejected, which is the whole point of the rule. A receiver whose type is
 * declared in another compilation unit, or which is a call the file never resolves, is reported as
 * [ResultReceiverVerdict.UNKNOWN]; a receiver drawn from [ResultTypeHeuristics.FOREIGN_TYPES], or
 * shadowed by a type declared in the analysed file, is reported as [ResultReceiverVerdict.FOREIGN].
 * Neither is mutated, so a custom `Repository.getOrNull()` or `Listener.onFailure { }` defined
 * outside the analysed file can no longer be rewritten into a call that does not compile.
 *
 * [ResultCallContracts] remains the independent first gate on the argument shape, which keeps the
 * standard library collisions such as `Map.getOrDefault(key, fallback)` out even before the
 * receiver is looked at.
 */
public class PsiResultReceiverAnalyzer : ResultReceiverAnalyzer {
    override fun verdict(
        call: KtCallExpression,
        callee: String,
    ): ResultReceiverVerdict {
        val receiver = receiverOf(call) ?: return ResultReceiverVerdict.FOREIGN
        if (receiver is KtConstantExpression) return ResultReceiverVerdict.FOREIGN
        val index = ResultDeclarationIndex.build(call.getContainingKtFile())
        val typeText = inferTypeText(receiver, index) ?: return ResultReceiverVerdict.UNKNOWN
        return classify(typeText, callee, index)
    }

    /**
     * Returns the receiver of a qualified call, or `null` for an unqualified call whose receiver
     * cannot be established from the PSI (for example inside a `with` block).
     */
    private fun receiverOf(call: KtCallExpression): KtExpression? {
        val qualified = call.parent as? KtQualifiedExpression ?: return null
        return if (qualified.selectorExpression === call) qualified.receiverExpression else null
    }

    /**
     * Classifies a receiver whose type the analysed file states, using [ResultTypeHeuristics.RESULT_TYPE]
     * as the only accepted outcome and a known non-Result type or a shadowing local declaration as the
     * two provable rejections. Everything else remains unproven.
     */
    private fun classify(
        typeText: String,
        callee: String,
        index: ResultDeclarationIndex,
    ): ResultReceiverVerdict {
        val base = ResultTypeHeuristics.baseTypeName(typeText)
        return when {
            base == ResultTypeHeuristics.RESULT_TYPE -> ResultReceiverVerdict.RESULT
            base in ResultTypeHeuristics.FOREIGN_TYPES -> ResultReceiverVerdict.FOREIGN
            index.declaresMember(base, callee) -> ResultReceiverVerdict.FOREIGN
            else -> ResultReceiverVerdict.UNKNOWN
        }
    }

    private fun inferTypeText(
        expression: KtExpression,
        index: ResultDeclarationIndex,
    ): String? =
        when (expression) {
            is KtCallExpression -> inferFromCall(expression, index)
            is KtNameReferenceExpression -> lookup(index, expression.getReferencedName(), expression)
            is KtThisExpression -> expression.getStrictParentOfType<KtClassOrObject>()?.name
            is KtParenthesizedExpression -> expression.expression?.let { inferTypeText(it, index) }
            is KtQualifiedExpression -> inferFromQualified(expression, index)
            else -> ResultTypeHeuristics.initializerType(expression)
        }

    private fun inferFromCall(
        call: KtCallExpression,
        index: ResultDeclarationIndex,
    ): String? {
        val factoryType = ResultTypeHeuristics.factoryTypeOf(call)
        if (factoryType != null) return factoryType
        // A call used as the selector of a qualified expression is resolved as a member of its
        // receiver only; consulting a same-named top-level function there would prove the wrong
        // declaration, so only the receiver path is taken.
        val asSelector = call.parent as? KtQualifiedExpression
        return if (asSelector?.selectorExpression === call) {
            memberTypeOfSelector(asSelector, index)
        } else {
            index.functionReturnType(call.calleeExpression?.text.orEmpty(), call.valueArguments.size)
        }
    }

    private fun inferFromQualified(
        qualified: KtQualifiedExpression,
        index: ResultDeclarationIndex,
    ): String? {
        val selector = qualified.selectorExpression
        // `Result.success(1)` is a qualified expression whose selector is the factory call, so the
        // factory is resolved before any receiver named `Result` would have to be looked up.
        val factoryType = (selector as? KtCallExpression)?.let { ResultTypeHeuristics.factoryTypeOf(it) }
        return factoryType ?: memberTypeOfSelector(qualified, index)
    }

    /**
     * Types a qualified expression by its receiver and selector, either through a member declared
     * locally or, when both are a `kotlin.Result` chain, through the Result-preserving rule.
     */
    private fun memberTypeOfSelector(
        qualified: KtQualifiedExpression,
        index: ResultDeclarationIndex,
    ): String? {
        val receiverType = inferTypeText(qualified.receiverExpression, index)
        val selectorName = selectorNameOf(qualified)
        if (receiverType == null || selectorName == null) return null
        val base = ResultTypeHeuristics.baseTypeName(receiverType)
        return if (isPreservedResult(base, selectorName)) {
            ResultTypeHeuristics.RESULT_TYPE
        } else {
            index.memberTypeOf(base, selectorName)
        }
    }

    /**
     * Reports whether applying [memberName] to a receiver of type [base] is guaranteed to yield
     * another `kotlin.Result`, which is what makes a factory chain provable.
     */
    private fun isPreservedResult(
        base: String,
        memberName: String,
    ): Boolean = base == ResultTypeHeuristics.RESULT_TYPE && ResultTypeHeuristics.isResultPreserving(memberName)

    private fun selectorNameOf(qualified: KtQualifiedExpression): String? =
        when (val selector = qualified.selectorExpression) {
            is KtNameReferenceExpression -> selector.getReferencedName()
            is KtCallExpression -> selector.calleeExpression?.text
            else -> null
        }

    /**
     * Resolves a simple name to the type of the nearest declaration visible at [anchor].
     *
     * Declarations inside the enclosing function win over unrelated ones declared earlier in the
     * file, which keeps a local shadowing variable from being mistaken for an outer receiver.
     */
    private fun lookup(
        index: ResultDeclarationIndex,
        name: String,
        anchor: KtExpression,
    ): String? {
        val anchorOffset = anchor.textRange.startOffset
        val scope = anchor.getStrictParentOfType<KtNamedFunction>()
        val preceding = index.declarations.filter { it.name == name && it.offset < anchorOffset }
        return preceding.filter { it.scope === scope }.maxByOrNull { it.offset }?.typeText
            ?: preceding.maxByOrNull { it.offset }?.typeText
    }
}
