/*
 * JNI Bridge connecting com.firecast.local.airplay.AirPlayNativeBridge
 * to UxPlay's RAOP / AirPlay 2 server core + MIT ALAC decoder.
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <algorithm>
#include <mutex>
#include <vector>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "pairing.h"
#include "android_dnssd_shim.h"
#include "alac.h"
}

#define TAG "CastViewerAirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int CT_PCM = 1;
static constexpr int CT_ALAC = 2;
static constexpr int MAX_ALAC_SPF = 4096;

static pthread_key_t g_detach_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
static JavaVM *g_jvm = nullptr;

static void detach_current_thread(void *value) {
    (void) value;
    if (g_jvm) {
        g_jvm->DetachCurrentThread();
    }
}

static void create_detach_key() {
    pthread_key_create(&g_detach_key, detach_current_thread);
}

struct AirPlayServerCtx {
    JavaVM *jvm = nullptr;
    jobject callback_obj = nullptr;

    jmethodID on_video_data = nullptr;
    jmethodID on_video_size = nullptr;
    jmethodID on_audio_format = nullptr;
    jmethodID on_audio_data = nullptr;
    jmethodID on_audio_flush = nullptr;
    jmethodID on_client_volume = nullptr;
    jmethodID on_client_volume_query = nullptr;
    jmethodID on_conn_init = nullptr;
    jmethodID on_conn_destroy = nullptr;
    jmethodID on_conn_reset = nullptr;
    jmethodID on_video_pause = nullptr;
    jmethodID on_video_resume = nullptr;
    jmethodID on_display_pin = nullptr;

    raop_t *raop = nullptr;
    dnssd_t *dnssd = nullptr;
    int h265_enabled = 1;

    std::mutex alac_mutex;
    alac_file *alac_decoder = nullptr;
    int alac_spf = 352;
    std::vector<int16_t> alac_pcm_scratch;
};

static JNIEnv *get_env(AirPlayServerCtx *ctx) {
    if (!ctx || !ctx->jvm) return nullptr;
    JNIEnv *env = nullptr;
    int status = ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
            return nullptr;
        }
        g_jvm = ctx->jvm;
        pthread_once(&g_key_once, create_detach_key);
        pthread_setspecific(g_detach_key, reinterpret_cast<void *>(1));
    } else if (status != JNI_OK) {
        return nullptr;
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return env;
}

static void clear_if_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void raop_log_cb(void *cls, int level, const char *msg) {
    (void) cls;
    int prio = ANDROID_LOG_DEBUG;
    if (level <= LOGGER_ERR) prio = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) prio = ANDROID_LOG_WARN;
    else if (level <= LOGGER_INFO) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, "UxPlayCore", "%s", msg ? msg : "");
}

static void init_alac_decoder_locked(AirPlayServerCtx *ctx, int spf) {
    const int safe_spf = (spf > 0 && spf <= MAX_ALAC_SPF) ? spf : 352;
    if (ctx->alac_decoder && ctx->alac_spf == safe_spf) {
        return;
    }
    if (ctx->alac_decoder) {
        alac_free(ctx->alac_decoder);
        ctx->alac_decoder = nullptr;
    }
    alac_file *alac = alac_create(16, 2);
    if (!alac) {
        LOGE("alac_create failed");
        return;
    }
    alac->setinfo_max_samples_per_frame = static_cast<uint32_t>(safe_spf);
    alac->setinfo_7a = 0;
    alac->setinfo_sample_size = 16;
    alac->setinfo_rice_historymult = 40;
    alac->setinfo_rice_initialhistory = 10;
    alac->setinfo_rice_kmodifier = 14;
    alac->setinfo_7f = 2;
    alac->setinfo_80 = 0x00ff;
    alac->setinfo_82 = 0;
    alac->setinfo_86 = 0;
    alac->setinfo_8a_rate = 44100;
    alac_allocate_buffers(alac);
    ctx->alac_decoder = alac;
    ctx->alac_spf = safe_spf;
    ctx->alac_pcm_scratch.resize(static_cast<size_t>(safe_spf) * 2 + 64);
    LOGI("Initialized native ALAC decoder (spf=%d)", safe_spf);
}

static void cb_conn_init(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_conn_init) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_init);
    clear_if_exception(env);
}

static void cb_conn_destroy(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_conn_destroy) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_destroy);
    clear_if_exception(env);
}

static void cb_conn_reset(void *cls, int reason) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_conn_reset) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_reset, static_cast<jint>(reason));
    clear_if_exception(env);
}

static void cb_video_reset(void *cls, reset_type_t reset_type) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_conn_reset) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_reset, static_cast<jint>(reset_type));
    clear_if_exception(env);
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) ntp;
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    if (!data || !data->data || data->data_len <= 0) return;
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_video_data) return;

    jbyteArray jbytes = env->NewByteArray(data->data_len);
    if (!jbytes) {
        clear_if_exception(env);
        return;
    }
    env->SetByteArrayRegion(jbytes, 0, data->data_len, reinterpret_cast<const jbyte *>(data->data));
    jlong ptsUs = static_cast<jlong>(data->ntp_time_local / 1000ULL);
    env->CallVoidMethod(ctx->callback_obj, ctx->on_video_data, jbytes, ptsUs,
                        static_cast<jboolean>(data->is_h265 ? JNI_TRUE : JNI_FALSE));
    env->DeleteLocalRef(jbytes);
    clear_if_exception(env);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec,
                                       unsigned short width_source, unsigned short height_source,
                                       unsigned short x_offset, unsigned short y_offset,
                                       unsigned short width, unsigned short height) {
    (void) x_offset;
    (void) y_offset;
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    if (!ctx) return -1;
    if (codec == VIDEO_CODEC_H265 && !ctx->h265_enabled) {
        LOGW("Rejecting H.265 stream because hardware HEVC is disabled");
        return -1;
    }
    JNIEnv *env = get_env(ctx);
    if (env && ctx->callback_obj && ctx->on_video_size) {
        const jboolean is_h265 = (codec == VIDEO_CODEC_H265) ? JNI_TRUE : JNI_FALSE;
        const jint w = (width > 0) ? static_cast<jint>(width) : static_cast<jint>(width_source);
        const jint h = (height > 0) ? static_cast<jint>(height) : static_cast<jint>(height_source);
        env->CallVoidMethod(ctx->callback_obj, ctx->on_video_size, is_h265,
                            static_cast<jint>(width_source), static_cast<jint>(height_source), w, h);
        clear_if_exception(env);
    }
    return 0;
}

static void cb_video_pause(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_video_pause) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_video_pause);
    clear_if_exception(env);
}

static void cb_video_resume(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_video_resume) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_video_resume);
    clear_if_exception(env);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                                bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void) isMedia;
    (void) audioFormat;
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    if (!ctx) return;

    const unsigned char codec_type = ct ? *ct : 0;
    const unsigned short samples_per_frame = spf ? *spf : 0;
    const bool screen_flag = usingScreen ? *usingScreen : false;

    if (codec_type == CT_ALAC) {
        std::lock_guard<std::mutex> lock(ctx->alac_mutex);
        init_alac_decoder_locked(ctx, samples_per_frame);
    }

    JNIEnv *env = get_env(ctx);
    if (env && ctx->callback_obj && ctx->on_audio_format) {
        env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_format,
                            static_cast<jint>(codec_type),
                            static_cast<jint>(samples_per_frame),
                            static_cast<jboolean>(screen_flag ? JNI_TRUE : JNI_FALSE));
        clear_if_exception(env);
    }
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) ntp;
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    if (!ctx || !data || !data->data || data->data_len <= 0) return;

    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_audio_data) return;

    const jlong ptsUs = static_cast<jlong>(data->ntp_time_local / 1000ULL);

    if (data->ct == CT_ALAC) {
        std::lock_guard<std::mutex> lock(ctx->alac_mutex);
        if (!ctx->alac_decoder) {
            init_alac_decoder_locked(ctx, ctx->alac_spf);
        }
        if (!ctx->alac_decoder) return;

        int out_bytes = static_cast<int>(ctx->alac_pcm_scratch.size() * sizeof(int16_t));
        alac_decode_frame(ctx->alac_decoder, data->data, ctx->alac_pcm_scratch.data(), &out_bytes);
        const int max_bytes = static_cast<int>(ctx->alac_pcm_scratch.size() * sizeof(int16_t));
        if (out_bytes <= 0 || out_bytes > max_bytes) return;

        jbyteArray jpcm = env->NewByteArray(out_bytes);
        if (!jpcm) {
            clear_if_exception(env);
            return;
        }
        env->SetByteArrayRegion(jpcm, 0, out_bytes, reinterpret_cast<const jbyte *>(ctx->alac_pcm_scratch.data()));
        env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_data, jpcm, static_cast<jint>(CT_PCM), ptsUs);
        env->DeleteLocalRef(jpcm);
        clear_if_exception(env);
        return;
    }

    jbyteArray jbytes = env->NewByteArray(data->data_len);
    if (!jbytes) {
        clear_if_exception(env);
        return;
    }
    env->SetByteArrayRegion(jbytes, 0, data->data_len, reinterpret_cast<const jbyte *>(data->data));
    env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_data, jbytes, static_cast<jint>(data->ct), ptsUs);
    env->DeleteLocalRef(jbytes);
    clear_if_exception(env);
}

static void cb_audio_flush(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_audio_flush) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_flush);
    clear_if_exception(env);
}

static void cb_audio_set_volume(void *cls, float volume) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_client_volume) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_client_volume, static_cast<jfloat>(volume));
    clear_if_exception(env);
}

static double cb_audio_set_client_volume(void *cls) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_client_volume_query) return -12.0;
    jfloat vol = env->CallFloatMethod(ctx->callback_obj, ctx->on_client_volume_query);
    clear_if_exception(env);
    return static_cast<double>(vol);
}

static void cb_display_pin(void *cls, char *pin) {
    auto *ctx = static_cast<AirPlayServerCtx *>(cls);
    if (!pin) return;
    JNIEnv *env = get_env(ctx);
    if (!env || !ctx->callback_obj || !ctx->on_display_pin) return;
    jstring jpin = env->NewStringUTF(pin);
    if (jpin) {
        env->CallVoidMethod(ctx->callback_obj, ctx->on_display_pin, jpin);
        env->DeleteLocalRef(jpin);
    }
    clear_if_exception(env);
}

static void cb_video_flush(void *cls) { (void) cls; }
static void cb_conn_feedback(void *cls) { (void) cls; }
static void cb_mirror_video_running(void *cls, bool is_running) { (void) cls; (void) is_running; }

extern "C"
JNIEXPORT jlong JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeInit(
        JNIEnv *env, jobject thiz,
        jobject callback, jbyteArray hwAddr, jstring name, jstring keyFile,
        jboolean nohold, jboolean requirePin) {
    (void) thiz;
    if (!callback || !hwAddr || !name || !keyFile) return 0;

    auto *ctx = new AirPlayServerCtx();
    env->GetJavaVM(&ctx->jvm);
    ctx->callback_obj = env->NewGlobalRef(callback);

    jclass cls = env->GetObjectClass(callback);
    ctx->on_video_data = env->GetMethodID(cls, "onVideoData", "([BJZ)V");
    ctx->on_video_size = env->GetMethodID(cls, "onVideoSize", "(ZIIII)V");
    ctx->on_audio_format = env->GetMethodID(cls, "onAudioFormat", "(IIZ)V");
    ctx->on_audio_data = env->GetMethodID(cls, "onAudioData", "([BIJ)V");
    ctx->on_audio_flush = env->GetMethodID(cls, "onAudioFlush", "()V");
    ctx->on_client_volume = env->GetMethodID(cls, "onClientVolume", "(F)V");
    ctx->on_client_volume_query = env->GetMethodID(cls, "onClientVolumeQuery", "()F");
    ctx->on_conn_init = env->GetMethodID(cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = env->GetMethodID(cls, "onConnectionDestroy", "()V");
    ctx->on_conn_reset = env->GetMethodID(cls, "onConnectionReset", "(I)V");
    ctx->on_video_pause = env->GetMethodID(cls, "onVideoPause", "()V");
    ctx->on_video_resume = env->GetMethodID(cls, "onVideoResume", "()V");
    ctx->on_display_pin = env->GetMethodID(cls, "onDisplayPin", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteGlobalRef(ctx->callback_obj);
        delete ctx;
        return 0;
    }

    char raw_hw[6] = {0};
    const jsize hw_len = std::min<jsize>(env->GetArrayLength(hwAddr), 6);
    if (hw_len > 0) {
        env->GetByteArrayRegion(hwAddr, 0, hw_len, reinterpret_cast<jbyte *>(raw_hw));
    }

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.cls = ctx;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.display_pin = cb_display_pin;

    ctx->raop = raop_init(&cbs);
    if (!ctx->raop) {
        LOGE("raop_init failed");
        env->DeleteGlobalRef(ctx->callback_obj);
        delete ctx;
        return 0;
    }

    raop_set_log_level(ctx->raop, LOGGER_INFO);
    raop_set_log_callback(ctx->raop, raop_log_cb, nullptr);

    const char *keyfile_c = env->GetStringUTFChars(keyFile, nullptr);
    const char *name_c = env->GetStringUTFChars(name, nullptr);

    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             static_cast<unsigned char>(raw_hw[0]), static_cast<unsigned char>(raw_hw[1]),
             static_cast<unsigned char>(raw_hw[2]), static_cast<unsigned char>(raw_hw[3]),
             static_cast<unsigned char>(raw_hw[4]), static_cast<unsigned char>(raw_hw[5]));

    if (raop_init2(ctx->raop, nohold ? 1 : 0, device_id, keyfile_c) < 0) {
        LOGE("raop_init2 failed");
    }

    if (requirePin) {
        int pin = random_pin();
        if (pin < 0) pin = 1234;
        raop_set_plist(ctx->raop, "pin", pin + 10000);
    }

    int dns_err = 0;
    const unsigned char pin_pw = requirePin ? 1 : 0;
    ctx->dnssd = dnssd_init(name_c, static_cast<int>(strlen(name_c)), raw_hw, 6, pin_pw, &dns_err);
    if (!ctx->dnssd) {
        LOGE("dnssd_init failed: %d", dns_err);
    } else {
        raop_set_dnssd(ctx->raop, ctx->dnssd);
        // Enable PCM, ALAC (native C decoder), AAC-LC, AAC-ELD
        android_dnssd_set_codecs(ctx->dnssd, 1, 1);
    }

    env->ReleaseStringUTFChars(keyFile, keyfile_c);
    env->ReleaseStringUTFChars(name, name_c);

    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeStart(
        JNIEnv *env, jobject thiz, jlong handle, jint requestedPort) {
    (void) env;
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->raop) return -1;

    unsigned short port = static_cast<unsigned short>(requestedPort > 0 ? requestedPort : 7000);
    if (raop_start_httpd(ctx->raop, &port) < 0) {
        LOGW("Port %u busy, retrying ephemeral port", port);
        port = 0;
        if (raop_start_httpd(ctx->raop, &port) < 0) {
            LOGE("raop_start_httpd failed on fallback ephemeral port");
            return -1;
        }
    }
    raop_set_port(ctx->raop, port);

    if (ctx->dnssd) {
        dnssd_register_raop(ctx->dnssd, port);
        dnssd_register_airplay(ctx->dnssd, port);
    }
    LOGI("AirPlay server listening on port %u", port);
    return static_cast<jint>(port);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->raop) return;

    raop_stop_httpd(ctx->raop);
    if (ctx->dnssd) {
        dnssd_unregister_raop(ctx->dnssd);
        dnssd_unregister_airplay(ctx->dnssd);
    }
    LOGI("AirPlay server stopped");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx) return;

    if (ctx->raop) {
        raop_destroy(ctx->raop);
        ctx->raop = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(ctx->alac_mutex);
        if (ctx->alac_decoder) {
            alac_free(ctx->alac_decoder);
            ctx->alac_decoder = nullptr;
        }
    }
    if (ctx->dnssd) {
        dnssd_destroy(ctx->dnssd);
        ctx->dnssd = nullptr;
    }
    if (ctx->callback_obj) {
        env->DeleteGlobalRef(ctx->callback_obj);
        ctx->callback_obj = nullptr;
    }
    delete ctx;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeSetDisplaySize(
        JNIEnv *env, jobject thiz, jlong handle, jint width, jint height, jint fps) {
    (void) env;
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->raop) return;

    raop_set_plist(ctx->raop, "width", width);
    raop_set_plist(ctx->raop, "height", height);
    raop_set_plist(ctx->raop, "refreshRate", fps);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeSetH265Enabled(
        JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    (void) env;
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx) return;
    ctx->h265_enabled = enabled ? 1 : 0;
    if (ctx->dnssd) {
        dnssd_set_airplay_features(ctx->dnssd, 42, enabled ? 1 : 0);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeDisconnectActiveClient(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->raop) return;
    raop_remove_known_connections(ctx->raop);
}

static jobject build_txt_map(JNIEnv *env, dnssd_t *dnssd, bool is_raop) {
    jclass map_cls = env->FindClass("java/util/LinkedHashMap");
    jmethodID map_init = env->GetMethodID(map_cls, "<init>", "()V");
    jmethodID map_put = env->GetMethodID(map_cls, "put",
                                         "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(map_cls, map_init);

    const int count = is_raop ? android_dnssd_get_raop_txt_count(dnssd)
                              : android_dnssd_get_airplay_txt_count(dnssd);
    for (int i = 0; i < count; i++) {
        const char *k = is_raop ? android_dnssd_get_raop_txt_key(dnssd, i)
                                : android_dnssd_get_airplay_txt_key(dnssd, i);
        const char *v = is_raop ? android_dnssd_get_raop_txt_val(dnssd, i)
                                : android_dnssd_get_airplay_txt_val(dnssd, i);
        if (k && v) {
            jstring jk = env->NewStringUTF(k);
            jstring jv = env->NewStringUTF(v);
            jobject prev = env->CallObjectMethod(map, map_put, jk, jv);
            if (prev) env->DeleteLocalRef(prev);
            env->DeleteLocalRef(jk);
            env->DeleteLocalRef(jv);
        }
    }
    env->DeleteLocalRef(map_cls);
    return map;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeGetRaopTxtRecords(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->dnssd) return nullptr;
    return build_txt_map(env, ctx->dnssd, true);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeGetAirplayTxtRecords(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->dnssd) return nullptr;
    return build_txt_map(env, ctx->dnssd, false);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_firecast_local_airplay_AirPlayNativeBridge_nativeGetRaopServiceName(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    auto *ctx = reinterpret_cast<AirPlayServerCtx *>(static_cast<intptr_t>(handle));
    if (!ctx || !ctx->dnssd) return nullptr;
    const char *serv = android_dnssd_get_raop_servname(ctx->dnssd);
    return serv ? env->NewStringUTF(serv) : nullptr;
}
