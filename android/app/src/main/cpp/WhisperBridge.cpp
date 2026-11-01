#include "WhisperBridge.h"
#include "whisper.h"
#include <memory>
#include <sstream>
#include <stdexcept>
#include <algorithm>
#include <thread>

static std::string escaped(const char* text) {
    std::string out;
    for (const unsigned char c : std::string(text)) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else if (c >= 32) out += c;
    }
    return out;
}
std::string transcribe_clip(const std::string& model, const std::vector<float>& samples, const std::string& language) {
    if (samples.empty() || samples.size() > 16000 * 60 * 10) throw std::invalid_argument("Expected a nonempty clip of at most ten minutes");
    if (language != "auto" && whisper_lang_id(language.c_str()) < 0) throw std::invalid_argument("Unknown language");
    auto options = whisper_context_default_params(); options.use_gpu = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(model.c_str(), options), whisper_free);
    if (!ctx) throw std::runtime_error("Model could not be loaded");
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
    params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    params.translate = false; params.language = language.c_str(); params.no_context = true;
    if (whisper_full(ctx.get(), params, samples.data(), static_cast<int>(samples.size())) != 0) throw std::runtime_error("Transcription failed");
    std::ostringstream output; output << "[";
    for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i) {
        if (i) output << ",";
        output << "{\"startMs\":" << whisper_full_get_segment_t0(ctx.get(), i) * 10
               << ",\"endMs\":" << whisper_full_get_segment_t1(ctx.get(), i) * 10
               << ",\"text\":\"" << escaped(whisper_full_get_segment_text(ctx.get(), i)) << "\"}";
    }
    output << "]"; return output.str();
}
