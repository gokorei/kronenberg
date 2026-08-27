package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

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
 * Mutates boolean prefixes (!flag -> flag), boolean literals (true <-> false), and logical operators (&& <-> ||).
 */
public class BooleanInversionMutator : AstMutator {
    override val name: String = "BooleanInversionMutator"
    override val category: MutatorCategory = MutatorCategory.BOOLEAN_INVERSION
    override val description: String = "Inverts boolean operators (&& <-> ||, !flag <-> flag)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element is KtPrefixExpression && element.operationToken == KtTokens.EXCL && element.baseExpression != null) {
            return true
        }
        if (element is KtConstantExpression && (element.text == "true" || element.text == "false")) {
            return true
        }
        if (element is KtBinaryExpression) {
            val sign = element.operationReference.operationSignTokenType
            return sign == KtTokens.ANDAND || sign == KtTokens.OROR
        }
        return false
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        return when (element) {
            is KtPrefixExpression -> {
                val base = element.baseExpression ?: return emptyList()
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = base.text,
                        originalText = element.text,
                        description = "Negation inverted (removed '!')",
                        line = line,
                        column = col,
                    ),
                )
            }
            is KtConstantExpression -> {
                val text = element.text
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                val replacement = if (text == "true") "false" else "true"
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = text,
                        description = "Inverted boolean literal from '$text' to '$replacement'",
                        line = line,
                        column = col,
                    ),
                )
            }
            is KtBinaryExpression -> {
                val opRef = element.operationReference
                val sign = opRef.operationSignTokenType
                val (replacement, desc) =
                    if (sign == KtTokens.ANDAND) {
                        "||" to "Replaced && with ||"
                    } else {
                        "&&" to "Replaced || with &&"
                    }
                val range = opRef.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = element.text,
                        description = desc,
                        line = line,
                        column = col,
                    ),
                )
            }
            else -> emptyList()
        }
    }
}

/**
 * Mutates return expressions by substituting default values (0, false, empty string, collections, null).
 */
public class ReturnValueMutator : AstMutator {
    override val name: String = "ReturnValueMutator"
    override val category: MutatorCategory = MutatorCategory.RETURN_VALUE
    override val description: String = "Mutates return values (return true -> false, return x -> 0, return str -> \"\", emptyList, null)"

    override fun canMutate(element: PsiElement): Boolean = element is KtReturnExpression && element.returnedExpression != null

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val returnExpr = element as? KtReturnExpression ?: return emptyList()
        val returned = returnExpr.returnedExpression ?: return emptyList()
        val range = returned.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        val text = returned.text.trim()
        val isStringExpr = returned is KtStringTemplateExpression || (returned is KtConstantExpression && text.startsWith("\""))

        val replacements = mutableListOf<Pair<String, String>>()
        if (isStringExpr) {
            replacements.add("\"\"" to "Replaced return string with empty string")
            replacements.add("\"mutated\"" to "Replaced return string with altered string")
        } else if (text == "true") {
            replacements.add("false" to "Replaced return value with false")
        } else if (text == "false") {
            replacements.add("true" to "Replaced return value with true")
        } else if (text.startsWith("listOf(") || text.startsWith("mutableListOf(")) {
            replacements.add("emptyList()" to "Replaced list return with emptyList()")
        } else if (text.startsWith("setOf(") || text.startsWith("mutableSetOf(")) {
            replacements.add("emptySet()" to "Replaced set return with emptySet()")
        } else if (text.startsWith("mapOf(") || text.startsWith("mutableMapOf(")) {
            replacements.add("emptyMap()" to "Replaced map return with emptyMap()")
        } else {
            replacements.add("0" to "Replaced return value with 0")
            replacements.add("false" to "Replaced return value with false")
        }

        // Check if enclosing function return type is nullable
        var parent = returnExpr.parent
        while (parent != null && parent !is KtNamedFunction) {
            parent = parent.parent
        }
        val fn = parent as? KtNamedFunction
        if (fn?.typeReference?.text?.endsWith("?") == true && text != "null") {
            replacements.add("null" to "Replaced nullable return value with null")
        }

        return replacements.filter { text != it.first }.map { (replacement, desc) ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = returnExpr.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates standalone void statements by replacing them with Unit.
 */
public class VoidMethodCallMutator : AstMutator {
    override val name: String = "VoidMethodCallMutator"
    override val category: MutatorCategory = MutatorCategory.VOID_METHOD_CALL
    override val description: String = "Omits side-effect method calls by replacing statement with Unit"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val parent = element.parent
        return parent is KtBlockExpression || (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression)
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val parent = callExpr.parent
        val targetElement: PsiElement =
            if (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression) {
                parent
            } else {
                callExpr
            }

        val range = targetElement.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = "Unit",
                originalText = targetElement.text,
                description = "Omitted statement '${targetElement.text.take(30)}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Modifies numeric constant literals (Int, Double, Float, Long).
 */
public class LiteralMutationMutator : AstMutator {
    override val name: String = "LiteralMutationMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates numeric constant literals (x -> x+1, x-1)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtConstantExpression) return false
        val text = element.text
        if (text == "true" || text == "false") return false
        return text.toIntOrNull() != null || text.toDoubleOrNull() != null || text.toLongOrNull() != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val constExpr = element as? KtConstantExpression ?: return emptyList()
        val text = constExpr.text
        val range = constExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        val intVal = text.toIntOrNull()
        if (intVal != null) {
            return listOf((intVal + 1).toString(), (intVal - 1).toString()).map { mutatedNum ->
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = mutatedNum,
                    originalText = text,
                    description = "Altered integer constant $text -> $mutatedNum",
                    line = line,
                    column = col,
                )
            }
        }

        val doubleVal = text.toDoubleOrNull()
        if (doubleVal != null && !text.contains("f") && !text.contains("F") && !text.contains("L")) {
            val mutatedNum = (doubleVal + 1.0).toString()
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = mutatedNum,
                    originalText = text,
                    description = "Altered double constant $text -> $mutatedNum",
                    line = line,
                    column = col,
                ),
            )
        }

        return emptyList()
    }
}

/**
 * Inverts higher-order collection methods (filter <-> filterNot, any <-> all, take <-> drop, first <-> last, map <-> mapNotNull, sorted <-> sortedDescending, minOrNull <-> maxOrNull).
 */
public class CollectionOperatorMutator : AstMutator {
    override val name: String = "CollectionOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String =
        "Inverts collection methods (filter <-> filterNot, any <-> all, map <-> mapNotNull, sorted <-> sortedDescending)"

    private val supportedMethods =
        setOf(
            "filter",
            "filterNot",
            "any",
            "all",
            "take",
            "drop",
            "first",
            "last",
            "map",
            "mapNotNull",
            "sorted",
            "sortedDescending",
            "minOrNull",
            "maxOrNull",
            "find",
            "findLast",
            "associate",
            "associateBy",
        )

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val calleeName = element.calleeExpression?.text
        return calleeName in supportedMethods
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val callee = callExpr.calleeExpression ?: return emptyList()
        val calleeName = callee.text
        val replacements =
            when (calleeName) {
                "filter" -> listOf("filterNot" to "Inverted filter -> filterNot")
                "filterNot" -> listOf("filter" to "Inverted filterNot -> filter")
                "any" -> listOf("all" to "Inverted any -> all")
                "all" -> listOf("any" to "Inverted all -> any")
                "take" -> listOf("drop" to "Inverted take -> drop")
                "drop" -> listOf("take" to "Inverted drop -> take")
                "first" -> listOf("last" to "Inverted first -> last")
                "last" -> listOf("first" to "Inverted last -> first")
                "map" -> listOf("mapNotNull" to "Inverted map -> mapNotNull")
                "mapNotNull" -> listOf("map" to "Inverted mapNotNull -> map")
                "sorted" -> listOf("sortedDescending" to "Inverted sorted -> sortedDescending")
                "sortedDescending" -> listOf("sorted" to "Inverted sortedDescending -> sorted")
                "minOrNull" -> listOf("maxOrNull" to "Inverted minOrNull -> maxOrNull")
                "maxOrNull" -> listOf("minOrNull" to "Inverted maxOrNull -> minOrNull")
                "find" -> listOf("findLast" to "Inverted find -> findLast")
                "findLast" -> listOf("find" to "Inverted findLast -> find")
                "associate" -> listOf("associateBy" to "Inverted associate -> associateBy")
                "associateBy" -> listOf("associate" to "Inverted associateBy -> associate")
                else -> emptyList()
            }

        return replacements.map { (rep, desc) ->
            val range = callee.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = callee.text,
                description = desc,
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Replaces boolean conditions in if-expressions with constant true / false.
 */
public class ConditionReplacementMutator : AstMutator {
    override val name: String = "ConditionReplacementMutator"
    override val category: MutatorCategory = MutatorCategory.CONDITION_REPLACEMENT
    override val description: String = "Replaces boolean if-conditions with constant true and false"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtIfExpression) return false
        val cond = element.condition ?: return false
        return cond.text != "true" && cond.text != "false"
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val ifExpr = element as? KtIfExpression ?: return emptyList()
        val cond = ifExpr.condition ?: return emptyList()
        val range = cond.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf("true", "false").map { boolRep ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = boolRep,
                originalText = cond.text,
                description = "Replaced condition '${cond.text}' with '$boolRep'",
                line = line,
                column = col,
            )
        }
    }
}

/**
 * Mutates null safety expressions (a ?: b -> b).
 */
public class NullSafetyMutator : AstMutator {
    override val name: String = "NullSafetyMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> default b)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        return element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.right != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val right = binaryExpr.right ?: return emptyList()
        val range = binaryExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = right.text,
                originalText = binaryExpr.text,
                description = "Replaced elvis '${binaryExpr.text}' with default '${right.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates elvis expressions (a ?: b -> fallback-bypassing left value a).
 */
public class ElvisLeftHandMutator : AstMutator {
    override val name: String = "ElvisLeftHandMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates elvis expressions (a ?: b -> left-hand a)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        return element.operationReference.operationSignTokenType == KtTokens.ELVIS && element.left != null
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val left = binaryExpr.left ?: return emptyList()
        val range = binaryExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = left.text,
                originalText = binaryExpr.text,
                description = "Replaced elvis '${binaryExpr.text}' with left-hand '${left.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates non-null assertion expressions (s!! -> s).
 */
public class NonNullAssertionMutator : AstMutator {
    override val name: String = "NonNullAssertionMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates non-null assertions (s!! -> s)"

    override fun canMutate(element: PsiElement): Boolean =
        element is KtPostfixExpression && element.operationToken == KtTokens.EXCLEXCL && element.baseExpression != null

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val postfixExpr = element as? KtPostfixExpression ?: return emptyList()
        val base = postfixExpr.baseExpression ?: return emptyList()
        val range = postfixExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = base.text,
                originalText = postfixExpr.text,
                description = "Removed non-null assertion '${postfixExpr.text}' -> '${base.text}'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates range expressions (until <-> .., downTo <-> .., ..< <-> ..).
 */
public class RangeOperatorMutator : AstMutator {
    override val name: String = "RangeOperatorMutator"
    override val category: MutatorCategory = MutatorCategory.RANGE_OPERATOR
    override val description: String = "Mutates range expressions (0 until n <-> 0..n, downTo <-> .., 0..<n <-> 0..n)"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.text
        return sign == "until" ||
            sign == "downTo" ||
            sign == "..<" ||
            element.operationReference.operationSignTokenType == KtTokens.RANGE ||
            element.operationReference.operationSignTokenType == KtTokens.RANGE_UNTIL
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val binaryExpr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = binaryExpr.operationReference
        val sign = opRef.text
        val replacement = if (sign == "until" || sign == "downTo" || sign == "..<") ".." else "until"
        val range = opRef.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = binaryExpr.text,
                description = "Mutated range operator '$sign' to '$replacement'",
                line = line,
                column = col,
            ),
        )
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

/**
 * Mutates safe-calls (a?.b -> a!!.b).
 */
public class SafeCallMutator : AstMutator {
    override val name: String = "SafeCallMutator"
    override val category: MutatorCategory = MutatorCategory.NULL_SAFETY
    override val description: String = "Mutates safe call operator (?. -> !!)"

    override fun canMutate(element: PsiElement): Boolean = element is KtSafeQualifiedExpression

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val safeExpr = element as? KtSafeQualifiedExpression ?: return emptyList()
        val receiver = safeExpr.receiverExpression
        val selector = safeExpr.selectorExpression ?: return emptyList()
        val range = safeExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        val rep = "${receiver.text}!!.${selector.text}"

        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = safeExpr.text,
                description = "Mutated safe call '${safeExpr.text}' to '$rep'",
                line = line,
                column = col,
            ),
        )
    }
}

/**
 * Mutates smart casts and type checks (x is Type <-> x !is Type, x as Type <-> x as? Type).
 */
public class SmartCastMutator : AstMutator {
    override val name: String = "SmartCastMutator"
    override val category: MutatorCategory = MutatorCategory.EXTREME
    override val description: String = "Mutates type checks and casts (is <-> !is, as <-> as?)"

    override fun canMutate(element: PsiElement): Boolean = element is KtIsExpression || element is KtBinaryExpressionWithTypeRHS

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        if (element is KtIsExpression) {
            val opRef = element.operationReference
            val isNegated = element.isNegated
            val rep = if (isNegated) "is" else "!is"
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated type check '${opRef.text}' to '$rep'",
                    line = line,
                    column = col,
                ),
            )
        }

        if (element is KtBinaryExpressionWithTypeRHS) {
            val opRef = element.operationReference
            val sign = opRef.text
            val rep = if (sign == "as") "as?" else "as"
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            return listOf(
                AstEdit(
                    startOffset = range.startOffset,
                    endOffset = range.endOffset,
                    replacement = rep,
                    originalText = element.text,
                    description = "Mutated cast operator '$sign' to '$rep'",
                    line = line,
                    column = col,
                ),
            )
        }

        return emptyList()
    }
}

/**
 * Mutates string template expressions by removing or emptying individual interpolated entries.
 */
public class StringTemplateMutator : AstMutator {
    override val name: String = "StringTemplateMutator"
    override val category: MutatorCategory = MutatorCategory.LITERAL_MUTATION
    override val description: String = "Mutates interpolated expressions within string templates"

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtStringTemplateExpression) return false
        return element.entries.any { it !is KtLiteralStringTemplateEntry }
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val strExpr = element as? KtStringTemplateExpression ?: return emptyList()
        val edits = mutableListOf<AstEdit>()

        for (entry in strExpr.entries) {
            if (entry !is KtLiteralStringTemplateEntry) {
                val range = entry.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                edits.add(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = "",
                        originalText = entry.text,
                        description = "Omitted string template interpolation '${entry.text}'",
                        line = line,
                        column = col,
                    ),
                )
            }
        }

        return edits
    }
}

/**
 * Mutates Kotlin Coroutine and Flow operators (delay(x) -> delay(0), flow.filter <-> filterNot, first <-> last).
 */
public class CoroutineFlowMutator : AstMutator {
    override val name: String = "CoroutineFlowMutator"
    override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
    override val description: String = "Mutates Coroutine and Flow operators (delay, flow filter/first/last)"

    private val supportedFlowMethods = setOf("filter", "filterNot", "first", "last")

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val callee = element.calleeExpression?.text ?: return false
        return callee == "delay" || callee in supportedFlowMethods
    }

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val callee = callExpr.calleeExpression ?: return emptyList()
        val calleeName = callee.text

        if (calleeName == "delay") {
            val valueArgs = callExpr.valueArgumentList ?: return emptyList()
            if (valueArgs.arguments.isNotEmpty()) {
                val arg = valueArgs.arguments.first()
                if (arg.text.trim() != "0L" && arg.text.trim() != "0") {
                    val range = arg.textRange
                    val (line, col) = context.lineAndCol(range.startOffset)
                    return listOf(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = "0L",
                            originalText = arg.text,
                            description = "Mutated delay argument '${arg.text}' to '0L'",
                            line = line,
                            column = col,
                        ),
                    )
                }
            }
        }

        return emptyList()
    }
}

/**
 * Registry containing active AST mutation rules.
 */
public class MutatorRegistry(
    mutators: List<AstMutator> = defaultMutators(),
) {
    private val registeredMutators = mutableListOf<AstMutator>().apply { addAll(mutators) }

    public fun register(mutator: AstMutator): MutatorRegistry {
        registeredMutators.add(mutator)
        return this
    }

    public fun mutators(includeExtreme: Boolean = false): List<AstMutator> =
        if (includeExtreme) {
            registeredMutators.toList()
        } else {
            registeredMutators.filter {
                it.category != MutatorCategory.EXTREME &&
                    it.category != MutatorCategory.LITERAL_MUTATION &&
                    it.category != MutatorCategory.CONDITION_REPLACEMENT
            }
        }

    public companion object {
        public fun defaultMutators(): List<AstMutator> =
            listOf(
                RelationalBoundaryMutator(),
                ArithmeticOperatorMutator(),
                CompoundAssignmentMutator(),
                UnaryOperatorMutator(),
                BooleanInversionMutator(),
                ReturnValueMutator(),
                VoidMethodCallMutator(),
                LiteralMutationMutator(),
                CollectionOperatorMutator(),
                ConditionReplacementMutator(),
                NullSafetyMutator(),
                ElvisLeftHandMutator(),
                NonNullAssertionMutator(),
                RangeOperatorMutator(),
                BitwiseOperatorMutator(),
                SafeCallMutator(),
                SmartCastMutator(),
                StringTemplateMutator(),
                CoroutineFlowMutator(),
            )

        public fun default(): MutatorRegistry = MutatorRegistry(defaultMutators())
    }
}
