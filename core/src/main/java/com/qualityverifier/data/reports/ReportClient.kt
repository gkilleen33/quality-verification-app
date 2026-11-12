package com.qualityverifier.data.reports

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** What asking for a preview came back with. */
sealed interface PreviewOutcome {
    data class Loaded(val preview: ReportPreview) : PreviewOutcome

    /** Offline, the server is unwell, or the answer would not read. */
    data object Unavailable : PreviewOutcome

    /** The server refused the request as it stands: not this account's piece, or a bad request. */
    data class Refused(val code: Int) : PreviewOutcome
}

/** What downloading a report came back with. */
sealed interface ReportOutcome {
    /** The PDF is on disk, whole. [reportId] is the server's row, from `X-Report-Id`. */
    data class Done(val file: File, val reportId: String?) : ReportOutcome

    /** Nothing on disk: the connection failed, the call timed out, or the server is unwell. */
    data object Unavailable : ReportOutcome

    /** The server was rendering another report and did not get to this one in time. */
    data object Busy : ReportOutcome

    data class Refused(val code: Int) : ReportOutcome
}

/** How far a download has got: bytes received, and the total if the server said. */
fun interface DownloadProgress {
    fun onProgress(received: Long, total: Long?)
}

/**
 * The preview and the download, as the screen's view model sees them. An interface for the
 * reason the profile client's is: what the flow does with a failure, a busy server or a
 * whole file is worth stating against a fake, not a server.
 */
interface ReportApi {
    suspend fun preview(pieceId: String, request: ReportRequest): PreviewOutcome

    /** Downloads the report into [into]. On any failure, [into] does not exist afterwards. */
    suspend fun download(pieceId: String, request: ReportRequest, into: File, progress: DownloadProgress): ReportOutcome
}

/**
 * The PDF reports (plan v7 §5.4 and §5.5): the preview, then the report as a file.
 *
 * In `:core` for the reason the profile client is: every authenticated client has to refresh
 * through the single-flight provider. Built on a copy of the shared client with its own
 * 150-second call timeout (see `AppContainer`), because a 500 KB report on a 40 kbps link
 * takes about 100 seconds and the shared client's read timeout restarts with every byte.
 *
 * The bytes are streamed to the file as they arrive, with progress, and the file is renamed
 * into place only once whole: a failure leaves no partial file to share by mistake.
 */
class ReportClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReportApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun preview(pieceId: String, request: ReportRequest): PreviewOutcome = withContext(io) {
        val response = http.send { token ->
            Request.Builder().url(baseUrl + previewPath(pieceId, request))
                .addHeader("Authorization", "Bearer $token").get().build()
        } ?: return@withContext PreviewOutcome.Unavailable
        response.use {
            when {
                it.isSuccessful -> runCatching {
                    PreviewOutcome.Loaded(json.decodeFromString<ReportPreview>(it.body!!.string()))
                }.getOrElse { error ->
                    Log.w(TAG, "Could not read the preview", error)
                    PreviewOutcome.Unavailable
                }
                it.code in 400..499 -> PreviewOutcome.Refused(it.code)
                else -> PreviewOutcome.Unavailable
            }
        }
    }

    /**
     * Downloads the report into [into], writing a sibling `.part` file first. [into] is
     * replaced only on success; on any failure neither exists afterwards.
     */
    override suspend fun download(
        pieceId: String,
        request: ReportRequest,
        into: File,
        progress: DownloadProgress,
    ): ReportOutcome = withContext(io) {
        val body = json.encodeToString(ReportRequest.serializer(), request).toRequestBody(JSON)
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/pieces/$pieceId/reports")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        } ?: return@withContext ReportOutcome.Unavailable
        response.use {
            when {
                it.code == 201 -> save(it, into, progress)
                it.code == 503 -> ReportOutcome.Busy
                it.code in 400..499 -> ReportOutcome.Refused(it.code)
                else -> ReportOutcome.Unavailable
            }
        }
    }

    private fun save(response: okhttp3.Response, into: File, progress: DownloadProgress): ReportOutcome {
        val responseBody = response.body ?: return ReportOutcome.Unavailable
        val total = responseBody.contentLength().takeIf { it > 0 }
        val part = File(into.parentFile, into.name + ".part")
        into.delete()
        part.delete()
        try {
            into.parentFile?.mkdirs()
            var received = 0L
            progress.onProgress(0, total)
            responseBody.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        progress.onProgress(received, total)
                    }
                }
            }
            // Short of what the server said is a cut connection, whatever OkHttp made of it.
            if (total != null && received < total) {
                Log.i(TAG, "Report download stopped at $received of $total bytes")
                part.delete()
                return ReportOutcome.Unavailable
            }
            if (!part.renameTo(into)) {
                part.delete()
                return ReportOutcome.Unavailable
            }
            return ReportOutcome.Done(into, response.header(REPORT_ID_HEADER))
        } catch (e: IOException) {
            Log.i(TAG, "Report download failed: ${e.javaClass.simpleName}")
            part.delete()
            into.delete()
            return ReportOutcome.Unavailable
        }
    }

    private fun previewPath(pieceId: String, request: ReportRequest): String = buildString {
        append("v1/fundi/pieces/").append(pieceId).append("/reports/preview?kind=").append(request.kind)
        request.parts?.takeIf { it.isNotEmpty() }?.let { append("&parts=").append(it.joinToString(",")) }
        request.sessionId?.let { append("&session_id=").append(it) }
    }

    private companion object {
        const val TAG = "ReportClient"
        const val REPORT_ID_HEADER = "X-Report-Id"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
