package cx.aphex.energysign.beatlinkdata

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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
import io.reactivex.rxjava3.subjects.BehaviorSubject

class BeatLinkDataConsumerServerService : Service() {
    // Binder given to clients
    private val binder = ServerBinder()

    val nowPlayingTrack: BehaviorSubject<BeatLinkTrack> = BehaviorSubject.create()

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
                post("/currentTrack") {
                    val track: BeatLinkTrack = call.receive()
                    nowPlayingTrack.onNext(track)
                    call.respondText("Received: $track")
                }
            }
        }.start(wait = false)
        super.onCreate()
    }
}
