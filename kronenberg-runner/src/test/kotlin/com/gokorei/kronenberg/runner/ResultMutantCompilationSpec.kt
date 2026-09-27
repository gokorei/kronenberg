package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.ast.AstMutantGenerator
import com.gokorei.kronenberg.ast.MutatorRegistry
import com.gokorei.kronenberg.ast.ResultMutator
import com.gokorei.kronenberg.model.MutationConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    /**
     * A separate compilation unit that declares `Result`-sounding members on custom types.
     *
     * Every signature is chosen to match the `kotlin.Result` overload exactly, so the argument-shape
     * contract in [com.gokorei.kronenberg.ast.ResultCallContracts] cannot reject them: only the
     * fail-closed receiver proof can.
     */
    private val externalDependencySource =
        """
        package com.example.external

        class Lookup {
            fun getOrNull(): String? = null

            fun getOrDefault(fallback: String): String = fallback

            fun getOrElse(fallback: () -> String): String = fallback()
        }

        class SuccessOnly {
            fun onSuccess(action: () -> Unit): SuccessOnly = this
        }

        class FailureOnly {
            fun onFailure(action: () -> Unit): FailureOnly = this
        }

        class Widget {
            fun getOrNull(): Int = 0

            fun getOrElse(fallback: () -> Int): Int = fallback()
        }

        fun buildWidget(): Widget = Widget()
        """.trimIndent()

    /**
     * Analysed files that call the external types above. None of them mentions `kotlin.Result`, and
     * none of the receiver types can be resolved from the analysed file alone.
     */
    private val externalReceiverSources =
        listOf(
            "import com.example.external.Lookup\n\nfun find(lookup: Lookup): String? = lookup.getOrNull()",
            "import com.example.external.Lookup\n\nfun fallback(lookup: Lookup): String = lookup.getOrDefault(\"none\")",
            "import com.example.external.Lookup\n\nfun fallback(lookup: Lookup): String = lookup.getOrElse { \"none\" }",
            "import com.example.external.SuccessOnly\n\n" +
                "fun watch(only: SuccessOnly): SuccessOnly = only.onSuccess { println(\"ok\") }",
            "import com.example.external.FailureOnly\n\n" +
                "fun watch(only: FailureOnly): FailureOnly = only.onFailure { println(\"failed\") }",
            "import com.example.external.Widget\n\nfun value(widget: Widget): Int = widget.getOrNull()",
            "import com.example.external.Widget\n\nfun fallback(widget: Widget): Int = widget.getOrElse { 0 }",
            "import com.example.external.buildWidget\n\nfun fallback(): Int = buildWidget().getOrElse { 0 }",
            "import com.example.external.Widget\n\nfun value(): Int? = Widget().getOrNull()",
        )

    @Test
    fun `custom receivers from a separate compilation unit produce no mutants`() {
        withExternalDependency { classpath ->
            val compiler = DefaultSnippetCompiler()
            externalReceiverSources.forEach { source ->
                generator
                    .generateMutants(source)
                    .filter { it.mutatorName == "ResultMutator" }
                    .shouldBeEmpty()

                val compiled = compiler.compile(source, extraClasspath = classpath)
                if (compiled is CompileResult.Failed) {
                    throw AssertionError("Fixture is not valid Kotlin: ${compiled.message}\n$source")
                }
                compiler.cleanup(compiled)
            }
        }
    }

    @Test
    fun `the rejected rewrite of an external custom receiver would not compile`() {
        withExternalDependency { classpath ->
            val compiler = DefaultSnippetCompiler()
            // Proof that the fail-closed gate is what keeps these out: every rewrite the mutator used
            // to emit for an unproven receiver is a hard compile error against the real external type.
            val rewrittenToThrow =
                "import com.example.external.Lookup\n\nfun find(lookup: Lookup): String? = lookup.getOrThrow()"
            compiler.compile(rewrittenToThrow, extraClasspath = classpath).shouldBeInstanceOf<CompileResult.Failed>()

            val swappedToFailure =
                "import com.example.external.SuccessOnly\n\n" +
                    "fun watch(only: SuccessOnly): SuccessOnly = only.onFailure {}"
            compiler.compile(swappedToFailure, extraClasspath = classpath).shouldBeInstanceOf<CompileResult.Failed>()

            val swappedToSuccess =
                "import com.example.external.FailureOnly\n\n" +
                    "fun watch(only: FailureOnly): FailureOnly = only.onSuccess {}"
            compiler.compile(swappedToSuccess, extraClasspath = classpath).shouldBeInstanceOf<CompileResult.Failed>()

            // The original calls all compile, so the failures above come from the rewrite alone.
            externalReceiverSources.forEach { source ->
                val compiled = compiler.compile(source, extraClasspath = classpath)
                if (compiled is CompileResult.Failed) {
                    throw AssertionError("Fixture is not valid Kotlin: ${compiled.message}\n$source")
                }
                compiler.cleanup(compiled)
            }
        }
    }

    @Test
    fun `pipeline reports no mutants and zero compile errors for external custom receivers`() {
        withExternalDependency { classpath ->
            val pipeline = DefaultMutationExecutionPipeline(generator = generator)
            try {
                externalReceiverSources.forEach { source ->
                    runBlocking {
                        val report =
                            pipeline.execute(
                                sourceCode = source,
                                testCode = "fun testNoMutants() { check(true) }",
                                config = MutationConfig(extraClasspath = classpath),
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

    @Test
    fun `a real Result from a separate compilation unit mutates once the type is written out`() {
        val dependency =
            """
            package com.example.external

            fun parse(input: String): Result<Int> = Result.success(input.length)
            """.trimIndent()
        val compiler = DefaultSnippetCompiler()
        val compiledDependency = compiler.compile(dependency).shouldBeInstanceOf<CompileResult.Compiled>()
        val classpath = listOf(compiledDependency.outDir.toString())
        try {
            // The receiver type lives only in the other compilation unit, so the analysed file
            // cannot prove it and the call is deliberately left alone.
            val unproven =
                "import com.example.external.parse\n\nfun first(input: String): Int? = parse(input).getOrNull()"
            generator.generateMutants(unproven).shouldBeEmpty()
            val baseline = compiler.compile(unproven, extraClasspath = classpath)
            if (baseline is CompileResult.Failed) {
                throw AssertionError("Fixture is not valid Kotlin: ${baseline.message}")
            }
            compiler.cleanup(baseline)

            // A local function that states the very same return type makes the identical call
            // provable again, and the mutant compiles against the external unit.
            val proven =
                "fun parse(input: String): Result<Int> = com.example.external.parse(input)\n\n" +
                    "fun first(input: String): Int? = parse(input).getOrNull()"
            val mutants = generator.generateMutants(proven)
            mutants.shouldNotBeEmpty()
            mutants.map { it.mutatedSource } shouldBe
                listOf(
                    proven.replace("parse(input).getOrNull()", "parse(input).getOrThrow()"),
                )
            mutants.forEach { mutant ->
                val compiled = compiler.compile(mutant.mutatedSource, extraClasspath = classpath)
                if (compiled is CompileResult.Failed) {
                    throw AssertionError("Mutant did not compile: ${compiled.message}\n${mutant.mutatedSource}")
                }
                compiler.cleanup(compiled)
            }
        } finally {
            compiler.cleanup(compiledDependency)
        }
    }

    /**
     * Compiles [externalDependencySource] into its own output directory and hands its classpath
     * entry to [block], so the block observes a receiver that lives in a real second compilation
     * unit rather than a declaration smuggled into the analysed file.
     */
    private fun <T> withExternalDependency(block: (classpath: List<String>) -> T): T {
        val compiler = DefaultSnippetCompiler()
        val compiled = compiler.compile(externalDependencySource).shouldBeInstanceOf<CompileResult.Compiled>()
        return try {
            block(listOf(compiled.outDir.toString()))
        } finally {
            compiler.cleanup(compiled)
        }
    }

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
