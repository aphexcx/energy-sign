package cx.aphex.energysign.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One reply to one message, from start to end: the prompt of this moment, the provider, the
 * length cap. It never throws, apart from cancellation: whatever goes wrong ends as
 * [AIReplyResult.NoReply], so a reply can never take the app down.
 */
class AIReplyService(
    private val generator: AIReplyGenerator,
    private val log: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
    /** Read for every request, so a schedule replaced on the device is used at once. */
    private val setTimes: () -> SetTimesLoad = { SetTimesLoad.Unusable("no set times were given") },
    /** The UTC date the app was built on; a device clock before it is not believed. */
    private val buildDate: LocalDate? = null,
    /** Takes the keys out of a text. Applied to every reason this service hands on. */
    private val redact: (String) -> String = { it },
) {
    suspend fun replyTo(userText: String): AIReplyResult =
        try {
            val request = AIReplyRequest(AIPrompts.directAnswerSystemPrompt(contextNow()), userText)
            when (val result = generator.generateReply(request)) {
                is AIReplyResult.NoReply -> result.copy(detail = redact(result.detail))
                is AIReplyResult.Reply -> {
                    val fitted = AIReplyLimits.fitToSign(result.text)
                    when {
                        fitted.isBlank() -> noReply(NoReplyReason.EMPTY, "nothing in the reply that the sign can show")
                        else -> {
                            if (fitted.length < result.text.trim().length) {
                                log(
                                    "${generator.name}: reply went from ${result.text.length} to ${fitted.length} characters " +
                                        "(what the sign cannot show was dropped, and it is cut when it is too long)"
                                )
                            }
                            AIReplyResult.Reply(fitted)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // The caller was cancelled: that goes through. A cancellation thrown by a library
            // while nobody cancelled us is a failure like any other; letting it through would end
            // the caller's coroutine without a word, and the sign would wait for ever.
            currentCoroutineContext().ensureActive()
            noReply(NoReplyReason.UNEXPECTED_FAILURE, e.toString())
        } catch (e: Throwable) {
            // The last line of defence, not the error handling: each generator turns the failures
            // it knows into NoReply itself. This is for what nobody expected, an Error from a
            // library on this old Android included, and it must not crash the app.
            noReply(NoReplyReason.UNEXPECTED_FAILURE, e.toString())
        }

    /** The context block for this request. Trouble with the set times costs the block, never the reply. */
    private fun contextNow(): String {
        val instant = now()
        val zone = deviceZone()
        val load = try {
            setTimes()
        } catch (e: Exception) {
            SetTimesLoad.Unusable("the set times could not be read ($e)")
        }
        if (load is SetTimesLoad.Unusable) log("No set times in the prompt: ${load.why}")

        val context = FestivalContext.describe(instant, zone, load, buildDate)
        if (context.isEmpty()) {
            log("The device clock reads $instant, which cannot be right: no date, time or set times in the prompt")
        }
        return context
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        val safeDetail = redact(detail)
        log("${generator.name}: no reply, $reason [$safeDetail]")
        return AIReplyResult.NoReply(reason, safeDetail)
    }
}
