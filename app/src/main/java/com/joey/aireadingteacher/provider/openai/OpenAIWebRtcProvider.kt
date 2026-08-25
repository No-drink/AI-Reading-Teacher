package com.joey.aireadingteacher.provider.openai

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.joey.aireadingteacher.tutor.PlaybackPosition
import com.joey.aireadingteacher.tutor.ProviderCapabilities
import com.joey.aireadingteacher.tutor.RealtimePayloadLimits
import com.joey.aireadingteacher.tutor.TutorConfig
import com.joey.aireadingteacher.tutor.TutorConnectionState
import com.joey.aireadingteacher.tutor.TutorEvent
import com.joey.aireadingteacher.tutor.TutorProvider
import com.joey.aireadingteacher.tutor.buildTutorInstructions
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC owns microphone capture, echo cancellation, jitter buffering, and speaker playback.
 * Realtime JSON events continue to travel over the `oai-events` data channel.
 */
class OpenAIWebRtcProvider(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) : TutorProvider {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    override val capabilities = ProviderCapabilities(
        realtimeAudio = true,
        imageInput = true,
        interruption = true,
        textInput = true,
        managedAudioTransport = true,
    )

    private val mutableConnectionState = MutableStateFlow(TutorConnectionState.DISCONNECTED)
    override val connectionState = mutableConnectionState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<TutorEvent>(extraBufferCapacity = 128)
    override val events = mutableEvents.asSharedFlow()

    private var connectionResult = CompletableDeferred<Unit>()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    override suspend fun connect(config: TutorConfig) {
        require(config.apiKey.isNotBlank()) { "OpenAI API key is required" }
        require(config.model.isNotBlank()) { "Realtime model is required" }
        check(mutableConnectionState.value == TutorConnectionState.DISCONNECTED) {
            "Provider is already connecting or connected"
        }

        mutableConnectionState.value = TutorConnectionState.CONNECTING
        connectionResult = CompletableDeferred()
        try {
            Log.i(TAG, "WEBRTC_INITIALIZING")
            initializeWebRtc()
            configureCommunicationAudio()
            Log.i(TAG, "WEBRTC_CREATING_PEER")
            val localPeer = createPeerConnection()
            val offer = localPeer.createOffer()
            localPeer.setLocalDescription(offer)
            Log.i(TAG, "WEBRTC_EXCHANGING_SDP")
            val answerSdp = exchangeOffer(
                callUrl = OpenAIRealtimeProtocol.webRtcCallUrl(config.baseUrl),
                apiKey = config.apiKey.trim(),
                offerSdp = offer.description,
                sessionConfig = OpenAIRealtimeProtocol.webRtcSessionConfig(
                    model = config.model.trim(),
                    instructions = buildTutorInstructions(config.globalInstructions),
                ),
            )
            localPeer.setRemoteDescription(
                SessionDescription(SessionDescription.Type.ANSWER, answerSdp),
            )
            Log.i(TAG, "WEBRTC_WAITING_FOR_CHANNEL")
            withTimeout(CONNECTION_TIMEOUT_MS) { connectionResult.await() }
        } catch (exception: Exception) {
            cleanup()
            mutableConnectionState.value = TutorConnectionState.ERROR
            throw exception
        }
    }

    override suspend fun disconnect() {
        cleanup()
        mutableConnectionState.value = TutorConnectionState.DISCONNECTED
    }

    override suspend fun sendAudio(pcm16: ByteArray) {
        check(pcm16.isEmpty()) {
            "Raw audio must not be sent when WebRTC manages the microphone"
        }
    }

    override suspend fun sendImage(bytes: ByteArray, mimeType: String) {
        require(bytes.isNotEmpty()) { "Screenshot is empty" }
        require(bytes.size <= RealtimePayloadLimits.MAX_IMAGE_BYTES) {
            "Screenshot is too large for the Realtime WebRTC channel"
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = OpenAIRealtimeProtocol.imageMessage("data:$mimeType;base64,$base64")
        require(event.toByteArray(Charsets.UTF_8).size <= RealtimePayloadLimits.MAX_EVENT_BYTES) {
            "Screenshot event is too large for the Realtime WebRTC channel"
        }
        send(event)
    }

    override suspend fun sendText(text: String) {
        require(text.isNotBlank()) { "Text must not be blank" }
        send(OpenAIRealtimeProtocol.textMessage(text))
    }

    override suspend fun truncateResponse(position: PlaybackPosition) {
        // OpenAI automatically truncates unplayed audio for interrupted WebRTC responses.
    }

    private fun initializeWebRtc() {
        synchronized(initializationLock) {
            if (!webRtcInitialized) {
                val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                webRtcInitialized = true
            }
        }
    }

    private fun createPeerConnection(): PeerConnection {
        val audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val localFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        audioDeviceModule.release()
        factory = localFactory

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val localPeer = checkNotNull(localFactory.createPeerConnection(configuration, PeerObserver())) {
            "Android WebRTC could not create a peer connection"
        }
        peerConnection = localPeer

        val source = localFactory.createAudioSource(MediaConstraints())
        val track = localFactory.createAudioTrack(AUDIO_TRACK_ID, source).apply { setEnabled(true) }
        audioSource = source
        audioTrack = track
        localPeer.addTrack(track, listOf(AUDIO_STREAM_ID))

        val channel = checkNotNull(localPeer.createDataChannel(EVENT_CHANNEL_LABEL, DataChannel.Init())) {
            "Android WebRTC could not create the Realtime event channel"
        }
        dataChannel = channel
        channel.registerObserver(EventChannelObserver())
        return localPeer
    }

    private suspend fun exchangeOffer(
        callUrl: String,
        apiKey: String,
        offerSdp: String,
        sessionConfig: String,
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "sdp",
                null,
                offerSdp.toRequestBody(SDP_MEDIA_TYPE),
            )
            .addFormDataPart("session", sessionConfig)
            .build()
        val request = Request.Builder()
            .url(callUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                error("OpenAI WebRTC session failed: HTTP ${response.code} ${responseText.take(500)}")
            }
            check(responseText.isNotBlank()) { "OpenAI returned an empty WebRTC answer" }
            responseText
        }
    }

    private fun send(message: String) {
        check(mutableConnectionState.value == TutorConnectionState.CONNECTED) {
            "Realtime provider is not connected"
        }
        val channel = checkNotNull(dataChannel) { "Realtime event channel is unavailable" }
        val accepted = channel.send(
            DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false),
        )
        check(accepted) { "Realtime event channel rejected an outgoing event" }
    }

    private fun handleMessage(message: String) {
        val event = OpenAIRealtimeProtocol.parseObject(message)
        when (val type = event["type"]?.jsonPrimitive?.contentOrNull) {
            "session.created", "session.updated" -> Log.v(TAG, "Realtime event: $type")
            "input_audio_buffer.speech_started" -> {
                mutableEvents.tryEmit(TutorEvent.UserSpeechStarted)
            }
            "output_audio_buffer.started", "response.output_audio.delta" -> {
                mutableEvents.tryEmit(TutorEvent.ResponseAudioStarted)
            }
            "output_audio_buffer.stopped", "output_audio_buffer.cleared",
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
                fail("REALTIME_WEBRTC_ERROR $messageText", IllegalStateException(messageText))
            }
            else -> Log.v(TAG, "Realtime event: $type")
        }
    }

    private fun markConnected() {
        if (mutableConnectionState.value != TutorConnectionState.CONNECTING) return
        mutableConnectionState.value = TutorConnectionState.CONNECTED
        connectionResult.complete(Unit)
        mutableEvents.tryEmit(TutorEvent.Connected)
        Log.i(TAG, "VOICE_CONNECTED transport=webrtc")
    }

    private fun fail(message: String, cause: Throwable) {
        Log.e(TAG, message, cause)
        mutableConnectionState.value = TutorConnectionState.ERROR
        mutableEvents.tryEmit(TutorEvent.Error(cause.message ?: message, cause))
        if (!connectionResult.isCompleted) connectionResult.completeExceptionally(cause)
    }

    @Suppress("DEPRECATION")
    private fun configureCommunicationAudio() {
        previousAudioMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    @Suppress("DEPRECATION")
    private fun restoreCommunicationAudio() {
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.mode = previousAudioMode
    }

    private fun cleanup() {
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        runCatching { dataChannel?.dispose() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { restoreCommunicationAudio() }
            .onFailure { Log.w(TAG, "Could not restore Android audio routing", it) }
    }

    private inner class EventChannelObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (dataChannel?.state()) {
                DataChannel.State.OPEN -> markConnected()
                DataChannel.State.CLOSED -> {
                    if (mutableConnectionState.value == TutorConnectionState.CONNECTED) {
                        fail("REALTIME_WEBRTC_CHANNEL_CLOSED", IllegalStateException("Realtime voice channel closed"))
                    }
                }
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            try {
                handleMessage(bytes.toString(Charsets.UTF_8))
            } catch (exception: Exception) {
                fail("Failed to process a Realtime WebRTC event", exception)
            }
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (newState == PeerConnection.IceConnectionState.FAILED) {
                fail("REALTIME_WEBRTC_ICE_FAILED", IllegalStateException("WebRTC network negotiation failed"))
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
        private const val EVENT_CHANNEL_LABEL = "oai-events"
        private const val AUDIO_TRACK_ID = "teacher-microphone"
        private const val AUDIO_STREAM_ID = "teacher-stream"
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private val SDP_MEDIA_TYPE = "application/sdp".toMediaType()
        private val initializationLock = Any()
        private var webRtcInitialized = false
    }
}

private suspend fun PeerConnection.createOffer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("WebRTC offer failed: $error"))
                    }
                }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.setLocalDescription(description: SessionDescription) =
    setDescription(description, local = true)

private suspend fun PeerConnection.setRemoteDescription(description: SessionDescription) =
    setDescription(description, local = false)

private suspend fun PeerConnection.setDescription(
    description: SessionDescription,
    local: Boolean,
): Unit = suspendCancellableCoroutine { continuation ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() {
            if (continuation.isActive) continuation.resume(Unit)
        }
        override fun onSetFailure(error: String) {
            if (continuation.isActive) {
                val side = if (local) "local offer" else "remote answer"
                continuation.resumeWithException(
                    IllegalStateException("WebRTC could not set $side: $error"),
                )
            }
        }
    }
    if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
}
