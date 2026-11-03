// The bridge between the app and whisper.cpp (MIT): open a model, write out sound (16 kHz mono floats), and Silero's
// voice detector that ships with it. Nothing is logged: the engines' own logs are turned off.
//
// Sources:
// - the bridge (open, transcribe, close) is SMS's own, built on whisper.cpp's include/whisper.h
//   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/include/whisper.h
// - the voice detector calls are whisper.cpp's: whisper_vad_default_context_params, whisper_vad_init_from_file_with_params,
//   whisper_vad_detect_speech_no_reset, whisper_vad_n_probs, whisper_vad_probs, whisper_vad_reset_state, whisper_vad_free
//   (same header, "Voice Activity Detection"); no_reset is the call made for streaming, as its comment says
// - the live settings (suppress_nst, no timestamps) follow examples/stream/stream.cpp in its VAD mode
//   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/stream/stream.cpp
// - the short audio context (whisper-acft models) is Joplin's WhisperSession::transcribe_ (AGPL-3.0-or-later), and the
//   no temperature fallback for a real-time source is stream.cpp's no_fallback (see run_whisper)
// - the abort callback is whisper_full_params.abort_callback of whisper.h
// - the methods are registered by name in JNI_OnLoad, so this file belongs to no package
//   (https://developer.android.com/training/articles/perf-jni#native-libraries, RegisterNatives)
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"

namespace {

std::atomic<bool> g_abort{false};

bool abort_requested(void *) { return g_abort.load(); }

void silent(enum ggml_log_level, const char *, void *) {}

// The CPU variants live with the app's own libraries; the best one for this phone is taken, once.
void ensure_backends(JNIEnv *env, jstring lib_dir) {
    static bool loaded = false;
    if (loaded) return;
    whisper_log_set(silent, nullptr);
    ggml_log_set(silent, nullptr);
    const char *dir = env->GetStringUTFChars(lib_dir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(lib_dir, dir);
    loaded = true;
}

jlong open_model(JNIEnv *env, jclass, jstring lib_dir, jstring model) {
    ensure_backends(env, lib_dir);
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

// [prompt]: words the speech is likely to hold (the text before the cursor, names an app expects), given to whisper as
// its initial prompt, as whisper.cpp's command line does with --prompt (examples/cli/cli.cpp, wparams.initial_prompt)
// https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
// [words]: one segment per word with its times, as cli.cpp's word-level timestamps (-ml 1): token_timestamps, max_len 1,
// split_on_word, timestamps on (README "Word-level timestamp (experimental)", cli.cpp wparams.token_timestamps /
// max_len / split_on_word) https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/README.md
// The answer is then UTF-8 lines "t0<TAB>t1<TAB>text<LF>": t0 and t1 in milliseconds from the start of the sound
// (whisper_full_get_segment_t0/t1 count 10 ms, whisper.h), the text as whisper wrote it (a leading space marks a new
// word). Without [words] it is the text alone.
// [short_context]: the model is a whisper-acft one (FUTO), tuned to read only as much audio as there is, so whisper is
// given an audio context sized to the sound: Joplin's WhisperSession::transcribe_ (see the block below). Word times
// keep the full context.
//
// The shortAudioContext part follows WhisperSession::transcribe_ of Joplin (AGPL-3.0-or-later,
// Copyright the Joplin contributors; this file's app is GPL-3.0 and credits Joplin in its README)
// https://github.com/laurent22/joplin/blob/dev/packages/whisper-voice-typing/cpp/utils/WhisperSession.cpp
// Changed from the original: the sound comes as a Java array, copied into a std::vector (n is its length); no exception
// crosses the JNI boundary (a failure answers null); under one second the sound is padded with silence up to one
// second instead of answering "" (whisper.cpp's "consider padding the input audio with silence"); the warning
// goes to Android's log directly (LOGW is Joplin's macro); whisper_print_timings is left out (the engine's logs are
// off here); the audio_ctx is kept for [short_context] without [words].
jbyteArray run_whisper(JNIEnv *env, jlong handle, jfloatArray pcm, jint threads, jstring language, jboolean live,
                       jstring prompt, bool words, bool short_context, jboolean noFallback) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    // The sound is copied (the Java array is only read), padded below when it is short.
    std::vector<float> audio(n);
    env->GetFloatArrayRegion(pcm, 0, n, audio.data());
    // Whisper won't transcribe anything shorter than 1s.
    int minTranscribeLength = WHISPER_SAMPLE_RATE; // 1s
    if (audio.size() < minTranscribeLength) {
        // Padded with silence, not dropped: whisper.cpp's own advice for a short input (whisper_full_with_state:
        // "input is too short - %d ms < 100 ms. consider padding the input audio with silence"), so a short word
        // said just before the end is still written out.
        audio.resize(minTranscribeLength, 0.0f);
    }
    n = static_cast<jsize>(audio.size());

    float seconds = static_cast<float>(n) / WHISPER_SAMPLE_RATE;
    if (seconds > 30.0f) {
        __android_log_print(ANDROID_LOG_WARN, "whisper", "Warning: Audio is longer than 30 seconds. Not all audio will be transcribed");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // The language asked for when whisper knows it, else found from the sound itself; never translated.
    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string code = lang;
    env->ReleaseStringUTFChars(language, lang);
    if (code != "auto" && whisper_lang_id(code.c_str()) < 0) code = "auto";
    params.language = code.c_str();
    std::string hint;
    if (prompt != nullptr) {
        const char *p = env->GetStringUTFChars(prompt, nullptr);
        hint = p;
        env->ReleaseStringUTFChars(prompt, p);
    }
    if (!hint.empty()) params.initial_prompt = hint.c_str();
    params.translate = false;
    params.no_timestamps = !words;
    if (words) {
        params.token_timestamps = true;
        params.max_len = 1;
        params.split_on_word = true;
    }
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    // Dictation: no sound effects written as words ([MUSIC]), and the work stops when asked.
    params.suppress_nst = live == JNI_TRUE;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = nullptr;
    params.n_threads = threads;
    // A real-time source (the microphone, a live stream): no temperature fallback (stream.cpp: -nf, --no-fallback),
    // a retry at a higher temperature costs a second pass over the phrase. A file keeps it. (The original reads the
    // flag from its command line; here the caller says it, apart from [live].)
    // https://github.com/ggml-org/whisper.cpp/blob/master/examples/stream/stream.cpp
    bool no_fallback = noFallback == JNI_TRUE;
    params.temperature_inc = no_fallback ? 0.0f : params.temperature_inc;

    // If supported by the model, allow shortening the transcription. This can significantly
    // improve performance, but requires a fine-tuned model.
    // See https://github.com/futo-org/whisper-acft
    if (short_context && !words) {
        // audio_ctx: 1500 every 30 seconds (50 units in one second).
        // See https://github.com/futo-org/whisper-acft/issues/6
        float padding = 64.0f;
        params.audio_ctx = static_cast<int>(seconds * (1500.0f / 30.0f) + padding);

        if (params.audio_ctx > 1500) {
            params.audio_ctx = 1500;
        }
    }
    whisper_reset_timings(ctx);

    int failed = whisper_full(ctx, params, audio.data(), n);
    if (failed != 0) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        std::string piece = whisper_full_get_segment_text(ctx, i);
        if (!words) {
            text += piece;
            continue;
        }
        // The line format holds no tab or line break inside the text
        for (char &c : piece) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
        text += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + '\t' +
                std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + '\t' + piece + '\n';
    }
    // Plain UTF-8 bytes: Java's own string maker refuses some of it (emoji).
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

// The text alone: examples/cli/cli.cpp without -ml, https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
jbyteArray transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads, jstring language, jboolean live,
                      jstring prompt, jboolean short_context, jboolean no_fallback) {
    return run_whisper(env, handle, pcm, threads, language, live, prompt, false, short_context == JNI_TRUE, no_fallback);
}

// Word by word with times: examples/cli/cli.cpp with -ml 1 (README "Word-level timestamp (experimental)"),
// https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
jbyteArray transcribe_words(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads, jstring language,
                            jboolean live, jstring prompt, jboolean short_context, jboolean no_fallback) {
    return run_whisper(env, handle, pcm, threads, language, live, prompt, true, short_context == JNI_TRUE, no_fallback);
}

// The language whisper heard in the last sound it wrote out (its code, "fr"), as cli.cpp reports it:
// whisper_lang_str(whisper_full_lang_id(ctx)) https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
jstring heard_language(JNIEnv *env, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    const int id = whisper_full_lang_id(ctx);
    if (id < 0) return nullptr;
    const char *code = whisper_lang_str(id);
    return code == nullptr ? nullptr : env->NewStringUTF(code);
}

void close_model(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

void abort_work(JNIEnv *, jclass, jboolean on) { g_abort.store(on == JNI_TRUE); }

jboolean knows_language(JNIEnv *env, jclass, jstring language) {
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jboolean known = whisper_lang_id(lang) >= 0 ? JNI_TRUE : JNI_FALSE;
    env->ReleaseStringUTFChars(language, lang);
    return known;
}

jlong vad_open(JNIEnv *env, jclass, jstring lib_dir, jstring model) {
    ensure_backends(env, lib_dir);
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.use_gpu = false;
    params.n_threads = 1; // 32 ms windows of a small network: one thread, the least power
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    // whisper_vad_detect_speech clears the network's memory before each sound, but the streaming call
    // (no_reset) does not: a new context starts from zero as whisper.h says ("Reset LSTM hidden/cell states to zero")
    if (vctx != nullptr) whisper_vad_reset_state(vctx);
    return reinterpret_cast<jlong>(vctx);
}

// The probability of speech in each window of the sound, the network keeping its memory from one call to the next.
jfloatArray vad_probs(JNIEnv *env, jclass, jlong handle, jfloatArray pcm) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx == nullptr) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    bool ok = whisper_vad_detect_speech_no_reset(vctx, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (!ok) return nullptr;
    const int count = whisper_vad_n_probs(vctx);
    jfloatArray out = env->NewFloatArray(count);
    env->SetFloatArrayRegion(out, 0, count, whisper_vad_probs(vctx));
    return out;
}

void vad_reset(JNIEnv *, jclass, jlong handle) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx != nullptr) whisper_vad_reset_state(vctx);
}

void vad_close(JNIEnv *, jclass, jlong handle) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx != nullptr) whisper_vad_free(vctx);
}

// The Kotlin object these methods belong to: the app's package, then core.voice.Whisper.
const char kClass[] = "com.yaz.language.core.voice.Whisper";

const JNINativeMethod kMethods[] = {
    {"open", "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(open_model)},
    {"transcribe", "(J[FILjava/lang/String;ZLjava/lang/String;ZZ)[B", reinterpret_cast<void *>(transcribe)},
    {"transcribeWords", "(J[FILjava/lang/String;ZLjava/lang/String;ZZ)[B", reinterpret_cast<void *>(transcribe_words)},
    {"heardLanguage", "(J)Ljava/lang/String;", reinterpret_cast<void *>(heard_language)},
    {"close", "(J)V", reinterpret_cast<void *>(close_model)},
    {"abort", "(Z)V", reinterpret_cast<void *>(abort_work)},
    {"knows", "(Ljava/lang/String;)Z", reinterpret_cast<void *>(knows_language)},
    {"vadOpen", "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(vad_open)},
    {"vadProbs", "(J[F)[F", reinterpret_cast<void *>(vad_probs)},
    {"vadReset", "(J)V", reinterpret_cast<void *>(vad_reset)},
    {"vadClose", "(J)V", reinterpret_cast<void *>(vad_close)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    std::string name = kClass;
    for (char &c : name) if (c == '.') c = '/';
    jclass cls = env->FindClass(name.c_str());
    if (cls == nullptr) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
