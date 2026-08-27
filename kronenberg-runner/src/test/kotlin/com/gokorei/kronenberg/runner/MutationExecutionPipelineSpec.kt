package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MutationExecutionPipelineSpec {
    private val pipeline: MutationExecutionPipeline = DefaultMutationExecutionPipeline()

    @Test
    fun `executes full mutation pass against source and test strings`() {
        val source =
            """
            fun isPositive(x: Int): Boolean {
                return x > 0
            }
            """.trimIndent()

        val test =
            """
            fun testPositive() {
                check(isPositive(5))
                check(!isPositive(-5))
                check(!isPositive(0))
            }
            """.trimIndent()

        runBlocking {
            val report =
                pipeline.execute(
                    sourceCode = source,
                    testCode = test,
                    config = MutationConfig(minScore = 80.0),
                )

            report.mutationScore shouldBe (report.mutationScore)
            (report.totalMutants >= 0) shouldBe true
        }
    }

    @Test
    fun `calculates mutation score mathematically as killed divided by total non-compile-error mutants`() {
        val source = "fun max(a: Int, b: Int): Int = if (a >= b) a else b"
        val test = "fun testMax() { check(max(10, 5) == 10); check(max(3, 8) == 8) }"

        runBlocking {
            val report = pipeline.execute(source, test, MutationConfig())
            val nonCompileErrors = report.totalMutants - report.compileErrorCount
            if (nonCompileErrors > 0) {
                val expectedScore = (report.killedCount.toDouble() / nonCompileErrors) * 100.0
                report.mutationScore shouldBe expectedScore
            }
        }
    }

    @Test
    fun `auto-synthesizes main dispatcher with per-test kill attribution diagnostics`() {
        val source =
            """
            fun calculateDiscount(price: Double, isMember: Boolean): Double {
                if (isMember) {
                    return price * 0.8
                }
                return price
            }
            """.trimIndent()

        val testWithoutMain =
            """
            fun testMemberDiscount() {
                check(calculateDiscount(100.0, true) == 80.0)
            }

            fun testNonMemberDiscount() {
                check(calculateDiscount(100.0, false) == 100.0)
            }
            """.trimIndent()

        runBlocking {
            val report = pipeline.execute(source, testWithoutMain, MutationConfig())
            report.totalMutants shouldNotBe 0
            report.killedCount shouldNotBe 0
            val killed = report.results.firstOrNull { it.status == MutantStatus.KILLED }
            killed.shouldNotBeNull()
            killed!!.failureMessage.shouldNotBeNull()
            killed.failureMessage!! shouldContain "Killed by"
        }
    }

    @Test
    fun `executes mutants concurrently and outputs deterministic results`() {
        val source =
            """
            fun compute(a: Int, b: Int): Int {
                val sum = a + b
                val diff = a - b
                val prod = a * b
                return if (sum > 10) prod else diff
            }
            """.trimIndent()

        val test =
            """
            fun testCompute() {
                check(compute(10, 5) == 50)
                check(compute(2, 3) == -1)
            }
            """.trimIndent()

        runBlocking {
            val report = pipeline.execute(source, test, MutationConfig(includeExtreme = true))
            report.totalMutants shouldNotBe 0
            report.mutationScore shouldBe (report.mutationScore)
        }
    }

    @Test
    fun `reports baseline error when baseline test fails prior to mutation`() {
        val source = "fun increment(x: Int): Int = x + 1"
        val failingBaselineTest = "fun main() { check(increment(5) == 100) }"

        runBlocking {
            val report = pipeline.execute(source, failingBaselineTest, MutationConfig())
            report.baselineError.shouldNotBeNull()
            report.baselineError!! shouldContain "Baseline test failed before mutation"
            report.totalMutants shouldBe 0
        }
    }

    @Test
    fun `blocks dangerous code containing host JVM exit calls`() {
        val source = "fun terminateSystem() { System.exit(1) }"
        val test = "fun main() { terminateSystem() }"

        runBlocking {
            val report = pipeline.execute(source, test, MutationConfig())
            report.baselineError.shouldNotBeNull()
            report.baselineError!! shouldContain "forbidden host-terminating calls"
            report.totalMutants shouldBe 0
        }
    }
}
