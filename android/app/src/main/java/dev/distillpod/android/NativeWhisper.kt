package dev.distillpod.android
class NativeWhisper {
    companion object { init { System.loadLibrary("distillpod_whisper") } }
    external fun transcribe(modelPath: String, samples: FloatArray, language: String): String
}
