package com.qualityverifier.data.keys

/**
 * Holds the user's Anthropic API key.
 *
 * Phase 1 only. In Phase 2 the key lives on the server as an environment variable
 * and this interface — along with its implementation and the setup screen — is
 * deleted outright. Nothing outside [com.qualityverifier.data] should depend on it;
 * UI code asks about [hasKey] at most, never the value.
 */
interface ApiKeyStore {
    fun hasKey(): Boolean
    fun get(): String?
    fun set(key: String)
    fun clear()
}

/** True for strings shaped like an Anthropic key. A hint for the UI, never a gate. */
fun looksLikeAnthropicKey(candidate: String): Boolean =
    candidate.trim().startsWith("sk-ant-") && candidate.trim().length > 20
