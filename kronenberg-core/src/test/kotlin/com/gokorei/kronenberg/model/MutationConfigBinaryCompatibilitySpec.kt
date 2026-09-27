package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Guards the published `MutationConfig` ABI and the security semantics of copying a configuration.
 *
 * The trust boundary added `SnippetExecutionTrust` to a published data class. Appending a primary
 * constructor parameter changes the generated `copy` and `copy$default` descriptors, so
 * `MutationConfig` re-declares the nine-parameter constructor and `copy`. These tests fail if that
 * compatibility shim is dropped, and if any copy path stops carrying [SnippetExecutionTrust] into
 * the copy.
 */
class MutationConfigBinaryCompatibilitySpec {
    private val nineParameterTypes: Array<out Class<*>> =
        arrayOf(
            java.lang.Double.TYPE,
            java.lang.Double.TYPE,
            java.lang.Long.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Integer::class.java,
            List::class.java,
            java.lang.Boolean.TYPE,
            List::class.java,
        )

    @Test
    fun `nine parameter copy descriptor stays available for previously compiled callers`() {
        val method = MutationConfig::class.java.getDeclaredMethod("copy", *nineParameterTypes)

        Modifier.isPublic(method.modifiers) shouldBe true
        method.returnType shouldBe MutationConfig::class.java
    }

    @Test
    fun `nine parameter copy default descriptor stays available for previously compiled callers`() {
        val method =
            MutationConfig::class.java.getDeclaredMethod(
                "copy\$default",
                MutationConfig::class.java,
                *nineParameterTypes,
                java.lang.Integer.TYPE,
                Any::class.java,
            )

        Modifier.isPublic(method.modifiers) shouldBe true
        Modifier.isStatic(method.modifiers) shouldBe true
        method.returnType shouldBe MutationConfig::class.java
    }

    @Test
    fun `nine parameter constructor stays available for previously compiled callers`() {
        val constructor = MutationConfig::class.java.getDeclaredConstructor(*nineParameterTypes)

        Modifier.isPublic(constructor.modifiers) shouldBe true
    }

    @Test
    fun `nine parameter constructor defaults to trusted local`() {
        val config =
            MutationConfig(
                minScore = 80.0,
                timeoutMultiplier = 3.0,
                baselineTimeoutMs = 1000L,
                higherOrderMutants = false,
                includeExtreme = false,
                maxMutants = null,
                targetLines = null,
                enableCache = false,
                extraClasspath = emptyList(),
            )

        config.executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
    }

    @Test
    fun `ten parameter constructor carries an explicit untrusted request`() {
        val config =
            MutationConfig(
                minScore = 80.0,
                timeoutMultiplier = 3.0,
                baselineTimeoutMs = 1000L,
                higherOrderMutants = false,
                includeExtreme = false,
                maxMutants = null,
                targetLines = null,
                enableCache = false,
                extraClasspath = emptyList(),
                executionTrust = SnippetExecutionTrust.UNTRUSTED,
            )

        config.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
    }

    @Test
    fun `no argument copy preserves untrusted execution request`() {
        val config = MutationConfig(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        val copied = config.copy()

        copied.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
        copied.minScore shouldBe config.minScore
    }

    @Test
    fun `named argument copy preserves untrusted execution request`() {
        val config = MutationConfig(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        val copied = config.copy(targetLines = listOf(4, 5), minScore = 90.0)

        copied.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
        copied.targetLines shouldBe listOf(4, 5)
        copied.minScore shouldBe 90.0
    }

    @Test
    fun `positional nine argument copy preserves untrusted execution request`() {
        val config = MutationConfig(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        val copied =
            config.copy(
                80.0,
                3.0,
                1000L,
                false,
                false,
                null,
                null,
                false,
                emptyList(),
            )

        copied.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
    }

    @Test
    fun `copy can narrow an execution trust request`() {
        val config = MutationConfig()

        val copied = config.copy(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        copied.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
        config.executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
    }

    @Test
    fun `execution trust participates in equality and hash code`() {
        val trusted = MutationConfig()
        val untrusted = MutationConfig(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        trusted shouldNotBe untrusted
        trusted.hashCode() shouldNotBe untrusted.hashCode()
    }

    @Test
    fun `toString reports the execution trust`() {
        MutationConfig().toString() shouldBe
            "MutationConfig(minScore=80.0, timeoutMultiplier=3.0, baselineTimeoutMs=1000, " +
            "higherOrderMutants=false, includeExtreme=false, maxMutants=null, targetLines=null, " +
            "enableCache=false, extraClasspath=[], executionTrust=TRUSTED_LOCAL)"
    }
}
