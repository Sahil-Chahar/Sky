#ifndef CACTUS_NEEDLE_H
#define CACTUS_NEEDLE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Cactus Compute — Needle 3 & Whistle C API Header (needle.h)
 *
 * Process-global model & conversation state:
 * All calls into needle_* MUST be serialized on a single dedicated thread.
 */

/**
 * Initializes the Needle 3 engine with the given .cact model file, optional
 * whistle.cact speech model path, system prompt, and JSON array of tool schemas.
 *
 * When > 5 tools are declared in tools_json, Needle's built-in contrastive
 * tool retrieval automatically indexes tool descriptions and selects the top 5
 * candidates per completion turn.
 *
 * @param needle_model_path  Filesystem path to needle3.cact
 * @param whistle_model_path Filesystem path to whistle.cact (nullable if text-only)
 * @param system_prompt      Static system prompt prefix
 * @param tools_json         Compact JSON array of tool schemas
 * @return Tokenized static prefix length (>= 0) on success, or negative error code on failure.
 */
int needle_init(
    const char* needle_model_path,
    const char* whistle_model_path,
    const char* system_prompt,
    const char* tools_json
);

/**
 * Transcribes 16 kHz mono float32 PCM audio samples using the loaded whistle.cact model.
 *
 * @param pcm_16k_mono   Pointer to float32 PCM samples normalized to [-1.0, 1.0]
 * @param num_samples    Number of samples at 16,000 Hz
 * @param out_buf        Caller-allocated UTF-8 buffer for JSON/text transcript output
 * @param out_buf_size   Capacity of out_buf in bytes
 * @return Number of bytes written (>= 0), or negative error code.
 */
int needle_transcribe(
    const float* pcm_16k_mono,
    size_t num_samples,
    char* out_buf,
    size_t out_buf_size
);

/**
 * Runs grammar-constrained tool-calling completion on the user query.
 * Outputs deterministic JSON containing:
 *   { "function_calls": [...], "suppressed_calls": [...], "confidence": 0.92, "retrieved_tools": [...] }
 *
 * @param prompt_text     Input transcript or user request
 * @param max_new_tokens  Maximum tokens to decode (keep modest, e.g. 128)
 * @param out_json_buf    Caller-allocated UTF-8 buffer for structured JSON output
 * @param out_buf_size    Capacity of out_json_buf in bytes
 * @return Number of bytes written (>= 0), or negative error code.
 */
int needle_complete(
    const char* prompt_text,
    int max_new_tokens,
    char* out_json_buf,
    size_t out_buf_size
);

/**
 * Computes L2-normalized semantic vector embeddings using Needle 3's embedding head.
 *
 * @param text         UTF-8 input text to embed
 * @param out_floats   Caller-allocated float buffer (capacity >= max_dims)
 * @param max_dims     Maximum embedding dimensions (typically 384)
 * @return Number of dimensions written (> 0), or negative error code.
 */
int needle_embed(
    const char* text,
    float* out_floats,
    size_t max_dims
);

/**
 * Returns a descriptive UTF-8 string of the last error encountered on the Needle thread.
 */
const char* needle_last_error(void);

/**
 * Releases native buffers and resets process-global state.
 */
void needle_free(void);

#ifdef __cplusplus
}
#endif

#endif // CACTUS_NEEDLE_H
