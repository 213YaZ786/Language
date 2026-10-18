package com.yaz.voice.ime

import android.text.InputType

/**
 * How the dictated words are fitted to the text around them, a pure function of what is before the cursor and what is
 * selected. Ported from Transcribro's VoiceInput.onPartialResults
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/voiceinput/VoiceInput.kt
 * (no leading space at the start of the field, after a space or a newline; a first letter lowered in the middle of a
 * sentence; a selection replaced keeping its own capital, its end sign and its spaces; words all in capitals stay in
 * capitals) with the space before the words and the signs that join to the text before them from our Keyboard's
 * dictation (InputLogic.dictationSpaceBefore and DictationController.startsWithSign, a fork of
 * https://github.com/HeliBorg/HeliBoard, app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java).
 * Two changes to Transcribro's rules: its test of "in the middle of a sentence" reads a second character that is not
 * there at the start of a field (an error), and takes a newline for a space (a new line then began in lower case);
 * here one character is enough and a newline starts a sentence.
 */
object Insertion {

    /** Signs that join to the word before: no space is put in front of them. */
    private const val JOINING = ",.;:!?)]}…%"

    /**
     * The words to put in the field.
     * [transcription]: what the engine heard. [before]: up to two characters before the cursor (or before the
     * selection), empty at the start of the field. [selected]: the selected text, null or empty when there is none.
     * [spaces]: whether the language puts spaces between words (not Thai, Khmer, Lao, Japanese, Chinese).
     */
    fun words(transcription: String, before: String, selected: String?, spaces: Boolean = true): String {
        val said = transcription.trim()
        if (said.isEmpty()) return ""
        var textToCommit = if (spaceBefore(before, said, spaces)) " $said" else said

        // Words all in capitals (an acronym, a shout) stay in capitals whatever is done to them below.
        val readdUppercase = textToCommit.filter { it.isLetter() }.firstOrNull { !it.isUpperCase() } == null

        if (!selected.isNullOrEmpty()) {
            val selectedTrimmed = selected.trim()
            val removeSuffixPoint = textToCommit.trim().toList().withIndex().reversed().firstOrNull { it.value.isLetterOrDigit() }
            if (removeSuffixPoint != null) textToCommit = textToCommit.trim().substring(0..removeSuffixPoint.index)

            val removePrefixPoint = textToCommit.trim().toList().withIndex().firstOrNull { it.value.isLetterOrDigit() }
            if (removePrefixPoint != null) {
                textToCommit = textToCommit.trim().substring(removePrefixPoint.index..textToCommit.trim().lastIndex)
            }

            val selectedEndOfWordPunctuation = selectedTrimmed.toList().withIndex().reversed().firstOrNull { it.value.isLetterOrDigit() }
            val firstSelectedNonWhitespaceCharacter = selectedTrimmed.firstOrNull()

            if (firstSelectedNonWhitespaceCharacter != null && textToCommit.isNotEmpty()) {
                textToCommit = when {
                    textToCommit.firstOrNull { !it.isUpperCase() } == null -> textToCommit
                    firstSelectedNonWhitespaceCharacter.isUpperCase() -> textToCommit[0].uppercaseChar() + textToCommit.substring(1)
                    firstSelectedNonWhitespaceCharacter.isLowerCase() -> textToCommit[0].lowercaseChar() + textToCommit.substring(1)
                    selected.firstOrNull { it.isLetterOrDigit() || it.isWhitespace() } == null ->
                        textToCommit[0].lowercaseChar() + textToCommit.substring(1)
                    else -> textToCommit
                }
            }

            if (selectedEndOfWordPunctuation != null) {
                textToCommit += selectedTrimmed.substring(selectedEndOfWordPunctuation.index + 1)
            }

            if (selected.firstOrNull { it.isLetterOrDigit() } == null) {
                textToCommit = " $textToCommit"
                if (textToCommit.last().isLetterOrDigit()) textToCommit += selected
            }
        } else if (midSentence(before)) {
            val firstLetterOrDigit = textToCommit.withIndex().firstOrNull { it.value.isLetterOrDigit() }
            // a first word with a capital inside (NASA, iPhone) keeps its first letter: an addition to Transcribro's rule
            val firstWord = firstLetterOrDigit?.let { textToCommit.substring(it.index).takeWhile { c -> c.isLetterOrDigit() } }
            if (firstLetterOrDigit != null && firstWord != null && firstWord.drop(1).none { it.isUpperCase() }) {
                val chars = textToCommit.toCharArray()
                chars[firstLetterOrDigit.index] = firstLetterOrDigit.value.lowercaseChar()
                textToCommit = chars.concatToString()
            }
        }

        if (readdUppercase) textToCommit = textToCommit.uppercase()
        return textToCommit
    }

    /**
     * The words come right after a word (or a word and its space), not after a sign or a newline: their first letter
     * is lowered. Transcribro's test on the two characters before the cursor, read with one character when there is only one.
     */
    private fun midSentence(before: String): Boolean {
        val last = before.lastOrNull() ?: return false
        if (last == '\n') return false
        if (last.isLetterOrDigit()) return true
        return last.isWhitespace() && before.getOrNull(before.length - 2)?.isLetterOrDigit() == true
    }

    /**
     * Whether a space goes before the words: the text before ends in something that is not a space nor an opening sign,
     * and the words do not start with a sign that joins. Keyboard's InputLogic.dictationSpaceBefore and startsWithSign.
     */
    private fun spaceBefore(before: String, said: String, spaces: Boolean): Boolean {
        if (!spaces) return false
        val last = before.lastOrNull() ?: return false
        if (last.isWhitespace()) return false
        val type = Character.getType(last)
        if (type == Character.START_PUNCTUATION.toInt() || type == Character.INITIAL_QUOTE_PUNCTUATION.toInt()) return false
        if (last == '"' || last == '\'') return false
        return JOINING.indexOf(said[0]) < 0
    }

    /**
     * Whether the language puts spaces between words. Our Keyboard's resources (values-th, -km, -lo of
     * current_language_has_spaces, a fork of https://github.com/HeliBorg/HeliBoard) with Japanese and Chinese added.
     */
    fun languageHasSpaces(language: String): Boolean = language !in setOf("th", "km", "lo", "ja", "zh")

    /**
     * A field that hides what is typed: text, web or number password. The tests of Android's TextView
     * isPasswordInputType and isVisiblePasswordInputType
     * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java
     */
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
