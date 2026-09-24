package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutatorCategory
import com.gokorei.kronenberg.model.ReportMutant
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SurvivingMutantTestProposerSpec {
    @Test
    fun `synthesizes kotest skeleton proposal for relational boundary mutant`() {
        val mutant =
            ReportMutant(
                id = "mutant-1",
                mutatorName = "RelationalBoundaryMutator",
                category = MutatorCategory.RELATIONAL_BOUNDARY,
                line = 3,
                column = 9,
                filePath = "src/main/kotlin/Checker.kt",
            )
        val sourceCode =
            """
            package com.example

            fun checkVal(x: Int): Boolean = x > 10
            """.trimIndent()

        val proposal = SurvivingMutantTestProposer.proposeTest(mutant, sourceCode, style = TestStyle.KOTEST)

        proposal.targetFunctionName shouldBe "checkVal"
        proposal.testMethodCode shouldContain "test(\"kill surviving mutant in checkVal at line 3\")"
        proposal.testMethodCode shouldContain "// Invariant: verify behavior at line 3"
        proposal.testMethodCode shouldContain "checkVal(/* TODO: boundary value */) shouldBe"
    }

    @Test
    fun `synthesizes junit5 skeleton proposal for return value mutant`() {
        val mutant =
            ReportMutant(
                id = "mutant-2",
                mutatorName = "ReturnValueMutator",
                category = MutatorCategory.RETURN_VALUE,
                line = 3,
                column = 5,
                filePath = "src/main/kotlin/Math.kt",
            )
        val sourceCode =
            """
            fun add(a: Int, b: Int): Int {
                return a + b
            }
            """.trimIndent()

        val proposal = SurvivingMutantTestProposer.proposeTest(mutant, sourceCode, style = TestStyle.JUNIT5)

        proposal.targetFunctionName shouldBe "add"
        proposal.testMethodCode shouldContain "@Test"
        proposal.testMethodCode shouldContain "fun testAddKillMutantLine3()"
        proposal.testMethodCode shouldContain "// Invariant: verify behavior at line 3"
        proposal.testMethodCode shouldContain "assertEquals(/* expected */, add(/* TODO */))"
    }
}
