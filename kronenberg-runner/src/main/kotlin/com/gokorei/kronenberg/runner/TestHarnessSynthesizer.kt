package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.K2SnippetFrontend
import com.gokorei.kronenberg.model.AstMutant
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Metadata representing a candidate test function discovered in test source code.
 */
public data class CandidateTestFunction(
    val name: String,
    val calledFunctionNames: Set<String>,
    val className: String? = null,
)

/**
 * Structural breakdown of parsed test code.
 */
public data class ParsedTestCode(
    val packageDirective: String?,
    val imports: List<String>,
    val rawBody: String,
    val testHasMain: Boolean,
    val sourceHasMain: Boolean,
    val candidateTests: List<CandidateTestFunction>,
) {
    public val hasMain: Boolean
        get() = testHasMain || sourceHasMain

    public constructor(
        packageDirective: String?,
        imports: List<String>,
        rawBody: String,
        hasMain: Boolean,
        candidateTests: List<CandidateTestFunction>,
    ) : this(packageDirective, imports, rawBody, hasMain, false, candidateTests)
}

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
        if (testCode.isBlank()) {
            return ParsedTestCode(
                packageDirective = null,
                imports = emptyList(),
                rawBody = "",
                testHasMain = false,
                sourceHasMain = false,
                candidateTests = emptyList(),
            )
        }
        val testFile = K2SnippetFrontend.parsePsi(testCode)
        val sourceFile = if (sourceCode.isNotBlank()) K2SnippetFrontend.parsePsi(sourceCode) else null

        val imports = testFile.importDirectives.map { it.text }
        val pkg = testFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text
        val rawBody = stripPackageAndImports(testCode, testFile)

        val testHasMain = testFile.declarations.filterIsInstance<KtNamedFunction>().any { it.name == "main" }
        val sourceHasMain = sourceFile?.declarations?.filterIsInstance<KtNamedFunction>()?.any { it.name == "main" } == true

        val candidateTests = mutableListOf<CandidateTestFunction>()
        val topLevelTestFunctions =
            testFile.declarations.filterIsInstance<KtNamedFunction>().filter { fn ->
                isTestFunctionCandidate(fn)
            }

        for (fn in topLevelTestFunctions) {
            val fnName = fn.name ?: continue
            val calledNames = CallGraphReachability.extractCalledFunctionNames(fn)
            candidateTests.add(CandidateTestFunction(fnName, calledNames, className = null))
        }

        val testClasses =
            testFile.declarations.filterIsInstance<KtClass>().filter { ktClass ->
                !ktClass.isInterface() && !ktClass.isAnnotation() && !ktClass.isEnum()
            }

        for (ktClass in testClasses) {
            val className = ktClass.name ?: continue
            val memberFunctions =
                ktClass.body?.functions.orEmpty().filter { fn ->
                    isTestFunctionCandidate(fn)
                }

            for (fn in memberFunctions) {
                val fnName = fn.name ?: continue
                val calledNames = CallGraphReachability.extractCalledFunctionNames(fn)
                candidateTests.add(CandidateTestFunction(fnName, calledNames, className = className))
            }
        }

        return ParsedTestCode(
            packageDirective = pkg,
            imports = imports,
            rawBody = rawBody,
            testHasMain = testHasMain,
            sourceHasMain = sourceHasMain,
            candidateTests = candidateTests,
        )
    }

    private fun sourceBodyWithoutMain(file: KtFile): String {
        val sourceMain = file.declarations.filterIsInstance<KtNamedFunction>().firstOrNull { it.name == "main" }
        return file.declarations
            .filterNot { it == sourceMain }
            .joinToString("\n\n") { it.text }
            .trim()
    }

    private fun isTestFunctionCandidate(fn: KtNamedFunction): Boolean {
        val name = fn.name ?: ""
        val isTestNamed = name.startsWith("test", ignoreCase = true) || name.endsWith("test", ignoreCase = true)
        val isAnnotated = fn.annotationEntries.any { it.shortName?.asString() == "Test" }
        return (isTestNamed || isAnnotated) && fn.valueParameters.isEmpty()
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
        val shouldSynthesizeMain = !test.testHasMain && test.candidateTests.isNotEmpty()
        val sourceHasMain = codeFile.declarations.filterIsInstance<KtNamedFunction>().any { it.name == "main" }
        val removeSourceMain = sourceHasMain && (test.testHasMain || shouldSynthesizeMain)
        val codeBody = mergedSourceBody(codeFile, removeSourceMain)
        val testBodyWithMain = buildTestBody(code, test, mutant, shouldSynthesizeMain)
        val imports = (codeFile.importDirectives.map { it.text } + test.imports).distinct()
        val packageDirective = codeFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text ?: test.packageDirective
        return buildMergedCode(packageDirective, imports, codeBody, testBodyWithMain)
    }

    private fun mergedSourceBody(
        codeFile: KtFile,
        removeSourceMain: Boolean,
    ): String =
        if (removeSourceMain) {
            sourceBodyWithoutMain(codeFile)
        } else {
            stripPackageAndImports(codeFile.text, codeFile)
        }

    private fun buildTestBody(
        code: String,
        test: ParsedTestCode,
        mutant: AstMutant?,
        shouldSynthesizeMain: Boolean,
    ): String {
        if (!shouldSynthesizeMain) return test.rawBody
        val orderedTests = orderedTests(code, test.candidateTests, mutant)
        return buildString {
            appendLine(test.rawBody)
            appendLine()
            appendLine("fun main() {")
            orderedTests.forEach { testFn ->
                val displayName =
                    if (testFn.className != null) "${testFn.className}.${testFn.name}()" else "${testFn.name}()"
                val invocation =
                    if (testFn.className != null) {
                        "${testFn.className}().${testFn.name}()"
                    } else {
                        "${testFn.name}()"
                    }
                appendLine(
                    "    try { $invocation } catch (t: Throwable) { throw AssertionError(\"Killed by $displayName: \" + t.message, t) }",
                )
            }
            appendLine("}")
        }
    }

    private fun orderedTests(
        code: String,
        candidateTests: List<CandidateTestFunction>,
        mutant: AstMutant?,
    ): List<CandidateTestFunction> {
        val enclosingFn = mutant?.let { CallGraphReachability.findEnclosingFunctionName(code, it.line) } ?: return candidateTests
        val relevant = candidateTests.filter { enclosingFn in it.calledFunctionNames }
        val others = candidateTests.filter { enclosingFn !in it.calledFunctionNames }
        return relevant + others
    }

    private fun buildMergedCode(
        packageDirective: String?,
        imports: List<String>,
        codeBody: String,
        testBody: String,
    ): String =
        buildString {
            if (packageDirective != null) {
                appendLine(packageDirective)
                appendLine()
            }
            if (imports.isNotEmpty()) {
                imports.forEach { appendLine(it) }
                appendLine()
            }
            appendLine(codeBody)
            appendLine()
            appendLine(testBody)
        }.trim()

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
