package com.joey.aireadingteacher.settings

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorSettingsTest {
    @Test
    fun `default settings are valid`() {
        val settings = TutorSettings()
        settings.validate()
        assertEquals("gpt-realtime-2.1", settings.model)
        assertTrue(settings.globalInstructions.contains("one to three short sentences"))
        assertTrue(settings.globalInstructions.contains("latest screen and conversation context"))
    }

    @Test
    fun `insecure base URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TutorSettings(baseUrl = "http://example.com/v1").validate()
        }
    }

    @Test
    fun `unimplemented provider is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TutorSettings(provider = "Other").validate()
        }
    }

    @Test
    fun `global instructions have a bounded size`() {
        assertThrows(IllegalArgumentException::class.java) {
            TutorSettings(globalInstructions = "x".repeat(4_001)).validate()
        }
    }
}
