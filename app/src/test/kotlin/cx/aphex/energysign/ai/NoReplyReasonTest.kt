package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What an HTTP status that is not a success means: one table for both providers (class C). */
class NoReplyReasonTest {

    @Test
    fun `a status means the same whichever provider sent it and whatever its client makes of it`() {
        val expected = mapOf(
            400 to NoReplyReason.REQUEST_REJECTED,
            401 to NoReplyReason.KEY_REJECTED,
            403 to NoReplyReason.KEY_REJECTED,
            404 to NoReplyReason.REQUEST_REJECTED,
            413 to NoReplyReason.REQUEST_REJECTED,
            422 to NoReplyReason.REQUEST_REJECTED,
            429 to NoReplyReason.RATE_LIMITED,
            500 to NoReplyReason.SERVER_ERROR,
            503 to NoReplyReason.SERVER_ERROR,
            529 to NoReplyReason.SERVER_ERROR,
            // Nothing this app knows a better word for: it may pass, and the pause applies.
            307 to NoReplyReason.PROVIDER_ERROR,
            402 to NoReplyReason.PROVIDER_ERROR,
            408 to NoReplyReason.PROVIDER_ERROR,
            409 to NoReplyReason.PROVIDER_ERROR,
        )

        assertEquals(expected, expected.keys.associateWith { NoReplyReason.forHttpStatus(it) })
    }

    @Test
    fun `a status that says the request is wrong, or the key, ends the query - one that may pass does not`() {
        for (status in listOf(400, 401, 403, 404, 413, 422)) {
            assertEquals(false, NoReplyReason.forHttpStatus(status).askAgain, "HTTP $status")
        }
        for (status in listOf(408, 409, 429, 500, 529)) {
            assertEquals(true, NoReplyReason.forHttpStatus(status).askAgain, "HTTP $status")
        }
    }
}
