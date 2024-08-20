package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.MessageManager
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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

    private lateinit var server: io.ktor.server.engine.ApplicationEngine

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {

        logD("Starting embedded server on port $HTTP_PORT")
        server = embeddedServer(Netty, HTTP_PORT) {
            install(ContentNegotiation) {
                gson {}
            }
//            install(CallLogging)
            routing {
                get("/") {
                    logD("GET /")
                    call.respondText("Hello, world!")
                }

                get("/messages") {
                    logD("GET /messages")
                    call.respond(messageManager.msgRepo.marqueeMessages)
                }
                post("/newUserMessage") {
                    val msg: PostedMessage = call.receive()
                    logW("Received new POSTed UserMessage: ${msg.message}")
                    messageManager.processNewUserMessage(msg.message)
                    call.respond(mapOf("success" to true))
                }
                post("/isGeneratingThought") {
                    val msg: SheepIsGeneratingThought = call.receive()
                    logW("Received new POSTed generating thought notification: ${msg.isGenerating}")
                    messageManager.togglePersistentThinkingMessage(msg.isGenerating)
                    call.respond(mapOf("success" to true))
                }
//                post("/streamPartialSheepThought") {
//                    val msg: PostedMessage = call.receive()7
//                    logW("Received new partial SheepThought: ${msg.message}")
//                    messageManager.processPartialThought(msg.message)
//                    call.respond(mapOf("success" to true))
//                }
                post("/newGPTReply") { // formerly /newSheepThought
                    val msg: PostedMessage = call.receive()
                    logW("Received new POSTed GPT Reply: ${msg.message}")
                    messageManager.onNewPostedGPTReply(msg.message)
                    call.respond(mapOf("success" to true))
                }
                post("/currentTrack") {
                    val track: BeatLinkTrack = call.receive()
                    logW("Received new BeatLinkTrack: $track")
                    messageManager.processNowPlayingTrack(track)
                    call.respond(mapOf("success" to true))
                }
            }

        }.start(wait = false)
        logD("Embedded server started")
        super.onCreate()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    data class PostedMessage(val message: String)
    data class SheepIsGeneratingThought(val isGenerating: Boolean)
}
