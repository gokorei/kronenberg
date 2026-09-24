package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.ConfigurationErrorCode
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationConfigValidator
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MutationConfigurationValidationSpec {
    @Test
    fun `returns structured configuration errors without running compiler or mutants`() {
        var compileCalls = 0
        var runnerCalls = 0
        val compiler =
            object : SnippetCompiler {
                override fun compile(
                    sourceCode: String,
                    extraClasspath: List<String>,
                ): CompileResult {
                    compileCalls++
                    return CompileResult.Failed("unexpected compilation")
                }

                override fun cleanup(result: CompileResult) = Unit
            }
        val runner =
            object : FastSnippetRunner {
                override fun run(
                    classesDir: Path,
                    mainClass: String,
                    timeoutMs: Long,
                    extraClasspath: List<String>,
                ): RunnerOutcome {
                    runnerCalls++
                    return RunnerOutcome(MutantStatus.SURVIVED, 0L)
                }

                override fun close() = Unit
            }
        val pipeline = DefaultMutationExecutionPipeline(compiler = compiler, runner = runner)

        val report =
            runBlocking {
                pipeline.execute(
                    sourceCode = "fun add(a: Int, b: Int) = a + b",
                    testCode = "fun main() { check(add(1, 2) == 3) }",
                    config =
                        MutationConfig(
                            minScore = Double.NaN,
                            baselineTimeoutMs = 0L,
                            timeoutMultiplier = Double.POSITIVE_INFINITY,
                            maxMutants = -1,
                        ),
                )
            }

        report.configurationErrors.map { it.code }.toSet() shouldBe
            setOf(
                ConfigurationErrorCode.THRESHOLD_NOT_FINITE,
                ConfigurationErrorCode.TIMEOUT_OUT_OF_RANGE,
                ConfigurationErrorCode.TIMEOUT_MULTIPLIER_NOT_FINITE,
                ConfigurationErrorCode.MAX_MUTANTS_OUT_OF_RANGE,
            )
        report.totalMutants shouldBe 0
        report.results shouldBe emptyList()
        report.baselineError.shouldBeNull()
        compileCalls shouldBe 0
        runnerCalls shouldBe 0
    }

    @Test
    fun `rejects oversized source before compiler execution`() {
        var compileCalls = 0
        val compiler =
            object : SnippetCompiler {
                override fun compile(
                    sourceCode: String,
                    extraClasspath: List<String>,
                ): CompileResult {
                    compileCalls++
                    return CompileResult.Failed("unexpected compilation")
                }

                override fun cleanup(result: CompileResult) = Unit
            }
        val pipeline = DefaultMutationExecutionPipeline(compiler = compiler)

        val report =
            runBlocking {
                pipeline.execute(
                    sourceCode = "x".repeat(MutationConfigValidator.MAX_SOURCE_CODE_CHARS + 1),
                    testCode = "fun main() = Unit",
                )
            }

        report.configurationErrors.map { it.code } shouldBe listOf(ConfigurationErrorCode.SOURCE_TOO_LARGE)
        report.totalMutants shouldBe 0
        report.results shouldBe emptyList()
        compileCalls shouldBe 0
    }
}
