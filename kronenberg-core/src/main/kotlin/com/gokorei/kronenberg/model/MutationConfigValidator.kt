package com.gokorei.kronenberg.model

import java.nio.file.Path

public sealed interface MutationConfigValidation {
    public data class Valid(
        val config: MutationConfig,
    ) : MutationConfigValidation

    public data class Invalid(
        val errors: List<ConfigurationError>,
    ) : MutationConfigValidation
}

public object MutationConfigValidator {
    public const val MIN_THRESHOLD: Double = 0.0
    public const val MAX_THRESHOLD: Double = 100.0
    public const val MIN_BASELINE_TIMEOUT_MS: Long = 1L
    public const val MAX_BASELINE_TIMEOUT_MS: Long = 300_000L
    public const val MIN_TIMEOUT_MULTIPLIER: Double = 1.0
    public const val MAX_TIMEOUT_MULTIPLIER: Double = 100.0
    public const val MAX_MUTANTS: Int = 10_000
    public const val MAX_SOURCE_CODE_CHARS: Int = 1_000_000
    public const val MAX_TEST_CODE_CHARS: Int = 1_000_000
    public const val MAX_SOURCE_FILE_BYTES: Long = 2_000_000L
    public const val MAX_TEST_FILE_BYTES: Long = 2_000_000L
    public const val MAX_SOURCE_FILES: Int = 2_000
    public const val MAX_TEST_FILES: Int = 2_000
    public const val MAX_CLASSPATH_ENTRIES: Int = 256
    public const val MAX_CLASSPATH_ENTRY_CHARS: Int = 4_096
    public const val MAX_REPORT_RESULTS: Int = 10_000
    public const val MAX_REPORT_TEXT_CHARS: Long = 10_000_000L

    public fun validate(config: MutationConfig): MutationConfigValidation {
        val errors = MutationConfigValidationSupport.validateNumericConfig(config)
        val canonicalClasspath = MutationConfigValidationSupport.validateClasspath(config.extraClasspath, errors)
        return if (errors.isEmpty()) {
            MutationConfigValidation.Valid(config.copy(extraClasspath = canonicalClasspath))
        } else {
            MutationConfigValidation.Invalid(errors)
        }
    }

    public fun validateExecution(
        config: MutationConfig,
        sourceCode: String,
        testCode: String,
    ): MutationConfigValidation = validateExecutionError(validate(config), sourceCode, testCode)

    public fun validateFileSets(
        sourceFiles: List<Path>,
        testFiles: List<Path>,
    ): List<ConfigurationError> {
        val errors = mutableListOf<ConfigurationError>()
        if (sourceFiles.size > MAX_SOURCE_FILES) {
            errors.add(
                ConfigurationError(
                    field = "sourceFiles",
                    code = ConfigurationErrorCode.SOURCE_FILES_TOO_MANY,
                    message = "At most $MAX_SOURCE_FILES source files may be audited",
                    actual = sourceFiles.size.toString(),
                    limit = MAX_SOURCE_FILES.toLong(),
                ),
            )
        }
        if (testFiles.size > MAX_TEST_FILES) {
            errors.add(
                ConfigurationError(
                    field = "testFiles",
                    code = ConfigurationErrorCode.TEST_FILES_TOO_MANY,
                    message = "At most $MAX_TEST_FILES test files may be audited",
                    actual = testFiles.size.toString(),
                    limit = MAX_TEST_FILES.toLong(),
                ),
            )
        }
        errors.addAll(MutationConfigValidationSupport.validateFileSizes(sourceFiles, true))
        errors.addAll(MutationConfigValidationSupport.validateFileSizes(testFiles, false))
        return errors
    }

    public fun validateReport(report: MutationReport): List<ConfigurationError> {
        if (report.results.size > MAX_REPORT_RESULTS) {
            return listOf(
                ConfigurationError(
                    field = "report.results",
                    code = ConfigurationErrorCode.REPORT_TOO_MANY_RESULTS,
                    message = "A report must contain at most $MAX_REPORT_RESULTS results",
                    actual = report.results.size.toString(),
                    limit = MAX_REPORT_RESULTS.toLong(),
                ),
            )
        }

        return MutationReportValidationSupport.validateReportText(report)
    }
}

private fun validateExecutionError(
    configValidation: MutationConfigValidation,
    sourceCode: String,
    testCode: String,
): MutationConfigValidation {
    if (configValidation is MutationConfigValidation.Invalid) {
        return configValidation
    }

    val errors = mutableListOf<ConfigurationError>()
    if (sourceCode.length > MutationConfigValidator.MAX_SOURCE_CODE_CHARS) {
        errors.add(
            ConfigurationError(
                field = "sourceCode",
                code = ConfigurationErrorCode.SOURCE_TOO_LARGE,
                message = "sourceCode must contain at most ${MutationConfigValidator.MAX_SOURCE_CODE_CHARS} characters",
                actual = sourceCode.length.toString(),
                limit = MutationConfigValidator.MAX_SOURCE_CODE_CHARS.toLong(),
            ),
        )
    }
    if (testCode.length > MutationConfigValidator.MAX_TEST_CODE_CHARS) {
        errors.add(
            ConfigurationError(
                field = "testCode",
                code = ConfigurationErrorCode.TEST_TOO_LARGE,
                message = "testCode must contain at most ${MutationConfigValidator.MAX_TEST_CODE_CHARS} characters",
                actual = testCode.length.toString(),
                limit = MutationConfigValidator.MAX_TEST_CODE_CHARS.toLong(),
            ),
        )
    }

    return if (errors.isEmpty()) {
        configValidation
    } else {
        MutationConfigValidation.Invalid(errors)
    }
}
