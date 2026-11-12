package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.server.Terms
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.mpesa.AnnotationOutcome
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.mpesa.MpesaAnnotation
import com.qualityverifier.server.mpesa.MpesaStore
import com.qualityverifier.server.mpesa.MpesaTransactionRow
import com.qualityverifier.server.mpesa.UnreadDay
import com.qualityverifier.server.terms.ConsentKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private val log = LoggerFactory.getLogger("com.qualityverifier.server.mpesa")

/**
 * Fundi Bora's M-Pesa uploads: parsed transactions, the fundi's notes on each, and the
 * daily count of messages the phone could not read (V29).
 *
 * Mounted only with the HMAC key (Application.main): without it there is no way to store a
 * counterparty except in the clear, so the routes answer 404 and the phone keeps its data
 * and tries again later.
 *
 * Every route refuses a Kagua account and an account whose newest `mpesa_send` row is not
 * an agreement. Nothing here logs a request body; see [MpesaTransactionDto.toString].
 */
fun Route.mpesaRoutes(store: MpesaStore, coder: CounterpartyCoder, auth: AuthStore, terms: Terms) {
    authenticate("jwt") {

        post("/v1/fundi/mpesa/transactions") {
            val userId = call.mpesaSender(auth, terms) ?: return@post
            val appVersion = call.appVersionOrRefuse() ?: return@post
            val request = call.receive<MpesaUploadRequest>()
            if (request.transactions.size > MAX_BATCH) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "at most $MAX_BATCH transactions per request"),
                )
                return@post
            }

            // Validated first, then the first valid row of each code kept, as the database
            // would keep it. Deduplicating first would let an invalid copy shadow a valid one
            // and send the code to `rejected`, which the phone never retries.
            val checked = request.transactions.map { it to it.toRow(appVersion, coder) }
            val rows = checked.mapNotNull { it.second }.distinctBy { it.code }
            val held = rows.map { it.code }.toSet()
            // As the phone sent them, so it can match them to its own rows.
            val rejected = checked.filter { (dto, row) -> row == null && dto.code.trim().uppercase() !in held }
                .map { it.first.code }
                .distinct()
            val inserted = if (rows.isEmpty()) 0 else store.insertTransactions(userId, rows)
            log.info(
                "M-Pesa upload for {}: {} new, {} already held, {} refused",
                userId, inserted, rows.size - inserted, rejected.size,
            )
            call.respond(MpesaUploadResponse(accepted = rows.map { it.code }, rejected = rejected, inserted = inserted))
        }

        put("/v1/fundi/mpesa/transactions/{code}") {
            val userId = call.mpesaSender(auth, terms) ?: return@put
            val code = call.parameters["code"]?.trim()?.uppercase()?.takeIf { CODE.matches(it) }
                ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_transaction"))
            val body = call.receive<MpesaAnnotationDto>()

            val category = body.category?.let {
                MpesaCategory.fromId(it) ?: return@put call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "unknown category"),
                )
            }
            val pieceId = body.pieceId?.let {
                // Cast with ?::uuid in the store, so refused here; the same 404 as a stranger's piece.
                it.takeIf(::isUuid) ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
            }
            val comment = body.comment?.trim()?.takeIf { it.isNotEmpty() }
            if (comment != null && ('\u0000' in comment || comment.codePointCount(0, comment.length) > MAX_COMMENT)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("comment_invalid", "at most $MAX_COMMENT characters"),
                )
                return@put
            }

            when (store.annotate(userId, code, MpesaAnnotation(category, pieceId, comment))) {
                AnnotationOutcome.SAVED -> call.respond(HttpStatusCode.NoContent)
                // Not uploaded yet, or not theirs: the same answer, as for a piece. The phone
                // keeps the note and sends it again after the transaction.
                AnnotationOutcome.NO_SUCH_TRANSACTION ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_transaction"))
                AnnotationOutcome.NO_SUCH_PIECE ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
                AnnotationOutcome.CATEGORY_DOES_NOT_FIT ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("category_does_not_fit"))
            }
        }

        put("/v1/fundi/mpesa/unread") {
            val userId = call.mpesaSender(auth, terms) ?: return@put
            val appVersion = call.appVersionOrRefuse() ?: return@put
            val request = call.receive<MpesaUnreadRequest>()
            if (request.days.size > MAX_UNREAD_DAYS) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "at most $MAX_UNREAD_DAYS days per request"),
                )
                return@put
            }
            val days = request.days.map { dto ->
                val day = runCatching { LocalDate.parse(dto.day) }.getOrNull()
                    ?.takeIf { it.year in FIRST_YEAR..LAST_YEAR }
                if (day == null || dto.unread !in 0..MAX_UNREAD || dto.parserVersion <= 0) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "bad day ${dto.day}"))
                    return@put
                }
                UnreadDay(day, dto.unread, dto.parserVersion, appVersion)
            }.distinctBy { it.day }
            if (days.isNotEmpty()) store.saveUnread(userId, days)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** At most this many transactions in one upload. The phone sends 100 at a time. */
internal const val MAX_BATCH = 500

/** Matches V29's `mpesa_transactions_comment_length`, counted in characters as Postgres counts them. */
internal const val MAX_COMMENT = 500

private const val MAX_UNREAD_DAYS = 62
private const val MAX_UNREAD = 100_000

/** The years a transaction or an unread day may fall in: M-Pesa existed, and PostgreSQL can store them. */
private const val FIRST_YEAR = 2007
private const val LAST_YEAR = 2100
private val EARLIEST_MILLIS = LocalDate.of(FIRST_YEAR, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
private val LATEST_MILLIS = LocalDate.of(LAST_YEAR, 12, 31).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/** Matches V29's `mpesa_transactions_code_shape`. */
private val CODE = Regex("^[A-Z0-9]{8,12}$")

/** Below numeric(12, 2)'s ceiling, and far above any M-Pesa limit. */
private val MAX_AMOUNT = BigDecimal("1000000000")

/**
 * The account id, when the caller is a Fundi Bora account whose newest `mpesa_send` row is
 * an agreement; otherwise answers and returns null.
 */
private suspend fun ApplicationCall.mpesaSender(auth: AuthStore, terms: Terms): String? {
    val userId = principal<JWTPrincipal>()?.subject
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
        return null
    }
    if (!requireFundi(auth, userId)) return null
    if (!terms.gate.agreed(userId, ConsentKind.MPESA_SEND)) {
        log.warn("M-Pesa upload refused for {}: no current agreement to send", userId)
        respond(HttpStatusCode.Forbidden, ErrorResponse("mpesa_send_required"))
        return null
    }
    return userId
}

/** The build that read the messages, from the header the app sends on every request. */
private suspend fun ApplicationCall.appVersionOrRefuse(): Int? {
    val version = AppVersionHeaders.parseVersion(request.headers[AppVersionHeaders.VERSION])
    if (version == null) respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "X-App-Version is required"))
    return version
}

/** The row to store, with the counterparty coded; null when anything in it is out of range. */
private fun MpesaTransactionDto.toRow(appVersion: Int, coder: CounterpartyCoder): MpesaTransactionRow? {
    val mpesaCode = this.code.trim().uppercase().takeIf { CODE.matches(it) } ?: return null
    val knownDirection = MpesaDirection.fromId(this.direction) ?: return null
    val knownType = MpesaType.fromId(this.type) ?: return null
    val amount = shillings(amountKes) ?: return null
    // A fee or balance that makes no sense is dropped, not the transaction.
    val fee = feeKes?.let(::shillings)
    val balance = balanceAfterKes?.takeIf { it.isFinite() && kotlin.math.abs(it) < MAX_AMOUNT.toDouble() }
        ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
    // Bounded so a phone with a broken clock or a units bug gets its row refused alone,
    // rather than PostgreSQL refusing the whole batch for a time it cannot store.
    if (occurredAt !in EARLIEST_MILLIS..LATEST_MILLIS || parserVersion <= 0) return null
    return MpesaTransactionRow(
        code = mpesaCode,
        direction = knownDirection,
        type = knownType,
        amountKes = amount,
        feeKes = fee,
        balanceAfterKes = balance,
        occurredAtMillis = occurredAt,
        counterparty = coder.code(counterpartyPhone, counterpartyName),
        parserVersion = parserVersion,
        appVersion = appVersion,
    )
}

/** A non-negative amount to the cent, or null. */
private fun shillings(value: Double): BigDecimal? =
    value.takeIf { it.isFinite() && it >= 0 }
        ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
        ?.takeIf { it < MAX_AMOUNT }
