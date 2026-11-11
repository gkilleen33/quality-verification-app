package com.qualityverifier.fundi.ui.terms

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.terms.AgreeOutcome
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.terms.TermsInfo
import com.qualityverifier.data.terms.TermsLanguageInfo
import com.qualityverifier.di.AppContainer
import com.qualityverifier.text.TermsDocument
import com.qualityverifier.text.TermsLabels
import com.qualityverifier.text.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the maker goes once the terms are agreed, or found already agreed. */
enum class AfterTerms {
    /** No workshop profile yet: a new account, or one that skipped setup. */
    SETUP,
    HOME,
}

sealed interface TermsLoad {
    data object Loading : TermsLoad

    /** No text could be read: offline with no matching copy, or a hash that did not match. */
    data object Failed : TermsLoad

    /**
     * A text to show. [key] changes whenever the ticks must start again: another language,
     * another text, or the same text reloaded after the server said it had changed.
     */
    data class Ready(
        val document: TermsDocument,
        val version: String,
        val language: String,
        val sha256: String,
        val languages: List<String>,
        val key: String,
    ) : TermsLoad
}

/**
 * The terms screen's data: which text to show, sending both agreements, and where to go next.
 *
 * The text is the one `/v1/me` names, checked by hash. The APK bundles the English file of
 * its build's version (from the repository's `terms/` folder), and the phone shows that
 * copy without a download when its hash is the one the server lists. Otherwise it fetches
 * the server's copy and shows it only if the hash matches. The agreement sends the hash of
 * the bytes shown, so the record says exactly what was on screen.
 */
class TermsViewModel(
    private val api: TermsApi,
    private val profiles: FundiProfiles,
    /** Reads a file bundled in the APK by its path under `terms/`, or null if absent. */
    private val bundled: (path: String) -> ByteArray?,
) : ViewModel() {

    private val _load = MutableStateFlow<TermsLoad>(TermsLoad.Loading)
    val load: StateFlow<TermsLoad> = _load.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _done = MutableStateFlow<AfterTerms?>(null)
    val done: StateFlow<AfterTerms?> = _done.asStateFlow()

    private var info: TermsInfo? = null
    private var reloads = 0
    private var started = false
    private var labels: TermsLabels = TermsLabels.ENGLISH

    /** Once per view model: a rotation keeps the text it already has. */
    fun start(labels: TermsLabels) {
        this.labels = labels
        if (started) return
        started = true
        reload()
    }

    /** Asks the server again: on Retry, and after it said the text had changed. */
    fun reload(language: String? = null) {
        viewModelScope.launch {
            _load.value = TermsLoad.Loading
            val status = api.status()
            if (status == null) {
                _load.value = TermsLoad.Failed
                return@launch
            }
            info = status
            // Nothing to agree to: another phone already did, or the app has no terms.
            if (!status.required || status.version == null) {
                finish()
                return@launch
            }
            show(status, language)
        }
    }

    /** Shows the same version in another language. Its ticks start again. */
    fun chooseLanguage(language: String) {
        val status = info ?: return
        viewModelScope.launch { show(status, language) }
    }

    private suspend fun show(status: TermsInfo, language: String?) {
        val version = status.version ?: return
        val chosen = status.languages.firstOrNull { it.language == language }
            ?: status.languages.firstOrNull { it.language == ENGLISH }
            ?: status.languages.firstOrNull()
        if (chosen == null) {
            _load.value = TermsLoad.Failed
            return
        }
        val bytes = textFor(version, chosen)
        if (bytes == null) {
            _load.value = TermsLoad.Failed
            return
        }
        val document = runCatching { TermsDocument.parse(String(bytes, Charsets.UTF_8)) }.getOrNull()
        if (document == null) {
            _load.value = TermsLoad.Failed
            return
        }
        reloads++
        _load.value = TermsLoad.Ready(
            document = document,
            version = version,
            language = chosen.language,
            sha256 = chosen.sha256,
            languages = status.languages.map { it.language },
            key = "${chosen.sha256}/$reloads",
        )
    }

    /** The bundled copy when its hash is the server's, otherwise the server's copy when its hash matches. */
    private suspend fun textFor(version: String, language: TermsLanguageInfo): ByteArray? {
        bundled("fundi/$version/${language.language}.md")
            ?.takeIf { sha256Hex(it) == language.sha256 }
            ?.let { return it }
        return api.text(language.path)?.takeIf { sha256Hex(it) == language.sha256 }
    }

    fun agree(ready: TermsLoad.Ready) {
        if (_busy.value) return
        // Set before launching, as FundiAuthViewModel does, so two taps send one request.
        _busy.value = true
        viewModelScope.launch {
            _message.value = null
            try {
                when (api.agree(ready.version, ready.language, ready.sha256)) {
                    AgreeOutcome.Agreed -> finish()
                    AgreeOutcome.TextChanged -> {
                        _message.value = labels.textChanged
                        reload(ready.language)
                    }
                    AgreeOutcome.Offline -> _message.value = labels.offline
                    AgreeOutcome.Failed -> _message.value = labels.sendFailed
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Setup when the server holds no workshop profile, home otherwise. Unreachable reads as
     * home: setup can be reached from there, and a maker should not be sent through it again
     * for want of signal.
     */
    private suspend fun finish() {
        _done.value = when (profiles.load()) {
            ProfileOutcome.NotSetUpYet -> AfterTerms.SETUP
            else -> AfterTerms.HOME
        }
    }

    companion object {
        private const val ENGLISH = "en"

        fun factory(container: AppContainer, assets: AssetManager): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TermsViewModel(
                    api = container.terms,
                    profiles = container.fundiProfiles,
                    bundled = { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() },
                )
            }
        }
    }
}
