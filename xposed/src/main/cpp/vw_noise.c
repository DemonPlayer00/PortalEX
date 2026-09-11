/*
 * 注入噪声档（Calibration 页可编辑）—— 逐轴 σ + 高斯动态值。
 *
 * 口径：默认 σ = 旧硬编码半宽 ÷ √3（旧口径是均匀分布 [−A,A]，σ = A/√3）⇒ 不改动时
 * **方差与旧行为一致**，只是分布由均匀改为高斯。陀螺零偏默认 0。
 *
 * 并发：与主世界**共用同一把锁** [g_lock]（见 vw_internal.h）—— 写入走 vw_set_noise，
 * 读取发生在生成期（fill_values 在 g_lock 内），靠这把锁串起来；拆分不得改成私有锁。
 *
 * 索引契约见 virtual_world.h 的 VW_NOISE_* 与 Kotlin 侧 SensorNoise（三处必须同序）。
 */
#include <stdio.h>
#include <string.h>

#include <android/log.h>
#define LOG_TAG "PortalSensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "vw_internal.h"

static float g_noise[VW_NOISE_COUNT] = {
    /* GYRO σ x/y/z     */ 0.000577f, 0.000577f, 0.000577f, /* 0.001/√3 */
    /* GYRO 零偏 x/y/z  */ 0.0f, 0.0f, 0.0f,
    /* ACCEL σ          */ 0.005774f, 0.005774f, 0.005774f, /* 0.01/√3 */
    /* GRAVITY σ        */ 0.005774f, 0.005774f, 0.005774f,
    /* LINEAR σ         */ 0.005774f, 0.005774f, 0.005774f,
    /* MAG σ            */ 0.2078f, 0.1212f, 0.3233f,       /* 0.36/0.21/0.56 ÷ √3 */
    /* ORIENT σ         */ 0.0866f,                        /* 0.15/√3 */
    /* ROTVEC σ         */ 0.000866f,                      /* 0.0015/√3 */
};

float vw_noise_raw(int index) {
    /* 无锁读取：**调用方必须已持有 g_lock**（生成期 fill_values 就在锁内）。
     * 不要在这里补锁 —— pthread 互斥量不可重入，会在生成路径上自死锁。 */
    if (index < 0 || index >= VW_NOISE_COUNT) return 0.0f;
    return g_noise[index];
}

void add_noise_i(portal_sensor_event_t *e, int index, float sigma) {
    if (index < 0 || index >= 16) return;
    if (!(sigma > 0.0f)) return;
    e->data.f[index] += (float) (vw_gauss() * (double) sigma);
}

void add_noise_xyz(portal_sensor_event_t *e, int base) {
    /* 读 g_noise 时不取锁：调用方（fill_values）已在 g_lock 内，重复取锁会自死锁 */
    add_noise_i(e, 0, g_noise[base]);
    add_noise_i(e, 1, g_noise[base + 1]);
    add_noise_i(e, 2, g_noise[base + 2]);
}

void vw_set_noise(int index, float amp) {
    if (index < 0 || index >= VW_NOISE_COUNT) return;
    if (amp != amp) return; /* NaN 丢弃 */
    int is_bias = (index >= VW_NOISE_BIAS_BASE && index <= VW_NOISE_BIAS_END);
    if (is_bias) {
        /* 零偏可为负（真机零偏本来就是有符号的）；±50 防手滑 */
        if (amp > 50.0f) amp = 50.0f;
        if (amp < -50.0f) amp = -50.0f;
    } else {
        if (!(amp >= 0.0f)) return; /* σ 为负无意义 */
        if (amp > 50.0f) amp = 50.0f;
    }
    pthread_mutex_lock(&g_lock);
    float old = g_noise[index];
    g_noise[index] = amp;
    pthread_mutex_unlock(&g_lock);
    if (old != amp) LOGI("noise[%d] %.5f -> %.5f", index, old, amp);
}

void vw_get_noise(float *out, int count) {
    if (out == NULL || count <= 0) return;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < count && i < VW_NOISE_COUNT; i++) out[i] = g_noise[i];
    pthread_mutex_unlock(&g_lock);
}

int vw_dump_noise(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    float n[VW_NOISE_COUNT];
    vw_get_noise(n, VW_NOISE_COUNT);
    int w = snprintf(out, out_size,
                     "gy=%.4f/%.4f/%.4f bias=%.4f/%.4f/%.4f acc=%.4f/%.4f/%.4f "
                     "grav=%.4f/%.4f/%.4f lin=%.4f/%.4f/%.4f mag=%.3f/%.3f/%.3f o=%.4f r=%.4f",
                     n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9], n[10], n[11],
                     n[12], n[13], n[14], n[15], n[16], n[17], n[18], n[19]);
    return w > 0 ? w : 0;
}
