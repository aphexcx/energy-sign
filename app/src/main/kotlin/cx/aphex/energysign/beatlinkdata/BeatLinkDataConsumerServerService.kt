package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import cx.aphex.energysign.Message
import cx.aphex.energysign.ext.logW
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.reactivex.rxjava3.subjects.PublishSubject

class BeatLinkDataConsumerServerService : Service() {
    // Binder given to clients
    private val binder = ServerBinder()

    private val nowPlayingTrackSubject: PublishSubject<BeatLinkTrack> = PublishSubject.create()
    val nowPlayingTrack =
        nowPlayingTrackSubject.distinctUntilChanged() //TODO doesnt actually work because euqals isnt right on usermessage

    val newUserMessages: PublishSubject<Message.UserMessage> = PublishSubject.create()

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
        embeddedServer(Netty, HTTP_PORT) {

            install(ContentNegotiation) {
                gson {}
            }
            routing {
                get("/") {
                    call.respond(mapOf("message" to "Hello world"))
                }
                post("/newUserMessage") {
                    val msg: PostedUserMessage = call.receive()
                    logW("Received new POSTed UserMessage: ${msg.message}")
                    newUserMessages.onNext(Message.UserMessage(msg.message))
                    call.respondText("Received new POSTed UserMessage: ${msg.message}")
                }
                post("/currentTrack") {
                    val track: BeatLinkTrack = call.receive()
                    logW("Received new BeatLinkTrack: $track")
                    nowPlayingTrackSubject.onNext(track)
                    call.respondText("Received new BeatLinkTrack: $track")
                }
            }

        }.start(wait = false)
        super.onCreate()
    }

    data class PostedUserMessage(val message: String)
}
