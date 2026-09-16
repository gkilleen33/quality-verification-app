package com.qualityverifier.data.prompts

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ItemType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches prompts from raw GitHub URLs and caches them on disk.
 *
 * Resolution order per file: fresh cache -> network -> stale cache -> compiled-in
 * default. A network failure with any cached copy present is therefore not an error,
 * which matters on intermittent mobile connections.
 *
 * To change prompts in production: edit the files in the repo and push to `main`.
 * Devices pick the change up within [ttlMillis].
 */
class GitHubPromptRepository(
    private val client: OkHttpClient,
    private val cache: PromptCache,
    private val baseUrl: String,
    private val ttlMillis: Long = TimeUnit.HOURS.toMillis(24),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where a failed fetch gets reported. Injected because this class is shared between
     * the phone and the server, which log to entirely different places — `android.util.Log`
     * on one and slf4j on the other — and neither belongs in a module both depend on. A
     * dropped fetch is never fatal here, so the default swallows it.
     */
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
) : PromptRepository {

    override suspend fun systemPromptFor(
        itemType: ItemType,
        audience: Audience,
    ): String = withContext(io) {
        // Two masters, cached under their own paths, so each audience keeps its own
        // prefix warm rather than evicting the other's.
        val masterPath = masterPathFor(audience)
        val master = load(masterPath, blankIsValid = false) { defaultMasterFor(audience) }
        // Blank is a valid answer for an item: most item files are still empty
        // placeholders, and a 404 (file not yet pushed) is not worth surfacing. The
        // compiled-in copy covers the case where the file has never been fetched at all.
        val item = load(itemType.promptPath, blankIsValid = true) { DefaultPrompts.forItem(itemType) }
        assembleSystemPrompt(master, item)
    }

    override suspend fun clearCache() = withContext(io) { cache.clear() }

    private fun load(
        remotePath: String,
        blankIsValid: Boolean,
        default: () -> String,
    ): String {
        val cached = cache.read(remotePath)
        if (cached != null && cached.ageMillis < ttlMillis && cached.text.isUsable(blankIsValid)) {
            return cached.text
        }

        val fetched = fetch(remotePath)
        if (fetched != null && fetched.isUsable(blankIsValid)) {
            cache.write(remotePath, fetched)
            return fetched
        }

        // Stale beats nothing.
        if (cached != null && cached.text.isUsable(blankIsValid)) return cached.text

        return default()
    }

    private fun String.isUsable(blankIsValid: Boolean) = blankIsValid || isNotBlank()

    /** Returns the body on HTTP 200, or null on any failure. Never throws. */
    private fun fetch(remotePath: String): String? = try {
        val request = Request.Builder().url(baseUrl + remotePath).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else {
                warn("Prompt fetch $remotePath returned HTTP ${response.code}", null)
                null
            }
        }
    } catch (e: Exception) {
        warn("Prompt fetch $remotePath failed", e)
        null
    }

    companion object {
        const val MASTER_PATH = "master.txt"
        const val FUNDI_MASTER_PATH = "fundi-master.txt"

        fun masterPathFor(audience: Audience): String = when (audience) {
            Audience.BUYER -> MASTER_PATH
            Audience.FUNDI -> FUNDI_MASTER_PATH
        }

        fun defaultMasterFor(audience: Audience): String = when (audience) {
            Audience.BUYER -> DefaultPrompts.MASTER
            Audience.FUNDI -> DefaultPrompts.FUNDI_MASTER
        }
    }
}
