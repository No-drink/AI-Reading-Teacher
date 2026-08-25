package com.joey.aireadingteacher.provider.openai

import android.util.Base64
import android.util.Log
import com.joey.aireadingteacher.tutor.PlaybackPosition
import com.joey.aireadingteacher.tutor.ProviderCapabilities
import com.joey.aireadingteacher.tutor.TutorConfig
import com.joey.aireadingteacher.tutor.TutorConnectionState
import com.joey.aireadingteacher.tutor.TutorEvent
import com.joey.aireadingteacher.tutor.TutorProvider
import com.joey.aireadingteacher.tutor.buildTutorInstructions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OpenAIRealtimeProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : TutorProvider {
    override val capabilities = ProviderCapabilities(
        realtimeAudio = true,
        imageInput = true,
        interruption = true,
        textInput = true,
    )

    private val mutableConnectionState = MutableStateFlow(TutorConnectionState.DISCONNECTED)
    override val connectionState = mutableConnectionState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<TutorEvent>(extraBufferCapacity = 128)
    override val events = mutableEvents.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    private var connectionResult = CompletableDeferred<Unit>()
    private var model = ""
    private var instructions = ""

    override suspend fun connect(config: TutorConfig) {
        require(config.apiKey.isNotBlank()) { "OpenAI API key is required" }
        require(config.model.isNotBlank()) { "Realtime model is required" }
        check(mutableConnectionState.value == TutorConnectionState.DISCONNECTED) {
            "Provider is already connecting or connected"
        }

        model = config.model.trim()
        instructions = buildTutorInstructions(config.globalInstructions)
        connectionResult = CompletableDeferred()
        mutableConnectionState.value = TutorConnectionState.CONNECTING
        val request = Request.Builder()
            .url(OpenAIRealtimeProtocol.websocketUrl(config.baseUrl, model))
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .build()
        webSocket = client.newWebSocket(request, SocketListener())

        try {
            withTimeout(CONNECTION_TIMEOUT_MS) { connectionResult.await() }
        } catch (exception: Exception) {
            webSocket?.cancel()
            webSocket = null
            mutableConnectionState.value = TutorConnectionState.ERROR
            throw exception
        }
    }

    override suspend fun disconnect() {
        val socket = webSocket
        webSocket = null
        mutableConnectionState.value = TutorConnectionState.DISCONNECTED
        if (socket != null && !socket.close(NORMAL_CLOSE_CODE, "Teacher stopped")) {
            socket.cancel()
        }
    }

    override suspend fun sendAudio(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val base64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        send(OpenAIRealtimeProtocol.appendAudio(base64))
    }

    override suspend fun sendImage(bytes: ByteArray, mimeType: String) {
        require(bytes.isNotEmpty()) { "Screenshot is empty" }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        send(OpenAIRealtimeProtocol.imageMessage("data:$mimeType;base64,$base64"))
    }

    override suspend fun sendText(text: String) {
        require(text.isNotBlank()) { "Text must not be blank" }
        send(OpenAIRealtimeProtocol.textMessage(text))
    }

    override suspend fun truncateResponse(position: PlaybackPosition) {
        send(OpenAIRealtimeProtocol.truncate(position))
    }

    private fun send(message: String) {
        check(mutableConnectionState.value == TutorConnectionState.CONNECTED) {
            "Realtime provider is not connected"
        }
        check(webSocket?.send(message) == true) { "Realtime socket rejected an outgoing event" }
    }

    private fun handleMessage(message: String) {
        val event = OpenAIRealtimeProtocol.parseObject(message)
        when (val type = event["type"]?.jsonPrimitive?.contentOrNull) {
            "session.created" -> {
                webSocket?.send(
                    OpenAIRealtimeProtocol.sessionUpdate(
                        model = model,
                        instructions = instructions,
                    ),
                )
            }

            "session.updated" -> {
                mutableConnectionState.value = TutorConnectionState.CONNECTED
                connectionResult.complete(Unit)
                mutableEvents.tryEmit(TutorEvent.Connected)
                Log.i(TAG, "VOICE_CONNECTED model=$model")
            }

            "input_audio_buffer.speech_started" -> {
                mutableEvents.tryEmit(TutorEvent.UserSpeechStarted)
            }

            "response.output_audio.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull ?: return
                val contentIndex = event["content_index"]?.jsonPrimitive?.intOrNull ?: 0
                val bytes = Base64.decode(delta, Base64.DEFAULT)
                mutableEvents.tryEmit(TutorEvent.AudioDelta(bytes, itemId, contentIndex))
            }

            "response.output_audio.done", "response.done" -> {
                mutableEvents.tryEmit(TutorEvent.ResponseAudioDone)
            }

            "response.output_audio_transcript.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    mutableEvents.tryEmit(
                        TutorEvent.AssistantTranscriptDelta(
                            responseId = event["response_id"]?.jsonPrimitive?.contentOrNull,
                            itemId = event["item_id"]?.jsonPrimitive?.contentOrNull,
                            delta = delta,
                        ),
                    )
                }
            }

            "response.output_audio_transcript.done" -> {
                mutableEvents.tryEmit(
                    TutorEvent.AssistantTranscriptDone(
                        responseId = event["response_id"]?.jsonPrimitive?.contentOrNull,
                        itemId = event["item_id"]?.jsonPrimitive?.contentOrNull,
                        transcript = event["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ),
                )
            }

            "error" -> {
                val errorObject = event["error"]?.jsonObject
                val messageText = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "OpenAI Realtime returned an unknown error"
                Log.e(TAG, "REALTIME_ERROR $messageText")
                mutableEvents.tryEmit(TutorEvent.Error(messageText))
                if (!connectionResult.isCompleted) {
                    connectionResult.completeExceptionally(IllegalStateException(messageText))
                }
            }

            else -> Log.v(TAG, "Realtime event: $type")
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "REALTIME_SOCKET_OPEN")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(text)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to process Realtime event", exception)
                mutableEvents.tryEmit(
                    TutorEvent.Error("Failed to process a Realtime event.", exception),
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpStatus = response?.let { "HTTP ${it.code} ${it.message}" }
            val transportDetail = t.message?.takeUnless { it == httpStatus }
            val message = listOfNotNull(httpStatus, transportDetail)
                .joinToString(": ")
                .ifBlank { "Realtime connection failed" }
            Log.e(TAG, "REALTIME_SOCKET_FAILED $message", t)
            mutableConnectionState.value = TutorConnectionState.ERROR
            mutableEvents.tryEmit(TutorEvent.Error(message, t))
            if (!connectionResult.isCompleted) {
                connectionResult.completeExceptionally(IllegalStateException(message, t))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "REALTIME_SOCKET_CLOSED code=$code reason=$reason")
            if (this@OpenAIRealtimeProvider.webSocket === webSocket) {
                this@OpenAIRealtimeProvider.webSocket = null
                mutableConnectionState.value = TutorConnectionState.DISCONNECTED
            }
        }
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
