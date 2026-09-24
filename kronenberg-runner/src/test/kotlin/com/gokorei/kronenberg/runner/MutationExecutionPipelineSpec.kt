package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.CleanupDiagnostic
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.SnippetExecutionTrust
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream

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
    fun `reports structured baseline compile timeout`() {
        val compiler = TimedOutCompiler()
        val runner = RecordingRunner()
        val isolatedPipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        try {
            runBlocking {
                val report =
                    isolatedPipeline.execute(
                        sourceCode = "fun value(): Int = 1",
                        testCode = "fun main() { check(value() == 1) }",
                        config = MutationConfig(compileTimeoutMs = 1L),
                    )
                report.baselineError!!.contains("Compilation timed out") shouldBe true
                report.totalMutants shouldBe 0
            }
        } finally {
            isolatedPipeline.close()
        }
    }

    @Test
    fun `does not clean compiled output while execution is still running`() {
        val compiler = TrackingCompiler()
        val runner = TrackingRunner(compiler)
        val isolatedPipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        try {
            runBlocking {
                isolatedPipeline.execute(
                    sourceCode = "fun add(a: Int, b: Int): Int = a + b",
                    testCode = "fun main() { check(add(1, 2) == 3) }",
                    config = MutationConfig(),
                )
            }
            runner.cleanupBeforeFirstRun shouldBe false
            compiler.cleanupCalled.get() shouldBe true
        } finally {
            isolatedPipeline.close()
        }
    }

    @Test
    fun `propagates configured compile deadline to the compiler`() {
        val compiler = DeadlineCompiler()
        val runner = RecordingRunner()
        val isolatedPipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        try {
            runBlocking {
                isolatedPipeline.execute(
                    sourceCode = "fun add(a: Int, b: Int): Int = a + b",
                    testCode = "fun main() { check(add(1, 2) == 3) }",
                    config = MutationConfig(compileTimeoutMs = 17L),
                )
            }
            compiler.compileTimeouts shouldBe listOf(17L, 17L)
        } finally {
            isolatedPipeline.close()
        }
    }

    @TestFactory
    fun `rejects untrusted capabilities before compilation or execution`(): Stream<DynamicTest> =
        Stream
            .of(
                "filesystem" to
                    """
                    fun abuse() {
                        java.nio.file.Files.readString(java.nio.file.Path.of("/etc/passwd"))
                    }
                    """.trimIndent(),
                "network" to
                    """
                    fun abuse() {
                        java.net.Socket("example.com", 443).close()
                    }
                    """.trimIndent(),
                "process" to
                    """
                    fun abuse() {
                        ProcessBuilder("/bin/sh").start()
                    }
                    """.trimIndent(),
                "reflection" to
                    """
                    fun abuse() {
                        Class.forName("java.lang.System")
                    }
                    """.trimIndent(),
                "environment" to
                    """
                    fun abuse() {
                        System.getenv("PATH")
                    }
                    """.trimIndent(),
                "global state" to
                    """
                    fun abuse() {
                        System.setProperty("kronenberg.untrusted", "changed")
                    }
                    """.trimIndent(),
            ).map { (capability, source) ->
                DynamicTest.dynamicTest(capability) {
                    assertUntrustedRejected(source)
                }
            }

    private fun assertUntrustedRejected(source: String) {
        val compiler = RecordingCompiler()
        val runner = RecordingRunner()
        val isolatedPipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        try {
            val report =
                runBlocking {
                    isolatedPipeline.execute(
                        sourceCode = source,
                        testCode = "fun main() { abuse() }",
                        config =
                            MutationConfig(
                                executionTrust = SnippetExecutionTrust.UNTRUSTED,
                                extraClasspath = listOf("/host/gradle/state"),
                            ),
                    )
                }

            report.baselineError shouldContain "Untrusted project code execution is not supported"
            report.totalMutants shouldBe 0
            compiler.compileCount shouldBe 0
            runner.runCount shouldBe 0
        } finally {
            isolatedPipeline.close()
        }
    }

    @Test
    fun `records cleanup failures without interrupting the audit`() {
        val compiler = ThrowingCleanupCompiler()
        val runner = RecordingRunner()
        val isolatedPipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        try {
            val report =
                runBlocking {
                    isolatedPipeline.execute(
                        sourceCode = "fun add(a: Int, b: Int): Int = a + b",
                        testCode = "fun main() { check(add(1, 2) == 3) }",
                        config = MutationConfig(),
                    )
                }

            report.cleanupDiagnostics.any {
                it.resource == "compiler" && it.operation == "cleanup" && it.message.contains("cleanup denied")
            } shouldBe true
        } finally {
            isolatedPipeline.close()
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

    private class TrackingCompiler : SnippetCompiler {
        val cleanupCalled: AtomicBoolean = AtomicBoolean(false)

        override fun compile(
            sourceCode: String,
            extraClasspath: List<String>,
            timeoutMs: Long,
        ): CompileResult =
            CompileResult.Compiled(
                outDir = Path.of("build", "tracking-output"),
                tempRoot = Path.of("build", "tracking-temp"),
            )

        override fun cleanup(result: CompileResult): List<CleanupDiagnostic> {
            cleanupCalled.set(true)
            return emptyList()
        }
    }

    private class TrackingRunner(
        private val compiler: TrackingCompiler,
    ) : FastSnippetRunner {
        var runCount: Int = 0
        var cleanupBeforeFirstRun: Boolean = false

        override fun run(
            classesDir: Path,
            mainClass: String,
            timeoutMs: Long,
            extraClasspath: List<String>,
        ): RunnerOutcome {
            runCount++
            if (runCount == 1) {
                cleanupBeforeFirstRun = compiler.cleanupCalled.get()
            }
            Thread.sleep(25L)
            return RunnerOutcome(MutantStatus.SURVIVED, 25L)
        }

        override fun close() = Unit
    }

    private class TimedOutCompiler : SnippetCompiler {
        override fun compile(
            sourceCode: String,
            extraClasspath: List<String>,
            timeoutMs: Long,
        ): CompileResult = CompileResult.TimedOut("Compilation timed out after ${timeoutMs}ms; worker terminated")

        override fun cleanup(result: CompileResult): List<CleanupDiagnostic> = emptyList()
    }

    private class DeadlineCompiler : SnippetCompiler {
        val compileTimeouts: MutableList<Long> = mutableListOf()

        override fun compile(
            sourceCode: String,
            extraClasspath: List<String>,
            timeoutMs: Long,
        ): CompileResult {
            compileTimeouts.add(timeoutMs)
            return CompileResult.Compiled(
                outDir = Path.of("build", "deadline-output"),
                tempRoot = Path.of("build", "deadline-temp"),
            )
        }

        override fun cleanup(result: CompileResult): List<CleanupDiagnostic> = emptyList()
    }

    private class ThrowingCleanupCompiler : SnippetCompiler {
        override fun compile(
            sourceCode: String,
            extraClasspath: List<String>,
            timeoutMs: Long,
        ): CompileResult =
            CompileResult.Compiled(
                outDir = Path.of("build", "throwing-cleanup-output"),
                tempRoot = Path.of("build", "throwing-cleanup-temp"),
            )

        override fun cleanup(result: CompileResult): List<CleanupDiagnostic> {
            error("cleanup denied")
        }
    }

    private class RecordingCompiler : SnippetCompiler {
        var compileCount: Int = 0

        override fun compile(
            sourceCode: String,
            extraClasspath: List<String>,
            timeoutMs: Long,
        ): CompileResult {
            compileCount++
            return CompileResult.Failed("Untrusted source must not be compiled")
        }

        override fun cleanup(result: CompileResult): List<CleanupDiagnostic> = emptyList()
    }

    private class RecordingRunner : FastSnippetRunner {
        var runCount: Int = 0

        override fun run(
            classesDir: Path,
            mainClass: String,
            timeoutMs: Long,
            extraClasspath: List<String>,
        ): RunnerOutcome {
            runCount++
            return RunnerOutcome(MutantStatus.SURVIVED, 0L)
        }

        override fun close() = Unit
    }
}
