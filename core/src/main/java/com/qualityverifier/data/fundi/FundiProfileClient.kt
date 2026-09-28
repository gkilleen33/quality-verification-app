package com.qualityverifier.data.fundi

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a read of the maker's setup answers came back with. */
sealed interface ProfileOutcome {
    data class Loaded(val profile: FundiProfile) : ProfileOutcome

    /**
     * Registered, never set up. Distinct from a profile with nothing in it: the first is
     * a setup flow to run, the second is a set of answers to leave alone.
     */
    data object NotSetUpYet : ProfileOutcome

    /** Offline, or the server is unwell. What is cached locally may still be right. */
    data object Unavailable : ProfileOutcome

    /** A Kagua account reached a Fundi Bora endpoint. A bug, not something to retry. */
    data object NotAFundi : ProfileOutcome
}

/**
 * Reading and writing the maker's setup answers.
 *
 * An interface for the same reason the server's stores are: the decision worth testing —
 * what a half-answered setup flow actually sends — lives in the view model, and stating
 * it needs a fake rather than a server.
 */
interface FundiProfiles {
    suspend fun load(): ProfileOutcome

    /**
     * Sends the whole profile. False means it did not land.
     *
     * Whole rather than per-field, because the server records what changed by comparing
     * against what it already holds — see `toolChanges`. A tool left out of an answer is
     * deliberately *not* treated as disposed of, so a partial write would silently
     * record half a history.
     */
    suspend fun save(profile: FundiProfile): Boolean
}

/**
 * The maker's workshop, tools and goals, as the server holds them.
 *
 * In `:core` rather than in `:fundi`, against this module's rule of holding only what
 * both apps need — because the rule it loses to is the stronger one. Every authenticated
 * client has to refresh through the single-flight provider or it can sign somebody out,
 * and keeping that in one place is worth more than keeping Kagua's classpath free of a
 * client it never constructs.
 */
class FundiProfileClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FundiProfiles {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun load(): ProfileOutcome = withContext(io) {
        val response = http.send { token ->
            Request.Builder().url(baseUrl + PATH)
                .addHeader("Authorization", "Bearer $token").get().build()
        } ?: return@withContext ProfileOutcome.Unavailable

        response.use {
            when {
                it.code == 404 -> ProfileOutcome.NotSetUpYet
                it.code == 403 -> ProfileOutcome.NotAFundi
                !it.isSuccessful -> ProfileOutcome.Unavailable
                else -> runCatching {
                    ProfileOutcome.Loaded(
                        json.decodeFromString<ProfileDto>(it.body!!.string()).toDomain()
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Could not read the profile", error)
                    ProfileOutcome.Unavailable
                }
            }
        }
    }

    override suspend fun save(profile: FundiProfile): Boolean = withContext(io) {
        val body = json.encodeToString(profile.toDto())
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = http.send { token ->
            Request.Builder().url(baseUrl + PATH)
                .addHeader("Authorization", "Bearer $token").put(body).build()
        } ?: return@withContext false
        response.use { it.isSuccessful }
    }

    private companion object {
        const val TAG = "FundiProfileClient"
        const val PATH = "v1/fundi/profile"
    }
}

// The wire shape, mirroring the server's FundiDto. Ids rather than enum names, because
// those are the strings the database CHECKs and the prompt use.

@Serializable
private data class ProfileDto(
    val workshop: WorkshopDto = WorkshopDto(),
    val tools: List<ToolDto> = emptyList(),
    val goals: List<String> = emptyList(),
)

@Serializable
private data class WorkshopDto(
    @SerialName("works_at") val worksAt: String? = null,
    @SerialName("years_in_trade") val yearsInTrade: Int? = null,
    val workers: Int? = null,
    val makes: String? = null,
    @SerialName("pieces_per_month") val piecesPerMonth: Int? = null,
    @SerialName("usual_timber") val usualTimber: String? = null,
    @SerialName("rents_tools") val rentsTools: Boolean = false,
)

@Serializable
private data class ToolDto(
    val kind: String,
    val ownership: String,
    @SerialName("day_rate_kes") val dayRateKes: Int? = null,
    @SerialName("change_reason") val changeReason: String? = null,
    @SerialName("change_note") val changeNote: String? = null,
)

private fun ProfileDto.toDomain() = FundiProfile(
    workshop = Workshop(
        worksAt = workshop.worksAt,
        yearsInTrade = workshop.yearsInTrade,
        workers = workshop.workers,
        makes = workshop.makes,
        piecesPerMonth = workshop.piecesPerMonth,
        usualTimber = workshop.usualTimber,
        rentsTools = workshop.rentsTools,
    ),
    // An id this build does not know is dropped rather than failing the whole profile:
    // the vocabularies grow with the prompts, and an older app meeting a newer server
    // should show the tools it understands.
    tools = tools.mapNotNull { tool ->
        val kind = ToolKind.fromId(tool.kind) ?: return@mapNotNull null
        val ownership = ToolOwnership.fromId(tool.ownership) ?: return@mapNotNull null
        OwnedTool(kind, ownership, tool.dayRateKes)
    },
    goals = goals.mapNotNull(FundiGoal::fromId).toSet(),
)

private fun FundiProfile.toDto() = ProfileDto(
    workshop = WorkshopDto(
        worksAt = workshop.worksAt,
        yearsInTrade = workshop.yearsInTrade,
        workers = workshop.workers,
        makes = workshop.makes,
        piecesPerMonth = workshop.piecesPerMonth,
        usualTimber = workshop.usualTimber,
        rentsTools = workshop.rentsTools,
    ),
    tools = tools.map {
        ToolDto(
            kind = it.kind.id,
            ownership = it.ownership.id,
            dayRateKes = it.dayRateKes,
            changeReason = it.changeReason?.id,
            changeNote = it.changeNote,
        )
    },
    goals = goals.map { it.id },
)
