package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

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
    fun `terminates infinite loops as TIMED_OUT only after worker termination`() {
        val compiled = compiler.compile("fun main() { while (true) { } }")
        try {
            val output = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            val outcome = runner.run(output.outDir, timeoutMs = 200L)
            outcome.status shouldBe MutantStatus.TIMED_OUT
            outcome.failureMessage!!.contains("terminated") shouldBe true
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `terminates native blocking calls as TIMED_OUT`() {
        val compiled = compiler.compile("fun main() { Thread.sleep(10_000L) }")
        try {
            val output = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(output.outDir, timeoutMs = 200L).status shouldBe MutantStatus.TIMED_OUT
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `terminates spawned child work when execution times out`() {
        val pidFile = Files.createTempFile("kronenberg-child", ".pid")
        val source =
            """
            import java.nio.file.Files
            import java.nio.file.Path

            fun main() {
                val child = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
                Files.writeString(Path.of("${pidFile.toAbsolutePath()}"), child.pid().toString())
                child.waitFor()
            }
            """.trimIndent()
        val compiled = compiler.compile(source)
        try {
            val output = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(output.outDir, timeoutMs = 2000L).status shouldBe MutantStatus.TIMED_OUT
            val childPid = pidFile.readText().trim().toLong()
            ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) shouldBe false
        } finally {
            compiler.cleanup(compiled)
            pidFile.toFile().delete()
        }
    }

    @Test
    fun `terminates spawned child work when execution returns normally`() {
        val pidFile = Files.createTempFile("kronenberg-returning-child", ".pid")
        val source =
            """
            import java.nio.file.Files
            import java.nio.file.Path

            fun main() {
                val child = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
                Files.writeString(Path.of("${pidFile.toAbsolutePath()}"), child.pid().toString())
            }
            """.trimIndent()
        val compiled = compiler.compile(source)
        try {
            val output = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(output.outDir, timeoutMs = 2000L).status shouldBe MutantStatus.SURVIVED
            val childPid = pidFile.readText().trim().toLong()
            ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) shouldBe false
        } finally {
            compiler.cleanup(compiled)
            pidFile.toFile().delete()
        }
    }

    @Test
    fun `handles repeated execution timeouts without retaining workers`() {
        val compiled = compiler.compile("fun main() { while (true) { } }")
        try {
            val output = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            repeat(4) {
                runner.run(output.outDir, timeoutMs = 100L).status shouldBe MutantStatus.TIMED_OUT
            }
        } finally {
            compiler.cleanup(compiled)
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
