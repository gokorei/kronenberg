package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.ast.MutatorRegistry
import com.gokorei.kronenberg.ast.ResultMutator
import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Proves that `ResultMutator` only emits mutants that compile, and that receivers which merely share
 * a callee name with a `kotlin.Result` member never reach the compiler at all.
 *
 * Every fixture in this spec is valid Kotlin, so a `CompileResult.Failed` always points at a broken
 * mutation rather than at a broken fixture.
 */
class ResultMutantCompilationSpec {
    private val generator = AstMutantGenerator(MutatorRegistry(listOf(ResultMutator())))

    private val resultSources =
        listOf(
            "fun recover(result: Result<Int>): Int = result.getOrElse { 0 }",
            "fun recover(result: Result<Int>): Int = result.getOrDefault(0)",
            "fun recover(result: Result<Int>): Int? = result.getOrNull()",
            "fun observe(result: Result<Int>): Result<Int> = result.onSuccess { println(it) }",
            "fun observe(result: Result<Int>): Result<Int> = result.onFailure { println(it) }",
            "fun recover(result: Result<Int>): Int = result.getOrElse({ 0 })",
            "fun recover(result: Result<Int>?): Int? = result?.getOrNull()",
            "fun first(): Int? = Result.success(1).getOrNull()",
            "fun first(): Int? = runCatching { 1 }.getOrNull()",
            "class Cache {\n    val last: Result<Int> = Result.success(1)\n}\n\n" +
                "fun last(cache: Cache): Int? = cache.last.getOrNull()",
        )

    /**
     * Valid Kotlin in which a non-`Result` receiver exposes a `Result`-sounding callee. The argument
     * shape of several of these matches the `kotlin.Result` overload exactly, so only the PSI
     * receiver rule can keep them out.
     */
    private val foreignSources =
        listOf(
            // Map / MutableMap collide on getOrDefault and getOrElse.
            "fun lookup(config: Map<String, Int>): Int = config.getOrDefault(\"timeout\", 0)",
            "fun lookup(config: MutableMap<String, Int>, fallback: Int): Int =\n" +
                "    config.getOrDefault(\"timeout\", fallback)",
            "fun lookup(config: Map<String, Int>): Int = config.getOrElse(\"timeout\") { 0 }",
            "fun lookup(config: HashMap<String, Int>): Int = config.getOrElse(\"timeout\") { 0 }",
            // Collections collide on getOrNull.
            "fun first(values: List<String>): String? = values.getOrNull(0)",
            "fun first(values: ArrayList<String>): String? = values.getOrNull(0)",
            // String extensions whose signature is identical to the Result overload.
            "fun String.getOrNull(): Char? = if (isEmpty()) null else this[0]\n\n" +
                "fun firstOrNull(text: String): Char? = text.getOrNull()",
            "fun String.getOrElse(fallback: () -> Char): Char = if (isEmpty()) fallback() else this[0]\n\n" +
                "fun firstOrElse(text: String): Char = text.getOrElse { '?' }",
            "fun String.getOrDefault(fallback: Char): Char = if (isEmpty()) fallback else this[0]\n\n" +
                "fun firstOrDefault(text: String): Char = text.getOrDefault('?')",
            "fun String.onSuccess(action: () -> Unit): String = also { action() }\n\n" +
                "fun observed(text: String): String = text.onSuccess { println(\"ok\") }",
            // Custom types shadowing the callee directly or through a local supertype.
            "class Repository {\n    fun getOrNull(id: String): String? = null\n}\n\n" +
                "fun find(repo: Repository): String? = repo.getOrNull(\"id\")",
            "class Repository {\n    fun getOrDefault(id: String, fallback: String): String = fallback\n}\n\n" +
                "fun find(repo: Repository, id: String): String = repo.getOrDefault(id, \"none\")",
            "class Cache {\n    fun onSuccess(action: () -> Unit): Cache = this\n}\n\n" +
                "fun watch(cache: Cache): Cache = cache.onSuccess { println(\"ok\") }",
            "abstract class Listener {\n    abstract fun onFailure(action: () -> Unit): Listener\n}\n\n" +
                "class Impl : Listener() {\n    override fun onFailure(action: () -> Unit): Listener = this\n}\n\n" +
                "fun watch(listener: Listener): Listener = listener.onFailure { println(\"failed\") }",
        )

    /**
     * Colliding callees that the receiver no longer exposes at all. The baseline does not compile,
     * so these are only checked for the absence of mutants, never compiled.
     */
    private val shadowedSources =
        listOf(
            "fun lookup(config: Map<String, Int>): Int = config.getOrDefault(0)",
            "fun lookup(config: Map<String, Int>): Int? = config.getOrNull(\"timeout\")",
        )

    @Test
    fun `every generated Result mutant compiles without errors`() {
        val compiler = DefaultSnippetCompiler()
        var checked = 0
        resultSources.forEach { source ->
            val mutants = generator.generateMutants(source)
            mutants.shouldNotBeEmpty()
            mutants.forEach { mutant ->
                mutant.mutatorName shouldBe "ResultMutator"
                val compiled = compiler.compile(mutant.mutatedSource)
                if (compiled is CompileResult.Failed) {
                    throw AssertionError("Mutant did not compile: ${compiled.message}\n${mutant.mutatedSource}")
                }
                compiler.cleanup(compiled)
                checked++
            }
        }
        checked shouldBe resultSources.size
    }

    @Test
    fun `a file mixing Result Map and String receivers yields only compilable mutants`() {
        val source =
            """
            class Cache {
                val last: Result<Int> = Result.success(1)
            }

            fun String.getOrElse(fallback: () -> Char): Char = if (isEmpty()) fallback() else this[0]

            fun recover(result: Result<Int>): Int = result.getOrElse { 0 }

            fun lookup(config: Map<String, Int>): Int = config.getOrDefault("timeout", 0)

            fun firstOrElse(text: String): Char = text.getOrElse { '?' }

            fun last(cache: Cache): Int? = cache.last.getOrNull()
            """.trimIndent()

        val compiler = DefaultSnippetCompiler()
        val mutants = generator.generateMutants(source)
        mutants.shouldNotBeEmpty()

        mutants.map { it.mutatorName }.distinct() shouldBe listOf("ResultMutator")
        mutants.filter { it.mutatedSource.contains("config.getOrThrow()") }.shouldBeEmpty()
        mutants.filter { it.mutatedSource.contains("text.getOrThrow()") }.shouldBeEmpty()
        mutants.map { it.mutatedSource }.all { it.contains("config.getOrDefault(\"timeout\", 0)") } shouldBe true
        mutants.map { it.mutatedSource }.all { it.contains("text.getOrElse { '?' }") } shouldBe true
        mutants.map { it.mutatedSource }.any { it.contains("result.getOrThrow()") } shouldBe true
        mutants.map { it.mutatedSource }.any { it.contains("cache.last.getOrThrow()") } shouldBe true

        mutants.forEach { mutant ->
            val compiled = compiler.compile(mutant.mutatedSource)
            if (compiled is CompileResult.Failed) {
                throw AssertionError("Mutant did not compile: ${compiled.message}\n${mutant.mutatedSource}")
            }
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `foreign receivers are valid Kotlin but produce no Result mutants`() {
        val compiler = DefaultSnippetCompiler()
        foreignSources.forEach { source ->
            generator
                .generateMutants(source)
                .filter { it.mutatorName == "ResultMutator" }
                .shouldBeEmpty()

            val compiled = compiler.compile(source)
            if (compiled is CompileResult.Failed) {
                throw AssertionError("Fixture is not valid Kotlin: ${compiled.message}")
            }
            compiler.cleanup(compiled)
        }
    }

    @Test
    fun `colliding callees the receiver no longer exposes are still skipped`() {
        shadowedSources.forEach { source ->
            generator
                .generateMutants(source)
                .filter { it.mutatorName == "ResultMutator" }
                .shouldBeEmpty()
        }
    }

    @Test
    fun `pipeline reports no mutants and zero compile errors for foreign receivers`() {
        val pipeline = DefaultMutationExecutionPipeline(generator = generator)
        try {
            foreignSources.forEach { source ->
                runBlocking {
                    val report =
                        pipeline.execute(
                            sourceCode = source,
                            testCode = "fun testNoMutants() { check(true) }",
                            config = MutationConfig(),
                        )
                    report.baselineError shouldBe null
                    report.totalMutants shouldBe 0
                    report.compileErrorCount shouldBe 0
                    report.results.shouldBeEmpty()
                }
            }
        } finally {
            pipeline.close()
        }
    }
}
