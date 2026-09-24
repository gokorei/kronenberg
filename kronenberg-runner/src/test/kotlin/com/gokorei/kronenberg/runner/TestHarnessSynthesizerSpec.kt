package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Test

class TestHarnessSynthesizerSpec {
    @Test
    fun `discovers test methods from class declarations annotated with Test`() {
        val testCode =
            """
            import org.junit.jupiter.api.Test

            class CalculatorTest {
                @Test
                fun testAddition() {
                    check(add(1, 2) == 3)
                }

                @Test
                fun testSubtraction() {
                    check(subtract(3, 1) == 2)
                }

                fun helperMethod() {
                    // Not a test
                }
            }
            """.trimIndent()

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, "")
        parsed.testHasMain shouldBe false
        parsed.candidateTests shouldHaveSize 2
        parsed.candidateTests.map { it.name } shouldBe listOf("testAddition", "testSubtraction")
        parsed.candidateTests.all { it.className == "CalculatorTest" } shouldBe true
    }

    @Test
    fun `synthesizes fun main instantiating test class and calling member methods`() {
        val sourceCode = "fun add(a: Int, b: Int): Int = a + b"
        val testCode =
            """
            class MyMathTest {
                fun testAdd() {
                    check(add(2, 2) == 4)
                }
            }
            """.trimIndent()

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        merged shouldContain "fun main() {"
        merged shouldContain "MyMathTest().testAdd()"
        merged shouldContain "Killed by MyMathTest.testAdd():"
    }

    @Test
    fun `distinguishes source and test entry points across the main matrix`() {
        val sourceWithMain = "fun main() {}\nfun value(): Int = 1"
        val sourceWithoutMain = "fun value(): Int = 1"
        val testWithMain = "fun main() {}\nfun testValue() { check(value() == 1) }"
        val testWithoutMain = "fun testValue() { check(value() == 1) }"

        val testOnly = TestHarnessSynthesizer.parseTestCode(testWithMain, sourceWithoutMain)
        testOnly.testHasMain shouldBe true
        testOnly.sourceHasMain shouldBe false

        val sourceOnly = TestHarnessSynthesizer.parseTestCode(testWithoutMain, sourceWithMain)
        sourceOnly.testHasMain shouldBe false
        sourceOnly.sourceHasMain shouldBe true
        sourceOnly.candidateTests shouldHaveSize 1

        val both = TestHarnessSynthesizer.parseTestCode(testWithMain, sourceWithMain)
        both.testHasMain shouldBe true
        both.sourceHasMain shouldBe true

        val neither = TestHarnessSynthesizer.parseTestCode(testWithoutMain, sourceWithoutMain)
        neither.testHasMain shouldBe false
        neither.sourceHasMain shouldBe false
        neither.candidateTests shouldHaveSize 1
    }

    @Test
    fun `keeps one main when only source defines it and invokes discovered tests`() {
        val sourceCode = "fun main() { sourceMainMustNotRun() }\nfun add(a: Int, b: Int): Int = a + b"
        val testCode = "fun testAdd() { check(add(2, 2) == 4) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldContain "testAdd()"
    }

    @Test
    fun `keeps one main when both source and test define it`() {
        val sourceCode = "fun main() {}\nfun add(a: Int, b: Int): Int = a + b"
        val testCode = "fun main() { check(add(2, 2) == 4) }\nfun testAdd() { check(add(2, 2) == 4) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldContain "check(add(2, 2) == 4)"
    }

    @Test
    fun `keeps test main authoritative when only test defines it`() {
        val sourceCode = "fun add(a: Int, b: Int): Int = a + b"
        val testCode = "fun main() { check(add(2, 2) == 4) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldContain "fun main() { check(add(2, 2) == 4) }"
    }

    @Test
    fun `keeps one synthesized main when neither source nor test defines it`() {
        val sourceCode = "fun add(a: Int, b: Int): Int = a + b"
        val testCode = "fun testAdd() { check(add(2, 2) == 4) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldContain "fun main() {"
        merged shouldContain "testAdd()"
    }

    private fun topLevelMainCount(code: String): Int =
        K2SnippetFrontend
            .parsePsi(code)
            .declarations
            .filterIsInstance<KtNamedFunction>()
            .count { it.name == "main" }
}
