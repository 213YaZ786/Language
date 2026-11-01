package com.yaz.language.recognition

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only. An app calling Language's recognition service the way any app does (SpeechRecognizer with
 * Language's service named), every callback written to files/recognize-test.txt, as Keyboard's DictationTestReceiver
 * tries its engine on whisper.cpp's samples/jfk.wav (tests/test-vad.cpp)
 * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/tests/test-vad.cpp
 * and as AOSP's CTS drives a recognition service through SpeechRecognizer (AbstractRecognitionServiceTest)
 * https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/voiceRecognition/src/android/voicerecognition/cts/AbstractRecognitionServiceTest.java
 *   adb shell am start -n <package>/com.yaz.language.recognition.RecognizerTestActivity --es wav jfk.wav --es lang en
 * With "wav" (16-bit PCM WAV in files/) the sound goes in through EXTRA_AUDIO_SOURCE, else the microphone is used.
 * Extras: --ez partial, --ez segmented, --ez detect, --esa bias a,b, --ei silence ms, --ez cancel (cancel after 1 s).
 */
class RecognizerTestActivity : Activity() {

    private val log = StringBuilder()
    private lateinit var out: File
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = File(filesDir, "recognize-test.txt")
        note("start")
        // --ez field true: a plain text field (multi-line with --ez multi true) for the voice keyboard, its text
        // written to the log on every change, as an EditText with a TextWatcher
        // https://developer.android.com/reference/android/widget/EditText
        if (intent.getBooleanExtra("field", false)) {
            val field = android.widget.EditText(this).apply {
                hint = "Type here"
                inputType = if (intent.getBooleanExtra("multi", false)) {
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT
                }
                if (intent.getBooleanExtra("password", false)) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                intent.getStringExtra("text")?.let { setText(it); setSelection(it.length) }
                setBackgroundColor(0xFFFFFFFF.toInt())
                setTextColor(0xFF000000.toInt())
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) = note("field [$s]")
                })
                setOnEditorActionListener { _, action, _ -> note("action $action"); true }
            }
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            setContentView(field)
            field.requestFocus()
            return
        }
        // --ez panel true: the RECOGNIZE_SPEECH panel opened for a result, as an app's mic button does
        // https://developer.android.com/reference/android/speech/RecognizerIntent#ACTION_RECOGNIZE_SPEECH
        if (intent.getBooleanExtra("panel", false)) {
            val open = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setPackage(packageName)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something")
            intent.getStringExtra("wav")?.let { open.putExtra("test_wav", it) }
            intent.getStringExtra("lang")?.let { open.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            @Suppress("DEPRECATION")
            startActivityForResult(open, 1)
            return
        }
        val made = SpeechRecognizer.createSpeechRecognizer(this, ComponentName(this, VoiceRecognitionService::class.java))
        recognizer = made
        made.setRecognitionListener(listener)
        val asked = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.getStringExtra("lang")?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, intent.getBooleanExtra("partial", true))
            if (intent.getBooleanExtra("segmented", false)) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            }
            // A session that lasts as long as the sound handed in, as Recorder asks it
            // https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION
            if (intent.getBooleanExtra("whole", false)) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            // Each word's time https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_REQUEST_WORD_TIMING
            if (intent.getBooleanExtra("timing", false)) putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            if (intent.getBooleanExtra("detect", false)) putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            intent.getStringArrayExtra("bias")?.let { putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(it.toList())) }
            if (intent.hasExtra("silence")) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, intent.getIntExtra("silence", 3000))
            }
            intent.getStringExtra("wav")?.let { name -> putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe(File(filesDir, name))) }
            if (intent.hasExtra("encoding")) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, intent.getIntExtra("encoding", 2))
            }
        }
        made.startListening(asked)
        if (intent.getBooleanExtra("cancel", false)) {
            window.decorView.postDelayed({ note("cancel"); made.cancel() }, 1_000)
        }
    }

    /** The WAV's samples after its 44-byte header, written into a pipe whose read end goes to the service. */
    private fun pipe(wav: File): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { sink ->
                runCatching { wav.inputStream().use { it.skip(44); it.copyTo(sink) } }
            }
        }
        return read
    }

    private fun note(line: String) {
        log.append(System.currentTimeMillis() % 100_000).append(' ').append(line).append('\n')
        out.writeText(log.toString())
    }

    // The results, then each RecognitionPart as text@ms when the words' times were asked (SpeechRecognizer.RECOGNITION_PARTS
    // https://developer.android.com/reference/android/speech/SpeechRecognizer#RECOGNITION_PARTS)
    private fun words(bundle: Bundle?): String? {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" | ") ?: return null
        val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, android.speech.RecognitionPart::class.java)
            ?: return text
        return text + " parts " + parts.joinToString(" ") { "${it.rawText}@${it.timestampMillis}" }
    }

    private val listener = object : RecognitionListener {
        private var levels = 0
        override fun onReadyForSpeech(params: Bundle?) = note("ready")
        override fun onBeginningOfSpeech() = note("begin")
        override fun onRmsChanged(rmsdB: Float) {
            if (levels++ % 20 == 0) note("rms $rmsdB")
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = note("end")
        override fun onError(error: Int) = note("error $error")
        override fun onResults(results: Bundle?) = note("results ${words(results)}")
        override fun onPartialResults(partialResults: Bundle?) = note("partial ${words(partialResults)}")
        override fun onSegmentResults(segmentResults: Bundle) = note("segment ${words(segmentResults)}")
        override fun onEndOfSegmentedSession() = note("end of segmented session")
        override fun onLanguageDetection(results: Bundle) =
            note("language ${results.getString(SpeechRecognizer.DETECTED_LANGUAGE)}")
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // The panel's answer: RecognizerIntent.EXTRA_RESULTS and EXTRA_CONFIDENCE_SCORES
    // https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_RESULTS
    @Deprecated("startActivityForResult is what apps calling RECOGNIZE_SPEECH use")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        note("panel result=$resultCode ${data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)} " +
            "${data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)?.toList()}")
        finish()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
