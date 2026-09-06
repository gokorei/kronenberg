package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResultMutatorsSpec {
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
    inner class ResultMutatorTests {
        private val mutator = ResultMutator()

        @Test
        fun `mutates getOrElse and getOrDefault and getOrNull to getOrThrow`() {
            mutator.category shouldBe MutatorCategory.RESULT_ERROR_HANDLING
            val edits1 = findMutations("fun compute(res: Result<Int>) = res.getOrElse { 0 }", mutator)
            edits1.any { it.replacement == "getOrThrow" } shouldBe true

            val edits2 = findMutations("fun compute(res: Result<Int>) = res.getOrDefault(0)", mutator)
            edits2.any { it.replacement == "getOrThrow" } shouldBe true

            val edits3 = findMutations("fun compute(res: Result<Int>) = res.getOrNull()", mutator)
            edits3.any { it.replacement == "getOrThrow" } shouldBe true
        }

        @Test
        fun `inverts onSuccess and onFailure callbacks`() {
            val sEdits = findMutations("fun log(res: Result<Int>) = res.onSuccess { println(it) }", mutator)
            sEdits.any { it.replacement == "onFailure" } shouldBe true

            val fEdits = findMutations("fun log(res: Result<Int>) = res.onFailure { println(it) }", mutator)
            fEdits.any { it.replacement == "onSuccess" } shouldBe true
        }
    }
}
