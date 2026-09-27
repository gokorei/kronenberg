package com.gokorei.kronenberg.ast

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.jupiter.api.Test

class ResultMutatorsSpec {
    private data class Case(
        val source: String,
        val originalText: String,
        val replacementText: String,
        val mutatedSource: String,
    )

    private val generator = AstMutantGenerator(MutatorRegistry(listOf(ResultMutator())))

    private fun mutantsOf(source: String) = generator.generateMutants(source).filter { it.mutatorName == "ResultMutator" }

    @Test
    fun `mutates complete Result calls with compilable source`() {
        val cases =
            listOf(
                Case(
                    source = "fun recover(result: Result<Int>): Int = result.getOrElse { 0 }",
                    originalText = "getOrElse { 0 }",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int = result.getOrThrow()",
                ),
                Case(
                    source = "fun recover(result: Result<Int>): Int = result.getOrDefault(0)",
                    originalText = "getOrDefault(0)",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int = result.getOrThrow()",
                ),
                Case(
                    source = "fun recover(result: Result<Int>): Int? = result.getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun recover(result: Result<Int>): Int? = result.getOrThrow()",
                ),
                Case(
                    source = "fun observe(result: Result<Int>): Result<Int> = result.onSuccess { println(it) }",
                    originalText = "onSuccess { println(it) }",
                    replacementText = "onFailure {}",
                    mutatedSource = "fun observe(result: Result<Int>): Result<Int> = result.onFailure {}",
                ),
                Case(
                    source = "fun observe(result: Result<Int>): Result<Int> = result.onFailure { println(it) }",
                    originalText = "onFailure { println(it) }",
                    replacementText = "onSuccess {}",
                    mutatedSource = "fun observe(result: Result<Int>): Result<Int> = result.onSuccess {}",
                ),
            )

        val mutator = ResultMutator()
        cases.forEach { case ->
            val mutants =
                AstMutantGenerator(MutatorRegistry(listOf(mutator)))
                    .generateMutants(case.source)
            mutants.size shouldBe 1
            mutants.single().originalText shouldBe case.originalText
            mutants.single().replacementText shouldBe case.replacementText
            mutants.single().mutatedSource shouldBe case.mutatedSource
        }
    }

    @Test
    fun `mutates Result receivers proven through factories members and safe calls`() {
        val cases =
            listOf(
                "fun first(): Int? = Result.success(1).getOrNull()" to
                    "fun first(): Int? = Result.success(1).getOrThrow()",
                "fun first(): Int? = runCatching { 1 }.getOrNull()" to
                    "fun first(): Int? = runCatching { 1 }.getOrThrow()",
                "fun recover(result: Result<Int>): Int = result.getOrElse({ 0 })" to
                    "fun recover(result: Result<Int>): Int = result.getOrThrow()",
                "fun recover(result: Result<Int>?): Int? = result?.getOrNull()" to
                    "fun recover(result: Result<Int>?): Int? = result?.getOrThrow()",
                "class Cache {\n    val last: Result<Int> = Result.success(1)\n}\n\n" +
                    "fun last(cache: Cache): Int? = cache.last.getOrNull()" to
                    "class Cache {\n    val last: Result<Int> = Result.success(1)\n}\n\n" +
                    "fun last(cache: Cache): Int? = cache.last.getOrThrow()",
                "fun recover(result: Result<Int>): Int {\n    val local: Result<Int> = result\n" +
                    "    return local.getOrNull() ?: 0\n}" to
                    "fun recover(result: Result<Int>): Int {\n    val local: Result<Int> = result\n" +
                    "    return local.getOrThrow() ?: 0\n}",
            )

        cases.forEach { (source, mutatedSource) ->
            mutantsOf(source).map { it.mutatedSource } shouldBe listOf(mutatedSource)
        }
    }

    @Test
    fun `mutates Result receivers proven through chains factories and declared return types`() {
        val cases =
            listOf(
                Case(
                    source = "fun first(): Int? = Result.success(1).getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun first(): Int? = Result.success(1).getOrThrow()",
                ),
                Case(
                    source = "fun first(): Int? = runCatching { 1 }.map { it * 2 }.getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun first(): Int? = runCatching { 1 }.map { it * 2 }.getOrThrow()",
                ),
                Case(
                    source = "fun first(): Int? = Result.success(1).mapCatching { it * 2 }.getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource = "fun first(): Int? = Result.success(1).mapCatching { it * 2 }.getOrThrow()",
                ),
                Case(
                    source =
                        "fun parse(input: String): Result<Int> = Result.success(1)\n\n" +
                            "fun first(input: String): Int? = parse(input).getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource =
                        "fun parse(input: String): Result<Int> = Result.success(1)\n\n" +
                            "fun first(input: String): Int? = parse(input).getOrThrow()",
                ),
                Case(
                    source =
                        "class Parser {\n    fun parse(input: String): Result<Int> = Result.success(1)\n}\n\n" +
                            "fun first(parser: Parser, input: String): Int? = parser.parse(input).getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource =
                        "class Parser {\n    fun parse(input: String): Result<Int> = Result.success(1)\n}\n\n" +
                            "fun first(parser: Parser, input: String): Int? = parser.parse(input).getOrThrow()",
                ),
                Case(
                    source =
                        "fun parse(input: String) = runCatching { input.length }\n\n" +
                            "fun first(input: String): Int? = parse(input).getOrNull()",
                    originalText = "getOrNull()",
                    replacementText = "getOrThrow()",
                    mutatedSource =
                        "fun parse(input: String) = runCatching { input.length }\n\n" +
                            "fun first(input: String): Int? = parse(input).getOrThrow()",
                ),
            )

        cases.forEach { case ->
            val mutants = mutantsOf(case.source)
            mutants.map { it.originalText } shouldBe listOf(case.originalText)
            mutants.single().replacementText shouldBe case.replacementText
            mutants.single().mutatedSource shouldBe case.mutatedSource
        }
    }

    @Test
    fun `mutates every provable call of a chain whose intermediate Result members are also mutated`() {
        val source = "fun first(): Int? = runCatching { 1 }.onSuccess { println(it) }.getOrNull()"

        mutantsOf(source).map { it.mutatedSource } shouldBe
            listOf(
                "fun first(): Int? = runCatching { 1 }.onFailure {}.getOrNull()",
                "fun first(): Int? = runCatching { 1 }.onSuccess { println(it) }.getOrThrow()",
            )
    }

    @Test
    fun `does not mutate custom receivers declared outside the analysed file`() {
        val sources =
            listOf(
                // `Repository`, `Session`, `Cache` and `Listener` all live in another compilation
                // unit, so the analysed file can state nothing about them.
                "fun find(repo: Repository): String? = repo.getOrNull()",
                "fun fallback(repo: Repository): String = repo.getOrDefault(\"none\")",
                "fun fallback(repo: Repository): String = repo.getOrElse { \"none\" }",
                "fun watch(cache: Cache): Cache = cache.onSuccess { println(\"ok\") }",
                "fun watch(listener: Listener): Listener = listener.onFailure { println(\"failed\") }",
                "fun value(session: Session): Int = session.getOrNull()",
                "fun fallback(session: Session): Int = session.getOrElse { 0 }",
                // The receiver is a call the analysed file never resolves, so it stays unproven.
                "fun find(id: String): String? = repository().getOrNull()",
                "fun first(key: String): Int? = lookup(key).getOrNull()",
                "fun first(): Int? = parse(\"x\").getOrNull()",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate when a same-named local function returns a foreign type`() {
        val sources =
            listOf(
                // Call arity selects the two-argument overload, whose return type is not a Result.
                "fun parse(input: String): Result<Int> = Result.success(1)\n\n" +
                    "fun parse(input: String, strict: Boolean): String = \"\"\n\n" +
                    "fun first(input: String): String? = parse(input, true).getOrNull()",
                // The declared return type is a Map, so the receiver is provably foreign.
                "fun lookup(key: String): Map<String, Int> = mapOf()\n\n" +
                    "fun first(key: String): Int? = lookup(key).getOrNull()",
                // A variadic declaration is not a proof, because arity no longer selects it.
                "fun parse(vararg input: String): Result<Int> = Result.success(1)\n\n" +
                    "fun first(): Int? = parse(\"x\").getOrNull()",
                // A body the file does not describe is not a proof either.
                "fun parse(input: String) = load(input)\n\n" +
                    "fun first(input: String): Int? = parse(input).getOrNull()",
                // `fold` is a Result member that does not return a Result, so the chain is unproven.
                "fun first(): Int? = runCatching { 1 }.fold(0) { acc, _ -> acc }.getOrNull()",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate Map receivers`() {
        val sources =
            listOf(
                "fun lookup(config: Map<String, Int>): Int = config.getOrDefault(\"timeout\", 0)",
                "fun lookup(config: MutableMap<String, Int>, fallback: Int): Int =\n" +
                    "    config.getOrDefault(\"timeout\", fallback)",
                "fun lookup(config: Map<String, Int>): Int = config.getOrElse(\"timeout\") { 0 }",
                "fun lookup(config: HashMap<String, Int>): Int = config.getOrElse(\"timeout\") { 0 }",
                "val defaults = mutableMapOf(\"a\" to 1)\n\nfun fallback(): Int = defaults.getOrDefault(\"a\", 0)",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate collection receivers`() {
        val sources =
            listOf(
                "fun first(values: List<String>): String? = values.getOrNull(0)",
                "fun first(values: ArrayList<String>): String? = values.getOrNull(0)",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate String receivers`() {
        val sources =
            listOf(
                "fun String.getOrNull(): Char? = if (isEmpty()) null else this[0]\n\n" +
                    "fun firstOrNull(text: String): Char? = text.getOrNull()",
                "fun String.getOrElse(fallback: () -> Char): Char = if (isEmpty()) fallback() else this[0]\n\n" +
                    "fun firstOrElse(text: String): Char = text.getOrElse { '?' }",
                "fun String.getOrDefault(fallback: Char): Char = if (isEmpty()) fallback else this[0]\n\n" +
                    "fun firstOrDefault(text: String): Char = text.getOrDefault('?')",
                "fun String.onSuccess(action: () -> Unit): String = also { action() }\n\n" +
                    "fun observed(text: String): String = text.onSuccess { println(\"ok\") }",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate custom methods that shadow Result members`() {
        val sources =
            listOf(
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

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `does not mutate calls without a receiver or with a foreign argument shape`() {
        val sources =
            listOf(
                "fun recover(result: Result<Int>): Int = with(result) { getOrNull() ?: 0 }",
                "fun recover(result: Result<Int>): Int = result.getOrElse(0) { 0 }",
                "fun recover(result: Result<Int>): Int = result.getOrDefault(0, 0)",
                "fun lookup(config: Map<String, Int>): Int? = config.getOrNull(\"timeout\")",
                "fun lookup(config: Map<String, Int>): Int = config.getOrDefault(0)",
            )

        sources.forEach { source ->
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `verdict separates Result foreign and unresolvable receivers`() {
        val analyzer = PsiResultReceiverAnalyzer()

        analyzer.verdict(callIn("fun f(r: Result<Int>): Int? = r.getOrNull()", "getOrNull"), "getOrNull") shouldBe
            ResultReceiverVerdict.RESULT
        analyzer.verdict(callIn("fun f(m: Map<String, Int>): Int = m.getOrDefault(\"a\", 0)", "getOrDefault"), "getOrDefault") shouldBe
            ResultReceiverVerdict.FOREIGN
        analyzer.verdict(callIn("fun f(s: String): Char? = s.getOrNull()", "getOrNull"), "getOrNull") shouldBe
            ResultReceiverVerdict.FOREIGN
        analyzer.verdict(callIn("fun f(): Int? = parse(\"x\").getOrNull()", "getOrNull"), "getOrNull") shouldBe
            ResultReceiverVerdict.UNKNOWN
    }

    @Test
    fun `verdict is UNKNOWN for receivers declared in another compilation unit`() {
        val analyzer = PsiResultReceiverAnalyzer()

        val cases =
            listOf(
                Triple("fun f(r: Repository): String? = r.getOrNull()", "getOrNull", "getOrNull"),
                Triple("fun f(r: Repository): String = r.getOrDefault(\"none\")", "getOrDefault", "getOrDefault"),
                Triple("fun f(r: Repository): String = r.getOrElse { \"none\" }", "getOrElse", "getOrElse"),
                Triple("fun f(c: Cache): Cache = c.onSuccess { }", "onSuccess", "onSuccess"),
                Triple("fun f(l: Listener): Listener = l.onFailure { }", "onFailure", "onFailure"),
                Triple("fun f(): String? = repository().getOrNull()", "getOrNull", "getOrNull"),
            )

        cases.forEach { (source, callee, receiverCallee) ->
            analyzer.verdict(callIn(source, receiverCallee), callee) shouldBe ResultReceiverVerdict.UNKNOWN
            mutantsOf(source).shouldBeEmpty()
        }
    }

    @Test
    fun `verdict is RESULT for declared return types factory chains and safe calls`() {
        val analyzer = PsiResultReceiverAnalyzer()

        val cases =
            listOf(
                Triple(
                    "fun p(i: String): Result<Int> = Result.success(1)\n\nfun f(i: String): Int? = p(i).getOrNull()",
                    "getOrNull",
                    "getOrNull",
                ),
                Triple("fun f(): Int? = runCatching { 1 }.map { it }.getOrNull()", "getOrNull", "getOrNull"),
                Triple("fun f(): Int? = runCatching { 1 }.onFailure { }.getOrNull()", "getOrNull", "getOrNull"),
                Triple("fun f(r: Result<Int>?): Int? = r?.getOrNull()", "getOrNull", "getOrNull"),
                Triple("fun f(r: Result<Int>): Result<Int> = r.onSuccess { }", "onSuccess", "onSuccess"),
            )

        cases.forEach { (source, callee, receiverCallee) ->
            analyzer.verdict(callIn(source, receiverCallee), callee) shouldBe ResultReceiverVerdict.RESULT
        }
    }

    @Test
    fun `verdict is FOREIGN when a local function of matching arity returns a foreign type`() {
        val analyzer = PsiResultReceiverAnalyzer()

        val source =
            "fun lookup(k: String): Map<String, Int> = mapOf()\n\nfun f(k: String): Int? = lookup(k).getOrNull()"
        analyzer.verdict(callIn(source, "getOrNull"), "getOrNull") shouldBe ResultReceiverVerdict.FOREIGN
        analyzer.verdict(callIn("fun f(k: String): Int? = lookup(k).getOrNull()", "getOrNull"), "getOrNull") shouldBe
            ResultReceiverVerdict.UNKNOWN
    }

    @Test
    fun `cyclic local supertypes terminate the shadowing search`() {
        val source =
            "abstract class A : B()\n\n" +
                "abstract class B : A() {\n    abstract fun getOrElse(fallback: () -> Int): Int\n}\n\n" +
                "class C : A()\n\n" +
                "fun value(subject: A): Int = subject.getOrElse { 0 }"
        mutantsOf(source).shouldBeEmpty()
        PsiResultReceiverAnalyzer().verdict(callIn(source, "getOrElse"), "getOrElse") shouldBe
            ResultReceiverVerdict.FOREIGN
    }

    @Test
    fun `an analyzer injected into the mutator decides applicability`() {
        val source = "fun find(repo: Repository): String? = repo.getOrNull()"
        val mutantsOfResultVerdict = generatorWith(ResultReceiverVerdict.RESULT).generateMutants(source)
        val mutantsOfUnknownVerdict = generatorWith(ResultReceiverVerdict.UNKNOWN).generateMutants(source)

        mutantsOfResultVerdict.map { it.mutatedSource } shouldBe
            listOf("fun find(repo: Repository): String? = repo.getOrThrow()")
        mutantsOfUnknownVerdict.shouldBeEmpty()
    }

    private fun generatorWith(verdict: ResultReceiverVerdict): AstMutantGenerator =
        AstMutantGenerator(
            MutatorRegistry(
                listOf(
                    ResultMutator(
                        receiverAnalyzer =
                            object : ResultReceiverAnalyzer {
                                override fun verdict(
                                    call: KtCallExpression,
                                    callee: String,
                                ): ResultReceiverVerdict = verdict
                            },
                    ),
                ),
            ),
        )

    private fun callIn(
        source: String,
        callee: String,
    ): KtCallExpression {
        val file = K2SnippetFrontend.parsePsi(source)
        return requireNotNull(
            file.collectDescendantsOfType<KtCallExpression>().firstOrNull { it.calleeExpression?.text == callee },
        ) { "no '$callee' call in snippet" }
    }
}
