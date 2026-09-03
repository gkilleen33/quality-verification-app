package com.qualityverifier.data.location

import com.qualityverifier.domain.LocationFix

/**
 * Where an assessment's one location fix comes from.
 *
 * A seam rather than a concrete class so that [com.qualityverifier.ui.chat.ChatViewModel]
 * stays testable without a GPS: every test in that file is about conversations, and none
 * of them should have to stand up a location provider to say so. [None] is the default,
 * and it reports itself unavailable so nothing is launched at all.
 */
interface LocationSource {

    /**
     * Whether there is any point starting: the setting is on, the permission is granted,
     * and Android has a provider enabled. Checked before launching so the common
     * refusals cost nothing.
     */
    val isAvailable: Boolean

    /** The best fix obtainable, or null. May suspend for up to a minute and a half. */
    suspend fun capture(): LocationFix?

    /** Records nothing. What every test and a customer who opted out both get. */
    object None : LocationSource {
        override val isAvailable = false
        override suspend fun capture(): LocationFix? = null
    }
}
