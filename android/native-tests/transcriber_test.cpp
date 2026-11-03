#include "WhisperBridge.h"
#include <stdexcept>
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error("Check failed: " #condition); } while (false)
#include <iostream>
#include <fstream>
#include <iterator>
int main(int argc, char** argv) {
    std::atomic<bool> cancelled(true);
    bool cancellation_reported = false;
    try { transcribe_clip("unused", std::vector<float>(160), "en", &cancelled); }
    catch (const TranscriptionCancelled&) { cancellation_reported = true; }
    CHECK(cancellation_reported);
    bool rejected = false;
    try { transcribe_clip("/missing/model.bin", std::vector<float>(160), "en"); } catch (const std::exception&) { rejected = true; }
    CHECK(rejected);
    rejected = false;
    try { transcribe_clip("unused", {}, "en"); } catch (const std::exception&) { rejected = true; }
    CHECK(rejected);
    if (argc == 3) {
        std::ifstream f(argv[2], std::ios::binary);
        std::vector<char> wav((std::istreambuf_iterator<char>(f)), {});
        CHECK(wav.size() > 44);
        std::vector<float> pcm;
        for (size_t i = 44; i + 1 < wav.size(); i += 2) {
            auto sample = static_cast<int16_t>(static_cast<unsigned char>(wav[i]) | (static_cast<unsigned char>(wav[i+1]) << 8));
            pcm.push_back(sample / 32768.0f);
        }
        std::vector<int> progress;
        auto result = transcribe_clip(argv[1], pcm, "en", nullptr, [&](int value) {
            CHECK(value >= 0 && value <= 100);
            CHECK(progress.empty() || value > progress.back());
            progress.push_back(value);
        });
        CHECK(result.find("country") != std::string::npos);
        CHECK(result.find("startMs") != std::string::npos);
        CHECK(progress.size() >= 2 && progress.back() == 100);
        std::cout << "Real Whisper inference passed\n";
    }
    std::cout << "Native input validation passed\n";
}
