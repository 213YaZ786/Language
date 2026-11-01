/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/CatalogModels.kt
 *
 * Cut: everything for documents (TxtLayoutChoice, DocumentTranslationProgress, translateDocumentPath), images and
 * OCR (rgbaBytes, translateImagePlan, detectImageBoxes, ocrImage*, renderTranslatedOverlay, detectDocumentQuad,
 * warpDocumentRgba), the dictionary, text to speech (voices, speech chunks, PCM), HTML fragments (browser), the
 * support files (adblock), upgrades, migrations (ONNX to MNN), cancelOngoingWork (documents) and the language
 * fields writingSystem and dictionaryCode. LangAvailability keeps the two translation directions.
 * Everything else is as is.
 */
package com.yaz.language.translate

import uniffi.bindings.CatalogException
import uniffi.bindings.CatalogHandle

typealias CatalogError = CatalogException

data class LangAvailability(
    val hasFromEnglish: Boolean,
    val hasToEnglish: Boolean
) {
    val translatorFiles: Boolean get() = hasFromEnglish || hasToEnglish
}

data class LanguageAvailabilityEntry(
    val language: Language,
    val availability: LangAvailability
)

// Rows are copied out when the repository publishes; handle queries read the session live. A new
// LanguageCatalog follows every install-state change, so the two only diverge until that publish.
class LanguageCatalog private constructor(
    private val handle: CatalogHandle,
    val baseDir: String,
    val formatVersion: Int,
    val generatedAt: Long,
    val languageRows: List<LanguageAvailabilityEntry>,
    val languageList: List<Language>,
    private val languagesByCode: Map<String, Language>,
    private val availabilityByCode: Map<String, LangAvailability>
) {
    companion object {
        fun of(handle: CatalogHandle): LanguageCatalog {
            val rows = handle.languageRows()
            val languageRows =
                rows.map { row ->
                    LanguageAvailabilityEntry(
                        language =
                        Language(
                            code = row.language.code,
                            displayName = row.language.displayName,
                            shortDisplayName = row.language.shortDisplayName,
                            script = row.language.script
                        ),
                        availability =
                        LangAvailability(
                            hasFromEnglish = row.availability.hasFromEnglish,
                            hasToEnglish = row.availability.hasToEnglish
                        )
                    )
                }
            val languageList = languageRows.map { it.language }
            val languagesByCode = languageList.associateBy { it.code }
            val availabilityByCode =
                languageRows.associate { row -> row.language.code to row.availability }
            return LanguageCatalog(
                handle = handle,
                baseDir = handle.baseDir(),
                formatVersion = handle.formatVersion(),
                generatedAt = handle.generatedAt(),
                languageRows = languageRows,
                languageList = languageList,
                languagesByCode = languagesByCode,
                availabilityByCode = availabilityByCode
            )
        }
    }

    val english: Language by lazy {
        languagesByCode.getValue("en")
    }

    fun languageByCode(code: String): Language? = languagesByCode[code]

    fun availabilityFor(language: Language?): LangAvailability? = language?.let { availabilityByCode[it.code] }

    fun canSwapLanguages(
        from: Language,
        to: Language
    ): Boolean = handle.canSwapLanguages(from.code, to.code)

    fun canTranslate(
        from: Language,
        to: Language
    ): Boolean = handle.canTranslate(from.code, to.code)

    fun warmTranslationModels(
        from: Language,
        to: Language
    ): Boolean = handle.warmTranslationModels(from.code, to.code)

    @Throws(CatalogException::class)
    fun translateText(
        from: Language,
        to: Language,
        text: String
    ): String = handle.translateText(from.code, to.code, text)

    fun translateTextWithAlternatives(
        from: Language,
        to: Language,
        text: String
    ): TranslationWithAlternatives = handle.translateTextWithAlternatives(from.code, to.code, text)

    fun steer(
        from: Language,
        to: Language,
        source: String,
        forcedPrefix: String
    ): TranslationWithAlternatives = handle.steer(from.code, to.code, source, forcedPrefix)

    fun translateMixedTexts(
        inputs: List<String>,
        forcedSourceLanguage: Language?,
        targetLanguage: Language,
        availableLanguages: List<Language>
    ): uniffi.translator_translate.MixedTextTranslationResult =
        handle.translateMixedTexts(
            inputs,
            forcedSourceLanguage?.code,
            targetLanguage.code,
            availableLanguages.map { it.code }
        )

    fun planRepair(): DownloadPlan = handle.planRepair()

    fun planDownload(
        languageCode: String,
        feature: Feature = Feature.CORE
    ): DownloadPlan? = handle.planDownload(languageCode, feature, null)

    fun downloadTaskInstalled(task: DownloadTask): Boolean = handle.downloadTaskInstalled(task)

    fun prepareDelete(
        languageCode: String,
        feature: Feature = Feature.CORE
    ): DeletePlan = handle.prepareDelete(languageCode, feature)

    fun sizeBytesForFeature(
        languageCode: String,
        feature: Feature = Feature.CORE
    ): Long = handle.sizeBytes(languageCode, feature).toLong()
}
