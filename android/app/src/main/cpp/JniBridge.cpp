#include <jni.h>
#include "WhisperBridge.h"
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
// Shared ownership protects the flag during inference, including concurrent cancellation.
// Opaque IDs avoid exposing native pointers to callers.
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic<bool>>> cancellations;
jlong next_id = 1;
std::shared_ptr<std::atomic<bool>> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = cancellations.find(id);
    if (found == cancellations.end()) throw std::invalid_argument("Invalid transcription operation");
    return found->second;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_distillpod_android_NativeWhisper_createOperation(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto id = next_id++;
    cancellations.emplace(id, std::make_shared<std::atomic<bool>>(false));
    return id;
}
extern "C" JNIEXPORT void JNICALL Java_dev_distillpod_android_NativeWhisper_cancelOperation(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = cancellations.find(id);
    if (found != cancellations.end()) found->second->store(true);
}
extern "C" JNIEXPORT void JNICALL Java_dev_distillpod_android_NativeWhisper_releaseOperation(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    cancellations.erase(id);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_distillpod_android_NativeWhisper_transcribeOperation(JNIEnv* env, jobject instance, jlong id, jstring model, jfloatArray audio, jstring language) {
    try {
        auto cancelled = lookup(id);
        if (cancelled->load()) throw TranscriptionCancelled();
        const auto size = env->GetArrayLength(audio);
        if (size == 0 || size > 16000 * 60 * 10) throw std::invalid_argument("Expected a nonempty clip of at most ten minutes");
        const char* model_chars = env->GetStringUTFChars(model, nullptr);
        if (!model_chars) return nullptr;
        const std::string path(model_chars); env->ReleaseStringUTFChars(model, model_chars);
        const char* lang_chars = env->GetStringUTFChars(language, nullptr);
        if (!lang_chars) return nullptr;
        const std::string lang(lang_chars); env->ReleaseStringUTFChars(language, lang_chars);
        std::vector<float> samples(size);
        env->GetFloatArrayRegion(audio, 0, size, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto cls = env->GetObjectClass(instance);
        const auto method = env->GetMethodID(cls, "reportProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (!method) return nullptr;
        const auto result = transcribe_clip(path, samples, lang, cancelled.get(), [&](int value) {
            if (!env->ExceptionCheck()) env->CallVoidMethod(instance, method, value);
            if (env->ExceptionCheck()) cancelled->store(true);
        });
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const TranscriptionCancelled& e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), e.what());
    } catch (const std::exception& e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    }
    return nullptr;
}
