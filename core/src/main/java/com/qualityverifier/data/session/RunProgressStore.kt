package com.qualityverifier.data.session

import com.qualityverifier.domain.PlanRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where a plan's photos and test answers are kept while the customer collects them (v0.7.1,
 * release scope row 57: "Kagua carries on unfinished checks", Nathan, 24 November 2026).
 *
 * Until v0.7.1 the run lived only in the screen's view model, so leaving an assessment before
 * sending it lost every photo taken for it: reopened from My reports, it asked for all of them
 * again. Kept now beside the session's photos, so it goes when the session's photos go.
 */
interface RunProgressStore {
    suspend fun load(sessionId: String): RunProgress?
    suspend fun save(sessionId: String, progress: RunProgress)
    suspend fun clear(sessionId: String)

    /** Keeps nothing: the tests that are not about resuming, and any screen that does not resume. */
    object None : RunProgressStore {
        override suspend fun load(sessionId: String): RunProgress? = null
        override suspend fun save(sessionId: String, progress: RunProgress) = Unit
        override suspend fun clear(sessionId: String) = Unit
    }
}

/**
 * A run's progress: which plan (the reply that issued it), each shot's photo by its place in
 * the plan (null for a shot the customer could not take), and each test's answer by place
 * (null for a test skipped).
 */
@Serializable
data class RunProgress(
    val sourceMessageId: String,
    val shots: Map<Int, String?> = emptyMap(),
    val answers: Map<Int, String?> = emptyMap(),
) {
    /**
     * Back onto [run], a run just made from the same plan. A photo whose file has gone is
     * dropped, so its shot is asked for again rather than sent as nothing.
     */
    fun restore(run: PlanRun, exists: (String) -> Boolean = { File(it).isFile }): PlanRun =
        if (run.sourceMessageId != sourceMessageId) {
            run
        } else {
            run.copy(
                shots = shots.filter { (index, path) -> index in run.plan.photos.indices && (path == null || exists(path)) },
                answers = answers.filterKeys { it in run.plan.tests.indices },
            )
        }

    companion object {
        fun of(run: PlanRun) = RunProgress(run.sourceMessageId, run.shots, run.answers)
    }
}

/** One small file in each session's photo folder ([folderOf]), deleted with the folder. */
class FileRunProgressStore(private val folderOf: (String) -> File) : RunProgressStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileOf(sessionId: String) = File(folderOf(sessionId), FILE_NAME)

    override suspend fun load(sessionId: String): RunProgress? =
        runCatching { json.decodeFromString<RunProgress>(fileOf(sessionId).readText()) }.getOrNull()

    override suspend fun save(sessionId: String, progress: RunProgress) {
        runCatching {
            val file = fileOf(sessionId)
            val temporary = File(file.parentFile, "$FILE_NAME.tmp")
            temporary.writeText(json.encodeToString(RunProgress.serializer(), progress))
            temporary.renameTo(file)
        }
    }

    override suspend fun clear(sessionId: String) {
        runCatching { fileOf(sessionId).delete() }
    }

    private companion object {
        const val FILE_NAME = "run_progress.json"
    }
}
