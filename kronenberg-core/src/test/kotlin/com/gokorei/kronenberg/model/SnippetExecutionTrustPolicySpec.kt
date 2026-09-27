package com.gokorei.kronenberg.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.stream.Stream

/**
 * Fail-closed policy tests for [SnippetExecutionTrust].
 *
 * The policy is an allow-list so that a trust level introduced by a future Kronenberg release is
 * untrusted for every version that predates it. The enum sweep test below is the regression guard:
 * it fails as soon as a new constant is added without a decision about its trust, and the name
 * tests cover values that cannot be represented as a constant at all.
 */
class SnippetExecutionTrustPolicySpec {
    @Test
    fun `only explicitly trusted local is trusted`() {
        SnippetExecutionTrustPolicy.isTrusted(SnippetExecutionTrust.TRUSTED_LOCAL) shouldBe true
    }

    @Test
    fun `untrusted is not trusted`() {
        SnippetExecutionTrustPolicy.isTrusted(SnippetExecutionTrust.UNTRUSTED) shouldBe false
    }

    @Test
    fun `absent trust is not trusted`() {
        SnippetExecutionTrustPolicy.isTrusted(null) shouldBe false
    }

    @TestFactory
    fun `every trust value is trusted only when it is explicitly trusted local`(): Stream<DynamicTest> =
        Stream.of(*SnippetExecutionTrust.entries.toTypedArray()).map { value ->
            DynamicTest.dynamicTest(value.name) {
                SnippetExecutionTrustPolicy.isTrusted(value) shouldBe (value == SnippetExecutionTrust.TRUSTED_LOCAL)
            }
        }

    @TestFactory
    fun `unrecognized names resolve to untrusted`(): Stream<DynamicTest> =
        Stream
            .of(
                null,
                "",
                " ",
                "trusted_local",
                "TRUSTED LOCAL",
                "TRUSTED",
                "SANDBOXED",
                "FUTURE_TRUST_MODE",
                "UNTRUSTED_V2",
                "0",
            ).map { name ->
                DynamicTest.dynamicTest(name?.let { "\"$it\"" } ?: "<null>") {
                    SnippetExecutionTrustPolicy.resolve(name) shouldBe SnippetExecutionTrust.UNTRUSTED
                    SnippetExecutionTrustPolicy.isTrustedName(name) shouldBe false
                }
            }

    @Test
    fun `known names resolve to their declared value`() {
        SnippetExecutionTrustPolicy.resolve("TRUSTED_LOCAL") shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
        SnippetExecutionTrustPolicy.resolve("UNTRUSTED") shouldBe SnippetExecutionTrust.UNTRUSTED
    }

    @Test
    fun `only the trusted local name passes the name gate`() {
        SnippetExecutionTrustPolicy.isTrustedName("TRUSTED_LOCAL") shouldBe true
        SnippetExecutionTrustPolicy.isTrustedName("UNTRUSTED") shouldBe false
    }

    @Test
    fun `every declared name survives the round trip through the resolver`() {
        SnippetExecutionTrust.entries.forEach { value ->
            SnippetExecutionTrustPolicy.resolve(value.name) shouldBe value
        }
    }

    @Test
    fun `an absent trust value is rejected by the policy even though the constructor defaults to trusted local`() {
        // Two different questions, deliberately kept apart. At the policy boundary a missing value
        // is untrusted, because the allow-list only authorizes the explicit trusted constant. At the
        // constructor boundary an omitted argument binds the nine-parameter constructor, whose body
        // names TRUSTED_LOCAL. Serialized configurations have no trusted default at all and fail to
        // decode when the key is absent.
        SnippetExecutionTrustPolicy.isTrusted(null) shouldBe false
        SnippetExecutionTrustPolicy.resolve(null) shouldBe SnippetExecutionTrust.UNTRUSTED
        MutationConfig().executionTrust shouldBe SnippetExecutionTrust.TRUSTED_LOCAL
    }
}
