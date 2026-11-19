package cx.aphex.energysign.beatlinkdata

import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.MessageManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * POST /currentTrack: the track that is playing now, or that none is (an empty track).
 *
 * THE ORDER OF TRACK UPDATES IS FIXED HERE, in the first statement, and nowhere else: this is
 * the earliest point the app has. What follows takes time and changes threads (the body is
 * read, a line is logged, the work goes to another dispatcher, a long title is fitted to the
 * sign), so two posts that overlap can finish in either order; the turn each took on arrival
 * is what the owner of the messages goes by. What the app cannot fix: the order in which the
 * server itself hands two requests that arrive at the same moment to this handler.
 *
 * In a file of its own, free of Android, so that a test can serve it and post to it.
 */
fun Route.currentTrack(messageManager: MessageManager) {
    post("/currentTrack") {
        val turn = messageManager.acceptTrackUpdate()
        val track: BeatLinkTrack = call.receive()
        logW("Received new BeatLinkTrack: $track")
        call.answer {
            messageManager.processNowPlayingTrack(track, turn)
            true
        }
    }
}

/**
 * Has the owner of the messages make [change] and answers the client with what happened:
 * success only when the change has been made. [change] returns false when it was refused
 * (a reply with nothing to reply to) and throws when the owner did not get to it.
 * Waits off the server's own threads.
 */
internal suspend fun ApplicationCall.answer(change: () -> Boolean) {
    val done = try {
        withContext(Dispatchers.IO) { change() }
    } catch (e: Exception) {
        logW("A posted change was not made: $e")
        respond(HttpStatusCode.ServiceUnavailable, mapOf("success" to false, "error" to e.toString()))
        return
    }
    respond(if (done) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("success" to done))
}
