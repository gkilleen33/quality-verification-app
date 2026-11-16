package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.server.Terms
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.TermsApp
import com.qualityverifier.server.tracker.TrackerAnnotation
import com.qualityverifier.server.tracker.TrackerAnnotationOutcome
import com.qualityverifier.server.tracker.TrackerStore
import com.qualityverifier.server.tracker.TrackerTransactionRow
import com.qualityverifier.server.tracker.TrackerUnreadDay
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger("com.qualityverifier.server.tracker")

/**
 * PesaLog's routes (V33), under `/v1/tracker/`, beside Fundi Bora's M-Pesa routes and in
 * their image.
 *
 * Who may call them: a Fundi Bora account (PesaLog signs in with one), never a Kagua account.
 * Fundi Bora's terms gate does not apply here (TermsGate.isTracker); PesaLog's terms do. The
 * status and deletion routes need only the account: status is how the phone learns what is
 * missing, and a person who has withdrawn everything must still be able to delete. The three
 * data routes also need a current agreement to PesaLog's terms, and a newest `tracker_send`
 * row that is an agreement, which the store checks again under its lock (TrackerStore).
 *
 * The data routes are mounted only with the HMAC key ([coder] not null), as the M-Pesa
 * routes are: without it a counterparty could only be stored in the clear, so they answer
 * 404 and the phone keeps its rows. Nothing here logs a request body.
 */
fun Route.trackerRoutes(store: TrackerStore, coder: CounterpartyCoder?, auth: AuthStore, terms: Terms) {
    authenticate("jwt") {

        get("/v1/tracker/status") {
            val userId = call.trackerAccount(auth) ?: return@get
            val status = terms.gate.trackerStatusFor(userId)
            call.respond(
                TrackerStatusResponse(
                    termsRequired = status.required,
                    terms = status.version?.let { terms.describe(TermsApp.TRACKER, it) },
                    transferAgreed = status.transferAgreed,
                    readAgreed = terms.gate.agreed(userId, ConsentKind.TRACKER_READ),
                    sendAgreed = terms.gate.agreed(userId, ConsentKind.TRACKER_SEND),
                ),
            )
        }

        // Deletion on request (Grady, 12 November 2026; terms section 8). Withdraws sending as
        // well, in the same transaction (TrackerStore.deleteAll); every earlier consent row stays.
        delete("/v1/tracker/records") {
            val userId = call.trackerAccount(auth) ?: return@delete
            // Required, as for a consent: the withdrawal row records the build that made it.
            val appVersion = call.trackerAppVersion() ?: return@delete
            val deletion = store.deleteAll(userId, Audience.FUNDI, appVersion)
            terms.gate.forget(userId)
            log.info(
                "PesaLog records deleted on request for {}: {} transactions, {} unread days, sending withdrawn {}",
                userId, deletion.transactions, deletion.unreadDays, deletion.sendingWithdrawn,
            )
            call.respond(TrackerDeletionResponse(deletion.transactions, deletion.unreadDays, deletion.sendingWithdrawn))
        }

        if (coder == null) return@authenticate

        post("/v1/tracker/transactions") {
            val userId = call.trackerSender(auth, terms) ?: return@post
            val appVersion = call.trackerAppVersion() ?: return@post
            val request = call.receive<TrackerUploadRequest>()
            if (request.transactions.size > MAX_BATCH) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "at most $MAX_BATCH transactions per request"),
                )
                return@post
            }
            // Validated first, then the first valid row of each key kept, for the reason
            // MpesaRoutes gives.
            val checked = request.transactions.map { it to it.toRow(appVersion, coder) }
            val rows = checked.mapNotNull { it.second }.distinctBy { it.network to it.code }
            val held = rows.map { TrackerKey(it.network.id, it.code) }.toSet()
            val rejected = checked
                .filter { (dto, row) -> row == null && TrackerKey(dto.network, dto.code.trim().uppercase()) !in held }
                .map { TrackerKey(it.first.network, it.first.code) }
                .distinct()
            val inserted = if (rows.isEmpty()) 0 else store.insertTransactions(userId, rows)
            log.info(
                "PesaLog upload for {}: {} new, {} already held, {} refused",
                userId, inserted, rows.size - inserted, rejected.size,
            )
            call.respond(TrackerUploadResponse(accepted = held.toList(), rejected = rejected, inserted = inserted))
        }

        put("/v1/tracker/transactions/{network}/{code}") {
            val userId = call.trackerSender(auth, terms) ?: return@put
            val network = MobileMoneyNetwork.fromId(call.parameters["network"])
            val code = network?.let { call.parameters["code"]?.let { raw -> normaliseCode(it, raw) } }
            if (network == null || code == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_transaction"))
                return@put
            }
            val body = call.receive<TrackerAnnotationDto>()
            val category = body.category?.let {
                MpesaCategory.fromId(it) ?: return@put call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "unknown category"),
                )
            }
            val comment = body.comment?.trim()?.takeIf { it.isNotEmpty() }
            if (comment != null && ('\u0000' in comment || comment.codePointCount(0, comment.length) > MAX_COMMENT)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("comment_invalid", "at most $MAX_COMMENT characters"),
                )
                return@put
            }
            when (store.annotate(userId, network, code, TrackerAnnotation(category, comment))) {
                TrackerAnnotationOutcome.SAVED -> call.respond(HttpStatusCode.NoContent)
                TrackerAnnotationOutcome.NO_SUCH_TRANSACTION ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_transaction"))
                TrackerAnnotationOutcome.CATEGORY_DOES_NOT_FIT ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("category_does_not_fit"))
            }
        }

        put("/v1/tracker/unread") {
            val userId = call.trackerSender(auth, terms) ?: return@put
            val appVersion = call.trackerAppVersion() ?: return@put
            val request = call.receive<TrackerUnreadRequest>()
            if (request.days.size > MAX_UNREAD_ROWS) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "at most $MAX_UNREAD_ROWS rows per request"),
                )
                return@put
            }
            val days = request.days.map { dto ->
                val network = MobileMoneyNetwork.fromId(dto.network)
                val day = runCatching { LocalDate.parse(dto.day) }.getOrNull()
                    ?.takeIf { it.year in FIRST_YEAR..LAST_YEAR }
                if (network == null || day == null || dto.unread !in 0..MAX_UNREAD || dto.parserVersion <= 0) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "bad row ${dto.network} ${dto.day}"))
                    return@put
                }
                TrackerUnreadDay(network, day, dto.unread, dto.parserVersion, appVersion)
            }.distinctBy { it.network to it.day }
            if (days.isNotEmpty()) store.saveUnread(userId, days)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Two networks' worth of days, as MpesaRoutes allows 62 days of one. */
private const val MAX_UNREAD_ROWS = 124
private const val MAX_UNREAD = 100_000

/** Mobile money in Kenya began in 2007; PostgreSQL stores the rest. */
private const val FIRST_YEAR = 2007
private const val LAST_YEAR = 2100
private val EARLIEST_MILLIS = LocalDate.of(FIRST_YEAR, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private val LATEST_MILLIS = LocalDate.of(LAST_YEAR, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val MAX_AMOUNT = BigDecimal("1000000000")

/**
 * The shape of a code on each network, mirrored by V33's `tracker_transactions_code_shape`
 * (TrackerVocabularyTest compares them). Upper-cased first: the parser reads an Airtel id
 * after "TID:" without changing its case.
 */
internal val TRACKER_CODE_SHAPES: Map<MobileMoneyNetwork, Regex> = mapOf(
    MobileMoneyNetwork.MPESA to Regex("^[A-Z0-9]{8,12}$"),
    MobileMoneyNetwork.AIRTEL to Regex("^[A-Z0-9][A-Z0-9.-]{5,31}$"),
)

private fun normaliseCode(network: MobileMoneyNetwork, raw: String): String? =
    raw.trim().uppercase().takeIf { TRACKER_CODE_SHAPES.getValue(network).matches(it) }

/**
 * The account id when the caller is a live Fundi Bora account; otherwise answers and returns
 * null. Every tracker route starts here.
 */
private suspend fun ApplicationCall.trackerAccount(auth: AuthStore): String? {
    val userId = principal<JWTPrincipal>()?.subject
    val user = userId?.let { auth.findUser(it) }
    if (user == null || user.disabled) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
        return null
    }
    if (user.audience != Audience.FUNDI) {
        log.warn("Non-fundi account {} tried to use a PesaLog endpoint", user.id)
        respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_fundi"))
        return null
    }
    return user.id
}

/**
 * As [trackerAccount], and the account has agreed to PesaLog's current terms and its newest
 * `tracker_send` row is an agreement.
 */
private suspend fun ApplicationCall.trackerSender(auth: AuthStore, terms: Terms): String? {
    val userId = trackerAccount(auth) ?: return null
    if (terms.gate.trackerStatusFor(userId).refuses) {
        log.warn("PesaLog upload refused for {}: no current agreement to PesaLog's terms", userId)
        respond(HttpStatusCode.Forbidden, ErrorResponse("tracker_terms_required"))
        return null
    }
    if (!terms.gate.agreed(userId, ConsentKind.TRACKER_SEND)) {
        log.warn("PesaLog upload refused for {}: no current agreement to send", userId)
        respond(HttpStatusCode.Forbidden, ErrorResponse("tracker_send_required"))
        return null
    }
    return userId
}

private suspend fun ApplicationCall.trackerAppVersion(): Int? {
    val version = AppVersionHeaders.parseVersion(request.headers[AppVersionHeaders.VERSION])
    if (version == null) respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "X-App-Version is required"))
    return version
}

/** The row to store, with the counterparty coded; null when anything in it is out of range. */
private fun TrackerTransactionDto.toRow(appVersion: Int, coder: CounterpartyCoder): TrackerTransactionRow? {
    val knownNetwork = MobileMoneyNetwork.fromId(network) ?: return null
    val knownCode = normaliseCode(knownNetwork, code) ?: return null
    val knownDirection = MpesaDirection.fromId(direction) ?: return null
    val knownType = MpesaType.fromId(type) ?: return null
    val amount = shillings(amountKes) ?: return null
    val fee = feeKes?.let(::shillings)
    val balance = balanceAfterKes?.takeIf { it.isFinite() && kotlin.math.abs(it) < MAX_AMOUNT.toDouble() }
        ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
    if (occurredAt !in EARLIEST_MILLIS..LATEST_MILLIS || parserVersion <= 0) return null
    return TrackerTransactionRow(
        network = knownNetwork,
        code = knownCode,
        direction = knownDirection,
        type = knownType,
        amountKes = amount,
        feeKes = fee,
        balanceAfterKes = balance,
        occurredAtMillis = occurredAt,
        // The same coder, key and rules as Fundi Bora's M-Pesa rows, so a number or name has
        // one code in both tables.
        counterparty = coder.code(counterpartyPhone, counterpartyName),
        parserVersion = parserVersion,
        appVersion = appVersion,
    )
}

private fun shillings(value: Double): BigDecimal? =
    value.takeIf { it.isFinite() && it >= 0 }
        ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
        ?.takeIf { it < MAX_AMOUNT }
