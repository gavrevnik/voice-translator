#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct whisper_jni_context {
    struct whisper_context *whisper;
    struct whisper_vad_context *vad;
    char *vad_model_path;
    int vad_threads;
    atomic_bool abort_requested;
};

static struct whisper_jni_context *wrap_context(struct whisper_context *context) {
    if (context == NULL) {
        return NULL;
    }
    struct whisper_jni_context *wrapper = malloc(sizeof(struct whisper_jni_context));
    if (wrapper == NULL) {
        whisper_free(context);
        return NULL;
    }
    wrapper->whisper = context;
    wrapper->vad = NULL;
    wrapper->vad_model_path = NULL;
    wrapper->vad_threads = 0;
    atomic_init(&wrapper->abort_requested, false);
    return wrapper;
}

static bool abort_requested(void *user_data) {
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) user_data;
    return atomic_load_explicit(&wrapper->abort_requested, memory_order_acquire);
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init_with_params(&loader, whisper_context_default_params());
    return (jlong) wrap_context(context);
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) wrap_context(context);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) wrap_context(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    if (wrapper == NULL) {
        return;
    }
    atomic_store_explicit(&wrapper->abort_requested, true, memory_order_release);
    whisper_vad_free(wrapper->vad);
    free(wrapper->vad_model_path);
    whisper_free(wrapper->whisper);
    free(wrapper);
}

static bool ensure_vad_context(
        struct whisper_jni_context *wrapper,
        const char *model_path,
        int num_threads) {
    if (
        wrapper->vad != NULL &&
        wrapper->vad_model_path != NULL &&
        strcmp(wrapper->vad_model_path, model_path) == 0 &&
        wrapper->vad_threads == num_threads
    ) {
        return true;
    }
    whisper_vad_free(wrapper->vad);
    wrapper->vad = NULL;
    free(wrapper->vad_model_path);
    wrapper->vad_model_path = NULL;
    wrapper->vad_threads = 0;

    struct whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = num_threads;
    params.use_gpu = false;
    wrapper->vad = whisper_vad_init_from_file_with_params(model_path, params);
    if (wrapper->vad == NULL) {
        return false;
    }
    wrapper->vad_model_path = strdup(model_path);
    if (wrapper->vad_model_path == NULL) {
        whisper_vad_free(wrapper->vad);
        wrapper->vad = NULL;
        return false;
    }
    wrapper->vad_threads = num_threads;
    return true;
}

static jlongArray empty_long_array(JNIEnv *env) {
    return (*env)->NewLongArray(env, 0);
}

JNIEXPORT jlongArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_detectSpeechBounds(
        JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data,
        jstring vad_model_path_str, jint num_threads, jfloat threshold,
        jint min_speech_duration_ms, jint min_silence_duration_ms) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    if (wrapper == NULL || audio_data == NULL || vad_model_path_str == NULL) {
        return empty_long_array(env);
    }

    const char *vad_model_path = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
    if (vad_model_path == NULL) {
        return empty_long_array(env);
    }
    if (!ensure_vad_context(wrapper, vad_model_path, max(1, num_threads))) {
        (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path);
        LOGW("Failed to initialize the VAD model");
        return empty_long_array(env);
    }
    (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path);

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (audio_data_arr == NULL) {
        return empty_long_array(env);
    }
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    struct whisper_vad_params params = whisper_vad_default_params();
    params.threshold = threshold;
    params.min_speech_duration_ms = min_speech_duration_ms;
    params.min_silence_duration_ms = min_silence_duration_ms;
    params.speech_pad_ms = 0;
    params.samples_overlap = 0.0f;
    struct whisper_vad_segments *segments = whisper_vad_segments_from_samples(
            wrapper->vad,
            params,
            audio_data_arr,
            audio_data_length);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    if (segments == NULL) {
        return empty_long_array(env);
    }

    const int segment_count = whisper_vad_segments_n_segments(segments);
    if (segment_count <= 0) {
        whisper_vad_free_segments(segments);
        return empty_long_array(env);
    }
    const float start_cs = whisper_vad_segments_get_segment_t0(segments, 0);
    const float end_cs = whisper_vad_segments_get_segment_t1(segments, segment_count - 1);
    whisper_vad_free_segments(segments);

    jlong bounds[2];
    bounds[0] = (jlong) (start_cs * 160.0f);
    bounds[1] = (jlong) (end_cs * 160.0f);
    bounds[0] = bounds[0] < 0 ? 0 : bounds[0];
    bounds[1] = bounds[1] > audio_data_length ? audio_data_length : bounds[1];
    if (bounds[1] <= bounds[0]) {
        return empty_long_array(env);
    }
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 2, bounds);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_requestAbort(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    if (wrapper != NULL) {
        atomic_store_explicit(&wrapper->abort_requested, true, memory_order_release);
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getDetailedTimings(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    if (wrapper == NULL) {
        return (*env)->NewLongArray(env, 0);
    }
    struct whisper_detailed_timings timings = {0};
    if (!whisper_get_detailed_timings(wrapper->whisper, &timings)) {
        return (*env)->NewLongArray(env, 0);
    }
    jlong values[13] = {
            timings.mel_us,
            timings.sample_us,
            timings.encode_us,
            timings.decode_us,
            timings.batchd_us,
            timings.prompt_us,
            timings.sample_runs,
            timings.encode_runs,
            timings.decode_runs,
            timings.batchd_runs,
            timings.prompt_runs,
            timings.fallback_prompt_runs,
            timings.fallback_hallucination_runs,
    };
    jlongArray result = (*env)->NewLongArray(env, 13);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 13, values);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data,
        jstring language_str, jstring initial_prompt_str, jboolean final_decode) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    struct whisper_context *context = wrapper->whisper;
    // Clear a previous cancellation before doing any JNI preparation. Once
    // this call has started, a concurrent requestAbort() must never be lost.
    atomic_store_explicit(&wrapper->abort_requested, false, memory_order_release);
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);
    const char *initial_prompt = initial_prompt_str == NULL
            ? NULL
            : (*env)->GetStringUTFChars(env, initial_prompt_str, NULL);

    // The below adapted from the Objective-C iOS sample
    const enum whisper_sampling_strategy strategy = final_decode
            ? WHISPER_SAMPLING_BEAM_SEARCH
            : WHISPER_SAMPLING_GREEDY;
    struct whisper_full_params params = whisper_full_default_params(strategy);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language;
    params.detect_language = false;
    params.initial_prompt = initial_prompt;
    params.carry_initial_prompt = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = wrapper;
    // Balanced final preset: retain beam search, but avoid timestamp decoding
    // and repeated temperature fallbacks that can dominate mobile latency.
    params.no_timestamps = true;
    params.single_segment = true;
    params.temperature_inc = 0.0f;
    if (final_decode) {
        params.beam_search.beam_size = 3;
    } else {
        params.greedy.best_of = 1;
    }

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    const int result = whisper_full(context, params, audio_data_arr, audio_data_length);
    if (result != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    if (initial_prompt != NULL) {
        (*env)->ReleaseStringUTFChars(env, initial_prompt_str, initial_prompt);
    }
    (*env)->ReleaseStringUTFChars(env, language_str, language);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    struct whisper_context *context = wrapper->whisper;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    struct whisper_context *context = wrapper->whisper;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    struct whisper_context *context = wrapper->whisper;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_jni_context *wrapper = (struct whisper_jni_context *) context_ptr;
    struct whisper_context *context = wrapper->whisper;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
