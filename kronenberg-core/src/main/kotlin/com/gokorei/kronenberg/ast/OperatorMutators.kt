package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/**
 * Mutates relational boundary operators (< <-> <=, > <-> >=, == <-> !=).
 */
public class RelationalBoundaryMutator : AstMutator {
    override val name: String = "RelationalBoundaryMutator"
    override val category: MutatorCategory = MutatorCategory.RELATIONAL_BOUNDARY
    override val description: String = "Mutates relational comparisons (< <-> <=, > <-> >=, == <-> !=)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.LT ||
            sign == KtTokens.LTEQ ||
            sign == KtTokens.GT ||
            sign == KtTokens.GTEQ ||
            sign == KtTokens.EQEQ ||
            sign == KtTokens.EXCLEQ
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val opElement = opRef.operationSignTokenType
        val replacements =
            when (opElement) {
                KtTokens.LT -> listOf("<=" to "Replaced < with <=")
                KtTokens.LTEQ -> listOf("<" to "Replaced <= with <")
                KtTokens.GT -> listOf(">=" to "Replaced > with >=")
                KtTokens.GTEQ -> listOf(">" to "Replaced >= with >")
                KtTokens.EQEQ -> listOf("!=" to "Replaced == with !=")
                KtTokens.EXCLEQ -> listOf("==" to "Replaced != with ==")
                else -> emptyList()
            }

        return replacements.map { (replacement, desc) ->
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = expr.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates arithmetic operators (+ <-> -, * <-> /, % <-> *).
 */
public class ArithmeticOperatorMutator : AstMutator {
    override val name: String = "ArithmeticOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.ARITHMETIC_OPERATOR
    override val description: String = "Mutates binary arithmetic operators (+ <-> -, * <-> /, % <-> *)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.PLUS ||
            sign == KtTokens.MINUS ||
            sign == KtTokens.MUL ||
            sign == KtTokens.DIV ||
            sign == KtTokens.PERC
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val opElement = opRef.operationSignTokenType
        val replacements =
            when (opElement) {
                KtTokens.PLUS -> listOf("-" to "Replaced + with -")
                KtTokens.MINUS -> listOf("+" to "Replaced - with +")
                KtTokens.MUL -> listOf("/" to "Replaced * with /")
                KtTokens.DIV -> listOf("*" to "Replaced / with *")
                KtTokens.PERC -> listOf("*" to "Replaced % with *")
                else -> emptyList()
            }

        return replacements.map { (replacement, desc) ->
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = expr.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates compound assignments (+= <-> -=, *= <-> /=, %= <-> *=).
 */
public class CompoundAssignmentMutator : AstMutator {
    override val name: String = "CompoundAssignmentMutator"
    override val category: MutatorCategory = MutatorCategory.COMPOUND_ASSIGNMENT
    override val description: String = "Mutates compound assignments (+= <-> -=, *= <-> /=, %= <-> *=)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.PLUSEQ ||
            sign == KtTokens.MINUSEQ ||
            sign == KtTokens.MULTEQ ||
            sign == KtTokens.DIVEQ ||
            sign == KtTokens.PERCEQ
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val sign = opRef.operationSignTokenType
        val replacements =
            when (sign) {
                KtTokens.PLUSEQ -> listOf("-=" to "Replaced += with -=")
                KtTokens.MINUSEQ -> listOf("+=" to "Replaced -= with +=")
                KtTokens.MULTEQ -> listOf("/=" to "Replaced *= with /=")
                KtTokens.DIVEQ -> listOf("*=" to "Replaced /= with *=")
                KtTokens.PERCEQ -> listOf("*=" to "Replaced %= with *=")
                else -> emptyList()
            }

        return replacements.map { (rep, desc) ->
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = expr.text,
                description = desc,
                line = line,
                column = col,
            )
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
            val token = element.operationToken
            val rep =
                when (token) {
                    KtTokens.PLUS -> "-${base.text}"
                    KtTokens.MINUS -> "+${base.text}"
                    KtTokens.PLUSPLUS -> "--${base.text}"
                    KtTokens.MINUSMINUS -> "++${base.text}"
                    else -> return emptyList()
                }
            val range = element.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated unary prefix ${element.text} -> $rep",
                    line = line,
                    column = col,
                ),
            )
        }
        if (element is KtPostfixExpression) {
            val base = element.baseExpression ?: return emptyList()
            val token = element.operationToken
            val rep =
                when (token) {
                    KtTokens.PLUSPLUS -> "${base.text}--"
                    KtTokens.MINUSMINUS -> "${base.text}++"
                    else -> return emptyList()
                }
            val range = element.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated unary postfix ${element.text} -> $rep",
                    line = line,
                    column = col,
                ),
            )
        }
        return emptyList()
    }
}

/**
 * Mutates bitwise operators (a and b <-> a or b, a xor b <-> a and b).
 */
public class BitwiseOperatorMutator : AstMutator {
    override val name: String = "BitwiseOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.BITWISE_OPERATOR
    override val description: String = "Mutates bitwise infix operators (and <-> or, xor <-> and)"

    private val supported = setOf("and", "or", "xor")

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        return element.operationReference.text in supported
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val sign = opRef.text
        val rep =
            when (sign) {
                "and" -> "or"
                "or" -> "and"
                "xor" -> "and"
                else -> return emptyList()
            }
        val range = opRef.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = expr.text,
                description = "Mutated bitwise operator '$sign' to '$rep'",
                line = line,
                column = col,
            ),
        )
    }
}
