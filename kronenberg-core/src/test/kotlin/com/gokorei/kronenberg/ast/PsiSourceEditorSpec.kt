package com.gokorei.kronenberg.ast

import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Test

class PsiSourceEditorSpec {
    private val editor = DefaultPsiSourceEditor

    @Test
    fun `applies descending edits through PSI documents`() {
        val source =
            """
            fun calculate(a: Int, b: Int, c: Int) = a + b + c
            """.trimIndent()
        val operators = mutableListOf<KtBinaryExpression>()
        K2SnippetFrontend.parsePsi(source).accept(
            object : KtTreeVisitorVoid() {
                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    operators.add(expression)
                    super.visitBinaryExpression(expression)
                }
            },
        )
        val edits =
            operators.map { expression ->
                val range = expression.operationReference.textRange
                PsiSourceEdit(range.startOffset, range.endOffset, "*")
            }

        val result = editor.replace(source, edits)

        result shouldBe PsiReplacementResult.Applied("fun calculate(a: Int, b: Int, c: Int) = a * b * c")
    }

    @Test
    fun `rejects non PSI boundaries`() {
        val source = "fun identity(value: Int) = value"
        val parameter =
            K2SnippetFrontend
                .parsePsi(source)
                .declarations
                .first()
                .textRange
        val edit = PsiSourceEdit(parameter.startOffset + 5, parameter.endOffset, "ignored")

        val result = editor.replace(source, listOf(edit))

        result shouldBe PsiReplacementResult.Rejected(edit, PsiReplacementFailure.NON_PSI_BOUNDARY)
    }

    @Test
    fun `rejects overlapping edits`() {
        val source = "fun sum(a: Int, b: Int) = a + b"
        var expression: KtBinaryExpression? = null
        K2SnippetFrontend.parsePsi(source).accept(
            object : KtTreeVisitorVoid() {
                override fun visitBinaryExpression(binary: KtBinaryExpression) {
                    expression = binary
                    super.visitBinaryExpression(binary)
                }
            },
        )
        val range = expression!!.textRange
        val edits =
            listOf(
                PsiSourceEdit(range.startOffset, range.endOffset, "0"),
                PsiSourceEdit(range.startOffset + 1, range.endOffset, "1"),
            )

        val result = editor.replace(source, edits)

        (result as PsiReplacementResult.Rejected).reason shouldBe PsiReplacementFailure.OVERLAPPING_RANGES
    }

    @Test
    fun `preserves nested templates comments and trailing commas`() {
        val source =
            """
            fun render(count: Int, enabled: Boolean): String =
                buildString {
                    val active = enabled && count > 0
                    // template
                    append("status=${'$'}{if (enabled) "${'$'}count" else "none"}",
                    )
                }
            """.trimIndent()
        var target: KtBinaryExpression? = null
        K2SnippetFrontend.parsePsi(source).accept(
            object : KtTreeVisitorVoid() {
                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    if (target == null) target = expression
                    super.visitBinaryExpression(expression)
                }
            },
        )
        val range = target!!.operationReference.textRange
        val result = editor.replace(source, listOf(PsiSourceEdit(range.startOffset, range.endOffset, "&&")))
        result shouldBe PsiReplacementResult.Applied(source)
        var errors = 0
        K2SnippetFrontend.parsePsi(source).accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
                    if (element is PsiErrorElement) errors++
                    super.visitElement(element)
                }
            },
        )

        errors shouldBe 0
        source shouldBe
            """
            fun render(count: Int, enabled: Boolean): String =
                buildString {
                    val active = enabled && count > 0
                    // template
                    append("status=${'$'}{if (enabled) "${'$'}count" else "none"}",
                    )
                }
            """.trimIndent()
    }
}
