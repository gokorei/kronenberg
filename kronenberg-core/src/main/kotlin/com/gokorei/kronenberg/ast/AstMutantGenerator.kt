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
import java.security.MessageDigest

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

public data class MutantGenerationResult(
    val mutants: List<AstMutant>,
    val candidateCount: Int,
    val discardedCount: Int,
)

@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements", "MagicNumber")
public class AstMutantGenerator(
    private val registry: MutatorRegistry = MutatorRegistry.default(),
) {
    public fun generateMutants(
        sourceCode: String,
        config: MutationConfig = MutationConfig(),
        filePath: String? = null,
    ): List<AstMutant> = generateMutantsWithMetrics(sourceCode, config, filePath).mutants

    public fun generateMutantsWithMetrics(
        sourceCode: String,
        config: MutationConfig = MutationConfig(),
        filePath: String? = null,
    ): MutantGenerationResult {
        if (sourceCode.isBlank()) return MutantGenerationResult(emptyList(), 0, 0)
        val limit = effectiveLimit(config)
        if (limit == 0) return MutantGenerationResult(emptyList(), 0, 0)

        val file = K2SnippetFrontend.parsePsi(sourceCode)
        val metadata = buildSourceMetadata(sourceCode, file)
        val context = MutationContext(sourceCode, file, filePath = filePath, metadata = metadata)
        val activeMutators = registry.mutators(config.includeExtreme)
        val targetLines = config.targetLines?.toSet()
        val mutants = mutableListOf<AstMutant>()
        val edits = mutableListOf<Pair<AstMutator, AstEdit>>()
        val seenSources = mutableSetOf<String>()
        var candidateCount = 0
        var discardedCount = 0

        fun reachedLimit(): Boolean = limit != null && mutants.size >= limit

        fun addFirstOrderMutant(
            mutator: AstMutator,
            edit: AstEdit,
        ) {
            candidateCount++
            if (targetLines != null && edit.line !in targetLines) {
                discardedCount++
                return
            }
            if (reachedLimit()) {
                discardedCount++
                return
            }
            val mutatedSource = replaceRange(sourceCode, edit.startOffset, edit.endOffset, edit.replacement)
            if (!seenSources.add(mutatedSource)) {
                discardedCount++
                return
            }
            if (reachedLimit()) {
                discardedCount++
                return
            }
            mutants.add(
                AstMutant(
                    id =
                        stableMutantId(
                            "fom",
                            listOf(
                                mutator.name,
                                edit.line.toString(),
                                edit.column.toString(),
                                edit.originalText,
                                edit.replacement,
                                mutatedSource,
                            ),
                        ),
                    mutatorName = mutator.name,
                    category = mutator.category,
                    line = edit.line,
                    column = edit.column,
                    originalText = edit.originalText,
                    replacementText = edit.replacement,
                    mutatedSource = mutatedSource,
                    filePath = filePath ?: edit.filePath,
                ),
            )
            edits.add(Pair(mutator, edit))
        }

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    if (reachedLimit()) return
                    for (mutator in activeMutators) {
                        if (reachedLimit()) return
                        if (mutator.canMutate(element)) {
                            for (edit in mutator.mutate(element, context)) {
                                if (reachedLimit()) return
                                addFirstOrderMutant(mutator, edit)
                            }
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        if (config.higherOrderMutants && !reachedLimit() && edits.size >= 2) {
            val sampledPairs = mutableListOf<Pair<Pair<AstMutator, AstEdit>, Pair<AstMutator, AstEdit>>>()
            val totalEdits = edits.size
            val stride = (totalEdits / 10).coerceAtLeast(1)

            outer@ for (i in 0 until totalEdits step stride) {
                for (j in (i + 1) until totalEdits) {
                    val first = edits[i].second
                    val second = edits[j].second
                    if (first.endOffset <= second.startOffset || second.endOffset <= first.startOffset) {
                        sampledPairs.add(Pair(edits[i], edits[j]))
                        if (sampledPairs.size >= 20 || reachedLimit()) break@outer
                    }
                }
            }

            for ((first, second) in sampledPairs) {
                if (reachedLimit()) break
                candidateCount++
                if (targetLines != null && first.second.line !in targetLines) {
                    discardedCount++
                    continue
                }
                val sorted = listOf(first.second, second.second).sortedByDescending { it.startOffset }
                var mutatedSource = sourceCode
                for (edit in sorted) {
                    mutatedSource = replaceRange(mutatedSource, edit.startOffset, edit.endOffset, edit.replacement)
                }
                if (!seenSources.add(mutatedSource)) {
                    discardedCount++
                    continue
                }
                if (reachedLimit()) {
                    discardedCount++
                    continue
                }
                mutants.add(
                    AstMutant(
                        id =
                            stableMutantId(
                                "hom",
                                listOf(
                                    first.second.line.toString(),
                                    first.second.column.toString(),
                                    first.second.originalText,
                                    second.second.originalText,
                                    mutatedSource,
                                ),
                            ),
                        mutatorName = "CompoundHigherOrderMutator",
                        category = MutatorCategory.EXTREME,
                        line = first.second.line,
                        column = first.second.column,
                        originalText = "${first.second.originalText} & ${second.second.originalText}",
                        replacementText = "${first.second.replacement} & ${second.second.replacement}",
                        mutatedSource = mutatedSource,
                        filePath = filePath ?: first.second.filePath,
                    ),
                )
            }
        }

        return MutantGenerationResult(mutants, candidateCount, discardedCount)
    }

    private fun effectiveLimit(config: MutationConfig): Int? =
        listOfNotNull(config.maxMutants, config.maxReportResults)
            .minOrNull()
            ?.coerceAtLeast(0)

    private fun stableMutantId(
        kind: String,
        identity: List<String>,
    ): String {
        val payload = (listOf("kronenberg-mutant-id-v1", kind) + identity).joinToString("\u0000") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return "mutant-$kind-${hash.joinToString("") { "%02x".format(it) }.take(24)}"
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
