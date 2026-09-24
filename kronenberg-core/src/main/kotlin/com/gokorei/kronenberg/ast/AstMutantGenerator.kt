@file:Suppress("K1_ANALYSIS", "K1_DEPRECATION", "DEPRECATION", "DEPRECATION_ERROR", "OPT_IN_USAGE")
@file:OptIn(
    org.jetbrains.kotlin.K1Deprecation::class,
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
)

package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutatorCategory
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import java.io.File
import java.util.UUID

/**
 * Embedded K2 PSI Frontend parser for Kotlin source text.
 */
public object K2SnippetFrontend {
    @Volatile
    private var rootDisposable: Disposable = Disposer.newDisposable("K2SnippetFrontend.root")

    @Volatile
    private var cachedEnvironment: KotlinCoreEnvironment? = null

    @Volatile
    private var cachedPsiFactory: KtPsiFactory? = null

    private val environment: KotlinCoreEnvironment
        get() {
            synchronized(this) {
                var env = cachedEnvironment
                if (env == null) {
                    val configuration = CompilerConfiguration()
                    env =
                        KotlinCoreEnvironment.createForProduction(
                            rootDisposable,
                            configuration,
                            EnvironmentConfigFiles.JVM_CONFIG_FILES,
                        )
                    cachedEnvironment = env
                }
                return env
            }
        }

    public fun parsePsi(code: String): KtFile {
        val factory =
            cachedPsiFactory ?: synchronized(this) {
                cachedPsiFactory ?: KtPsiFactory(environment.project, false).also { cachedPsiFactory = it }
            }
        return factory.createFile("Snippet.kt", code)
    }

    internal fun analyze(
        file: KtFile,
        extraClasspath: List<String>,
    ): BindingContext? =
        synchronized(this) {
            val roots = semanticClasspath(extraClasspath).map(::JvmClasspathRoot)
            runCatching {
                environment.updateClasspath(roots)
                val configuration =
                    CompilerConfiguration().apply {
                        put(CommonConfigurationKeys.MODULE_NAME, "KronenbergSemanticAnalysis")
                        put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)
                    }
                val result =
                    TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                        environment.project,
                        listOf(file),
                        NoScopeRecordCliBindingTrace(environment.project),
                        configuration,
                        { scope: GlobalSearchScope -> environment.createPackagePartProvider(scope) },
                    )
                result.bindingContext.takeUnless { result.isError() }
            }.getOrNull()
        }

    internal fun semanticClasspath(extraClasspath: List<String>): List<File> =
        buildSet {
            extraClasspath.map(::File).filterTo(this) { it.exists() }
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .map(::File)
                .filterTo(this) { it.exists() }
            KtFile::class.java.protectionDomain.codeSource
                ?.location
                ?.let { add(File(it.toURI())) }
            Any::class.java.protectionDomain.codeSource
                ?.location
                ?.let { add(File(it.toURI())) }
            Result::class.java.protectionDomain.codeSource
                ?.location
                ?.let { add(File(it.toURI())) }
        }.toList()
}

/**
 * Generator that scans a Kotlin file AST with registered mutators and produces mutants.
 */
public class AstMutantGenerator(
    private val registry: MutatorRegistry = MutatorRegistry.default(),
) {
    /**
     * Generates all AST mutants for the provided Kotlin source code string.
     */
    public fun generateMutants(
        sourceCode: String,
        config: MutationConfig = MutationConfig(),
        filePath: String? = null,
    ): List<AstMutant> = generateMutationResult(sourceCode, config, filePath).mutants

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    public fun generateMutationResult(
        sourceCode: String,
        config: MutationConfig = MutationConfig(),
        filePath: String? = null,
    ): MutantGenerationResult {
        if (sourceCode.isBlank()) return MutantGenerationResult(emptyList(), emptyList())
        val file = K2SnippetFrontend.parsePsi(sourceCode)
        val context = MutationContext(sourceCode, file, filePath = filePath)
        val activeMutators = registry.mutators(config.includeExtreme)
        val semanticAnalysis = analyzeSemanticTargets(file, config.extraClasspath)
        val candidates = mutableListOf<MutationCandidate>()

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    val target = semanticAnalysis.targetsByOffset[element.textRange.startOffset]
                    for (mutator in activeMutators) {
                        val semanticEdits = mutator.mutateUsingResolvedTarget(element, context, target)
                        val edits =
                            if (semanticEdits.isNotEmpty()) {
                                semanticEdits
                            } else if (mutator.canMutate(element)) {
                                mutator.mutate(element, context)
                            } else {
                                emptyList()
                            }
                        edits.forEach { edit -> candidates.add(MutationCandidate(mutator, edit, target)) }
                    }
                    super.visitElement(element)
                }
            },
        )

        val accepted = mutableListOf<MutationCandidate>()
        val discarded = mutableListOf<DiscardedMutant>()
        var baselineCompilationChecked = false
        var baselineCompiles = true

        candidates.forEach { candidate ->
            val kind = candidate.mutator.semanticTargetKind
            if (kind == null) {
                accepted.add(candidate)
            } else if (!semanticAnalysis.available) {
                discarded.add(candidate.discard(MutantDiscardReason.VALIDATION_UNAVAILABLE, null))
            } else if (candidate.target == null) {
                discarded.add(candidate.discard(MutantDiscardReason.UNRESOLVED_TARGET, null))
            } else if (!candidate.target.acceptedBy(kind)) {
                discarded.add(candidate.discard(MutantDiscardReason.NON_STANDARD_TARGET, candidate.target))
            } else if (kind.requiresTypeValidation()) {
                if (!baselineCompilationChecked) {
                    baselineCompiles = InProcessMutantCompiler.compiles(sourceCode, config.extraClasspath)
                    baselineCompilationChecked = true
                }
                if (baselineCompiles &&
                    !InProcessMutantCompiler.compiles(
                        candidate.mutatedSource(sourceCode, ::replaceRange),
                        config.extraClasspath,
                    )
                ) {
                    discarded.add(candidate.discard(MutantDiscardReason.TYPE_INVALID_TRANSFORMATION, candidate.target))
                } else {
                    accepted.add(candidate)
                }
            } else {
                accepted.add(candidate)
            }
        }

        val mutants = mutableListOf<AstMutant>()
        accepted.forEachIndexed { index, candidate ->
            val edit = candidate.edit
            val lineCol = computeLineAndColumn(sourceCode, edit.startOffset)
            mutants.add(
                AstMutant(
                    id = "mutant-fom-${index + 1}-${UUID.randomUUID().toString().take(6)}",
                    mutatorName = candidate.mutator.name,
                    category = candidate.mutator.category,
                    line = lineCol.first,
                    column = lineCol.second,
                    originalText = edit.originalText,
                    replacementText = edit.replacement,
                    mutatedSource = candidate.mutatedSource(sourceCode, ::replaceRange),
                    filePath = filePath ?: edit.filePath,
                ),
            )
        }

        if (config.higherOrderMutants && accepted.size >= 2) {
            val maxSampled = 20
            val sampledPairs = mutableListOf<Pair<MutationCandidate, MutationCandidate>>()
            val stride = (accepted.size / 10).coerceAtLeast(1)
            outer@ for (i in 0 until accepted.size step stride) {
                for (j in (i + 1) until accepted.size) {
                    val e1 = accepted[i].edit
                    val e2 = accepted[j].edit
                    if (e1.endOffset <= e2.startOffset || e2.endOffset <= e1.startOffset) {
                        sampledPairs.add(Pair(accepted[i], accepted[j]))
                        if (sampledPairs.size >= maxSampled) break@outer
                    }
                }
            }
            sampledPairs.forEachIndexed { index, (first, second) ->
                val edits = listOf(first.edit, second.edit).sortedByDescending { it.startOffset }
                var mutatedSource = sourceCode
                edits.forEach { edit -> mutatedSource = replaceRange(mutatedSource, edit.startOffset, edit.endOffset, edit.replacement) }
                mutants.add(
                    AstMutant(
                        id = "mutant-hom-${index + 1}-${UUID.randomUUID().toString().take(6)}",
                        mutatorName = "CompoundHigherOrderMutator",
                        category = MutatorCategory.EXTREME,
                        line = first.edit.line,
                        column = first.edit.column,
                        originalText = "${first.edit.originalText} & ${second.edit.originalText}",
                        replacementText = "${first.edit.replacement} & ${second.edit.replacement}",
                        mutatedSource = mutatedSource,
                        filePath = filePath ?: first.edit.filePath,
                    ),
                )
            }
        }

        val filteredMutants = config.targetLines?.let { lines -> mutants.filter { it.line in lines } } ?: mutants
        val result = filteredMutants.distinctBy { it.mutatedSource }
        val retained = if (config.maxMutants != null) result.take(config.maxMutants) else result
        val reportedDiscards =
            discarded.distinctBy {
                listOf(it.mutatorName, it.line, it.column, it.originalText, it.replacementText, it.reason)
            }
        return MutantGenerationResult(retained, reportedDiscards)
    }

    private data class MutationCandidate(
        val mutator: AstMutator,
        val edit: AstEdit,
        val target: ResolvedSemanticTarget?,
    ) {
        fun mutatedSource(
            source: String,
            replaceRange: (String, Int, Int, String) -> String,
        ): String = replaceRange(source, edit.startOffset, edit.endOffset, edit.replacement)

        fun discard(
            reason: MutantDiscardReason,
            target: ResolvedSemanticTarget?,
        ): DiscardedMutant =
            DiscardedMutant(
                mutatorName = mutator.name,
                category = mutator.category,
                line = edit.line,
                column = edit.column,
                originalText = edit.originalText,
                replacementText = edit.replacement,
                reason = reason,
                resolvedTarget = target?.fqName,
                filePath = edit.filePath,
            )
    }

    private fun replaceRange(
        source: String,
        start: Int,
        end: Int,
        replacement: String,
    ): String {
        val safeStart = start.coerceIn(0, source.length)
        val safeEnd = end.coerceIn(safeStart, source.length)
        return source.substring(0, safeStart) + replacement + source.substring(safeEnd)
    }
}
