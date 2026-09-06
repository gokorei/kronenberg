package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DataClassMutatorsSpec {
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
    inner class DataClassCopyMutatorTests {
        private val mutator = DataClassCopyMutator()

        @Test
        fun `strips copy parameters to empty call`() {
            mutator.category shouldBe MutatorCategory.EXTREME
            val edits = findMutations("fun bump(user: User) = user.copy(age = user.age + 1)", mutator)
            edits.any { it.replacement == "()" } shouldBe true
        }

        @Test
        fun `drops individual arguments when multiple arguments exist`() {
            val edits = findMutations("fun update(user: User) = user.copy(name = \"Alice\", age = 30)", mutator)
            edits.size shouldBe 3 // all stripped, only name dropped, only age dropped
            edits.any { it.replacement == "()" } shouldBe true
            edits.any { it.replacement == "(age = 30)" } shouldBe true
            edits.any { it.replacement == "(name = \"Alice\")" } shouldBe true
        }
    }

    @Nested
    inner class DestructuringMutatorTests {
        private val mutator = DestructuringMutator()

        @Test
        fun `swaps first two variables in destructuring declaration`() {
            mutator.category shouldBe MutatorCategory.EXTREME
            val edits = findMutations("fun split(p: Pair<Int, String>) { val (id, name) = p }", mutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "(name, id)"
            edits.first().description shouldContain "Swapped destructuring entries"
        }

        @Test
        fun `handles destructuring with more than two variables`() {
            val edits = findMutations("fun split(t: Triple<Int, String, Boolean>) { val (a, b, c) = t }", mutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "(b, a, c)"
        }

        @Test
        fun `correctly handles destructuring with whitespace inside parentheses`() {
            val code = "fun split(p: Pair<Int, String>) { val (  first  ,  second  ) = p }"
            val edits = findMutations(code, mutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "(second, first)"

            val generator = AstMutantGenerator()
            val mutants =
                generator.generateMutants(
                    code,
                    com.gokorei.kronenberg.model
                        .MutationConfig(includeExtreme = true),
                )
            mutants.any { it.mutatedSource.contains("val (second, first) = p") } shouldBe true
        }
    }
}
