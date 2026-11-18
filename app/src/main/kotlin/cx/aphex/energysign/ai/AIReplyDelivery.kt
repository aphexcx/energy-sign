package cx.aphex.energysign.ai

import java.util.Collections
import java.util.IdentityHashMap

/** A call that failed, for the error LiveData the sign already listens to. */
class AIReplyFailedException(val reason: NoReplyReason, detail: String) :
    Exception(if (detail.isBlank()) reason.name else "${reason.name}: $detail")

/**
 * How a result reaches the sign. There are exactly the three things MessageManager already did
 * with the old replies: show an answer, do nothing for a blank answer, show the error notice.
 */
sealed class AIReplyDelivery {
    data class Answer(val text: String) : AIReplyDelivery()

    /** The thinking notice goes away and nothing else happens. */
    object Quiet : AIReplyDelivery()

    /** The call failed: the thinking notice goes away and the sign shows its error notice. */
    data class Failure(val error: AIReplyFailedException) : AIReplyDelivery()
}

fun AIReplyResult.toDelivery(): AIReplyDelivery = when (this) {
    is AIReplyResult.Reply -> AIReplyDelivery.Answer(text)
    is AIReplyResult.NoReply ->
        if (reason.isFailure) AIReplyDelivery.Failure(AIReplyFailedException(reason, detail)) else AIReplyDelivery.Quiet
}

/**
 * The queries that are being answered right now, by identity. The sign asks for a reply every
 * time a query comes round in the list; while its first request is still out, asking again would
 * only buy a second answer to the same message.
 */
class InFlightQueries {
    private val queries: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    /** True when [query] was not being answered and now is; false when a request for it is already out. */
    fun start(query: Any): Boolean = queries.add(query)

    fun finish(query: Any) {
        queries.remove(query)
    }
}
