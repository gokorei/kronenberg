package com.gokorei.kronenberg.doc

import com.gokorei.kronenberg.ast.AstMutator
import com.gokorei.kronenberg.ast.MutatorRegistry
import com.gokorei.kronenberg.model.MutatorCategory
import java.io.File

/**
 * Explicit interface for generating human- and LLM-friendly documentation
 * directly from the registered AST mutators in [MutatorRegistry].
 */
public interface MutatorDocGenerator {
    /**
     * Generates a comprehensive GitHub Flavored Markdown reference guide
     * documenting every AST mutator rule, its category, and transformation description.
     */
    public fun generateMarkdownReference(): String

    /**
     * Generates a compact Markdown table summarizing mutator categories
     * and rules for embedding into README.md or wiki homepages.
     */
    public fun generateSummaryTable(): String
}

/**
 * Default implementation of [MutatorDocGenerator] deriving documentation
 * directly from [MutatorRegistry].
 */
public class DefaultMutatorDocGenerator(
    private val registry: MutatorRegistry = MutatorRegistry.default(),
) : MutatorDocGenerator {
    override fun generateMarkdownReference(): String {
        val mutators = registry.mutators(includeExtreme = true)
        val grouped = mutators.groupBy { it.category }

        return buildString {
            appendLine("# Kronenberg AST Mutators Reference")
            appendLine(
                "This document is the code-backed catalog of all **${mutators.size} AST mutation operators** implemented in Kronenberg.",
            )
            appendLine(
                "Every mutator is implemented via pure K2 PSI AST visitors (`KtTreeVisitorVoid`), " +
                    "avoiding regular expressions or bytecode transformations.",
            )
            appendLine()
            appendLine("**Total Mutators**: ${mutators.size} | **Categories**: ${grouped.size}")
            appendLine()
            appendLine("---")
            appendLine()

            for ((category, categoryMutators) in grouped.entries.sortedBy { categoryDisplayName(it.key) }) {
                appendLine("### ${categoryDisplayName(category)}")
                appendLine()
                appendLine("| Mutator Class | Description |")
                appendLine("| :--- | :--- |")
                for (mutator in categoryMutators.sortedBy { it.name }) {
                    appendLine("| `${mutator.name}` | ${mutator.description.replace("|", "\\|")} |")
                }
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    override fun generateSummaryTable(): String {
        val mutators = registry.mutators(includeExtreme = true)
        val grouped = mutators.groupBy { it.category }

        return buildString {
            appendLine("| Mutator Category | Mutators | Target Syntax & Examples |")
            appendLine("| :--- | :--- | :--- |")
            for ((category, categoryMutators) in grouped.entries.sortedBy { categoryDisplayName(it.key) }) {
                val names = categoryMutators.joinToString(", ") { "`${it.name.removeSuffix("Mutator")}`" }
                val examples = categorySummaryExample(category)
                appendLine("| **${categoryDisplayName(category)}** | $names | $examples |")
            }
        }.trimEnd() + "\n"
    }

    private fun categoryDisplayName(category: MutatorCategory): String =
        when (category) {
            MutatorCategory.RELATIONAL_BOUNDARY -> "Relational Boundary"
            MutatorCategory.EQUALITY -> "Equality & Identity"
            MutatorCategory.ARITHMETIC_OPERATOR -> "Arithmetic Operators"
            MutatorCategory.COMPOUND_ASSIGNMENT -> "Compound Assignments"
            MutatorCategory.UNARY_OPERATOR -> "Unary Operators"
            MutatorCategory.BOOLEAN_INVERSION -> "Boolean Inversions"
            MutatorCategory.RETURN_VALUE -> "Return Values"
            MutatorCategory.VOID_METHOD_CALL -> "Void Method Calls"
            MutatorCategory.LITERAL_MUTATION -> "Literal Mutations"
            MutatorCategory.COLLECTION_OPERATOR -> "Collection Operators"
            MutatorCategory.CONDITION_REPLACEMENT -> "Condition Replacements"
            MutatorCategory.NULL_SAFETY -> "Null Safety & Elvis"
            MutatorCategory.RANGE_OPERATOR -> "Range Operators"
            MutatorCategory.BITWISE_OPERATOR -> "Bitwise Operators"
            MutatorCategory.COROUTINE -> "Coroutine & Concurrency"
            MutatorCategory.SCOPE_FUNCTION -> "Scope Function"
            MutatorCategory.PRECONDITION -> "Precondition & Defensive Assertion"
            MutatorCategory.RESULT_ERROR_HANDLING -> "Result & Error Handling"
            MutatorCategory.EXTREME -> "Extreme / Structural Mutations"
        }

    private fun categorySummaryExample(category: MutatorCategory): String =
        when (category) {
            MutatorCategory.RELATIONAL_BOUNDARY -> {
                "`<` $\\leftrightarrow$ `<=`, `>` $\\leftrightarrow$ `>=`"
            }

            MutatorCategory.EQUALITY -> {
                "`==` $\\leftrightarrow$ `!=`, `===` $\\leftrightarrow$ `!==`"
            }

            MutatorCategory.ARITHMETIC_OPERATOR -> {
                "`+` $\\leftrightarrow$ `-`, `*` $\\leftrightarrow$ `/`, `%` $\\leftrightarrow$ `*`"
            }

            MutatorCategory.COMPOUND_ASSIGNMENT -> {
                "`+=` $\\leftrightarrow$ `-=`, `*=` $\\leftrightarrow$ `/=`"
            }

            MutatorCategory.UNARY_OPERATOR -> {
                "`+x` $\\leftrightarrow$ `-x`, `++x` $\\leftrightarrow$ `--x`"
            }

            MutatorCategory.BOOLEAN_INVERSION -> {
                "`&&` $\\leftrightarrow$ `||`, `!x` $\\to$ `x`"
            }

            MutatorCategory.RETURN_VALUE -> {
                "`return true` $\\to$ `false`, `return \"\"` $\\to$ `\"mutated\"`"
            }

            MutatorCategory.VOID_METHOD_CALL -> {
                "`doSideEffect()` $\\to$ `Unit`"
            }

            MutatorCategory.LITERAL_MUTATION -> {
                "Constants $+1$, $-1$, `0`"
            }

            MutatorCategory.COLLECTION_OPERATOR -> {
                "`.map` $\\leftrightarrow$ `.mapNotNull`, `.first` $\\leftrightarrow$ `.last`"
            }

            MutatorCategory.CONDITION_REPLACEMENT -> {
                "`if (cond)` $\\to$ `true`, `false`"
            }

            MutatorCategory.NULL_SAFETY -> {
                "`a ?: b` $\\to$ `b`, `a?.b` $\\to$ `a!!.b`"
            }

            MutatorCategory.RANGE_OPERATOR -> {
                "`0 until n` $\\leftrightarrow$ `0..n`, `0..<n` $\\leftrightarrow$ `0..n`"
            }

            MutatorCategory.BITWISE_OPERATOR -> {
                "`a and b` $\\leftrightarrow$ `a or b`, `a xor b` $\\leftrightarrow$ `a and b`"
            }

            MutatorCategory.COROUTINE -> {
                "`Dispatchers.IO` $\\leftrightarrow$ `Default`, `delay(n)` $\\to$ `delay(0)`"
            }

            MutatorCategory.SCOPE_FUNCTION -> {
                "`apply` $\\leftrightarrow$ `also`, `let` $\\leftrightarrow$ `run`, `takeIf` $\\leftrightarrow$ `takeUnless`"
            }

            MutatorCategory.PRECONDITION -> {
                "`require(c)` $\\to$ `require(!c)`, unwrapping `checkNotNull`"
            }

            MutatorCategory.RESULT_ERROR_HANDLING -> {
                "`getOrElse` $\\to$ `getOrThrow`, `onSuccess` $\\leftrightarrow$ `onFailure`"
            }

            MutatorCategory.EXTREME -> {
                "Destructuring variable swapping, data class `copy()` parameter stripping"
            }
        }
}

/**
 * Main entry point invoked by Gradle task `generateMutatorDocs`.
 */
public fun main(args: Array<String>) {
    val targetFile = File(args.getOrNull(0) ?: "docs/wiki/Mutators-Reference.md")
    val generator = DefaultMutatorDocGenerator()
    val content = generator.generateMarkdownReference()
    targetFile.parentFile?.mkdirs()
    targetFile.writeText(content)
    println("Generated AST Mutator Reference -> ${targetFile.absolutePath}")
}
