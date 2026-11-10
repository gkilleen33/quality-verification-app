package com.qualityverifier.domain

/**
 * Which app sent a request, as the phone names itself in [AppVersionHeaders.APP].
 *
 * Its own vocabulary rather than [Audience]: both apps are at the same `versionCode`, so a
 * version number alone does not say which app it belongs to, and the server keeps one
 * latest and one minimum per app. The ids are the apps' names rather than the audiences',
 * because this is about which build is installed, not about whose prompt it reaches.
 *
 * Not stored in the database, so no CHECK mirrors it.
 */
enum class AppId(val id: String) {
    KAGUA("kagua"),
    FUNDI("fundi");

    companion object {
        fun fromId(id: String?): AppId? {
            val wanted = id?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == wanted }
        }
    }
}

/**
 * The two headers every request from a v0.4.0 or later app carries.
 *
 * Headers rather than body fields: the server's JSON reader refuses unknown keys, so a new
 * body field would break a new app against an old server, while an unknown header is
 * ignored by both.
 */
object AppVersionHeaders {
    /** The app's `versionCode`, as a decimal integer. */
    const val VERSION = "X-App-Version"

    /** [AppId.id]: `kagua` or `fundi`. */
    const val APP = "X-App-Id"

    /**
     * The version a header carries, or null.
     *
     * Absent, blank, non-numeric and non-positive all read as null: "we do not know which
     * build this is". A v0.3.0 or older app sends nothing, and nothing about not knowing
     * may refuse a request.
     */
    fun parseVersion(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * What the server says about one app's versions, from `GET /v1/auth/config`.
 *
 * [latest] is the newest `versionCode` released; an older install shows a banner.
 * [minimum] is the oldest the server still serves; an older install is blocked, and the
 * server answers its requests `426`. [minimum] stays 1 unless a security flaw, an unsafe
 * old protocol, or a server change old apps cannot use forces it up.
 */
data class VersionPolicy(
    val latest: Int,
    val minimum: Int,
    /** The release page, opened by the Download button. */
    val downloadUrl: String,
) {
    /**
     * Whether the server refuses a request from [versionCode].
     *
     * Null never is: a request with no version header comes from an app built before the
     * header existed, and refusing it would lock out every phone in the field at once.
     */
    fun refuses(versionCode: Int?): Boolean = versionCode != null && versionCode < minimum

    /**
     * What the phone at [versionCode] shows.
     *
     * Below the minimum wins over a dismissed banner: dismissing "a new version is
     * available" is a choice about an optional update, and it cannot opt somebody out of
     * one the server will no longer serve.
     */
    fun promptFor(versionCode: Int, bannerDismissed: Boolean): UpdatePrompt = when {
        versionCode < minimum -> UpdatePrompt.REQUIRED
        versionCode < latest && !bannerDismissed -> UpdatePrompt.AVAILABLE
        else -> UpdatePrompt.NONE
    }

    companion object {
        /** The public release page; GitHub resolves it to the newest tagged release. */
        const val DEFAULT_DOWNLOAD_URL =
            "https://github.com/gkilleen33/quality-verification-app/releases/latest"

        /** What the server answers when it has not been told anything: nothing to update. */
        val UNCONFIGURED = VersionPolicy(latest = 1, minimum = 1, downloadUrl = DEFAULT_DOWNLOAD_URL)
    }
}

/** What the home screen shows about this install's version. */
enum class UpdatePrompt {
    /** Up to date, or the banner was dismissed this run, or the check has not answered. */
    NONE,

    /** Below `latest`: a dismissible banner on the home screen. */
    AVAILABLE,

    /** Below `minimum`: a screen in place of the app, with the same Download button. */
    REQUIRED,
}
