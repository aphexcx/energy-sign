package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The system prompt: Afik's wording, kept, with two rules added and the context block at the end. */
class AIPromptsTest {

    /** The persona paragraph as it was in OpenAIClient.kt, with the year Afik asked for (2025 became 2026). */
    private val persona = """
You are RaveGPT, an AI attending Dreamstate 2026, a music festival at the Queen Mary in Long Beach, CA. You are running on a big LED sign on my totem at the festival. I am Aphex, a trance DJ and engineer who built the totem you're running on. 
    Other people at the festival can add messages to the sign by coming up to our group, asking to add a message, and then typing it out on a phone we give them. When they send the message, it shows up on the sign immediately. The message scrolls away, and then the sign displays the previous message that was added to it, and so on.
    """

    /** The rules as they were in OpenAIClient.kt, word for word. */
    private val originalRules = """
Here are some rules that are very important to follow:
- Your reply is going to show up on a huge LED sign on the totem, visible to everyone around us at the festival. So keep that in mind. Your response won't just be seen by the person writing the last message - it'll also be seen by every other raver who happens to be looking at you right now.
- Never include any emojis in your output.
- Use really snarky and dry humor, because that's the kind of humor ravers expect. You don't want to sound like a boomer. Your goal is to make as many people around us laugh as possible.
- Generate only ONE thought. It will appear immediately in response to the last message.
- Your response must be only the one funny reply you generate. No other text is allowed.
- If the last message is a question, then try to answer that question.
"""

    private val context = "Current local time: Friday 2026-11-20, 9:30 pm (America/Los_Angeles)."

    @Test
    fun `the persona, the task and the rules keep Afik's wording`() {
        val prompt = AIPrompts.directAnswerSystemPrompt(context)

        assertTrue(prompt.startsWith(persona), prompt)
        assertTrue(prompt.contains("\nYour task is to write one witty reply to the message you receive. \n"), prompt)
        assertTrue(prompt.contains(originalRules), prompt)
        assertFalse(prompt.contains("2025"), prompt)
    }

    @Test
    fun `two rules are added after Afik's - the length limit and the set times`() {
        val prompt = AIPrompts.directAnswerSystemPrompt(context)

        val added = prompt.substringAfter(originalRules).substringBefore(context).trim().lines()
        assertEquals(listOf(AIPrompts.LENGTH_RULE, AIPrompts.SET_TIMES_RULE), added)
        assertEquals(
            "- Keep the reply under 200 characters; it scrolls across the sign one letter at a time.",
            AIPrompts.LENGTH_RULE,
        )
        assertEquals(
            "- You may mention who is playing now or next when it fits. Never invent a set time.",
            AIPrompts.SET_TIMES_RULE,
        )
        assertEquals(200, AIPrompts.MAX_REPLY_CHARS)
    }

    @Test
    fun `the context block of this request closes the prompt`() {
        assertTrue(AIPrompts.directAnswerSystemPrompt(context).endsWith("\n$context"))
        assertTrue(AIPrompts.directAnswerSystemPrompt("something else entirely").endsWith("\nsomething else entirely"))
    }

    @Test
    fun `with no context block, when the clock cannot be believed, the prompt names no date and no time`() {
        val prompt = AIPrompts.directAnswerSystemPrompt("")

        assertTrue(prompt.trimEnd().endsWith(AIPrompts.NO_TIME_RULE), prompt)
        assertFalse(prompt.contains("Current"), prompt)
    }

    // ---- found by the review of round 3

    @Test
    fun `with no context block the prompt does not invite the model to say who is playing - it says the time is not known`() {
        val without = AIPrompts.directAnswerSystemPrompt("")
        val with = AIPrompts.directAnswerSystemPrompt("Current local time: Friday 2026-11-20, 9:30 pm (America/Los_Angeles).")

        assertFalse(without.contains(AIPrompts.SET_TIMES_RULE), without)
        assertTrue(without.contains("- You do not know what time it is or who is playing. Do not state a time or a set time."), without)
        assertTrue(with.contains(AIPrompts.SET_TIMES_RULE), with)
        assertFalse(with.contains(AIPrompts.NO_TIME_RULE), with)
    }
}
