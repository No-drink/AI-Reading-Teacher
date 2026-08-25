package com.joey.aireadingteacher.service

import com.joey.aireadingteacher.tutor.VoiceSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherRuntimeTest {
    @Test
    fun `disconnect during cleanup does not overwrite actionable error`() {
        TeacherRuntime.markStarting()
        TeacherRuntime.markError("HTTP 401 Unauthorized")

        TeacherRuntime.markVoiceState(VoiceSessionState.DISCONNECTED)

        assertEquals(TeacherPhase.ERROR, TeacherRuntime.status.value.phase)
        assertEquals("HTTP 401 Unauthorized", TeacherRuntime.status.value.message)
    }

    @Test
    fun `transcript deltas form one line and done finalizes it`() {
        TeacherRuntime.markStarting()

        TeacherRuntime.appendAssistantTranscript("item-1", "The key ")
        TeacherRuntime.appendAssistantTranscript("item-1", "idea")
        assertFalse(TeacherRuntime.status.value.assistantTranscripts.single().isFinal)

        TeacherRuntime.finishAssistantTranscript("item-1", "The key idea.")

        val transcript = TeacherRuntime.status.value.assistantTranscripts.single()
        assertEquals("The key idea.", transcript.text)
        assertTrue(transcript.isFinal)
    }

    @Test
    fun `starting a new session clears transcripts`() {
        TeacherRuntime.markStarting()
        TeacherRuntime.appendAssistantTranscript("item-1", "Old answer")

        TeacherRuntime.markStarting()

        assertTrue(TeacherRuntime.status.value.assistantTranscripts.isEmpty())
    }
}
