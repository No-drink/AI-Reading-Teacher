package com.joey.aireadingteacher.tutor

data class ProviderCapabilities(
    val realtimeAudio: Boolean,
    val imageInput: Boolean,
    val interruption: Boolean,
    val textInput: Boolean,
    val managedAudioTransport: Boolean = false,
)

data class TutorConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val globalInstructions: String,
)

enum class TutorConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface TutorEvent {
    data object Connected : TutorEvent
    data object ResponseAudioStarted : TutorEvent
    data class AudioDelta(
        val bytes: ByteArray,
        val itemId: String,
        val contentIndex: Int,
    ) : TutorEvent
    data object UserSpeechStarted : TutorEvent
    data object ResponseAudioDone : TutorEvent
    data class AssistantTranscriptDelta(
        val responseId: String?,
        val itemId: String?,
        val delta: String,
    ) : TutorEvent
    data class AssistantTranscriptDone(
        val responseId: String?,
        val itemId: String?,
        val transcript: String,
    ) : TutorEvent
    data class Error(val message: String, val cause: Throwable? = null) : TutorEvent
}

data class PlaybackPosition(
    val itemId: String,
    val contentIndex: Int,
    val audioEndMs: Long,
)

object RealtimePayloadLimits {
    // Base64 expands binary data by about one third. These limits leave room for the JSON event.
    const val MAX_IMAGE_BYTES = 170_000
    const val MAX_EVENT_BYTES = 240_000
}
