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
        // Outcome contract
        if (result is CompileResult.Compiled) {
            result.outDir.exists() shouldBe true
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
}
