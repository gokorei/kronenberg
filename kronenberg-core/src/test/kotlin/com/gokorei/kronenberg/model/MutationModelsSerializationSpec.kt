package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
    fun `compact public report omits execution source`() {
        val secret = "S9WT162W_SOURCE_SECRET"
        val result =
            MutantResult(
                mutant =
                    ReportMutant(
                        id = "mutant-redacted",
                        mutatorName = "ArithmeticOperatorMutator",
                        category = MutatorCategory.ARITHMETIC_OPERATOR,
                        line = 1,
                        column = 25,
                        filePath = "src/main/kotlin/Secret.kt",
                    ),
                status = MutantStatus.SURVIVED,
                executionTimeMs = 3L,
            )
        val report =
            MutationReport(
                totalMutants = 1,
                killedCount = 0,
                survivedCount = 1,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = listOf(result),
            )

        json.encodeToString(report) shouldNotContain secret
        json.encodeToString(report) shouldNotContain "mutatedSource"
        json.encodeToString(report) shouldNotContain "originalText"
        json.encodeToString(report) shouldNotContain "replacementText"
        json.encodeToString(report) shouldNotContain "failureMessage"
    }

    @Test
    fun `roundtrip serialization for complete MutationReport`() {
        val mutant =
            ReportMutant(
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
