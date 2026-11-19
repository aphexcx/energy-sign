package cx.aphex.energysign.beatlinkdata

import android.content.Context
import cx.aphex.energysign.StartAlarm
import cx.aphex.energysign.startupAlarm
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.serialWorker
import cx.aphex.energysign.SignStartup
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.server.engine.ApplicationEngine
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.message.MessageManager
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal const val BEATLINK_PORT = 8080

/** MainApplication owns the only listener. Binding the TCP port and closing Netty are serialized. */
internal class BeatLinkListener(
    context: Context,
    private val dispatch: Executor = serialWorker("beatlink-http"),
    arm: StartAlarm = ::startupAlarm,
) : KoinComponent {
    private val messageManager: MessageManager by inject()
    private val startup = SignStartup { logW(it) }
    private val binding = BoundedStart(arm)
    private val discovery = DnsSdAnnouncer(context, dispatch)
    private var server: ApplicationEngine? = null
    private val queued = AtomicBoolean()
    @Volatile private var closed = false

    fun ensureListening(create: () -> ApplicationEngine = ::createEngine) {
        if (closed || !queued.compareAndSet(false, true)) return
        val job = Runnable {
            queued.set(false)
            if (closed) return@Runnable
            if (server == null) {
                val ticket = binding.begin { startup.failed("BeatLink listener startup", "bind deadline") }
                    ?: return@Runnable
                val engine = startup.optional("BeatLink listener construction", create)
                if (engine == null) {
                    ticket.fail()
                    return@Runnable
                }
                val running = if (ticket.pending() && !closed) {
                    startup.optional("BeatLink listener startup") { engine.start(wait = false) }
                } else null
                if (running == null || closed || !ticket.done()) {
                    ticket.fail()
                    startup.optional("BeatLink listener cleanup") { engine.stop(0, 0) }
                    return@Runnable
                }
                server = engine
                logD("HTTP listener bound on port $BEATLINK_PORT")
            }
            startup.optional("DNS-SD startup") { discovery.start() }
        }
        try {
            dispatch.execute { startup.optional("BeatLink worker", job::run) }
        } catch (failure: Throwable) {
            queued.set(false)
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        binding.reset()
        dispatch.execute {
            discovery.stop()
            val old = server
            server = null
            startup.optional("BeatLink listener cleanup") { old?.stop(0, 0) }
            (dispatch as? java.util.concurrent.ExecutorService)?.shutdown()
        }
    }

    private fun createEngine() = embeddedServer(Netty, BEATLINK_PORT) {
        install(ContentNegotiation) {
            gson {}
        }
//            install(CallLogging)
        routing {
//                get("/") {
//                    logD("GET /")
//                    call.respondText("Hello, world!")
//                }

            get("/messages") {
                logD("GET /messages")
                // as the owner has them now: a message posted just before this is in the list
                val messages = try {
                    withContext(Dispatchers.IO) { messageManager.storedMessages() }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("success" to false, "error" to e.toString()))
                    return@get
                }
                call.respond(messages)
            }
            post("/newUserMessage") {
                val msg: PostedMessage = call.receive()
                logW("Received new POSTed UserMessage: ${msg.message}")
                call.answer {
                    messageManager.processNewUserMessage(msg.message)
                    true
                }
            }
            post("/isGeneratingThought") {
                val msg: SheepIsGeneratingThought = call.receive()
                logW("Received new POSTed generating thought notification: ${msg.isGenerating}")
                call.answer {
                    messageManager.togglePersistentThinkingMessage(msg.isGenerating)
                    true
                }
            }
//                post("/streamPartialSheepThought") {
//                    val msg: PostedMessage = call.receive()7
//                    logW("Received new partial SheepThought: ${msg.message}")
//                    messageManager.processPartialThought(msg.message)
//                    call.respond(mapOf("success" to true))
//                }
            post("/newGPTReply") { // formerly /newSheepThought
                val answer: PostedAIAnswer = call.receive()
                logW("Received new POSTed AI Reply: ${answer}")
                call.answer { messageManager.onNewPostedAIReply(answer) }
            }
            currentTrack(messageManager)
        }

    }

    data class PostedMessage(val message: String)
    data class SheepIsGeneratingThought(val isGenerating: Boolean)
}
