package com.gokorei.kronenberg.ast

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PsiSourceArchitectureSpec {
    @Test
    fun `production mutators contain no source text parsing`() {
        val root = repositoryRoot()
        val sources =
            Files.walk(root).use { paths ->
                paths
                    .filter { path -> path.toString().endsWith("src/main/kotlin") }
                    .flatMap { path -> Files.walk(path) }
                    .filter { path -> path.toString().endsWith(".kt") }
                    .map { path ->
                        val code = Files.readString(path)
                        K2SnippetFrontend.parsePsi(code)
                    }.toList()
            }
        val astSources = sources.filter { it.packageFqName.asString() == "com.gokorei.kronenberg.ast" }
        val violations =
            astSources.flatMap { file ->
                findSourceTextParsingCalls(file).map { method -> "${file.name}:$method" }
            }

        violations.shouldBeEmpty()
    }

    @Test
    fun `architecture detector rejects substring inspection`() {
        val file =
            K2SnippetFrontend.parsePsi(
                """
                package com.gokorei.kronenberg.ast
                fun inspect(value: String) = value.substringBefore("=")
                """.trimIndent(),
            )

        findSourceTextParsingCalls(file).shouldHaveSize(1)
    }

    @Test
    fun `architecture detector rejects aliased text inspection`() {
        val file =
            K2SnippetFrontend.parsePsi(
                """
                package com.gokorei.kronenberg.ast
                fun inspect(value: String): Boolean {
                    val sourceText = value
                    return sourceText.contains("=")
                }
                """.trimIndent(),
            )

        findSourceTextParsingCalls(file).shouldHaveSize(1)
    }

    @Test
    fun `architecture detector allows Git diff parsing`() {
        val file =
            K2SnippetFrontend.parsePsi(
                """
                package com.gokorei.kronenberg.cli
                fun parse(value: String) = Regex("\\\\d+").find(value)
                """.trimIndent(),
            )

        findSourceTextParsingCalls(file).shouldBeEmpty()
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun findSourceTextParsingCalls(file: KtFile): List<String> {
        if (file.packageFqName.asString() != "com.gokorei.kronenberg.ast") return emptyList()
        val textBindings = mutableSetOf<String>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    val name = property.name
                    if (name != null && (name in TEXT_BINDING_NAMES || property.initializer?.referencesSourceText(textBindings) == true)) {
                        textBindings.add(name)
                    }
                    super.visitProperty(property)
                }
            },
        )

        val calls = mutableListOf<String>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val method = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (method != null && method in FORBIDDEN_METHODS) {
                        calls.add(method)
                    }
                    super.visitCallExpression(expression)
                }
            },
        )
        return calls
    }

    private fun org.jetbrains.kotlin.com.intellij.psi.PsiElement.referencesSourceText(textBindings: Set<String>): Boolean =
        when (this) {
            is KtNameReferenceExpression -> {
                getReferencedName() in textBindings
            }

            is KtDotQualifiedExpression -> {
                (selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == "text" ||
                    selectorExpression?.referencesSourceText(textBindings) == true ||
                    receiverExpression.referencesSourceText(textBindings)
            }

            is KtSafeQualifiedExpression -> {
                (selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == "text" ||
                    selectorExpression?.referencesSourceText(textBindings) == true ||
                    receiverExpression.referencesSourceText(textBindings)
            }

            is KtCallExpression -> {
                val qualified = parent as? KtDotQualifiedExpression
                qualified?.selectorExpression === this && qualified.receiverExpression.referencesSourceText(textBindings)
            }

            else -> {
                false
            }
        }

    private companion object {
        val TEXT_BINDING_NAMES = setOf("code", "source", "sourceText", "text")
        val FORBIDDEN_METHODS =
            setOf(
                "Regex",
                "contains",
                "endsWith",
                "indexOf",
                "lastIndexOf",
                "split",
                "startsWith",
                "substring",
                "substringAfter",
                "substringAfterLast",
                "substringBefore",
                "substringBeforeLast",
                "toRegex",
            )
    }
}
