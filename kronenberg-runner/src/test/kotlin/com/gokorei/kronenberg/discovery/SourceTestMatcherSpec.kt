package com.gokorei.kronenberg.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceTestMatcherSpec {
    private val matcher: SourceTestMatcher = DefaultSourceTestMatcher

    @Test
    fun `matches the test in the same package before a duplicate basename`(
        @TempDir projectDir: Path,
    ) {
        val sourceRoot = projectDir.resolve("src/main/kotlin")
        val testRoot = projectDir.resolve("src/test/kotlin")
        val source = createFile(sourceRoot.resolve("com/example/alpha/Service.kt"))
        val alphaTest = createFile(testRoot.resolve("com/example/alpha/ServiceTest.kt"))
        createFile(testRoot.resolve("com/example/beta/ServiceTest.kt"))

        matcher.match(source, listOf(alphaTest, testRoot.resolve("com/example/beta/ServiceTest.kt")), sourceRoot, testRoot) shouldBe
            TestMatchResult.Matched(alphaTest)
    }

    @Test
    fun `matches conventional src main and src test layouts without explicit roots`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/kotlin/com/example/Service.kt"))
        val test = createFile(projectDir.resolve("src/test/kotlin/com/example/ServiceSpec.kt"))

        matcher.match(source, listOf(test)) shouldBe TestMatchResult.Matched(test)
    }

    @Test
    fun `infers the conventional test root from a src main source set root`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/Service.kt"))
        val expectedRoot = projectDir.resolve("src/test")

        DefaultSourceTestMatcher.conventionalTestRoot(source) shouldBe expectedRoot
    }

    @Test
    fun `falls back to a unique basename when no package match exists`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/kotlin/com/example/Service.kt"))
        val test = createFile(projectDir.resolve("src/test/kotlin/com/other/ServiceTest.kt"))

        matcher.match(source, listOf(test)) shouldBe TestMatchResult.Matched(test)
    }

    @Test
    fun `never matches a source file as its own test`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/kotlin/com/example/Service.kt"))

        matcher.match(source, listOf(source)) shouldBe TestMatchResult.Missing(source)
    }

    @Test
    fun `returns an ambiguous result when multiple basename candidates exist`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/kotlin/com/example/Service.kt"))
        val firstTest = createFile(projectDir.resolve("src/test/kotlin/com/example/alpha/ServiceTest.kt"))
        val secondTest = createFile(projectDir.resolve("src/test/kotlin/com/example/beta/ServiceTest.kt"))

        matcher.match(source, listOf(firstTest, secondTest)) shouldBe
            TestMatchResult.Ambiguous(source, listOf(firstTest, secondTest))
    }

    @Test
    fun `returns a missing result when no candidate exists`(
        @TempDir projectDir: Path,
    ) {
        val source = createFile(projectDir.resolve("src/main/kotlin/com/example/Service.kt"))

        val result = matcher.match(source, emptyList())
        result.shouldBeInstanceOf<TestMatchResult.Missing>()
        (result as TestMatchResult.Missing).sourceFile shouldBe source
    }

    private fun createFile(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }
}
