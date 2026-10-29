package com.qualityverifier.server.routes

import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.WorkerSlot
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
    /**
     * Fundi Bora's piece. Links a re-assessment to the first assessment of the same
     * physical piece, which is what the two quality rates walk. Ignored on Kagua's
     * endpoint, and only honoured for a piece this account owns.
     */
    @SerialName("piece_id") val pieceId: String? = null,
    /**
     * What the maker calls the piece, given at the start of its first assessment.
     * Stored with the piece when the piece is created and ignored afterwards: a
     * rename goes through PUT /v1/fundi/pieces/{id}, so a later turn repeating an
     * old name cannot undo one.
     */
    @SerialName("piece_label") val pieceLabel: String? = null,
    /**
     * Who worked on the piece, from the roster, each on an optional part. Stored with
     * the session when it is created and ignored afterwards; an edit goes through
     * PUT /v1/sessions/{id}/workers.
     */
    val workers: List<WorkerSlotDto> = emptyList(),
    /**
     * This turn was written by the app on the user's behalf — an intake, a plan
     * submission — rather than typed. Stored, and handed back through sync, so a phone
     * that rebuilds its history can still show it compactly. See V18.
     */
    val composed: Boolean = false,
    /**
     * Where the assessment was made, when the customer agreed to record it. Optional and
     * expected to be absent: the setting can be off, and indoors a fix often never
     * arrives at all.
     *
     * Never reaches Claude. ChatStore.history selects id, role, text and created_at from
     * `messages` and nothing else, so a column on `sessions` cannot enter the request —
     * which is a property of that query, and the one place to check if this is ever
     * doubted.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyMetres: Double? = null,
) {
    /**
     * The fix, if all three arrived and each is in range.
     *
     * Same rule as the registration payload: the three travel together or not at all,
     * because a point without its accuracy is a false precision. A partial or absurd set
     * is dropped rather than refused — this is optional data attached to a turn somebody
     * spent minutes on, and failing their assessment over a bad coordinate would be the
     * wrong trade.
     */
    val locationOrNull: SessionLocation?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            val accuracy = accuracyMetres ?: return null
            val sane = lat in -90.0..90.0 && lon in -180.0..180.0 &&
                accuracy > 0 && accuracy <= MAX_ACCURACY_METRES
            return if (sane) SessionLocation(lat, lon, accuracy) else null
        }
}

/**
 * Coarser than this names a district, not a shop. Matches the users table and the CHECK
 * on sessions.location_accuracy_m.
 *
 * Top-level rather than in a companion: a @Serializable class's companion is the
 * generated serializer's, and declaring a private one of our own makes it unreachable by
 * reflection — which surfaces as every request to this route failing to decode.
 */
private const val MAX_ACCURACY_METRES = 5000.0

/** A validated point, ready to store. */
data class SessionLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
)

/** One "Who worked on this?" answer as the phone sends it. No id means the manager. */
@Serializable
data class WorkerSlotDto(
    val id: String? = null,
    val name: String? = null,
    val part: String? = null,
) {
    /**
     * Why this slot cannot be stored, or null. Checked at the route so a bad slot is a
     * 400 rather than a constraint violation inside the transaction that creates the
     * session.
     */
    val problem: String?
        get() = when {
            id != null && !isUuid(id) -> "workers[].id must be a uuid"
            id == null && name != null -> "workers[].name needs an id"
            name != null && WorkerNames.length(name.trim()) !in 1..WorkerNames.MAX_LENGTH ->
                "workers[].name must be 1 to ${WorkerNames.MAX_LENGTH} characters"
            part != null && WorkPart.fromId(part) == null -> "workers[].part is not a known part"
            else -> null
        }

    fun toDomain() = WorkerSlot(
        workerId = id,
        name = name?.trim(),
        part = part?.let(WorkPart::fromId),
    )
}

/** The bound on a worker's name, as V23's `workers_name_length` counts it. */
object WorkerNames {
    const val MAX_LENGTH = 60

    fun length(name: String): Int = name.codePointCount(0, name.length)
}

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
