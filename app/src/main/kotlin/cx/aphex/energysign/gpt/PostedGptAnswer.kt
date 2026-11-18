package cx.aphex.energysign.gpt

/** A reply written elsewhere and posted to the app over HTTP (`/newGPTReply`). */
data class PostedGptAnswer(val answer: String, val inReplyTo: String)
