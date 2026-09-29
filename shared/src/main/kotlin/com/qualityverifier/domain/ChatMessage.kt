package com.qualityverifier.domain

enum class Role { USER, ASSISTANT }

/**
 * One turn in a conversation. [attachments] are images the user included with this
 * turn; assistant turns never carry attachments.
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = 0L,
    /**
     * Written by the app on the user's behalf — an intake turn, a plan submission —
     * rather than typed. The model reads these in full; a screen may show them compactly.
     */
    val composed: Boolean = false,
)

/**
 * An image stored on the device. [path] is an absolute path under the app's private
 * files directory — images are kept as files rather than database blobs so the
 * conversation table stays small and Phase 2 can upload them independently.
 */
data class Attachment(
    val id: String,
    val path: String,
    val mimeType: String = "image/jpeg",
)
