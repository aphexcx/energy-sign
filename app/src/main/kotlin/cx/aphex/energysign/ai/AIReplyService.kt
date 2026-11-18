package cx.aphex.energysign.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One reply to one message, from start to end: the prompt of this moment, the provider, the
 * clean-up for the sign. It never throws, apart from cancellation: whatever goes wrong ends as
 * [AIReplyResult.NoReply], so a reply can never take the app down.
 *
 * A reply comes out whole or not at all: see [AIReplyLimits.fitToSign].
 */
class AIReplyService(
    private val generator: AIReplyGenerator,
    private val log: (String) -> Unit,
    /**
     * The time, or null while it is not known. With no time the prompt makes no claim about time:
     * no date, no clock, no set times. A wrong time is worse than none.
     */
    private val now: () -> Instant? = Instant::now,
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
    /** Read for every request, so a schedule replaced on the device is used at once. */
    private val setTimes: () -> SetTimesLoad = { SetTimesLoad.Unusable("no set times were given") },
    /** The UTC date the app was built on; a time before it is not believed. */
    private val buildDate: LocalDate? = null,
    /** Takes the keys out of a text. Applied to every reason this service hands on. */
    private val redact: (String) -> String = { it },
    /** How long the set times may take to read before the prompt goes without them. */
    private val setTimesLimit: Duration = Duration.ofSeconds(5),
) {
    /** Where the set times are read, apart from the call: a read that hangs is left behind here. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    suspend fun replyTo(userText: String): AIReplyResult =
        try {
            if (userText.isBlank()) {
                noReply(NoReplyReason.NOTHING_TO_ASK, "the text is blank")
            } else {
                val request = AIReplyRequest(AIPrompts.directAnswerSystemPrompt(contextNow()), userText)
                when (val result = generator.generateReply(request)) {
                    is AIReplyResult.NoReply -> result.copy(detail = redact(result.detail))
                    is AIReplyResult.Reply -> {
                        val fitted = AIReplyLimits.fitToSign(result.text)
                        if (fitted.isBlank()) {
                            noReply(NoReplyReason.EMPTY, "nothing in the reply that the sign can show, or not one whole word that fits")
                        } else {
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
            // The caller was cancelled (its deadline passed): that goes through. A cancellation
            // thrown by a library while nobody cancelled us is a failure like any other; letting
            // it through would end the caller's coroutine without a word.
            currentCoroutineContext().ensureActive()
            noReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name)
        } catch (e: Throwable) {
            // The last line of defence, not the error handling: each generator turns the failures
            // it knows into NoReply itself. This is for what nobody expected, an Error from a
            // library on this old Android included, and it must not crash the app. Only the kind
            // of failure is logged: what a library writes into its message is not ours to print.
            noReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name)
        }

    /**
     * The context block for this request, or "" for none. Whatever goes wrong in here costs the
     * block, never the reply, and it cannot hold the reply up for long:
     * - the set times are read with a time limit ([setTimesLimit]). Storage that hangs is left
     *   behind and the prompt goes without them;
     * - when the time is not known, the provider is asked for it once (a few seconds at most);
     * - the clock is read LAST, so nothing above can make the time in the prompt an old one.
     */
    private suspend fun contextNow(): String =
        try {
            val reading = background.async {
                try {
                    setTimes()
                } catch (e: Exception) {
                    SetTimesLoad.Unusable("the set times could not be read (${e.javaClass.simpleName})")
                }
            }
            val load = withTimeoutOrNull(setTimesLimit.toMillis()) { reading.await() }
                ?: SetTimesLoad.Unusable("reading the set times took longer than ${setTimesLimit.seconds} s")
            if (load is SetTimesLoad.Unusable) log("No set times in the prompt: ${load.why}")

            if (now() == null) withContext(NonCancellable + Dispatchers.IO) { generator.syncClock() }
            val instant = now()
            if (instant == null) {
                log("The time is not known yet: no date, time or set times in the prompt")
                ""
            } else {
                FestivalContext.describe(instant, deviceZone(), load, buildDate).also {
                    if (it.isEmpty()) log("The time reads $instant, which cannot be right: no date, time or set times in the prompt")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("The context for the prompt could not be built (${e.javaClass.simpleName}): the prompt goes without it")
            ""
        }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        val safeDetail = redact(detail)
        log("${generator.name}: no reply, $reason [$safeDetail]")
        return AIReplyResult.NoReply(reason, safeDetail)
    }
}
