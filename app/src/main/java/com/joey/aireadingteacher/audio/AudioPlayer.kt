package com.joey.aireadingteacher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.joey.aireadingteacher.tutor.PlaybackPosition
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AudioPlayer {
    private data class Chunk(val bytes: ByteArray, val generation: Long)

    private val lock = Any()
    private val generation = AtomicLong(0)
    private val queue = Channel<Chunk>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val track: AudioTrack

    private var activeItemId: String? = null
    private var activeContentIndex = 0
    private var responseStartHeadFrames = 0L
    private var submittedFrames = 0L

    init {
        val minimumBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android could not determine an audio output buffer size" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, SAMPLE_RATE_HZ / 2))
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output initialization failed" }
        track.play()
        scope.launch {
            for (chunk in queue) {
                if (chunk.generation != generation.get()) continue
                var offset = 0
                while (offset < chunk.bytes.size && chunk.generation == generation.get()) {
                    val written = track.write(
                        chunk.bytes,
                        offset,
                        chunk.bytes.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    fun play(bytes: ByteArray, itemId: String, contentIndex: Int) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (activeItemId != itemId || activeContentIndex != contentIndex) {
                activeItemId = itemId
                activeContentIndex = contentIndex
                responseStartHeadFrames = unsignedPlaybackHead()
                submittedFrames = 0
            }
            submittedFrames += bytes.size / BYTES_PER_SAMPLE
            queue.trySend(Chunk(bytes, generation.get()))
        }
    }

    fun interrupt(): PlaybackPosition? = synchronized(lock) {
        val itemId = activeItemId ?: return@synchronized null
        val playedFrames = (unsignedPlaybackHead() - responseStartHeadFrames)
            .coerceIn(0L, submittedFrames)
        val position = PlaybackPosition(
            itemId = itemId,
            contentIndex = activeContentIndex,
            audioEndMs = playedFrames * 1_000L / SAMPLE_RATE_HZ,
        )
        generation.incrementAndGet()
        track.pause()
        track.flush()
        track.play()
        activeItemId = null
        submittedFrames = 0
        position
    }

    fun release() {
        generation.incrementAndGet()
        queue.close()
        scope.cancel()
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    private fun unsignedPlaybackHead(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    companion object {
        private const val SAMPLE_RATE_HZ = 24_000
        private const val BYTES_PER_SAMPLE = 2
    }
}
