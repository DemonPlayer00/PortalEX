/*
 * virtual_world.c 的投递不变量测试（host，无需设备）。
 *
 * 覆盖评审里"最贵的一类 bug"：投递路径**静默丢数据 / 静默不出数据**时没人知道。
 * 这里把过程性不变量写成断言，任何一条被破坏都直接失败：
 *   1. 时间戳：全局严格单调、且不越过生成时刻（jitter 不得把事件推到未来）；
 *   2. 静默规则：框架说"没人订"（active=0）就不许再出该类型的事件；恢复活跃后必须立刻出；
 *   3. 记账守恒：Σ(每次调用返回的 n) 必须等于 emitted 的增量（一条都不许凭空消失）；
 *   4. 容量压力：cap 不足时丢的是"最新"而不是崩，且必须计入 dropped；
 *   5. 噪声档：逐轴 σ 与陀螺零偏必须真的按配置生成。**只能按统计置信区间断言**：
 *      RNG 虽是固定种子的 xorshift（virtual_world.c:227），但本测试的时间基准取自真实时钟，
 *      每次运行"抽取次数"不同 ⇒ 跨运行不可复现（实测同一断言两次跑出 0.0125 / 0.0140）。
 *
 * 编译（在 xposed/src/main/cpp 下）：
 *   cc -D_GNU_SOURCE -I test/stub -I . test/vw_invariants_test.c virtual_world.c -lpthread -lm -o /tmp/vwinv
 */
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "virtual_world.h"

static int failures = 0;
#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        if (!(cond)) {                                                         \
            failures++;                                                        \
            printf("  FAIL: ");                                                \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

#define OUT_CAP 512
static portal_sensor_event_t out[OUT_CAP];

static long long now0(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

typedef struct {
    long long n_calls;
    long long n_events;      /* Σ 返回条数 */
    long long last_ts;       /* 全局时间戳单调性 */
    int monotonic;
    int future;              /* 时间戳越过 now 的次数 */
    int by_type[256];
} collector_t;

/** 跑 [calls] 次生成，每次推进 [step_ns]，逐事件检查时间戳与计数 */
static void drive(collector_t *c, long long t0, long long *t, long long step_ns,
                  int calls, int want_poll, int cap) {
    for (int k = 0; k < calls; k++) {
        *t += step_ns;
        vw_update_state(0.0, 0.0, 0, 0, t0 + *t); /* 静止、不转弯：陀螺信号恒 0 */
        int n = vw_generate(out, cap, t0 + *t, want_poll);
        c->n_calls++;
        c->n_events += n;
        for (int i = 0; i < n; i++) {
            if (out[i].timestamp < c->last_ts) c->monotonic = 0;
            if (out[i].timestamp > t0 + *t) {
                c->future++;
                long long over = out[i].timestamp - (t0 + *t);
                if (c->future <= 3) printf("     [越界] type=%d 超出 %lld ns\n", out[i].type, over);
            }
            c->last_ts = out[i].timestamp;
            if (out[i].type > 0 && out[i].type < 256) c->by_type[out[i].type]++;
        }
    }
}

static void stats(long long *e, long long *d, long long *s) { vw_stats(e, d, s); }

int main(void) {
    vw_init();
    vw_set_handle(PS_TYPE_ACCELEROMETER, 0x0b, 0);
    vw_set_handle(PS_TYPE_GYROSCOPE, 0x29, 0);
    vw_set_handle(PS_TYPE_MAGNETIC_FIELD, 0x15, 0);
    long long t0 = now0(), t = 0;
    vw_update_state(0.0, 0.0, 0, 0, t0);

    /* ---------- 1) 时间戳单调 + 不越 now + 记账守恒 ---------- */
    vw_set_active(1);
    long long e0 = 0, d0 = 0, s0 = 0, e1 = 0, d1 = 0, s1 = 0;
    stats(&e0, &d0, &s0);
    collector_t c;
    memset(&c, 0, sizeof(c));
    c.monotonic = 1;
    c.last_ts = 0;
    drive(&c, t0, &t, 50000000LL, 200, 2, OUT_CAP);
    stats(&e1, &d1, &s1);
    printf("1) 事件=%lld accel=%d gyro=%d mag=%d\n", c.n_events,
           c.by_type[PS_TYPE_ACCELEROMETER], c.by_type[PS_TYPE_GYROSCOPE],
           c.by_type[PS_TYPE_MAGNETIC_FIELD]);
    CHECK(c.n_events > 0, "一次都没生成事件（用例前提不成立）");
    CHECK(c.by_type[PS_TYPE_ACCELEROMETER] > 0 && c.by_type[PS_TYPE_GYROSCOPE] > 0,
          "栅格类型没出事件（accel=%d gyro=%d）", c.by_type[PS_TYPE_ACCELEROMETER],
          c.by_type[PS_TYPE_GYROSCOPE]);
    CHECK(c.monotonic, "时间戳出现回退（应用侧按 dt 计算会拿到负值）");
    CHECK(c.future == 0, "有 %d 条事件的时间戳越过生成时刻（jitter 越界）", c.future);
    CHECK(e1 - e0 == c.n_events, "记账不守恒：emitted 增量 %lld ≠ Σ返回 %lld", e1 - e0, c.n_events);
    CHECK(d1 >= d0 && s1 >= s0, "dropped/suppressed 出现回退");

    /* ---------- 2) 静默规则：没人订就不出，恢复活跃立刻出 ---------- */
    vw_set_channel_hint(PS_TYPE_MAGNETIC_FIELD, 40000000LL, 0, 0); /* 标成"没人订" */
    long long t_before = t;
    collector_t c2;
    memset(&c2, 0, sizeof(c2));
    c2.monotonic = 1;
    c2.last_ts = c.last_ts;
    drive(&c2, t0, &t, 50000000LL, 20, 2, OUT_CAP);
    printf("2) 静默期 mag=%d（期望 0），accel=%d（期望 >0）\n",
           c2.by_type[PS_TYPE_MAGNETIC_FIELD], c2.by_type[PS_TYPE_ACCELEROMETER]);
    CHECK(c2.by_type[PS_TYPE_MAGNETIC_FIELD] == 0,
          "已标成静默的通道仍在出事件（%d 条）——真机 HAL 不会这样",
          c2.by_type[PS_TYPE_MAGNETIC_FIELD]);
    CHECK(c2.by_type[PS_TYPE_ACCELEROMETER] > 0, "静默其它通道时把没被标记的类型也停了");

    vw_set_channel_hint(PS_TYPE_MAGNETIC_FIELD, 40000000LL, 0, 1); /* 恢复活跃 */
    collector_t c3;
    memset(&c3, 0, sizeof(c3));
    c3.monotonic = 1;
    c3.last_ts = c2.last_ts;
    drive(&c3, t0, &t, 50000000LL, 20, 2, OUT_CAP);
    printf("3) 恢复活跃 mag=%d（期望 >0），t_before=%lld\n", c3.by_type[PS_TYPE_MAGNETIC_FIELD],
           t_before);
    CHECK(c3.by_type[PS_TYPE_MAGNETIC_FIELD] > 0,
          "恢复活跃后通道没恢复出数据（应用刚 registerListener 会拿不到东西）");

    /* ---------- 3) 容量压力：不许崩，且必须计 dropped ---------- */
    long long e2 = 0, d2 = 0, s2 = 0, e3 = 0, d3 = 0, s3 = 0;
    stats(&e2, &d2, &s2);
    collector_t c4;
    memset(&c4, 0, sizeof(c4));
    c4.monotonic = 1;
    c4.last_ts = c3.last_ts;
    drive(&c4, t0, &t, 200000000LL, 40, 2, 2); /* cap=2：几乎每拍都塞不下 */
    stats(&e3, &d3, &s3);
    printf("4) cap=2: 返回条数=%lld dropped %lld -> %lld\n", c4.n_events, d2, d3);
    CHECK(d3 > d2, "容量不足时没有计入 dropped（丢了却不知道）");
    CHECK(d3 - d2 >= c4.n_events, "dropped 增量少于实际发出的条数，记账可疑");

    /* ---------- 3b) 延迟队列溢出：丢最旧、有上限、必须计数 ---------- */
    vw_set_channel_hint(PS_TYPE_ACCELEROMETER, 5000000LL, 0, 1); /* 5ms 的 poll 类型 */
    int pend_before = 0, pend_after = 0;
    long long dfd_before = 0, dfd_after = 0;
    vw_defer_stats(&pend_before, &dfd_before);
    collector_t c5;
    memset(&c5, 0, sizeof(c5));
    c5.monotonic = 1;
    c5.last_ts = c4.last_ts;
    /* 只用运行时消费者推进：poll 类型的事件全进延迟队列 ⇒ 2s×200Hz 必然撑爆 */
    drive(&c5, t0, &t, 10000000LL, 200, 0, OUT_CAP);
    vw_defer_stats(&pend_after, &dfd_after);
    printf("5) 延迟队列: pending %d -> %d（上限 96），溢出丢弃 %lld -> %lld\n",
           pend_before, pend_after, dfd_before, dfd_after);
    CHECK(pend_after <= 96, "延迟队列超过上限（%d）——内存会被撑大", pend_after);
    CHECK(dfd_after > dfd_before, "撑爆延迟队列却没有任何溢出计数（丢了不知道）");

    /* ---------- 4) 噪声档：逐轴 σ 与陀螺零偏 ---------- */
    vw_set_active(1);
    /* 让陀螺通道只走栅格：把它标成活跃、周期 5ms；静止 + 不转弯 ⇒ 信号恒 0 */
    vw_set_channel_hint(PS_TYPE_GYROSCOPE, 5000000LL, 0, 1);
    const float SIGMA = 0.02f, BIAS = 0.013f;
    for (int a = 0; a < 3; a++) vw_set_noise(VW_NOISE_GYRO + a, SIGMA);
    vw_set_noise(VW_NOISE_GYRO_BIAS + 0, BIAS);
    vw_set_noise(VW_NOISE_GYRO_BIAS + 1, -BIAS);
    vw_set_noise(VW_NOISE_GYRO_BIAS + 2, 0.0f);

    double sum = 0, sum2 = 0, sumY = 0;
    long long cnt = 0;
    t += 1000000000LL;
    for (int k = 0; k < 1200; k++) {
        t += 5000000LL;
        vw_update_state(0.0, 0.0, 0, 0, t0 + t);
        int n = vw_generate(out, OUT_CAP, t0 + t, 2);
        for (int i = 0; i < n; i++) {
            if (out[i].type != PS_TYPE_GYROSCOPE) continue;
            double x = (double) out[i].data.f[0];
            sum += x;
            sum2 += x * x;
            sumY += (double) out[i].data.f[1];
            cnt++;
        }
    }
    double mean = cnt > 0 ? sum / (double) cnt : 0.0;
    double var = cnt > 1 ? (sum2 / (double) cnt - mean * mean) : 0.0;
    double sd = var > 0 ? sqrt(var) : 0.0;
    double meanY = cnt > 0 ? sumY / (double) cnt : 0.0;
    printf("6) 噪声档: n=%lld meanX=%.5f(期望 %.5f) sdX=%.5f(期望 %.5f) meanY=%.5f(期望 %.5f)\n",
           cnt, mean, BIAS, sd, SIGMA, meanY, -BIAS);
    CHECK(cnt > 1000, "陀螺样本太少（%lld），统计结论不可信", cnt);
    /*
     * 容差按**实测** σ 与样本量自适应（4 倍均值标准误）：写死常数会随运行抖动假红
     * —— 实测过一次 mean=0.01082（2.7σ 的正常波动）把固定 0.002 的容差顶爆。
     * 4σ/√n 在 n≈1200、σ=0.02 时约 0.0023，而"零偏根本没注入"是 0.013 ⇒ 判得出来。
     */
    double tol_mean = 4.0 * sd / sqrt((double) cnt);
    if (tol_mean < 0.0005) tol_mean = 0.0005;
    printf("   容差(4σ/√n)=%.5f\n", tol_mean);
    CHECK(fabs(mean - BIAS) < tol_mean, "陀螺 x 轴零偏没按配置注入（mean=%.5f 容差 %.5f）", mean,
          tol_mean);
    CHECK(fabs(meanY + BIAS) < tol_mean, "陀螺 y 轴零偏没按配置注入（mean=%.5f 容差 %.5f）", meanY,
          tol_mean);
    CHECK(fabs(sd - SIGMA) < 0.25 * SIGMA, "陀螺 x 轴 σ 与配置不符（sd=%.5f 期望 %.5f）", sd, SIGMA);
    /* σ=0 的槽必须完全不抖（否则"校准到 0"根本没生效） */
    vw_set_noise(VW_NOISE_GYRO + 0, 0.0f);
    double s2sum = 0, s2sum2 = 0;
    long long c2n = 0;
    for (int k = 0; k < 100; k++) {
        t += 5000000LL;
        vw_update_state(0.0, 0.0, 0, 0, t0 + t);
        int n = vw_generate(out, OUT_CAP, t0 + t, 2);
        for (int i = 0; i < n; i++) {
            if (out[i].type != PS_TYPE_GYROSCOPE) continue;
            double x = (double) out[i].data.f[0];
            s2sum += x;
            s2sum2 += x * x;
            c2n++;
        }
    }
    double m2 = c2n > 0 ? s2sum / (double) c2n : 0.0;
    double v2 = c2n > 1 ? (s2sum2 / (double) c2n - m2 * m2) : 0.0;
    printf("7) σ=0 时 sd=%.8f（期望 0），mean=%.5f\n", v2 > 0 ? sqrt(v2) : 0.0, m2);
    CHECK(v2 <= 1e-12, "σ 配成 0 之后该轴仍在抖（sd²=%.3e）", v2);

    printf(failures == 0 ? "\n全部通过 ✓\n" : "\n失败 %d 项 ✗\n", failures);
    return failures == 0 ? 0 : 1;
}
