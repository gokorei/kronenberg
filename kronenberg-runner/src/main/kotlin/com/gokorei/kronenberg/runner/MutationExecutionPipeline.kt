package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.ast.K2SnippetFrontend
import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

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
    ): MutationReport {
        val trimmedSource = sourceCode.trim()
        val trimmedTest = testCode.trim()
        val parsedTest = parseTestCode(trimmedTest, trimmedSource)
        val baselineCombined = mergeSourceWithParsedTest(trimmedSource, parsedTest, null)

        // 0. Safety pre-flight check
        if (SnippetAstSafetyChecker.containsHostTerminatingCalls(baselineCombined)) {
            return MutationReport(
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = emptyList(),
                baselineError = "Code contains forbidden host-terminating calls (e.g. System.exit, exitProcess, Runtime.halt)",
            )
        }

        // 1. Verify baseline code and tests
        val baselineCompile = compiler.compile(baselineCombined)
        if (baselineCompile !is CompileResult.Compiled) {
            val failMsg = (baselineCompile as? CompileResult.Failed)?.message ?: "Baseline compilation failed"
            return MutationReport(
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = emptyList(),
                baselineError = "Baseline compilation failed: $failMsg",
            )
        }

        val baselineOutcome =
            try {
                runner.run(baselineCompile.outDir, timeoutMs = config.baselineTimeoutMs)
            } finally {
                compiler.cleanup(baselineCompile)
            }

        if (baselineOutcome.status == MutantStatus.KILLED) {
            return MutationReport(
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = emptyList(),
                baselineError = "Baseline test failed before mutation: ${baselineOutcome.failureMessage}",
            )
        }

        if (baselineOutcome.status == MutantStatus.TIMED_OUT) {
            return MutationReport(
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = emptyList(),
                baselineError = "Baseline test execution timed out after ${config.baselineTimeoutMs}ms",
            )
        }

        val calibratedTimeoutMs =
            (maxOf(baselineOutcome.executionTimeMs, 10L) * config.timeoutMultiplier)
                .toLong()
                .coerceIn(50L, 10_000L)

        // 2. Generate AST mutants
        val mutants = generator.generateMutants(trimmedSource, config)
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

                            val combinedMutantCode = mergeSourceWithParsedTest(mutant.mutatedSource, parsedTest, mutant)

                            val evalResult =
                                if (SnippetAstSafetyChecker.containsHostTerminatingCalls(combinedMutantCode)) {
                                    MutantResult(
                                        mutant = mutant,
                                        status = MutantStatus.KILLED,
                                        executionTimeMs = 0L,
                                        failureMessage = "Blocked dangerous mutant containing host-terminating call",
                                    )
                                } else {
                                    val compiledMutant = compiler.compile(combinedMutantCode)

                                    if (compiledMutant !is CompileResult.Compiled) {
                                        MutantResult(
                                            mutant = mutant,
                                            status = MutantStatus.COMPILE_ERROR,
                                            executionTimeMs = 0L,
                                            failureMessage = (compiledMutant as? CompileResult.Failed)?.message,
                                        )
                                    } else {
                                        try {
                                            val outcome = runner.run(compiledMutant.outDir, timeoutMs = calibratedTimeoutMs)
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

    private data class CandidateTestFunction(
        val name: String,
        val calledFunctionNames: Set<String>,
    )

    private data class ParsedTestCode(
        val packageDirective: String?,
        val imports: List<String>,
        val rawBody: String,
        val hasMain: Boolean,
        val candidateTests: List<CandidateTestFunction>,
    )

    private fun parseTestCode(
        testCode: String,
        sourceCode: String,
    ): ParsedTestCode {
        if (testCode.isBlank()) return ParsedTestCode(null, emptyList(), "", false, emptyList())
        val testFile = K2SnippetFrontend.parsePsi(testCode)
        val sourceFile = if (sourceCode.isNotBlank()) K2SnippetFrontend.parsePsi(sourceCode) else null

        val imports = testFile.importDirectives.map { it.text }
        val pkg = testFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text
        val rawBody = stripPackageAndImports(testCode, testFile)

        val testHasMain = testFile.declarations.filterIsInstance<KtNamedFunction>().any { it.name == "main" }
        val sourceHasMain = sourceFile?.declarations?.filterIsInstance<KtNamedFunction>()?.any { it.name == "main" } == true
        val hasMain = testHasMain || sourceHasMain

        val candidateTests = mutableListOf<CandidateTestFunction>()
        if (!hasMain) {
            val testFunctions =
                testFile.declarations.filterIsInstance<KtNamedFunction>().filter { fn ->
                    val name = fn.name ?: ""
                    val isTestNamed = name.startsWith("test", ignoreCase = true) || name.endsWith("test", ignoreCase = true)
                    val isAnnotated = fn.annotationEntries.any { it.shortName?.asString() == "Test" }
                    (isTestNamed || isAnnotated) && fn.valueParameters.isEmpty()
                }

            for (fn in testFunctions) {
                val fnName = fn.name ?: continue
                val calledNames = mutableSetOf<String>()
                fn.accept(
                    object : KtTreeVisitorVoid() {
                        override fun visitCallExpression(expression: KtCallExpression) {
                            expression.calleeExpression?.text?.let { calledNames.add(it) }
                            super.visitCallExpression(expression)
                        }
                    },
                )
                candidateTests.add(CandidateTestFunction(fnName, calledNames))
            }
        }

        return ParsedTestCode(pkg, imports, rawBody, hasMain, candidateTests)
    }

    private fun findEnclosingFunctionName(
        sourceCode: String,
        line: Int,
    ): String? {
        val psi = K2SnippetFrontend.parsePsi(sourceCode)
        var enclosingName: String? = null
        psi.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    val range = function.textRange
                    val (startLine, _) = computeLineAndColumn(sourceCode, range.startOffset)
                    val (endLine, _) = computeLineAndColumn(sourceCode, range.endOffset)
                    if (line in startLine..endLine) {
                        enclosingName = function.name
                    }
                    super.visitNamedFunction(function)
                }
            },
        )
        return enclosingName
    }

    private fun computeLineAndColumn(
        source: String,
        offset: Int,
    ): Pair<Int, Int> {
        var line = 1
        var lastLineBreak = -1
        for (i in 0 until offset.coerceAtMost(source.length)) {
            if (source[i] == '\n') {
                line++
                lastLineBreak = i
            }
        }
        val col = offset - lastLineBreak
        return Pair(line, col)
    }

    private fun mergeSourceWithParsedTest(
        code: String,
        test: ParsedTestCode,
        mutant: AstMutant?,
    ): String {
        if (test.rawBody.isBlank()) return code

        val codeFile = K2SnippetFrontend.parsePsi(code)
        val codeImports = codeFile.importDirectives.map { it.text }
        val allImports = (codeImports + test.imports).distinct()
        val selectedPackage = codeFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text ?: test.packageDirective
        val codeBody = stripPackageAndImports(code, codeFile)

        val testBodyWithMain =
            if (!test.hasMain && test.candidateTests.isNotEmpty()) {
                val enclosingFn = if (mutant != null) findEnclosingFunctionName(code, mutant.line) else null

                // Call-graph pruning and ordering: prioritize tests that invoke the mutated function
                val orderedTests =
                    if (enclosingFn != null) {
                        val relevant = test.candidateTests.filter { enclosingFn in it.calledFunctionNames }
                        val others = test.candidateTests.filter { enclosingFn !in it.calledFunctionNames }
                        relevant + others
                    } else {
                        test.candidateTests
                    }

                val sb = StringBuilder()
                sb.appendLine(test.rawBody)
                sb.appendLine()
                sb.appendLine("fun main() {")
                orderedTests.forEach { testFn ->
                    sb.appendLine(
                        "    try { ${testFn.name}() } catch (t: Throwable) { throw AssertionError(\"Killed by ${testFn.name}(): \" + t.message, t) }",
                    )
                }
                sb.appendLine("}")
                sb.toString()
            } else {
                test.rawBody
            }

        val sb = StringBuilder()
        if (selectedPackage != null) {
            sb.appendLine(selectedPackage)
            sb.appendLine()
        }
        if (allImports.isNotEmpty()) {
            allImports.forEach { sb.appendLine(it) }
            sb.appendLine()
        }
        sb.appendLine(codeBody)
        sb.appendLine()
        sb.appendLine(testBodyWithMain)
        return sb.toString().trim()
    }

    private fun stripPackageAndImports(
        source: String,
        file: KtFile,
    ): String {
        val rangesToRemove = mutableListOf<org.jetbrains.kotlin.com.intellij.openapi.util.TextRange>()
        file.packageDirective?.takeIf { it.text.isNotBlank() }?.let { rangesToRemove.add(it.textRange) }
        file.importList?.takeIf { it.text.isNotBlank() }?.let { rangesToRemove.add(it.textRange) }

        if (rangesToRemove.isEmpty()) return source.trim()

        val sortedRanges = rangesToRemove.sortedByDescending { it.startOffset }
        var result = source
        for (range in sortedRanges) {
            val start = range.startOffset.coerceAtLeast(0)
            val end = range.endOffset.coerceAtMost(result.length)
            if (start < end) {
                result = result.substring(0, start) + result.substring(end)
            }
        }
        return result.trim()
    }

    override fun close() {
        runner.close()
    }
}
