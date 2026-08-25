package com.joey.aireadingteacher.tutor

import android.util.Log
import com.joey.aireadingteacher.audio.AudioPlayer
import com.joey.aireadingteacher.audio.AudioRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TutorSession(
    private val provider: TutorProvider,
    private val listener: Listener,
    private val imageEncoder: TutorImageEncoder = TutorImageEncoder(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingImages = MutableSharedFlow<File>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var eventJob: Job? = null
    private var imageJob: Job? = null
    private var recorder: AudioRecorder? = null
    private var player: AudioPlayer? = null

    suspend fun start(config: TutorConfig) {
        check(eventJob == null) { "Tutor session is already started" }
        val audioPlayer = if (provider.capabilities.managedAudioTransport) null else AudioPlayer()
        player = audioPlayer
        eventJob = scope.launch {
            provider.events.collect { event -> handleEvent(event, audioPlayer) }
        }

        provider.connect(config)
        imageJob = scope.launch {
            pendingImages.collectLatest { file -> sendImageSafely(file) }
        }
        if (!provider.capabilities.managedAudioTransport) {
            val audioRecorder = AudioRecorder()
            recorder = audioRecorder
            audioRecorder.start(
                onAudio = provider::sendAudio,
                onError = { listener.onFatalError("Microphone streaming failed.", it) },
            )
        }
        listener.onVoiceStateChanged(VoiceSessionState.LISTENING)
    }

    fun updateReadingContext(file: File) {
        pendingImages.tryEmit(file)
    }

    suspend fun stop() {
        recorder?.release()
        recorder = null
        player?.release()
        player = null
        imageJob?.cancel()
        eventJob?.cancel()
        provider.disconnect()
        scope.cancel()
        listener.onVoiceStateChanged(VoiceSessionState.DISCONNECTED)
    }

    private fun handleEvent(event: TutorEvent, audioPlayer: AudioPlayer?) {
        when (event) {
            TutorEvent.Connected -> listener.onVoiceStateChanged(VoiceSessionState.CONNECTED)
            TutorEvent.ResponseAudioStarted -> {
                listener.onVoiceStateChanged(VoiceSessionState.SPEAKING)
            }
            TutorEvent.UserSpeechStarted -> {
                val position = audioPlayer?.interrupt()
                if (position != null) {
                    scope.launch {
                        runCatching { provider.truncateResponse(position) }
                            .onFailure {
                                listener.onRecoverableError(
                                    "Could not synchronize an interrupted response.",
                                    it,
                                )
                            }
                    }
                    Log.i(TAG, "VOICE_RESPONSE_INTERRUPTED audioEndMs=${position.audioEndMs}")
                }
                listener.onVoiceStateChanged(VoiceSessionState.LISTENING)
            }

            is TutorEvent.AudioDelta -> {
                audioPlayer?.play(event.bytes, event.itemId, event.contentIndex)
                listener.onVoiceStateChanged(VoiceSessionState.SPEAKING)
            }

            TutorEvent.ResponseAudioDone -> {
                listener.onVoiceStateChanged(VoiceSessionState.LISTENING)
            }

            is TutorEvent.AssistantTranscriptDelta -> {
                listener.onAssistantTranscriptDelta(
                    id = event.itemId ?: event.responseId,
                    delta = event.delta,
                )
            }

            is TutorEvent.AssistantTranscriptDone -> {
                listener.onAssistantTranscriptDone(
                    id = event.itemId ?: event.responseId,
                    transcript = event.transcript,
                )
            }

            is TutorEvent.Error -> listener.onFatalError(event.message, event.cause)
        }
    }

    private suspend fun sendImageSafely(file: File) {
        try {
            val image = imageEncoder.encode(file)
            provider.sendImage(image.bytes, image.mimeType)
            Log.i(TAG, "IMAGE_SENT_TO_TUTOR bytes=${image.bytes.size}")
            listener.onImageSent()
        } catch (exception: CancellationException) {
            Log.d(TAG, "IMAGE_ENCODING_SUPERSEDED")
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "IMAGE_SEND_FAILED", exception)
            listener.onRecoverableError("The latest screenshot could not be sent.", exception)
        }
    }

    interface Listener {
        fun onVoiceStateChanged(state: VoiceSessionState)
        fun onImageSent()
        fun onAssistantTranscriptDelta(id: String?, delta: String)
        fun onAssistantTranscriptDone(id: String?, transcript: String)
        fun onRecoverableError(message: String, cause: Throwable? = null)
        fun onFatalError(message: String, cause: Throwable? = null)
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
    }
}

enum class VoiceSessionState {
    DISCONNECTED,
    CONNECTED,
    LISTENING,
    SPEAKING,
}
