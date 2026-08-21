package com.qualityverifier.data.db

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.qualityverifier.data.chat.ImageBytesSource
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.images.lumaOf
import com.qualityverifier.images.measureQuality as measureLuma
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Owns image files on disk. Photos live under `filesDir/images/<sessionId>/` rather
 * than in the database: blobs bloat Room, and keeping files separate lets Phase 2
 * upload them independently of the message rows.
 *
 * Images are normalised on the way in — EXIF rotation applied, long edge capped at
 * [MAX_EDGE_PX], re-encoded as JPEG at [JPEG_QUALITY]. A 12MP camera frame drops from
 * several megabytes to a couple hundred kilobytes, which is the difference between a
 * usable and unusable app on metered mobile data. Rotation matters for accuracy too:
 * Claude cannot judge whether a table is level from a sideways photo.
 */
class ImageFileStore(private val context: Context) : ImageBytesSource, SessionImageStore {

    private val root: File get() = File(context.filesDir, "images")

    fun sessionDir(sessionId: String): File =
        File(root, sessionId).apply { mkdirs() }

    /** An empty file for the camera to write into, plus the file itself. */
    override fun newImageFile(sessionId: String): File =
        File(sessionDir(sessionId), "${UUID.randomUUID()}.jpg")

    fun relativePathOf(file: File): String =
        file.absolutePath.removePrefix(context.filesDir.absolutePath).trimStart('/')

    fun resolve(relativePath: String): File = File(context.filesDir, relativePath)

    fun deleteSessionImages(sessionId: String) {
        runCatching { File(root, sessionId).deleteRecursively() }
    }

    override fun delete(file: File) {
        runCatching { file.delete() }
    }

    /**
     * Deletes image directories whose session no longer exists. Covers the case where
     * a user attached photos, then left the chat without ever sending — no session row
     * was created, so nothing else would ever clean the files up.
     */
    fun pruneOrphans(keepSessionIds: Set<String>) {
        runCatching {
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in keepSessionIds) dir.deleteRecursively()
            }
        }
    }

    /**
     * Copies a gallery/document [uri] into the session directory, normalising it.
     * Returns null if the image could not be read or decoded.
     */
    override fun importFromUri(sessionId: String, uri: Uri): File? = try {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val normalised = normalise(raw) ?: return null
        newImageFile(sessionId).apply { writeBytes(normalised) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not import image from $uri", e)
        null
    }

    /**
     * Rewrites a just-captured camera file in place, normalised. Returns false and
     * leaves the file alone if it could not be decoded.
     */
    override fun normaliseInPlace(file: File): Boolean = try {
        val normalised = normalise(file.readBytes())
        if (normalised == null) false else { file.writeBytes(normalised); true }
    } catch (e: Exception) {
        Log.w(TAG, "Could not normalise ${file.name}", e)
        false
    }

    /** JPEG bytes ready to be base64-encoded into an API request. */
    override fun bytesForUpload(file: File): ByteArray? = try {
        file.readBytes().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read ${file.name}", e)
        null
    }

    /**
     * Decodes a small copy of [file] and measures it. Deliberately tiny: sharpness is a
     * ratio, so a thumbnail answers the question, and doing this on a full frame would
     * make the shutter feel slow on a 2GB phone.
     */
    override fun measureQuality(file: File): ImageQuality? {
        return try {
            val raw = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= QUALITY_EDGE_PX) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            measureLuma(lumaOf(pixels), width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Could not measure ${file.name}", e)
            null
        }
    }

    private fun normalise(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        bitmap = applyExifRotation(bitmap, raw)
        bitmap = scaleToMaxEdge(bitmap)

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, raw: ByteArray): Bitmap {
        val degrees = try {
            val orientation = raw.inputStream().use { ExifInterface(it) }
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longEdge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    companion object {
        /** Anthropic downsamples above roughly this edge length, so sending more is waste. */
        const val MAX_EDGE_PX = 1568
        const val JPEG_QUALITY = 80

        /** Long edge used for the blur and brightness check only. */
        private const val QUALITY_EDGE_PX = 320

        internal fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
            return sample
        }

        private const val TAG = "ImageFileStore"
    }
}
