package com.joey.aireadingteacher.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(onAudio: suspend (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        check(running.compareAndSet(false, true)) { "Audio recorder is already running" }
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android could not determine an audio input buffer size" }
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, CHUNK_BYTES * 2))
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        audioRecord = record

        job = scope.launch {
            val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
            try {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Android did not start microphone recording"
                }
                val buffer = ByteArray(CHUNK_BYTES)
                while (running.get()) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 -> onAudio(buffer.copyOf(count))
                        count < 0 -> error("Microphone read failed with Android error $count")
                    }
                }
            } catch (exception: Throwable) {
                if (running.get()) onError(exception)
            } finally {
                running.set(false)
                echoCanceler?.release()
                runCatching { record.stop() }
                record.release()
                if (audioRecord === record) audioRecord = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 24_000
        private const val CHUNK_DURATION_MS = 100
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES =
            SAMPLE_RATE_HZ * CHUNK_DURATION_MS / 1_000 * BYTES_PER_SAMPLE
    }
}
