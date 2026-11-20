package cx.aphex.energysign.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One reply to one message, from start to end: the prompt of this moment, the provider, the
 * clean-up for the sign. It never throws, apart from cancellation: whatever goes wrong ends as
 * [AIReplyResult.NoReply], so a reply can never take the app down.
 *
 * ONE time limit for all of it: the [Deadline] of the query, which the caller sets when the work
 * starts. Every step in here gets what is left of it and no step has a clock of its own.
 *
 * A reply comes out whole or not at all: see [AIReplyLimits.fitToSign]. It comes out ready for
 * the sign: nothing is left to do with its text for whoever puts it into the list.
 *
 * AN ANSWER THAT WAS DELIVERED IS NEVER BOUGHT AGAIN. Once the generator has handed over an
 * answer, nothing that fails in here can end as a result that lets the query be asked again:
 * the failure ends the query ([NoReplyReason.FAILED_AFTER_ANSWER]). The log is never such a
 * failure: a log line that cannot be written is lost, and nothing else.
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
    setTimes: () -> SetTimesLoad = { SetTimesLoad.Unusable("no set times were given") },
    /** The UTC date the app was built on; a time before it is not believed. */
    private val buildDate: LocalDate? = null,
    /** Takes the keys out of a text. Applied to every reason this service hands on. */
    private val redact: (String) -> String = { it },
    /** How long the set times may take to read before the prompt goes without them. */
    private val setTimesLimit: Duration = Duration.ofSeconds(5),
    /** How long asking for the time may take. */
    private val timeCheckLimit: Duration = Duration.ofSeconds(5),
    /** Starts the one thread that reads the set times. Only tests set this. */
    startReadThread: (Runnable) -> Unit = { Thread(it, "ai-set-times").apply { isDaemon = true }.start() },
    /** Makes an answer fit the sign. Only tests set this. */
    private val fit: (String) -> String = AIReplyLimits::fitToSign,
) {
    /**
     * The set times are read apart from the call, by one thread at most: a read that hangs is
     * waited for by the next query too, it is not started a second time.
     */
    private val setTimesReader = OneReadAtATime(
        read = {
            try {
                setTimes()
            } catch (e: Exception) {
                SetTimesLoad.Unusable("the set times could not be read (${e.javaClass.simpleName})")
            }
        },
        startThread = startReadThread,
    )

    suspend fun replyTo(
        userText: String,
        deadline: Deadline = Deadline.after(AIReplyRunner.QUERY_BUDGET),
        stillWanted: () -> Boolean = { true },
        reservation: AIHourlyBudget.Reservation? = null,
    ): AIReplyResult =
        try {
            if (userText.isBlank()) {
                noReply(NoReplyReason.NOTHING_TO_ASK, "the text is blank")
            } else {
                val request = AIReplyRequest(
                    AIPrompts.directAnswerSystemPrompt(contextNow(deadline)), userText, deadline, stillWanted, reservation,
                )
                when (val result = generator.generateReply(request)) {
                    is AIReplyResult.NoReply -> result.copy(detail = redact(result.detail))
                    is AIReplyResult.Reply -> fitted(result)
                }
            }
        } catch (e: CancellationException) {
            // The caller was cancelled: that goes through. A cancellation thrown by a library
            // while nobody cancelled us is a failure like any other; letting it through would
            // end the caller's coroutine without a word.
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
     * - the set times are waited for [setTimesLimit] at most, and never longer than the query has
     *   left. Storage that hangs is left to its one thread and the prompt goes without them;
     * - when the time is not known, a provider is asked for it once ([timeCheckLimit] at most);
     * - the clock is read LAST, so nothing above can make the time in the prompt an old one.
     */
    private suspend fun contextNow(deadline: Deadline): String =
        try {
            val load = setTimesReader.await(deadline.atMost(setTimesLimit).toMillis())
                ?: SetTimesLoad.Unusable("reading the set times took longer than ${setTimesLimit.seconds} s")
            if (load is SetTimesLoad.Unusable) say("No set times in the prompt: ${load.why}")

            if (now() == null) {
                withContext(NonCancellable + Dispatchers.IO) { generator.syncClock(deadline.atMost(timeCheckLimit)) }
            }
            val instant = now()
            if (instant == null) {
                say("The time is not known yet: no date, time or set times in the prompt")
                ""
            } else {
                FestivalContext.describe(instant, deviceZone(), load, buildDate).also {
                    if (it.isEmpty()) say("The time reads $instant, which cannot be right: no date, time or set times in the prompt")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("The context for the prompt could not be built (${e.javaClass.simpleName}): the prompt goes without it")
            ""
        }

    /**
     * [answer] for the sign. The provider HAS answered when this is called, so whatever fails
     * in here, an Error included, ends the query: it never becomes a reason to ask again.
     */
    private fun fitted(answer: AIReplyResult.Reply): AIReplyResult =
        try {
            val fitted = fit(answer.text)
            if (fitted.isBlank()) {
                noReply(NoReplyReason.EMPTY, "nothing in the reply that the sign can show, or not one whole word that fits")
            } else {
                if (fitted.length < answer.text.trim().length) {
                    say(
                        "${generator.name}: reply went from ${answer.text.length} to ${fitted.length} characters " +
                            "(what the sign cannot show was dropped, and it is cut when it is too long)"
                    )
                }
                AIReplyResult.Reply(fitted)
            }
        } catch (e: Throwable) {
            noReply(NoReplyReason.FAILED_AFTER_ANSWER, e.javaClass.name)
        }

    /** The result for "no reply", with its line in the log. It never throws: see [say]. */
    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        val safeDetail = try {
            redact(detail)
        } catch (e: Throwable) {
            "" // what could not be cleaned of keys is not handed on
        }
        say("${generator.name}: no reply, $reason [$safeDetail]")
        return AIReplyResult.NoReply(reason, safeDetail)
    }

    /** A line for the log. A log that fails changes no result: there is nowhere to report it. */
    private fun say(line: String) {
        try {
            log(line)
        } catch (e: Throwable) {
            // nothing
        }
    }
}
