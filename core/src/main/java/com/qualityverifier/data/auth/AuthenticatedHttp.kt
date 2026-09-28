package com.qualityverifier.data.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Sends a request with a bearer token, and retries once through the single-flight
 * provider if the server says the token is stale.
 *
 * **Extracted because a second copy of this is how somebody gets signed out.** The server
 * rotates refresh tokens and reads a spent one coming back as theft, revoking every token
 * for that account. The safety comes from going through [TokenProvider.refreshAfterUnauthorized],
 * which double-checks under a mutex: if another call already refreshed, this one gets the
 * new token rather than spending the retired one a second time. A client that hand-rolled
 * "on 401, call refresh" would look identical and be wrong under concurrency, which is
 * exactly the shape a phone reopened after a while produces.
 *
 * `ServerChatService` deliberately keeps its own version, because its retry has to
 * re-upload photographs as well as re-authenticate. Two is a considered exception; three
 * would have been drift.
 */
class AuthenticatedHttp(
    private val client: OkHttpClient,
    private val tokens: TokenProvider,
    /** Names the caller in the one log line this emits. */
    private val tag: String,
) {
    /**
     * Null means there was no usable token, or the network failed. Anything else is the
     * server's answer, **including a 5xx** — the caller decides what a 500 means for it.
     *
     * [build] is called again on retry rather than the request being reused, because the
     * token is baked into the headers and the second attempt needs the new one.
     */
    suspend fun send(build: (String) -> Request): Response? {
        val token = tokens.accessToken() ?: return null
        val first = execute(build(token)) ?: return null
        if (first.code != 401) return first
        first.close()

        val refreshed = tokens.refreshAfterUnauthorized(token) ?: return null
        return execute(build(refreshed))
    }

    private fun execute(request: Request): Response? = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        Log.i(tag, "Request failed: ${e.javaClass.simpleName}")
        null
    }
}
