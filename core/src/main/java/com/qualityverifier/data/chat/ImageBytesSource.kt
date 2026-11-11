package com.qualityverifier.data.chat

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Supplies the bytes to upload for an attached image.
 *
 * Narrower than the full [com.qualityverifier.data.db.ImageFileStore] on purpose:
 * the chat service only ever needs to read bytes, and this keeps request building
 * testable without an Android [android.content.Context].
 */
interface ImageBytesSource {
    fun bytesForUpload(file: File): ByteArray?

    /**
     * The camera metadata kept with [file] (see [com.qualityverifier.domain.PhotoMeta]), as
     * it may be sent now, or null. Defaulted so a source that keeps none needs no change.
     */
    fun photoMetaFor(file: File): JsonObject? = null
}
