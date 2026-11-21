package cx.aphex.energysign.beatlinkdata

import android.content.Context
import cx.aphex.energysign.OptionalStart
import cx.aphex.energysign.StartWorker
import cx.aphex.energysign.optional
import java.util.concurrent.Executor
import io.ktor.server.engine.EmbeddedServer
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

/** The shared worker owns publication. A slow Netty call occupies only its own attempt thread. */
internal class BeatLinkListener(
    context: Context,
    private val worker: StartWorker,
    private val nettyWork: Executor = Executor { task -> Thread(task, "beatlink-netty").apply { isDaemon = true }.start() },
    create: (() -> EmbeddedServer<*, *>)? = null,
) : KoinComponent {
    private val messageManager: MessageManager by inject()
    internal val discovery = DnsSdAnnouncer(context, worker)
    private val createServer = create ?: ::createEngine
    private var server: EmbeddedServer<*, *>? = null
    private val binding = OptionalStart("BeatLink listener startup", worker, { logW(it) }) { attempt ->
        nettyWork.execute {
            var engine: EmbeddedServer<*, *>? = null
            val result = runCatching {
                createServer().also { engine = it; it.start(wait = false) }
            }
            worker.post {
                val running = result.getOrNull()
                if (running != null && attempt.done()) {
                    server = running
                    logD("HTTP listener bound on port $BEATLINK_PORT")
                    discovery.ensure()
                } else {
                    result.exceptionOrNull()?.let { attempt.fail(it.javaClass.name) }
                    engine?.let { stale ->
                        nettyWork.execute { optional("BeatLink listener cleanup", { logW(it) }) { stale.stop(0, 0) } }
                    }
                }
            }
        }
    }

    fun ensureListening() {
        if (server == null) binding.ensure() else discovery.ensure()
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
