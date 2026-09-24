package com.gokorei.kronenberg.ast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResultMutatorsSpec {
    private data class Case(
        val source: String,
        val originalText: String,
        val replacementText: String,
        val mutatedSource: String,
    )

    @Test
    fun `mutates complete Result calls with compilable source`() {
        val cases =
            listOf(
                Case(
                    source = "fun recover(result: Result<Int>): Int = result.getOrElse { 0 }",
                    originalText = "getOrElse { 0 }",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int = result.getOrThrow()",
                ),
                Case(
                    source = "fun recover(result: Result<Int>): Int = result.getOrDefault(0)",
                    originalText = "getOrDefault(0)",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int = result.getOrThrow()",
                ),
                Case(
                    source = "fun recover(result: Result<Int>): Int? = result.getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int? = result.getOrThrow()",
                ),
                Case(
                    source = "fun observe(result: Result<Int>): Result<Int> = result.onSuccess { println(it) }",
                    originalText = "onSuccess { println(it) }",
                    replacementText = "onFailure {}",
                    mutatedSource = "fun observe(result: Result<Int>): Result<Int> = result.onFailure {}",
                ),
                Case(
                    source = "fun observe(result: Result<Int>): Result<Int> = result.onFailure { println(it) }",
                    originalText = "onFailure { println(it) }",
                    replacementText = "onSuccess {}",
                    mutatedSource = "fun observe(result: Result<Int>): Result<Int> = result.onSuccess {}",
                ),
            )

        val mutator = ResultMutator()
        cases.forEach { case ->
            val mutants =
                AstMutantGenerator(MutatorRegistry(listOf(mutator)))
                    .generateMutants(case.source)
            mutants.size shouldBe 1
            mutants.single().originalText shouldBe case.originalText
            mutants.single().replacementText shouldBe case.replacementText
            mutants.single().mutatedSource shouldBe case.mutatedSource
        }
    }
}
