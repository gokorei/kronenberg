@file:Suppress("K1_ANALYSIS", "DEPRECATION", "OPT_IN_USAGE")
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
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
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
    ): List<AstMutant> {
        if (sourceCode.isBlank()) return emptyList()
        val file = K2SnippetFrontend.parsePsi(sourceCode)
        val context = MutationContext(sourceCode, file, filePath = filePath)
        val activeMutators = registry.mutators(config.includeExtreme)
        val edits = mutableListOf<Pair<AstMutator, AstEdit>>()

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    for (mutator in activeMutators) {
                        if (mutator.canMutate(element)) {
                            for (edit in mutator.mutate(element, context)) {
                                edits.add(Pair(mutator, edit))
                            }
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        val mutants = mutableListOf<AstMutant>()

        // 1. First-Order Mutants (FOM)
        edits.forEachIndexed { index, (mutator, edit) ->
            val mutatedSource = replaceRange(sourceCode, edit.startOffset, edit.endOffset, edit.replacement)
            val lineCol = computeLineAndColumn(sourceCode, edit.startOffset)
            mutants.add(
                AstMutant(
                    id = "mutant-fom-${index + 1}-${UUID.randomUUID().toString().take(6)}",
                    mutatorName = mutator.name,
                    category = mutator.category,
                    line = lineCol.first,
                    column = lineCol.second,
                    originalText = edit.originalText,
                    replacementText = edit.replacement,
                    mutatedSource = mutatedSource,
                    filePath = filePath ?: edit.filePath,
                ),
            )
        }

        // 2. Higher-Order Mutants (HOM)
        if (config.higherOrderMutants && edits.size >= 2) {
            val maxSampled = 20
            val sampledPairs = mutableListOf<Pair<Pair<AstMutator, AstEdit>, Pair<AstMutator, AstEdit>>>()
            val totalEdits = edits.size
            val stride = (totalEdits / 10).coerceAtLeast(1)

            outer@ for (i in 0 until totalEdits step stride) {
                for (j in (i + 1) until totalEdits) {
                    val e1 = edits[i].second
                    val e2 = edits[j].second
                    if (e1.endOffset <= e2.startOffset || e2.endOffset <= e1.startOffset) {
                        sampledPairs.add(Pair(edits[i], edits[j]))
                        if (sampledPairs.size >= maxSampled) break@outer
                    }
                }
            }

            sampledPairs.forEachIndexed { idx, (p1, p2) ->
                val sorted = listOf(p1.second, p2.second).sortedByDescending { it.startOffset }
                var src = sourceCode
                for (e in sorted) {
                    src = replaceRange(src, e.startOffset, e.endOffset, e.replacement)
                }

                mutants.add(
                    AstMutant(
                        id = "mutant-hom-${idx + 1}-${UUID.randomUUID().toString().take(6)}",
                        mutatorName = "CompoundHigherOrderMutator",
                        category = MutatorCategory.EXTREME,
                        line = p1.second.line,
                        column = p1.second.column,
                        originalText = "${p1.second.originalText} & ${p2.second.originalText}",
                        replacementText = "${p1.second.replacement} & ${p2.second.replacement}",
                        mutatedSource = src,
                        filePath = filePath ?: p1.second.filePath,
                    ),
                )
            }
        }

        val filteredMutants =
            if (config.targetLines != null) {
                mutants.filter { it.line in config.targetLines }
            } else {
                mutants
            }

        val result = filteredMutants.distinctBy { it.mutatedSource }
        return if (config.maxMutants != null) result.take(config.maxMutants) else result
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
