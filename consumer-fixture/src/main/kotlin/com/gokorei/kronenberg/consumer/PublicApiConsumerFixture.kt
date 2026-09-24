package com.gokorei.kronenberg.consumer

import com.gokorei.kronenberg.ast.AstMutator
import com.gokorei.kronenberg.ast.K2SnippetFrontend
import com.gokorei.kronenberg.ast.MutationContext
import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import com.gokorei.kronenberg.runner.CallGraphReachability
import com.gokorei.kronenberg.runner.TestHarnessSynthesizer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

private class ConsumerBooleanLiteralMutator : AstMutator {
    override val name: String = "ConsumerBooleanLiteralMutator"

    override val category: MutatorCategory = MutatorCategory.BOOLEAN_INVERSION

    override val description: String = "Replaces a true literal with false"

    override fun canMutate(element: PsiElement): Boolean = element is KtConstantExpression && element.text == "true"

    override fun mutate(
        element: PsiElement,
        context: MutationContext,
    ): List<AstEdit> =
        if (canMutate(element)) {
            listOf(context.edit(element, "false", description))
        } else {
            emptyList()
        }
}

public data class ConsumerFixtureResult(
    val edits: List<AstEdit>,
    val strippedSource: String,
    val calledFunctions: Set<String>,
)

public object PublicApiConsumerFixture {
    public fun run(): ConsumerFixtureResult {
        val source = "fun answer(): Boolean = true"
        val file = K2SnippetFrontend.parsePsi(source)
        val context = MutationContext(source, file, "Consumer.kt")
        val mutator = ConsumerBooleanLiteralMutator()
        val edits = mutableListOf<AstEdit>()

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitConstantExpression(expression: KtConstantExpression) {
                    if (mutator.canMutate(expression)) {
                        edits.addAll(mutator.mutate(expression, context))
                    }
                    super.visitConstantExpression(expression)
                }
            },
        )

        val callableSource = "fun consumer() { helper() }"
        val callableFile = K2SnippetFrontend.parsePsi(callableSource)
        val callable = callableFile.declarations.filterIsInstance<KtNamedFunction>().single()
        val testSource = "package sample\nimport kotlin.math.abs\nfun testConsumer() { consumer() }"

        return ConsumerFixtureResult(
            edits = edits,
            strippedSource = TestHarnessSynthesizer.stripPackageAndImports(testSource, K2SnippetFrontend.parsePsi(testSource)),
            calledFunctions = CallGraphReachability.extractCalledFunctionNames(callable),
        )
    }
}
