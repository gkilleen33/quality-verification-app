package com.qualityverifier.domain

/**
 * How an assessment already in the database was started.
 *
 * Read once when a conversation is opened, so that a report reopened from history knows
 * as much as one the app has just created: which protocol applies, which earlier piece
 * this one can be compared with, and which answers to carry into the next piece.
 *
 * Every field is nullable because every field can genuinely be absent — a row written by
 * an older version, an assessment started from the grid rather than from another one, an
 * intake the customer handed over part way.
 */
data class SessionStart(
    val itemType: ItemType?,
    val previousSessionId: String?,
    val intake: AssessmentContext?,
    /**
     * Where the assessment was made, if the customer left that on and a fix arrived.
     *
     * Carried here rather than passed through ChatService.send because it is written
     * minutes after the conversation begins — the fix resolves while the customer is
     * answering the intake — and this is already read on every turn. A turn sent before
     * the fix landed simply carries null, and the next one carries it.
     */
    val location: LocationFix? = null,
)
