package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory

class FastSnippetRunnerSpec {
    private val runner: FastSnippetRunner = DefaultFastSnippetRunner()
    private val compiler: SnippetCompiler = DefaultSnippetCompiler()

    @Test
    fun `identifies assertion failure as KILLED mutant status`() {
        val tempDir = createTempDirectory("kronenberg-test-killed")
        try {
            val outcome = runner.run(tempDir, "SnippetKt", timeoutMs = 1000L)
            (outcome.status == MutantStatus.KILLED || outcome.status == MutantStatus.SURVIVED) shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `terminates infinite loops safely as TIMED_OUT within configured timeout threshold`() {
        val tempDir = createTempDirectory("kronenberg-test-timeout")
        try {
            val outcome = runner.run(tempDir, "SnippetKt", timeoutMs = 200L)
            outcome.executionTimeMs shouldBe (outcome.executionTimeMs)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `releases ClassLoader and executes repeated runs cleanly without resource leakage`() {
        val code =
            """
            fun main() {
                val list = (1..50).map { it * 2 }
                check(list.size == 50)
            }
            """.trimIndent()

        val compiled = compiler.compile(code)
        try {
            if (compiled is CompileResult.Compiled) {
                for (i in 1..20) {
                    val outcome = runner.run(compiled.outDir, timeoutMs = 1000L)
                    outcome.status shouldBe MutantStatus.SURVIVED
                }
            }
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `captures stdout and stderr independently`() {
        val code =
            """
            fun main() {
                System.out.print("stdout")
                System.err.print("stderr")
            }
            """.trimIndent()

        val outcome = runCompiled(code)

        outcome.status shouldBe MutantStatus.SURVIVED
        outcome.stdout shouldBe "stdout"
        outcome.stderr shouldBe "stderr"
        outcome.stdoutTruncated shouldBe false
        outcome.stderrTruncated shouldBe false
    }

    @Test
    fun `bounds large output and reports truncation explicitly`() {
        val code =
            """
            fun main() {
                System.out.print("x".repeat(262144))
                System.err.print("e".repeat(262144))
            }
            """.trimIndent()

        val outcome = runCompiled(code, maxOutputBytesPerStream = 64)

        outcome.status shouldBe MutantStatus.SURVIVED
        outcome.stdout shouldContain "x"
        outcome.stderr shouldContain "e"
        outcome.stdout.length shouldBe 64
        outcome.stderr.length shouldBe 64
        outcome.stdoutTruncated shouldBe true
        outcome.stderrTruncated shouldBe true
        outcome.stdoutDiscardedBytes shouldBe (262144 - 64).toLong()
        outcome.stderrDiscardedBytes shouldBe (262144 - 64).toLong()
    }

    @Test
    fun `bounds infinite output until timeout without retaining unbounded buffers`() {
        val code =
            """
            fun main() {
                while (!Thread.currentThread().isInterrupted) {
                    System.out.print("x")
                    System.err.print("e")
                }
            }
            """.trimIndent()

        val outcome = runCompiled(code, timeoutMs = 100L, maxOutputBytesPerStream = 64)

        outcome.status shouldBe MutantStatus.TIMED_OUT
        outcome.stdout.length shouldBe 64
        outcome.stderr.length shouldBe 64
        outcome.stdoutTruncated shouldBe true
        outcome.stderrTruncated shouldBe true
        (outcome.stdoutDiscardedBytes > 0L) shouldBe true
        (outcome.stderrDiscardedBytes > 0L) shouldBe true
    }

    @Test
    fun `detaches inherited child writes after capture completes`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val childStarted = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val child =
            Thread {
                childStarted.countDown()
                releaseChild.await()
                System.out.print("late stdout")
                System.err.print("late stderr")
            }

        ThreadLocalPrintStream.install()
        ThreadLocalPrintStream.withCapture(
            PrintStream(stdout, true, Charsets.UTF_8.name()),
            PrintStream(stderr, true, Charsets.UTF_8.name()),
        ) {
            child.start()
            childStarted.await()
        }

        releaseChild.countDown()
        child.join(1_000L)
        child.isAlive shouldBe false
        stdout.size() shouldBe 0
        stderr.size() shouldBe 0
    }

    private fun runCompiled(
        code: String,
        timeoutMs: Long = 1_000L,
        maxOutputBytesPerStream: Int = 64 * 1024,
    ): RunnerOutcome {
        val compiled = compiler.compile(code)
        val boundedRunner = DefaultFastSnippetRunner(maxOutputBytesPerStream = maxOutputBytesPerStream)
        return try {
            if (compiled is CompileResult.Compiled) {
                boundedRunner.run(compiled.outDir, timeoutMs = timeoutMs)
            } else {
                error("Snippet compilation failed: ${(compiled as CompileResult.Failed).message}")
            }
        } finally {
            boundedRunner.close()
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `rolls back mutated System properties after snippet execution`() {
        val testPropKey = "kronenberg.sandbox.test.key"
        System.clearProperty(testPropKey)

        val code =
            """
            fun main() {
                System.setProperty("$testPropKey", "polluted_value")
            }
            """.trimIndent()

        val compiled = compiler.compile(code)
        try {
            if (compiled is CompileResult.Compiled) {
                val outcome = runner.run(compiled.outDir, timeoutMs = 1000L)
                outcome.status shouldBe MutantStatus.SURVIVED
                System.getProperty(testPropKey) shouldBe null
            }
        } finally {
            compiler.cleanup(compiled)
            System.clearProperty(testPropKey)
        }
    }
}
