package cx.aphex.energysign.ai

import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * The ONE time limit of the work for one query. It is set when the work starts and handed down
 * to every step: reading the set times, asking for the time, building the client, the request.
 * No step has a clock of its own that could disagree with it, and nothing stands behind it that
 * could end a request the provider is still answering.
 */
class Deadline private constructor(private val endNanos: Long, private val nanoTime: () -> Long) {
    /** What is left, in milliseconds; never below zero. */
    fun remainingMs(): Long = ((endNanos - nanoTime()) / 1_000_000).coerceAtLeast(0)

    /** [limit], or what is left when that is less. */
    fun atMost(limit: Duration): Duration = Duration.ofMillis(minOf(limit.toMillis(), remainingMs()))

    companion object {
        fun after(budget: Duration, nanoTime: () -> Long = System::nanoTime): Deadline =
            Deadline(nanoTime() + budget.toNanos(), nanoTime)
    }
}

/**
 * What a generator is asked: the system prompt and the text the person typed, the [deadline] of
 * the query it is for, and [stillWanted], which the generator asks as the last thing before the
 * request goes out: false when the query was deleted in the meantime, and then nothing is sent.
 */
data class AIReplyRequest(
    val systemPrompt: String,
    val userText: String,
    val deadline: Deadline = Deadline.after(AIReplyRunner.QUERY_BUDGET),
    val stillWanted: () -> Boolean = { true },
)

/** What a generator answers: the reply text, or why there is none. */
sealed class AIReplyResult {
    data class Reply(val text: String) : AIReplyResult()
    data class NoReply(val reason: NoReplyReason, val detail: String = "") : AIReplyResult()
}

/**
 * Why there is no reply.
 *
 * Two questions decide what happens to the query, and each reason answers both:
 * - [askAgain]: can asking the same thing again later give another outcome? When it cannot (a
 *   refusal, an empty answer, a rejected request), the query is retired: it stays on the sign as
 *   the plain message it was and is never sent again. When it can, it is asked again when it
 *   comes round, a limited number of times.
 * - [backOff]: did the provider fail to give an answer at all? Then nothing is asked of it for a
 *   while, however many queries are waiting.
 */
enum class NoReplyReason(val askAgain: Boolean, val backOff: Boolean) {
    /** No API key in this build. Nothing was sent. */
    MISSING_KEY(askAgain = false, backOff = false),

    /** There is nothing to ask: the text is blank once it is what the sign can show. Nothing was sent. */
    NOTHING_TO_ASK(askAgain = false, backOff = false),

    /**
     * The query was not confirmed as still wanted right before the request: it was deleted, or
     * the owner of the messages did not answer. Nothing was sent.
     */
    WITHDRAWN(askAgain = true, backOff = false),

    /** The provider declined (stop reason "refusal"), and so did the fallback model. */
    REFUSED(askAgain = false, backOff = false),

    /** The answer had no text in it that the sign can show. */
    EMPTY(askAgain = false, backOff = false),

    /** The answer was cut off, by the token limit or by a stream that ended early; half a line is not shown. */
    TRUNCATED(askAgain = false, backOff = false),

    /** A complete answer of a kind this app does not handle (an unknown stop reason). */
    UNEXPECTED_RESPONSE(askAgain = false, backOff = false),

    /** HTTP 200 with a body that is not a message at all (a captive portal, a proxy page). */
    NOT_A_MESSAGE(askAgain = true, backOff = true),

    /** No complete answer before the deadline of the call. */
    TIMEOUT(askAgain = true, backOff = true),

    /** No route, no DNS, connection refused or dropped. */
    NETWORK(askAgain = true, backOff = true),

    /** The server certificate is not valid at the time the device believes it is: check the clock. */
    CERTIFICATE_CLOCK(askAgain = true, backOff = true),

    /** The server certificate does not chain to a root this device trusts (or something sits in between). */
    CERTIFICATE_UNTRUSTED(askAgain = true, backOff = true),

    /** Another TLS failure. */
    TLS(askAgain = true, backOff = true),

    /** HTTP 429. */
    RATE_LIMITED(askAgain = true, backOff = true),

    /** HTTP 5xx, overloaded included. */
    SERVER_ERROR(askAgain = true, backOff = true),

    /** HTTP 401 or 403: the key is wrong, revoked, or may not use this model. */
    KEY_REJECTED(askAgain = false, backOff = true),

    /** HTTP 400, 404, 413 or 422: the request itself is wrong (a bug here, or a model id that is gone). */
    REQUEST_REJECTED(askAgain = false, backOff = true),

    /** Any other failure the provider's client reports. */
    PROVIDER_ERROR(askAgain = true, backOff = true),

    /** Something no generator is expected to throw. Caught at the edge so the app keeps running. */
    UNEXPECTED_FAILURE(askAgain = true, backOff = true);

    /**
     * Whether a request went out at all. When none did, the provider has neither answered nor
     * failed: such a result says nothing about how the provider is doing.
     */
    val nothingWasSent: Boolean get() = this == MISSING_KEY || this == NOTHING_TO_ASK || this == WITHDRAWN

    companion object {
        /**
         * What an HTTP status that is not a success means, for both providers. Decided from the
         * number alone: which exception a client library makes of a status, and what it reads
         * from the body, change nothing here.
         */
        fun forHttpStatus(status: Int): NoReplyReason = when {
            status == 401 || status == 403 -> KEY_REJECTED
            status == 400 || status == 404 || status == 413 || status == 422 -> REQUEST_REJECTED
            status == 429 -> RATE_LIMITED
            status >= 500 -> SERVER_ERROR
            else -> PROVIDER_ERROR
        }
    }
}

/**
 * Writes one reply to one message. Implementations do their waiting off the main thread and
 * report every failure they know as [AIReplyResult.NoReply].
 */
interface AIReplyGenerator {
    /** For the log: which provider and model this is. */
    val name: String

    /**
     * One request to the provider, ended by [AIReplyRequest.deadline] at the latest. It returns
     * when the request has really ended: a caller that stops waiting does not cut it short
     * (see the generators for what can and cannot be cancelled).
     */
    suspend fun generateReply(request: AIReplyRequest): AIReplyResult

    /**
     * Gets the slow first use out of the way before a person waits for it: called once, soon after
     * the app starts, on a background thread of low priority. It blocks until it is done. It
     * must cost no money. The default is to do nothing.
     */
    fun warmUp() {}

    /**
     * Learns the time from a provider when it is not known yet. Free, never throws, and returns
     * within [limit].
     */
    fun syncClock(limit: Duration) {}
}

/**
 * Where replies come from, as the owner of the messages sees it: something that is asked and
 * answers later, on some other thread. See [AIReplyRunner].
 */
interface AIReplySource {
    /**
     * Starts the warm-up when there is one, and returns at once. True: [onDone] will be called
     * once, on some other thread, and nothing may be asked before that. False: there is no
     * warm-up and [onDone] is never called.
     */
    fun warmUp(onDone: () -> Unit): Boolean

    /**
     * Asks for one reply to [text] and returns at once. [onResult] is called exactly once, on
     * some other thread, whatever happens. [stillWanted] is asked on that other thread, as the
     * last thing before the request goes out; when it says no, nothing is sent.
     */
    fun ask(text: String, stillWanted: () -> Boolean = { true }, onResult: (AIReplyResult) -> Unit)
}

/** Which provider writes the replies. One setting: `AI_PROVIDER` in local.properties. */
enum class AIProvider(val setting: String) {
    ANTHROPIC("anthropic"),
    OPENAI("openai");

    companion object {
        val DEFAULT = ANTHROPIC

        /** Absent or blank is [DEFAULT]. A value that names no provider is [DEFAULT] too, with a warning. */
        fun fromSetting(setting: String?, warn: (String) -> Unit = {}): AIProvider {
            val wanted = setting.orEmpty().trim()
            if (wanted.isEmpty()) return DEFAULT
            return values().firstOrNull { it.setting.equals(wanted, ignoreCase = true) } ?: DEFAULT.also {
                // The value itself is never logged: a key pasted on the wrong line would be printed.
                warn("AI_PROVIDER is set to something that names no provider (anthropic, openai); using ${it.setting}")
            }
        }
    }
}

/**
 * How hard the model thinks before it answers; on Claude it is the only depth control. More
 * effort is a slower reply, not a longer one: the length is set in the prompt.
 *
 * One build setting, `AI_EFFORT` in local.properties, and one file on the device that overrides
 * it without a rebuild: [OVERRIDE_FILE_NAME] in the app's files directory, holding one of the
 * level names. The file is read once, when the app starts (see [ReadOnceAtStart]): a change to
 * it takes effect at the next start. The OpenAI generator does not use it.
 */
enum class AIEffort(val setting: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        /** Afik's choice after timing the levels with real messages (about 4 s a reply). */
        val DEFAULT = HIGH

        const val OVERRIDE_FILE_NAME = "ai_effort.txt"
        private const val MAX_OVERRIDE_FILE_BYTES = 64L

        /** Absent or blank is [DEFAULT]. A value that names no level is [DEFAULT] too, with a warning. */
        fun fromSetting(setting: String?, warn: (String) -> Unit = {}): AIEffort {
            val wanted = setting.orEmpty().trim()
            if (wanted.isEmpty()) return DEFAULT
            return named(wanted) ?: DEFAULT.also {
                warn("AI_EFFORT is set to something that names no level (low, medium, high, xhigh, max); using ${it.setting}")
            }
        }

        /**
         * The effort for this run of the app: the level [overrideFile] names, when the file is
         * there and names one, otherwise [buildSetting]. This reads storage: never on the path
         * of a reply.
         */
        fun fromOverrideFile(buildSetting: AIEffort, overrideFile: File, warn: (String) -> Unit): AIEffort {
            if (!overrideFile.isFile) return buildSetting
            // A level name is a few letters: a bigger file is not one, and is not read.
            if (overrideFile.length() > MAX_OVERRIDE_FILE_BYTES) {
                warn("${overrideFile.name} is too big to be a level name; effort stays ${buildSetting.setting}")
                return buildSetting
            }
            val wanted = try {
                overrideFile.readText().trim()
            } catch (e: IOException) {
                warn("${overrideFile.name} cannot be read (${e.javaClass.simpleName}); effort stays ${buildSetting.setting}")
                return buildSetting
            }
            if (wanted.isEmpty()) return buildSetting
            return named(wanted) ?: buildSetting.also {
                // What the file holds is never logged: it may be anything, a key included.
                warn("${overrideFile.name} names no level (low, medium, high, xhigh, max); effort stays ${it.setting}")
            }
        }

        private fun named(name: String): AIEffort? = values().firstOrNull { it.setting.equals(name, ignoreCase = true) }
    }
}

object AIReplyGenerators {
    /** The generator for [provider]. There is no fallback from one provider to the other. */
    fun forProvider(
        provider: AIProvider,
        anthropicApiKey: String,
        openAiApiKey: String,
        effort: () -> AIEffort,
        log: (String) -> Unit,
        info: (String) -> Unit = log,
        serverClock: ServerClock? = null,
        /** Only tests set these, to local fakes of the two APIs. */
        anthropicBaseUrl: String? = null,
        openAiBaseUrl: String? = null,
    ): AIReplyGenerator = when (provider) {
        AIProvider.ANTHROPIC -> AnthropicReplyGenerator(
            apiKey = anthropicApiKey, log = log, info = info, effort = effort, serverClock = serverClock,
            baseUrl = anthropicBaseUrl,
        )

        AIProvider.OPENAI -> OpenAIReplyGenerator(
            apiKey = openAiApiKey, log = log, baseUrl = openAiBaseUrl,
            // The OpenAI client hands no response headers on, so it cannot tell the time. When
            // this build has an Anthropic key too, the free request of that API tells it;
            // otherwise (that generator asks nobody without a key) the time stays unknown and
            // the prompt says nothing about time.
            timeFrom = if (serverClock == null) null else AnthropicReplyGenerator(
                apiKey = anthropicApiKey, log = log, info = info, serverClock = serverClock, baseUrl = anthropicBaseUrl,
            ),
        )
    }
}
