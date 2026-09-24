package com.gokorei.kronenberg.discovery

import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

public interface SourceTestMatcher {
    public fun match(
        sourceFile: Path,
        testFiles: Iterable<Path>,
        sourceRoot: Path? = null,
        testRoot: Path? = null,
    ): TestMatchResult
}

public sealed interface TestMatchResult {
    public data class Matched(
        val testFile: Path,
    ) : TestMatchResult

    public data class Missing(
        val sourceFile: Path,
    ) : TestMatchResult

    public data class Ambiguous(
        val sourceFile: Path,
        val candidates: List<Path>,
    ) : TestMatchResult
}

public object DefaultSourceTestMatcher : SourceTestMatcher {
    override fun match(
        sourceFile: Path,
        testFiles: Iterable<Path>,
        sourceRoot: Path?,
        testRoot: Path?,
    ): TestMatchResult {
        val normalizedSource = sourceFile.toAbsolutePath().normalize()
        val candidates =
            testFiles
                .filter { it.toString().endsWith(".kt") }
                .filter { it.toAbsolutePath().normalize() != normalizedSource }
                .distinctBy { it.toAbsolutePath().normalize() }
        val sourceName = sourceFile.nameWithoutExtension
        val allowedNames = setOf(sourceName, "${sourceName}Test", "${sourceName}Spec")
        val sourcePackage = packagePath(sourceFile, sourceRoot, "main")
        val packageCandidates =
            candidates.filter {
                allowedNames.contains(it.nameWithoutExtension) &&
                    sourcePackage != null &&
                    packagePath(it, testRoot, "test") == sourcePackage
            }
        val matchingCandidates =
            if (packageCandidates.isNotEmpty()) {
                packageCandidates
            } else {
                candidates.filter { allowedNames.contains(it.nameWithoutExtension) }
            }
        val sortedCandidates = matchingCandidates.sortedBy { it.toAbsolutePath().normalize().toString() }
        return when (sortedCandidates.size) {
            0 -> TestMatchResult.Missing(sourceFile)
            1 -> TestMatchResult.Matched(sortedCandidates.single())
            else -> TestMatchResult.Ambiguous(sourceFile, sortedCandidates)
        }
    }

    public fun conventionalTestRoot(sourceFile: Path): Path? =
        inferredRoot(sourceFile, "main")?.let { sourceRoot ->
            val sourceSet = sourceRoot.fileName?.toString()
            when (sourceSet) {
                "main" -> {
                    sourceRoot.parent?.resolve("test")?.normalize()
                }

                null -> {
                    null
                }

                else -> {
                    sourceRoot.parent
                        ?.parent
                        ?.resolve("test")
                        ?.resolve(sourceSet)
                        ?.normalize()
                }
            }
        }

    private fun packagePath(
        file: Path,
        root: Path?,
        sourceSet: String,
    ): Path? {
        val normalizedFile = file.toAbsolutePath().normalize()
        val normalizedRoot = (root ?: inferredRoot(file, sourceSet))?.toAbsolutePath()?.normalize()
        return if (normalizedRoot != null && normalizedFile.startsWith(normalizedRoot)) {
            normalizedRoot.relativize(normalizedFile).parent
        } else {
            normalizedFile.parent
        }
    }

    private fun inferredRoot(
        file: Path,
        sourceSet: String,
    ): Path? {
        val normalizedFile = file.toAbsolutePath().normalize()
        val segments: List<Path> = generateSequence<Path>(normalizedFile) { path -> path.parent }.toList().asReversed()
        val sourceIndex = segments.indexOfFirst { segment -> segment.fileName?.toString() == "src" }
        val sourcePath = segments.getOrNull(sourceIndex)
        val sourceSetPath = segments.getOrNull(sourceIndex + 1)
        if (sourceIndex < 0 || sourcePath == null || sourceSetPath?.fileName?.toString() != sourceSet) {
            return null
        }
        val languagePath = segments.getOrNull(sourceIndex + 2)
        val language = languagePath?.fileName?.toString()
        return if (language == "kotlin" || language == "java") {
            sourcePath.resolve(sourceSetPath.fileName.toString()).resolve(language)
        } else {
            sourceSetPath
        }
    }
}
