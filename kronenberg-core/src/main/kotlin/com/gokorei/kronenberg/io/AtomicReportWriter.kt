package com.gokorei.kronenberg.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

public object AtomicReportWriter {
    public fun write(
        targetFile: Path,
        content: String,
    ) {
        val target = targetFile.toAbsolutePath().normalize()
        val parent = target.parent ?: throw IOException("Report path has no parent: $target")
        rejectSymlink(target)
        rejectSymlink(parent)
        Files.createDirectories(parent)
        rejectSymlink(target)
        rejectSymlink(parent)
        val temporaryFile = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporaryFile,
                content,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(
                temporaryFile,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun rejectSymlink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw IOException("Refusing to write report through symbolic link: $path")
        }
    }
}
