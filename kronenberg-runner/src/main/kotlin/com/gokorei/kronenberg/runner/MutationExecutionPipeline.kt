package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationConfigValidation
import com.gokorei.kronenberg.model.MutationConfigValidator
import com.gokorei.kronenberg.model.MutationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * High-level orchestration pipeline for executing in-process AST mutation test suites.
 */
public interface MutationExecutionPipeline : AutoCloseable {
    /**
     * Executes mutation testing against given source code and test code strings.
     */
    public suspend fun execute(
        sourceCode: String,
        testCode: String,
        config: MutationConfig = MutationConfig(),
        sourceFilePath: String? = null,
    ): MutationReport
}

/**
 * Default implementation of the in-process mutation execution pipeline with coroutine parallelism.
 */
public class DefaultMutationExecutionPipeline(
    private val generator: AstMutantGenerator = AstMutantGenerator(),
    private val compiler: SnippetCompiler = DefaultSnippetCompiler(),
    private val runner: FastSnippetRunner = DefaultFastSnippetRunner(),
    private val cache: MutationResultCache = DefaultMutationResultCache(),
) : MutationExecutionPipeline {
    override suspend fun execute(
        sourceCode: String,
        testCode: String,
        config: MutationConfig,
        sourceFilePath: String?,
    ): MutationReport {
        val configValidation =
            MutationConfigValidator.validateExecution(
                config,
                sourceCode,
                testCode,
            )
        return when (configValidation) {
            is MutationConfigValidation.Invalid -> {
                MutationReport.invalidConfiguration(configValidation.errors)
            }

            is MutationConfigValidation.Valid -> {
                executeValidated(
                    sourceCode = sourceCode,
                    testCode = testCode,
                    config = configValidation.config,
                    sourceFilePath = sourceFilePath,
                )
            }
        }
    }

    private suspend fun executeValidated(
        sourceCode: String,
        testCode: String,
        config: MutationConfig,
        sourceFilePath: String?,
    ): MutationReport {
        val baseline = prepareBaseline(sourceCode, testCode, config)
        return when (baseline) {
            is BaselinePreparation.Failed -> {
                baseline.report
            }

            is BaselinePreparation.Ready -> {
                evaluateMutants(
                    trimmedSource = baseline.trimmedSource,
                    sourceFilePath = sourceFilePath,
                    context = MutantEvaluationContext(testCode, config, baseline.parsedTest, baseline.calibratedTimeoutMs),
                )
            }
        }
    }

    private fun prepareBaseline(
        sourceCode: String,
        testCode: String,
        config: MutationConfig,
    ): BaselinePreparation {
        val trimmedSource = sourceCode.trim()
        val parsedTest = TestHarnessSynthesizer.parseTestCode(testCode.trim(), trimmedSource)
        val baselineCombined = TestHarnessSynthesizer.mergeSourceWithParsedTest(trimmedSource, parsedTest, null)
        val preparation =
            if (SnippetAstSafetyChecker.containsHostTerminatingCalls(baselineCombined)) {
                BaselinePreparation.Failed(
                    baselineFailure("Code contains forbidden host-terminating calls (e.g. System.exit, exitProcess, Runtime.halt)"),
                )
            } else {
                val baselineCompile = compiler.compile(baselineCombined, extraClasspath = config.extraClasspath)
                if (baselineCompile !is CompileResult.Compiled) {
                    val message = (baselineCompile as? CompileResult.Failed)?.message ?: "Baseline compilation failed"
                    BaselinePreparation.Failed(baselineFailure("Baseline compilation failed: $message"))
                } else {
                    prepareBaselineOutcome(baselineCompile, trimmedSource, parsedTest, config)
                }
            }
        return preparation
    }

    private fun prepareBaselineOutcome(
        baselineCompile: CompileResult.Compiled,
        trimmedSource: String,
        parsedTest: ParsedTestCode,
        config: MutationConfig,
    ): BaselinePreparation {
        val outcome =
            try {
                runner.run(
                    baselineCompile.outDir,
                    timeoutMs = config.baselineTimeoutMs,
                    extraClasspath = config.extraClasspath,
                )
            } finally {
                compiler.cleanup(baselineCompile)
            }
        return when (outcome.status) {
            MutantStatus.KILLED -> {
                BaselinePreparation.Failed(
                    baselineFailure("Baseline test failed before mutation: ${outcome.failureMessage}"),
                )
            }

            MutantStatus.TIMED_OUT -> {
                BaselinePreparation.Failed(
                    baselineFailure("Baseline test execution timed out after ${config.baselineTimeoutMs}ms"),
                )
            }

            else -> {
                BaselinePreparation.Ready(
                    trimmedSource = trimmedSource,
                    parsedTest = parsedTest,
                    calibratedTimeoutMs =
                        (maxOf(outcome.executionTimeMs, 10L) * config.timeoutMultiplier)
                            .toLong()
                            .coerceIn(50L, 10_000L),
                )
            }
        }
    }

    private fun baselineFailure(message: String): MutationReport =
        MutationReport(
            totalMutants = 0,
            killedCount = 0,
            survivedCount = 0,
            timeoutCount = 0,
            compileErrorCount = 0,
            mutationScore = 0.0,
            results = emptyList(),
            baselineError = message,
        )

    private suspend fun evaluateMutants(
        trimmedSource: String,
        sourceFilePath: String?,
        context: MutantEvaluationContext,
    ): MutationReport {
        val mutants = generator.generateMutants(trimmedSource, context.config, filePath = sourceFilePath)
        if (mutants.isEmpty()) {
            return MutationReport(
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 100.0,
                results = emptyList(),
            )
        }
        val results =
            coroutineScope {
                mutants
                    .map { mutant ->
                        async(Dispatchers.Default) {
                            evaluateMutant(mutant, context)
                        }
                    }.awaitAll()
            }
        return buildMutationReport(mutants.size, results)
    }

    private suspend fun evaluateMutant(
        mutant: AstMutant,
        context: MutantEvaluationContext,
    ): MutantResult {
        val cacheKey =
            if (context.config.enableCache) {
                cache.computeKey(mutant.mutatedSource, context.testCode, mutant.id)
            } else {
                null
            }
        if (cacheKey != null) {
            val cachedResult = cache.get(cacheKey)
            if (cachedResult != null) return cachedResult
        }

        val combinedCode = TestHarnessSynthesizer.mergeSourceWithParsedTest(mutant.mutatedSource, context.parsedTest, mutant)
        val result =
            if (SnippetAstSafetyChecker.containsHostTerminatingCalls(combinedCode)) {
                MutantResult(
                    mutant = mutant,
                    status = MutantStatus.KILLED,
                    executionTimeMs = 0L,
                    failureMessage = "Blocked dangerous mutant containing host-terminating call",
                )
            } else {
                compileAndRunMutant(mutant, combinedCode, context.config, context.calibratedTimeoutMs)
            }
        if (cacheKey != null) cache.put(cacheKey, result)
        return result
    }

    private fun compileAndRunMutant(
        mutant: AstMutant,
        combinedCode: String,
        config: MutationConfig,
        calibratedTimeoutMs: Long,
    ): MutantResult {
        val compiled = compiler.compile(combinedCode, extraClasspath = config.extraClasspath)
        if (compiled !is CompileResult.Compiled) {
            return MutantResult(
                mutant = mutant,
                status = MutantStatus.COMPILE_ERROR,
                executionTimeMs = 0L,
                failureMessage = (compiled as? CompileResult.Failed)?.message,
            )
        }
        return try {
            val outcome =
                runner.run(
                    compiled.outDir,
                    timeoutMs = calibratedTimeoutMs,
                    extraClasspath = config.extraClasspath,
                )
            MutantResult(
                mutant = mutant,
                status = outcome.status,
                executionTimeMs = outcome.executionTimeMs,
                failureMessage = outcome.failureMessage,
            )
        } finally {
            compiler.cleanup(compiled)
        }
    }

    private fun buildMutationReport(
        totalMutants: Int,
        results: List<MutantResult>,
    ): MutationReport {
        val killedCount = results.count { it.status == MutantStatus.KILLED }
        val survivedCount = results.count { it.status == MutantStatus.SURVIVED }
        val timeoutCount = results.count { it.status == MutantStatus.TIMED_OUT }
        val compileErrorCount = results.count { it.status == MutantStatus.COMPILE_ERROR }
        val totalEffective = killedCount + survivedCount + timeoutCount
        val score =
            if (totalEffective > 0) {
                ((killedCount + timeoutCount).toDouble() / totalEffective.toDouble()) * 100.0
            } else {
                100.0
            }
        val report =
            MutationReport(
                totalMutants = totalMutants,
                killedCount = killedCount,
                survivedCount = survivedCount,
                timeoutCount = timeoutCount,
                compileErrorCount = compileErrorCount,
                mutationScore = (score * 10.0).toInt() / 10.0,
                results = results,
            )
        val errors = MutationConfigValidator.validateReport(report)
        return if (errors.isEmpty()) report else MutationReport.invalidConfiguration(errors)
    }

    private data class MutantEvaluationContext(
        val testCode: String,
        val config: MutationConfig,
        val parsedTest: ParsedTestCode,
        val calibratedTimeoutMs: Long,
    )

    private sealed interface BaselinePreparation {
        data class Ready(
            val trimmedSource: String,
            val parsedTest: ParsedTestCode,
            val calibratedTimeoutMs: Long,
        ) : BaselinePreparation

        data class Failed(
            val report: MutationReport,
        ) : BaselinePreparation
    }

    override fun close() {
        runner.close()
    }
}
