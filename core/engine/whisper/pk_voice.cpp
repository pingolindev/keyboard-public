// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

// A narrow C interface to whisper.cpp for the engine's voice module: open a
// model file, turn 16 kHz mono samples into text, close. The parameters are
// set here, where whisper.h defines their struct, so Rust needs no copy of it.
// Nothing is logged: whisper's log goes to a function that drops it.

#include <cstdlib>
#include <cstring>
#include <string>

#include "whisper.h"

namespace {
void drop_log(enum ggml_log_level, const char *, void *) {}

constexpr float NO_SPEECH = 0.6f;
}

extern "C" {

struct pk_voice {
    whisper_context *ctx;
};

pk_voice *pk_voice_open(const char *path) {
    whisper_log_set(drop_log, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    if (ctx == nullptr) return nullptr;
    auto *voice = static_cast<pk_voice *>(std::malloc(sizeof(pk_voice)));
    if (voice == nullptr) {
        whisper_free(ctx);
        return nullptr;
    }
    voice->ctx = ctx;
    return voice;
}

int pk_voice_is_multilingual(const pk_voice *voice) {
    return voice != nullptr && whisper_is_multilingual(voice->ctx) ? 1 : 0;
}

// The text for `n` samples, or null on failure. `language` is a whisper
// language code ("en", "de"), or null to detect it. Free with pk_voice_free_text.
char *pk_voice_transcribe(pk_voice *voice, const float *samples, int n, const char *language, int threads) {
    if (voice == nullptr || samples == nullptr || n <= 0) return nullptr;
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = language;
    params.detect_language = false;
    if (language == nullptr) params.language = "auto";
    if (whisper_full(voice->ctx, params, samples, n) != 0) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(voice->ctx);
    for (int i = 0; i < segments; i++) {
        // Whisper invents words for silence and noise ("you", "Thank you."):
        // a segment it rates as probably not speech is left out.
        if (whisper_full_get_segment_no_speech_prob(voice->ctx, i) > NO_SPEECH) continue;
        const char *segment = whisper_full_get_segment_text(voice->ctx, i);
        if (segment != nullptr) text += segment;
    }
    char *out = static_cast<char *>(std::malloc(text.size() + 1));
    if (out == nullptr) return nullptr;
    std::memcpy(out, text.c_str(), text.size() + 1);
    return out;
}

void pk_voice_free_text(char *text) { std::free(text); }

void pk_voice_close(pk_voice *voice) {
    if (voice == nullptr) return;
    whisper_free(voice->ctx);
    std::free(voice);
}

}  // extern "C"
