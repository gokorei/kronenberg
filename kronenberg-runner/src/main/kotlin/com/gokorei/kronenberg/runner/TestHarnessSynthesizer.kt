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
 *
 * The file-level annotations, package directive, and imports are hoisted out of [rawBody] so that
 * [TestHarnessSynthesizer.mergeSourceWithParsedTest] can re-emit them ahead of the merged declarations,
 * which is the only position the Kotlin grammar accepts them in.
 */
public data class ParsedTestCode(
    val packageDirective: String?,
    val imports: List<String>,
    val fileAnnotations: List<String>,
    val rawBody: String,
    val testHasMain: Boolean,
    val sourceHasMain: Boolean,
    val candidateTests: List<CandidateTestFunction>,
) {
    /**
     * True when the harness must generate a top-level `main` dispatcher for the discovered candidate tests.
     */
    public val requiresSynthesizedMain: Boolean
        get() = !testHasMain && candidateTests.isNotEmpty()

    @Deprecated(
        "Reports a disjunction of two independent entry points; use testHasMain and sourceHasMain instead.",
        ReplaceWith("testHasMain || sourceHasMain"),
    )
    public val hasMain: Boolean
        get() = testHasMain || sourceHasMain

    @Deprecated(
        "Use the primary constructor so source and test entry points stay independently observable.",
    )
    public constructor(
        packageDirective: String?,
        imports: List<String>,
        rawBody: String,
        hasMain: Boolean,
        candidateTests: List<CandidateTestFunction>,
    ) : this(packageDirective, imports, emptyList(), rawBody, hasMain, false, candidateTests)
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
                fileAnnotations = emptyList(),
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
        val fileAnnotations = testFile.annotationEntries.map { it.text }
        val rawBody = SourceTextSlicer.bodyExcluding(testCode, testFile, emptyList())

        val testHasMain = topLevelMains(testFile).isNotEmpty()
        val sourceHasMain = sourceFile?.let { topLevelMains(it).isNotEmpty() } == true

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
            fileAnnotations = fileAnnotations,
            rawBody = rawBody,
            testHasMain = testHasMain,
            sourceHasMain = sourceHasMain,
            candidateTests = candidateTests,
        )
    }

    /**
     * Merges source code with test definitions, synthesizing an auto-wrapping `main()` harness when needed.
     *
     * The merged program keeps exactly one top-level `main`: the test entry point when the test file
     * declares one, otherwise the synthesized dispatcher, otherwise the source entry point. Every
     * top-level source `main` dropped on the way is reported by [removedSourceMainLineRanges] so that
     * mutations inside that unreachable region are never scored.
     */
    public fun mergeSourceWithParsedTest(
        code: String,
        test: ParsedTestCode,
        mutant: AstMutant?,
    ): String {
        if (test.rawBody.isBlank()) return code

        val codeFile = K2SnippetFrontend.parsePsi(code)
        val removedMains = if (removesSourceMains(test)) topLevelMains(codeFile) else emptyList()
        val codeBody = SourceTextSlicer.bodyExcluding(code, codeFile, removedMains)
        val testBodyWithMain = buildTestBody(code, test, mutant)
        val imports = (codeFile.importDirectives.map { it.text } + test.imports).distinct()
        val fileAnnotations = (codeFile.annotationEntries.map { it.text } + test.fileAnnotations).distinct()
        val packageDirective = codeFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text ?: test.packageDirective
        return buildMergedCode(fileAnnotations, packageDirective, imports, codeBody, testBodyWithMain)
    }

    /**
     * Inclusive 1-indexed line ranges of the top-level source `main` declarations that
     * [mergeSourceWithParsedTest] strips from the executed program.
     *
     * Those lines are unreachable once the harness supplies the entry point, so a mutation landing
     * inside them can never influence the outcome and would otherwise be recorded as a permanent
     * false survivor. Callers must exclude them from evaluation.
     */
    public fun removedSourceMainLineRanges(
        code: String,
        test: ParsedTestCode,
    ): List<IntRange> {
        if (!removesSourceMains(test)) return emptyList()
        val file = K2SnippetFrontend.parsePsi(code)
        return topLevelMains(file).map { fn -> SourceTextSlicer.lineRangeOf(code, fn) }
    }

    private fun removesSourceMains(test: ParsedTestCode): Boolean = test.testHasMain || test.requiresSynthesizedMain

    private fun topLevelMains(file: KtFile): List<KtNamedFunction> =
        file.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == "main" }

    private fun buildTestBody(
        code: String,
        test: ParsedTestCode,
        mutant: AstMutant?,
    ): String {
        if (!test.requiresSynthesizedMain) return test.rawBody
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
        fileAnnotations: List<String>,
        packageDirective: String?,
        imports: List<String>,
        codeBody: String,
        testBody: String,
    ): String =
        buildString {
            if (fileAnnotations.isNotEmpty()) {
                fileAnnotations.forEach { appendLine(it) }
                appendLine()
            }
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
    ): String = SourceTextSlicer.stripPackageAndImports(source, file)

    private fun isTestFunctionCandidate(fn: KtNamedFunction): Boolean {
        val name = fn.name ?: ""
        val isTestNamed = name.startsWith("test", ignoreCase = true) || name.endsWith("test", ignoreCase = true)
        val isAnnotated = fn.annotationEntries.any { it.shortName?.asString() == "Test" }
        return (isTestNamed || isAnnotated) && fn.valueParameters.isEmpty()
    }
}
