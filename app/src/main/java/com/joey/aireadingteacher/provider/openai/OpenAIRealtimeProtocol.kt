package com.joey.aireadingteacher.provider.openai

import com.joey.aireadingteacher.tutor.PlaybackPosition
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OpenAIRealtimeProtocol {
    private val json = Json

    fun websocketUrl(baseUrl: String, model: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Base URL is required" }
        val uri = URI(normalized)
        require(uri.scheme == "https") {
            "Base URL must begin with https://"
        }
        require(!uri.host.isNullOrBlank()) { "Base URL must contain a host" }
        val encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8.toString())
        return URI(
            "wss",
            uri.userInfo,
            uri.host,
            uri.port,
            "${uri.path.trimEnd('/')}/realtime",
            "model=$encodedModel",
            null,
        ).toString()
    }

    fun webRtcCallUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Base URL is required" }
        val uri = URI(normalized)
        require(uri.scheme == "https") { "Base URL must begin with https://" }
        require(!uri.host.isNullOrBlank()) { "Base URL must contain a host" }
        return URI(
            "https",
            uri.userInfo,
            uri.host,
            uri.port,
            "${uri.path.trimEnd('/')}/realtime/calls",
            null,
            null,
        ).toString()
    }

    fun webRtcSessionConfig(model: String, instructions: String): String = encode(
        buildJsonObject {
            put("type", "realtime")
            put("model", model)
            put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
            put("instructions", instructions)
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("turn_detection", buildJsonObject {
                        put("type", "server_vad")
                        put("threshold", 0.5)
                        put("prefix_padding_ms", 300)
                        put("silence_duration_ms", 500)
                        put("create_response", true)
                        put("interrupt_response", true)
                    })
                })
                put("output", buildJsonObject {
                    put("voice", "marin")
                })
            })
        },
    )

    fun sessionUpdate(model: String, instructions: String): String = encode(
        buildJsonObject {
            put("type", "session.update")
            put("session", buildJsonObject {
                put("type", "realtime")
                put("model", model)
                put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
                put("instructions", instructions)
                put("audio", buildJsonObject {
                    put("input", buildJsonObject {
                        put("format", pcmFormat())
                        put("turn_detection", buildJsonObject {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 500)
                            put("create_response", true)
                            put("interrupt_response", true)
                        })
                    })
                    put("output", buildJsonObject {
                        put("format", pcmFormat())
                        put("voice", "marin")
                    })
                })
            })
        },
    )

    fun appendAudio(base64Audio: String): String = encode(buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64Audio)
    })

    fun imageMessage(dataUrl: String): String = encode(buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put(
                        "text",
                        "This is the latest reading screen. Use it silently as current context; do not respond yet.",
                    )
                })
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", dataUrl)
                })
            })
        })
    })

    fun textMessage(text: String): String = encode(buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            })
        })
    })

    fun truncate(position: PlaybackPosition): String = encode(buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", position.itemId)
        put("content_index", position.contentIndex)
        put("audio_end_ms", position.audioEndMs)
    })

    fun parseObject(message: String): JsonObject = json.parseToJsonElement(message).let {
        it as? JsonObject ?: error("Realtime event is not a JSON object")
    }

    private fun pcmFormat(): JsonObject = buildJsonObject {
        put("type", "audio/pcm")
        put("rate", PCM_SAMPLE_RATE_HZ)
    }

    private fun encode(value: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), value)

    private const val PCM_SAMPLE_RATE_HZ = 24_000
}
