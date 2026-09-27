// Auto Bot GGUF Engine — llama.cpp JNI bridge
// llama.cpp v0.5.0 C API se model load + generate
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include "llama.h"

#define LOG_TAG "AutoBotGGUF"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint ctxSize, jint threads) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) { LOGI("model load FAILED"); return JNI_FALSE; }

    int hc = (int) std::thread::hardware_concurrency();
    if (hc < 2) hc = 2;
    if (threads <= 0) threads = hc > 1 ? hc - 1 : 1;
    if (ctxSize < 512) ctxSize = 2048;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (unsigned) ctxSize;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; LOGI("ctx init FAILED"); return JNI_FALSE; }
    LOGI("model loaded ok (ctx=%d threads=%d)", ctxSize, threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeIsLoaded(JNIEnv *, jobject) {
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeFree(JNIEnv *, jobject) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("model freed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alnoor_autobot_LlamaBridge_nativeGenerate(JNIEnv *env, jobject,
        jstring jprompt, jint maxTokens, jfloat temp, jfloat topP, jint topK) {
    if (!g_model || !g_ctx) return env->NewStringUTF("");

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    const char *pc = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(pc);
    env->ReleaseStringUTFChars(jprompt, pc);

    // ---- tokenize prompt (add_special=true, parse_special=true) ----
    int32_t n_prompt = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt < 0) return env->NewStringUTF("");
    std::vector<llama_token> toks((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true) < 0)
        return env->NewStringUTF("");
    LOGI("prompt tokens: %d", n_prompt);

    // ---- stop token: <|im_end|> (Qwen) + EOG ----
    llama_token im_end = -1;
    const char *stop_str = "<|im_end|>";
    llama_token st[8];
    int32_t nst = llama_tokenize(vocab, stop_str, (int32_t) strlen(stop_str), st, 8, false, true);
    if (nst == 1) im_end = st[0];
    llama_token eos = llama_vocab_eos(vocab);

    // ---- sampler chain ----
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP > 0 && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (temp > 0.05f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    // ---- generate ----
    std::string out;
    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    if (maxTokens < 16) maxTokens = 128;
    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(g_ctx, batch) != 0) { LOGI("decode failed"); break; }
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (id == eos || (im_end >= 0 && id == im_end) || llama_vocab_is_eog(vocab, id)) break;
        char piece[64];
        int pn = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (pn > 0) out.append(piece, (size_t) pn);
        batch = llama_batch_get_one(&id, 1);
    }
    llama_sampler_free(smpl);
    LOGI("generated %d chars", (int) out.size());
    return env->NewStringUTF(out.c_str());
}
