package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.AstMutant
import com.gokorei.kronenberg.model.MutatorCategory

/**
 * Framework style for synthesized test proposals.
 */
public enum class TestStyle {
    KOTEST,
    JUNIT5,
}

/**
 * Synthesized test method proposal guiding developers to kill a surviving mutant.
 */
public data class ProposedTest(
    val mutant: AstMutant,
    val targetFunctionName: String?,
    val style: TestStyle,
    val rationale: String,
    val testMethodCode: String,
)

/**
 * Intelligent test proposal and skeleton synthesizer for surviving mutants.
 */
public object SurvivingMutantTestProposer {
    public fun proposeTest(
        mutant: AstMutant,
        sourceCode: String,
        style: TestStyle = TestStyle.KOTEST,
    ): ProposedTest {
        val enclosingFn = CallGraphReachability.findEnclosingFunctionName(sourceCode, mutant.line)
        val fnName = enclosingFn ?: "targetFunction"
        val capitalizedFn = fnName.replaceFirstChar { it.uppercase() }

        val rationale =
            "Mutant survived because tests did not distinguish between original expression " +
                "'${mutant.originalText}' and mutated replacement '${mutant.replacementText}'."

        val code =
            when (style) {
                TestStyle.KOTEST -> {
                    buildString {
                        appendLine("    test(\"kill surviving mutant in $fnName at line ${mutant.line}\") {")
                        appendLine(
                            "        // Survived: ${mutant.mutatorName} replaced '${mutant.originalText}' with '${mutant.replacementText}'",
                        )
                        appendLine("        // Invariant: verify behavior specifically distinguishing this replacement")
                        when (mutant.category) {
                            MutatorCategory.RELATIONAL_BOUNDARY -> {
                                appendLine("        $fnName(/* TODO: boundary value */) shouldBe /* expected */")
                            }

                            MutatorCategory.RETURN_VALUE -> {
                                appendLine("        $fnName(/* TODO: parameters */) shouldBe /* expected non-mutated return */")
                            }

                            MutatorCategory.BOOLEAN_INVERSION, MutatorCategory.EQUALITY -> {
                                appendLine("        $fnName(/* TODO: condition-specific input */) shouldBe /* expected */")
                            }

                            else -> {
                                appendLine("        $fnName(/* TODO: input */) shouldBe /* expected */")
                            }
                        }
                        append("    }")
                    }
                }

                TestStyle.JUNIT5 -> {
                    buildString {
                        appendLine("    @Test")
                        appendLine("    fun test${capitalizedFn}KillMutantLine${mutant.line}() {")
                        appendLine(
                            "        // Survived: ${mutant.mutatorName} replaced '${mutant.originalText}' with '${mutant.replacementText}'",
                        )
                        appendLine("        // Invariant: verify behavior specifically distinguishing this replacement")
                        when (mutant.category) {
                            MutatorCategory.RELATIONAL_BOUNDARY -> {
                                appendLine("        assertEquals(/* expected */, $fnName(/* TODO: boundary value */))")
                            }

                            MutatorCategory.RETURN_VALUE -> {
                                appendLine("        assertEquals(/* expected */, $fnName(/* TODO */))")
                            }

                            else -> {
                                appendLine("        assertEquals(/* expected */, $fnName(/* TODO: input */))")
                            }
                        }
                        append("    }")
                    }
                }
            }

        return ProposedTest(
            mutant = mutant,
            targetFunctionName = enclosingFn,
            style = style,
            rationale = rationale,
            testMethodCode = code,
        )
    }
}
