package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.junit.jupiter.api.Test

class MutatorRegistrySpec {
    @Test
    fun `default registry contains all core mutators`() {
        val registry = MutatorRegistry.default()
        val mutators = registry.mutators(includeExtreme = true)

        mutators shouldHaveAtLeastSize 8
        mutators.map { it.name } shouldContain "RelationalBoundaryMutator"
        mutators.map { it.name } shouldContain "ArithmeticOperatorMutator"
        mutators.map { it.name } shouldContain "BooleanInversionMutator"
        mutators.map { it.name } shouldContain "ReturnValueMutator"
    }

    @Test
    fun `category filter excludes extreme mutators when includeExtreme is false`() {
        val registry = MutatorRegistry.default()
        val standard = registry.mutators(includeExtreme = false)
        val all = registry.mutators(includeExtreme = true)

        all.size shouldBe (all.size)
        standard.any { it.category == MutatorCategory.EXTREME } shouldBe false
    }

    @Test
    fun `custom mutators can be registered via SPI`() {
        val customMutator =
            object : AstMutator {
                override val name: String = "CustomAssertMutator"
                override val category: MutatorCategory = MutatorCategory.RELATIONAL_BOUNDARY
                override val description: String = "Custom assertion rule"

                override fun canMutate(element: PsiElement): Boolean = false

                override fun mutate(
                    element: PsiElement,
                    context: MutationContext,
                ): List<AstEdit> = emptyList()
            }

        val registry = MutatorRegistry().register(customMutator)
        registry.mutators().map { it.name } shouldContain "CustomAssertMutator"
    }
}
