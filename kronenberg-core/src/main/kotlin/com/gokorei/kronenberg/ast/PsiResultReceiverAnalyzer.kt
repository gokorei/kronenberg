package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getContainingKtFile
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * PSI-only [ResultReceiverAnalyzer] deciding whether a call really targets `kotlin.Result`.
 *
 * The analysis is deliberately conservative in one direction and permissive in the other:
 *
 * * A receiver is rejected ([ResultReceiverVerdict.FOREIGN]) whenever the parsed file proves it is
 *   not a `kotlin.Result`. This covers literal receivers, a static type drawn from
 *   [ResultTypeHeuristics.FOREIGN_TYPES] such as `Map`, `MutableMap` or `String`, and any type
 *   declared in the file that shadows the callee with its own member (directly or through a
 *   locally declared supertype).
 * * A receiver is accepted ([ResultReceiverVerdict.UNKNOWN]) when the file simply does not carry
 *   enough information, for example `parse(input).getOrNull()`. Rejecting those would silently
 *   remove the mutation operator from real-world `Result` code, which is its main use case.
 *
 * The second layer of defence is the argument shape checked by [ResultCallContracts]: receiver
 * types that cannot be resolved at all still fail the shape check whenever their overload differs
 * from the `kotlin.Result` one.
 */
public class PsiResultReceiverAnalyzer : ResultReceiverAnalyzer {
    override fun verdict(
        call: KtCallExpression,
        callee: String,
    ): ResultReceiverVerdict {
        val receiver = receiverOf(call) ?: return ResultReceiverVerdict.FOREIGN
        if (receiver is KtConstantExpression) return ResultReceiverVerdict.FOREIGN
        val file = call.getContainingKtFile() ?: return ResultReceiverVerdict.UNKNOWN
        val declarations = ResultDeclarationIndexBuilder.build(file)
        val typeText = inferTypeText(receiver, file, declarations) ?: return ResultReceiverVerdict.UNKNOWN
        val base = ResultTypeHeuristics.baseTypeName(typeText)
        if (base == ResultTypeHeuristics.RESULT_TYPE) return ResultReceiverVerdict.RESULT
        if (base in ResultTypeHeuristics.FOREIGN_TYPES) return ResultReceiverVerdict.FOREIGN
        if (declaresMember(file, base, callee, mutableSetOf())) return ResultReceiverVerdict.FOREIGN
        return ResultReceiverVerdict.UNKNOWN
    }

    /**
     * Returns the receiver of a qualified call, or `null` for an unqualified call whose receiver
     * cannot be established from the PSI (for example inside a `with` block).
     */
    private fun receiverOf(call: KtCallExpression): KtExpression? {
        val qualified = call.parent as? KtQualifiedExpression ?: return null
        return if (qualified.selectorExpression === call) qualified.receiverExpression else null
    }

    private fun inferTypeText(
        expression: KtExpression,
        file: KtFile,
        declarations: List<ResultLocalDeclaration>,
    ): String? =
        when (expression) {
            is KtCallExpression -> inferFromCall(expression, file, declarations)
            is KtNameReferenceExpression -> lookup(declarations, expression.getReferencedName(), expression)
            is KtThisExpression -> expression.getStrictParentOfType<KtClassOrObject>()?.name
            is KtParenthesizedExpression -> expression.expression?.let { inferTypeText(it, file, declarations) }
            is KtQualifiedExpression -> inferFromQualified(expression, file, declarations)
            else -> ResultTypeHeuristics.initializerType(expression)
        }

    private fun inferFromCall(
        call: KtCallExpression,
        file: KtFile,
        declarations: List<ResultLocalDeclaration>,
    ): String? {
        ResultTypeHeuristics.factoryTypeOf(call)?.let { return it }
        val qualified = call.parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression !== call) return null
        val receiver = qualified.receiverExpression ?: return null
        val receiverType = inferTypeText(receiver, file, declarations) ?: return null
        val callee = call.calleeExpression?.text ?: return null
        return localMemberType(file, ResultTypeHeuristics.baseTypeName(receiverType), callee)
    }

    private fun inferFromQualified(
        qualified: KtQualifiedExpression,
        file: KtFile,
        declarations: List<ResultLocalDeclaration>,
    ): String? {
        val receiver = qualified.receiverExpression ?: return null
        val receiverType = inferTypeText(receiver, file, declarations) ?: return null
        val selectorName =
            when (val selector = qualified.selectorExpression) {
                is KtNameReferenceExpression -> selector.getReferencedName()
                is KtCallExpression -> selector.calleeExpression?.text
                else -> null
            } ?: return null
        return localMemberType(file, ResultTypeHeuristics.baseTypeName(receiverType), selectorName)
    }

    /**
     * Resolves a simple name to the type of the nearest declaration visible at [anchor].
     *
     * Declarations inside the enclosing function win over unrelated ones declared earlier in the
     * file, which keeps a local shadowing variable from being mistaken for an outer receiver.
     */
    private fun lookup(
        declarations: List<ResultLocalDeclaration>,
        name: String,
        anchor: KtExpression,
    ): String? {
        val anchorOffset = anchor.textRange.startOffset
        val scope = anchor.getStrictParentOfType<KtNamedFunction>()
        val preceding = declarations.filter { it.name == name && it.offset < anchorOffset }
        return preceding.filter { it.scope === scope }.maxByOrNull { it.offset }?.typeText
            ?: preceding.maxByOrNull { it.offset }?.typeText
    }

    /**
     * Reports whether a type declared in [file] declares [memberName] itself, either directly or
     * through a supertype that is also declared in the same file.
     */
    private fun declaresMember(
        file: KtFile,
        className: String,
        memberName: String,
        visited: MutableSet<String>,
    ): Boolean {
        if (!visited.add(className)) return false
        val declaration = localClass(file, className) ?: return false
        val shadowsCallee =
            declaration.declarations.any { child ->
                when (child) {
                    is KtNamedFunction -> child.name == memberName
                    is KtProperty -> child.name == memberName
                    else -> false
                }
            }
        if (shadowsCallee) return true
        return declaration.superTypeListEntries
            .mapNotNull { entry -> entry.typeReference?.let { ResultTypeHeuristics.baseTypeName(it.text) } }
            .any { declaresMember(file, it, memberName, visited) }
    }

    private fun localClass(
        file: KtFile,
        className: String,
    ): KtClassOrObject? = file.collectDescendantsOfType<KtClassOrObject>().firstOrNull { it.name == className }

    private fun localMemberType(
        file: KtFile,
        className: String,
        memberName: String,
    ): String? {
        val declaration = localClass(file, className) ?: return null
        return declaration.declarations
            .firstNotNullOfOrNull { member ->
                when (member) {
                    is KtProperty -> member.typeReference?.text?.takeIf { member.name == memberName }
                    is KtNamedFunction -> member.typeReference?.text?.takeIf { member.name == memberName }
                    else -> null
                }
            }
    }
}
