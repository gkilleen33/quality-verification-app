package com.qualityverifier.fundi.reports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where a downloaded report lives, and how it reaches the share sheet (plan v7 §5.4).
 *
 * The file sits under `cacheDir/reports/` only as long as it takes to share it: the folder
 * is emptied at every app start and before every new report, so no file outlives one app
 * session, and Android may clear the cache at any time besides. A maker therefore has no
 * file to edit between render and send, which is the whole of the Phase One check.
 */
object ReportFiles {
    /** Matches the manifest's `android:authorities` on ReportFileProvider. */
    const val AUTHORITY = "com.qualityverifier.fundibora.files"

    const val MIME_TYPE = "application/pdf"

    fun dir(context: Context): File = File(context.cacheDir, "reports")

    /** Deletes every report on the phone. Called at app start and before each new report. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** The file a new report is downloaded into. The folder is emptied first. */
    fun newFile(context: Context, pieceId: String): File {
        clear(context)
        val folder = dir(context).apply { mkdirs() }
        return File(folder, "fundi-bora-report-${pieceId.take(8)}.pdf")
    }

    /** What the share intent carries, as a value the tests can read without Android. */
    data class ShareSpec(
        val mimeType: String,
        val uri: Uri,
        val grantsRead: Boolean,
        val hasClipData: Boolean,
    )

    /**
     * Hands the report to the share sheet. WhatsApp is the destination in practice, but
     * choosing it here would break on a phone that does not have it installed. The
     * receiving app reads the file through the provider; the grant is for that one URI.
     */
    fun share(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        context.startActivity(Intent.createChooser(shareIntent(uri), chooserTitle))
    }

    /** The intent alone, so a test can check its shape through [specOf]. */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        // ClipData too: some receivers read the stream from there rather than the extra,
        // and the read grant applies to the clip's URI.
        clipData = ClipData.newRawUri("report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun specOf(intent: Intent): ShareSpec = ShareSpec(
        mimeType = intent.type.orEmpty(),
        uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: Uri.EMPTY,
        grantsRead = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        hasClipData = intent.clipData != null,
    )
}
