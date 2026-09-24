package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationReport
import com.gokorei.kronenberg.model.MutationReportEvaluator
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
        val trimmedSource = sourceCode.trim()
        val trimmedTest = testCode.trim()
        val parsedTest = TestHarnessSynthesizer.parseTestCode(trimmedTest, trimmedSource)
        val baselineCombined = TestHarnessSynthesizer.mergeSourceWithParsedTest(trimmedSource, parsedTest, null)

        // 0. Safety pre-flight check
        if (SnippetAstSafetyChecker.containsHostTerminatingCalls(baselineCombined)) {
            return MutationReportEvaluator.fromResults(
                results = emptyList(),
                baselineError = "Code contains forbidden host-terminating calls (e.g. System.exit, exitProcess, Runtime.halt)",
                sourceFilePath = sourceFilePath,
            )
        }

        // 1. Verify baseline code and tests
        val baselineCompile = compiler.compile(baselineCombined, extraClasspath = config.extraClasspath)
        if (baselineCompile !is CompileResult.Compiled) {
            val failMsg = (baselineCompile as? CompileResult.Failed)?.message ?: "Baseline compilation failed"
            return MutationReportEvaluator.fromResults(
                results = emptyList(),
                baselineError = "Baseline compilation failed: $failMsg",
                sourceFilePath = sourceFilePath,
            )
        }

        val baselineOutcome =
            try {
                runner.run(
                    baselineCompile.outDir,
                    timeoutMs = config.baselineTimeoutMs,
                    extraClasspath = config.extraClasspath,
                )
            } finally {
                compiler.cleanup(baselineCompile)
            }

        if (baselineOutcome.status == MutantStatus.KILLED) {
            return MutationReportEvaluator.fromResults(
                results = emptyList(),
                baselineError = "Baseline test failed before mutation: ${baselineOutcome.failureMessage}",
                sourceFilePath = sourceFilePath,
            )
        }

        if (baselineOutcome.status == MutantStatus.TIMED_OUT) {
            return MutationReportEvaluator.fromResults(
                results = emptyList(),
                baselineError = "Baseline test execution timed out after ${config.baselineTimeoutMs}ms",
                sourceFilePath = sourceFilePath,
            )
        }

        val calibratedTimeoutMs =
            (maxOf(baselineOutcome.executionTimeMs, 10L) * config.timeoutMultiplier)
                .toLong()
                .coerceIn(50L, 10_000L)

        // 2. Generate AST mutants
        val mutants = generator.generateMutants(trimmedSource, config, filePath = sourceFilePath)
        if (mutants.isEmpty()) {
            return MutationReportEvaluator.fromResults(
                results = emptyList(),
                sourceFilePath = sourceFilePath,
            )
        }

        // 3. Execute mutants in parallel via coroutines
        val results: List<MutantResult> =
            coroutineScope {
                mutants
                    .map { mutant ->
                        async(Dispatchers.Default) {
                            val cacheKey =
                                if (config.enableCache) {
                                    cache.computeKey(mutant.mutatedSource, testCode, mutant.id)
                                } else {
                                    null
                                }

                            if (cacheKey != null) {
                                val cachedResult = cache.get(cacheKey)
                                if (cachedResult != null) return@async cachedResult
                            }

                            val combinedMutantCode =
                                TestHarnessSynthesizer.mergeSourceWithParsedTest(
                                    mutant.mutatedSource,
                                    parsedTest,
                                    mutant,
                                )

                            val evalResult =
                                if (SnippetAstSafetyChecker.containsHostTerminatingCalls(combinedMutantCode)) {
                                    MutantResult(
                                        mutant = mutant,
                                        status = MutantStatus.KILLED,
                                        executionTimeMs = 0L,
                                        failureMessage = "Blocked dangerous mutant containing host-terminating call",
                                    )
                                } else {
                                    val compiledMutant = compiler.compile(combinedMutantCode, extraClasspath = config.extraClasspath)

                                    if (compiledMutant !is CompileResult.Compiled) {
                                        MutantResult(
                                            mutant = mutant,
                                            status = MutantStatus.COMPILE_ERROR,
                                            executionTimeMs = 0L,
                                            failureMessage = (compiledMutant as? CompileResult.Failed)?.message,
                                        )
                                    } else {
                                        try {
                                            val outcome =
                                                runner.run(
                                                    compiledMutant.outDir,
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
                                            compiler.cleanup(compiledMutant)
                                        }
                                    }
                                }

                            if (cacheKey != null) {
                                cache.put(cacheKey, evalResult)
                            }
                            evalResult
                        }
                    }.awaitAll()
            }

        return MutationReportEvaluator.fromResults(
            results = results,
            totalMutants = mutants.size,
            sourceFilePath = sourceFilePath,
        )
    }

    override fun close() {
        runner.close()
    }
}
