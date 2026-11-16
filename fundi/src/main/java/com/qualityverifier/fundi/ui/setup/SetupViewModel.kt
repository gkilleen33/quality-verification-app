package com.qualityverifier.fundi.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.fundi.BenchApi
import com.qualityverifier.data.fundi.BenchOutcome
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.location.LocationSource
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.BenchTool
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The maker's setup answers, held while they work through the three screens.
 *
 * One view model across all three rather than one each, because they are one answer: the
 * profile is sent whole, since the server derives what changed by comparing against what
 * it holds. Three separate saves would write three partial profiles and record a tool
 * history of the maker apparently acquiring their own tools one screen at a time.
 */
class SetupViewModel(
    private val profiles: FundiProfiles,
    /** One GPS fix, now — `LocationCapture.captureNow`. Null when none arrived. */
    private val locate: suspend () -> LocationFix? = { null },
    /** The bench photo's draft (v0.6.0, row 5). Null in tests that never photograph a bench. */
    private val bench: BenchApi? = null,
    /** Where the bench photo is written and processed, as an assessment's photos are. */
    private val images: SessionImageStore? = null,
    /**
     * The bench photo's GPS point, only when the fundi allows location: the same switch,
     * answer and permission an assessment's point needs (`LocationSource.isAvailable`).
     */
    private val benchLocation: LocationSource = LocationSource.None,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * The bench photo at setup (v0.6.0, release scope row 5; design of 3 and 4 November
     * 2026): before the typed list, one photo of the bench, from which Claude drafts the
     * tools; then each drafted tool is put to the fundi, and only one they confirm and say
     * who owns is set. Nothing is set from the draft alone, and nothing reaches the server
     * until the profile is saved at the end of setup, as a typed answer does.
     */
    sealed interface Bench {
        /** The offer: take a photo, or skip to the typed list. */
        data object Intro : Bench
        /** The photo is being processed, sent, and read. */
        data object Sending : Bench
        /** Is [BenchTool] number [index] of [draft] on the bench? Then, once yes, who owns it. */
        data class Confirm(val draft: List<BenchTool>, val index: Int, val askingOwner: Boolean) : Bench
        /** Every drafted tool answered, or skipped: on to the typed list, prefilled. */
        data object Done : Bench
        data class Failed(val reason: Reason) : Bench

        enum class Reason { LIMIT, UNAVAILABLE, UNREADABLE }
    }

    private val _bench = MutableStateFlow<Bench>(Bench.Intro)
    val benchStep: StateFlow<Bench> = _bench.asStateFlow()

    /** The photo being sent, and its id, kept so a retry is the same photo to the server. */
    private var benchPhoto: Pair<String, File>? = null

    /**
     * The photo processed for upload, once. Processing again on a retry would re-encode the
     * file and change its hash, so the server would hold one photo and be sent another.
     */
    private var benchProcessed: Deferred<Boolean>? = null

    /**
     * The GPS point, started when the photo is taken and waited for at most
     * [BENCH_FIX_WAIT_MILLIS] (a capture may take a minute and a half). A retry reuses it.
     */
    private var benchFix: Deferred<LocationFix?>? = null

    /** Where the camera writes the bench photo. Null when it cannot be made. */
    fun newBenchFile(): File? = runCatching { images?.newImageFile(BENCH_FOLDER) }.getOrNull()

    /** The camera has written the photo: process it as an assessment's, then ask for the draft. */
    fun onBenchPhoto(file: File) {
        benchPhoto = UUID.randomUUID().toString() to file
        benchProcessed = viewModelScope.async(io) {
            runCatching { file.length() > 0 && images?.normaliseInPlace(file) != false }.getOrDefault(false)
        }
        benchFix = if (benchLocation.isAvailable) {
            viewModelScope.async { runCatching { benchLocation.capture() }.getOrNull() }
        } else {
            null
        }
        sendBench()
    }

    /** Sends the photo again after a failure that a retry can clear. */
    fun retryBench() = sendBench()

    private fun sendBench() {
        // A second tap while the first send is under way would start a second upload.
        if (_bench.value == Bench.Sending) return
        val (photoId, file) = benchPhoto ?: return
        val processed = benchProcessed ?: return
        val api = bench ?: return
        _bench.value = Bench.Sending
        viewModelScope.launch {
            if (!processed.await()) {
                _bench.value = Bench.Failed(Bench.Reason.UNREADABLE)
                return@launch
            }
            val fix = benchFix?.let { pending -> withTimeoutOrNull(BENCH_FIX_WAIT_MILLIS) { pending.await() } }
            _bench.value = when (val outcome = runCatching { api.draft(photoId, file, fix) }.getOrDefault(BenchOutcome.Unavailable)) {
                is BenchOutcome.Drafted ->
                    if (outcome.draft.tools.isEmpty()) Bench.Done
                    else Bench.Confirm(outcome.draft.tools, index = 0, askingOwner = false)
                BenchOutcome.LimitReached -> Bench.Failed(Bench.Reason.LIMIT)
                BenchOutcome.Unavailable -> Bench.Failed(Bench.Reason.UNAVAILABLE)
                BenchOutcome.Refused -> Bench.Failed(Bench.Reason.UNREADABLE)
            }
        }
    }

    /** "Is this on your bench?" A yes asks who owns it; a no leaves the tool unset and moves on. */
    fun confirmTool(onBench: Boolean) {
        val current = _bench.value as? Bench.Confirm ?: return
        if (current.askingOwner) return
        _bench.value = if (onBench) current.copy(askingOwner = true) else next(current)
    }

    /** Who owns the confirmed tool: only now is it set, with that owner. */
    fun chooseOwner(owner: ToolOwnership) {
        val current = _bench.value as? Bench.Confirm ?: return
        if (!current.askingOwner || !owner.toHand) return
        val kind = ToolKind.fromId(current.draft[current.index].kind) ?: return
        setTool(kind, owner)
        _bench.value = next(current)
    }

    /** Straight to the typed list, from the offer or part way through the draft. */
    fun skipBench() {
        _bench.value = Bench.Done
    }

    private fun next(current: Bench.Confirm): Bench =
        if (current.index + 1 < current.draft.size) current.copy(index = current.index + 1, askingOwner = false)
        else Bench.Done

    /**
     * The shop's GPS point: two questions, then a fix.
     *
     * "Are you at your shop?" comes first because a point taken anywhere else is a point
     * in the wrong place, recorded as accurate. Only a yes leads to the second question,
     * whether we may record it — its own consent, separate from anything else in setup.
     */
    data class ShopLocation(
        val atShop: Boolean? = null,
        val consent: Boolean? = null,
        val locating: Boolean = false,
        val fix: LocationFix? = null,
        val problem: Problem? = null,
    ) {
        enum class Problem { NO_PERMISSION, NO_FIX }
    }

    private val _shop = MutableStateFlow(ShopLocation())
    val shop: StateFlow<ShopLocation> = _shop.asStateFlow()

    /** Whether the fix in [shop] has reached the server, so a retried save does not send it twice. */
    private var shopFixSent = false

    fun answerAtShop(atShop: Boolean) {
        // A no forgets any point already taken: it was taken on the understanding they
        // were at the shop.
        _shop.value = if (atShop) _shop.value.copy(atShop = true) else ShopLocation(atShop = false)
    }

    fun answerConsent(consent: Boolean) {
        _shop.value = if (consent) {
            _shop.value.copy(consent = true, problem = null)
        } else {
            ShopLocation(atShop = _shop.value.atShop, consent = false)
        }
    }

    /** Called once Android has answered the permission prompt. */
    fun onPermission(granted: Boolean) {
        if (!granted) {
            _shop.value = _shop.value.copy(problem = ShopLocation.Problem.NO_PERMISSION)
            return
        }
        locateShop()
    }

    fun locateShop() {
        val current = _shop.value
        if (current.locating || current.atShop != true || current.consent != true) return
        _shop.value = current.copy(locating = true, problem = null)
        viewModelScope.launch {
            val fix = runCatching { locate() }.getOrNull()?.takeIf { it.isUsable }
            shopFixSent = false
            _shop.value = _shop.value.copy(
                locating = false,
                fix = fix,
                problem = if (fix == null) ShopLocation.Problem.NO_FIX else null,
            )
        }
    }

    private val _workshop = MutableStateFlow(Workshop())
    val workshop: StateFlow<Workshop> = _workshop.asStateFlow()

    /**
     * Only the tools they have actually answered about.
     *
     * Absent is not the same as [ToolOwnership.NONE], all the way down to the prompt: a
     * missing tool means nobody asked, and `NONE` means they said they have none. Only
     * the second lets the coaching work around it, so an unanswered toggle must not
     * default to anything.
     */
    private val _tools = MutableStateFlow<Map<ToolKind, ToolOwnership>>(emptyMap())
    val tools: StateFlow<Map<ToolKind, ToolOwnership>> = _tools.asStateFlow()

    private val _goals = MutableStateFlow<Set<FundiGoal>>(emptySet())
    val goals: StateFlow<Set<FundiGoal>> = _goals.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /**
     * Loads what the server already holds, so re-running setup edits rather than
     * replaces. Silent on failure: an empty form is a worse answer than a stale one, but
     * both are better than blocking somebody out of their own setup because they are
     * underground with no signal.
     */
    fun loadExisting() {
        viewModelScope.launch {
            when (val outcome = profiles.load()) {
                is ProfileOutcome.Loaded -> {
                    _workshop.value = outcome.profile.workshop
                    _tools.value = outcome.profile.tools.associate { it.kind to it.ownership }
                    _goals.value = outcome.profile.goals
                }
                else -> Unit
            }
        }
    }

    fun updateWorkshop(update: (Workshop) -> Workshop) {
        _workshop.value = update(_workshop.value)
    }

    fun setTool(kind: ToolKind, ownership: ToolOwnership) {
        _tools.value = _tools.value + (kind to ownership)
    }

    fun toggleGoal(goal: FundiGoal) {
        _goals.value = if (goal in _goals.value) _goals.value - goal else _goals.value + goal
    }

    fun save() {
        if (_busy.value) return
        // Set before launching, not inside the coroutine. Checked outside and set inside,
        // the guard only works because viewModelScope happens to dispatch immediately —
        // two taps in one frame would otherwise both pass the check and both write. A
        // second write compares against the first and records a tool history of nothing
        // changing, which is noise in the one table the longitudinal claim rests on.
        _busy.value = true
        viewModelScope.launch {
            _failed.value = false
            try {
                val profile = FundiProfile(
                    workshop = _workshop.value,
                    tools = _tools.value.map { (kind, ownership) -> OwnedTool(kind, ownership) },
                    goals = _goals.value,
                )
                val profileSaved = profiles.save(profile)
                // Only with both answers still yes: a fix kept after a later "no" would be
                // recorded against the maker's wishes.
                val fix = _shop.value.fix?.takeIf { _shop.value.atShop == true && _shop.value.consent == true }
                val locationSaved = when {
                    !profileSaved -> false
                    fix == null || shopFixSent -> true
                    else -> profiles.saveWorkshopLocation(fix).also { shopFixSent = it }
                }
                if (profileSaved && locationSaved) _saved.value = true else _failed.value = true
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        /** The folder the bench photo is written to, beside the assessments' own. */
        const val BENCH_FOLDER = "bench"

        /**
         * How long the bench photo waits for its GPS point. The point is a check on where the
         * workshop is, not the reason for the photo; a fundi under a tin roof should see their
         * tools, not a spinner, and the photo goes without a point after this.
         */
        const val BENCH_FIX_WAIT_MILLIS = 15_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SetupViewModel(
                    profiles = container.fundiProfiles,
                    locate = container.locationCapture::captureNow,
                    bench = container.bench,
                    images = container.images,
                    benchLocation = container.locationCapture,
                )
            }
        }
    }
}
