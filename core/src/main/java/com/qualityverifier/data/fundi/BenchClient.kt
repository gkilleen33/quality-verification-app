package com.qualityverifier.data.fundi

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.chat.ImageBytesSource
import com.qualityverifier.domain.BenchDraft
import com.qualityverifier.domain.LocationFix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest

/** What sending a bench photo came back with. */
sealed interface BenchOutcome {
    /** Claude's draft of the tools on the bench, for the fundi to confirm one by one. */
    data class Drafted(val draft: BenchDraft) : BenchOutcome

    /** Five bench photos today already (V35): the fundi types the list instead. */
    data object LimitReached : BenchOutcome

    /** Offline, the server or Claude is unwell, or the answer would not read. Worth a retry. */
    data object Unavailable : BenchOutcome

    /** Refused for good, or the photo could not be read on the phone. */
    data object Refused : BenchOutcome
}

/** The bench photo at setup (v0.6.0, release scope row 5). An interface so the setup flow can be tested. */
fun interface BenchApi {
    /**
     * Uploads the photo and asks the server for a draft of the tools on it. [photoId] is
     * made once per photo, so a retry after a lost answer is the same photo on the server
     * and is neither counted nor paid for twice.
     */
    suspend fun draft(photoId: String, file: File, location: LocationFix?): BenchOutcome
}

@Serializable
private data class BenchBody(
    val id: String,
    val sha256: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyMetres: Double? = null,
)

/**
 * Sends a bench photo: the bytes once, by hash, as an assessment's photos go
 * (`HEAD`/`PUT /v1/blobs/{sha}`), then `POST /v1/fundi/bench-photos`, which answers with
 * Claude's draft. Through the shared client and the single-flight token provider, as every
 * authenticated call is (see [AuthenticatedHttp]).
 *
 * The bytes are the photo as processed for upload, with no camera metadata. The point goes
 * only when the fundi allows location, which the caller decides.
 */
class BenchClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val images: ImageBytesSource,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BenchApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun draft(photoId: String, file: File, location: LocationFix?): BenchOutcome = withContext(io) {
        val bytes = images.bytesForUpload(file) ?: return@withContext BenchOutcome.Refused
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        // HEAD first, so a retry does not upload the photo again.
        val present = http.send { token ->
            Request.Builder().url(baseUrl + "v1/blobs/" + sha).addHeader("Authorization", "Bearer $token").head().build()
        }?.use { it.isSuccessful } ?: return@withContext BenchOutcome.Unavailable
        if (!present) {
            val stored = http.send { token ->
                Request.Builder().url(baseUrl + "v1/blobs/" + sha).addHeader("Authorization", "Bearer $token")
                    .put(bytes.toRequestBody(JPEG)).build()
            }?.use { it.isSuccessful } ?: return@withContext BenchOutcome.Unavailable
            if (!stored) return@withContext BenchOutcome.Unavailable
        }

        val body = json.encodeToString(
            BenchBody.serializer(),
            BenchBody(photoId, sha, location?.latitude, location?.longitude, location?.accuracyMetres),
        ).toRequestBody(JSON)
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/bench-photos").addHeader("Authorization", "Bearer $token")
                .post(body).build()
        } ?: return@withContext BenchOutcome.Unavailable
        response.use {
            when {
                it.isSuccessful -> runCatching {
                    BenchOutcome.Drafted(json.decodeFromString(BenchDraft.serializer(), it.body!!.string()))
                }.getOrElse { error ->
                    Log.w(TAG, "Could not read the bench draft", error)
                    BenchOutcome.Unavailable
                }
                it.code == 429 -> BenchOutcome.LimitReached
                // A malformed request or another account's id: a retry sends the same.
                it.code == 400 || it.code == 404 -> BenchOutcome.Refused
                // 409 (the photo is still being read, or its bytes did not arrive), 403 and
                // 426 included: nothing about the photo is wrong, so it is not "unreadable".
                else -> BenchOutcome.Unavailable
            }
        }
    }

    private companion object {
        const val TAG = "BenchClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
    }
}
