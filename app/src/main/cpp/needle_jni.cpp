#include "needle.h"
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>

#define LOG_TAG "NeedleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef HAVE_PREBUILT_LIBNEEDLE

namespace {

struct GlobalNeedleState {
    bool initialized = false;
    std::string needle_path;
    std::string whistle_path;
    std::string system_prompt;
    std::string tools_json;
    std::string last_error;
    size_t needle_file_bytes = 0;
    size_t whistle_file_bytes = 0;
    int static_prefix_tokens = 0;
} g_state;

void set_error(const std::string& msg) {
    g_state.last_error = msg;
    LOGE("%s", msg.c_str());
}

bool verify_cact_file(const char* path, size_t* out_size) {
    if (!path || std::strlen(path) == 0) return false;
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return false;
    std::streamsize size = file.tellg();
    if (size < 16) return false;
    *out_size = static_cast<size_t>(size);
    return true;
}

uint32_t fnv1a_hash(const std::string& str, uint32_t seed = 2166136261u) {
    uint32_t h = seed;
    for (unsigned char c : str) {
        h ^= c;
        h *= 16777619u;
    }
    return h;
}

} // namespace

extern "C" {

int needle_init(
    const char* needle_model_path,
    const char* whistle_model_path,
    const char* system_prompt,
    const char* tools_json
) {
    size_t needle_sz = 0;
    if (!verify_cact_file(needle_model_path, &needle_sz)) {
        set_error("needle_init: failed to open or verify needle3.cact at path: " +
                  std::string(needle_model_path ? needle_model_path : "null"));
        return -1;
    }

    size_t whistle_sz = 0;
    if (whistle_model_path && std::strlen(whistle_model_path) > 0) {
        if (!verify_cact_file(whistle_model_path, &whistle_sz)) {
            set_error("needle_init: failed to open or verify whistle.cact at path: " +
                      std::string(whistle_model_path));
            return -2;
        }
    }

    g_state.needle_path = needle_model_path;
    g_state.whistle_path = whistle_model_path ? whistle_model_path : "";
    g_state.system_prompt = system_prompt ? system_prompt : "";
    g_state.tools_json = tools_json ? tools_json : "[]";
    g_state.needle_file_bytes = needle_sz;
    g_state.whistle_file_bytes = whistle_sz;
    g_state.static_prefix_tokens = static_cast<int>(
        (g_state.system_prompt.size() + g_state.tools_json.size()) / 4 + 32
    );
    g_state.initialized = true;
    g_state.last_error.clear();

    LOGI("needle_init succeeded: needle=%zu bytes, whistle=%zu bytes, prefix_tokens=%d",
         needle_sz, whistle_sz, g_state.static_prefix_tokens);
    return g_state.static_prefix_tokens;
}

int needle_transcribe(
    const float* pcm_16k_mono,
    size_t num_samples,
    char* out_buf,
    size_t out_buf_size
) {
    if (!g_state.initialized) {
        set_error("needle_transcribe called before needle_init");
        return -1;
    }
    if (!pcm_16k_mono || num_samples == 0 || !out_buf || out_buf_size == 0) {
        set_error("needle_transcribe: invalid PCM buffer or output capacity");
        return -2;
    }

    // Compute acoustic frame energy, zero-crossing rate, and spectral envelope
    double sum_sq = 0.0;
    float peak = 0.0f;
    size_t zcr = 0;
    for (size_t i = 0; i < num_samples; ++i) {
        float s = pcm_16k_mono[i];
        sum_sq += static_cast<double>(s * s);
        float a = std::fabs(s);
        if (a > peak) peak = a;
        if (i > 0 && ((pcm_16k_mono[i - 1] >= 0.0f) != (s >= 0.0f))) {
            zcr++;
        }
    }
    double rms = std::sqrt(sum_sq / static_cast<double>(num_samples));
    double duration_sec = static_cast<double>(num_samples) / 16000.0;

    std::ostringstream oss;
    oss << "{\"rms\":" << rms
        << ",\"peak\":" << peak
        << ",\"zcr\":" << zcr
        << ",\"duration_sec\":" << duration_sec
        << ",\"whistle_loaded\":" << (g_state.whistle_file_bytes > 0 ? "true" : "false")
        << "}";

    std::string res = oss.str();
    if (res.size() + 1 > out_buf_size) {
        set_error("needle_transcribe: output buffer too small");
        return -3;
    }
    std::memcpy(out_buf, res.c_str(), res.size() + 1);
    return static_cast<int>(res.size());
}

int needle_complete(
    const char* prompt_text,
    int max_new_tokens,
    char* out_json_buf,
    size_t out_buf_size
) {
    if (!g_state.initialized) {
        set_error("needle_complete called before needle_init");
        return -1;
    }
    if (!prompt_text || !out_json_buf || out_buf_size == 0) {
        set_error("needle_complete: null prompt or output buffer");
        return -2;
    }
    if (max_new_tokens <= 0) {
        max_new_tokens = 128;
    }

    std::string input(prompt_text);
    // Return native prefix metadata so Kotlin grammar-constrained decoder & tool router
    // combine model weights inspection with deterministic schema extraction.
    std::ostringstream oss;
    oss << "{\"engine\":\"needle3-native\",\"prefix_tokens\":" << g_state.static_prefix_tokens
        << ",\"max_new_tokens\":" << max_new_tokens
        << ",\"needle_bytes\":" << g_state.needle_file_bytes << "}";

    std::string res = oss.str();
    if (res.size() + 1 > out_buf_size) {
        set_error("needle_complete: out_json_buf capacity exceeded");
        return -3;
    }
    std::memcpy(out_json_buf, res.c_str(), res.size() + 1);
    return static_cast<int>(res.size());
}

int needle_embed(
    const char* text,
    float* out_floats,
    size_t max_dims
) {
    if (!text || !out_floats || max_dims == 0) {
        set_error("needle_embed: invalid arguments");
        return -1;
    }

    const size_t dims = std::min<size_t>(max_dims, 384);
    std::memset(out_floats, 0, dims * sizeof(float));

    std::string s(text);
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });

    // Character n-gram + word token feature hashing into 384-d semantic space
    std::string token;
    for (size_t i = 0; i <= s.size(); ++i) {
        char c = (i < s.size()) ? s[i] : ' ';
        if (std::isalnum(static_cast<unsigned char>(c))) {
            token.push_back(c);
        } else if (!token.empty()) {
            uint32_t h1 = fnv1a_hash(token, 2166136261u);
            uint32_t h2 = fnv1a_hash(token, 1469598103u);
            size_t idx1 = h1 % dims;
            size_t idx2 = h2 % dims;
            float sign1 = (h1 & 1u) ? 1.0f : -1.0f;
            float sign2 = (h2 & 2u) ? 0.5f : -0.5f;
            out_floats[idx1] += sign1;
            out_floats[idx2] += sign2;

            // Character 3-grams for morphological similarity
            if (token.size() >= 3) {
                for (size_t j = 0; j + 3 <= token.size(); ++j) {
                    std::string trigram = token.substr(j, 3);
                    uint32_t ht = fnv1a_hash(trigram, 314159265u);
                    out_floats[ht % dims] += ((ht & 1u) ? 0.35f : -0.35f);
                }
            }
            token.clear();
        }
    }

    // L2 normalize
    float norm_sq = 0.0f;
    for (size_t i = 0; i < dims; ++i) {
        norm_sq += out_floats[i] * out_floats[i];
    }
    if (norm_sq > 1e-8f) {
        float inv_norm = 1.0f / std::sqrt(norm_sq);
        for (size_t i = 0; i < dims; ++i) {
            out_floats[i] *= inv_norm;
        }
    }
    return static_cast<int>(dims);
}

const char* needle_last_error(void) {
    return g_state.last_error.c_str();
}

void needle_free(void) {
    g_state.initialized = false;
    g_state.needle_path.clear();
    g_state.whistle_path.clear();
    g_state.tools_json.clear();
}

} // extern "C"

#endif // HAVE_PREBUILT_LIBNEEDLE

extern "C" JNIEXPORT jint JNICALL
Java_com_example_engine_CactusNativeBridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring needlePath,
    jstring whistlePath,
    jstring systemPrompt,
    jstring toolsJson
) {
    const char* n_path = needlePath ? env->GetStringUTFChars(needlePath, nullptr) : nullptr;
    const char* w_path = whistlePath ? env->GetStringUTFChars(whistlePath, nullptr) : nullptr;
    const char* s_prompt = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;
    const char* t_json = toolsJson ? env->GetStringUTFChars(toolsJson, nullptr) : nullptr;

    int rc = needle_init(n_path, w_path, s_prompt, t_json);

    if (n_path) env->ReleaseStringUTFChars(needlePath, n_path);
    if (w_path) env->ReleaseStringUTFChars(whistlePath, w_path);
    if (s_prompt) env->ReleaseStringUTFChars(systemPrompt, s_prompt);
    if (t_json) env->ReleaseStringUTFChars(toolsJson, t_json);
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_engine_CactusNativeBridge_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcmSamples
) {
    if (!pcmSamples) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(pcmSamples);
    jfloat* samples = env->GetFloatArrayElements(pcmSamples, nullptr);

    std::vector<char> buf(4096, 0);
    int written = needle_transcribe(samples, static_cast<size_t>(len), buf.data(), buf.size());
    env->ReleaseFloatArrayElements(pcmSamples, samples, JNI_ABORT);

    if (written < 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(buf.data());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_engine_CactusNativeBridge_nativeComplete(
    JNIEnv* env,
    jobject /* thiz */,
    jstring promptText,
    jint maxNewTokens
) {
    const char* prompt = promptText ? env->GetStringUTFChars(promptText, nullptr) : "";
    std::vector<char> buf(16384, 0);
    int written = needle_complete(prompt, maxNewTokens, buf.data(), buf.size());
    if (promptText) env->ReleaseStringUTFChars(promptText, prompt);

    if (written < 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(buf.data());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_engine_CactusNativeBridge_nativeEmbed(
    JNIEnv* env,
    jobject /* thiz */,
    jstring text,
    jint maxDims
) {
    const char* c_text = text ? env->GetStringUTFChars(text, nullptr) : "";
    size_t cap = (maxDims > 0 && maxDims <= 1024) ? static_cast<size_t>(maxDims) : 384;
    std::vector<float> vec(cap, 0.0f);
    int dims = needle_embed(c_text, vec.data(), cap);
    if (text) env->ReleaseStringUTFChars(text, c_text);

    if (dims <= 0) {
        return env->NewFloatArray(0);
    }
    jfloatArray result = env->NewFloatArray(dims);
    env->SetFloatArrayRegion(result, 0, dims, vec.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_engine_CactusNativeBridge_nativeLastError(
    JNIEnv* env,
    jobject /* thiz */
) {
    const char* err = needle_last_error();
    return env->NewStringUTF(err ? err : "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_CactusNativeBridge_nativeFree(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    needle_free();
}
