package com.gokorei.kronenberg.gradle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KronenbergPluginSpec {
    @Test
    fun `plugin applies cleanly and registers extension and kronenbergCheck task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.gokorei.kronenberg")

        val extension = project.extensions.findByName("kronenberg") as? KronenbergExtension
        extension shouldBe extension
        extension!!.minScore.get() shouldBe 80.0
        extension.baselineTimeoutMs.get() shouldBe 2000L

        val task = project.tasks.findByName("kronenbergCheck") as? KronenbergAuditTask
        task shouldBe task
        task!!.group shouldBe "verification"
    }

    @Test
    fun `task audit succeeds and outputs reports on sample kotlin project`(
        @TempDir testProjectDir: File,
    ) {
        val buildFile = File(testProjectDir, "build.gradle.kts")
        val settingsFile = File(testProjectDir, "settings.gradle.kts")
        settingsFile.writeText("rootProject.name = \"test-sample\"")

        buildFile.writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("com.gokorei.kronenberg")
            }

            repositories {
                mavenCentral()
            }

            kronenberg {
                minScore.set(50.0)
            }
            """.trimIndent(),
        )

        val srcDir = File(testProjectDir, "src/main/kotlin")
        srcDir.mkdirs()
        File(srcDir, "Calculator.kt").writeText(
            """
            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent(),
        )

        val testDir = File(testProjectDir, "src/test/kotlin")
        testDir.mkdirs()
        File(testDir, "CalculatorTest.kt").writeText(
            """
            fun main() {
                check(add(2, 3) == 5)
            }
            """.trimIndent(),
        )

        val runner =
            GradleRunner
                .create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments("kronenbergCheck", "--stacktrace")

        val result = runner.build()
        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "KRONENBERG MUTATION AUDIT"

        val reportHtml = File(testProjectDir, "build/reports/kronenberg/mutation-report.html")
        reportHtml.exists() shouldBe true
        reportHtml.readText() shouldContain "Kronenberg Mutation Audit"

        val reportXml = File(testProjectDir, "build/reports/kronenberg/mutation-results.xml")
        reportXml.exists() shouldBe true
        reportXml.readText() shouldContain "<testsuite name=\"Kronenberg Mutation Audit\""
    }

    @Test
    fun `task audit fails when mutation score is below configured threshold`(
        @TempDir testProjectDir: File,
    ) {
        val buildFile = File(testProjectDir, "build.gradle.kts")
        val settingsFile = File(testProjectDir, "settings.gradle.kts")
        settingsFile.writeText("rootProject.name = \"test-fail-sample\"")

        buildFile.writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("com.gokorei.kronenberg")
            }

            repositories {
                mavenCentral()
            }

            kronenberg {
                minScore.set(100.0)
            }
            """.trimIndent(),
        )

        val srcDir = File(testProjectDir, "src/main/kotlin")
        srcDir.mkdirs()
        File(srcDir, "Complex.kt").writeText(
            """
            fun calculate(x: Int, y: Int): Int {
                if (x > 10) return x * 2
                return x + y
            }
            """.trimIndent(),
        )

        val testDir = File(testProjectDir, "src/test/kotlin")
        testDir.mkdirs()
        // Incomplete test suite that lets mutants survive
        File(testDir, "ComplexTest.kt").writeText(
            """
            fun main() {
                check(calculate(5, 5) == 10)
            }
            """.trimIndent(),
        )

        val runner =
            GradleRunner
                .create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments("kronenbergCheck")

        val result = runner.buildAndFail()
        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "did not pass threshold 100.0%"
    }

    @Test
    fun `task audit reports an all-skipped batch and fails closed`(
        @TempDir testProjectDir: File,
    ) {
        createKronenbergProject(testProjectDir)
        val srcDir = File(testProjectDir, "src/main/kotlin").apply { mkdirs() }
        File(srcDir, "First.kt").writeText("fun first(x: Int) = x + 1")
        File(srcDir, "Second.kt").writeText("fun second(x: Int) = x - 1")
        File(testProjectDir, "src/test/kotlin").mkdirs()

        val result = kronenbergRunner(testProjectDir).buildAndFail()

        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "First.kt"
        result.output shouldContain "Second.kt"
        result.output shouldContain "MISSING_TEST"
        val reportHtml = File(testProjectDir, "build/reports/kronenberg/mutation-report.html")
        val reportXml = File(testProjectDir, "build/reports/kronenberg/mutation-results.xml")
        reportHtml.readText() shouldContain "MISSING_TEST"
        reportXml.readText() shouldContain "MissingTest"
    }

    @Test
    fun `task audit preserves per-file baseline failures in a mixed batch`(
        @TempDir testProjectDir: File,
    ) {
        createKronenbergProject(testProjectDir)
        val srcDir = File(testProjectDir, "src/main/kotlin").apply { mkdirs() }
        File(srcDir, "Valid.kt").writeText("fun add(a: Int, b: Int) = a + b")
        File(srcDir, "Broken.kt").writeText("fun broken(): Int = 42")
        val testDir = File(testProjectDir, "src/test/kotlin").apply { mkdirs() }
        File(testDir, "ValidTest.kt").writeText("fun main() { check(add(1, 2) == 3) }")
        File(testDir, "BrokenTest.kt").writeText("fun main() { check(1 == 2) }")

        val result = kronenbergRunner(testProjectDir).buildAndFail()

        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "Broken.kt"
        result.output shouldContain "BASELINE_ERROR"
        val reportHtml = File(testProjectDir, "build/reports/kronenberg/mutation-report.html")
        val reportXml = File(testProjectDir, "build/reports/kronenberg/mutation-results.xml")
        reportHtml.readText() shouldContain "Broken.kt"
        reportHtml.readText() shouldContain "Baseline test failed before mutation"
        reportXml.readText() shouldContain "BaselineError"
    }

    @Test
    fun `task audit reports ambiguous test mappings and fails closed`(
        @TempDir testProjectDir: File,
    ) {
        createKronenbergProject(testProjectDir)
        val srcDir = File(testProjectDir, "src/main/kotlin").apply { mkdirs() }
        File(srcDir, "Calculator.kt").writeText("fun add(a: Int, b: Int) = a + b")
        val testDir = File(testProjectDir, "src/test/kotlin").apply { mkdirs() }
        File(testDir, "CalculatorTest.kt").writeText("fun main() { check(add(1, 2) == 3) }")
        File(testDir, "CalculatorSpec.kt").writeText("fun main() { check(add(1, 2) == 3) }")

        val result = kronenbergRunner(testProjectDir).buildAndFail()

        result.task(":kronenbergCheck")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "AMBIGUOUS_TEST"
        val reportHtml = File(testProjectDir, "build/reports/kronenberg/mutation-report.html")
        reportHtml.readText() shouldContain "Multiple matching test files found"
    }

    private fun createKronenbergProject(testProjectDir: File) {
        File(testProjectDir, "settings.gradle.kts").writeText("rootProject.name = \"batch-audit-sample\"")
        File(testProjectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("com.gokorei.kronenberg")
            }

            repositories {
                mavenCentral()
            }

            kronenberg {
                minScore.set(0.0)
            }
            """.trimIndent(),
        )
    }

    private fun kronenbergRunner(testProjectDir: File): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("kronenbergCheck", "--stacktrace")
}
