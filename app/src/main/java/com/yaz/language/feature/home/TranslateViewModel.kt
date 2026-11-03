package com.yaz.language.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.translate.DownloadId
import com.yaz.language.translate.DownloadManager
import com.yaz.language.translate.DownloadRequest
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.TranslationCoordinator
import com.yaz.language.translate.TranslationResult
import com.yaz.language.translate.languageNameComparator
import com.yaz.language.translate.localizedName
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A language the pair still needs, its size and where its download stands. */
data class GetItem(val language: Language, val bytes: Long, val status: DownloadStatus?)

/** What the translate screen shows. */
data class TranslateUi(
    /** The whole catalogue, installed ones first, each group by name in the phone's language. */
    val languages: List<Language> = emptyList(),
    /** Codes of the languages on the phone. */
    val onPhone: Set<String> = emptySet(),
    /** The user's pick, null for Detect. */
    val source: Language? = null,
    val target: Language? = null,
    val input: String = "",
    /** What the text looks like, kept while it is still valid ([refreshedDetection]). */
    val detected: Language? = null,
    val output: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** The packs the pair needs and the phone lacks. */
    val toGet: List<GetItem> = emptyList(),
    /** Both ends are on the phone yet the pair does not translate (a language that only goes to English). */
    val unavailable: Boolean = false,
    val networkAllowed: Boolean = true
) {
    /** The language the text is translated from: the pick, else what was detected. */
    val from: Language? get() = source ?: detected
}

/**
 * The translate screen's state. The flow is offline-translator's TranslationSession (v0.8.7, commit
 * e7716c6859e3d90b8883c667c1a3cfeef407df08, GPL-3.0, ui/TranslationSession.kt and ui/TranslatorViewModel.kt):
 * one translation job at a time that every change cancels (replaceJob), detection before each run with the picked
 * source as hint (withDetectedSource, refreshedDetection), a swap that drops itself when it no longer translates
 * (MainSelectionRules.swapped), a pick of the detected language that moves the old source to the target
 * (FromLangChange.MovePreviousToTarget). Cut: images, documents, dictionary, speech out, transliteration, steering,
 * target tabs. Changed: the pickers offer the whole catalogue, not only installed languages, so the pair can name a
 * pack to get ([languagesToGet]); Detect is a null source; a short pause before a typed text runs, as the
 * restart-on-input pattern of kotlinx.coroutines' Flow.debounce
 * (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/debounce.html).
 * No Log of the text (offline-translator's TranslatorViewModel logs it; OWASP MASVS-STORAGE-2). Translation runs
 * only while this screen lives, in viewModelScope.
 */
class TranslateViewModel(
    private val store: SettingsStore,
    private val coordinator: TranslationCoordinator,
    private val states: LanguageStateManager,
    private val downloads: DownloadManager
) : ViewModel() {

    private data class Core(
        val source: Language? = null,
        val target: Language? = null,
        val input: String = "",
        val detection: LanguageDetection? = null,
        val output: String? = null,
        val busy: Boolean = false,
        val error: String? = null
    )

    private val core = MutableStateFlow(initialCore())
    private var job: Job? = null

    // What the text field shows, written at once on the main thread: a field fed by a flow that is
    // computed on another dispatcher would lose letters and jump its cursor.
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    val ui: StateFlow<TranslateUi> =
        combine(core, states.catalog, states.languageState, downloads.state, store.settings) { c, catalog, availability, runs, settings ->
            val comparator = languageNameComparator()
            val onPhone = availability.availableLanguages
                .filter { it.availability.translatorFiles || (it.language.isEnglish && availability.hasLanguages) }
                .map { it.language.code }
                .toSet()
            val languages = catalog.languageList.sortedWith(
                compareByDescending<Language> { it.code in onPhone }.thenBy(comparator) { it.localizedName() }
            )
            val from = c.source ?: c.detection?.language
            val toGet = if (from != null && c.target != null && from != c.target) {
                languagesToGet(availability, from, c.target).map {
                    GetItem(it, catalog.sizeBytesForFeature(it.code), runs[DownloadId.Core(it)])
                }
            } else {
                emptyList()
            }
            val unavailable = toGet.isEmpty() && c.input.isNotBlank() && from != null && c.target != null &&
                from != c.target && !catalog.canTranslate(from, c.target)
            TranslateUi(
                languages = languages,
                onPhone = onPhone,
                source = c.source,
                target = c.target,
                input = c.input,
                detected = c.detection?.language,
                output = c.output,
                busy = c.busy,
                error = c.error,
                toGet = toGet,
                unavailable = unavailable,
                networkAllowed = settings.networkAllowed
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, TranslateUi())

    init {
        // A pack that arrives or goes changes what the pair can do: translate again (offline-translator's
        // TranslationSession collects languageState the same way).
        viewModelScope.launch {
            states.languageState.drop(1).collect { if (core.value.input.isNotBlank()) run(pause = false) }
        }

        // The model of a pair that can translate is loaded as soon as the pair is known, so the first result does not
        // wait for it: offline-translator's TranslationSession (the end of init), where the ready selections are
        // mapped to their pair, distinctUntilChanged, and each new pair preloaded. A selection is ready here when both
        // ends are known and installed (the pick or what was detected, and the target). Off the main thread
        // (TranslationService.preloadModel runs on IO); a failure is ignored, the translation loads the model itself.
        viewModelScope.launch {
            combine(core, states.languageState) { c, _ ->
                val from = c.source ?: c.detection?.language
                val to = c.target
                if (from != null && to != null && from != to && states.canTranslate(from, to)) Pair(from, to) else null
            }
                .flowOn(Dispatchers.Default)
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (from, to) ->
                    try {
                        coordinator.preloadModel(from, to)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                    }
                }
        }
    }

    private fun initialCore(): Core {
        val catalog = states.catalog.value
        val settings = store.current
        val source = settings.sourceLanguage.takeIf { it.isNotEmpty() }?.let(catalog::languageByCode)
        val target = settings.targetLanguage.takeIf { it.isNotEmpty() }?.let(catalog::languageByCode)
            ?: catalog.languageByCode(Locale.getDefault().language)
            ?: catalog.languageByCode("en")
        return Core(source = source?.takeIf { it != target }, target = target)
    }

    /** The text changed (typed, pasted, dictated). */
    fun setText(text: String) {
        if (_input.value == text) return
        _input.value = text
        // The last result stays until the new one comes, so a typed letter does not blank the card.
        core.update {
            it.copy(input = text, detection = if (text.isBlank()) null else it.detection, output = if (text.isBlank()) null else it.output, error = null)
        }
        if (text.isBlank()) {
            job?.cancel()
            core.update { it.copy(busy = false) }
        } else {
            run(pause = true)
        }
    }

    fun clear() = setText("")

    /**
     * Picks the source, null for Detect. When it is the target the old source takes the target's place, as
     * offline-translator moves the previous source to the target (FromLangChange.MovePreviousToTarget).
     */
    fun pickSource(language: Language?) {
        val c = core.value
        val previous = c.source ?: c.detection?.language
        val target = if (language != null && language == c.target && previous != null && previous != language) previous else c.target
        change(language, target)
    }

    fun pickTarget(language: Language) {
        val c = core.value
        val previous = c.source ?: c.detection?.language
        val source = if (language == c.source && previous != null) c.target else c.source
        change(source, language)
    }

    /** Swaps the two ends. Dropped when nothing is known to swap, as MainSelectionRules.swapped drops it. */
    fun swap(): Boolean {
        val c = core.value
        val from = c.source ?: c.detection?.language ?: return false
        val to = c.target ?: return false
        if (from == to) return false
        change(to, from)
        return true
    }

    private fun change(source: Language?, target: Language?) {
        core.update { it.copy(source = source, target = target, output = null, error = null) }
        store.update { it.copy(sourceLanguage = source?.code.orEmpty(), targetLanguage = target?.code.orEmpty()) }
        if (core.value.input.isNotBlank()) run(pause = false)
    }

    /** Asks for the packs; the Network switch is checked here too, DownloadManager refuses while it is off. */
    fun get(items: List<GetItem>) {
        if (!store.current.networkAllowed) return
        items.forEach { downloads.enqueue(DownloadRequest.Core(it.language)) }
    }

    fun cancel(items: List<GetItem>) = items.forEach { downloads.cancel(DownloadId.Core(it.language)) }

    private fun run(pause: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            if (pause) delay(TYPING_PAUSE_MILLIS)
            withDetectedSource()
            translate()
        }
    }

    // offline-translator's TranslationSession.withDetectedSource, minus the auto-source selection (Detect is a null source).
    private suspend fun withDetectedSource() {
        val c = core.value
        val text = c.input
        val detected = if (text.isBlank()) null else {
            coordinator.detectLanguageRobust(text, c.source, states.languageState.value.allLanguages())
        }
        core.update { it.copy(detection = refreshedDetection(it.detection, it.input, detected)) }
    }

    // offline-translator's TranslationSession.translate (text input): trim, translate, show the error or the result.
    private suspend fun translate() {
        val c = core.value
        val text = c.input.trim()
        val from = c.source ?: c.detection?.language
        val to = c.target
        if (text.isEmpty() || from == null || to == null) {
            core.update { it.copy(output = null, busy = false) }
            return
        }
        if (from == to) {
            core.update { it.copy(output = text, busy = false, error = null) }
            return
        }
        val can = withContext(Dispatchers.Default) { states.canTranslate(from, to) }
        if (!can) {
            core.update { it.copy(output = null, busy = false, error = null) }
            return
        }
        core.update { it.copy(busy = true, error = null) }
        when (val result = coordinator.translateText(from, to, text)) {
            is TranslationResult.Success -> core.update { it.copy(output = result.result.translated, busy = false, error = null) }
            is TranslationResult.Error -> core.update { it.copy(output = null, busy = false, error = result.message) }
        }
    }

    private companion object {
        const val TYPING_PAUSE_MILLIS = 300L
    }
}
