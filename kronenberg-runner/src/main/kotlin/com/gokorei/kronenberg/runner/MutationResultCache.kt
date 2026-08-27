package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Cache SPI for persisting deterministic evaluation results across mutation passes.
 */
public interface MutationResultCache {
    public fun get(cacheKey: String): MutantResult?

    public fun put(
        cacheKey: String,
        result: MutantResult,
    )

    public fun computeKey(
        mutantSource: String,
        testCode: String,
        mutantId: String,
    ): String
}

/**
 * In-memory and disk backed JSON mutation result cache.
 */
public class DefaultMutationResultCache(
    private val cacheDir: Path? = null,
) : MutationResultCache {
    private val memoryCache = ConcurrentHashMap<String, MutantResult>()
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
        }

    init {
        if (cacheDir != null && cacheDir.exists()) {
            val cacheFile = cacheDir.resolve("mutations-cache.json")
            if (cacheFile.exists()) {
                runCatching {
                    val map = json.decodeFromString<Map<String, MutantResult>>(cacheFile.readText())
                    memoryCache.putAll(map)
                }
            }
        }
    }

    override fun get(cacheKey: String): MutantResult? = memoryCache[cacheKey]

    override fun put(
        cacheKey: String,
        result: MutantResult,
    ) {
        memoryCache[cacheKey] = result
        val dir = cacheDir ?: return
        runCatching {
            Files.createDirectories(dir)
            val cacheFile = dir.resolve("mutations-cache.json")
            val text = json.encodeToString(memoryCache.toMap())
            cacheFile.writeText(text)
        }
    }

    override fun computeKey(
        mutantSource: String,
        testCode: String,
        mutantId: String,
    ): String {
        val payload = "$mutantSource\n---\n$testCode\n---\n$mutantId"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
