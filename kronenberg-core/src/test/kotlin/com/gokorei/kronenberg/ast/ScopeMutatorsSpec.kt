package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ScopeMutatorsSpec {
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
    inner class TakeIfMutatorTests {
        private val mutator = TakeIfMutator()

        @Test
        fun `inverts takeIf to takeUnless`() {
            mutator.category shouldBe MutatorCategory.SCOPE_FUNCTION
            val edits = findMutations("fun check(x: Int) = x.takeIf { it > 0 }", mutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "takeUnless"
            edits.first().description shouldContain "takeUnless"
        }

        @Test
        fun `inverts takeUnless to takeIf`() {
            val edits = findMutations("fun check(x: Int) = x.takeUnless { it == 0 }", mutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "takeIf"
        }
    }

    @Nested
    inner class ScopeFunctionMutatorTests {
        private val mutator = ScopeFunctionMutator()

        @Test
        fun `mutates apply to also`() {
            mutator.category shouldBe MutatorCategory.SCOPE_FUNCTION
            val edits = findMutations("fun configure(sb: StringBuilder) = sb.apply { append(1) }", mutator)
            edits.any { it.replacement == "also" } shouldBe true
        }

        @Test
        fun `mutates also to apply`() {
            val edits = findMutations("fun log(x: String) = x.also { println(it) }", mutator)
            edits.any { it.replacement == "apply" } shouldBe true
        }

        @Test
        fun `mutates let to run`() {
            val edits = findMutations("fun transform(x: Int) = x.let { it * 2 }", mutator)
            edits.any { it.replacement == "run" } shouldBe true
        }

        @Test
        fun `mutates run to let`() {
            val edits = findMutations("fun exec(x: Int) = x.run { this + 1 }", mutator)
            edits.any { it.replacement == "let" } shouldBe true
        }
    }
}
