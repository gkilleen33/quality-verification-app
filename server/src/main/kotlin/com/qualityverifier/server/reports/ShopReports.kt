package com.qualityverifier.server.reports

import com.qualityverifier.server.db.LinkCreation
import com.qualityverifier.server.db.ShopReportLinkRow
import com.qualityverifier.server.db.ShopReportStore
import com.qualityverifier.text.parseAssistantContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64

/**
 * The customer's PDF by QR code on a shop's tablet (release scope row 31; V40).
 *
 * After the verdict, the tablet asks for a link to the assessment in progress and shows it as
 * a QR code. The customer scans it with their own phone and downloads the PDF from a public
 * route that needs no sign-in. No number is collected and nothing is sent by SMS.
 *
 * The link is a random token of 128 bits; the server keeps only its SHA-256, so neither the
 * table nor a backup can download a report. It works for [LINK_LIFETIME_MILLIS] from when it
 * was made and for [MAX_DOWNLOADS] downloads. Each download renders the assessment as it then
 * stands, so a follow-up the customer asked after scanning is in the PDF.
 */
class ShopReportService(
    private val store: ShopReportStore,
    private val renderer: PdfRenderer,
    private val composer: ReportComposer = ReportComposer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val log = LoggerFactory.getLogger(ShopReportService::class.java)

    sealed interface IssueOutcome {
        /** [path] is relative to the server's base URL, as `/v1/me` gives the terms' paths. */
        data class Issued(val path: String, val expiresAt: Long) : IssueOutcome
        /** A personal Kagua account, or a Fundi Bora one: Share is theirs, not this. */
        data object NotAShopTablet : IssueOutcome
        /** Not the shop account's assessment in progress (V39), or not on the server. */
        data object NoSuchSession : IssueOutcome
        /** The assessment has no verdict yet: the tablet offers the code only after one. */
        data object NoVerdict : IssueOutcome
        /** [MAX_LINKS_PER_ASSESSMENT] already made for this assessment. */
        data object TooMany : IssueOutcome
    }

    sealed interface DownloadOutcome {
        data class Pdf(val bytes: ByteArray) : DownloadOutcome
        /** A wrong token, a used-up one, and an expired one: one answer for all three. */
        data object Expired : DownloadOutcome
    }

    /**
     * A link to [sessionId] for the tablet of [userId]. Only a shop account (V39's
     * `users.shop_mode`, passed in as [shopMode]) and only for its assessment in progress:
     * the tablet wipes on every end, and an earlier customer's assessment is never reachable
     * from it, by this route or any other.
     */
    suspend fun issue(userId: String, shopMode: Boolean, sessionId: String): IssueOutcome {
        if (!shopMode) return IssueOutcome.NotAShopTablet
        if (store.sessionInProgress(userId) != sessionId) return IssueOutcome.NoSuchSession
        val session = store.session(sessionId) ?: return IssueOutcome.NoSuchSession
        if (session.replies.none { parseAssistantContent(it).verdict != null }) return IssueOutcome.NoVerdict
        val token = newToken()
        val now = clock()
        val expiresAt = now + LINK_LIFETIME_MILLIS
        val row = ShopReportLinkRow(
            tokenSha256 = hashOf(token),
            sessionId = sessionId,
            userId = userId,
            createdAt = now,
            expiresAt = expiresAt,
        )
        return when (store.createLink(row, MAX_LINKS_PER_ASSESSMENT)) {
            LinkCreation.TOO_MANY -> IssueOutcome.TooMany
            LinkCreation.NO_SESSION -> IssueOutcome.NoSuchSession
            LinkCreation.CREATED -> {
                log.info("Report link issued for session {}", sessionId)
                IssueOutcome.Issued(path = PUBLIC_PATH_PREFIX + token, expiresAt = expiresAt)
            }
        }
    }

    /**
     * The PDF for [token], counting one download, or [DownloadOutcome.Expired]. The PDF is
     * rendered before the download is counted, so a failed render costs the customer none;
     * the count is then taken in one statement that checks the limits again, so two
     * downloads at once cannot both take the last one.
     */
    suspend fun download(token: String): DownloadOutcome {
        if (!TOKEN_SHAPE.matches(token)) return DownloadOutcome.Expired
        val hash = hashOf(token)
        val link = store.link(hash) ?: return DownloadOutcome.Expired
        val now = clock()
        if (!usable(link, now)) return DownloadOutcome.Expired
        val session = store.session(link.sessionId) ?: return DownloadOutcome.Expired
        val bytes = withContext(Dispatchers.Default) { renderer.renderKagua(composer.composeKagua(session)) }
        if (!store.claimDownload(hash, now, MAX_DOWNLOADS)) return DownloadOutcome.Expired
        log.info("Report link for session {} downloaded ({} of {})", link.sessionId, link.downloads + 1, MAX_DOWNLOADS)
        return DownloadOutcome.Pdf(bytes)
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** Thirty minutes from creation (row 31). V40's CHECK holds the table to it. */
        const val LINK_LIFETIME_MILLIS = 30L * 60 * 1000

        /** Three downloads (row 31). V40's CHECK holds the table to it. */
        const val MAX_DOWNLOADS = 3

        /**
         * Five links an assessment. One is enough for a customer; a few more cover a second
         * phone, a rotation, or a code scanned too late. More would only be a loop.
         */
        const val MAX_LINKS_PER_ASSESSMENT = 5

        /** 128 bits, the least row 31 allows: 22 characters, so the QR code stays small. */
        const val TOKEN_BYTES = 16

        /** Under /v1/: production nginx passes nothing else to the app. */
        const val PUBLIC_PATH_PREFIX = "v1/r/"

        /** Base64url without padding, of [TOKEN_BYTES] bytes. Anything else is a wrong token. */
        private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]{22}$")

        /** Lowercase hex SHA-256 of the token's characters: what V40 stores. */
        fun hashOf(token: String): String = ReportCache.sha256(token.toByteArray(Charsets.US_ASCII))

        /** Whether [link] may still be downloaded at [now]: not expired, and not used up. */
        fun usable(link: ShopReportLinkRow, now: Long): Boolean =
            now < link.expiresAt && link.downloads < MAX_DOWNLOADS
    }
}

/**
 * A per-address limit on the public report route (row 31): at most [limit] requests in any
 * [windowMillis], counted in memory and never stored. A token of 128 bits cannot be guessed,
 * so this is not what protects the reports; it bounds what one address can make the server
 * render, and stops a scanner walking the route.
 *
 * Generous on purpose: Kenyan mobile networks put many phones behind one address, and a
 * customer may try a slow download more than once.
 *
 * An IPv6 address counts by its /64, the block one subscriber is given: a phone can take a new
 * address inside it for every request, and would otherwise get a new allowance with each.
 *
 * At most [maxAddresses] are remembered. Past that the one heard from longest ago is
 * forgotten, which only gives it a fresh allowance; so memory and the work per request stay
 * bounded however many addresses arrive.
 */
class AddressRateLimit(
    private val limit: Int = DEFAULT_LIMIT,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAddresses: Int = DEFAULT_MAX_ADDRESSES,
) {
    // In access order, so the eldest entry is the one heard from longest ago. One lock for the
    // map and every count in it: a request does at most [limit] removals under it, and counting
    // and recording are then one step, so two requests at once cannot both take the last place.
    private val seen = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean =
            size > maxAddresses
    }

    /** True, and counted, when [address] may make one more request now. */
    fun tryAcquire(address: String): Boolean {
        val now = clock()
        val key = keyOf(address)
        synchronized(seen) {
            val times = seen.getOrPut(key) { ArrayDeque() }
            while (times.isNotEmpty() && times.first() <= now - windowMillis) times.removeFirst()
            if (times.size >= limit) return false
            times.addLast(now)
            return true
        }
    }

    /** How many addresses are remembered now: never more than [maxAddresses]. */
    internal fun remembered(): Int = synchronized(seen) { seen.size }

    companion object {
        /** Thirty requests in ten minutes per address: ten customers' three downloads each. */
        const val DEFAULT_LIMIT = 30
        const val DEFAULT_WINDOW_MILLIS = 10L * 60 * 1000

        /** About 10 MB at the most: each address holds at most [DEFAULT_LIMIT] times, about 1 KB. */
        const val DEFAULT_MAX_ADDRESSES = 10_000

        /** Hex digits, colons and an IPv4 tail: an IPv6 literal, which [InetAddress] parses without DNS. */
        private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$")

        /**
         * What [address] is counted under: an IPv6 address's /64, written as its first four
         * groups; an IPv4 address (an IPv4-mapped IPv6 one included) as itself. Anything else
         * as it came, since nginx sets the address and a test or a local run may send a name.
         */
        internal fun keyOf(address: String): String {
            val literal = address.substringBefore('%').removePrefix("[").removeSuffix("]")
            if (!IPV6_LITERAL.matches(literal)) return address
            // The pattern above starts with a hex digit or a colon, which InetAddress takes as
            // a literal and never looks up.
            val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return address
            if (parsed is Inet4Address) return parsed.hostAddress
            val bytes = parsed.address
            return (0 until 8 step 2).joinToString(":", postfix = "::/64") {
                "%x".format(((bytes[it].toInt() and 0xff) shl 8) or (bytes[it + 1].toInt() and 0xff))
            }
        }
    }
}
