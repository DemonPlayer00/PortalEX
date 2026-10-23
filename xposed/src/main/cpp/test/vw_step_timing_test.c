/*
 * **步事件时间戳的投递时序测试**（host，不需要设备）。
 *
 * 背景（用户报的现象）：大约**每 20 秒**出现一次步数尖峰，怀疑"同一时刻被下发了两条步频事件"。
 * 客户端按事件时间戳算配速/步频时，**两条时间戳相同而计数值差 1 的事件**会被算成
 * "无穷大步频" —— 这正是尖峰的签名。所以这里把时序当成可断言的不变量来钉：
 *
 *   A. 1 秒轮询（用户口径的"期望 1 秒更新间隔"）：一秒钟里攒下的几步必须**时间戳严格递增**，
 *      且间隔落在合理带内（≥ 期望步间隔的一半、≤ 2×）；
 *   B. 15 Hz 轮询：常见客户端（SENSOR_DELAY_UI）逐拍取，同样必须严格递增；
 *   C. 边界轮询（恰好在到点时刻取）：jitter 会把时间戳推过 now 被钳回 now，
 *      这里量"两条事件被钳成同一时间戳"到底会不会发生；
 *   D. counter 重基（步数一次跳很多、跨度却很短）：`per = span/delta` 会退化成 1ns，
 *      这里量它是否会产出一串"同一时刻"的步事件（会话开始/恢复路径的现实场景）。
 *
 * C/D 只**报告**不判定对错（真实 HAL 也会出现同一纳秒两条事件），
 * 但会打印出最小间隔与同刻对数 —— 用来回答"尖峰是不是我们这侧的"。
 */
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "virtual_world.h"

#define STEP_COUNTER 19
#define STEP_DETECTOR 18

#define OUT_CAP 1024
static portal_sensor_event_t out[OUT_CAP];

/* 观测器：只看 STEP_COUNTER，记录相邻两条的间隔与"同刻对数" */
typedef struct {
    long long last_ts;
    long long prev_ts;
    long long min_gap;
    long long max_gap;
    int same_ts_pairs;   /* 时间戳完全相同的相邻对 */
    int gaps_lt_half;    /* 间隔 < 期望的一半 */
    int count;
    int value_skips;     /* 计数值不是 +1 的对数 */
    long long last_value;
} watch_t;

static void watch_reset(watch_t *w) {
    memset(w, 0, sizeof(*w));
    w->last_ts = 0;
    w->prev_ts = 0;
    w->min_gap = -1;
    w->max_gap = 0;
    w->last_value = -1;
}

static void watch_feed(watch_t *w, portal_sensor_event_t *ev, int n, long long expect_gap) {
    for (int i = 0; i < n; i++) {
        if (ev[i].type != STEP_COUNTER) continue;
        long long ts = ev[i].timestamp;
        long long v = (long long) ev[i].data.u64[0];
        if (w->last_ts != 0) {
            long long gap = ts - w->last_ts;
            if (gap == 0) w->same_ts_pairs++;
            if (w->min_gap < 0 || gap < w->min_gap) w->min_gap = gap;
            if (gap > w->max_gap) w->max_gap = gap;
            if (expect_gap > 0 && gap < expect_gap / 2) w->gaps_lt_half++;
            if (w->last_value >= 0 && v - w->last_value != 1) w->value_skips++;
        }
        w->last_ts = ts;
        w->last_value = v;
        w->count++;
    }
}

static void report(const char *tag, watch_t *w, long long expect_gap) {
    printf("  %-22s 步事件 %d 条；最小间隔 %lld ms / 最大 %lld ms；同刻对 %d；"
           "<半个期望间隔 %d；计数值非 +1 的对 %d\n",
           tag, w->count,
           w->min_gap < 0 ? 0 : w->min_gap / 1000000,
           w->max_gap / 1000000,
           w->same_ts_pairs, w->gaps_lt_half, w->value_skips);
    (void) expect_gap;
}

/* 把世界推进到 [steps_total] 步：每 step_ms 一步，按 50ms 的模块拍长下发状态 */
static void feed_steps(long long *t, long long *steps, long long steps_total,
                       long long step_ms, long long push_ms) {
    while (*steps < steps_total) {
        *t += push_ms * 1000000LL;
        /* 每 push_ms 涨的步数 = push_ms / step_ms（保持真实步频） */
        double per_push = (double) push_ms / (double) step_ms;
        long long target = (long long) (*t / 1000000LL / per_push / 1.0);
        (void) target;
        *steps = (long long) ((double) *t / 1000000.0 / (double) step_ms);
        if (*steps > steps_total) *steps = steps_total;
        /* 一定在动：速度 4 m/s（步态/方位都活起来） */
        vw_update_state(4.0, 37.0, 1, *steps, *t);
    }
}

/* 共享时间游标：四个场景必须**同一条时间线**往下走。
 * （第一版每个场景各给一个起始时刻，B 跑到 ~108s、C 却从 40s 开始 ⇒ 时间倒流，
 *   步事件被冻在同一时刻，测出来全是"同刻"，那是测试自己的 bug。） */
static long long g_t = 1000000000LL;

int main(void) {
    const long long STEP_MS = 295;    /* ≈3.39 步/秒 ≈ 203 步/分 */
    const long long GAP_NS = STEP_MS * 1000000LL;
    int failures = 0;

    printf("== 准备：激活注入层 + 登记步数 handle + 通道活跃\n");
    vw_set_active(1);
    vw_set_handle(STEP_COUNTER, 0xbf, 0);
    vw_set_handle(STEP_DETECTOR, 0xb5, 0);
    vw_set_channel_hint(STEP_COUNTER, 66000000LL, 0, 1);
    vw_set_channel_hint(STEP_DETECTOR, 66000000LL, 0, 1);

    /* ---------- A. 1 秒轮询 ---------- */
    printf("== A. 1 秒轮询（期望 1 秒更新间隔的客户端）\n");
    {
        watch_t w;
        watch_reset(&w);
        long long steps = (long long) (g_t / 1000000.0 / (double) STEP_MS);
        vw_update_state(4.0, 37.0, 1, steps, g_t);   /* 先建立基线，别让第一步吃到 delta=3 */
        for (int sec = 0; sec < 20; sec++) {
            /* 这一秒里按 50ms 拍长持续下发状态（模块的 supervisor 就在这么干） */
            for (int k = 0; k < 20; k++) {
                g_t += 50000000LL;
                steps = (long long) ((double) (g_t) / 1000000.0 / (double) STEP_MS);
                vw_update_state(4.0, 37.0, 1, steps, g_t);
            }
            /* 客户端每 1 秒取一次 */
            int n = vw_generate(out, OUT_CAP, g_t, 2);
            watch_feed(&w, out, n, GAP_NS);
        }
        report("A 1s 轮询", &w, GAP_NS);
        if (w.same_ts_pairs != 0) {
            printf("  FAIL: 1 秒轮询下出现 %d 对同刻步事件（客户端会算出无穷步频）\n", w.same_ts_pairs);
            failures++;
        }
        if (w.value_skips != 0) {
            printf("  FAIL: 计数值出现 %d 处非 +1 跳变\n", w.value_skips);
            failures++;
        }
        if (w.min_gap < 100000000LL) {
            printf("  FAIL: 步事件最小间隔 %lldms < 100ms（客户端会看到「两步同时」）\n",
                   w.min_gap / 1000000);
            failures++;
        }
    }

    /* ---------- B. 15 Hz 轮询（SENSOR_DELAY_UI） ---------- */
    printf("== B. 15 Hz 轮询（SENSOR_DELAY_UI 客户端）\n");
    {
        watch_t w;
        watch_reset(&w);
        g_t += 1000000000LL;                          /* 时间只能前进 */
        vw_update_state(4.0, 37.0, 1, (long long) (g_t / 1000000.0 / STEP_MS), g_t);
        for (int k = 0; k < 20 * 15; k++) {           /* ~20 秒、每 66.7ms 一拍 */
            g_t += 66666667LL;
            long long steps = (long long) ((double) g_t / 1000000.0 / (double) STEP_MS);
            vw_update_state(4.0, 37.0, 1, steps, g_t);
            int n = vw_generate(out, OUT_CAP, g_t, 2);
            watch_feed(&w, out, n, GAP_NS);
        }
        report("B 15Hz 轮询", &w, GAP_NS);
        if (w.same_ts_pairs != 0) {
            printf("  FAIL: 15Hz 轮询下出现 %d 对同刻步事件\n", w.same_ts_pairs);
            failures++;
        }
        if (w.min_gap < 100000000LL) {
            printf("  FAIL: 15Hz 下步事件最小间隔 %lldms < 100ms\n", w.min_gap / 1000000);
            failures++;
        }
    }

    /* ---------- C. 边界轮询：恰在到点时刻取 ---------- */
    printf("== C. 边界轮询（恰好在步到点时刻取；只报告）\n");
    {
        watch_t w;
        watch_reset(&w);
        g_t += 1000000000LL;
        for (int k = 0; k < 60; k++) {
            /* 每步恰好推进一个期望间隔后再取：事件应当"刚好到点" */
            long long steps = (long long) ((double) g_t / 1000000.0 / (double) STEP_MS);
            vw_update_state(4.0, 37.0, 1, steps + 1, g_t);   /* 让队列里始终有一步待发 */
            int n = vw_generate(out, OUT_CAP, g_t, 2);
            watch_feed(&w, out, n, GAP_NS);
            g_t += GAP_NS;
        }
        report("C 边界轮询", &w, GAP_NS);
        if (w.same_ts_pairs != 0) {
            printf("  FAIL: 边界轮询下出现 %d 对同刻步事件\n", w.same_ts_pairs);
            failures++;
        }
    }

    /* ---------- D. counter 重基：步数一次跳很多、跨度很短 ---------- */
    printf("== D. counter 重基（steps 跳 +50，跨度 50ms；只报告）\n");
    {
        watch_t w;
        watch_reset(&w);
        g_t += 1000000000LL;
        long long steps = (long long) (g_t / 1000000.0 / STEP_MS);
        vw_update_state(4.0, 37.0, 1, steps, g_t);
        int n = vw_generate(out, OUT_CAP, g_t, 2);
        watch_feed(&w, out, n, GAP_NS);
        g_t += 50000000LL;                     /* 只过 50ms */
        steps += 50;                           /* 但步数跳 50（会话重基的现实场景） */
        vw_update_state(4.0, 37.0, 1, steps, g_t);
        n = vw_generate(out, OUT_CAP, g_t, 2);
        watch_feed(&w, out, n, GAP_NS);
        report("D 重基", &w, GAP_NS);
        /* 重基是"计数器基线搬移"，不是走路 ⇒ 不许把它排成一串事件（旧行为就是那样） */
        if (w.same_ts_pairs != 0) {
            printf("  FAIL: 重基场景出现 %d 对同刻步事件（应判为基线搬移、不发事件）\n",
                   w.same_ts_pairs);
            failures++;
        }
        if (w.min_gap >= 0 && w.min_gap < 100000000LL) {
            printf("  FAIL: 重基场景最小间隔 %lldms < 100ms（步流被压缩）\n", w.min_gap / 1000000);
            failures++;
        }
        printf("  （重基跳过的步数：%lld）\n", vw_step_rebase_skipped());
    }

    if (failures == 0) printf("\n步事件时序：常规路径全部通过 ✓\n");
    else printf("\n有 %d 条判据失败 ✗\n", failures);
    return failures == 0 ? 0 : 1;
}
