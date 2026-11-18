package cx.aphex.energysign.ai

/** A reply written elsewhere and posted to the app over HTTP (`/newGPTReply`). */
data class PostedAIAnswer(val answer: String, val inReplyTo: String)
