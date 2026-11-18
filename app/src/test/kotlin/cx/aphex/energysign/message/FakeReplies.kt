package cx.aphex.energysign.message

import cx.aphex.energysign.ai.AIReplyResult
import cx.aphex.energysign.ai.AIReplySource

/** Stands in for the provider: it records what was asked, and the test answers by hand, when it likes. */
class FakeReplies : AIReplySource {
    class Asked(val text: String, val answer: (AIReplyResult) -> Unit)

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

    override fun ask(text: String, onResult: (AIReplyResult) -> Unit) {
        asked.add(Asked(text, onResult))
    }
}
