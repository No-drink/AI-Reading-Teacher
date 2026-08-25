package com.joey.aireadingteacher.provider.openai

import com.joey.aireadingteacher.tutor.PlaybackPosition
import com.joey.aireadingteacher.tutor.RealtimePayloadLimits
import java.util.Base64
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIRealtimeProtocolTest {
    @Test
    fun `builds secure websocket URL and encodes model`() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1",
            OpenAIRealtimeProtocol.websocketUrl(
                "https://api.openai.com/v1/",
                "gpt-realtime-2.1",
            ),
        )
    }

    @Test
    fun `rejects insecure base URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAIRealtimeProtocol.websocketUrl("http://api.openai.com/v1", "model")
        }
    }

    @Test
    fun `builds WebRTC calls URL from secure base URL`() {
        assertEquals(
            "https://api.openai.com/v1/realtime/calls",
            OpenAIRealtimeProtocol.webRtcCallUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `WebRTC session delegates audio formats and keeps interruption enabled`() {
        val session = OpenAIRealtimeProtocol.parseObject(
            OpenAIRealtimeProtocol.webRtcSessionConfig("test-model", "Be quiet"),
        )
        val audio = session.getValue("audio").jsonObject
        val input = audio.getValue("input").jsonObject
        val output = audio.getValue("output").jsonObject
        val turnDetection = input.getValue("turn_detection").jsonObject

        assertEquals("realtime", session.getValue("type").jsonPrimitive.content)
        assertEquals("test-model", session.getValue("model").jsonPrimitive.content)
        assertEquals("Be quiet", session.getValue("instructions").jsonPrimitive.content)
        assertEquals("audio", session.getValue("output_modalities").jsonArray.single().jsonPrimitive.content)
        assertFalse(input.containsKey("format"))
        assertFalse(output.containsKey("format"))
        assertEquals("marin", output.getValue("voice").jsonPrimitive.content)
        assertTrue(turnDetection.getValue("create_response").jsonPrimitive.content.toBoolean())
        assertTrue(turnDetection.getValue("interrupt_response").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `session enables PCM audio VAD and interruption`() {
        val event = OpenAIRealtimeProtocol.parseObject(
            OpenAIRealtimeProtocol.sessionUpdate("test-model", "Be quiet"),
        )
        val session = event.getValue("session").jsonObject
        val audio = session.getValue("audio").jsonObject
        val input = audio.getValue("input").jsonObject
        val output = audio.getValue("output").jsonObject
        val turnDetection = input.getValue("turn_detection").jsonObject

        assertEquals("session.update", event.getValue("type").jsonPrimitive.content)
        assertEquals("test-model", session.getValue("model").jsonPrimitive.content)
        assertEquals("audio", session.getValue("output_modalities").jsonArray.single().jsonPrimitive.content)
        assertEquals("audio/pcm", input.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("24000", input.getValue("format").jsonObject.getValue("rate").jsonPrimitive.content)
        assertEquals("audio/pcm", output.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("24000", output.getValue("format").jsonObject.getValue("rate").jsonPrimitive.content)
        assertEquals("server_vad", turnDetection.getValue("type").jsonPrimitive.content)
        assertTrue(turnDetection.getValue("create_response").jsonPrimitive.content.toBoolean())
        assertTrue(turnDetection.getValue("interrupt_response").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `image updates context without creating a response`() {
        val message = OpenAIRealtimeProtocol.imageMessage("data:image/jpeg;base64,abc")
        val event = OpenAIRealtimeProtocol.parseObject(message)
        val content = event.getValue("item").jsonObject.getValue("content").jsonArray

        assertEquals("conversation.item.create", event.getValue("type").jsonPrimitive.content)
        assertEquals("input_text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("input_image", content[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(message.contains("response.create"))
    }

    @Test
    fun `maximum screenshot stays below WebRTC event safety limit`() {
        val base64 = Base64.getEncoder().encodeToString(
            ByteArray(RealtimePayloadLimits.MAX_IMAGE_BYTES),
        )
        val event = OpenAIRealtimeProtocol.imageMessage("data:image/jpeg;base64,$base64")

        assertTrue(event.toByteArray().size <= RealtimePayloadLimits.MAX_EVENT_BYTES)
    }

    @Test
    fun `truncate preserves playback position`() {
        val event = OpenAIRealtimeProtocol.parseObject(
            OpenAIRealtimeProtocol.truncate(PlaybackPosition("item-1", 2, 1_250)),
        )
        assertEquals("item-1", event.getValue("item_id").jsonPrimitive.content)
        assertEquals("2", event.getValue("content_index").jsonPrimitive.content)
        assertEquals("1250", event.getValue("audio_end_ms").jsonPrimitive.content)
    }
}
