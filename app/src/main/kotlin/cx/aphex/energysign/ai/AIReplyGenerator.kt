package cx.aphex.energysign.ai

import java.io.File
import java.io.IOException

/** What a generator is asked: the system prompt and the text the person typed. */
data class AIReplyRequest(val systemPrompt: String, val userText: String)

/** What a generator answers: the reply text, or why there is none. */
sealed class AIReplyResult {
    data class Reply(val text: String) : AIReplyResult()
    data class NoReply(val reason: NoReplyReason, val detail: String = "") : AIReplyResult()
}

/**
 * Why there is no reply.
 *
 * [isFailure] separates the two things the sign already does when no reply comes. A failure of
 * the call (no network, a timeout, an API error) is shown the way an error always was. The others
 * are the provider answering with nothing usable, or not being asked at all: the thinking notice
 * goes away and nothing else happens, as with a blank answer.
 */
enum class NoReplyReason(val isFailure: Boolean) {
    /** No API key in this build. Nothing was sent. */
    MISSING_KEY(false),

    /** The provider declined (stop reason "refusal"), and so did the fallback model. */
    REFUSED(false),

    /** The answer had no text in it. */
    EMPTY(false),

    /** The answer was cut off by the token limit; half a line is not shown. */
    TRUNCATED(false),

    /** A response that is not an answer: an unknown stop reason, or a body that is not a message. */
    UNEXPECTED_RESPONSE(false),

    /** No complete answer within the bounded wait. */
    TIMEOUT(true),

    /** No route, no DNS, connection refused or dropped. */
    NETWORK(true),

    /** The server certificate is not valid at the time the device believes it is: check the clock. */
    CERTIFICATE_CLOCK(true),

    /** The server certificate does not chain to a root this device trusts. */
    CERTIFICATE_UNTRUSTED(true),

    /** Another TLS failure. */
    TLS(true),

    /** HTTP 429. */
    RATE_LIMITED(true),

    /** HTTP 5xx, overloaded included. */
    SERVER_ERROR(true),

    /** HTTP 401 or 403: the key is wrong, revoked, or may not use this model. */
    KEY_REJECTED(true),

    /** HTTP 400, 404 or 422: the request itself is wrong (a bug here, or a model id that is gone). */
    REQUEST_REJECTED(true),

    /** Any other failure the provider's client reports. */
    PROVIDER_ERROR(true),

    /** Something no generator is expected to throw. Caught at the edge so the app keeps running. */
    UNEXPECTED_FAILURE(true),
}

/**
 * Writes one reply to one message. Implementations do their waiting off the main thread and
 * report every failure they know as [AIReplyResult.NoReply].
 */
interface AIReplyGenerator {
    /** For the log: which provider and model this is. */
    val name: String

    suspend fun generateReply(request: AIReplyRequest): AIReplyResult
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
                warn("AI_PROVIDER=\"$wanted\" names no provider (anthropic, openai); using ${it.setting}")
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
 * level names. The OpenAI generator does not use it.
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

        /** Absent or blank is [DEFAULT]. A value that names no level is [DEFAULT] too, with a warning. */
        fun fromSetting(setting: String?, warn: (String) -> Unit = {}): AIEffort {
            val wanted = setting.orEmpty().trim()
            if (wanted.isEmpty()) return DEFAULT
            return named(wanted) ?: DEFAULT.also {
                warn("AI_EFFORT=\"$wanted\" names no level (low, medium, high, xhigh, max); using ${it.setting}")
            }
        }

        /**
         * The effort for the request being made now: the level [overrideFile] names, when the
         * file is there and names one, otherwise [buildSetting].
         */
        fun forRequest(buildSetting: AIEffort, overrideFile: File, warn: (String) -> Unit): AIEffort {
            if (!overrideFile.isFile) return buildSetting
            val wanted = try {
                overrideFile.readText().trim()
            } catch (e: IOException) {
                warn("${overrideFile.name} cannot be read (${e.message}); effort stays ${buildSetting.setting}")
                return buildSetting
            }
            if (wanted.isEmpty()) return buildSetting
            return named(wanted) ?: buildSetting.also {
                warn("${overrideFile.name} says \"${wanted.take(20)}\", which names no level; effort stays ${it.setting}")
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
    ): AIReplyGenerator = when (provider) {
        AIProvider.ANTHROPIC -> AnthropicReplyGenerator(apiKey = anthropicApiKey, log = log, effort = effort)
        AIProvider.OPENAI -> OpenAIReplyGenerator(apiKey = openAiApiKey, log = log)
    }
}
