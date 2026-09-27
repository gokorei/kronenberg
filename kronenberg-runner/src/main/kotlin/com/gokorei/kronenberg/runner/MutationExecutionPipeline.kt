package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
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
        val trimmedSource = sourceCode.trim()
        val trimmedTest = testCode.trim()
        val parsedTest = TestHarnessSynthesizer.parseTestCode(trimmedTest, trimmedSource)
        val baselineCombined = TestHarnessSynthesizer.mergeSourceWithParsedTest(trimmedSource, parsedTest, null)

        // 0. Safety pre-flight check
        if (SnippetAstSafetyChecker.containsHostTerminatingCalls(baselineCombined)) {
            return baselineFailureReport("Code contains forbidden host-terminating calls (e.g. System.exit, exitProcess, Runtime.halt)")
        }

        // 1. Verify baseline code and tests
        val baseline = verifyBaseline(baselineCombined, config)
        baseline.failureReport?.let { return it }

        val calibratedTimeoutMs =
            (maxOf(baseline.executionTimeMs, 10L) * config.timeoutMultiplier)
                .toLong()
                .coerceIn(50L, 10_000L)

        // 2. Generate AST mutants, excluding the regions the harness removed from the program
        val removedSourceMainLines = TestHarnessSynthesizer.removedSourceMainLineRanges(trimmedSource, parsedTest)
        val mutants = generateReachableMutants(trimmedSource, config, removedSourceMainLines, sourceFilePath)
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

        return MutationReport(
            totalMutants = mutants.size,
            killedCount = killedCount,
            survivedCount = survivedCount,
            timeoutCount = timeoutCount,
            compileErrorCount = compileErrorCount,
            mutationScore = (score * 10.0).toInt() / 10.0,
            results = results,
        )
    }

    override fun close() {
        runner.close()
    }

    private fun verifyBaseline(
        combinedCode: String,
        config: MutationConfig,
    ): BaselineVerification {
        val compiled = compiler.compile(combinedCode, extraClasspath = config.extraClasspath)
        if (compiled !is CompileResult.Compiled) {
            val message = (compiled as? CompileResult.Failed)?.message ?: "Baseline compilation failed"
            return BaselineVerification(baselineFailureReport("Baseline compilation failed: $message"))
        }

        val outcome =
            try {
                runner.run(
                    compiled.outDir,
                    timeoutMs = config.baselineTimeoutMs,
                    extraClasspath = config.extraClasspath,
                )
            } finally {
                compiler.cleanup(compiled)
            }

        val failure =
            when (outcome.status) {
                MutantStatus.KILLED -> "Baseline test failed before mutation: ${outcome.failureMessage}"
                MutantStatus.TIMED_OUT -> "Baseline test execution timed out after ${config.baselineTimeoutMs}ms"
                else -> null
            }
        return BaselineVerification(failure?.let(::baselineFailureReport), outcome.executionTimeMs)
    }

    /**
     * Generates the mutants that still influence the executed program.
     *
     * A mutation inside a top-level source `main` that the harness removed cannot reach the run, so it is
     * dropped instead of being scored as a survivor. The mutant budget is applied after that exclusion so
     * the retained budget always goes to reachable code.
     */
    private fun generateReachableMutants(
        source: String,
        config: MutationConfig,
        removedMainLines: List<IntRange>,
        sourceFilePath: String?,
    ): List<AstMutant> {
        val generationConfig = if (removedMainLines.isEmpty()) config else config.copy(maxMutants = null)
        return generator
            .generateMutants(source, generationConfig, filePath = sourceFilePath)
            .filter { mutant -> removedMainLines.none { mutant.line in it } }
            .let { reachable -> config.maxMutants?.let { reachable.take(it) } ?: reachable }
    }

    private fun baselineFailureReport(baselineError: String): MutationReport =
        MutationReport(
            totalMutants = 0,
            killedCount = 0,
            survivedCount = 0,
            timeoutCount = 0,
            compileErrorCount = 0,
            mutationScore = 0.0,
            results = emptyList(),
            baselineError = baselineError,
        )
}

/**
 * Outcome of the pre-mutation baseline pass: a failure report when the suite is unusable, plus the
 * measured baseline execution time used to calibrate the per-mutant timeout.
 */
private data class BaselineVerification(
    val failureReport: MutationReport? = null,
    val executionTimeMs: Long = 0L,
)
