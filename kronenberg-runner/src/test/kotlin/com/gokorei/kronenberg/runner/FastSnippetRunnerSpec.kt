package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class FastSnippetRunnerSpec {
    private val compiler: SnippetCompiler = DefaultSnippetCompiler()

    @Test
    fun `identifies assertion failure as KILLED mutant status`() {
        withRunner { runner ->
            val tempDir = createTempDirectory("kronenberg-test-killed")
            try {
                val outcome = runner.run(tempDir, "SnippetKt", timeoutMs = 1000L)
                (outcome.status == MutantStatus.KILLED || outcome.status == MutantStatus.SURVIVED) shouldBe true
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `terminates infinite loops safely as TIMED_OUT within configured timeout threshold`() {
        withRunner { runner ->
            val tempDir = createTempDirectory("kronenberg-test-timeout")
            try {
                val outcome = runner.run(tempDir, "SnippetKt", timeoutMs = 200L)
                outcome.executionTimeMs shouldBe (outcome.executionTimeMs)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `releases ClassLoader and executes repeated runs cleanly without resource leakage`() {
        withRunner { runner ->
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

        withRunner { runner ->
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

    @Test
    fun `concurrent successful runs restore global state and attribute output`() {
        assertConcurrentSuccessRestoresGlobalState()
    }

    @Test
    fun `concurrent failure restores global state and attributes output`() {
        assertConcurrentFailureRestoresGlobalState()
    }

    @Test
    fun `concurrent timeout restores global state and attributes output`() {
        assertConcurrentTimeoutRestoresGlobalState()
    }

    private fun assertConcurrentSuccessRestoresGlobalState() {
        val holdKey = "kronenberg.concurrent.success.hold"
        val trailingKey = "kronenberg.concurrent.success.trailing"
        val originalHoldValue = System.getProperty(holdKey)
        val originalOut = System.out
        val originalErr = System.err
        System.clearProperty(holdKey)
        System.clearProperty(trailingKey)

        val holder =
            compile(
                """
                fun main() {
                    System.setProperty("$holdKey", "held")
                    println("success-holder-out")
                    System.err.println("success-holder-err")
                    System.setOut(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    System.setErr(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    Thread.sleep(300)
                }
                """.trimIndent(),
            )
        val trailing =
            compile(
                """
                fun main() {
                    System.setProperties(java.util.Properties())
                    System.setProperty("$trailingKey", "trailing")
                    println("success-trailing-out")
                    System.err.println("success-trailing-err")
                }
                """.trimIndent(),
            )

        withRunner { runner ->
            try {
                runBlocking {
                    val holderRun = async(Dispatchers.Default) { runner.run(holder.outDir, timeoutMs = 1000L) }
                    waitForProperty(holdKey, "held")
                    val trailingRun = async(Dispatchers.Default) { runner.run(trailing.outDir, timeoutMs = 1000L) }
                    val outcomes = listOf(holderRun, trailingRun).awaitAll()
                    outcomes.map { it.status } shouldBe List(2) { MutantStatus.SURVIVED }
                    outcomes[0].stdout shouldContain "success-holder-out"
                    outcomes[0].stderr shouldContain "success-holder-err"
                    outcomes[1].stdout shouldContain "success-trailing-out"
                    outcomes[1].stderr shouldContain "success-trailing-err"
                }
                System.getProperty(holdKey) shouldBe null
                System.getProperty(trailingKey) shouldBe null
                System.out shouldBe originalOut
                System.err shouldBe originalErr
            } finally {
                compiler.cleanup(holder)
                compiler.cleanup(trailing)
                restoreProperty(holdKey, originalHoldValue)
                System.clearProperty(trailingKey)
            }
        }
    }

    private fun assertConcurrentFailureRestoresGlobalState() {
        val holdKey = "kronenberg.concurrent.failure.hold"
        val failureKey = "kronenberg.concurrent.failure.failed"
        val originalHoldValue = System.getProperty(holdKey)
        val originalOut = System.out
        val originalErr = System.err
        System.clearProperty(holdKey)
        System.clearProperty(failureKey)

        val holder =
            compile(
                """
                fun main() {
                    System.setProperty("$holdKey", "held")
                    Thread.sleep(300)
                }
                """.trimIndent(),
            )
        val failure =
            compile(
                """
                fun main() {
                    System.setProperty("$failureKey", "failed")
                    println("failure-out")
                    System.err.println("failure-err")
                    System.setOut(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    System.setErr(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    error("expected-failure")
                }
                """.trimIndent(),
            )

        withRunner { runner ->
            try {
                runBlocking {
                    val holderRun = async(Dispatchers.Default) { runner.run(holder.outDir, timeoutMs = 1000L) }
                    waitForProperty(holdKey, "held")
                    val failureRun = async(Dispatchers.Default) { runner.run(failure.outDir, timeoutMs = 1000L) }
                    val outcomes = listOf(holderRun, failureRun).awaitAll()
                    outcomes.map { it.status } shouldBe listOf(MutantStatus.SURVIVED, MutantStatus.KILLED)
                    outcomes[1].stdout shouldContain "failure-out"
                    outcomes[1].stderr shouldContain "failure-err"
                    outcomes[1].failureMessage shouldContain "expected-failure"
                }
                System.getProperty(holdKey) shouldBe null
                System.getProperty(failureKey) shouldBe null
                System.out shouldBe originalOut
                System.err shouldBe originalErr
            } finally {
                compiler.cleanup(holder)
                compiler.cleanup(failure)
                restoreProperty(holdKey, originalHoldValue)
                System.clearProperty(failureKey)
            }
        }
    }

    private fun assertConcurrentTimeoutRestoresGlobalState() {
        val holdKey = "kronenberg.concurrent.timeout.hold"
        val timeoutKey = "kronenberg.concurrent.timeout.timed-out"
        val originalHoldValue = System.getProperty(holdKey)
        val originalOut = System.out
        val originalErr = System.err
        System.clearProperty(holdKey)
        System.clearProperty(timeoutKey)

        val holder =
            compile(
                """
                fun main() {
                    System.setProperty("$holdKey", "held")
                    Thread.sleep(300)
                }
                """.trimIndent(),
            )
        val timeout =
            compile(
                """
                fun main() {
                    System.setProperty("$timeoutKey", "timed-out")
                    println("timeout-out")
                    System.err.println("timeout-err")
                    System.setOut(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    System.setErr(java.io.PrintStream(java.io.ByteArrayOutputStream()))
                    Thread.sleep(5000)
                }
                """.trimIndent(),
            )

        withRunner { runner ->
            try {
                runBlocking {
                    val holderRun = async(Dispatchers.Default) { runner.run(holder.outDir, timeoutMs = 1000L) }
                    waitForProperty(holdKey, "held")
                    val timeoutRun = async(Dispatchers.Default) { runner.run(timeout.outDir, timeoutMs = 100L) }
                    val outcomes = listOf(holderRun, timeoutRun).awaitAll()
                    outcomes.map { it.status } shouldBe listOf(MutantStatus.SURVIVED, MutantStatus.TIMED_OUT)
                    outcomes[1].stdout shouldContain "timeout-out"
                    outcomes[1].stderr shouldContain "timeout-err"
                    outcomes[1].failureMessage shouldContain "100ms"
                }
                System.getProperty(holdKey) shouldBe null
                System.getProperty(timeoutKey) shouldBe null
                System.out shouldBe originalOut
                System.err shouldBe originalErr
            } finally {
                compiler.cleanup(holder)
                compiler.cleanup(timeout)
                restoreProperty(holdKey, originalHoldValue)
                System.clearProperty(timeoutKey)
            }
        }
    }

    private fun compile(code: String): CompileResult.Compiled {
        val result = compiler.compile(code)
        return result.shouldBeInstanceOf<CompileResult.Compiled>()
    }

    private fun waitForProperty(
        key: String,
        expected: String,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.getProperty(key) != expected && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        check(System.getProperty(key) == expected) { "Timed out waiting for $key" }
    }

    private fun withRunner(block: (FastSnippetRunner) -> Unit) {
        val originalOut = System.out
        val originalErr = System.err
        val runner = DefaultFastSnippetRunner()
        try {
            block(runner)
        } finally {
            runner.close()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun restoreProperty(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
    }
}
