package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CoroutineConcurrencyMutatorsSpec {
    private fun parse(code: String): KtFile = K2SnippetFrontend.parsePsi(code)

    private fun context(code: String): MutationContext = MutationContext(code, parse(code))

    private fun findMutations(
        code: String,
        mutator: AstMutator,
    ) = buildList {
        val ctx = context(code)
        ctx.file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    if (mutator.canMutate(element)) {
                        addAll(mutator.mutate(element, ctx))
                    }
                }
            },
        )
    }

    @Nested
    inner class CoroutineConcurrencyMutatorTests {
        private val mutator = CoroutineConcurrencyMutator()

        @Test
        fun `mutates Dispatchers IO to Default and vice versa`() {
            mutator.category shouldBe MutatorCategory.COROUTINE
            val editsIo = findMutations("suspend fun runWork() = withContext(Dispatchers.IO) { 1 }", mutator)
            editsIo.any { it.replacement == "Dispatchers.Default" } shouldBe true

            val editsDef = findMutations("suspend fun runWork() = withContext(Dispatchers.Default) { 1 }", mutator)
            editsDef.any { it.replacement == "Dispatchers.IO" } shouldBe true
        }

        @Test
        fun `mutates SupervisorJob to Job and supervisorScope to coroutineScope`() {
            val editsJob = findMutations("val job = SupervisorJob()", mutator)
            editsJob.any { it.replacement == "Job" } shouldBe true

            val editsScope = findMutations("suspend fun scope() = supervisorScope { 1 }", mutator)
            editsScope.any { it.replacement == "coroutineScope" } shouldBe true

            val editsCoro = findMutations("suspend fun scope() = coroutineScope { 1 }", mutator)
            editsCoro.any { it.replacement == "supervisorScope" } shouldBe true
        }

        @Test
        fun `mutates async to launch builder`() {
            val edits = findMutations("fun launchAsync(scope: CoroutineScope) { scope.async { 42 } }", mutator)
            edits.any { it.replacement == "launch" } shouldBe true
        }
    }
}
