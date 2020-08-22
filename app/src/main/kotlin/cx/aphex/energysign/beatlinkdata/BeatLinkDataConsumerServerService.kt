package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import cx.aphex.energysign.ext.logW
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.JdkLoggerFactory
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject

class BeatLinkDataConsumerServerService : Service() {

    // Binder given to clients
    private val binder = ServerBinder()

    private val nowPlayingTrackSubject: PublishSubject<BeatLinkTrack> = PublishSubject.create()
    val nowPlayingTrack: Observable<BeatLinkTrack> =
        nowPlayingTrackSubject.distinctUntilChanged()

    val newUserMessages: PublishSubject<String> = PublishSubject.create()

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
                get("/") {
                    call.respond(mapOf("message" to "Hello world"))
                }
                post("/newUserMessage") {
                    val msg: PostedUserMessage = call.receive()
                    logW("Received new POSTed UserMessage: ${msg.message}")
                    newUserMessages.onNext(msg.message)
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
