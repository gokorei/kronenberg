@file:Suppress("K1_ANALYSIS", "K1_DEPRECATION", "DEPRECATION", "DEPRECATION_ERROR", "OPT_IN_USAGE")
@file:OptIn(
    org.jetbrains.kotlin.K1Deprecation::class,
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
)

package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.safeFqNameForLazyResolve
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import java.nio.file.Files

internal data class ResolvedSemanticTarget(
    val name: String,
    val fqName: String,
    val source: PsiElement?,
    val userDefined: Boolean = false,
)

internal data class SemanticAnalysis(
    val available: Boolean,
    val targetsByOffset: Map<Int, ResolvedSemanticTarget>,
)

internal fun analyzeSemanticTargets(
    file: KtFile,
    extraClasspath: List<String>,
): SemanticAnalysis {
    val bindingContext =
        K2SnippetFrontend.analyze(file, extraClasspath)
            ?: return SemanticAnalysis(available = false, targetsByOffset = emptyMap())
    val targets = mutableMapOf<Int, ResolvedSemanticTarget>()
    val userCopyFqNames = mutableSetOf<String>()
    file.accept(
        object : KtTreeVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                if (element is KtNamedFunction && element.name == "copy") {
                    element.safeFqNameForLazyResolve()?.asString()?.let(userCopyFqNames::add)
                }
                super.visitElement(element)
            }
        },
    )

    file.accept(
        object : KtTreeVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is KtCallExpression -> {
                        resolve(element.calleeExpression)?.let { targets[element.textRange.startOffset] = it }
                    }

                    is KtDotQualifiedExpression -> {
                        resolve(element.selectorExpression)?.let { targets[element.textRange.startOffset] = it }
                    }
                }
                super.visitElement(element)
            }

            @Suppress("ReturnCount")
            private fun resolve(expression: PsiElement?): ResolvedSemanticTarget? {
                val reference = expression ?: return null
                val descriptor =
                    bindingContext.getSliceContents(BindingContext.REFERENCE_TARGET)[reference] as? CallableDescriptor
                        ?: return null
                return ResolvedSemanticTarget(
                    name = descriptor.name.asString(),
                    fqName = descriptor.resolvedFqName,
                    source = descriptor.source as? PsiElement,
                    userDefined = userCopyFqNames.contains(descriptor.resolvedFqName),
                )
            }
        },
    )
    return SemanticAnalysis(available = true, targetsByOffset = targets)
}

private val DeclarationDescriptor.resolvedFqName: String
    get() {
        val resolved = DescriptorUtils.getFqNameSafe(this).asString()
        if (resolved.isNotEmpty()) return resolved
        val owner = containingDeclaration ?: return resolved
        return if (owner is ClassDescriptor) {
            "${DescriptorUtils.getFqNameSafe(owner).asString()}.$name"
        } else {
            DescriptorUtils.getFqNameSafe(owner).asString()
        }
    }

internal fun AstMutator.mutateUsingResolvedTarget(
    element: PsiElement,
    context: MutationContext,
    target: ResolvedSemanticTarget?,
): List<AstEdit> {
    if (target == null) return emptyList()
    return when (this) {
        is CollectionOperatorMutator -> mutateResolved(element, context, target)
        is TakeIfMutator -> mutateResolved(element, context, target)
        is ScopeFunctionMutator -> mutateResolved(element, context, target)
        is PreconditionMutator -> mutateResolved(element, context, target)
        is ResultMutator -> mutateResolved(element, context, target)
        is DataClassCopyMutator -> mutateResolved(element, context, target)
        is CoroutineFlowMutator -> mutateResolved(element, context, target)
        is CoroutineConcurrencyMutator -> mutateResolved(element, context, target)
        else -> emptyList()
    }
}

internal val AstMutator.semanticTargetKind: SemanticTargetKind?
    get() =
        when (this) {
            is CollectionOperatorMutator -> SemanticTargetKind.COLLECTION
            is TakeIfMutator -> SemanticTargetKind.SCOPE
            is ScopeFunctionMutator -> SemanticTargetKind.SCOPE
            is PreconditionMutator -> SemanticTargetKind.PRECONDITION
            is ResultMutator -> SemanticTargetKind.RESULT
            is DataClassCopyMutator -> SemanticTargetKind.DATA_COPY
            is CoroutineFlowMutator -> SemanticTargetKind.COROUTINE_FLOW
            is CoroutineConcurrencyMutator -> SemanticTargetKind.COROUTINE_CONCURRENCY
            else -> null
        }

internal enum class SemanticTargetKind {
    COLLECTION,
    SCOPE,
    PRECONDITION,
    RESULT,
    DATA_COPY,
    COROUTINE_FLOW,
    COROUTINE_CONCURRENCY,
}

internal fun SemanticTargetKind.requiresTypeValidation(): Boolean =
    this == SemanticTargetKind.COLLECTION ||
        this == SemanticTargetKind.SCOPE ||
        this == SemanticTargetKind.RESULT ||
        this == SemanticTargetKind.DATA_COPY ||
        this == SemanticTargetKind.COROUTINE_CONCURRENCY

internal fun ResolvedSemanticTarget.acceptedBy(kind: SemanticTargetKind): Boolean =
    when (kind) {
        SemanticTargetKind.COLLECTION -> {
            isCollectionTarget()
        }

        SemanticTargetKind.SCOPE -> {
            name in setOf("takeIf", "takeUnless", "apply", "also", "let", "run") && isKotlinTopLevelTarget()
        }

        SemanticTargetKind.PRECONDITION -> {
            name in setOf("require", "check", "requireNotNull", "checkNotNull") && isKotlinTopLevelTarget()
        }

        SemanticTargetKind.RESULT -> {
            name in setOf("getOrElse", "getOrDefault", "getOrNull", "onSuccess", "onFailure") && isKotlinTopLevelTarget()
        }

        SemanticTargetKind.DATA_COPY -> {
            name == "copy" && !userDefined && source !is org.jetbrains.kotlin.psi.KtNamedFunction
        }

        SemanticTargetKind.COROUTINE_FLOW -> {
            name in setOf("delay", "filter", "filterNot", "first", "last") && isCoroutineTarget()
        }

        SemanticTargetKind.COROUTINE_CONCURRENCY -> {
            isCoroutineTarget()
        }
    }

private fun ResolvedSemanticTarget.isCollectionTarget(): Boolean =
    name in
        setOf(
            "filter",
            "filterNot",
            "any",
            "all",
            "take",
            "drop",
            "first",
            "last",
            "map",
            "mapNotNull",
            "sorted",
            "sortedDescending",
            "minOrNull",
            "maxOrNull",
            "find",
            "findLast",
            "associate",
            "associateBy",
        ) &&
        (
            fqName.startsWith("kotlin.collections.") ||
                fqName.startsWith("kotlin.sequences.") ||
                fqName.startsWith("kotlinx.coroutines.flow.")
        )

private fun ResolvedSemanticTarget.isKotlinTopLevelTarget(): Boolean = fqName.startsWith("kotlin.")

private fun ResolvedSemanticTarget.isCoroutineTarget(): Boolean = fqName.startsWith("kotlinx.coroutines.")

internal object InProcessMutantCompiler {
    fun compiles(
        source: String,
        extraClasspath: List<String>,
    ): Boolean =
        synchronized(this) {
            val tempRoot =
                runCatching { Files.createTempDirectory("kronenberg-semantic-mutation") }.getOrNull() ?: return@synchronized false
            try {
                val sourceFile = tempRoot.resolve("Snippet.kt")
                val outputDirectory = tempRoot.resolve("out")
                Files.createDirectories(outputDirectory)
                Files.writeString(sourceFile, source)
                val arguments =
                    K2JVMCompilerArguments().apply {
                        destination = outputDirectory.toString()
                        classpath = K2SnippetFrontend.semanticClasspath(extraClasspath).joinToString(java.io.File.pathSeparator)
                        freeArgs = listOf(sourceFile.toString())
                        jvmTarget = "21"
                    }
                val collector = ErrorMessageCollector()
                K2JVMCompiler().exec(collector, Services.EMPTY, arguments) == ExitCode.OK && !collector.hasErrors()
            } catch (_: Throwable) {
                false
            } finally {
                tempRoot.toFile().deleteRecursively()
            }
        }

    private class ErrorMessageCollector : MessageCollector {
        private var errors = false

        override fun clear() {
            errors = false
        }

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) errors = true
        }

        override fun hasErrors(): Boolean = errors
    }
}
