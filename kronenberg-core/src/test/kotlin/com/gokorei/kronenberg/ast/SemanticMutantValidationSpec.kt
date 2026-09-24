package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SemanticMutantValidationSpec {
    private val generator = AstMutantGenerator()

    @Test
    fun `resolves standard library targets before applying standard mutators`() {
        val source =
            """
            package application
            fun checked(flag: Boolean) {
                check(flag)
            }
            fun mapped(values: List<Int>): List<Int> = values.map { it * 2 }
            """.trimIndent()

        val result = generator.generateMutationResult(source)
        result.mutants.any { it.mutatorName == "CollectionOperatorMutator" } shouldBe true
        result.mutants.any { it.mutatorName == "PreconditionMutator" } shouldBe true
        result.discarded.shouldBeEmpty()
    }

    @Test
    fun `discards user-defined map check async and copy targets`() {
        val source =
            """
            package application.api

            class UserApi {
                fun map(value: Int): Int = value
                fun check(flag: Boolean) = Unit
                fun async(block: () -> Int): Int = block()
                fun copy(value: Int): Int = value
            }

            fun use(api: UserApi): Int {
                api.check(true)
                return api.map(api.async { 2 }) + api.copy(3)
            }
            """.trimIndent()

        val result = generator.generateMutationResult(source, MutationConfig(includeExtreme = true))
        val standardNames =
            setOf(
                "CollectionOperatorMutator",
                "PreconditionMutator",
                "CoroutineConcurrencyMutator",
                "DataClassCopyMutator",
            )

        result.mutants.none { it.mutatorName in standardNames } shouldBe true
        result.discarded.map { it.mutatorName }.toSet() shouldBe standardNames
        result.discarded.all { it.reason == MutantDiscardReason.NON_STANDARD_TARGET } shouldBe true
        result.discarded.all { it.resolvedTarget?.startsWith("application.api.UserApi.") == true } shouldBe true
    }

    @Test
    fun `resolves package imports aliases and nested user targets`() {
        val source =
            """
            package application.nested

            import kotlin.collections.filter as projectFilter

            class Outer {
                class Nested {
                    fun map(value: Int): Int = value
                }
            }

            fun standard(values: List<Int>): List<Int> = values.map { it * 2 }
            fun aliased(values: List<Int>): List<Int> = values.projectFilter { it > 0 }
            fun custom(outer: Outer.Nested): Int = outer.map(3)
            """.trimIndent()

        val result = generator.generateMutationResult(source)
        val collectionMutants = result.mutants.filter { it.mutatorName == "CollectionOperatorMutator" }

        collectionMutants.map { it.mutatedSource }.any { it.contains("values.mapNotNull") } shouldBe true
        collectionMutants.map { it.mutatedSource }.any { it.contains("values.filterNot") } shouldBe true
        result.discarded.map { it.mutatorName } shouldContain "CollectionOperatorMutator"
        result.discarded.single { it.mutatorName == "CollectionOperatorMutator" }.resolvedTarget shouldBe
            "application.nested.Outer.Nested.map"
    }

    @Test
    fun `discards type-invalid standard transformations without returning compile error mutants`() {
        val source =
            """
            package application.types

            fun associateValues(values: List<Pair<Int, String>>): Map<Int, String> = values.associate { it }

            fun compute(result: Result<Int>): Int = result.getOrElse { 0 }

            fun valid(result: Result<Int>): Int? = result.getOrNull()
            """.trimIndent()

        val result = generator.generateMutationResult(source)

        result.mutants.none { it.mutatorName == "CollectionOperatorMutator" } shouldBe true
        result.mutants.any { it.mutatorName == "ResultMutator" } shouldBe true
        result.discarded.count { it.reason == MutantDiscardReason.TYPE_INVALID_TRANSFORMATION } shouldBe 2
        result.discarded.filter { it.reason == MutantDiscardReason.TYPE_INVALID_TRANSFORMATION }.all {
            it.resolvedTarget?.startsWith("kotlin.") == true
        } shouldBe true
    }
}
