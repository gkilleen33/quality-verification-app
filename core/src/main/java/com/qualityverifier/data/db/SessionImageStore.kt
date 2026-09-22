package com.qualityverifier.data.db

import android.net.Uri
import com.qualityverifier.images.ImageQuality
import java.io.File

/**
 * The image operations the chat screen needs.
 *
 * Narrower than [ImageFileStore] on purpose: depending on the interface keeps
 * [com.qualityverifier.ui.chat.ChatViewModel] free of an Android `Context`, so its
 * behaviour — including whether a walkthrough starts itself — is unit-testable.
 */
interface SessionImageStore {
    fun newImageFile(sessionId: String): File
    fun importFromUri(sessionId: String, uri: Uri): File?
    fun normaliseInPlace(file: File): Boolean
    fun delete(file: File)

    /**
     * How readable a just-captured photo is. Null when it could not be measured, which
     * is treated as "no complaint" rather than as a problem — the check exists to catch
     * obviously unusable photos, not to gate on its own reliability.
     */
    fun measureQuality(file: File): ImageQuality?
}
