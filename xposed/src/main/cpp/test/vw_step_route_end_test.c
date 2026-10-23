/*
 * **步数"跑飞"的宿主回归测试**（host，不需要设备）。
 *
 * ## 现象（用户报的）
 *
 * 走/播完之后，客户端折合出来的步频会飞到 **2000+ 步/分**。步频公式本身是被钳过的
 * （60..220，乘步频倍率后 30..300），所以"一秒钟几十步"不可能来自积分 —— 只能来自
 * **步事件成串爆发到达**：客户端按相邻两条事件的间隔折算，就得到上千步/分。
 *
 * ## 成因（本测试钉住的两条）
 *
 * 1. `vw_update_state` 里重基判定用的是**钳位之后**的增量：
 *    「一次涨 9000 步 / 跨度 11s」先被钳成 64，再算步率 = 5.8 步/秒 ⇒ 看着像人走路 ⇒
 *    队列被填满 64 条**排在过去时刻**的步事件，一次性全发出去。
 * 2. `vw_set_active` 重新激活时把基线设成 `g_steps`，而那个变量**从未被赋值**（恒 0）
 *    ⇒ 新会话第一拍把整个计数器（几千步）当成一次增量，又走上面那条路径。
 *
 * ## 判据
 *
 * A. 正常走动：步事件间隔落在真实步频带内（本测试只要求 ≥100ms、无同刻对）。
 * B. 会话停止 → 重新开始（计数器不变）：**一个步事件都不许发**。
 * C. 会话停止 → 重新开始（计数器被重锚到真实计数器，+3000 步）：**仍然一个都不许发**
 *    （只搬基线），因为这几千步不是"我们这段时间走出来的"。
 * D. 播放结束 → 世界停下 → 再开始走：恢复段不发爆发（窗口内最大事件数 ≤ 期望上限）。
 */
#include <math.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

#include "virtual_world.h"

#define STEP_COUNTER PS_TYPE_STEP_COUNTER

static int g_fail = 0;

static void expect(int ok, const char *fmt, ...) {
    char msg[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    printf("  %s %s\n", ok ? "✓" : "✗", msg);
    if (!ok) g_fail = 1;
}

#define OUT_CAP 2048
static portal_sensor_event_t out[OUT_CAP];

/* ---- 与 Java 侧同构的时间轴 ---- */
static long long g_now = 1000000000LL;
static long long g_pump_next = 0;
static long long g_steps = 0;      /* Java 侧推送的计数器 */
static double g_frac = 0.0;
static int g_idle_streak = 0;

/* ---- 窗口观测 ---- */
static int g_events = 0;
static int g_same_ts = 0;
static long long g_min_gap = -1, g_last_ts = 0;
static int g_max_per_sec = 0, g_cur_sec = 0, g_cur_sec_events = 0;
/** 单次 vw_generate 里最多带回几条步事件 —— **"同时到达"的直接读数**（爆发 = 几十条一起到） */
static int g_max_per_call = 0;

static void window_reset(void) {
    g_events = 0; g_same_ts = 0; g_min_gap = -1; g_last_ts = 0;
    g_max_per_sec = 0; g_cur_sec = 0; g_cur_sec_events = 0; g_max_per_call = 0;
}

static void observe(int n) {
    int in_call = 0;
    for (int i = 0; i < n; i++) {
        if (out[i].type != STEP_COUNTER) continue;
        g_events++;
        in_call++;
        long long ts = out[i].timestamp;
        int sec = (int) (ts / 1000000000LL);
        if (sec != g_cur_sec) { g_cur_sec = sec; g_cur_sec_events = 0; }
        if (++g_cur_sec_events > g_max_per_sec) g_max_per_sec = g_cur_sec_events;
        if (g_last_ts) {
            long long gap = ts - g_last_ts;
            if (gap == 0) g_same_ts++;
            if (g_min_gap < 0 || gap < g_min_gap) g_min_gap = gap;
        }
        g_last_ts = ts;
    }
    if (in_call > g_max_per_call) g_max_per_call = in_call;
}

/** 泵：与 Java 泵同口径（每 5ms 一帧，只取运行时那批） */
static void pump(void) {
    while (g_pump_next <= g_now) {
        observe(vw_generate(out, OUT_CAP, g_pump_next, 0));
        g_pump_next += 5000000LL;
    }
}

/** 一拍：世界在动 ⇒ 50ms、按 174 步/分积步；静止 ⇒ 20 拍后降到 1Hz */
static void tick(long long dt_ms, int moving) {
    g_now += dt_ms * 1000000LL;
    pump();
    if (moving) {
        g_idle_streak = 0;
        g_frac += 174.0 / 60.0 * (dt_ms / 1000.0);
        long long whole = (long long) g_frac;
        if (whole > 0) { g_frac -= (double) whole; g_steps += whole; }
    } else {
        g_idle_streak++;
    }
    vw_update_state(moving ? 3.05 : 0.0, 37.0, moving, g_steps, g_now);
}

static void walk(int seconds, int moving) {
    if (moving) {
        for (int i = 0; i < seconds * 20; i++) tick(50, 1);
    } else {
        for (int i = 0; i < 20; i++) tick(50, 0);
        for (int i = 0; i < seconds; i++) tick(1000, 0);
    }
}

int main(void) {
    vw_init();
    vw_set_class_enable(1, 1);
    vw_set_handle(STEP_COUNTER, 0xbf, 0);
    vw_set_handle(PS_TYPE_STEP_DETECTOR, 0xb5, 0);
    vw_set_channel_hint(STEP_COUNTER, 66000000LL, 0, 1);
    vw_set_channel_hint(PS_TYPE_STEP_DETECTOR, 66000000LL, 0, 1);

    /* 会话开始：首拍建基线，然后接在"已经走了 9000 步"的真实计数器后面 */
    vw_set_active(1);
    vw_update_state(0.0, 37.0, 0, 0, g_now);
    g_steps = 9000;
    vw_update_state(0.0, 37.0, 0, g_steps, g_now);
    g_now += 1000000000LL;

    /* ---- A. 正常走动 30s ---- */
    printf("== A. 正常走动 30s（174 步/分）\n");
    window_reset();
    walk(30, 1);
    printf("  事件 %d 条；最小间隔 %.0f ms；同刻对 %d；单秒最多 %d 条；单帧最多 %d 条\n",
           g_events, g_min_gap / 1e6, g_same_ts, g_max_per_sec, g_max_per_call);
    expect(g_events >= 60 && g_events <= 95, "事件数与步频一致（%d 条，约 87）", g_events);
    expect(g_same_ts == 0, "没有时间戳相同的相邻对");
    expect(g_min_gap >= 100000000LL, "步事件最小间隔 ≥100ms（真机步间隔 ~300ms）");
    expect(g_max_per_sec <= 6, "没有任何一秒发出 >6 条（≤400 步/分）");
    expect(g_max_per_call <= 3, "单帧最多带回 3 条（不会「几十条同时到达」）");

    /* ---- B. 播完 → 会话停止 → 重新开始（计数器不变） ---- */
    printf("== B. 播完静止 → 停会话 → 10s 后重新开会话（计数器不变）\n");
    window_reset();
    walk(60, 0);
    printf("  静止 60s 期间发出 %d 条\n", g_events);
    expect(g_events == 0, "静止不发步事件");
    vw_set_active(0);
    g_now += 10000000000LL;
    vw_set_active(1);
    window_reset();
    tick(50, 0);
    walk(5, 0);
    printf("  重启后 5s 发出 %d 条（单秒最多 %d，单帧最多 %d）\n",
           g_events, g_max_per_sec, g_max_per_call);
    expect(g_events == 0, "重启后不许补发空档步事件（基线未变 ⇒ 0 条）");
    expect(g_max_per_call <= 3, "重启后没有「成串到达」的帧");

    /* ---- C. 会话停止 → 重新开始 + 计数器被重锚（+3000 步） ---- */
    printf("== C. 重新开会话时计数器被重锚到真实计数器（+3000 步）\n");
    vw_set_active(0);
    g_now += 5000000000LL;
    vw_set_active(1);
    g_steps += 3000;
    window_reset();
    tick(50, 0);
    walk(5, 0);
    printf("  重锚后 5s 发出 %d 条（单秒最多 %d，单帧最多 %d）；原生「重基跳过」累计 %lld 步\n",
           g_events, g_max_per_sec, g_max_per_call, vw_step_rebase_skipped());
    expect(g_events == 0, "计数器重锚只搬基线，不发事件（不发爆发）");
    expect(vw_step_rebase_skipped() >= 3000, "重锚步数记进了「重基跳过」");

    /* ---- D. 播完 → 世界停下 → 再开始走（重播/摇杆） ---- */
    printf("== D. 停 5s 后再开始走（重播路线/摇杆接管）\n");
    walk(5, 0);
    window_reset();
    walk(5, 1);
    printf("  恢复段 5s 发出 %d 条；最小间隔 %.0f ms；单秒最多 %d 条；单帧最多 %d 条\n",
           g_events, g_min_gap / 1e6, g_max_per_sec, g_max_per_call);
    expect(g_min_gap >= 100000000LL, "恢复段步事件间隔仍 ≥100ms（不许挤成同刻）");
    expect(g_max_per_sec <= 6, "恢复段没有任何一秒 >6 条");
    expect(g_max_per_call <= 3, "恢复段没有成串到达的帧");

    printf("\n%s\n", g_fail ? "步数爆发回归测试：**有失败** ✗" : "步数爆发回归测试通过 ✓");
    printf("诊断：多步推送=%lld 短间隔=%lld 重基跳过=%lld 事件总数=%lld\n",
           vw_step_multi_push_count(), vw_step_short_gap_count(),
           vw_step_rebase_skipped(), vw_step_events_total());
    return g_fail;
}
