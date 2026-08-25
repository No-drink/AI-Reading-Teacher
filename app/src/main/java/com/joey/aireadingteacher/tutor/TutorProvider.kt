package com.joey.aireadingteacher.tutor

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TutorProvider {
    val capabilities: ProviderCapabilities
    val connectionState: StateFlow<TutorConnectionState>
    val events: SharedFlow<TutorEvent>

    suspend fun connect(config: TutorConfig)
    suspend fun disconnect()
    suspend fun sendAudio(pcm16: ByteArray)
    suspend fun sendImage(bytes: ByteArray, mimeType: String)
    suspend fun sendText(text: String)
    suspend fun truncateResponse(position: PlaybackPosition)
}
