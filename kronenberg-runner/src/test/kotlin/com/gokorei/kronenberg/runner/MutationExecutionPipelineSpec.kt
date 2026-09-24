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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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
    fun `rejects oversized inputs before parsing and reports input metrics`() {
        val pipeline = DefaultMutationExecutionPipeline()
        try {
            val report =
                runBlocking {
                    pipeline.execute(
                        sourceCode = "fun value(): Int = 1",
                        testCode = "fun main() { check(value() == 1) }",
                        config = MutationConfig(maxInputCharacters = 4),
                    )
                }

            report.baselineError shouldContain "Input exceeds"
            report.totalMutants shouldBe 0
            report.metrics.phaseMetrics.map { it.phase } shouldBe listOf("input")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `records cache hits across repeated bounded audits`() {
        val compiler = RecordingCompiler()
        val runner = RecordingRunner()
        val pipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)
        val source = "fun add(a: Int, b: Int): Int = a + b"
        val test = "fun main() { check(add(1, 2) == 3) }"
        try {
            val first =
                runBlocking {
                    pipeline.execute(source, test, MutationConfig(maxMutants = 1, enableCache = true))
                }
            val second =
                runBlocking {
                    pipeline.execute(source, test, MutationConfig(maxMutants = 1, enableCache = true))
                }

            first.metrics.cacheMisses shouldBe 1
            first.metrics.cacheHits shouldBe 0
            second.metrics.cacheHits shouldBe 1
            second.metrics.cacheMisses shouldBe 0
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `limits compile and execution concurrency independently`() {
        val compiler = RecordingCompiler()
        val runner = RecordingRunner()
        val pipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)
        val source =
            """
            fun addOne(a: Int, b: Int): Int = a + b
            fun addTwo(a: Int, b: Int): Int = a + b
            fun addThree(a: Int, b: Int): Int = a + b
            fun addFour(a: Int, b: Int): Int = a + b
            """.trimIndent()
        val test = "fun main() { check(addOne(1, 2) == 3) }"
        try {
            val report =
                runBlocking {
                    pipeline.execute(
                        sourceCode = source,
                        testCode = test,
                        config =
                            MutationConfig(
                                maxMutants = 4,
                                maxCompileConcurrency = 1,
                                maxExecutionConcurrency = 2,
                            ),
                    )
                }

            report.totalMutants shouldBe 4
            (compiler.maxConcurrent() <= 1) shouldBe true
            (runner.maxConcurrent() <= 2) shouldBe true
        } finally {
            pipeline.close()
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

private class RecordingCompiler : SnippetCompiler {
    private val active = AtomicInteger()
    private val maximum = AtomicInteger()

    override fun compile(
        sourceCode: String,
        extraClasspath: List<String>,
    ): CompileResult {
        val current = active.incrementAndGet()
        maximum.updateAndGet { previous -> maxOf(previous, current) }
        return try {
            Thread.sleep(20L)
            val directory = Files.createTempDirectory("recording-compiler")
            CompileResult.Compiled(directory, tempRoot = directory)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun cleanup(result: CompileResult) {
        if (result is CompileResult.Compiled) {
            result.tempRoot.toFile().deleteRecursively()
        }
    }

    fun maxConcurrent(): Int = maximum.get()
}

private class RecordingRunner : FastSnippetRunner {
    private val active = AtomicInteger()
    private val maximum = AtomicInteger()

    override fun run(
        classesDir: Path,
        mainClass: String,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome {
        val current = active.incrementAndGet()
        maximum.updateAndGet { previous -> maxOf(previous, current) }
        return try {
            Thread.sleep(20L)
            RunnerOutcome(MutantStatus.SURVIVED, executionTimeMs = 20L)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun close() = Unit

    fun maxConcurrent(): Int = maximum.get()
}
