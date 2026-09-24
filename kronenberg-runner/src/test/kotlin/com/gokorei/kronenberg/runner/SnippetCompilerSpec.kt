package com.gokorei.kronenberg.runner

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class SnippetCompilerSpec {
    private val compiler: SnippetCompiler = DefaultSnippetCompiler()

    @Test
    fun `compiles valid kotlin snippet into bytecode in-process`() {
        val source =
            """
            fun calculate(x: Int, y: Int): Int = x * y + 10
            fun main() {
                assert(calculate(2, 3) == 16)
            }
            """.trimIndent()

        val result = compiler.compile(source)
        try {
            result.shouldBeInstanceOf<CompileResult.Compiled>().outDir.exists() shouldBe true
        } finally {
            compiler.cleanup(result)
        }
    }

    @Test
    fun `returns structured failed result with diagnostics on syntax error without throwing`() {
        val invalidSource =
            """
            fun invalidSyntax( {
                return 42
            }
            """.trimIndent()

        val result = compiler.compile(invalidSource)
        result.shouldBeInstanceOf<CompileResult.Failed>()
    }

    @Test
    fun `returns package-qualified metadata for packaged top-level main`() {
        val source =
            """
            package audit.fixture

            class FirstClass

            fun main(args: Array<String>) {
                check(args.isEmpty())
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Resolved(
                className = "audit.fixture.SnippetKt",
                parameterCount = 1,
            )
    }

    @Test
    fun `returns package-qualified metadata for class main`() {
        val source =
            """
            package audit.fixture

            class Entry {
                fun main(args: Array<String>) {
                    check(args.isEmpty())
                }
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Resolved(
                className = "audit.fixture.Entry",
                parameterCount = 1,
                receiver = EntrypointReceiver.CLASS,
            )
    }

    @Test
    fun `returns package-qualified metadata for nested class main`() {
        val source =
            """
            package audit.fixture

            class Outer {
                class Entry {
                    fun main(args: Array<String>) {
                        check(args.isEmpty())
                    }
                }
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Resolved(
                className = "audit.fixture.Outer${'$'}Entry",
                parameterCount = 1,
                receiver = EntrypointReceiver.CLASS,
            )
    }

    @Test
    fun `returns package-qualified metadata for object main`() {
        val source =
            """
            package audit.fixture

            object Entry {
                @JvmStatic
                fun main(args: Array<String>) {
                    check(args.isEmpty())
                }
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Resolved(
                className = "audit.fixture.Entry",
                parameterCount = 1,
            )
    }

    @Test
    fun `returns package-qualified metadata for companion main`() {
        val source =
            """
            package audit.fixture

            class Entry {
                companion object {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        check(args.isEmpty())
                    }
                }
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Resolved(
                className = "audit.fixture.Entry",
                parameterCount = 1,
            )
    }

    @Test
    fun `returns missing entrypoint metadata without choosing a class file`() {
        val source =
            """
            package audit.fixture

            class FirstClass
            class SecondClass
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Missing(
                candidateClassNames = listOf("audit.fixture.FirstClass", "audit.fixture.SecondClass"),
            )
    }

    @Test
    fun `returns deterministic ambiguous entrypoint metadata`() {
        val source =
            """
            package audit.fixture

            object Second {
                @JvmStatic
                fun main(args: Array<String>) = Unit
            }

            object First {
                @JvmStatic
                fun main(args: Array<String>) = Unit
            }
            """.trimIndent()

        entrypointFor(source) shouldBe
            CompilationEntrypoint.Ambiguous(
                candidates =
                    listOf(
                        CompilationEntrypoint.Resolved(
                            className = "audit.fixture.First",
                            parameterCount = 1,
                        ),
                        CompilationEntrypoint.Resolved(
                            className = "audit.fixture.Second",
                            parameterCount = 1,
                        ),
                    ),
            )
    }

    private fun entrypointFor(source: String): CompilationEntrypoint {
        val result = compiler.compile(source)
        return try {
            result.shouldBeInstanceOf<CompileResult.Compiled>().entrypoint
        } finally {
            compiler.cleanup(result)
        }
    }
}
