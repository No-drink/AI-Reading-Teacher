package com.joey.aireadingteacher.service

import com.joey.aireadingteacher.settings.TutorSettings
import com.joey.aireadingteacher.tutor.VoiceSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TeacherPhase { STOPPED, STARTING, ACTIVE, ERROR }

data class TeacherStatus(
    val phase: TeacherPhase = TeacherPhase.STOPPED,
    val voiceState: VoiceSessionState = VoiceSessionState.DISCONNECTED,
    val provider: String = TutorSettings.DEFAULT_PROVIDER,
    val model: String = TutorSettings.DEFAULT_MODEL,
    val lastCaptureEpochMs: Long? = null,
    val lastCapturePath: String? = null,
    val lastImageSentEpochMs: Long? = null,
    val message: String? = null,
    val assistantTranscripts: List<AssistantTranscript> = emptyList(),
)

data class AssistantTranscript(
    val id: String,
    val text: String,
    val isFinal: Boolean,
)

object TeacherRuntime {
    private val mutableStatus = MutableStateFlow(TeacherStatus())
    val status = mutableStatus.asStateFlow()

    fun markStarting() {
        mutableStatus.value = mutableStatus.value.copy(
            phase = TeacherPhase.STARTING,
            voiceState = VoiceSessionState.DISCONNECTED,
            message = "Starting screen capture and realtime voice…",
            assistantTranscripts = emptyList(),
        )
    }

    fun markConfigured(settings: TutorSettings) {
        mutableStatus.value = mutableStatus.value.copy(
            provider = settings.provider,
            model = settings.model,
        )
    }

    fun markScreenActive() {
        mutableStatus.value = mutableStatus.value.copy(
            phase = TeacherPhase.ACTIVE,
            message = "Screen capture is active. Connecting realtime voice…",
        )
    }

    fun markVoiceState(state: VoiceSessionState) {
        val current = mutableStatus.value
        // Cleanup emits DISCONNECTED after failures. Keep the actionable error instead of
        // replacing it with a generic disconnect message.
        if (current.phase == TeacherPhase.ERROR) return
        mutableStatus.value = current.copy(
            phase = TeacherPhase.ACTIVE,
            voiceState = state,
            message = when (state) {
                VoiceSessionState.DISCONNECTED -> "Realtime voice is disconnected."
                VoiceSessionState.CONNECTED -> "Realtime voice connected."
                VoiceSessionState.LISTENING -> "Teacher is silently listening."
                VoiceSessionState.SPEAKING -> "Teacher is answering. You can interrupt by speaking."
            },
        )
    }

    fun markFrameSaved(path: String) {
        mutableStatus.value = mutableStatus.value.copy(
            phase = TeacherPhase.ACTIVE,
            lastCaptureEpochMs = System.currentTimeMillis(),
            lastCapturePath = path,
            message = "A changed screen became stable and was captured.",
        )
    }

    fun markImageSent() {
        mutableStatus.value = mutableStatus.value.copy(
            lastImageSentEpochMs = System.currentTimeMillis(),
            message = "Latest reading screen sent silently as tutor context.",
        )
    }

    fun markWarning(message: String) {
        mutableStatus.value = mutableStatus.value.copy(message = message)
    }

    fun appendAssistantTranscript(id: String?, delta: String) {
        if (delta.isEmpty()) return
        val current = mutableStatus.value
        val stableId = id ?: current.assistantTranscripts.lastOrNull()
            ?.takeUnless { it.isFinal }?.id ?: "transcript-${System.currentTimeMillis()}"
        val existingIndex = current.assistantTranscripts.indexOfLast {
            it.id == stableId && !it.isFinal
        }
        val updated = if (existingIndex >= 0) {
            current.assistantTranscripts.toMutableList().apply {
                val existing = this[existingIndex]
                this[existingIndex] = existing.copy(text = existing.text + delta)
            }
        } else {
            (current.assistantTranscripts + AssistantTranscript(stableId, delta, false))
                .takeLast(MAX_TRANSCRIPT_LINES)
        }
        mutableStatus.value = current.copy(assistantTranscripts = updated)
    }

    fun finishAssistantTranscript(id: String?, transcript: String) {
        val current = mutableStatus.value
        val stableId = id ?: current.assistantTranscripts.lastOrNull()
            ?.takeUnless { it.isFinal }?.id ?: "transcript-${System.currentTimeMillis()}"
        val existingIndex = current.assistantTranscripts.indexOfLast {
            it.id == stableId && !it.isFinal
        }
        val updated = if (existingIndex >= 0) {
            current.assistantTranscripts.toMutableList().apply {
                val existing = this[existingIndex]
                this[existingIndex] = existing.copy(
                    text = transcript.ifBlank { existing.text },
                    isFinal = true,
                )
            }
        } else if (transcript.isNotBlank()) {
            (current.assistantTranscripts + AssistantTranscript(stableId, transcript, true))
                .takeLast(MAX_TRANSCRIPT_LINES)
        } else {
            current.assistantTranscripts
        }
        mutableStatus.value = current.copy(assistantTranscripts = updated)
    }

    fun markPermissionDenied(message: String) {
        mutableStatus.value = mutableStatus.value.copy(
            phase = TeacherPhase.ERROR,
            message = message,
        )
    }

    fun markError(message: String) {
        mutableStatus.value = mutableStatus.value.copy(
            phase = TeacherPhase.ERROR,
            voiceState = VoiceSessionState.DISCONNECTED,
            message = message,
        )
    }

    fun markStopped() {
        if (mutableStatus.value.phase != TeacherPhase.ERROR) {
            val previous = mutableStatus.value
            mutableStatus.value = TeacherStatus(
                provider = previous.provider,
                model = previous.model,
            )
        }
    }


    private const val MAX_TRANSCRIPT_LINES = 12
}
