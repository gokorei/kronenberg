package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class MutationModelsSerializationSpec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `roundtrip serialization for complete MutationReport`() {
        val mutant =
            AstMutant(
                id = "fom-1",
                mutatorName = "ArithmeticOperatorMutator",
                category = MutatorCategory.ARITHMETIC_OPERATOR,
                line = 10,
                column = 18,
                originalText = "+",
                replacementText = "-",
                mutatedSource = "fun sum(a: Int, b: Int) = a - b",
            )

        val result =
            MutantResult(
                mutant = mutant,
                status = MutantStatus.KILLED,
                executionTimeMs = 15L,
                failureMessage = "Assertion failed at line 5",
            )

        val report =
            MutationReport(
                totalMutants = 10,
                killedCount = 9,
                survivedCount = 1,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 90.0,
                results = listOf(result),
            )

        val serialized = json.encodeToString(report)
        serialized shouldContain "\"totalMutants\": 10"
        serialized shouldContain "\"mutationScore\": 90.0"

        val deserialized = json.decodeFromString<MutationReport>(serialized)
        deserialized.totalMutants shouldBe 10
        deserialized.killedCount shouldBe 9
        deserialized.survivedCount shouldBe 1
        deserialized.mutationScore shouldBe 90.0
        deserialized.isPassed shouldBe false
        deserialized.results
            .first()
            .mutant.replacementText shouldBe "-"
    }

    @Test
    fun `MutationConfig serialization preserves custom parameters`() {
        val config =
            MutationConfig(
                minScore = 95.0,
                timeoutMultiplier = 4.0,
                baselineTimeoutMs = 500L,
                higherOrderMutants = true,
                includeExtreme = true,
                maxMutants = 50,
            )

        val serialized = json.encodeToString(config)
        val deserialized = json.decodeFromString<MutationConfig>(serialized)

        deserialized.minScore shouldBe 95.0
        deserialized.timeoutMultiplier shouldBe 4.0
        deserialized.higherOrderMutants shouldBe true
        deserialized.maxMutants shouldBe 50
    }
}
