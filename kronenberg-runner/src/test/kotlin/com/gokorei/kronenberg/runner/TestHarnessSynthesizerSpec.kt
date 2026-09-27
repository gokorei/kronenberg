package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        testOnly.requiresSynthesizedMain shouldBe false

        val sourceOnly = TestHarnessSynthesizer.parseTestCode(testWithoutMain, sourceWithMain)
        sourceOnly.testHasMain shouldBe false
        sourceOnly.sourceHasMain shouldBe true
        sourceOnly.requiresSynthesizedMain shouldBe true
        sourceOnly.candidateTests shouldHaveSize 1

        val both = TestHarnessSynthesizer.parseTestCode(testWithMain, sourceWithMain)
        both.testHasMain shouldBe true
        both.sourceHasMain shouldBe true
        both.requiresSynthesizedMain shouldBe false

        val neither = TestHarnessSynthesizer.parseTestCode(testWithoutMain, sourceWithoutMain)
        neither.testHasMain shouldBe false
        neither.sourceHasMain shouldBe false
        neither.requiresSynthesizedMain shouldBe true
        neither.candidateTests shouldHaveSize 1
    }

    @Test
    fun `keeps one main when only source defines it and invokes discovered tests`() {
        val sourceCode = "fun main() { onlyInSourceMain() }\nfun add(a: Int, b: Int): Int = a + b"
        val testCode = "fun testAdd() { check(add(2, 2) == 4) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldNotContain "onlyInSourceMain"
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

    @Test
    fun `preserves file annotations and leading comments when removing the source main`() {
        val sourceCode =
            """
            // leading file comment
            @file:Suppress("UNUSED_VARIABLE")
            @file:JvmName("Renamed")

            package com.example

            import kotlin.math.abs

            fun main() {
                val unused = 1
            }

            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent()
        val testCode = "fun testAdd() { check(add(2, 3) == 5) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        merged shouldContain "// leading file comment"
        merged shouldContain "@file:Suppress(\"UNUSED_VARIABLE\")"
        merged shouldContain "@file:JvmName(\"Renamed\")"
        merged shouldContain "package com.example"
        merged shouldContain "import kotlin.math.abs"
        merged shouldNotContain "val unused"
        merged.indexOf("@file:Suppress") shouldBeLessThan merged.indexOf("package com.example")
        topLevelMainCount(merged) shouldBe 1
    }

    @Test
    fun `hoists file annotations of source and test ahead of the package directive`() {
        val sourceCode = "package com.example\n\nfun add(a: Int, b: Int): Int = a + b"
        val testCode =
            """
            @file:Suppress("unused")

            package com.example

            fun testAdd() {
                check(add(2, 3) == 5)
            }
            """.trimIndent()

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        parsed.fileAnnotations shouldBe listOf("@file:Suppress(\"unused\")")
        parsed.rawBody shouldNotContain "@file:Suppress"

        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        merged.indexOf("@file:Suppress") shouldBeLessThan merged.indexOf("package com.example")
        merged shouldContain "fun testAdd()"
    }

    @Test
    fun `removes every top level source main from the merged program`() {
        val sourceCode =
            """
            fun main() {
                onlyInFirstSourceMain()
            }

            fun main(args: Array<String>) {
                onlyInSecondSourceMain()
            }

            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent()
        val testCode = "fun testAdd() { check(add(2, 3) == 5) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        topLevelMainCount(merged) shouldBe 1
        merged shouldNotContain "onlyInFirstSourceMain"
        merged shouldNotContain "onlyInSecondSourceMain"
        merged shouldContain "testAdd()"
    }

    @Test
    fun `keeps object scoped main declarations intact`() {
        val sourceCode =
            """
            object EntryPoint {
                fun main() {
                    runEntry()
                }
            }

            fun runEntry() {}
            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent()
        val testCode = "fun testAdd() { check(add(2, 3) == 5) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val merged = TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, parsed, null)

        parsed.sourceHasMain shouldBe false
        parsed.requiresSynthesizedMain shouldBe true
        merged shouldContain "object EntryPoint {"
        merged shouldContain "runEntry()"
        topLevelMainCount(merged) shouldBe 1
    }

    @Test
    fun `reports the line range of every removed source main`() {
        val sourceCode =
            """
            fun main() {
                val first = 1
                val second = 2
            }

            fun main(args: Array<String>) {
                require(args.isEmpty())
            }

            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent()
        val testCode = "fun testAdd() { check(add(2, 3) == 5) }"

        val parsed = TestHarnessSynthesizer.parseTestCode(testCode, sourceCode)
        val ranges = TestHarnessSynthesizer.removedSourceMainLineRanges(sourceCode, parsed)

        ranges shouldHaveSize 2
        ranges[0] shouldBe (1..4)
        ranges[1] shouldBe (6..8)
    }

    @Test
    fun `reports no removed main ranges when the source entry point is retained`() {
        val sourceCode = "fun main() {}\nfun add(a: Int, b: Int): Int = a + b"

        val blankTest = TestHarnessSynthesizer.parseTestCode("   ", sourceCode)
        TestHarnessSynthesizer.removedSourceMainLineRanges(sourceCode, blankTest).shouldBeEmpty()

        val helperOnlyTest = TestHarnessSynthesizer.parseTestCode("fun helper() {}", sourceCode)
        TestHarnessSynthesizer.removedSourceMainLineRanges(sourceCode, helperOnlyTest).shouldBeEmpty()
        topLevelMainCount(TestHarnessSynthesizer.mergeSourceWithParsedTest(sourceCode, helperOnlyTest, null)) shouldBe 1
    }

    @Test
    fun `strips package and imports while retaining file annotations`() {
        val code = "package com.example\n\nimport kotlin.math.abs\n\nfun add(a: Int, b: Int): Int = a + b"
        val file = K2SnippetFrontend.parsePsi(code)

        TestHarnessSynthesizer.stripPackageAndImports(code, file) shouldBe "fun add(a: Int, b: Int): Int = a + b"
        TestHarnessSynthesizer.stripPackageAndImports("fun add() = 1", K2SnippetFrontend.parsePsi("fun add() = 1")) shouldBe
            "fun add() = 1"
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated hasMain shim reports the union of both entry points`() {
        val legacy =
            ParsedTestCode(
                packageDirective = null,
                imports = emptyList(),
                rawBody = "fun testAdd() {}",
                hasMain = true,
                candidateTests = listOf(CandidateTestFunction("testAdd", emptySet())),
            )

        legacy.testHasMain shouldBe true
        legacy.sourceHasMain shouldBe false
        legacy.requiresSynthesizedMain shouldBe false
        legacy.hasMain shouldBe true
        legacy.hasMain shouldBe legacy.testHasMain.or(legacy.sourceHasMain)

        val sourceOnly = TestHarnessSynthesizer.parseTestCode("fun testAdd() {}", "fun main() {}\nfun add() = 1")
        sourceOnly.testHasMain shouldBe false
        sourceOnly.sourceHasMain shouldBe true
        sourceOnly.requiresSynthesizedMain shouldBe true
        sourceOnly.hasMain shouldBe true
    }

    private fun topLevelMainCount(code: String): Int =
        K2SnippetFrontend
            .parsePsi(code)
            .declarations
            .filterIsInstance<KtNamedFunction>()
            .count { it.name == "main" }
}
