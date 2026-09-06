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
    fun `default mutators include PreconditionMutator in standard audit mode`() {
        val registry = MutatorRegistry.default()
        val standard = registry.mutators(includeExtreme = false)
        standard.map { it.name } shouldContain "PreconditionMutator"
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

    @Test
    fun `default registry contains EqualityMutator and categorizes mutators appropriately`() {
        val registry = MutatorRegistry.default()
        val all = registry.mutators(includeExtreme = true)

        all.map { it.name } shouldContain "EqualityMutator"

        val equalityMutators = registry.mutatorsForCategory(MutatorCategory.EQUALITY)
        equalityMutators.map { it.name } shouldContain "EqualityMutator"

        val coroutineMutators = registry.mutatorsForCategory(MutatorCategory.COROUTINE)
        coroutineMutators.map { it.name } shouldContain "CoroutineFlowMutator"
        coroutineMutators.map { it.name } shouldContain "CoroutineConcurrencyMutator"

        val scopeMutators = registry.mutatorsForCategory(MutatorCategory.SCOPE_FUNCTION)
        scopeMutators.map { it.name } shouldContain "TakeIfMutator"
        scopeMutators.map { it.name } shouldContain "ScopeFunctionMutator"

        val resultMutators = registry.mutatorsForCategory(MutatorCategory.RESULT_ERROR_HANDLING)
        resultMutators.map { it.name } shouldContain "ResultMutator"

        val combined = registry.mutatorsForCategories(setOf(MutatorCategory.COROUTINE, MutatorCategory.RESULT_ERROR_HANDLING))
        combined.map { it.name } shouldContain "CoroutineConcurrencyMutator"
        combined.map { it.name } shouldContain "ResultMutator"
    }
}
