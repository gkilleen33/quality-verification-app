package com.qualityverifier.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("item_type_id") val itemTypeId: String = "",
    /** The id the phone generated. Doubles as the idempotency key for this turn. */
    @SerialName("message_id") val messageId: String = "",
    val text: String = "",
    /**
     * Content hashes of photos already uploaded via /v1/blobs. Hashes, not bytes: the
     * phone stops re-uploading the same nine photos on every turn, which is the whole
     * saving on the expensive leg of the journey.
     */
    val blobs: List<String> = emptyList(),
    @SerialName("previous_session_id") val previousSessionId: String? = null,
    @SerialName("intake_answers") val intakeAnswers: String? = null,
)

@Serializable
data class ChatResponse(
    @SerialName("message_id") val messageId: String,
    val text: String,
)

/** Which photos the server does not have, so the phone can upload them and retry. */
@Serializable
data class MissingBlobsResponse(
    val error: String = "missing_blobs",
    val missing: List<String>,
)

/**
 * One increment of a streamed reply.
 *
 * A JSON object rather than the bare text, because SSE is a line protocol and a reply
 * contains newlines: sending the text raw would split one delta across several `data:`
 * lines and hand the client the job of putting it back together. Encoded as a JSON string
 * it cannot contain a literal newline at all.
 */
@Serializable
data class ChatDelta(val text: String)

/**
 * The end of a streamed reply, carrying the whole thing.
 *
 * Deliberately redundant with the deltas the client already has. The client commits this
 * text, not its own accumulation, so what it stores is exactly what the server stored —
 * a dropped delta becomes a cosmetic glitch during the wait rather than a conversation
 * whose two copies disagree from then on.
 */
@Serializable
data class ChatDone(
    @SerialName("message_id") val messageId: String,
    val text: String,
)
