/*
 * virtual_world.c 的 host 回归测试（不需要设备）。
 *
 * 覆盖两处已修的缺陷：
 *  1. 步事件在"不属于本次消费者"时**必须留在队列里**等对方来取
 *     （旧实现在判归属之前就清了 used ⇒ 事件凭空消失、还不计数）；
 *  2. 未激活时的早退**不能**把已经从延迟队列取进 out 的事件算作"发出去了"
 *     （旧实现直接 return 0，事件既不发也不计 dropped）。
 *
 * 编译（在 xposed/src/main/cpp 下）：
 *   cc -D_GNU_SOURCE -I test/stub test/vw_consumer_split_test.c virtual_world.c -lpthread -lm -o /tmp/vwtest
 */
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

#define OUT_CAP 256

static portal_sensor_event_t out[OUT_CAP];

static int count_type(int n, int32_t type) {
    int c = 0;
    for (int i = 0; i < n; i++) if (out[i].type == type) c++;
    return c;
}

static long long now_base(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

int main(void) {
    vw_init();
    /* 播种 handle：步数两条流 + 加速度（后者是 poll 类型） */
    vw_set_handle(PS_TYPE_STEP_COUNTER, 0xb5, 0);
    vw_set_handle(PS_TYPE_STEP_DETECTOR, 0xbf, 0);
    vw_set_handle(PS_TYPE_ACCELEROMETER, 0x0b, 0);

    long long t0 = now_base();

    /* ---------- 1) 消费者分离：poll 侧不得吞掉步事件 ---------- */
    vw_update_state(3.0, 0.0, 1, 0, t0);
    vw_set_active(1);

    long long t = 0;
    int poll_steps = 0, poll_accel = 0;
    /*
     * 步事件由**步数增量**驱动（Java 侧按步频积分后推 steps 总量，native 按增量补事件），
     * 所以这里必须让 steps 真的往上走：每 400ms 一步 = 150 步/分。
     */
    long long steps_total = 0;
    for (; t < 2000000000LL; t += 20000000LL) {
        if (t % 400000000LL == 0) steps_total++;
        vw_update_state(3.0, (double) (t / 1000000), 1, steps_total, t0 + t);
        int n = vw_generate(out, OUT_CAP, t0 + t, 1); /* 1 = 只要 poll 那批 */
        if (n > 0) {
            poll_steps += count_type(n, PS_TYPE_STEP_COUNTER) + count_type(n, PS_TYPE_STEP_DETECTOR);
            poll_accel += count_type(n, PS_TYPE_ACCELEROMETER);
        }
    }
    printf("poll 侧: accel=%d, 步事件=%d（期望 accel>0 且步事件=0，步数归运行时通道）\n",
           poll_accel, poll_steps);
    printf("  诊断: step_events_total=%lld counter_value=%lld step_rate=%d/min\n",
           vw_step_events_total(), vw_step_counter_value(), vw_step_rate_per_min(t0 + t));
    printf("  诊断: polltypes=%d(acc=%d steps=%d) gait=%s\n",
           vw_poll_types_enabled(), vw_is_poll_type(PS_TYPE_ACCELEROMETER),
           vw_is_poll_type(PS_TYPE_STEP_COUNTER), vw_gait_describe());
    CHECK(poll_accel > 0, "poll 侧没拿到加速度事件（%d）", poll_accel);
    CHECK(poll_steps == 0, "步事件被 poll 消费者取走了（%d）——归属判定失效", poll_steps);

    /* 运行时消费者来取：步事件必须在这里（旧实现在 poll 侧就丢了） */
    int rt_steps = 0, rt_counter = 0;
    long long last = -1;
    int monotonic = 1;
    for (int k = 0; k < 8; k++) {
        int n = vw_generate(out, OUT_CAP, t0 + t + k * 5000000LL, 0); /* 0 = 只要运行时那批 */
        for (int i = 0; i < n; i++) {
            if (out[i].type == PS_TYPE_STEP_COUNTER) rt_counter++;
            if (out[i].type == PS_TYPE_STEP_DETECTOR) rt_steps++;
            if (out[i].type == PS_TYPE_STEP_COUNTER) {
                long long v = (long long) out[i].data.u64[0];
                if (last >= 0 && v != last + 1) monotonic = 0;
                last = v;
            }
        }
    }
    printf("运行时侧: counter=%d, detector=%d（期望成对且 >0）\n", rt_counter, rt_steps);
    printf("  诊断: 运行后 step_events_total=%lld\n", vw_step_events_total());
    /* 再要一次"全都要"，用于区分"队列里没有"还是"运行时消费者没取到" */
    int n_all = vw_generate(out, OUT_CAP, t0 + t + 200000000LL, 2);
    printf("  诊断: want_poll=2 再取一次 -> n=%d counter=%d detector=%d\n",
           n_all, count_type(n_all, PS_TYPE_STEP_COUNTER), count_type(n_all, PS_TYPE_STEP_DETECTOR));
    CHECK(rt_counter > 0, "运行时消费者没拿到步数计数器——步事件被静默丢掉了");
    CHECK(rt_counter == rt_steps, "计数器/检测器不成对（%d vs %d）", rt_counter, rt_steps);
    CHECK(monotonic, "计数器值出现跳变（应逐步 +1）");

    /* ---------- 2) 未激活早退的记账：取走的延迟事件要计 dropped ---------- */
    long long emitted0 = 0, dropped0 = 0, suppressed0 = 0;
    vw_stats(&emitted0, &dropped0, &suppressed0);

    /* 前提：先让"属于 poll 消费者"的栅格事件攒进延迟队列 —— 由运行时消费者推进 */
    vw_set_active(1);
    /* 注意：上面那行诊断用 want_poll=2 把内部时间轴推到了 +200ms，
     * 这里必须从**更晚**的时刻继续，否则 now < g_last_tick ⇒ tick 循环整个不跑（踩过）。 */
    t = 2400000000LL;
    for (int k = 0; k < 6; k++) {
        vw_update_state(3.0, (double) k, 1, steps_total, t0 + t + k * 20000000LL);
        vw_generate(out, OUT_CAP, t0 + t + k * 20000000LL, 0);
    }
    int pend = 0;
    long long deferDropped = 0;
    vw_defer_stats(&pend, &deferDropped);
    printf("延迟队列: pending=%d\n", pend);
    CHECK(pend > 0, "延迟队列没攒到事件（%d）——本用例前提不成立", pend);

    /* 关键：关掉总开关后再让 poll 消费者来取。取出来的**不许发出去**，
     * 但也**不能像旧实现那样直接 return 0 了事**（事件既不发也不计 ⇒ 账实不符）。 */
    vw_set_active(0);
    int n2 = vw_generate(out, OUT_CAP, t0 + t + 200000000LL, 1);
    long long emitted1 = 0, dropped1 = 0, suppressed1 = 0;
    vw_stats(&emitted1, &dropped1, &suppressed1);
    printf("未激活早退: 返回=%d, dropped %lld -> %lld\n", n2, dropped0, dropped1);
    CHECK(n2 == 0, "未激活时不该把事件发出去（返回 %d）", n2);
    CHECK(dropped1 > dropped0, "从延迟队列取走的事件没有计入 dropped（旧实现就是这样账实不符）");
    CHECK(dropped1 >= dropped0, "dropped 不该倒退");

    printf(failures == 0 ? "\n全部通过 ✓\n" : "\n失败 %d 项 ✗\n", failures);
    return failures == 0 ? 0 : 1;
}
