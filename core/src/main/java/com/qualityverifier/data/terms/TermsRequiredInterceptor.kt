package com.qualityverifier.data.terms

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Raised when the server refuses a request with `403 terms_required`: the account has not
 * agreed to its app's current terms, or has withdrawn the transfer agreement. Fundi Bora's
 * navigation listens and opens the terms screen; Kagua does not listen, and its server
 * never sends it while Kagua has no terms.
 */
class TermsSignal {
    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val required: SharedFlow<Unit> = events.asSharedFlow()

    fun raise() {
        events.tryEmit(Unit)
    }
}

/**
 * Spots `403 terms_required` on every response from our server.
 *
 * On the shared client rather than in each caller, so chat, uploads, profile saves, the
 * roster and piece names all lead to the terms screen without each learning the code.
 * Each caller still handles its own 403 as before; this only adds the signal. The body is
 * peeked, not read, so the caller still receives it whole.
 */
class TermsRequiredInterceptor(
    private val onTermsRequired: () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == FORBIDDEN && isTermsRequired(response)) onTermsRequired()
        return response
    }

    private fun isTermsRequired(response: Response): Boolean =
        runCatching { response.peekBody(PEEK_BYTES).string().contains(TERMS_REQUIRED) }.getOrDefault(false)

    companion object {
        const val TERMS_REQUIRED = "terms_required"
        private const val FORBIDDEN = 403

        /** The server's error body is a few dozen bytes; this is ample and bounded. */
        private const val PEEK_BYTES = 512L
    }
}
