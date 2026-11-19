package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import cx.aphex.energysign.SignStartup
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

class BeatLinkDataConsumerServerService : Service(), KoinComponent {
    private val messageManager: MessageManager by inject()

    // Binder given to clients
    private val binder = ServerBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class ServerBinder : Binder() {
        // Return this instance so clients can call public methods
        fun getService(): BeatLinkDataConsumerServerService = this@BeatLinkDataConsumerServerService
    }

    val HTTP_PORT = 8080

    private val startup = SignStartup { logW(it) }
    private var server: ApplicationEngine? = null

    override fun onCreate() {
        super.onCreate()
        ensureListening()
    }

    internal fun ensureListening(create: () -> ApplicationEngine = ::createEngine) {
        if (server != null) return
        val engine = startup.optional("BeatLink listener construction", create) ?: return
        server = startup.optional("BeatLink listener startup") { engine.start(wait = false) }
        if (server == null) startup.optional("BeatLink listener cleanup") { engine.stop(0, 0) }
    }

    override fun onDestroy() {
        val engine = server
        server = null
        startup.optional("BeatLink listener cleanup") { engine?.stop(0, 0) }
        super.onDestroy()
    }

    private fun createEngine() = embeddedServer(Netty, HTTP_PORT) {
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
