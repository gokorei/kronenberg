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
            // 1. Top-level test functions
            val topLevelTestFunctions =
                testFile.declarations.filterIsInstance<KtNamedFunction>().filter { fn ->
                    isTestFunctionCandidate(fn)
                }

            for (fn in topLevelTestFunctions) {
                val fnName = fn.name ?: continue
                val calledNames = CallGraphReachability.extractCalledFunctionNames(fn)
                candidateTests.add(CandidateTestFunction(fnName, calledNames, className = null))
            }

            // 2. Class-based member test functions
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
        }

        return ParsedTestCode(pkg, imports, rawBody, hasMain, candidateTests)
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
        val codeImports = codeFile.importDirectives.map { it.text }
        val allImports = (codeImports + test.imports).distinct()
        val selectedPackage = codeFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text ?: test.packageDirective
        val codeBody = stripPackageAndImports(code, codeFile)

        val testBodyWithMain = synthesizeTestBody(code, test, mutant)

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

    private fun synthesizeTestBody(
        code: String,
        test: ParsedTestCode,
        mutant: AstMutant?,
    ): String {
        if (test.hasMain || test.candidateTests.isEmpty()) return test.rawBody

        val enclosingFn = mutant?.let { CallGraphReachability.findEnclosingFunctionName(code, it.line) }
        val orderedTests =
            if (enclosingFn == null) {
                test.candidateTests
            } else {
                val relevant = test.candidateTests.filter { enclosingFn in it.calledFunctionNames }
                val others = test.candidateTests.filter { enclosingFn !in it.calledFunctionNames }
                relevant + others
            }

        val sb = StringBuilder()
        sb.appendLine(test.rawBody)
        sb.appendLine()
        sb.appendLine("fun main() {")
        orderedTests.forEach { testFn ->
            val displayName =
                if (testFn.className != null) "${testFn.className}.${testFn.name}()" else "${testFn.name}()"
            val invocation =
                if (testFn.className != null) {
                    "${testFn.className}().${testFn.name}()"
                } else {
                    "${testFn.name}()"
                }
            appendInvocation(sb, invocation, displayName)
        }
        sb.appendLine("}")
        return sb.toString()
    }

    private fun appendInvocation(
        sb: StringBuilder,
        invocation: String,
        displayName: String,
    ) {
        sb.appendLine("    try { $invocation }")
        sb.appendLine("    catch (t: LinkageError) { throw t }")
        sb.appendLine("    catch (t: VirtualMachineError) { throw t }")
        sb.appendLine(
            "    catch (t: Error) { if (t !is AssertionError) throw t else " +
                "throw AssertionError(\"Killed by $displayName: \" + t.message, t) }",
        )
        sb.appendLine(
            "    catch (t: Throwable) { throw AssertionError(\"Killed by $displayName: \" + t.message, t) }",
        )
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
