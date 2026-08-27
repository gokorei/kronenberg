package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class KronenbergRunnerSmokeTest {
    @Test
    fun `runner pipeline initializes and executes mutation run successfully`() {
        runBlocking {
            val pipeline: MutationExecutionPipeline = DefaultMutationExecutionPipeline()
            val report =
                pipeline.execute(
                    sourceCode = "fun add(a: Int, b: Int): Int = a + b",
                    testCode = "fun main() { check(add(2, 3) == 5) }",
                    config = MutationConfig(),
                )

            report.totalMutants shouldBe 1
            report.killedCount shouldBe 1
            report.survivedCount shouldBe 0
            report.mutationScore shouldBe 100.0
        }
    }
}
