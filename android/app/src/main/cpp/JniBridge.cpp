#include <jni.h>
#include "WhisperBridge.h"
extern "C" JNIEXPORT jstring JNICALL Java_dev_distillpod_android_NativeWhisper_transcribe(JNIEnv* env, jobject, jstring model, jfloatArray audio, jstring language) {
    const char* model_chars = env->GetStringUTFChars(model, nullptr);
    const std::string path(model_chars); env->ReleaseStringUTFChars(model, model_chars);
    const char* lang_chars = env->GetStringUTFChars(language, nullptr);
    const std::string lang(lang_chars); env->ReleaseStringUTFChars(language, lang_chars);
    std::vector<float> samples(env->GetArrayLength(audio));
    env->GetFloatArrayRegion(audio, 0, samples.size(), samples.data());
    try { return env->NewStringUTF(transcribe_clip(path, samples, lang).c_str()); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); return nullptr; }
}
