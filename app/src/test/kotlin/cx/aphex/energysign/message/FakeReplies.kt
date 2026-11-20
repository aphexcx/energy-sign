package cx.aphex.energysign.message

import cx.aphex.energysign.ai.AIHourlyBudget
import cx.aphex.energysign.ai.AIReplyResult
import cx.aphex.energysign.ai.AIReplySource

/** Stands in for the provider: it records what was asked, and the test answers by hand, when it likes. */
class FakeReplies : AIReplySource {
    class Asked(val text: String, val stillWanted: () -> Boolean, val answer: (AIReplyResult) -> Unit)

    /** Set before the MessageManager is built: whether there is a warm-up to wait for. */
    var hasWarmUp: Boolean = false

    /** Call it to end the warm-up. */
    var endWarmUp: () -> Unit = {}

    val asked = mutableListOf<Asked>()

    override fun warmUp(onDone: () -> Unit): Boolean {
        if (!hasWarmUp) return false
        endWarmUp = onDone
        return true
    }

    override fun ask(
        text: String,
        stillWanted: () -> Boolean,
        reservation: AIHourlyBudget.Reservation?,
        onResult: (AIReplyResult) -> Unit,
    ) {
        asked.add(Asked(text, stillWanted) { result ->
            // Stand-in for the HTTP wire gate: outcomes that represent a sent request stamp the
            // reservation, while pre-send outcomes (nothingWasSent) leave it unstamped.
            if (result is AIReplyResult.Reply || !(result as AIReplyResult.NoReply).reason.nothingWasSent) {
                reservation?.stamp()
            }
            onResult(result)
        })
    }
}
