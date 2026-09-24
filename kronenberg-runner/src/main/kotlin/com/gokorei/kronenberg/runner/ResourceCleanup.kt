package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.CleanupDiagnostic
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator

@Suppress("TooGenericExceptionCaught")
internal fun deleteTemporaryTree(path: Path): CleanupDiagnostic? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    return try {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
        null
    } catch (exception: Exception) {
        CleanupDiagnostic(
            resource = "temporary-output",
            operation = "delete",
            message = "${exception.javaClass.simpleName}: ${exception.message.orEmpty()} at $path",
        )
    }
}
