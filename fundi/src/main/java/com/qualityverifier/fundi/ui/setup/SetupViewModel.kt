package com.qualityverifier.fundi.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) : ViewModel() {

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
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SetupViewModel(container.fundiProfiles, container.locationCapture::captureNow)
            }
        }
    }
}
