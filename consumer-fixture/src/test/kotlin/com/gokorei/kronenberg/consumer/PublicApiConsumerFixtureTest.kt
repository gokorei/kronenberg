package com.gokorei.kronenberg.consumer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicApiConsumerFixtureTest {
    @Test
    fun `consumer compiles and exercises public PSI APIs transitively`() {
        val result = PublicApiConsumerFixture.run()

        assertEquals(1, result.edits.size)
        assertEquals("true", result.edits.single().originalText)
        assertEquals("false", result.edits.single().replacement)
        assertEquals("fun testConsumer() { consumer() }", result.strippedSource)
        assertEquals(setOf("helper"), result.calledFunctions)
        assertEquals("Consumer.kt", result.edits.single().filePath)
        assertEquals(1, result.edits.single().line)
    }
}
