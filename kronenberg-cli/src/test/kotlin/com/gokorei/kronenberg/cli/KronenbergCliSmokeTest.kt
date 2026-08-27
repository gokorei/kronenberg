package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class KronenbergCliSmokeTest {
    @Test
    fun `cli help message displays usage information`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("--help")

        result.statusCode shouldBe 0
        result.output shouldContain "Usage: kronenberg"
        result.output shouldContain "audit"
    }

    @Test
    fun `audit command help message displays required options`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("audit --help")

        result.statusCode shouldBe 0
        result.output shouldContain "--source"
        result.output shouldContain "--test"
        result.output shouldContain "--threshold"
    }
}
