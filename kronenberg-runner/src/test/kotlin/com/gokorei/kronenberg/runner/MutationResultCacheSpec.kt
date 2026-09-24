package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantResult
import com.gokorei.kronenberg.model.MutantStatus
import com.gokorei.kronenberg.model.MutationConfig
import com.gokorei.kronenberg.model.MutatorCategory
import com.gokorei.kronenberg.model.ReportMutant
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class MutationResultCacheSpec {
    private val source = "fun add(a: Int, b: Int): Int = a + b"
    private val test = "fun main() { check(add(2, 3) == 5) }"
    private val secret = "S9WT162W_CACHE_SECRET"

    @Test
    fun `cache key excludes mutant identity and includes execution inputs`() {
        val cache = DefaultMutationResultCache()
        val config = MutationConfig(enableCache = true)
        val key = cache.computeKey(source, test, config, 1000L)

        key shouldBe cache.computeKey(source, test, config, 1000L)
        key shouldNotBe cache.computeKey(source, test, config, 1001L)
        key shouldNotBe cache.computeKey(source, test, config.copy(timeoutMultiplier = 4.0), 1000L)
        key shouldNotBe cache.computeKey(source, test, config.copy(extraClasspath = listOf("/changed")), 1000L)
    }

    @Test
    fun `two executions with one cache instance reuse results and stable ids`() {
        runBlocking {
            val cache = DefaultMutationResultCache()
            val pipeline = DefaultMutationExecutionPipeline(cache = cache)
            val config = MutationConfig(enableCache = true)

            try {
                val first = pipeline.execute(source, test, config)
                val second = pipeline.execute(source, test, config)

                first.results.map { it.mutant.id } shouldBe second.results.map { it.mutant.id }
                first.results.map { it.status } shouldBe second.results.map { it.status }
                first.results.map { it.executionTimeMs } shouldBe second.results.map { it.executionTimeMs }
            } finally {
                pipeline.close()
            }
        }
    }

    @Test
    fun `new cache instance loads compact results from disk`() {
        runBlocking {
            val cacheDir = Files.createTempDirectory("kronenberg-cache-test")
            Files.setPosixFilePermissions(cacheDir, PosixFilePermissions.fromString("rwxrwxrwx"))
            val config = MutationConfig(enableCache = true)
            val firstPipeline = DefaultMutationExecutionPipeline(cache = DefaultMutationResultCache(cacheDir))
            val first =
                try {
                    firstPipeline.execute(source, test, config)
                } finally {
                    firstPipeline.close()
                }

            val secondPipeline = DefaultMutationExecutionPipeline(cache = DefaultMutationResultCache(cacheDir))
            val second =
                try {
                    secondPipeline.execute(source, test, config)
                } finally {
                    secondPipeline.close()
                }

            first.results.map { it.mutant.id } shouldBe second.results.map { it.mutant.id }
            first.results.map { it.status } shouldBe second.results.map { it.status }
            first.results.map { it.executionTimeMs } shouldBe second.results.map { it.executionTimeMs }
            val cacheFile = cacheDir.resolve("mutations-cache.json")
            val cacheText = Files.readString(cacheFile)
            cacheText shouldNotContain source
            cacheText shouldNotContain secret
            cacheText shouldNotContain "originalText"
            cacheText shouldNotContain "replacementText"
            cacheText shouldNotContain "failureMessage"
            Files.getPosixFilePermissions(cacheDir) shouldBe
                PosixFilePermissions.fromString("rwx------")
            Files.getPosixFilePermissions(cacheFile) shouldBe
                PosixFilePermissions.fromString("rw-------")
            Files.getPosixFilePermissions(cacheDir.resolve(".mutations-cache.lock")) shouldBe
                PosixFilePermissions.fromString("rw-------")
        }
    }

    @Test
    fun `schema one cache is sanitized when loaded`() {
        val cacheDir = Files.createTempDirectory("kronenberg-cache-migration")
        val cacheFile = cacheDir.resolve("mutations-cache.json")
        Files.writeString(
            cacheFile,
            """
            {
              "schemaVersion": 1,
              "entries": {
                "key": {
                  "id": "mutant-1",
                  "mutatorName": "ArithmeticOperatorMutator",
                  "category": "ARITHMETIC_OPERATOR",
                  "line": 1,
                  "column": 2,
                  "originalText": "$secret",
                  "replacementText": "$secret",
                  "filePath": "Secret.kt",
                  "status": "SURVIVED",
                  "executionTimeMs": 1
                }
              }
            }
            """.trimIndent(),
        )

        DefaultMutationResultCache(cacheDir).loadResult shouldBe CachePersistenceResult.Loaded

        val sanitized = Files.readString(cacheFile)
        sanitized shouldNotContain secret
        sanitized shouldNotContain "originalText"
        sanitized shouldNotContain "replacementText"
        sanitized shouldNotContain "failureMessage"
        Json.parseToJsonElement(sanitized).jsonObject["schemaVersion"].toString() shouldBe "2"
    }

    @Test
    fun `concurrent writes from multiple cache instances preserve every entry`() {
        runBlocking {
            val cacheDir = Files.createTempDirectory("kronenberg-cache-concurrent")
            val caches = List(8) { DefaultMutationResultCache(cacheDir) }
            val writes =
                (0 until 64).map { index ->
                    async(Dispatchers.Default) {
                        caches[index % caches.size].put("key-$index", result(index))
                    }
                }

            writes.awaitAll().forEach { it.shouldBeInstanceOf<CachePersistenceResult.Persisted>() }

            val document = Json.parseToJsonElement(Files.readString(cacheDir.resolve("mutations-cache.json")))
            document.jsonObject
                .getValue("entries")
                .jsonObject.size shouldBe 64
        }
    }

    @Test
    fun `disk cache bounds entries and exposes persistence failures`() {
        val cacheDir = Files.createTempDirectory("kronenberg-cache-bounded")
        val cache = DefaultMutationResultCache(cacheDir)

        repeat(DefaultMutationResultCache.MAX_ENTRIES + 1) { index ->
            cache.put("key-$index", result(index)).shouldBeInstanceOf<CachePersistenceResult.Persisted>()
        }

        val document = Json.parseToJsonElement(Files.readString(cacheDir.resolve("mutations-cache.json")))
        document.jsonObject
            .getValue("entries")
            .jsonObject.size shouldBe DefaultMutationResultCache.MAX_ENTRIES

        val invalidDirectory = Files.createTempFile("kronenberg-cache-invalid", ".tmp")
        val invalidCache = DefaultMutationResultCache(invalidDirectory)
        invalidCache.loadResult.shouldBeInstanceOf<CachePersistenceResult.Failed>()
        invalidCache.put("key", result(0)).shouldBeInstanceOf<CachePersistenceResult.Failed>()
    }

    private fun result(index: Int): MutantResult =
        MutantResult(
            mutant =
                ReportMutant(
                    id = "mutant-$index",
                    mutatorName = "ArithmeticOperatorMutator",
                    category = MutatorCategory.ARITHMETIC_OPERATOR,
                    line = 1,
                    column = 28,
                    originalText = secret,
                    replacementText = secret,
                    mutatedSource = secret,
                ),
            status = MutantStatus.KILLED,
            executionTimeMs = index.toLong(),
            failureMessage = "Execution exposed $secret",
        )
}
