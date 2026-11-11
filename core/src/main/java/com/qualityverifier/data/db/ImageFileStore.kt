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
 * [MAX_EDGE_PX], re-encoded as JPEG at [JPEG_QUALITY], camera metadata left behind. A
 * 12MP camera frame drops from several megabytes to around one, uploaded once however
 * many turns carry it, which matters on metered mobile data. Rotation matters for
 * accuracy too: Claude cannot judge whether a table is level from a sideways photo.
 * Kagua and Fundi Bora both route every new photo through here.
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

    /**
     * Every photo, for a different account signing in. A failure is not fatal: with the
     * rows gone nothing can show a leftover file, and [pruneOrphans] removes it later.
     */
    fun deleteAll() {
        runCatching { root.deleteRecursively() }
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
    } catch (e: OutOfMemoryError) {
        // A photo this phone cannot hold at the cap is a photo that could not be read,
        // not a crash in the middle of an assessment.
        Log.w(TAG, "Out of memory importing $uri", e)
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
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory normalising ${file.name}", e)
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

    /**
     * The camera or gallery file as it will be stored, hashed and sent: upright, capped
     * at [MAX_EDGE_PX], JPEG at [JPEG_QUALITY], and carrying none of the camera's
     * metadata. Only ever applied to a photo as it arrives. A photo already on the phone
     * keeps its bytes, because its SHA-256 names it on the server and its bytes sit in
     * cached conversation prefixes.
     */
    private fun normalise(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Read from the original bytes, before anything is decoded or re-encoded: the
        // re-encoded file has no EXIF left to read. Any camera metadata kept with the
        // photo (v0.4.0 PR 7) belongs here too, read from this same ExifInterface.
        val exif = runCatching { raw.inputStream().use { ExifInterface(it) } }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val upright = decodeUpright(raw, bounds.outWidth, bounds.outHeight, orientation)
            ?: return null

        val encoded = ByteArrayOutputStream().use { out ->
            // Recycled even if the encoder runs out of memory, so the caller's "could not
            // be read" does not leave the frame for the next retake to work around.
            try {
                upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } finally {
                upright.recycle()
            }
            out.toByteArray()
        }
        return stripMetadata(encoded)
    }

    /**
     * Decodes at the coarsest power-of-two sampling that keeps the long edge at or above
     * [MAX_EDGE_PX], so the final scale is a downscale of less than half, then turns the
     * frame upright and caps it.
     *
     * At 2,576 px that is no sampling at all for a 12 MP frame: 4,000 x 3,000 decodes to
     * 48 MB, against 12 MB at the old 1,568 px cap, and the transform allocates its output
     * while that is still held. On a phone that cannot spare it, at either step, both are
     * tried once more at twice the sampling. Retrying only the decode was not enough: a
     * phone that could just decode the frame ran out in the transform on every retake. A
     * photo a little under the cap is better than "That photo could not be read".
     */
    private fun decodeUpright(raw: ByteArray, width: Int, height: Int, orientation: Int): Bitmap? {
        val sample = sampleSizeFor(width, height)
        return try {
            uprightAt(raw, sample, orientation)
        } catch (e: OutOfMemoryError) {
            // Whatever the first attempt allocated is recycled by now (see uprightAndCapped),
            // so the retry does not start with the full frame still held.
            Log.w(TAG, "Out of memory at 1/$sample for ${width}x$height; trying 1/${sample * 2}")
            try {
                uprightAt(raw, sample * 2, orientation)
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    private fun uprightAt(raw: ByteArray, sample: Int, orientation: Int): Bitmap? {
        val bitmap = decodeAt(raw, sample) ?: return null
        return uprightAndCapped(bitmap, orientation)
    }

    private fun decodeAt(raw: ByteArray, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
    }

    /**
     * Rotation and the size cap in one transform, so only one more bitmap is allocated,
     * at the output size. Done as two steps, the rotation copied the full decoded frame
     * before the scale threw most of it away: twice the decode's memory at the peak.
     *
     * Out of memory here recycles [bitmap] before rethrowing, so the caller's retry at
     * twice the sampling decodes into the memory this frame held.
     */
    private fun uprightAndCapped(bitmap: Bitmap, orientation: Int): Bitmap {
        val turn = uprightTurn(orientation)
        val scale = capScale(bitmap.width, bitmap.height)
        if (turn == UprightTurn.NONE && scale == 1f) return bitmap
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (turn.mirrored) postScale(-1f, 1f)
            postRotate(turn.degrees)
        }
        val out = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw e
        }
        if (out != bitmap) bitmap.recycle()
        return out
    }

    /** How to turn a decoded frame upright: a mirror first, if any, then a clockwise turn. */
    internal data class UprightTurn(val degrees: Float, val mirrored: Boolean) {
        companion object {
            val NONE = UprightTurn(0f, mirrored = false)
        }
    }

    companion object {
        /**
         * The longest edge `claude-sonnet-5-5` reads at full detail (Anthropic's
         * high-resolution tier, from Claude 4.7 on). It shrinks anything larger itself,
         * so sending more costs the user's data and buys nothing. The tier also has a
         * budget of 4,784 visual tokens (28 px patches), which a 4:3 frame at this edge
         * exceeds: Anthropic shrinks 2,576 x 1,932 again, to 2,212 x 1,659. A 16:9 frame
         * at 2,576 x 1,449 fits as sent. Raised from 1,568 in v0.4.0, a treatment change.
         */
        const val MAX_EDGE_PX = 2576
        const val JPEG_QUALITY = 80

        /** Long edge used for the blur and brightness check only. */
        private const val QUALITY_EDGE_PX = 320

        internal fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
            return sample
        }

        /** The factor that brings the long edge down to [MAX_EDGE_PX]; 1 when it is already there. */
        internal fun capScale(width: Int, height: Int): Float {
            val longEdge = maxOf(width, height)
            return if (longEdge <= MAX_EDGE_PX) 1f else MAX_EDGE_PX.toFloat() / longEdge
        }

        /**
         * The size a decoded [width] x [height] frame is stored at, once turned upright
         * and capped. What [uprightAndCapped] produces, kept as arithmetic so it can be
         * checked without a device.
         */
        internal fun outputSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
            val scale = capScale(width, height)
            val w = Math.round(width * scale).coerceAtLeast(1)
            val h = Math.round(height * scale).coerceAtLeast(1)
            return if (uprightTurn(orientation).swapsSides) h to w else w to h
        }

        /**
         * All eight EXIF orientations. Phones write 6 for a portrait shot from the back
         * camera, which is most photos of a chair leg; the mirrored four come from front
         * cameras and editing apps, and two of those are sideways too.
         */
        internal fun uprightTurn(orientation: Int): UprightTurn = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> UprightTurn(0f, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_180 -> UprightTurn(180f, mirrored = false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> UprightTurn(180f, mirrored = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> UprightTurn(270f, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_90 -> UprightTurn(90f, mirrored = false)
            ExifInterface.ORIENTATION_TRANSVERSE -> UprightTurn(90f, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_270 -> UprightTurn(270f, mirrored = false)
            else -> UprightTurn.NONE
        }

        private val UprightTurn.swapsSides: Boolean get() = degrees == 90f || degrees == 270f

        /**
         * [jpeg] without its metadata segments: EXIF and XMP (APP1), IPTC (APP13), and
         * every other application segment except the JFIF header (APP0) and a colour
         * profile (APP2), which say how to read the pixels rather than where they came
         * from. Comments (COM) go too.
         *
         * `Bitmap.compress` writes none of these today, so on current phones this returns
         * its input unchanged and the bytes, and so the hash, are what the encoder made.
         * It is here so that "the file goes up clean" is a property of this code rather
         * than of an Android version: the camera metadata is sent as separate values (PR
         * 7), and a serial number or GPS fix left in the file would bypass the location
         * switch. Bytes that do not parse as a JPEG are returned as they are.
         */
        internal fun stripMetadata(jpeg: ByteArray): ByteArray {
            fun at(index: Int): Int = jpeg[index].toInt() and 0xFF
            if (jpeg.size < 4 || at(0) != 0xFF || at(1) != SOI) return jpeg
            val out = ByteArrayOutputStream(jpeg.size)
            out.write(jpeg, 0, 2)
            var i = 2
            var dropped = false
            while (i + 4 <= jpeg.size) {
                if (at(i) != 0xFF) return jpeg
                val marker = at(i + 1)
                // A fill byte before a marker; the marker is the next byte.
                if (marker == 0xFF) {
                    i++
                    continue
                }
                // Start of scan: the entropy-coded image follows, and with it the end.
                if (marker == SOS) {
                    if (!dropped) return jpeg
                    out.write(jpeg, i, jpeg.size - i)
                    return out.toByteArray()
                }
                val length = (at(i + 2) shl 8) or at(i + 3)
                if (length < 2 || i + 2 + length > jpeg.size) return jpeg
                val metadata = marker == COM || (marker in APP1..APP15 && marker != APP2)
                if (metadata) dropped = true else out.write(jpeg, i, 2 + length)
                i += 2 + length
            }
            return jpeg
        }

        private const val SOI = 0xD8
        private const val SOS = 0xDA
        private const val APP1 = 0xE1
        private const val APP2 = 0xE2
        private const val APP15 = 0xEF
        private const val COM = 0xFE

        private const val TAG = "ImageFileStore"
    }
}
