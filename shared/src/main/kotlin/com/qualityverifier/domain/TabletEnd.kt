package com.qualityverifier.domain

/**
 * How a shop tablet's copy of an assessment ended (release scope row 12, V39).
 *
 * Kagua on a shop's tablet keeps no customer's assessment: it deletes its own copy when one of
 * these happens, and tells the server which, so research can tell a customer who read the
 * result and pressed Done from one who walked away. The server keeps every assessment either
 * way. Mirrored by `sessions_tablet_ended_known` in V39; the vocabulary test holds the two
 * together.
 */
enum class TabletEnd(val id: String) {
    /** "Done: delete this assessment", or Back to the start screen: the customer ended it. */
    DONE("done"),

    /** The app went to the background with the screen on: Home, another app, a notification. */
    LEFT_APP("left_app"),

    /** The screen went off or locked. */
    SCREEN_LOCK("screen_lock"),

    /** Five minutes with no touch on the screen. */
    IDLE("idle");

    companion object {
        fun fromId(id: String?): TabletEnd? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}
