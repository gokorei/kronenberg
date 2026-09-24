package com.gokorei.kronenberg.model

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object MutationConfigValidationSupport {
    fun validateNumericConfig(config: MutationConfig): MutableList<ConfigurationError> {
        val errors = mutableListOf<ConfigurationError>()
        validateThreshold(config.minScore, errors)
        validateTimeout(config.baselineTimeoutMs, errors)
        validateTimeoutMultiplier(config.timeoutMultiplier, errors)
        validateMaxMutants(config.maxMutants, errors)
        return errors
    }

    fun validateClasspath(
        entries: List<String>,
        errors: MutableList<ConfigurationError>,
    ): List<String> {
        if (entries.size > MutationConfigValidator.MAX_CLASSPATH_ENTRIES) {
            errors.add(
                ConfigurationError(
                    field = "extraClasspath",
                    code = ConfigurationErrorCode.CLASSPATH_TOO_MANY,
                    message = "extraClasspath must contain at most ${MutationConfigValidator.MAX_CLASSPATH_ENTRIES} entries",
                    actual = entries.size.toString(),
                    limit = MutationConfigValidator.MAX_CLASSPATH_ENTRIES.toLong(),
                ),
            )
            return emptyList()
        }
        entries.forEachIndexed { index, entry ->
            if (entry.length > MutationConfigValidator.MAX_CLASSPATH_ENTRY_CHARS) {
                errors.add(
                    ConfigurationError(
                        field = "extraClasspath[$index]",
                        code = ConfigurationErrorCode.CLASSPATH_ENTRY_TOO_LARGE,
                        message = "Classpath entry must contain at most ${MutationConfigValidator.MAX_CLASSPATH_ENTRY_CHARS} characters",
                        actual = entry.length.toString(),
                        limit = MutationConfigValidator.MAX_CLASSPATH_ENTRY_CHARS.toLong(),
                    ),
                )
            }
        }
        return canonicalizeClasspath(entries, errors)
    }

    fun validateFileSizes(
        files: List<Path>,
        source: Boolean,
    ): List<ConfigurationError> {
        val field = if (source) "sourceFiles" else "testFiles"
        val maxBytes =
            if (source) MutationConfigValidator.MAX_SOURCE_FILE_BYTES else MutationConfigValidator.MAX_TEST_FILE_BYTES
        val tooLargeCode =
            if (source) ConfigurationErrorCode.SOURCE_FILE_TOO_LARGE else ConfigurationErrorCode.TEST_FILE_TOO_LARGE
        val invalidCode =
            if (source) ConfigurationErrorCode.SOURCE_FILE_INVALID else ConfigurationErrorCode.TEST_FILE_INVALID
        val errors = mutableListOf<ConfigurationError>()
        files.distinct().forEach { path ->
            val size =
                try {
                    Files.size(path)
                } catch (_: Exception) {
                    errors.add(
                        ConfigurationError(
                            field = "$field[$path]",
                            code = invalidCode,
                            message = "Input file size cannot be read",
                        ),
                    )
                    return@forEach
                }
            if (size > maxBytes) {
                errors.add(
                    ConfigurationError(
                        field = "$field[$path]",
                        code = tooLargeCode,
                        message = "Input file must be at most $maxBytes bytes",
                        actual = size.toString(),
                        limit = maxBytes,
                    ),
                )
            }
        }
        return errors
    }

    private fun validateThreshold(
        minScore: Double,
        errors: MutableList<ConfigurationError>,
    ) {
        if (!minScore.isFinite()) {
            errors.add(
                ConfigurationError(
                    field = "minScore",
                    code = ConfigurationErrorCode.THRESHOLD_NOT_FINITE,
                    message = "minScore must be finite",
                    actual = minScore.toString(),
                ),
            )
        } else if (minScore !in MutationConfigValidator.MIN_THRESHOLD..MutationConfigValidator.MAX_THRESHOLD) {
            errors.add(
                ConfigurationError(
                    field = "minScore",
                    code = ConfigurationErrorCode.THRESHOLD_OUT_OF_RANGE,
                    message =
                        "minScore must be between ${MutationConfigValidator.MIN_THRESHOLD} and " +
                            MutationConfigValidator.MAX_THRESHOLD,
                    actual = minScore.toString(),
                    limit = MutationConfigValidator.MAX_THRESHOLD.toLong(),
                ),
            )
        }
    }

    private fun validateTimeout(
        timeoutMs: Long,
        errors: MutableList<ConfigurationError>,
    ) {
        if (timeoutMs !in MutationConfigValidator.MIN_BASELINE_TIMEOUT_MS..MutationConfigValidator.MAX_BASELINE_TIMEOUT_MS) {
            errors.add(
                ConfigurationError(
                    field = "baselineTimeoutMs",
                    code = ConfigurationErrorCode.TIMEOUT_OUT_OF_RANGE,
                    message =
                        "baselineTimeoutMs must be between ${MutationConfigValidator.MIN_BASELINE_TIMEOUT_MS} and " +
                            MutationConfigValidator.MAX_BASELINE_TIMEOUT_MS,
                    actual = timeoutMs.toString(),
                    limit = MutationConfigValidator.MAX_BASELINE_TIMEOUT_MS,
                ),
            )
        }
    }

    private fun validateTimeoutMultiplier(
        multiplier: Double,
        errors: MutableList<ConfigurationError>,
    ) {
        if (!multiplier.isFinite()) {
            errors.add(
                ConfigurationError(
                    field = "timeoutMultiplier",
                    code = ConfigurationErrorCode.TIMEOUT_MULTIPLIER_NOT_FINITE,
                    message = "timeoutMultiplier must be finite",
                    actual = multiplier.toString(),
                ),
            )
        } else if (multiplier !in MutationConfigValidator.MIN_TIMEOUT_MULTIPLIER..MutationConfigValidator.MAX_TIMEOUT_MULTIPLIER) {
            errors.add(
                ConfigurationError(
                    field = "timeoutMultiplier",
                    code = ConfigurationErrorCode.TIMEOUT_MULTIPLIER_OUT_OF_RANGE,
                    message =
                        "timeoutMultiplier must be between ${MutationConfigValidator.MIN_TIMEOUT_MULTIPLIER} and " +
                            MutationConfigValidator.MAX_TIMEOUT_MULTIPLIER,
                    actual = multiplier.toString(),
                    limit = MutationConfigValidator.MAX_TIMEOUT_MULTIPLIER.toLong(),
                ),
            )
        }
    }

    private fun validateMaxMutants(
        maxMutants: Int?,
        errors: MutableList<ConfigurationError>,
    ) {
        if (maxMutants != null && maxMutants !in 1..MutationConfigValidator.MAX_MUTANTS) {
            errors.add(
                ConfigurationError(
                    field = "maxMutants",
                    code = ConfigurationErrorCode.MAX_MUTANTS_OUT_OF_RANGE,
                    message = "maxMutants must be between 1 and ${MutationConfigValidator.MAX_MUTANTS}",
                    actual = maxMutants.toString(),
                    limit = MutationConfigValidator.MAX_MUTANTS.toLong(),
                ),
            )
        }
    }

    private fun canonicalizeClasspath(
        entries: List<String>,
        errors: MutableList<ConfigurationError>,
    ): List<String> {
        if (entries.size > MutationConfigValidator.MAX_CLASSPATH_ENTRIES) {
            return emptyList()
        }

        val canonical = linkedSetOf<String>()
        entries.forEachIndexed { index, entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty() || entry.length > MutationConfigValidator.MAX_CLASSPATH_ENTRY_CHARS) {
                return@forEachIndexed
            }
            val path =
                try {
                    Path.of(trimmed)
                } catch (_: InvalidPathException) {
                    errors.addInvalidClasspath(index, entry, "Classpath entry is not a valid path")
                    return@forEachIndexed
                }
            val canonicalPath =
                try {
                    if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
                } catch (_: Exception) {
                    errors.addInvalidClasspath(index, entry, "Classpath entry could not be canonicalized")
                    return@forEachIndexed
                }
            canonical.add(canonicalPath.toString())
        }
        return canonical.toList()
    }

    private fun MutableList<ConfigurationError>.addInvalidClasspath(
        index: Int,
        entry: String,
        reason: String,
    ) {
        add(
            ConfigurationError(
                field = "extraClasspath[$index]",
                code = ConfigurationErrorCode.CLASSPATH_ENTRY_INVALID,
                message = reason,
                actual = entry,
            ),
        )
    }
}

internal object MutationReportValidationSupport {
    fun validateReportText(report: MutationReport): List<ConfigurationError> {
        var textChars = 0L
        for (result in report.results) {
            textChars += reportResultTextChars(result)
            if (textChars > MutationConfigValidator.MAX_REPORT_TEXT_CHARS) {
                return listOf(
                    ConfigurationError(
                        field = "report.results",
                        code = ConfigurationErrorCode.REPORT_TOO_LARGE,
                        message = "Report result text must contain at most ${MutationConfigValidator.MAX_REPORT_TEXT_CHARS} characters",
                        actual = textChars.toString(),
                        limit = MutationConfigValidator.MAX_REPORT_TEXT_CHARS,
                    ),
                )
            }
        }
        return emptyList()
    }

    private fun reportResultTextChars(result: MutantResult): Long {
        val mutant = result.mutant
        return mutant.id.length.toLong() +
            mutant.mutatorName.length.toLong() +
            mutant.originalText.length.toLong() +
            mutant.replacementText.length.toLong() +
            mutant.mutatedSource.length.toLong() +
            (mutant.filePath?.length ?: 0).toLong() +
            (result.failureMessage?.length ?: 0).toLong()
    }
}
