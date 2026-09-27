package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Outcome of the PSI-only receiver analysis performed before a Result mutation is applied.
 */
public enum class ResultReceiverVerdict {
    /** The receiver is provably a `kotlin.Result`. */
    RESULT,

    /**
     * The receiver provably is not a `kotlin.Result`, either because its static type is a known
     * non-Result type, or because a type declared in the analysed file shadows the callee itself.
     */
    FOREIGN,

    /**
     * The receiver type cannot be derived from the parsed file alone, for example because the type
     * is declared in a separate compilation unit or the receiver is a call the file never resolves.
     *
     * Applicability fails closed on this verdict: an unproven receiver is not mutated, because a
     * custom class declared outside the analysed file is free to declare `getOrElse`,
     * `getOrDefault`, `getOrNull`, `onSuccess` or `onFailure` with an identical signature. Rewriting
     * such a call to `getOrThrow()` produces a mutant that cannot compile.
     */
    UNKNOWN,
}

/**
 * Decides whether a [KtCallExpression] is a `kotlin.Result` member call using PSI inspection only.
 */
public interface ResultReceiverAnalyzer {
    /**
     * Classifies the receiver of [call] as a [ResultReceiverVerdict].
     */
    public fun verdict(
        call: KtCallExpression,
        callee: String,
    ): ResultReceiverVerdict
}

/**
 * Argument shape of a `kotlin.Result` member: fixed value arguments plus trailing lambdas.
 */
public data class ResultCallContract(
    val valueArguments: Int,
    val lambdaArguments: Int,
)

/**
 * Argument-shape contracts of the `kotlin.Result` members rewritten by `ResultMutator`.
 *
 * Several standard library types share a callee name with a `kotlin.Result` member while exposing
 * a different signature: `Map.getOrDefault(key, fallback)`, `Map.getOrElse(key) { fallback }` and
 * `List.getOrNull(index)`. Verifying the shape first keeps those receivers out even when their type
 * cannot be resolved from the parsed file.
 */
public object ResultCallContracts {
    private val CONTRACTS =
        mapOf(
            "getOrElse" to ResultCallContract(valueArguments = 0, lambdaArguments = 1),
            "getOrDefault" to ResultCallContract(valueArguments = 1, lambdaArguments = 0),
            "getOrNull" to ResultCallContract(valueArguments = 0, lambdaArguments = 0),
            "onSuccess" to ResultCallContract(valueArguments = 0, lambdaArguments = 1),
            "onFailure" to ResultCallContract(valueArguments = 0, lambdaArguments = 1),
        )

    /**
     * Reports whether the argument shape of [call] matches the `kotlin.Result` overload of [callee].
     *
     * `KtCallExpression.valueArguments` already contains trailing lambdas, so a lambda is detected
     * by its argument expression rather than by consulting `lambdaArguments` as well, which would
     * count every trailing lambda twice.
     */
    public fun accepts(
        callee: String,
        call: KtCallExpression,
    ): Boolean {
        val contract = CONTRACTS[callee] ?: return false
        val arguments = call.valueArguments
        val lambdas = arguments.count { it.getArgumentExpression() is KtLambdaExpression }
        return arguments.size - lambdas == contract.valueArguments && lambdas == contract.lambdaArguments
    }
}

/**
 * Non-recursive, PSI-local type heuristics shared by the receiver analysis.
 *
 * Only facts that are literally written in the parsed file are used: declared type references,
 * well-known factory return types and literal expressions. No name reference is followed back into
 * the declaration index, which keeps index construction free of recursion.
 */
public object ResultTypeHeuristics {
    public const val RESULT_TYPE: String = "Result"

    public const val STRING_TYPE: String = "String"

    /**
     * Static types that can never be a `kotlin.Result` but do declare colliding callee names.
     */
    public val FOREIGN_TYPES: Set<String> =
        setOf(
            "Array",
            "Boolean",
            "Byte",
            "Char",
            "CharSequence",
            "Collection",
            "Double",
            "Exception",
            "Float",
            "HashMap",
            "HashSet",
            "Int",
            "Iterable",
            "LinkedHashMap",
            "LinkedHashSet",
            "List",
            "Long",
            "Map",
            "MutableCollection",
            "MutableIterable",
            "MutableList",
            "MutableMap",
            "MutableSet",
            "NavigableMap",
            "Number",
            "Sequence",
            "Set",
            "Short",
            "SortedMap",
            "String",
            "StringBuilder",
            "Throwable",
            "TreeMap",
            "TreeSet",
            "Unit",
        )

    /**
     * `kotlin.Result` members that return another `kotlin.Result`.
     *
     * Applying one of these to a proven `Result` receiver proves the whole chain is a `Result`, so
     * `runCatching { }.map { }.getOrNull()` stays a `kotlin.Result` call even though the file
     * declares no `Result` class to look the member up in.
     */
    public val RESULT_PRESERVING_MEMBERS: Set<String> =
        setOf(
            "andAlso",
            "map",
            "mapCatching",
            "onFailure",
            "onSuccess",
            "recoverCatching",
        )

    private val FACTORY_TYPES: Map<String, String> =
        mapOf(
            "Result.failure" to RESULT_TYPE,
            "Result.runCatching" to RESULT_TYPE,
            "Result.success" to RESULT_TYPE,
            "mapCatching" to RESULT_TYPE,
            "recoverCatching" to RESULT_TYPE,
            "runCatching" to RESULT_TYPE,
            "toResult" to RESULT_TYPE,
            "buildString" to STRING_TYPE,
            "emptyList" to "List",
            "emptyMap" to "Map",
            "emptySet" to "Set",
            "hashMapOf" to "HashMap",
            "hashSetOf" to "HashSet",
            "joinToString" to STRING_TYPE,
            "listOf" to "List",
            "mapOf" to "Map",
            "mutableListOf" to "MutableList",
            "mutableMapOf" to "MutableMap",
            "mutableSetOf" to "MutableSet",
            "readText" to STRING_TYPE,
            "setOf" to "Set",
            "toList" to "List",
            "toMap" to "Map",
            "toMutableList" to "MutableList",
            "toMutableMap" to "MutableMap",
            "toMutableSet" to "MutableSet",
            "toSet" to "Set",
        )

    /**
     * Reports whether [memberName] is a `kotlin.Result` member that returns a `kotlin.Result`.
     */
    public fun isResultPreserving(memberName: String): Boolean = memberName in RESULT_PRESERVING_MEMBERS

    /**
     * Reduces a possibly qualified, nullable and generic type reference to its bare type name.
     */
    public fun baseTypeName(typeText: String): String =
        typeText
            .trim()
            .substringBefore('<')
            .removeSuffix("?")
            .trim()
            .substringAfterLast('.')
            .trim()

    /**
     * Resolves the return type of a well-known factory call such as `runCatching { }` or `mapOf()`.
     *
     * Both a bare call (`runCatching { }`) and a qualified call written as `Qualifier.callee(...)`
     * are recognised, so `Result.success(1)` resolves to `Result` in either position.
     */
    public fun factoryTypeOf(call: KtCallExpression): String? {
        val callee = call.calleeExpression?.text ?: return null
        val qualified = call.parent as? KtQualifiedExpression
        val qualifier =
            if (qualified != null && qualified.selectorExpression === call) {
                (qualified.receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
            } else {
                null
            }
        return FACTORY_TYPES[callee] ?: qualifier?.let { FACTORY_TYPES["$it.$callee"] }
    }

    /**
     * Infers a type from an initializer expression without following name references.
     */
    public fun initializerType(initializer: KtExpression): String? =
        when (initializer) {
            is KtStringTemplateExpression -> {
                STRING_TYPE
            }

            is KtCallExpression -> {
                factoryTypeOf(initializer)
            }

            is KtQualifiedExpression -> {
                (initializer.selectorExpression as? KtCallExpression)?.let { factoryTypeOf(it) }
            }

            else -> {
                null
            }
        }
}
