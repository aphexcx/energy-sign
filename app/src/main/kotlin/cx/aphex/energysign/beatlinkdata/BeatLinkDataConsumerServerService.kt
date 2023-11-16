package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.MessageManager
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.JdkLoggerFactory
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

    override fun onCreate() {
        InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

        embeddedServer(Netty, HTTP_PORT) {

            install(ContentNegotiation) {
                gson {}
            }
            routing {
                get("/messages") {
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
                    messageManager.setGeneratingThought(msg.isGenerating)
                    call.respond(mapOf("success" to true))
                }
                post("/streamPartialSheepThought") {
                    val msg: PostedMessage = call.receive()
                    logW("Received new partial SheepThought: ${msg.message}")
                    messageManager.processPartialThought(msg.message)
                    call.respond(mapOf("success" to true))
                }
                post("/newSheepThought") {
                    val msg: PostedMessage = call.receive()
                    logW("Received new POSTed SheepThought: ${msg.message}")
                    messageManager.processNewSheepThought(msg.message)
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
        super.onCreate()
    }

    data class PostedMessage(val message: String)
    data class SheepIsGeneratingThought(val isGenerating: Boolean)
}
