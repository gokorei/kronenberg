package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KronenbergCliSpec {
    @Test
    fun `audit command runs successfully with valid source and test files`() {
        val srcFile = createTempFile("Sample", ".kt")
        val testFile = createTempFile("SampleTest", ".kt")
        try {
            srcFile.writeText("fun add(a: Int, b: Int) = a + b")
            testFile.writeText("fun main() { check(add(1, 2) == 3) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --threshold 50.0")

            result.statusCode shouldBe 0
            result.output shouldContain "KRONENBERG MUTATION AUDIT"
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
        }
    }

    @Test
    fun `audit command outputs json when --json flag is passed`() {
        val srcFile = createTempFile("SampleJson", ".kt")
        val testFile = createTempFile("SampleJsonTest", ".kt")
        try {
            srcFile.writeText("fun subtract(a: Int, b: Int) = a - b")
            testFile.writeText("fun main() { check(subtract(3, 1) == 2) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --json")

            result.statusCode shouldBe 0
            result.output shouldContain "\"totalMutants\""
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
        }
    }

    @Test
    fun `audit command exports junit xml report when --junit-xml flag is provided`() {
        val srcFile = createTempFile("SampleXml", ".kt")
        val testFile = createTempFile("SampleXmlTest", ".kt")
        val xmlFile = createTempFile("junit-report", ".xml")
        try {
            srcFile.writeText("fun multiply(a: Int, b: Int) = a * b")
            testFile.writeText("fun main() { check(multiply(3, 2) == 6) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --junit-xml $xmlFile --threshold 50.0")

            result.statusCode shouldBe 0
            val xmlContent = xmlFile.readText()
            xmlContent shouldContain "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            xmlContent shouldContain "<testsuite name=\"Kronenberg Mutation Audit\""
            xmlContent shouldContain "<testcase"
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
            xmlFile.toFile().delete()
        }
    }

    @Test
    fun `audit command exports standalone html report when --html-report is provided`() {
        val srcFile = createTempFile("SampleHtml", ".kt")
        val testFile = createTempFile("SampleHtmlTest", ".kt")
        val htmlFile = createTempFile("html-report", ".html")
        try {
            srcFile.writeText("fun divide(a: Int, b: Int) = a / b")
            testFile.writeText("fun main() { check(divide(6, 2) == 3) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --html-report $htmlFile --threshold 50.0")

            result.statusCode shouldBe 0
            val htmlContent = htmlFile.readText()
            htmlContent shouldContain "<!DOCTYPE html>"
            htmlContent shouldContain "Kronenberg Mutation Audit"
            htmlContent shouldContain "Total Mutants"
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
            htmlFile.toFile().delete()
        }
    }

    @Test
    fun `audit command exports sarif report when --sarif is provided`() {
        val srcFile = createTempFile("SampleSarif", ".kt")
        val testFile = createTempFile("SampleSarifTest", ".kt")
        val sarifFile = createTempFile("sarif-report", ".sarif")
        try {
            srcFile.writeText("fun modulo(a: Int, b: Int) = a % b")
            testFile.writeText("fun main() { check(modulo(5, 2) == 1) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --sarif $sarifFile --threshold 50.0")

            result.statusCode shouldBe 0
            val sarifContent = sarifFile.readText()
            sarifContent shouldContain "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
            sarifContent shouldContain "\"name\": \"Kronenberg\""
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
            sarifFile.toFile().delete()
        }
    }

    @Test
    fun `git diff parser correctly extracts line numbers from unified diff hunks`() {
        val diffOutput =
            """
            @@ -1,4 +1,5 @@
            +fun newFeature() {
            @@ -10,3 +11,6 @@
            """.trimIndent()
        val lines = GitDiffParser.parseHunkLines(diffOutput)
        lines shouldBe listOf(1, 2, 3, 4, 5, 11, 12, 13, 14, 15, 16)
    }

    @Test
    fun `audit command performs batch auditing across source-dir and test-dir`() {
        val srcDir = createTempDirectory("batch-src")
        val testDir = createTempDirectory("batch-test")
        try {
            val src1 = srcDir.resolve("MathUtils.kt")
            val test1 = testDir.resolve("MathUtilsTest.kt")
            src1.writeText("fun square(x: Int): Int = x * x")
            test1.writeText("fun testSquare() { check(square(4) == 16) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source-dir $srcDir --test-dir $testDir --threshold 50.0")

            result.statusCode shouldBe 0
            result.output shouldContain "KRONENBERG MUTATION AUDIT"
            result.output shouldContain "Total Mutants:"
        } finally {
            srcDir.toFile().deleteRecursively()
            testDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `audit command fails when source file does not exist`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("audit --source /non/existent/file.kt --test /non/existent/test.kt")

        (result.statusCode != 0) shouldBe true
    }
}
