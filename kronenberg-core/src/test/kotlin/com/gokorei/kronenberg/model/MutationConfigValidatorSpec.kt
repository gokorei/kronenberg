package com.gokorei.kronenberg.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

class MutationConfigValidatorSpec {
    @Test
    fun `rejects invalid numeric configuration with structured errors`() {
        val cases =
            listOf(
                MutationConfig(minScore = Double.NaN) to ConfigurationErrorCode.THRESHOLD_NOT_FINITE,
                MutationConfig(minScore = -0.1) to ConfigurationErrorCode.THRESHOLD_OUT_OF_RANGE,
                MutationConfig(minScore = 100.1) to ConfigurationErrorCode.THRESHOLD_OUT_OF_RANGE,
                MutationConfig(baselineTimeoutMs = 0L) to ConfigurationErrorCode.TIMEOUT_OUT_OF_RANGE,
                MutationConfig(baselineTimeoutMs = MutationConfigValidator.MAX_BASELINE_TIMEOUT_MS + 1L) to
                    ConfigurationErrorCode.TIMEOUT_OUT_OF_RANGE,
                MutationConfig(timeoutMultiplier = Double.NaN) to ConfigurationErrorCode.TIMEOUT_MULTIPLIER_NOT_FINITE,
                MutationConfig(timeoutMultiplier = Double.POSITIVE_INFINITY) to ConfigurationErrorCode.TIMEOUT_MULTIPLIER_NOT_FINITE,
                MutationConfig(timeoutMultiplier = 0.0) to ConfigurationErrorCode.TIMEOUT_MULTIPLIER_OUT_OF_RANGE,
                MutationConfig(timeoutMultiplier = 101.0) to ConfigurationErrorCode.TIMEOUT_MULTIPLIER_OUT_OF_RANGE,
                MutationConfig(maxMutants = 0) to ConfigurationErrorCode.MAX_MUTANTS_OUT_OF_RANGE,
                MutationConfig(maxMutants = -1) to ConfigurationErrorCode.MAX_MUTANTS_OUT_OF_RANGE,
                MutationConfig(maxMutants = MutationConfigValidator.MAX_MUTANTS + 1) to ConfigurationErrorCode.MAX_MUTANTS_OUT_OF_RANGE,
            )

        cases.forEach { (config, expectedCode) ->
            val result = MutationConfigValidator.validate(config)
            result.shouldBeInstanceOfInvalid(expectedCode)
        }
    }

    @Test
    fun `accepts supported configuration boundaries`() {
        val result =
            MutationConfigValidator.validate(
                MutationConfig(
                    minScore = 0.0,
                    baselineTimeoutMs = 1L,
                    timeoutMultiplier = 1.0,
                    maxMutants = 1,
                ),
            )

        (result as MutationConfigValidation.Valid).config shouldBe
            MutationConfig(
                minScore = 0.0,
                baselineTimeoutMs = 1L,
                timeoutMultiplier = 1.0,
                maxMutants = 1,
            )
    }

    @Test
    fun `enforces source and test size limits`() {
        val sourceResult =
            MutationConfigValidator.validateExecution(
                MutationConfig(),
                "x".repeat(MutationConfigValidator.MAX_SOURCE_CODE_CHARS + 1),
                "",
            )
        sourceResult.shouldBeInstanceOfInvalid(ConfigurationErrorCode.SOURCE_TOO_LARGE)

        val testResult =
            MutationConfigValidator.validateExecution(
                MutationConfig(),
                "",
                "x".repeat(MutationConfigValidator.MAX_TEST_CODE_CHARS + 1),
            )
        testResult.shouldBeInstanceOfInvalid(ConfigurationErrorCode.TEST_TOO_LARGE)
    }

    @Test
    fun `canonicalizes and deduplicates classpath entries`(
        @TempDir tempDir: Path,
    ) {
        val dependency = Files.createDirectories(tempDir.resolve("lib/dependency"))
        val result =
            MutationConfigValidator.validate(
                MutationConfig(
                    extraClasspath =
                        listOf(
                            dependency.toString(),
                            tempDir.resolve("lib/./dependency").toString(),
                            "   ",
                        ),
                ),
            )

        (result as MutationConfigValidation.Valid).config.extraClasspath shouldContainExactly
            listOf(dependency.toRealPath().toString())
    }

    @Test
    fun `rejects oversized classpath entries and canonicalizes pending paths`(
        @TempDir tempDir: Path,
    ) {
        val oversized =
            MutationConfigValidator.validate(
                MutationConfig(extraClasspath = listOf("x".repeat(MutationConfigValidator.MAX_CLASSPATH_ENTRY_CHARS + 1))),
            )
        oversized.shouldBeInstanceOfInvalid(ConfigurationErrorCode.CLASSPATH_ENTRY_TOO_LARGE)

        val missing =
            MutationConfigValidator.validate(
                MutationConfig(extraClasspath = listOf(tempDir.resolve("missing").toString())),
            )
        (missing as MutationConfigValidation.Valid).config.extraClasspath shouldContainExactly
            listOf(
                tempDir
                    .resolve("missing")
                    .toAbsolutePath()
                    .normalize()
                    .toString(),
            )
    }

    @Test
    fun `enforces classpath and source test file count limits`(
        @TempDir tempDir: Path,
    ) {
        val source = Files.writeString(tempDir.resolve("Source.kt"), "fun source() = Unit")
        val test = Files.writeString(tempDir.resolve("SourceTest.kt"), "fun main() = Unit")

        val classpathErrors =
            MutationConfigValidator.validate(
                MutationConfig(
                    extraClasspath = List(MutationConfigValidator.MAX_CLASSPATH_ENTRIES + 1) { source.toString() },
                ),
            )
        classpathErrors.shouldBeInstanceOfInvalid(ConfigurationErrorCode.CLASSPATH_TOO_MANY)

        val fileErrors =
            MutationConfigValidator.validateFileSets(
                List(MutationConfigValidator.MAX_SOURCE_FILES + 1) { source },
                List(MutationConfigValidator.MAX_TEST_FILES + 1) { test },
            )
        fileErrors.map { it.code } shouldContainExactly
            listOf(ConfigurationErrorCode.SOURCE_FILES_TOO_MANY, ConfigurationErrorCode.TEST_FILES_TOO_MANY)
    }

    @Test
    fun `enforces source and test file byte limits`(
        @TempDir tempDir: Path,
    ) {
        val source = Files.createFile(tempDir.resolve("Source.kt"))
        val test = Files.createFile(tempDir.resolve("SourceTest.kt"))
        RandomAccessFile(source.toFile(), "rw").use {
            it.setLength(MutationConfigValidator.MAX_SOURCE_FILE_BYTES + 1)
        }
        RandomAccessFile(test.toFile(), "rw").use {
            it.setLength(MutationConfigValidator.MAX_TEST_FILE_BYTES + 1)
        }

        val errors =
            MutationConfigValidator.validateFileSets(
                listOf(source),
                listOf(test),
            )

        errors.map { it.code } shouldContainExactly
            listOf(ConfigurationErrorCode.SOURCE_FILE_TOO_LARGE, ConfigurationErrorCode.TEST_FILE_TOO_LARGE)
    }

    @Test
    fun `enforces report result and text size limits`() {
        val mutant =
            AstMutant(
                id = "mutant-1",
                mutatorName = "TestMutator",
                category = MutatorCategory.EXTREME,
                line = 1,
                column = 1,
                originalText = "a",
                replacementText = "b",
                mutatedSource = "b",
            )
        val result = MutantResult(mutant, MutantStatus.SURVIVED, 0L)
        val tooManyResults =
            MutationReport(
                totalMutants = MutationConfigValidator.MAX_REPORT_RESULTS + 1,
                killedCount = 0,
                survivedCount = MutationConfigValidator.MAX_REPORT_RESULTS + 1,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results = List(MutationConfigValidator.MAX_REPORT_RESULTS + 1) { result },
            )
        MutationConfigValidator
            .validateReport(tooManyResults)
            .map { it.code } shouldContainExactly listOf(ConfigurationErrorCode.REPORT_TOO_MANY_RESULTS)

        val tooLarge =
            MutationReport(
                totalMutants = 1,
                killedCount = 0,
                survivedCount = 1,
                timeoutCount = 0,
                compileErrorCount = 0,
                mutationScore = 0.0,
                results =
                    listOf(
                        result.copy(
                            mutant =
                                mutant.copy(
                                    mutatedSource = "x".repeat(MutationConfigValidator.MAX_REPORT_TEXT_CHARS.toInt() + 1),
                                ),
                        ),
                    ),
            )
        MutationConfigValidator
            .validateReport(tooLarge)
            .map { it.code } shouldContainExactly listOf(ConfigurationErrorCode.REPORT_TOO_LARGE)
    }

    private fun MutationConfigValidation.shouldBeInstanceOfInvalid(expectedCode: ConfigurationErrorCode) {
        this.shouldBeInstanceOf<MutationConfigValidation.Invalid>()
        errors.map { it.code } shouldContainExactly listOf(expectedCode)
    }
}
