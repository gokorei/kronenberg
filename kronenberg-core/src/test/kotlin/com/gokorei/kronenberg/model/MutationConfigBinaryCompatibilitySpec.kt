package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Guards the published `MutationConfig` ABI and the security semantics of copying a configuration.
 *
 * The trust boundary added `SnippetExecutionTrust` to a published data class, which changes the JVM
 * descriptors of the generated `copy`, `copy$default`, and default-argument constructor bridges.
 * `kotlinx.binary-compatibility-validator` filters synthetic members out of `apiCheck`, so `apiCheck`
 * alone cannot catch a caller compiled against 0.1.0 failing to link. These tests therefore look up
 * every pre-change descriptor by reflection, and they fail if the compatibility shim is dropped or
 * if any copy path stops carrying [SnippetExecutionTrust] into the copy.
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

    private val configClass: Class<MutationConfig> = MutationConfig::class.java

    @Test
    fun `no argument constructor stays available for previously compiled callers`() {
        val constructor = configClass.getDeclaredConstructor()

        Modifier.isPublic(constructor.modifiers) shouldBe true
    }

    @Test
    fun `nine parameter constructor stays available for previously compiled callers`() {
        val constructor = configClass.getDeclaredConstructor(*nineParameterTypes)

        Modifier.isPublic(constructor.modifiers) shouldBe true
    }

    @Test
    fun `nine parameter synthetic default argument constructor stays available for previously compiled callers`() {
        val constructor = legacyDefaultArgumentConstructor()

        Modifier.isPublic(constructor.modifiers) shouldBe true
        constructor.isSynthetic shouldBe true
    }

    @Test
    fun `nine parameter copy descriptor stays available for previously compiled callers`() {
        val method = configClass.getDeclaredMethod("copy", *nineParameterTypes)

        Modifier.isPublic(method.modifiers) shouldBe true
        method.returnType shouldBe MutationConfig::class.java
    }

    @Test
    fun `nine parameter copy default descriptor stays available for previously compiled callers`() {
        val method =
            configClass.getDeclaredMethod(
                "copy\$default",
                MutationConfig::class.java,
                *nineParameterTypes,
                Integer.TYPE,
                Any::class.java,
            )

        Modifier.isPublic(method.modifiers) shouldBe true
        Modifier.isStatic(method.modifiers) shouldBe true
        method.returnType shouldBe MutationConfig::class.java
    }

    @Test
    fun `every pre trust boundary accessor still resolves`() {
        // component1..9 and the matching getters are the rest of the published surface. They are
        // unaffected by the trust boundary, so this test exists to catch an accidental reorder or
        // rename of the primary constructor parameters.
        (1..9).forEach { index ->
            Modifier.isPublic(configClass.getDeclaredMethod("component$index").modifiers) shouldBe true
            val property = legacyPropertyName(index)
            Modifier.isPublic(configClass.getDeclaredMethod("get$property").modifiers) shouldBe true
        }

        Modifier.isPublic(configClass.getDeclaredMethod("equals", Any::class.java).modifiers) shouldBe true
        Modifier.isPublic(configClass.getDeclaredMethod("hashCode").modifiers) shouldBe true
        Modifier.isPublic(configClass.getDeclaredMethod("toString").modifiers) shouldBe true
        Modifier.isPublic(configClass.getDeclaredField("Companion").modifiers) shouldBe true
    }

    @Test
    fun `the legacy synthetic default argument constructor still substitutes defaults from its mask`() {
        // A caller compiled against 0.1.0 that wrote `MutationConfig(minScore = 90.0)` links against
        // this synthetic constructor and passes a bit mask instead of the omitted arguments. Driving
        // the bridge reflectively is the only way to prove the mask is still honoured, because
        // recompiling this test source would bind to a different constructor.
        val minScoreBitSet = 0b1

        val config =
            legacyDefaultArgumentConstructor().newInstance(
                55.0,
                3.0,
                1000L,
                false,
                false,
                null,
                null,
                false,
                emptyList<String>(),
                minScoreBitSet,
                null,
            )

        config.minScore shouldBe 80.0
        config.executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
    }

    @Test
    fun `omitting execution trust resolves to the trusted local default`() {
        MutationConfig().executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
        MutationConfig(minScore = 90.0).executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
    }

    @Test
    fun `the no argument constructor and the nine parameter constructor agree on every default`() {
        // `MutationConfig()` resolves through the no-argument constructor while
        // `MutationConfig(minScore = 90.0)` resolves through the all-defaulted nine-parameter one. If
        // the two default sets ever drift, the same call site would behave differently depending on
        // which argument the caller happened to pass.
        val noArgument = MutationConfig()
        val everyArgument =
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

        noArgument.copy(executionTrust = everyArgument.executionTrust) shouldBe everyArgument
        everyArgument.copy(executionTrust = noArgument.executionTrust) shouldBe noArgument
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
    fun `copy cannot widen an execution trust request`() {
        val config = MutationConfig(executionTrust = SnippetExecutionTrust.UNTRUSTED)

        listOf(
            config.copy(),
            config.copy(minScore = 90.0),
            config.copy(80.0, 3.0, 1000L, false, false, null, null, false, emptyList()),
            config.copy(80.0, 3.0, 1000L, false, false, null, null, false, emptyList(), SnippetExecutionTrust.UNTRUSTED),
        ).forEach { copied ->
            copied.executionTrust shouldBe SnippetExecutionTrust.UNTRUSTED
        }
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

    private fun legacyDefaultArgumentConstructor(): Constructor<MutationConfig> =
        configClass.getDeclaredConstructor(
            *nineParameterTypes,
            Integer.TYPE,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )

    private fun legacyPropertyName(index: Int): String =
        when (index) {
            1 -> "MinScore"
            2 -> "TimeoutMultiplier"
            3 -> "BaselineTimeoutMs"
            4 -> "HigherOrderMutants"
            5 -> "IncludeExtreme"
            6 -> "MaxMutants"
            7 -> "TargetLines"
            8 -> "EnableCache"
            else -> "ExtraClasspath"
        }
}
