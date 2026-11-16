package com.qualityverifier.tracker.ui.terms

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.terms.AgreeOutcome
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.terms.TermsInfo
import com.qualityverifier.data.terms.TermsLanguageInfo
import com.qualityverifier.text.TermsDocument
import com.qualityverifier.text.TermsLabels
import com.qualityverifier.text.sha256Hex
import com.qualityverifier.tracker.TrackerContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TermsLoad {
    data object Loading : TermsLoad

    /** No text could be read: offline with no matching copy, or a hash that did not match. */
    data object Failed : TermsLoad

    /** A text to show. [key] changes whenever the ticks must start again. */
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
 * PesaLog's terms screen's data, as Fundi Bora's TermsViewModel: the text `/v1/tracker/status`
 * names, checked by hash, shown from the APK's bundled copy when its hash is the server's and
 * otherwise from the server; both boxes sent with the hash of the bytes shown.
 */
class TermsViewModel(
    private val api: TermsApi,
    /** Reads a file bundled in the APK by its path under `terms/`, or null if absent. */
    private val bundled: (path: String) -> ByteArray?,
) : ViewModel() {

    private val _load = MutableStateFlow<TermsLoad>(TermsLoad.Loading)
    val load: StateFlow<TermsLoad> = _load.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True once the terms are agreed, here or already. */
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var info: TermsInfo? = null
    private var reloads = 0
    private var started = false
    private var labels: TermsLabels = TermsLabels.ENGLISH

    fun start(labels: TermsLabels) {
        this.labels = labels
        if (started) return
        started = true
        reload()
    }

    fun reload(language: String? = null) {
        viewModelScope.launch {
            _load.value = TermsLoad.Loading
            val status = api.status()
            if (status == null) {
                _load.value = TermsLoad.Failed
                return@launch
            }
            info = status
            if (!status.required) {
                _done.value = true
                return@launch
            }
            show(status, language)
        }
    }

    fun chooseLanguage(language: String) {
        val status = info ?: return
        viewModelScope.launch { show(status, language) }
    }

    private suspend fun show(status: TermsInfo, language: String?) {
        // Required with no version: the server holds no text for PesaLog's terms, and refuses
        // until it does. Nothing to show or agree to.
        val version = status.version ?: run {
            _load.value = TermsLoad.Failed
            return
        }
        val chosen = status.languages.firstOrNull { it.language == language }
            ?: status.languages.firstOrNull { it.language == ENGLISH }
            ?: status.languages.firstOrNull()
        val bytes = chosen?.let { textFor(version, it) }
        val document = bytes?.let { runCatching { TermsDocument.parse(String(it, Charsets.UTF_8)) }.getOrNull() }
        if (chosen == null || document == null) {
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

    private suspend fun textFor(version: String, language: TermsLanguageInfo): ByteArray? {
        bundled("tracker/$version/${language.language}.md")
            ?.takeIf { sha256Hex(it) == language.sha256 }
            ?.let { return it }
        return api.text(language.path)?.takeIf { sha256Hex(it) == language.sha256 }
    }

    fun agree(ready: TermsLoad.Ready) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = null
            try {
                when (api.agree(ready.version, ready.language, ready.sha256)) {
                    AgreeOutcome.Agreed -> _done.value = true
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

    companion object {
        private const val ENGLISH = "en"

        fun factory(container: TrackerContainer, assets: AssetManager): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TermsViewModel(
                    api = container.terms,
                    bundled = { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() },
                )
            }
        }
    }
}
