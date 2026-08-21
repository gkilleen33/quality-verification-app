package com.qualityverifier.data.db

import android.net.Uri
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
}
