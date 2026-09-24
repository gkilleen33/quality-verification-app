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
