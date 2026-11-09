#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaContextState {
    std::string model_path;
    uint32_t gguf_version = 0;
    uint64_t tensor_count = 0;
    uint64_t kv_count = 0;
    size_t file_size_bytes = 0;
    int n_ctx = 4096;
    int n_threads = 4;
    std::atomic<bool> cancel_requested{false};
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelPath,
    jint nCtx,
    jint nThreads,
    jboolean /* useGpu */
) {
    if (!modelPath) return 0L;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Failed to open GGUF file: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0L;
    }

    char magic[4] = {0};
    file.read(magic, 4);
    if (std::strncmp(magic, "GGUF", 4) != 0) {
        LOGE("Invalid GGUF magic in file: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0L;
    }

    uint32_t version = 0;
    uint64_t tensor_count = 0;
    uint64_t kv_count = 0;
    file.read(reinterpret_cast<char*>(&version), sizeof(uint32_t));
    file.read(reinterpret_cast<char*>(&tensor_count), sizeof(uint64_t));
    file.read(reinterpret_cast<char*>(&kv_count), sizeof(uint64_t));

    file.seekg(0, std::ios::end);
    size_t total_bytes = static_cast<size_t>(file.tellg());

    auto* state = new LlamaContextState();
    state->model_path = path;
    state->gguf_version = version;
    state->tensor_count = tensor_count;
    state->kv_count = kv_count;
    state->file_size_bytes = total_bytes;
    state->n_ctx = nCtx > 0 ? nCtx : 4096;
    state->n_threads = nThreads > 0 ? nThreads : 4;

    LOGI("Loaded GGUF v%u (%zu bytes, %llu tensors, %llu kv pairs) from %s",
         version, total_bytes,
         static_cast<unsigned long long>(tensor_count),
         static_cast<unsigned long long>(kv_count),
         path);

    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeCancelCompletion(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0L) return;
    auto* state = reinterpret_cast<LlamaContextState*>(handle);
    state->cancel_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeFreeModel(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0L) return;
    auto* state = reinterpret_cast<LlamaContextState*>(handle);
    delete state;
}
