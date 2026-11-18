package cx.aphex.energysign.ai

import android.content.Context
import android.os.Process
import android.os.SystemClock
import cx.aphex.energysign.BuildConfig
import cx.aphex.energysign.ext.logI
import cx.aphex.energysign.ext.logW
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The replies of this app: [AIReplyRunner] with what the build and the device say. Which provider
 * answers is one build setting (AI_PROVIDER), how hard it thinks another (AI_EFFORT).
 *
 * Making this object costs nothing: the provider's client and everything behind it are built on
 * first use, by the warm-up or by the first call, and both run on background threads.
 *
 * Switch: `adb shell setprop debug.energysign.ai.warmup off` before the app starts leaves the
 * warm-up out, so the first query pays for the slow first use (for a comparison on the sign).
 */
class AIReplies(private val context: Context) : AIReplySource {

    // No key of this build may reach a log line, whatever a library writes into a message.
    private val secrets = Secrets(
        listOf(BuildConfig.ANTHROPIC_API_KEY, BuildConfig.OPENAI_API_KEY, BuildConfig.DEEPGRAM_API_KEY)
    )
    private val warn: (String) -> Unit = secrets.sink { logW(it) }
    private val info: (String) -> Unit = secrets.sink { logI(it) }

    /** The time as the API's server told it. The board's own clock is not believed (see [ServerClock]). */
    private val serverClock = ServerClock { SystemClock.elapsedRealtime() }

    private class Parts(val generator: AIReplyGenerator, val service: AIReplyService)

    private val parts: Parts by lazy {
        val provider = AIProvider.fromSetting(BuildConfig.AI_PROVIDER, warn)
        val builtWithEffort = AIEffort.fromSetting(BuildConfig.AI_EFFORT, warn)
        val effortOverride = File(context.filesDir, AIEffort.OVERRIDE_FILE_NAME)
        val generator = AIReplyGenerators.forProvider(
            provider = provider,
            anthropicApiKey = BuildConfig.ANTHROPIC_API_KEY,
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            effort = { AIEffort.forRequest(builtWithEffort, effortOverride, warn) },
            log = warn,
            info = info,
            serverClock = serverClock,
        )
        info("Replies are written by ${generator.name}, effort ${builtWithEffort.setting} unless ${effortOverride.name} says otherwise")
        val service = AIReplyService(
            generator = generator,
            log = warn,
            // Anthropic tells the time with every response. The OpenAI client does not hand its
            // headers on, so on that path the board's clock has to do (with its lower bounds).
            now = if (provider == AIProvider.ANTHROPIC) ({ serverClock.now() }) else ({ serverClock.now() ?: Instant.now() }),
            setTimes = {
                SetTimes.load(File(context.filesDir, SetTimes.FILE_NAME)) {
                    context.assets.open(SetTimes.FILE_NAME).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            },
            buildDate = try {
                LocalDate.parse(BuildConfig.BUILD_DATE)
            } catch (e: DateTimeParseException) {
                null
            },
            redact = secrets::redact,
        )
        Parts(generator, service)
    }

    private val runner = AIReplyRunner(
        reply = { text -> parts.service.replyTo(text) },
        warmUp = if (warmUpWanted()) ({ parts.generator.warmUp() }) else null,
        log = info,
        startWarmUpThread = { work ->
            Thread({
                // Behind everything else: the sign link is slow enough in the first minute.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "ai-warm-up").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        },
    )

    override fun warmUp(onDone: () -> Unit): Boolean = runner.warmUp(onDone)

    override fun ask(text: String, onResult: (AIReplyResult) -> Unit) = runner.ask(text, onResult)

    private fun warmUpWanted(): Boolean {
        val off = systemProperty(WARM_UP_PROPERTY)?.trim()?.lowercase() in setOf("off", "0", "false", "no")
        if (off) info("The warm-up is switched off ($WARM_UP_PROPERTY)")
        return !off
    }

    private companion object {
        /** System property, read once when the app starts: `off` leaves the warm-up out. */
        const val WARM_UP_PROPERTY = "debug.energysign.ai.warmup"

        /** `android.os.SystemProperties.get(key, default)`: not in the SDK, present on the device. */
        fun systemProperty(key: String): String? =
            try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, key, "") as? String
            } catch (e: Exception) {
                null
            }
    }
}
