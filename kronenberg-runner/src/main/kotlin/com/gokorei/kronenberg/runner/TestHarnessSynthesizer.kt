package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import com.gokorei.kronenberg.model.AstMutant
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Metadata representing a candidate test function discovered in test source code.
 */
public data class CandidateTestFunction(
    val name: String,
    val calledFunctionNames: Set<String>,
)

/**
 * Structural breakdown of parsed test code.
 */
public data class ParsedTestCode(
    val packageDirective: String?,
    val imports: List<String>,
    val rawBody: String,
    val hasMain: Boolean,
    val candidateTests: List<CandidateTestFunction>,
)

/**
 * Test code parser and synthesized test runner harness generator.
 */
public object TestHarnessSynthesizer {
    /**
     * Parses test code, discovering candidate test functions and package/import directives.
     */
    public fun parseTestCode(
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
                val calledNames = CallGraphReachability.extractCalledFunctionNames(fn)
                candidateTests.add(CandidateTestFunction(fnName, calledNames))
            }
        }

        return ParsedTestCode(pkg, imports, rawBody, hasMain, candidateTests)
    }

    /**
     * Merges source code with test definitions, synthesizing an auto-wrapping `main()` harness when needed.
     */
    public fun mergeSourceWithParsedTest(
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
                val enclosingFn = if (mutant != null) CallGraphReachability.findEnclosingFunctionName(code, mutant.line) else null

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

    /**
     * Strips package and import statements from source text to enable clean snippet concatenation.
     */
    public fun stripPackageAndImports(
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
}
