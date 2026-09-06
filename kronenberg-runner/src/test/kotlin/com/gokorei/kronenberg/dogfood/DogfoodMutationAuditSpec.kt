package com.gokorei.kronenberg.dogfood

import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.runner.DefaultMutationExecutionPipeline
import com.gokorei.kronenberg.runner.MutationExecutionPipeline
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DogfoodMutationAuditSpec {
    private val pipeline: MutationExecutionPipeline = DefaultMutationExecutionPipeline()

    @Nested
    inner class CoreUtilityDogfoodTests {
        @Test
        fun `dogfood - audits computeLineAndColumn coordinate logic with self-tests`() {
            val sourceToMutate =
                """
                fun computeLineAndColumn(source: String, offset: Int): Pair<Int, Int> {
                    var line = 1
                    var lastLineBreak = -1
                    val safeOffset = offset.coerceIn(0, source.length)
                    for (i in 0 until safeOffset) {
                        if (source[i] == '\n') {
                            line++
                            lastLineBreak = i
                        }
                    }
                    val col = safeOffset - lastLineBreak
                    return Pair(line, col)
                }
                """.trimIndent()

            val testHarness =
                """
                fun testCoordinates() {
                    val text = "hello\nworld\nfoo"
                    check(computeLineAndColumn(text, 0) == Pair(1, 1))
                    check(computeLineAndColumn(text, 5) == Pair(1, 6))
                    check(computeLineAndColumn(text, 6) == Pair(2, 1))
                    check(computeLineAndColumn(text, 11) == Pair(2, 6))
                    check(computeLineAndColumn(text, 12) == Pair(3, 1))
                    check(computeLineAndColumn("", 0) == Pair(1, 1))
                    check(computeLineAndColumn(text, 999) == Pair(3, 4))
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = sourceToMutate,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 80.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }

        @Test
        fun `dogfood - audits mutation score and pass-fail calculation with self-tests`() {
            val scoreCalcSource =
                """
                fun calculateScore(killed: Int, total: Int, compileErrors: Int): Double {
                    val valid = total - compileErrors
                    if (valid <= 0) return 100.0
                    return (killed.toDouble() / valid) * 100.0
                }

                fun isPassed(survivedCount: Int): Boolean {
                    return survivedCount == 0
                }
                """.trimIndent()

            val testHarness =
                """
                fun testScoreAndPass() {
                    check(calculateScore(10, 10, 0) == 100.0)
                    check(calculateScore(8, 10, 0) == 80.0)
                    check(calculateScore(0, 5, 0) == 0.0)
                    check(calculateScore(5, 10, 5) == 100.0)
                    check(calculateScore(0, 0, 0) == 100.0)
                    check(isPassed(0) == true)
                    check(isPassed(1) == false)
                    check(isPassed(5) == false)
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = scoreCalcSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 90.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }
    }

    @Nested
    inner class RegistryAndOperatorDogfoodTests {
        @Test
        fun `dogfood - audits MutatorRegistry category filter predicate with self-tests`() {
            val filterSource =
                """
                fun isExtremeCategory(categoryName: String): Boolean {
                    return categoryName == "EXTREME" ||
                           categoryName == "LITERAL_MUTATION" ||
                           categoryName == "CONDITION_REPLACEMENT"
                }

                fun filterCategories(categories: List<String>, includeExtreme: Boolean): List<String> {
                    if (includeExtreme) return categories
                    return categories.filter { !isExtremeCategory(it) }
                }
                """.trimIndent()

            val testHarness =
                """
                fun testCategoryFiltering() {
                    val all = listOf("RELATIONAL_BOUNDARY", "ARITHMETIC_OPERATOR", "EXTREME", "LITERAL_MUTATION")
                    val standardOnly = filterCategories(all, includeExtreme = false)
                    check(standardOnly.size == 2)
                    check(standardOnly.contains("RELATIONAL_BOUNDARY"))
                    check(standardOnly.contains("ARITHMETIC_OPERATOR"))
                    check(!standardOnly.contains("EXTREME"))
                    check(!standardOnly.contains("LITERAL_MUTATION"))

                    val allIncluded = filterCategories(all, includeExtreme = true)
                    check(allIncluded.size == 4)
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = filterSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 85.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }

        @Test
        fun `dogfood - audits operator mapping tables with self-tests`() {
            val operatorMapSource =
                """
                fun invertRelational(op: String): String {
                    if (op == "<") return "<="
                    if (op == "<=") return "<"
                    if (op == ">") return ">="
                    if (op == ">=") return ">"
                    if (op == "==") return "!="
                    if (op == "!=") return "=="
                    return op
                }

                fun invertArithmetic(op: String): String {
                    if (op == "+") return "-"
                    if (op == "-") return "+"
                    if (op == "*") return "/"
                    if (op == "/") return "*"
                    if (op == "%") return "*"
                    return op
                }
                """.trimIndent()

            val testHarness =
                """
                fun testOperatorInversions() {
                    check(invertRelational("<") == "<=")
                    check(invertRelational("<=") == "<")
                    check(invertRelational(">") == ">=")
                    check(invertRelational(">=") == ">")
                    check(invertRelational("==") == "!=")
                    check(invertRelational("!=") == "==")
                    check(invertRelational("unknown") == "unknown")

                    check(invertArithmetic("+") == "-")
                    check(invertArithmetic("-") == "+")
                    check(invertArithmetic("*") == "/")
                    check(invertArithmetic("/") == "*")
                    check(invertArithmetic("%") == "*")
                    check(invertArithmetic("unknown") == "unknown")
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = operatorMapSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 90.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }
    }

    @Nested
    inner class RuntimeAndCliDogfoodTests {
        @Test
        fun `dogfood - audits baseline timeout calibration logic with self-tests`() {
            val timeoutCalibratorSource =
                """
                fun calibrateTimeout(baselineMs: Long, multiplier: Double, minFloorMs: Long, maxCapMs: Long): Long {
                    val raw = (baselineMs * multiplier).toLong()
                    return raw.coerceIn(minFloorMs, maxCapMs)
                }
                """.trimIndent()

            val testHarness =
                """
                fun testTimeoutCalibration() {
                    // Normal scaling
                    check(calibrateTimeout(100L, 3.0, 50L, 5000L) == 300L)
                    // Floor enforcement
                    check(calibrateTimeout(10L, 2.0, 100L, 5000L) == 100L)
                    // Ceiling cap enforcement
                    check(calibrateTimeout(2000L, 4.0, 50L, 5000L) == 5000L)
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = timeoutCalibratorSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 85.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }

        @Test
        fun `dogfood - audits CLI threshold exit gate logic with self-tests`() {
            val cliGateSource =
                """
                fun evaluateExitCode(score: Double, threshold: Double): Int {
                    return if (score >= threshold) 0 else 1
                }
                """.trimIndent()

            val testHarness =
                """
                fun testCliGate() {
                    check(evaluateExitCode(80.0, 80.0) == 0)
                    check(evaluateExitCode(85.0, 80.0) == 0)
                    check(evaluateExitCode(79.9, 80.0) == 1)
                    check(evaluateExitCode(0.0, 50.0) == 1)
                    check(evaluateExitCode(100.0, 100.0) == 0)
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = cliGateSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 90.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }

        @Test
        fun `dogfood - audits unified diff line generation with self-tests`() {
            val diffFormatterSource =
                """
                fun formatDiffLine(isAddition: Boolean, text: String): String {
                    val prefix = if (isAddition) "+" else "-"
                    val trimmed = text.trim()
                    return "${'$'}prefix ${'$'}trimmed"
                }

                fun formatLocation(file: String, line: Int, col: Int): String {
                    return "${'$'}file:${'$'}line:${'$'}col"
                }
                """.trimIndent()

            val testHarness =
                """
                fun testDiffFormatting() {
                    check(formatDiffLine(true, " val x = 10 ") == "+ val x = 10")
                    check(formatDiffLine(false, " val x = 5 ") == "- val x = 5")
                    check(formatLocation("Foo.kt", 42, 15) == "Foo.kt:42:15")
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = diffFormatterSource,
                        testCode = testHarness,
                        config = MutationConfig(minScore = 85.0),
                    )
                report.totalMutants shouldBeGreaterThan 0
                report.killedCount shouldBeGreaterThan 0
                report.survivedCount shouldBe 0
                report.isPassed shouldBe true
            }
        }
    }
}
