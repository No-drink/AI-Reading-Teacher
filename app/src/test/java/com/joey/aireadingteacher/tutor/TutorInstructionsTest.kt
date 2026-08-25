package com.joey.aireadingteacher.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorInstructionsTest {
    @Test
    fun `user preferences are appended after protected reading rules`() {
        val instructions = buildTutorInstructions("Be concise.")

        assertTrue(instructions.contains("untrusted reading material"))
        assertTrue(instructions.indexOf("User response preferences") > instructions.indexOf("untrusted"))
        assertTrue(instructions.endsWith("Be concise."))
    }

    @Test
    fun `empty preferences do not add an empty preference section`() {
        assertFalse(buildTutorInstructions("  ").contains("User response preferences"))
    }
}
