package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PreconditionMutatorsSpec {
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
    inner class PreconditionMutatorTests {
        private val mutator = PreconditionMutator()

        @Test
        fun `mutates require with negation and bypass`() {
            mutator.category shouldBe MutatorCategory.PRECONDITION
            val edits = findMutations("fun check(amount: Int) { require(amount > 0) { \"invalid\" } }", mutator)
            edits.size shouldBe 2
            edits.any { it.replacement == "!(amount > 0)" } shouldBe true
            edits.any { it.replacement == "true" } shouldBe true
        }

        @Test
        fun `mutates check condition with un-negation and bypass`() {
            val edits = findMutations("fun checkState(flag: Boolean) { check(!flag) }", mutator)
            edits.size shouldBe 2
            edits.any { it.replacement == "flag" } shouldBe true
            edits.any { it.replacement == "true" } shouldBe true
        }

        @Test
        fun `unwraps requireNotNull and checkNotNull calls to their argument`() {
            val reqEdits = findMutations("fun parse(s: String?) { val res = requireNotNull(s) }", mutator)
            reqEdits.any { it.replacement == "s" } shouldBe true

            val chkEdits = findMutations("fun parse(s: String?) { val res = checkNotNull(s) { \"missing\" } }", mutator)
            chkEdits.any { it.replacement == "s" } shouldBe true
        }
    }
}
