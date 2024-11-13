package cx.aphex.energysign.message

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class MessageSerializationDetailedTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Message::class.java, Message.MessageSerializer())
        .create()

    // Color functions are now part of the ColorMessage class

    private fun assertJsonContains(jsonString: String, expectedValues: Map<String, Any>) {
        val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
        expectedValues.forEach { (key, value) ->
            val jsonValue = jsonObject.get(key)
            when (value) {
                is String -> assertEquals(value, jsonValue?.asString, "Mismatch for key: $key")
                is Number -> assertEquals(value.toString(), jsonValue?.asString, "Mismatch for key: $key")
                is Boolean -> assertEquals(value, jsonValue?.asBoolean, "Mismatch for key: $key")
                else -> fail("Unexpected value type for key: $key")
            }
        }
    }

    private inline fun <reified T : Message> testSerialization(message: T, expectedValues: Map<String, Any>) {
        val jsonString = gson.toJson(message)
        println(jsonString)  // For debugging
        assertJsonContains(jsonString, expectedValues)
    }

    private inline fun <reified T : Message> testDeserialization(jsonString: String, expectedMessage: T) {
        val deserializedMessage = gson.fromJson(jsonString, T::class.java)
        assertEquals(expectedMessage, deserializedMessage)
    }

    @Test
    fun testGPTThinkingSerialization() {
        val message = Message.ColorMessage.GPTThinking(
            str = "...",
            colorCycle = 0xFF0000,
            delayMs = 250,
            showCaret = true,
            thinkDots = 3
        )
        val expectedValues = mapOf(
            "type" to "C",
            "str" to "...",
            "dly" to 250,
            "showCaret" to true,
            "thinkDots" to 3,
            "r" to 255,
            "g" to 0,
            "b" to 0
        )
        testSerialization(message, expectedValues)
        testDeserialization(gson.toJson(message), message)
    }

    @Test
    fun testOneByOneMessageSerialization() {
        val message = Message.ColorMessage.OneByOneMessage(
            str = "Test",
            color = 0xFF0000,
            delayMs = 1000
        )
        val expectedValues = mapOf(
            "type" to "O",
            "str" to "Test",
            "dly" to 1000,
            "r" to 255,
            "g" to 0,
            "b" to 0
        )
        testSerialization(message, expectedValues)
        testDeserialization(gson.toJson(message), message)
    }

    @Test
    fun testChonkySlideSerialization() {
        val message = Message.ColorMessage.ChonkySlide(
            str = "Chonky",
            colorCycle = 0x00FF00,
            delayMs = 750,
            shouldScrollToLastLetter = true
        )
        val expectedValues = mapOf(
            "type" to "C",
            "str" to "Chonky",
            "dly" to 750,
            "r" to 0,
            "g" to 255,
            "b" to 0,
            "scroll" to true
        )
        testSerialization(message, expectedValues)
        testDeserialization(gson.toJson(message), message)
    }

    @Test
    fun testIconInvadersSerialization() {
        val message = Message.ColorMessage.IconInvaders.Enemy1(0xFFFF00)
        val expectedValues = mapOf(
            "type" to "I",
            "str" to "1",
            "r" to 255,
            "g" to 255,
            "b" to 0
        )
        testSerialization(message, expectedValues)
        testDeserialization(gson.toJson(message), message)
    }

    // No tearDown needed
}
