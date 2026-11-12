package com.qualityverifier.server.routes

import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolChangeReason
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The maker's setup answers on the wire.
 *
 * Ids rather than enum names, because these are the same strings the database CHECKs and
 * the prompt use. A value this build has never heard of is dropped on the way in rather
 * than refused: prompts are data and the vocabularies grow, so an older server meeting a
 * newer app should store the tools it understands instead of rejecting the whole profile.
 */
@Serializable
data class FundiProfileDto(
    val workshop: WorkshopDto = WorkshopDto(),
    val tools: List<ToolDto> = emptyList(),
    val goals: List<String> = emptyList(),
)

@Serializable
data class WorkshopDto(
    @SerialName("works_at") val worksAt: String? = null,
    @SerialName("years_in_trade") val yearsInTrade: Int? = null,
    val workers: Int? = null,
    val makes: String? = null,
    @SerialName("pieces_per_month") val piecesPerMonth: Int? = null,
    @SerialName("usual_timber") val usualTimber: String? = null,
    @SerialName("rents_tools") val rentsTools: Boolean = false,
)

@Serializable
data class ToolDto(
    val kind: String,
    val ownership: String,
    @SerialName("day_rate_kes") val dayRateKes: Int? = null,
    /**
     * Why this differs from the answer we already hold, when the app asked.
     *
     * Inbound only, and absent from what we send back: it describes a transition, and the
     * transitions live in their own history rather than on the current answer. An app
     * reading a profile is asking what the maker has today.
     */
    @SerialName("change_reason") val changeReason: String? = null,
    @SerialName("change_note") val changeNote: String? = null,
)

fun FundiProfileDto.toDomain(): FundiProfile = FundiProfile(
    workshop = Workshop(
        worksAt = workshop.worksAt,
        yearsInTrade = workshop.yearsInTrade,
        workers = workshop.workers,
        makes = workshop.makes,
        piecesPerMonth = workshop.piecesPerMonth,
        usualTimber = workshop.usualTimber,
        rentsTools = workshop.rentsTools,
    ),
    tools = tools.mapNotNull { tool ->
        val kind = ToolKind.fromId(tool.kind) ?: return@mapNotNull null
        val ownership = ToolOwnership.fromId(tool.ownership) ?: return@mapNotNull null
        OwnedTool(
            kind = kind,
            ownership = ownership,
            dayRateKes = tool.dayRateKes,
            // An unrecognised reason costs the reason, never the change. Losing "they
            // sold it" because the word for why was new would be the worse trade.
            changeReason = tool.changeReason?.let(ToolChangeReason::fromId),
            changeNote = tool.changeNote,
        )
    },
    goals = goals.mapNotNull(FundiGoal::fromId).toSet(),
)

fun FundiProfile.toDto(): FundiProfileDto = FundiProfileDto(
    workshop = WorkshopDto(
        worksAt = workshop.worksAt,
        yearsInTrade = workshop.yearsInTrade,
        workers = workshop.workers,
        makes = workshop.makes,
        piecesPerMonth = workshop.piecesPerMonth,
        usualTimber = workshop.usualTimber,
        rentsTools = workshop.rentsTools,
    ),
    tools = tools.map { ToolDto(it.kind.id, it.ownership.id, it.dayRateKes) },
    goals = goals.map { it.id },
)

/** One worker on the roster, as the phone reads it back. Archived ones are included. */
@Serializable
data class WorkerDto(
    val id: String,
    val name: String? = null,
    @SerialName("archived_at") val archivedAt: Long? = null,
    /** E.164, or null for an entry without a number (V30). */
    val phone: String? = null,
    /** `employee` or `temporary` (V30). Defaulted only so an older server's answer still reads. */
    val employment: String? = null,
    /** The two-sided match (V31): this worker's own account lists this workshop too. */
    val linked: Boolean = false,
)

@Serializable
data class WorkerListDto(val workers: List<WorkerDto>)

/** One studio this account works at (V31), as the phone reads it back. Archived ones are included. */
@Serializable
data class StudioDto(
    val id: String,
    val name: String? = null,
    /** E.164; null only once the account is deleted. */
    val phone: String? = null,
    @SerialName("archived_at") val archivedAt: Long? = null,
    /** The two-sided match: the studio's roster names this account's number too. */
    val linked: Boolean = false,
)

@Serializable
data class StudioListDto(val studios: List<StudioDto>)

/** The body of PUT /v1/fundi/studios/{id}. Both fields on every write; archiving is sticky. */
@Serializable
data class StudioUpsertDto(
    val name: String? = null,
    val phone: String? = null,
    val archived: Boolean = false,
)

/**
 * The body of PUT /v1/fundi/workers/{id}. Archiving is sticky: false never revives.
 *
 * [phone] absent leaves the stored number alone, blank removes it, and anything else must
 * be a number the route can read (see `PhoneChange`). An app from before V30 sends neither
 * it nor [employment], and its writes change only the name and the archive flag.
 */
@Serializable
data class WorkerUpsertDto(
    val name: String? = null,
    val archived: Boolean = false,
    val phone: String? = null,
    val employment: String? = null,
)

/** The body of PUT /v1/fundi/pieces/{id}. A blank or absent label clears the name. */
@Serializable
data class PieceLabelDto(val label: String? = null)

/** One fix of the maker's shop. All three, always: a point without its accuracy is a false precision. */
@Serializable
data class WorkshopLocationDto(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyMetres: Double,
) {
    /** On the planet, and with an accuracy that means something. */
    val isPlausible: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracyMetres > 0 && accuracyMetres <= MAX_ACCURACY_METRES

    companion object {
        /** Past this it no longer says which trading centre, which is what the point is for. */
        const val MAX_ACCURACY_METRES = 5_000.0
    }
}

