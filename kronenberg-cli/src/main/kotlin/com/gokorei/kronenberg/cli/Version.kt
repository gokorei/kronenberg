package com.gokorei.kronenberg.cli

/**
 * Single source of truth for runtime version and CLI identity resolution.
 */
public object Version {
    public const val NAME: String = "kronenberg"
    public const val FALLBACK_VERSION: String = "0.1.0-SNAPSHOT"

    /**
     * Dynamically resolved CLI version.
     *
     * Resolution order:
     * 1. JAR Manifest (`Package.implementationVersion`)
     * 2. Build-generated classpath resource (`kronenberg-version.txt`)
     * 3. Non-release fallback constant ("0.1.0-SNAPSHOT")
     */
    public val CURRENT: String by lazy {
        Version::class.java.`package`
            ?.implementationVersion
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Version::class.java.classLoader
                ?.getResourceAsStream("kronenberg-version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_VERSION
    }
}
