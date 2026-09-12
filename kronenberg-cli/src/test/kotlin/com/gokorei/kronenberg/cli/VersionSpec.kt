package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class VersionSpec {
    @Test
    fun `resolves non-blank version from Version object`() {
        Version.CURRENT.isNotBlank() shouldBe true
        Version.NAME shouldBe "kronenberg"
    }

    @Test
    fun `cli supports --version flag`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("--version")

        result.statusCode shouldBe 0
        result.output shouldContain "kronenberg version"
    }

    @Test
    fun `cli supports -v flag`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("-v")

        result.statusCode shouldBe 0
        result.output shouldContain "kronenberg version"
    }
}
