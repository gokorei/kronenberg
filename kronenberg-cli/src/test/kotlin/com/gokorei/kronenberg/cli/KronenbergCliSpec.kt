package com.gokorei.kronenberg.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
    fun `audit command preserves relative file paths in batch directory audit`() {
        val srcDir = createTempDirectory("batch-src-paths")
        val testDir = createTempDirectory("batch-test-paths")
        val htmlFile = createTempFile("report", ".html")
        val sarifFile = createTempFile("report", ".sarif")
        try {
            val subPkg = srcDir.resolve("pkg")
            java.nio.file.Files
                .createDirectories(subPkg)
            val src1 = subPkg.resolve("MathUtils.kt")
            val test1 = testDir.resolve("MathUtilsTest.kt")
            src1.writeText("fun square(x: Int): Int = x * x")
            test1.writeText("fun testSquare() { /* unasserted, mutant survives */ }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result =
                cli.test(
                    "audit --source-dir $srcDir --test-dir $testDir --json --html-report $htmlFile --sarif $sarifFile --threshold 0.0",
                )

            result.statusCode shouldBe 1
            val report =
                kotlinx.serialization.json.Json
                    .decodeFromString<com.gokorei.kronenberg.model.MutationReport>(result.output)
            report.totalMutants shouldBeGreaterThan 0
            report.results.all { it.mutant.filePath == "pkg/MathUtils.kt" } shouldBe true

            htmlFile.readText() shouldContain "pkg/MathUtils.kt"
            sarifFile.readText() shouldContain "pkg/MathUtils.kt"
        } finally {
            srcDir.toFile().deleteRecursively()
            testDir.toFile().deleteRecursively()
            htmlFile.toFile().delete()
            sarifFile.toFile().delete()
        }
    }

    @Test
    fun `audit command fails when source file does not exist`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        val result = cli.test("audit --source /non/existent/file.kt --test /non/existent/test.kt")

        (result.statusCode != 0) shouldBe true
    }

    @Test
    fun `audit command accepts --classpath and compiles against external dependencies`() {
        val compiler =
            com.gokorei.kronenberg.runner
                .DefaultSnippetCompiler()
        val helperSource =
            """
            package com.example.service
            class Greeter {
                fun greet(name: String): String = "Hello, " + name
            }
            """.trimIndent()
        val compiledHelper = compiler.compile(helperSource)
        val srcFile = createTempFile("ServiceCaller", ".kt")
        val testFile = createTempFile("ServiceCallerTest", ".kt")

        try {
            val compiled = compiledHelper.shouldBeInstanceOf<com.gokorei.kronenberg.runner.CompileResult.Compiled>()
            val helperCp = compiled.outDir.toString()

            srcFile.writeText(
                """
                import com.example.service.Greeter

                fun createGreeting(name: String): String {
                    return Greeter().greet(name) + "!"
                }
                """.trimIndent(),
            )
            testFile.writeText(
                """
                fun testGreeting() {
                    check(createGreeting("World") == "Hello, World!")
                }
                """.trimIndent(),
            )

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile -cp $helperCp --threshold 50.0")

            result.statusCode shouldBe 0
            result.output shouldContain "KRONENBERG MUTATION AUDIT"
        } finally {
            compiler.cleanup(compiledHelper)
            srcFile.toFile().delete()
            testFile.toFile().delete()
        }
    }

    @Test
    fun `git diff parser extracts staged kotlin files from git output`() {
        val rawDiffNames = "src/main/Foo.kt\nsrc/test/BarSpec.kt\nREADME.md\nbuild.gradle.kts\n"
        val staged = GitDiffParser.parseStagedKotlinFileNames(rawDiffNames)
        staged shouldBe listOf("src/main/Foo.kt", "src/test/BarSpec.kt")
    }

    @Test
    fun `audit command with --pre-commit exits 0 when no staged kotlin files exist`() {
        val cli = KronenbergCli().subcommands(AuditCommand())
        // In a temp dir or without staged files, pre-commit should report clean or evaluate staged
        val result = cli.test("audit --pre-commit")
        result.statusCode shouldBe 0
        result.output shouldContain "pre-commit"
    }

    @Test
    fun `audit command with --pre-commit runs fast audit on provided source and test`() {
        val srcFile = createTempFile("PreCommitSample", ".kt")
        val testFile = createTempFile("PreCommitSampleTest", ".kt")
        try {
            srcFile.writeText("fun add(a: Int, b: Int) = a + b")
            testFile.writeText("fun main() { check(add(1, 2) == 3) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --pre-commit --source $srcFile --test $testFile --threshold 50.0")

            result.statusCode shouldBe 1
            result.output shouldContain "KRONENBERG MUTATION AUDIT"
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
        }
    }

    @Test
    fun `audit command exports code climate json when --codeclimate flag is provided`() {
        val srcFile = createTempFile("CodeClimateSample", ".kt")
        val testFile = createTempFile("CodeClimateSampleTest", ".kt")
        val codeClimateFile = createTempFile("codeclimate-report", ".json")
        try {
            srcFile.writeText(
                """
                fun compute(x: Int): Int {
                    if (x > 10) return x * 2
                    return x
                }
                """.trimIndent(),
            )
            // Test that lets mutants survive to produce Code Climate issues
            testFile.writeText("fun main() { check(compute(5) == 5) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --codeclimate $codeClimateFile --threshold 0.0")

            result.statusCode shouldBe 1
            val content = codeClimateFile.readText()
            content shouldContain "\"type\": \"issue\""
            content shouldContain "\"check_name\": \"KronenbergMutationCheck\""
            content shouldContain "\"categories\":"
            content shouldContain "\"Bug Risk\""
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
            codeClimateFile.toFile().delete()
        }
    }

    @Test
    fun `audit command proposes test skeleton for surviving mutants when --propose-tests flag is provided`() {
        val srcFile = createTempFile("ProposeSample", ".kt")
        val testFile = createTempFile("ProposeSampleTest", ".kt")
        try {
            srcFile.writeText(
                """
                fun evaluate(x: Int): Int {
                    if (x > 10) return x * 2
                    return x
                }
                """.trimIndent(),
            )
            // Test that lets mutants survive
            testFile.writeText("fun main() { check(evaluate(5) == 5) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --propose-tests --threshold 0.0")

            result.statusCode shouldBe 1
            result.output shouldContain "PROPOSED TEST SKELETONS TO KILL SURVIVED MUTANTS"
            result.output shouldContain "evaluate"
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
        }
    }

    @Test
    fun `diff scoped directory audit does not fail on unchanged source files`() {
        val repo = createTempDirectory("diff-audit-repo")
        try {
            val srcDir = repo.resolve("src")
            val testDir = repo.resolve("test")
            Files.createDirectories(srcDir)
            Files.createDirectories(testDir)

            val changed = srcDir.resolve("Changed.kt")
            val unchanged = srcDir.resolve("Unchanged.kt")
            changed.writeText("fun addOne(x: Int): Int = x + 1")
            unchanged.writeText("fun twice(x: Int): Int = x * 2")
            testDir.resolve("ChangedTest.kt").writeText("fun testAddOne() { check(addOne(1) == 2) }")
            testDir.resolve("UnchangedTest.kt").writeText("fun testTwice() { check(twice(2) == 4) }")

            git(repo, "init", "-q")
            git(repo, "add", ".")
            git(repo, "-c", "user.email=t@t.io", "-c", "user.name=t", "commit", "-q", "-m", "base")

            // Only Changed.kt is modified relative to HEAD, so Unchanged.kt is out of scope.
            changed.writeText("fun addOne(x: Int): Int = x + 2")
            testDir.resolve("ChangedTest.kt").writeText("fun testAddOne() { check(addOne(1) == 3) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source-dir $srcDir --test-dir $testDir --diff HEAD --threshold 50.0")

            result.statusCode shouldBe 0
            result.output shouldContain "unchanged 1"
            result.output shouldContain "Source Coverage:"
            result.output shouldNotContain "were not audited"
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diff scoped directory audit reports unchanged files as skipped in junit xml`() {
        val repo = createTempDirectory("diff-audit-xml-repo")
        try {
            val srcDir = repo.resolve("src")
            val testDir = repo.resolve("test")
            Files.createDirectories(srcDir)
            Files.createDirectories(testDir)

            val changed = srcDir.resolve("Changed.kt")
            srcDir.resolve("Unchanged.kt").writeText("fun twice(x: Int): Int = x * 2")
            changed.writeText("fun addOne(x: Int): Int = x + 1")
            testDir.resolve("ChangedTest.kt").writeText("fun testAddOne() { check(addOne(1) == 2) }")
            testDir.resolve("UnchangedTest.kt").writeText("fun testTwice() { check(twice(2) == 4) }")

            git(repo, "init", "-q")
            git(repo, "add", ".")
            git(repo, "-c", "user.email=t@t.io", "-c", "user.name=t", "commit", "-q", "-m", "base")

            changed.writeText("fun addOne(x: Int): Int = x + 2")
            testDir.resolve("ChangedTest.kt").writeText("fun testAddOne() { check(addOne(1) == 3) }")

            val xmlFile = createTempFile("diff-audit-report", ".xml")
            val cli = KronenbergCli().subcommands(AuditCommand())
            val result =
                cli.test(
                    "audit --source-dir $srcDir --test-dir $testDir --diff HEAD --threshold 50.0 --junit-xml $xmlFile",
                )

            result.statusCode shouldBe 0
            val xml = xmlFile.readText()
            xml shouldContain "skipped=\"1\""
            xml shouldContain "name=\"skipped_unchanged\""
            xml shouldContain "1 source file(s) had no changed lines in the requested diff window"
            xml shouldNotContain "type=\"BaselineError\""
            xmlFile.toFile().delete()
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing test suites still fail a directory audit and surface in junit xml`() {
        val srcDir = createTempDirectory("missing-test-src")
        val testDir = createTempDirectory("missing-test-dir")
        val xmlFile = createTempFile("missing-test-report", ".xml")
        try {
            val covered = srcDir.resolve("Covered.kt")
            covered.writeText("fun addOne(x: Int): Int = x + 1")
            srcDir.resolve("Orphan.kt").writeText("fun orphan(x: Int): Int = x + 1")
            testDir.resolve("CoveredTest.kt").writeText("fun testAddOne() { check(addOne(1) == 2) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result =
                cli.test(
                    "audit --source-dir $srcDir --test-dir $testDir --threshold 50.0 --junit-xml $xmlFile",
                )

            result.statusCode shouldBe 1
            result.output shouldContain "no matching test was found"
            val xml = xmlFile.readText()
            xml shouldContain "type=\"BaselineError\""
            xml shouldContain "1 source file(s) were not audited because no matching test was found"
            xml shouldContain "name=\"skipped_missing_test\""
        } finally {
            srcDir.toFile().deleteRecursively()
            testDir.toFile().deleteRecursively()
            xmlFile.toFile().delete()
        }
    }

    @Test
    fun `failed baseline pre-flight is visible in junit xml as an error`() {
        val srcFile = createTempFile("BaselineFailure", ".kt")
        val testFile = createTempFile("BaselineFailureTest", ".kt")
        val xmlFile = createTempFile("baseline-failure-report", ".xml")
        try {
            srcFile.writeText("fun addOne(x: Int): Int = x + 1")
            // The baseline itself fails, so no mutant is ever evaluated.
            testFile.writeText("fun testAddOne() { check(addOne(1) == 99) }")

            val cli = KronenbergCli().subcommands(AuditCommand())
            val result = cli.test("audit --source $srcFile --test $testFile --junit-xml $xmlFile --threshold 0.0")

            result.statusCode shouldBe 1
            result.output shouldContain "BASELINE PRE-FLIGHT ERROR"
            val xml = xmlFile.readText()
            xml shouldContain "type=\"BaselineError\""
            xml shouldContain "errors=\"1\""
            xml shouldContain "name=\"audit_baseline\""
        } finally {
            srcFile.toFile().delete()
            testFile.toFile().delete()
            xmlFile.toFile().delete()
        }
    }

    private fun git(
        workingDir: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        withClue("git ${args.joinToString(" ")} failed: $output") {
            process.exitValue() shouldBe 0
        }
    }
}
