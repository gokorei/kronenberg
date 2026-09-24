package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationMetrics
import com.gokorei.kronenberg.model.MutationPhaseMetrics
import com.gokorei.kronenberg.model.MutationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

private const val NANOS_PER_MILLISECOND: Long = 1_000_000L

public interface MutationExecutionPipeline : AutoCloseable {
    public suspend fun execute(
        sourceCode: String,
        testCode: String,
        config: MutationConfig = MutationConfig(),
        sourceFilePath: String? = null,
    ): MutationReport
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
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
        val phaseMetrics = mutableListOf<MutationPhaseMetrics>()
        var candidates = 0
        var discarded = 0
        var cacheHits = 0
        var cacheMisses = 0

        fun addPhase(
            phase: String,
            startedAt: Long,
            phaseCandidates: Int = 0,
            phaseDiscarded: Int = 0,
            phaseCacheHits: Int = 0,
            phaseCacheMisses: Int = 0,
        ) {
            phaseMetrics.add(
                MutationPhaseMetrics(
                    phase = phase,
                    durationMs = elapsedMs(startedAt),
                    candidates = phaseCandidates,
                    discarded = phaseDiscarded,
                    cacheHits = phaseCacheHits,
                    cacheMisses = phaseCacheMisses,
                ),
            )
        }

        fun metrics(): MutationMetrics =
            MutationMetrics(
                candidates = candidates,
                discarded = discarded,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                phaseMetrics = phaseMetrics.toList(),
            )

        fun report(
            totalMutants: Int,
            killedCount: Int,
            survivedCount: Int,
            timeoutCount: Int,
            compileErrorCount: Int,
            score: Double,
            results: List<MutantResult> = emptyList(),
            baselineError: String? = null,
        ): MutationReport =
            MutationReport(
                totalMutants = totalMutants,
                killedCount = killedCount,
                survivedCount = survivedCount,
                timeoutCount = timeoutCount,
                compileErrorCount = compileErrorCount,
                mutationScore = score,
                results = results,
                baselineError = baselineError,
                metrics = metrics(),
            )

        val inputStartedAt = System.nanoTime()
        val configurationError = validateConfig(config)
        if (configurationError != null) {
            addPhase("configuration", inputStartedAt)
            return report(0, 0, 0, 0, 0, 0.0, baselineError = configurationError)
        }
        if (sourceCode.length > config.maxInputCharacters || testCode.length > config.maxInputCharacters) {
            addPhase("input", inputStartedAt)
            return report(
                0,
                0,
                0,
                0,
                0,
                0.0,
                baselineError = "Input exceeds maxInputCharacters=${config.maxInputCharacters}",
            )
        }

        val trimmedSource = sourceCode.trim()
        val trimmedTest = testCode.trim()
        val baselineStartedAt = System.nanoTime()
        val parsedTest = TestHarnessSynthesizer.parseTestCode(trimmedTest, trimmedSource)
        val baselineCombined =
            TestHarnessSynthesizer.mergeSourceWithParsedTest(
                trimmedSource,
                parsedTest,
                null,
                parsedTest.sourceMetadata,
            )

        if (SnippetAstSafetyChecker.containsHostTerminatingCalls(baselineCombined)) {
            addPhase("baseline", baselineStartedAt)
            return report(
                0,
                0,
                0,
                0,
                0,
                0.0,
                baselineError = "Code contains forbidden host-terminating calls (e.g. System.exit, exitProcess, Runtime.halt)",
            )
        }

        val compileSemaphore = Semaphore(config.maxCompileConcurrency)
        val executionSemaphore = Semaphore(config.maxExecutionConcurrency)
        val baselineCompile =
            try {
                withPermit(compileSemaphore) {
                    compiler.compile(baselineCombined, extraClasspath = config.extraClasspath)
                }
            } catch (exception: Throwable) {
                addPhase("baseline", baselineStartedAt)
                return report(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    baselineError = "Baseline compilation failed: ${exception.message ?: exception.javaClass.simpleName}",
                )
            }

        if (baselineCompile !is CompileResult.Compiled) {
            addPhase("baseline", baselineStartedAt)
            val message = (baselineCompile as? CompileResult.Failed)?.message ?: "Baseline compilation failed"
            return report(0, 0, 0, 0, 0, 0.0, baselineError = "Baseline compilation failed: $message")
        }

        val baselineOutcome =
            try {
                withPermit(executionSemaphore) {
                    runner.run(
                        baselineCompile.outDir,
                        timeoutMs = config.baselineTimeoutMs,
                        extraClasspath = config.extraClasspath,
                    )
                }
            } catch (exception: Throwable) {
                addPhase("baseline", baselineStartedAt)
                return report(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    baselineError = "Baseline execution failed: ${exception.message ?: exception.javaClass.simpleName}",
                )
            } finally {
                compiler.cleanup(baselineCompile)
            }

        if (baselineOutcome.status == MutantStatus.KILLED) {
            addPhase("baseline", baselineStartedAt)
            return report(
                0,
                0,
                0,
                0,
                0,
                0.0,
                baselineError = "Baseline test failed before mutation: ${baselineOutcome.failureMessage}",
            )
        }
        if (baselineOutcome.status == MutantStatus.TIMED_OUT) {
            addPhase("baseline", baselineStartedAt)
            return report(
                0,
                0,
                0,
                0,
                0,
                0.0,
                baselineError = "Baseline test execution timed out after ${config.baselineTimeoutMs}ms",
            )
        }

        addPhase("baseline", baselineStartedAt)
        val calibratedTimeoutMs =
            (maxOf(baselineOutcome.executionTimeMs, 10L) * config.timeoutMultiplier)
                .toLong()
                .coerceIn(50L, 10_000L)
        val generationStartedAt = System.nanoTime()
        val generation = generator.generateMutantsWithMetrics(trimmedSource, config, filePath = sourceFilePath)
        candidates = generation.candidateCount
        discarded = generation.discardedCount
        addPhase("generation", generationStartedAt, candidates, discarded)
        if (generation.mutants.isEmpty()) {
            return report(0, 0, 0, 0, 0, 100.0)
        }

        val executionStartedAt = System.nanoTime()
        val hits = AtomicInteger()
        val misses = AtomicInteger()
        val results: List<MutantResult> =
            coroutineScope {
                generation.mutants
                    .map { mutant ->
                        async(Dispatchers.Default) {
                            val cacheKey =
                                if (config.enableCache) {
                                    cache.computeKey(mutant.mutatedSource, trimmedTest, mutant.id)
                                } else {
                                    null
                                }
                            if (cacheKey != null) {
                                val cached = cache.get(cacheKey)
                                if (cached != null) {
                                    hits.incrementAndGet()
                                    return@async cached.copy(mutant = mutant)
                                }
                                misses.incrementAndGet()
                            }

                            val combinedMutantCode =
                                TestHarnessSynthesizer.mergeSourceWithParsedTest(
                                    mutant.mutatedSource,
                                    parsedTest,
                                    mutant,
                                    parsedTest.sourceMetadata,
                                )
                            val result =
                                if (SnippetAstSafetyChecker.containsHostTerminatingCalls(combinedMutantCode)) {
                                    MutantResult(
                                        mutant = mutant,
                                        status = MutantStatus.KILLED,
                                        executionTimeMs = 0L,
                                        failureMessage = "Blocked dangerous mutant containing host-terminating call",
                                    )
                                } else {
                                    val compiled =
                                        withPermit(compileSemaphore) {
                                            compiler.compile(combinedMutantCode, extraClasspath = config.extraClasspath)
                                        }
                                    if (compiled !is CompileResult.Compiled) {
                                        MutantResult(
                                            mutant = mutant,
                                            status = MutantStatus.COMPILE_ERROR,
                                            executionTimeMs = 0L,
                                            failureMessage = (compiled as? CompileResult.Failed)?.message,
                                        )
                                    } else {
                                        try {
                                            val outcome =
                                                withPermit(executionSemaphore) {
                                                    runner.run(
                                                        compiled.outDir,
                                                        timeoutMs = calibratedTimeoutMs,
                                                        extraClasspath = config.extraClasspath,
                                                    )
                                                }
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
                                }
                            if (cacheKey != null) cache.put(cacheKey, result)
                            result
                        }
                    }.awaitAll()
            }
        cacheHits = hits.get()
        cacheMisses = misses.get()
        addPhase("execution", executionStartedAt, generation.mutants.size, 0, cacheHits, cacheMisses)

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
        return report(
            totalMutants = generation.mutants.size,
            killedCount = killedCount,
            survivedCount = survivedCount,
            timeoutCount = timeoutCount,
            compileErrorCount = compileErrorCount,
            score = (score * 10.0).toInt() / 10.0,
            results = results,
        )
    }

    override fun close() {
        runner.close()
    }
}

private fun validateConfig(config: MutationConfig): String? =
    when {
        config.maxInputCharacters < 1 -> "maxInputCharacters must be at least 1"
        config.maxReportResults < 0 -> "maxReportResults must not be negative"
        config.maxCompileConcurrency < 1 -> "maxCompileConcurrency must be at least 1"
        config.maxExecutionConcurrency < 1 -> "maxExecutionConcurrency must be at least 1"
        else -> null
    }

private suspend fun <T> withPermit(
    semaphore: Semaphore,
    block: suspend () -> T,
): T {
    semaphore.acquire()
    return try {
        block()
    } finally {
        semaphore.release()
    }
}

private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
