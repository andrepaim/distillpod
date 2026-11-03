#pragma once
#include <string>
#include <vector>
#include <atomic>
#include <stdexcept>
#include <functional>
class TranscriptionCancelled : public std::runtime_error {
public:
    TranscriptionCancelled() : std::runtime_error("Transcription cancelled") {}
};
std::string transcribe_clip(const std::string& model, const std::vector<float>& samples, const std::string& language,
                            const std::atomic<bool>* cancelled = nullptr,
                            const std::function<void(int)>& progress = {});
