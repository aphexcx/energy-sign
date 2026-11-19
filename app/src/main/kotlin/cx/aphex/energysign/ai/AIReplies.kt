package cx.aphex.energysign.ai

import android.content.Context
import android.os.Process
import android.os.SystemClock
import cx.aphex.energysign.BuildConfig
import cx.aphex.energysign.ext.logI
import cx.aphex.energysign.ext.logW
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The replies of this app: [AIReplyRunner] with what the build and the device say. Which provider
 * answers is one build setting (AI_PROVIDER), how hard it thinks another (AI_EFFORT).
 *
 * Making this object costs next to nothing: it starts the one thread that reads the effort
 * file. The provider's client and everything behind it are built on first use, by the warm-up
 * or by the first call, and both run on background threads.
 *
 * It is made on the main thread, on the way to the start of the sign link: making it touches no
 * storage (the effort reader's thread asks for the files directory itself), and it is made
 * through [aiRepliesOrNone] (AppModule), so that whatever fails in here costs the replies of
 * this run and never the sign.
 *
 * Switch: `adb shell setprop debug.energysign.ai.warmup off` before the app starts leaves the
 * warm-up out, so the first query pays for the slow first use (for a comparison on the sign).
 */
class AIReplies(private val context: Context) : AIReplySource {

    /** The ONE rolling-hour wire budget shared by MessageManager's desk and the provider transport. */
    override val hourlyBudget: AIHourlyBudget = AIHourlyBudget(nowMs = { SystemClock.elapsedRealtime() })

    // No key of this build may reach a log line, whatever a library writes into a message.
    private val secrets = Secrets(
        listOf(BuildConfig.ANTHROPIC_API_KEY, BuildConfig.OPENAI_API_KEY, BuildConfig.DEEPGRAM_API_KEY)
    )
    private val warn: (String) -> Unit = secrets.sink { logW(it) }
    private val info: (String) -> Unit = secrets.sink { logI(it) }

    /** The time as the API's server told it. The board's own clock is not believed (see [ServerClock]). */
    private val serverClock = ServerClock { SystemClock.elapsedRealtime() }

    /**
     * How hard the model thinks, for this run of the app: the build's setting, or what the file
     * on the device says. The file is read once, now, by a thread of its own; no reply reads it
     * or waits for it.
     */
    private val effort: ReadOnceAtStart<AIEffort> =
        AIEffort.readOnceAtStart(BuildConfig.AI_EFFORT, filesDir = { context.filesDir }, warn = warn, info = info)

    private class Parts(val generator: AIReplyGenerator, val service: AIReplyService)

    private val parts: Parts by lazy {
        val provider = AIProvider.fromSetting(BuildConfig.AI_PROVIDER, warn)
        val generator = AIReplyGenerators.forProvider(
            provider = provider,
            anthropicApiKey = BuildConfig.ANTHROPIC_API_KEY,
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            effort = effort::get,
            log = warn,
            info = info,
            serverClock = serverClock,
            budget = hourlyBudget,
            nanoTime = { SystemClock.elapsedRealtimeNanos() },
        )
        info("Replies are written by ${generator.name}")
        val service = AIReplyService(
            generator = generator,
            log = warn,
            // Whoever the provider is: the time is what a server told, or it is not known, and
            // then the prompt says nothing about time. The board's own clock is never used.
            now = { serverClock.now() },
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
        reply = { text, deadline, stillWanted, reservation ->
            parts.service.replyTo(text, deadline, stillWanted, reservation)
        },
        warmUp = if (warmUpWanted()) ({ parts.generator.warmUp() }) else null,
        log = info,
        // The same clock as the desk's (AppModule): it goes on while the board sleeps.
        nanoTime = { SystemClock.elapsedRealtimeNanos() },
        warmUpThread = { work ->
            Thread(work, "ai-warm-up").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        },
        // Behind everything else: the sign link is slow enough in the first minute. The runner
        // calls this on the warm-up thread and goes on when Android refuses it.
        onWarmUpThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
    )

    override fun warmUp(onDone: () -> Unit): Boolean = runner.warmUp(onDone)

    override fun ask(
        text: String,
        stillWanted: () -> Boolean,
        reservation: AIHourlyBudget.Reservation?,
        onResult: (AIReplyResult) -> Unit,
    ) = runner.ask(text, stillWanted, reservation, onResult)

    /** Asked on the main thread while this object is made: whatever fails in it, the warm-up is wanted. */
    private fun warmUpWanted(): Boolean =
        try {
            val off = systemProperty(WARM_UP_PROPERTY)?.trim()?.lowercase() in setOf("off", "0", "false", "no")
            if (off) info("The warm-up is switched off ($WARM_UP_PROPERTY)")
            !off
        } catch (e: Throwable) {
            true
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
            } catch (e: Throwable) {
                // An Error too (a class that does not link on this build of Android): no switch.
                null
            }
    }
}
