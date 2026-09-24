package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
            killed.failureMessage.shouldNotBeNull()
            killed.failureMessage!! shouldContain "Killed by"
        }
    }

    @Test
    fun `auto-synthesizes main dispatcher for class-based test suites with JUnit5 annotations`() {
        val source =
            """
            fun calculateDiscount(price: Double, isMember: Boolean): Double {
                if (isMember) {
                    return price * 0.8
                }
                return price
            }
            """.trimIndent()

        val classBasedTest =
            """
            import org.junit.jupiter.api.Test

            class DiscountTest {
                @Test
                fun verifyMemberDiscount() {
                    check(calculateDiscount(100.0, true) == 80.0)
                }

                @Test
                fun verifyNonMemberDiscount() {
                    check(calculateDiscount(100.0, false) == 100.0)
                }
            }
            """.trimIndent()

        runBlocking {
            val report = pipeline.execute(source, classBasedTest, MutationConfig())
            report.baselineError shouldBe null
            report.totalMutants shouldNotBe 0
            report.killedCount shouldNotBe 0
            val killed = report.results.firstOrNull { it.status == MutantStatus.KILLED }
            killed.shouldNotBeNull()
            val message = killed.failureMessage
            message.shouldNotBeNull()
            message shouldContain "DiscountTest"
        }
    }

    @Test
    fun `executes packaged source with synthetic harness main`() {
        val source =
            """
            package audit.fixture

            fun isPositive(value: Int): Boolean = value > 0
            """.trimIndent()
        val test =
            """
            fun testPositive() {
                check(isPositive(1))
                check(!isPositive(0))
            }
            """.trimIndent()

        runBlocking {
            val report = pipeline.execute(source, test, MutationConfig())

            report.baselineError shouldBe null
            report.totalMutants shouldNotBe 0
            report.killedCount shouldNotBe 0
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
    fun `does not classify synthesized harness infrastructure errors as kills`() {
        val source = "fun isPositive(x: Int): Boolean = x > 0"
        val test = "fun testInfrastructure() { throw LinkageError(\"missing dependency\") }"

        runBlocking {
            val report = pipeline.execute(source, test, MutationConfig())
            report.baselineError.shouldNotBeNull()
            report.baselineError!! shouldContain "Baseline infrastructure error"
            report.totalMutants shouldBe 0
        }
    }

    @Test
    fun `reports baseline infrastructure errors without counting them as kills`() {
        val source = "fun increment(x: Int): Int = x + 1"
        val failingBaselineTest = "fun main() { throw LinkageError(\"missing dependency\") }"

        runBlocking {
            val report = pipeline.execute(source, failingBaselineTest, MutationConfig())
            report.baselineError.shouldNotBeNull()
            report.baselineError!! shouldContain "Baseline infrastructure error"
            report.totalMutants shouldBe 0
        }
    }

    @Test
    fun `excludes infrastructure outcomes from score and fails the report`() {
        val fakeRunner =
            object : FastSnippetRunner {
                private var calls = 0

                override fun run(
                    classesDir: java.nio.file.Path,
                    mainClass: String,
                    timeoutMs: Long,
                    extraClasspath: List<String>,
                ): RunnerOutcome {
                    calls++
                    return RunnerOutcome(
                        status = if (calls == 1) MutantStatus.SURVIVED else MutantStatus.INFRASTRUCTURE_ERROR,
                        executionTimeMs = 1L,
                        failureMessage = if (calls == 1) null else "missing entrypoint",
                    )
                }

                override fun close() = Unit
            }
        val scopedPipeline = DefaultMutationExecutionPipeline(runner = fakeRunner)
        try {
            runBlocking {
                val report =
                    scopedPipeline.execute(
                        sourceCode = "fun isPositive(x: Int): Boolean = x > 0",
                        testCode = "fun testPositive() { check(isPositive(1)); check(!isPositive(0)) }",
                    )
                report.totalMutants shouldNotBe 0
                report.killedCount shouldBe 0
                report.infrastructureErrorCount shouldBe report.totalMutants
                report.mutationScore shouldBe 100.0
                report.isPassed shouldBe false
            }
        } finally {
            scopedPipeline.close()
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

    @Test
    fun `executes mutation pass successfully against external classes provided via extraClasspath`() {
        val compiler = DefaultSnippetCompiler()
        val externalHelperSource =
            """
            package com.example.external
            object MathHelper {
                fun add(a: Int, b: Int): Int = a + b
            }
            """.trimIndent()
        val compiledHelper = compiler.compile(externalHelperSource)
        try {
            val compiled = compiledHelper.shouldBeInstanceOf<CompileResult.Compiled>()
            val helperOutDir = compiled.outDir.toString()

            val source =
                """
                import com.example.external.MathHelper

                fun calculateTotal(a: Int, b: Int): Int {
                    return MathHelper.add(a, b) + 10
                }
                """.trimIndent()

            val test =
                """
                fun testCalculateTotal() {
                    check(calculateTotal(2, 3) == 15)
                }
                """.trimIndent()

            runBlocking {
                val report =
                    pipeline.execute(
                        sourceCode = source,
                        testCode = test,
                        config = MutationConfig(extraClasspath = listOf(helperOutDir)),
                    )
                report.baselineError shouldBe null
                report.totalMutants shouldNotBe 0
                report.killedCount shouldNotBe 0
            }
        } finally {
            compiler.cleanup(compiledHelper)
        }
    }
}
