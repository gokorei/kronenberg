package com.gokorei.kronenberg.runner

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        parsed.hasMain shouldBe false
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
}
