package cx.aphex.energysign.message

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class MessageSerializationDetailedTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_class"
    }


    // Color functions are now part of the ColorMessage class

    private fun assertJsonContains(jsonString: String, expectedValues: Map<String, Any>) {
        val jsonMap = json.decodeFromString<Map<String, JsonElement>>(jsonString)
        expectedValues.forEach { (key, value) ->
            val jsonValue = jsonMap[key]
            when (value) {
                is String -> assertEquals(value, (jsonValue as? JsonPrimitive)?.content, "Mismatch for key: $key")
                is Number -> assertEquals(
                    value.toString(),
                    (jsonValue as? JsonPrimitive)?.content,
                    "Mismatch for key: $key"
                )

                is Boolean -> assertEquals(value, (jsonValue as? JsonPrimitive)?.boolean, "Mismatch for key: $key")
                else -> fail("Unexpected value type for key: $key")
            }
        }
    }

    private inline fun <reified T : Message> testSerialization(message: T, expectedValues: Map<String, Any>) {
        val jsonString = json.encodeToString(message)
        println(jsonString)  // For debugging
        assertJsonContains(jsonString, expectedValues)
    }

    private inline fun <reified T : Message> testDeserialization(jsonString: String, expectedMessage: T) {
        val deserializedMessage = json.decodeFromString<T>(jsonString)
        assertEquals(expectedMessage, deserializedMessage)
    }

    @Test
    fun testGPTThinkingSerialization() {
        val message = Message.ColorMessage.GPTThinking(
            str = "...",
            color = 0xFF0000,
            delayMs = 250,
            showCaret = true,
            thinkDots = 3,
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
        val jsonString = json.encodeToString(message)
        testSerialization(message, expectedValues)
//        testDeserialization(jsonString, message)
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
        val jsonString = json.encodeToString(message)
        testSerialization(message, expectedValues)
        testDeserialization(jsonString, message)
    }

    @Test
    fun testChonkySlideSerialization() {
        val message = Message.ColorMessage.ChonkySlide(
            str = "Chonky",
            color = 0x00FF00,
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
        val jsonString = json.encodeToString(message)
//        testDeserialization(jsonString, message)
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
        val jsonString = json.encodeToString(message)
        testSerialization(message, expectedValues)
        testDeserialization(jsonString, message)
    }

    // No tearDown needed
}
