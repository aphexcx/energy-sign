package cx.aphex.energysign.message

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [Message.MessageSerializer] used to fail on every icon: its ICON branch was compiled with a
 * cast to `java.lang.Void`, so a list that held an icon could not be read at all. That is why
 * the ads were lost at every start of the app.
 */
class MessageSerializerIconTest {

    /** The Gson and the list type the app read `ads.json` with. */
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Message::class.java, Message.MessageSerializer())
        .create()
    private val listOfMessages = object : TypeToken<List<Message>>() {}.type

    @Test
    fun `a list that ends in an icon is read`() {
        val json = """[
            {"dly":2000,"scroll":false,"str":"HELLO","type":"C","b":128,"g":0,"r":255},
            {"dly":1000,"str":"WORLD","type":"O","b":59,"g":212,"r":254},
            {"str":"1","type":"I","b":59,"g":212,"r":254}
        ]"""

        val messages: List<Message> = gson.fromJson(json, listOfMessages)

        assertEquals(3, messages.size)
        assertIs<Message.ColorMessage.ChonkySlide>(messages[0])
        assertIs<Message.ColorMessage.OneByOneMessage>(messages[1])
        val icon = assertIs<Message.ColorMessage.IconInvaders.Enemy1>(messages[2])
        assertEquals("1", icon.str)
        assertEquals(listOf(254, 212, 59), listOf(icon.r, icon.g, icon.b))
    }

    @Test
    fun `every icon is read as its own class`() {
        val expected = mapOf(
            "1" to Message.ColorMessage.IconInvaders.Enemy1::class,
            "2" to Message.ColorMessage.IconInvaders.Enemy2::class,
            "X" to Message.ColorMessage.IconInvaders.Explosion::class,
            "A" to Message.ColorMessage.IconInvaders.ANJUNA::class,
            "B" to Message.ColorMessage.IconInvaders.BAAAHS::class,
            "D" to Message.ColorMessage.IconInvaders.DREAMSTATE::class,
            "E" to Message.ColorMessage.IconInvaders.EDC::class,
        )
        expected.forEach { (str, iconClass) ->
            val message: Message = gson.fromJson("""{"str":"$str","type":"I","b":3,"g":2,"r":1}""", Message::class.java)
            assertEquals(iconClass, message::class, "icon $str")
        }
    }
}
