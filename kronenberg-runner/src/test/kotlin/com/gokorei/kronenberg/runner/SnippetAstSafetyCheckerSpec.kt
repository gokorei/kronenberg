package com.gokorei.kronenberg.runner

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SnippetAstSafetyCheckerSpec {
    @Test
    fun `detects System exit calls`() {
        val dangerousCode = "fun crash() { System.exit(0) }"
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode) shouldBe true
    }

    @Test
    fun `detects exitProcess calls`() {
        val dangerousCode =
            """
            import kotlin.system.exitProcess
            fun terminate() { exitProcess(1) }
            """.trimIndent()
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode) shouldBe true
    }

    @Test
    fun `detects Runtime halt calls`() {
        val dangerousCode = "fun stop() { Runtime.getRuntime().halt(0) }"
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode) shouldBe true
    }

    @Test
    fun `detects ProcessBuilder and Runtime exec calls`() {
        val dangerousCode1 = "fun spawn() { ProcessBuilder(\"rm\", \"-rf\", \"/\").start() }"
        val dangerousCode2 = "fun exec() { Runtime.getRuntime().exec(\"whoami\") }"
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode1) shouldBe true
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode2) shouldBe true
    }

    @Test
    fun `detects destructive recursive file deletion calls`() {
        val dangerousCode = "fun wipe(f: java.io.File) { f.deleteRecursively() }"
        SnippetAstSafetyChecker.containsHostTerminatingCalls(dangerousCode) shouldBe true
    }

    @Test
    fun `allows safe Kotlin snippet code`() {
        val safeCode = "fun add(a: Int, b: Int): Int = a + b"
        SnippetAstSafetyChecker.containsHostTerminatingCalls(safeCode) shouldBe false
    }
}
