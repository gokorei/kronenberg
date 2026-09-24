package com.gokorei.kronenberg.gradle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KronenbergConfigurationValidationSpec {
    @Test
    fun `extension and task expose timeout multiplier conventions`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.gokorei.kronenberg")

        val extension = project.extensions.getByType(KronenbergExtension::class.java)
        val task = project.tasks.getByName("kronenbergCheck") as KronenbergAuditTask

        extension.timeoutMultiplier.get() shouldBe 3.0
        task.timeoutMultiplier.get() shouldBe 3.0
    }

    @Test
    fun `task fails with structured errors before generating reports`(
        @TempDir testProjectDir: File,
    ) {
        File(testProjectDir, "settings.gradle.kts").writeText("rootProject.name = \"invalid-config\"")
        File(testProjectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.gokorei.kronenberg")
            }

            kronenberg {
                minScore.set(Double.NaN)
                baselineTimeoutMs.set(0L)
                timeoutMultiplier.set(Double.POSITIVE_INFINITY)
                maxMutants.set(-1)
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments("kronenbergCheck", "--stacktrace")
                .buildAndFail()

        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "Configuration error [THRESHOLD_NOT_FINITE] minScore"
        result.output shouldContain "Configuration error [TIMEOUT_OUT_OF_RANGE] baselineTimeoutMs"
        result.output shouldContain "Configuration error [TIMEOUT_MULTIPLIER_NOT_FINITE] timeoutMultiplier"
        result.output shouldContain "Configuration error [MAX_MUTANTS_OUT_OF_RANGE] maxMutants"
        File(testProjectDir, "build/reports/kronenberg/mutation-report.html").exists() shouldBe false
        File(testProjectDir, "build/reports/kronenberg/mutation-results.xml").exists() shouldBe false
    }
}
