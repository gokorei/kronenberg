package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutatorCategory
import com.gokorei.kronenberg.model.ReportMutant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

public sealed interface CachePersistenceResult {
    public data object NotConfigured : CachePersistenceResult

    public data object Loaded : CachePersistenceResult

    public data object MemoryOnly : CachePersistenceResult

    public data object Persisted : CachePersistenceResult

    public data class Failed(
        val message: String,
    ) : CachePersistenceResult
}

public interface MutationResultCache {
    public fun get(cacheKey: String): MutantResult?

    public fun put(
        cacheKey: String,
        result: MutantResult,
    ): CachePersistenceResult

    public fun computeKey(
        mutantSource: String,
        testCode: String,
        config: MutationConfig,
        timeoutMs: Long,
    ): String
}

public class DefaultMutationResultCache(
    private val cacheDir: Path? = null,
) : MutationResultCache {
    public val loadResult: CachePersistenceResult

    private val memoryLock = Any()
    private val memoryCache =
        LinkedHashMap<String, CachedMutationResult>(INITIAL_MEMORY_CAPACITY, MEMORY_LOAD_FACTOR, true)
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
        }

    init {
        loadResult = loadFromDisk()
    }

    override fun get(cacheKey: String): MutantResult? =
        synchronized(memoryLock) {
            memoryCache[cacheKey]?.toMutantResult()
        }

    override fun put(
        cacheKey: String,
        result: MutantResult,
    ): CachePersistenceResult {
        val entry = result.toCachedResult()
        val dir = normalizedCacheDirectory(cacheDir)
        if (dir == null) {
            synchronized(memoryLock) {
                memoryCache[cacheKey] = entry
                trimMemoryCache(memoryCache)
            }
            return CachePersistenceResult.MemoryOnly
        }

        return persist(dir, cacheKey, entry)
    }

    override fun computeKey(
        mutantSource: String,
        testCode: String,
        config: MutationConfig,
        timeoutMs: Long,
    ): String {
        val configPayload = json.encodeToString(config)
        val classpath = (config.extraClasspath + listOf(System.getProperty("java.class.path").orEmpty())).joinToString("|")
        val payload =
            listOf(
                "kronenberg-cache-key-v$SCHEMA_VERSION",
                mutantSource,
                testCode,
                configPayload,
                timeoutMs.toString(),
                "compiler=${KotlinVersion.CURRENT}",
                "runtime=${System.getProperty("java.specification.version").orEmpty()}",
                "kronenberg=${projectVersion()}",
                "classpath=$classpath",
            ).joinToString("\u0000") { "${it.length}:$it" }
        return sha256(payload)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persist(
        dir: Path,
        cacheKey: String,
        entry: CachedMutationResult,
    ): CachePersistenceResult =
        try {
            createPrivateCacheDirectory(dir)
            withFileLock(dir) {
                when (val disk = readDocument(dir)) {
                    is CacheRead.Failed -> {
                        disk.result
                    }

                    is CacheRead.Success -> {
                        val entries = LinkedHashMap(disk.entries)
                        entries[cacheKey] = entry
                        while (entries.size > MAX_ENTRIES) {
                            val eldest =
                                entries.entries
                                    .iterator()
                                    .next()
                                    .key
                            entries.remove(eldest)
                        }
                        val document = CacheDocument(SCHEMA_VERSION, entries)
                        val text = json.encodeToString(document)
                        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE_BYTES) {
                            CachePersistenceResult.Failed("Cache file exceeds the size limit")
                        } else {
                            writeDocumentAtomically(dir, text)
                            synchronized(memoryLock) {
                                memoryCache.clear()
                                memoryCache.putAll(entries)
                            }
                            CachePersistenceResult.Persisted
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            CachePersistenceResult.Failed(exception.message ?: exception.javaClass.simpleName)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun loadFromDisk(): CachePersistenceResult {
        val dir = normalizedCacheDirectory(cacheDir) ?: return CachePersistenceResult.NotConfigured
        return try {
            if (!Files.exists(dir)) {
                CachePersistenceResult.Loaded
            } else {
                setOwnerOnlyPermissions(dir, directory = true)
                withFileLock(dir) {
                    when (val disk = readDocument(dir)) {
                        is CacheRead.Failed -> {
                            disk.result
                        }

                        is CacheRead.Success -> {
                            synchronized(memoryLock) {
                                memoryCache.clear()
                                memoryCache.putAll(disk.entries)
                                trimMemoryCache(memoryCache)
                            }
                            CachePersistenceResult.Loaded
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            CachePersistenceResult.Failed(exception.message ?: exception.javaClass.simpleName)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readDocument(dir: Path): CacheRead {
        val cacheFile = dir.resolve(CACHE_FILE_NAME)
        if (!Files.exists(cacheFile)) return CacheRead.Success(emptyMap())

        return try {
            setOwnerOnlyPermissions(cacheFile, directory = false)
            if (Files.size(cacheFile) > MAX_FILE_SIZE_BYTES) {
                CacheRead.Failed(CachePersistenceResult.Failed("Cache file exceeds the size limit"))
            } else {
                val document = json.decodeFromString<CacheDocument>(Files.readString(cacheFile))
                when {
                    document.schemaVersion != SCHEMA_VERSION -> {
                        val sanitized = json.encodeToString(CacheDocument(SCHEMA_VERSION, emptyMap()))
                        writeDocumentAtomically(dir, sanitized)
                        CacheRead.Success(emptyMap())
                    }

                    document.entries.size > MAX_ENTRIES -> {
                        CacheRead.Failed(CachePersistenceResult.Failed("Cache entry count exceeds the limit"))
                    }

                    else -> {
                        CacheRead.Success(document.entries)
                    }
                }
            }
        } catch (exception: Exception) {
            CacheRead.Failed(
                CachePersistenceResult.Failed(exception.message ?: exception.javaClass.simpleName),
            )
        }
    }

    private fun writeDocumentAtomically(
        dir: Path,
        text: String,
    ) {
        val cacheFile = dir.resolve(CACHE_FILE_NAME)
        val temporaryFile = createPrivateTempFile(dir)
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            FileChannel
                .open(
                    temporaryFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                    channel.force(true)
                }
            try {
                Files.move(
                    temporaryFile,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(cacheFile, directory = false)
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun <T> withFileLock(
        dir: Path,
        block: () -> T,
    ): T {
        val lock = processLocks.computeIfAbsent(dir) { Any() }
        return synchronized(lock) {
            val lockFile = createPrivateLockFile(dir, LOCK_FILE_NAME)
            FileChannel
                .open(
                    lockFile,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        block()
                    }
                }
        }
    }

    private fun MutantResult.toCachedResult(): CachedMutationResult =
        CachedMutationResult(
            id = mutant.id,
            mutatorName = mutant.mutatorName,
            category = mutant.category,
            line = mutant.line,
            column = mutant.column,
            filePath = mutant.filePath,
            status = status,
            executionTimeMs = executionTimeMs,
        )

    private fun CachedMutationResult.toMutantResult(): MutantResult =
        MutantResult(
            mutant =
                ReportMutant(
                    id = id,
                    mutatorName = mutatorName,
                    category = category,
                    line = line,
                    column = column,
                    filePath = filePath,
                ),
            status = status,
            executionTimeMs = executionTimeMs,
        )

    private sealed interface CacheRead {
        data class Success(
            val entries: Map<String, CachedMutationResult>,
        ) : CacheRead

        data class Failed(
            val result: CachePersistenceResult.Failed,
        ) : CacheRead
    }

    @Serializable
    private data class CacheDocument(
        val schemaVersion: Int,
        val entries: Map<String, CachedMutationResult>,
    )

    @Serializable
    private data class CachedMutationResult(
        val id: String,
        val mutatorName: String,
        val category: MutatorCategory,
        val line: Int,
        val column: Int,
        val filePath: String? = null,
        val status: MutantStatus,
        val executionTimeMs: Long,
    )

    public companion object {
        public const val SCHEMA_VERSION: Int = 2
        public const val MAX_ENTRIES: Int = 512
        public const val MAX_FILE_SIZE_BYTES: Long = 4L * 1024L * 1024L
        public const val CACHE_FILE_NAME: String = "mutations-cache.json"

        private const val INITIAL_MEMORY_CAPACITY: Int = 16
        private const val MEMORY_LOAD_FACTOR: Float = 0.75f
        private const val LOCK_FILE_NAME: String = ".mutations-cache.lock"
        private val processLocks = ConcurrentHashMap<Path, Any>()
    }
}

private fun createPrivateCacheDirectory(dir: Path) {
    try {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS))
    } catch (_: UnsupportedOperationException) {
        Files.createDirectories(dir)
    }
    setOwnerOnlyPermissions(dir, directory = true)
}

private fun createPrivateTempFile(dir: Path): Path =
    try {
        Files.createTempFile(
            dir,
            ".mutations-cache-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS),
        )
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(dir, ".mutations-cache-", ".tmp").also {
            setOwnerOnlyPermissions(it, directory = false)
        }
    }

private fun createPrivateLockFile(
    dir: Path,
    lockFileName: String,
): Path {
    val lockFile = dir.resolve(lockFileName)
    try {
        Files.createFile(lockFile, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))
    } catch (_: FileAlreadyExistsException) {
        setOwnerOnlyPermissions(lockFile, directory = false)
    } catch (_: UnsupportedOperationException) {
        if (!Files.exists(lockFile)) {
            Files.createFile(lockFile).also { setOwnerOnlyPermissions(it, directory = false) }
        } else {
            setOwnerOnlyPermissions(lockFile, directory = false)
        }
    }
    return lockFile
}

private fun setOwnerOnlyPermissions(
    path: Path,
    directory: Boolean,
) {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        val permissions =
            if (directory) {
                PRIVATE_DIRECTORY_PERMISSIONS
            } else {
                PRIVATE_FILE_PERMISSIONS
            }
        Files.setPosixFilePermissions(path, permissions)
        return
    }

    val file = path.toFile()
    val secured =
        file.setReadable(false, false) &&
            file.setWritable(false, false) &&
            file.setExecutable(false, false) &&
            file.setReadable(true, true) &&
            file.setWritable(true, true) &&
            (!directory || file.setExecutable(true, true))
    if (!secured) throw IOException("Unable to secure cache permissions for $path")
}

private val PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
private val PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")

private fun trimMemoryCache(memoryCache: LinkedHashMap<String, *>) {
    while (memoryCache.size > DefaultMutationResultCache.MAX_ENTRIES) {
        val eldest =
            memoryCache.entries
                .iterator()
                .next()
                .key
        memoryCache.remove(eldest)
    }
}

private fun normalizedCacheDirectory(cacheDir: Path?): Path? = cacheDir?.toAbsolutePath()?.normalize()

private fun projectVersion(): String =
    MutationResultCache::class.java
        .getResourceAsStream("/kronenberg-version.txt")
        ?.use { String(it.readBytes(), Charsets.UTF_8).trim() }
        ?.ifBlank { "unknown" }
        ?: "unknown"

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
