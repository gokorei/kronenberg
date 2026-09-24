package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class FastSnippetRunnerSpec {
    private val runner: FastSnippetRunner = DefaultFastSnippetRunner()
    private val compiler: SnippetCompiler = DefaultSnippetCompiler()

    @Test
    fun `identifies assertion failure as KILLED mutant status`() {
        val compiled = compiler.compile("fun main() { throw AssertionError(\"test assertion failed\") }")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(classes.outDir, timeoutMs = 1000L).status shouldBe MutantStatus.KILLED
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `identifies ordinary test execution failure as KILLED mutant status`() {
        val compiled = compiler.compile("fun main() { error(\"test execution failed\") }")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(classes.outDir, timeoutMs = 1000L).status shouldBe MutantStatus.KILLED
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `reports missing entrypoint as infrastructure error`() {
        val compiled = compiler.compile("class NoEntrypoint")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(classes.outDir, "NoEntrypoint", timeoutMs = 1000L).status shouldBe MutantStatus.INFRASTRUCTURE_ERROR
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `reports missing class as infrastructure error`() {
        val tempDir = createTempDirectory("kronenberg-test-missing-class")
        try {
            runner.run(tempDir, "MissingSnippet", timeoutMs = 1000L).status shouldBe MutantStatus.INFRASTRUCTURE_ERROR
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns structured missing entrypoint error`() {
        val compiled = compiler.compile("class NoEntrypoint")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            val outcome = runner.run(classes.outDir, classes.entrypoint, timeoutMs = 1000L)

            outcome.error shouldBe RunnerError.MissingEntrypoint
            outcome.status shouldBe MutantStatus.INFRASTRUCTURE_ERROR
            outcome.failureMessage shouldBe "MissingEntrypoint: no deterministic main entrypoint was found"
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `returns structured ambiguous entrypoint error`() {
        val source =
            """
            package audit.fixture

            object First {
                @JvmStatic
                fun main(args: Array<String>) = Unit
            }

            object Second {
                @JvmStatic
                fun main(args: Array<String>) = Unit
            }
            """.trimIndent()
        val compiled = compiler.compile(source)
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            val outcome = runner.run(classes.outDir, classes.entrypoint, timeoutMs = 1000L)

            outcome.status shouldBe MutantStatus.INFRASTRUCTURE_ERROR
            outcome.error shouldBe
                RunnerError.AmbiguousEntrypoint(
                    candidates = listOf("audit.fixture.First", "audit.fixture.Second"),
                )
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `executes packaged top-level class and nested entrypoints`() {
        assertEntrypoints(
            listOf(
                """
                package audit.fixture

                class Decoy

                fun main() {
                    check(true)
                }
                """.trimIndent(),
                """
                package audit.fixture

                class Entry {
                    fun main() {
                        check(true)
                    }
                }
                """.trimIndent(),
                """
                package audit.fixture

                class Outer {
                    class Entry {
                        fun main() {
                            check(true)
                        }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `executes packaged object and companion entrypoints`() {
        assertEntrypoints(
            listOf(
                """
                package audit.fixture

                object Entry {
                    fun main() {
                        check(true)
                    }
                }
                """.trimIndent(),
                """
                package audit.fixture

                class Entry {
                    companion object {
                        fun main() {
                            check(true)
                        }
                    }
                }
                """.trimIndent(),
                """
                package audit.fixture

                object Entry {
                    @JvmStatic
                    fun main() {
                        check(true)
                    }
                }
                """.trimIndent(),
                """
                package audit.fixture

                class Entry {
                    companion object {
                        @JvmStatic
                        fun main() {
                            check(true)
                        }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun assertEntrypoints(sources: List<String>) {
        sources.forEach { source ->
            val compiled = compiler.compile(source)
            try {
                val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
                runner.run(classes.outDir, classes.entrypoint, timeoutMs = 1000L).status shouldBe MutantStatus.SURVIVED
            } finally {
                compiler.cleanup(compiled)
            }
        }
    }

    @Test
    fun `reports linkage failure as infrastructure error`() {
        val helper = compiler.compile("package external\nclass Helper {}")
        val app =
            compiler.compile(
                "import external.Helper\nfun main() { Helper() }",
                extraClasspath = listOf(helper.shouldBeInstanceOf<CompileResult.Compiled>().outDir.toString()),
            )
        try {
            val classes = app.shouldBeInstanceOf<CompileResult.Compiled>()
            compiler.cleanup(helper)
            runner.run(classes.outDir, timeoutMs = 1000L).status shouldBe MutantStatus.INFRASTRUCTURE_ERROR
        } finally {
            compiler.cleanup(app)
            compiler.cleanup(helper)
        }
    }

    @Test
    fun `reports closed runner as runner error`() {
        val closedRunner = DefaultFastSnippetRunner()
        val tempDir = createTempDirectory("kronenberg-test-closed-runner")
        try {
            closedRunner.close()
            closedRunner.run(tempDir, timeoutMs = 1000L).status shouldBe MutantStatus.RUNNER_ERROR
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports internal runner setup failure as runner error`() {
        val invalidPath = mockk<Path>()
        every { invalidPath.toUri() } throws IllegalStateException("internal runner failure")
        runner.run(invalidPath, timeoutMs = 1000L).status shouldBe MutantStatus.RUNNER_ERROR
    }

    @Test
    fun `reports fatal virtual machine error as runner error`() {
        val compiled = compiler.compile("fun main() { throw OutOfMemoryError(\"fatal sandbox failure\") }")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            runner.run(classes.outDir, timeoutMs = 1000L).status shouldBe MutantStatus.RUNNER_ERROR
        } finally {
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `terminates infinite loops safely as TIMED_OUT within configured timeout threshold`() {
        val compiled = compiler.compile("fun main() { while (true) {} }")
        try {
            val classes = compiled.shouldBeInstanceOf<CompileResult.Compiled>()
            val outcome = runner.run(classes.outDir, timeoutMs = 200L)
            outcome.status shouldBe MutantStatus.TIMED_OUT
            outcome.executionTimeMs shouldBe (outcome.executionTimeMs)
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
