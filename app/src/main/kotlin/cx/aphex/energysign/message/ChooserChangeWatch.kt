package cx.aphex.energysign.message

/**
 * Notices when what the chooser should show has changed, so the app can push it to the sign
 * (chooser contract VERSION 3: the app raises its hand, the sign does not ask while it scrolls).
 *
 * Everything that can change the stored messages, the chooser index or the chooser mode runs
 * inside [watch]. [view] is what the chooser shows at that moment, null outside chooser mode.
 * When the text or the count differ after the outermost [watch] from before it, [onChange] is
 * called once: entering chooser mode, another message, another count, and leaving it. A command
 * that changes nothing the sign shows (`!next` on the last message) calls nothing, so the sign's
 * scroll is not restarted for it.
 *
 * Not thread-safe: the caller runs every [watch] under one lock (MessageManager's).
 */
class ChooserChangeWatch(
    private val view: () -> Message.Chooser?,
    private val onChange: () -> Unit,
) {
    private var depth = 0

    fun <T> watch(change: () -> T): T {
        val outermost = depth == 0
        val before = if (outermost) shown() else null
        depth++
        try {
            return change()
        } finally {
            depth--
            if (outermost && shown() != before) onChange()
        }
    }

    /** The two fields the sign draws, and the checksum covers. */
    private fun shown(): Pair<String, String>? = view()?.let { it.str to it.flashy }
}
