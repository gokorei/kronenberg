package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class AstMutantGeneratorSpec {
    private val generator = AstMutantGenerator(MutatorRegistry.default())

    @Test
    fun `generates first-order mutants with correct line and column offsets`() {
        val source =
            """
            fun calculateDiscount(price: Double, isVip: Boolean): Double {
                if (price > 100.0 && isVip) {
                    return price * 0.8
                }
                return price
            }
            """.trimIndent()

        val mutants = generator.generateMutants(source, MutationConfig(higherOrderMutants = false))

        mutants shouldHaveAtLeastSize 3

        // Relational operator mutation: > -> >=
        val gtMutant = mutants.firstOrNull { it.mutatorName == "RelationalBoundaryMutator" && it.replacementText == ">=" }
        gtMutant shouldNotBe null
        gtMutant?.let {
            it.line shouldBe 2
            it.mutatedSource shouldContain "price >= 100.0"
        }

        // Return value mutation
        val returnMutant = mutants.firstOrNull { it.mutatorName == "ReturnValueMutator" }
        returnMutant shouldNotBe null
        returnMutant?.let {
            it.mutatedSource shouldNotContain "return price * 0.8"
        }
    }

    @Test
    fun `generates higher-order mutants when configured`() {
        val source =
            """
            fun evaluate(a: Int, b: Int): Boolean {
                val sum = a + b
                return sum > 10
            }
            """.trimIndent()

        val config = MutationConfig(higherOrderMutants = true)
        val mutants = generator.generateMutants(source, config)

        mutants shouldHaveAtLeastSize 1
        mutants.any { it.mutatorName == "CompoundHigherOrderMutator" } shouldBe true
    }

    @Test
    fun `preserves filePath in generated mutants when provided`() {
        val source = "fun add(a: Int, b: Int): Int = a + b"
        val mutants = generator.generateMutants(source, filePath = "src/common/Math.kt")
        mutants shouldHaveAtLeastSize 1
        mutants.all { it.filePath == "src/common/Math.kt" } shouldBe true
    }

    @Test
    fun `suppresses statically invalid mutants such as string concatenation arithmetic and type mismatched returns`() {
        val source =
            """
            fun formatMessage(name: String): String {
                val greeting = "Hello, " + name
                return greeting
            }
            """.trimIndent()
        val mutants = generator.generateMutants(source)

        // ArithmeticOperatorMutator should NOT produce a mutant on String concatenation '+'
        mutants.any { it.mutatorName == "ArithmeticOperatorMutator" } shouldBe false

        // ReturnValueMutator should NOT produce 'false' or '0' for String return type
        val returnMutants = mutants.filter { it.mutatorName == "ReturnValueMutator" }
        returnMutants.any { it.replacementText == "false" } shouldBe false
        returnMutants.any { it.replacementText == "0" } shouldBe false
        returnMutants.any { it.replacementText == "\"\"" } shouldBe true
    }

    @Test
    fun `applies maxMutants while visiting large source surfaces`() {
        val source =
            buildString {
                repeat(250) { index ->
                    appendLine("fun calculate$index(value: Int): Int = value + $index")
                }
            }

        val result = generator.generateMutantsWithMetrics(source, MutationConfig(maxMutants = 1))

        result.mutants shouldHaveSize 1
        result.candidateCount shouldBe 1
        result.discardedCount shouldBe 0
    }

    @Test
    fun `keeps generated mutants within the report result limit`() {
        val source =
            buildString {
                repeat(50) { index ->
                    appendLine("fun calculate$index(value: Int): Int = value + $index")
                }
            }

        val result = generator.generateMutantsWithMetrics(source, MutationConfig(maxReportResults = 2))

        result.mutants shouldHaveSize 2
    }

    @Test
    fun `precomputes source line and enclosing function metadata`() {
        val source =
            """
            fun outer(value: Int): Int {
                return value + 1
            }
            """.trimIndent()
        val file = K2SnippetFrontend.parsePsi(source)
        val metadata = buildSourceMetadata(source, file)

        metadata.lineAndColumn(source.indexOf("value + 1")) shouldBe Pair(2, 12)
        metadata.enclosingFunctionName(2) shouldBe "outer"
    }

    @Test
    fun `computeLineAndColumn correctly calculates 1-indexed coordinates`() {
        val source = "line1\nline2\nline3"
        // Offset 0 = line 1, col 1
        computeLineAndColumn(source, 0) shouldBe Pair(1, 1)
        // Offset 6 = start of 'line2' (5 chars + \n) -> line 2, col 1
        computeLineAndColumn(source, 6) shouldBe Pair(2, 1)
        // Offset 12 = start of 'line3' -> line 3, col 1
        computeLineAndColumn(source, 12) shouldBe Pair(3, 1)
    }
}
