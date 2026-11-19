package cx.aphex.energysign.message

/**
 * Notices when what the chooser should show has changed, so the app can push it to the sign
 * (chooser contract VERSION 3.1: the app gets the sign's attention, the sign need not ask while it scrolls).
 *
 * Every change the owner is handed (a command, a new message, a reply, a key that stores a
 * message) runs inside [watch]. (Answering a request of the sign moves the index and the list
 * too, but only outside chooser mode, where the chooser shows nothing.) [view] is what the
 * chooser shows at that moment, null outside chooser mode.
 * When the text or the count differ after the outermost [watch] from before it, [onChange] is
 * called once: entering chooser mode, another message, another count, and leaving it. A command
 * that changes nothing the sign shows (`!next` on the last message) calls nothing, so the sign's
 * scroll is not restarted for it.
 *
 * Not thread-safe: every [watch] runs on the one thread that owns the message state.
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

/**
 * THE ONLY DOOR from MessageManager to the owner of the message state. Whatever is handed over,
 * and however the caller waits for it (not at all: [post]; for its result: [ask]; for which of
 * three things became of it: [askFor]), the owner runs it under [watch], so that a push follows
 * when what the chooser shows has changed.
 *
 * MessageManager holds this and not the owner's queue: it has no way to hand the owner a change
 * past the watch, whatever entry point is added to it. (Before, each entry point wrapped its
 * change itself, and one that did not, the adb hook's, said "ok" to a step in the chooser that
 * no push followed.) What is handed over that changes nothing (a question about the list) is
 * watched too; that costs two looks at what the chooser shows.
 */
internal class WatchedOwner(private val owner: OwnerQueue, private val watch: ChooserChangeWatch) {
    /** The one place a change is put under the watch. */
    private fun <T> watched(change: () -> T): () -> T = { watch.watch(change) }

    /** See [OwnerQueue.post]. */
    fun post(change: () -> Unit) = owner.post(watched(change))

    /** See [OwnerQueue.ask]. The owner asking itself is answered at once, under the watch like everything else. */
    fun <T> ask(change: () -> T): T = owner.ask(change = watched(change))

    /** See [OwnerQueue.askFor]. */
    fun <T> askFor(change: () -> T): OwnerQueue.Asked<T> = owner.askFor(change = watched(change))
}
