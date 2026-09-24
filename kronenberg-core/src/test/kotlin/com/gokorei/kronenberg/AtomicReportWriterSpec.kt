package com.gokorei.kronenberg

import com.gokorei.kronenberg.io.AtomicReportWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AtomicReportWriterSpec {
    @Test
    fun `replaces a report atomically and removes its temporary file`(
        @TempDir directory: Path,
    ) {
        val report = directory.resolve("mutation-report.json")
        report.toFile().writeText("old")

        AtomicReportWriter.write(report, "new")

        report.readText() shouldBe "new"
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.count().toInt() shouldBe 0
        }
    }

    @Test
    fun `rejects a symlink report without changing its target`(
        @TempDir directory: Path,
    ) {
        val target = directory.resolve("target.txt")
        target.toFile().writeText("original")
        val report = directory.resolve("report.txt")
        Files.createSymbolicLink(report, target)

        shouldThrow<IOException> {
            AtomicReportWriter.write(report, "replacement")
        }

        target.readText() shouldBe "original"
    }

    @Test
    fun `rejects a report below a symlink directory`(
        @TempDir directory: Path,
    ) {
        val actual = directory.resolve("actual")
        Files.createDirectory(actual)
        val linkedDirectory = directory.resolve("linked")
        Files.createSymbolicLink(linkedDirectory, actual)

        shouldThrow<IOException> {
            AtomicReportWriter.write(linkedDirectory.resolve("report.txt"), "content")
        }

        actual.resolve("report.txt").exists() shouldBe false
    }
}
