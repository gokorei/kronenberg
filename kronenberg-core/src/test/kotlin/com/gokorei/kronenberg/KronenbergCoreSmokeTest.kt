package com.gokorei.kronenberg

import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationReport
import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class KronenbergCoreSmokeTest {
    @Test
    fun `domain models serialize and deserialize cleanly to JSON`() {
        val mutant =
            AstMutant(
                id = "mutant-1",
                mutatorName = "RelationalBoundaryMutator",
                category = MutatorCategory.RELATIONAL_BOUNDARY,
                line = 42,
                column = 15,
                originalText = "<",
                replacementText = "<=",
                mutatedSource = "if (x <= 10) return true",
            )

        val result =
            MutantResult(
                mutant = mutant,
                status = MutantStatus.KILLED,
                executionTimeMs = 12L,
            )

        val report =
            MutationReport(
                totalMutants = 1,
                killedCount = 1,
                survivedCount = 0,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 100.0,
                results = listOf(result),
            )

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(report)

        serialized shouldContain "RelationalBoundaryMutator"
        serialized shouldContain "KILLED"

        val deserialized = json.decodeFromString<MutationReport>(serialized)
        deserialized.mutationScore shouldBe 100.0
        deserialized.isPassed shouldBe true
        deserialized.results.first().status shouldBe MutantStatus.KILLED
    }
}
