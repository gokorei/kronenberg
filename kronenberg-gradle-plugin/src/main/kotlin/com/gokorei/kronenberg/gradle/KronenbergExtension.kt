package com.gokorei.kronenberg.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration extension for the Kronenberg mutation testing Gradle plugin.
 */
public open class KronenbergExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /**
         * Minimum mutation score threshold percentage (0.0 - 100.0).
         * Defaults to 80.0%.
         */
        public val minScore: Property<Double> =
            objects.property(Double::class.java).convention(80.0)

        /**
         * Maximum execution timeout in milliseconds for baseline test execution.
         * Defaults to 2000ms.
         */
        public val baselineTimeoutMs: Property<Long> =
            objects.property(Long::class.java).convention(2000L)

        /**
         * Whether to enable extreme / structural mutation operators.
         * Defaults to false.
         */
        public val includeExtreme: Property<Boolean> =
            objects.property(Boolean::class.java).convention(false)

        /**
         * Whether to generate and evaluate Higher-Order Mutants (HOM).
         * Defaults to false.
         */
        public val higherOrderMutants: Property<Boolean> =
            objects.property(Boolean::class.java).convention(false)

        /**
         * Maximum number of mutants to evaluate (optional cutoff limit).
         */
        public val maxMutants: Property<Int> =
            objects.property(Int::class.java)

        /**
         * Enable deterministic mutant evaluation caching.
         * Defaults to false.
         */
        public val enableCache: Property<Boolean> =
            objects.property(Boolean::class.java).convention(false)

        /**
         * Directory to write mutation test reports to (HTML, JUnit XML).
         */
        public val reportsDir: DirectoryProperty =
            objects.directoryProperty()
    }
