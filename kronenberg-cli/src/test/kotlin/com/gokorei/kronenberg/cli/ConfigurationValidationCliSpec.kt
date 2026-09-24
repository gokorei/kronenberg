package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.gokorei.kronenberg.model.ConfigurationErrorCode
import com.gokorei.kronenberg.model.MutationReport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class ConfigurationValidationCliSpec {
    @Test
    fun `returns structured json errors and skips report exporters`(
        @TempDir tempDir: File,
    ) {
        val source = Files.writeString(tempDir.toPath().resolve("Sample.kt"), "fun add(a: Int, b: Int) = a + b")
        val test = Files.writeString(tempDir.toPath().resolve("SampleTest.kt"), "fun main() { check(add(1, 2) == 3) }")
        val html = tempDir.toPath().resolve("report.html")

        val result =
            KronenbergCli().subcommands(AuditCommand()).test(
                "audit --source $source --test $test --threshold NaN --html-report $html --json",
            )

        result.statusCode shouldBe 1
        Files.exists(html) shouldBe false
        val report = Json.decodeFromString<MutationReport>(result.output)
        report.configurationErrors.map { it.code } shouldBe listOf(ConfigurationErrorCode.THRESHOLD_NOT_FINITE)
        report.totalMutants shouldBe 0
        report.results shouldBe emptyList()
    }

    @Test
    fun `validates configuration when source directory has no Kotlin files`(
        @TempDir tempDir: File,
    ) {
        val sourceDir = Files.createDirectory(tempDir.toPath().resolve("main"))

        val result =
            KronenbergCli().subcommands(AuditCommand()).test(
                "audit --source-dir $sourceDir --threshold NaN",
            )

        result.statusCode shouldBe 1
        result.output shouldContain "Configuration error [THRESHOLD_NOT_FINITE] minScore"
    }

    @Test
    fun `displays timeout multiplier errors before mutant auditing`(
        @TempDir tempDir: File,
    ) {
        val source = Files.writeString(tempDir.toPath().resolve("Sample.kt"), "fun add(a: Int, b: Int) = a + b")
        val test = Files.writeString(tempDir.toPath().resolve("SampleTest.kt"), "fun main() { check(add(1, 2) == 3) }")

        val result =
            KronenbergCli().subcommands(AuditCommand()).test(
                "audit --source $source --test $test --timeout-multiplier Infinity",
            )

        result.statusCode shouldBe 1
        result.output shouldContain "Configuration error [TIMEOUT_MULTIPLIER_NOT_FINITE] timeoutMultiplier"
    }
}
