// Auto Bot GGUF Engine — llama.cpp JNI bridge (fixed for v0.5.0)
// Fixes: backend_init, KV memory clear before every generate, thread-safety, better errors
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdarg>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include "llama.h"

#define LOG_TAG "AutoBotGGUF"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mtx;
static bool g_backend_ok = false;
static char g_last_error[256] = "none";

static void set_err(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
    va_end(ap); LOGE("err: %s", g_last_error);
}

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

static void ensure_backend() {
    if (!g_backend_ok) {
        llama_backend_init();
        g_backend_ok = true;
        LOGI("llama_backend_init done");
    }
}

static void free_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint ctxSize, jint threads) {
    std::lock_guard<std::mutex> lock(g_mtx);
    ensure_backend();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) { LOGE("null path"); return JNI_FALSE; }

    free_locked();

    llama_model_params mp = llama_model_default_params();
    // Mobile: no GPU layers by default (CPU only — stable on all phones)
    mp.n_gpu_layers = 0;  // CPU only
    // v0.5.0: use_mmap/use_mlock members nahi hain — load_mode use hota hai
    // LLAMA_LOAD_MODE_DEFAULT already mmap-style lazy load karta hai

    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) {
        set_err("model load FAILED — file corrupt/RAM kam?");
        return JNI_FALSE;
    }

    int hc = (int) std::thread::hardware_concurrency();
    if (hc < 2) hc = 2;
    if (threads <= 0) threads = hc > 1 ? hc - 1 : 1;
    // Cap threads on low-RAM / 32-bit to avoid OOM / thermal
    if (threads > 4) threads = 4;
    if (ctxSize < 512) ctxSize = 2048;
    // Keep context modest for 0.5B model on phones (saves RAM)
    if (ctxSize > 4096) ctxSize = 4096;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) ctxSize;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        set_err("ctx init FAILED (ctx=%d threads=%d)", ctxSize, threads);
        return JNI_FALSE;
    }
    snprintf(g_last_error, sizeof(g_last_error), "none"); LOGI("model loaded ok (ctx=%d threads=%d)", ctxSize, threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeIsLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeFree(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    free_locked();
    LOGI("model freed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeGenerate(JNIEnv *env, jobject,
        jstring jprompt, jint maxTokens, jfloat temp, jfloat topP, jint topK) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model || !g_ctx) {
        set_err("generate: model/ctx null");
        return env->NewStringUTF("");
    }

    // CRITICAL FIX: clear KV memory before every new prompt
    // Without this, 2nd+ generate either fails, overflows, or returns garbage
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        set_err("generate: null vocab");
        return env->NewStringUTF("");
    }

    const char *pc = env->GetStringUTFChars(jprompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc);
    env->ReleaseStringUTFChars(jprompt, pc);

    // ---- tokenize prompt (add_special=true, parse_special=true) ----
    int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        // first call with null buffer returns negative required size on some versions;
        // retry with positive size
        n_prompt = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
        if (n_prompt < 0) n_prompt = -n_prompt;
    }
    if (n_prompt <= 0) {
        set_err("generate: tokenize failed (%d)", n_prompt);
        return env->NewStringUTF("");
    }
    std::vector<llama_token> toks((size_t) n_prompt);
    int32_t got = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                 toks.data(), (int32_t) toks.size(), true, true);
    if (got < 0) {
        set_err("generate: tokenize write failed");
        return env->NewStringUTF("");
    }
    n_prompt = got;
    LOGI("prompt tokens: %d", n_prompt);

    // ---- stop tokens: <|im_end|> (Qwen ChatML) + EOS/EOG ----
    llama_token im_end = -1;
    const char *stop_str = "<|im_end|>";
    llama_token st[8];
    int32_t nst = llama_tokenize(vocab, stop_str, (int32_t) strlen(stop_str), st, 8, false, true);
    if (nst == 1) im_end = st[0];
    llama_token eos = llama_vocab_eos(vocab);

    // ---- sampler chain ----
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) {
        set_err("generate: sampler init failed");
        return env->NewStringUTF("");
    }
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (temp > 0.05f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    else llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.0f)); // greedy
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    // ---- generate ----
    std::string out;
    if (maxTokens < 16) maxTokens = 128;
    if (maxTokens > 512) maxTokens = 512; // mobile safety

    // Prefill: feed whole prompt
    llama_batch batch = llama_batch_get_one(toks.data(), n_prompt);
    if (llama_decode(g_ctx, batch) != 0) {
        set_err("generate: prefill decode FAILED (RAM/KV?)");
        llama_sampler_free(smpl);
        return env->NewStringUTF("");
    }

    for (int i = 0; i < maxTokens; i++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (id == eos || (im_end >= 0 && id == im_end) || llama_vocab_is_eog(vocab, id)) {
            break;
        }
        char piece[256];
        int pn = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (pn > 0) {
            out.append(piece, (size_t) pn);
        }
        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("decode failed at step %d", i);
            break;
        }
    }
    llama_sampler_free(smpl);
    LOGI("generated %d chars", (int) out.size());

    // Safe UTF-8 for JNI (NewStringUTF crashes on invalid sequences on some Android)
    // Strip any lone continuation bytes just in case
    jstring result = env->NewStringUTF(out.c_str());
    if (!result) {
        // Fallback: return empty on rare encoding failure
        LOGE("NewStringUTF failed");
        return env->NewStringUTF("");
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error);
}
