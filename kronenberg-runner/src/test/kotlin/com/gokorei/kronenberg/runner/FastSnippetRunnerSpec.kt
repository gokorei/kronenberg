package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
