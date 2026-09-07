package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Mutates relational boundary operators (< <-> <=, > <-> >=).
 */
public class RelationalBoundaryMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "RelationalBoundaryMutator"
    override val category: MutatorCategory = MutatorCategory.RELATIONAL_BOUNDARY
    override val description: String = "Mutates relational comparisons (< <-> <=, > <-> >=)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean {
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.LT ||
            sign == KtTokens.LTEQ ||
            sign == KtTokens.GT ||
            sign == KtTokens.GTEQ
    }

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val replacements =
            when (opRef.operationSignTokenType) {
                KtTokens.LT -> listOf("<=" to "Replaced < with <=")
                KtTokens.LTEQ -> listOf("<" to "Replaced <= with <")
                KtTokens.GT -> listOf(">=" to "Replaced > with >=")
                KtTokens.GTEQ -> listOf(">" to "Replaced >= with >")
                else -> emptyList()
            }

        return replacements.map { (replacement, desc) ->
            context.edit(opRef, replacement, desc, originalText = element.text)
        }
    }
}

/**
 * Mutates structural and referential equality operators (== <-> !=, === <-> !==).
 */
public class EqualityMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "EqualityMutator"
    override val category: MutatorCategory = MutatorCategory.EQUALITY
    override val description: String = "Mutates equality comparisons (== <-> !=, === <-> !==)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean {
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.EQEQ ||
            sign == KtTokens.EXCLEQ ||
            sign == KtTokens.EQEQEQ ||
            sign == KtTokens.EXCLEQEQEQ
    }

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val replacements =
            when (opRef.operationSignTokenType) {
                KtTokens.EQEQ -> listOf("!=" to "Replaced == with !=")
                KtTokens.EXCLEQ -> listOf("==" to "Replaced != with ==")
                KtTokens.EQEQEQ -> listOf("!==" to "Replaced === with !==")
                KtTokens.EXCLEQEQEQ -> listOf("===" to "Replaced !== with ===")
                else -> emptyList()
            }

        return replacements.map { (replacement, desc) ->
            context.edit(opRef, replacement, desc, originalText = element.text)
        }
    }
}

/**
 * Mutates arithmetic operators (+ <-> -, * <-> /, % <-> *).
 */
public class ArithmeticOperatorMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "ArithmeticOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.ARITHMETIC_OPERATOR
    override val description: String = "Mutates binary arithmetic operators (+ <-> -, * <-> /, % <-> *)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean {
        val sign = element.operationReference.operationSignTokenType
        val isArithmeticSign =
            sign == KtTokens.PLUS ||
                sign == KtTokens.MINUS ||
                sign == KtTokens.MUL ||
                sign == KtTokens.DIV ||
                sign == KtTokens.PERC
        if (!isArithmeticSign) return false

        // Suppress mutating '+' when either operand is a string literal / string template (string concatenation)
        if (sign == KtTokens.PLUS) {
            val left = element.left
            val right = element.right
            val leftIsString = left is KtStringTemplateExpression || (left is KtConstantExpression && left.text.startsWith("\""))
            val rightIsString = right is KtStringTemplateExpression || (right is KtConstantExpression && right.text.startsWith("\""))
            if (leftIsString || rightIsString) {
                return false
            }
        }

        return true
    }

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val replacements =
            when (opRef.operationSignTokenType) {
                KtTokens.PLUS -> listOf("-" to "Replaced + with -")
                KtTokens.MINUS -> listOf("+" to "Replaced - with +")
                KtTokens.MUL -> listOf("/" to "Replaced * with /")
                KtTokens.DIV -> listOf("*" to "Replaced / with *")
                KtTokens.PERC -> listOf("*" to "Replaced % with *")
                else -> emptyList()
            }

        return replacements.map { (replacement, desc) ->
            context.edit(opRef, replacement, desc, originalText = element.text)
        }
    }
}

/**
 * Mutates compound assignments (+= <-> -=, *= <-> /=, %= <-> *=).
 */
public class CompoundAssignmentMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "CompoundAssignmentMutator"
    override val category: MutatorCategory = MutatorCategory.COMPOUND_ASSIGNMENT
    override val description: String = "Mutates compound assignments (+= <-> -=, *= <-> /=, %= <-> *=)"

    override fun canMutateTyped(element: KtBinaryExpression): Boolean {
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.PLUSEQ ||
            sign == KtTokens.MINUSEQ ||
            sign == KtTokens.MULTEQ ||
            sign == KtTokens.DIVEQ ||
            sign == KtTokens.PERCEQ
    }

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val replacements =
            when (opRef.operationSignTokenType) {
                KtTokens.PLUSEQ -> listOf("-=" to "Replaced += with -=")
                KtTokens.MINUSEQ -> listOf("+=" to "Replaced -= with +=")
                KtTokens.MULTEQ -> listOf("/=" to "Replaced *= with /=")
                KtTokens.DIVEQ -> listOf("*=" to "Replaced /= with *=")
                KtTokens.PERCEQ -> listOf("*=" to "Replaced %= with *=")
                else -> emptyList()
            }

        return replacements.map { (rep, desc) ->
            context.edit(opRef, rep, desc, originalText = element.text)
        }
    }
}

/**
 * Mutates unary operators (+x <-> -x, ++x <-> --x, x++ <-> x--).
 */
public class UnaryOperatorMutator : AstMutator {
    override val name: String = "UnaryOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.UNARY_OPERATOR
    override val description: String = "Mutates unary operators (+x <-> -x, ++x <-> --x, x++ <-> x--)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element is KtPrefixExpression) {
            val token = element.operationToken
            return token == KtTokens.PLUS ||
                token == KtTokens.MINUS ||
                token == KtTokens.PLUSPLUS ||
                token == KtTokens.MINUSMINUS
        }
        if (element is KtPostfixExpression) {
            val token = element.operationToken
            return token == KtTokens.PLUSPLUS || token == KtTokens.MINUSMINUS
        }
        return false
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (element is KtPrefixExpression) {
            val base = element.baseExpression ?: return emptyList()
            val rep =
                when (element.operationToken) {
                    KtTokens.PLUS -> "-${base.text}"
                    KtTokens.MINUS -> "+${base.text}"
                    KtTokens.PLUSPLUS -> "--${base.text}"
                    KtTokens.MINUSMINUS -> "++${base.text}"
                    else -> return emptyList()
                }
            return listOf(context.edit(element, rep, "Mutated unary prefix ${element.text} -> $rep"))
        }
        if (element is KtPostfixExpression) {
            val base = element.baseExpression ?: return emptyList()
            val rep =
                when (element.operationToken) {
                    KtTokens.PLUSPLUS -> "${base.text}--"
                    KtTokens.MINUSMINUS -> "${base.text}++"
                    else -> return emptyList()
                }
            return listOf(context.edit(element, rep, "Mutated unary postfix ${element.text} -> $rep"))
        }
        return emptyList()
    }
}

/**
 * Mutates bitwise operators (a and b <-> a or b, a xor b <-> a and b).
 */
public class BitwiseOperatorMutator : TypedAstMutator<KtBinaryExpression>(KtBinaryExpression::class) {
    override val name: String = "BitwiseOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.BITWISE_OPERATOR
    override val description: String = "Mutates bitwise infix operators (and <-> or, xor <-> and)"

    private val supported = setOf("and", "or", "xor")

    override fun canMutateTyped(element: KtBinaryExpression): Boolean = element.operationReference.text in supported

    override fun mutateTyped(
        element: KtBinaryExpression,
        context: MutationContext,
    ): List<AstEdit> {
        val opRef = element.operationReference
        val sign = opRef.text
        val rep =
            when (sign) {
                "and" -> "or"
                "or" -> "and"
                "xor" -> "and"
                else -> return emptyList()
            }
        return listOf(context.edit(opRef, rep, "Mutated bitwise operator '$sign' to '$rep'", originalText = element.text))
    }
}
