package com.gokorei.kronenberg.doc

import com.gokorei.kronenberg.ast.MutatorRegistry
import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MutatorDocGeneratorSpec {
    private val generator: MutatorDocGenerator = DefaultMutatorDocGenerator()

    @Test
    fun `generates markdown reference containing all default mutators`() {
        val markdown = generator.generateMarkdownReference()
        val allMutators = MutatorRegistry.default().mutators(includeExtreme = true)

        markdown shouldContain "# Kronenberg AST Mutators Reference"
        markdown shouldContain "**Total Mutators**: ${allMutators.size}"

        for (mutator in allMutators) {
            markdown shouldContain mutator.name
            markdown shouldContain mutator.description.replace("|", "\\|")
        }
    }

    @Test
    fun `groups mutators by category in markdown reference`() {
        val markdown = generator.generateMarkdownReference()

        markdown shouldContain "### Relational Boundary"
        markdown shouldContain "### Equality & Identity"
        markdown shouldContain "### Coroutine & Concurrency"
        markdown shouldContain "### Scope Function"
        markdown shouldContain "### Result & Error Handling"
        markdown shouldContain "### Precondition & Defensive Assertion"
    }

    @Test
    fun `generates compact summary markdown table`() {
        val table = generator.generateSummaryTable()

        table shouldContain "| Mutator Category | Mutators | Target Syntax & Examples |"
        table shouldContain "| **Relational Boundary** |"
        table shouldContain "| **Equality & Identity** |"
        table shouldContain "| **Coroutine & Concurrency** |"
    }
}
