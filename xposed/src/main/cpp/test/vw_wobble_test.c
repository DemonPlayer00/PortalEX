/*
 * **按组波动的验收判据**（host 可测，两条都必须成立）：
 *
 *  ① 参数为 0 ⇒ 输出与"没有这个功能"**逐位一致**，且**不消耗随机数**（随机流不动 ⇒
 *     后续噪声序列不变）。这是"新参数默认不改变既有行为"的判据 —— 反过来，如果实现里
 *     在 0 值时也调了一次 vw_rng_unit()，全局随机流会被挪位，本用例立刻红。
 *  ② 参数非 0 ⇒ 偏差**可测**且**在界内**：`dev ∈ 约 ±(amp+rnd)`，慢漂有界、均值≈0；
 *     两组互不影响（改一组的参数不改变另一组的偏差）。
 *
 * 还钉住两处容易写坏的口径：
 *  · 角度类按**参考量**加绝对偏差（朝向 0° 与 350° 的抖动幅度一样），不是逐值百分比；
 *  · 步间隔波动只作用在**步频组**上，且 0 值时原样返回。
 */
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "virtual_world.h"
#include "vw_internal.h"

static int failures = 0;
static void expect(int cond, const char *what) {
    if (!cond) { printf("  ✗ %s\n", what); failures++; }
}
static void expect_near(double got, double want, double tol, const char *what) {
    if (!(fabs(got - want) <= tol)) {
        printf("  ✗ %s：期望 %.6f±%.6f 得到 %.6f\n", what, want, tol, got);
        failures++;
    }
}

static double mean_dev(int group, int n, long long t0) {
    double s = 0;
    for (int i = 0; i < n; i++) s += vw_wobble_dev(group, t0 + (long long) i * 10000000LL); /* 10ms 步进 */
    return s / n;
}
static double max_abs_dev(int group, int n, long long t0, double *out) {
    double m = 0;
    for (int i = 0; i < n; i++) {
        double d = vw_wobble_dev(group, t0 + (long long) i * 10000000LL);
        if (fabs(d) > m) m = fabs(d);
    }
    if (out) *out = m;
    return m;
}

int main(void) {
    const long long T0 = 1000000000LL; /* 1s */

    printf("== ① 参数全 0：偏差恒 0，且**不消耗随机数**\n");
    vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.0f, 0.0f);
    vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.0f, 0.0f);
    {
        int all_zero = 1;
        for (int i = 0; i < 200; i++) {
            if (vw_wobble_dev(VW_WOB_GROUP_CADENCE, T0 + i * 1000000LL) != 0.0) all_zero = 0;
            if (vw_wobble_dev(VW_WOB_GROUP_ORIENTATION, T0 + i * 1000000LL) != 0.0) all_zero = 0;
        }
        expect(all_zero, "0 值下偏差必须恒为 0");
    }
    {
        /*
         * 流位置判据（本用例最关键的一条）：把随机流钉死后，
         *   A) 直接抽 32 个随机数 = 基准序列；
         *   B) 先插 500 次「0 值偏差调用」再抽 32 个 ⇒ 必须与基准**逐个相同**（一个都没被偷走）；
         *   C) 先插 500 次「非 0 值偏差调用」再抽 32 个 ⇒ 必须与基准不同（否则说明这条断言是空的）。
         */
        double ref[32], zero_case[32], nonzero_case[32];
        vw_rng_seed_fixed(0x1234567890ABCDEFULL);
        for (int i = 0; i < 32; i++) ref[i] = vw_rng_unit();

        vw_rng_seed_fixed(0x1234567890ABCDEFULL);
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.0f, 0.0f);
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.0f, 0.0f);
        for (int i = 0; i < 500; i++) {
            (void) vw_wobble_dev(VW_WOB_GROUP_CADENCE, T0 + i * 1000000LL);
            (void) vw_wobble_dev(VW_WOB_GROUP_ORIENTATION, T0 + i * 1000000LL);
        }
        for (int i = 0; i < 32; i++) zero_case[i] = vw_rng_unit();

        vw_rng_seed_fixed(0x1234567890ABCDEFULL);
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.15f, 0.15f);
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.15f, 0.15f);
        for (int i = 0; i < 500; i++) {
            (void) vw_wobble_dev(VW_WOB_GROUP_CADENCE, T0 + i * 1000000LL);
            (void) vw_wobble_dev(VW_WOB_GROUP_ORIENTATION, T0 + i * 1000000LL);
        }
        for (int i = 0; i < 32; i++) nonzero_case[i] = vw_rng_unit();

        expect(memcmp(ref, zero_case, sizeof(ref)) == 0,
               "0 值调用不得消耗随机数（流位置必须原封不动）");
        expect(memcmp(ref, nonzero_case, sizeof(ref)) != 0,
               "非 0 值调用必须消耗随机数（否则上面那条断言是空的）");
    }
    {   /* 步间隔：0 值必须原样返回（含边界）。注意上一段"非 0 对照"把 cadence 打开了 ⇒ 先归 0 */
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.0f, 0.0f);
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.0f, 0.0f);
        long long cases[] = {1, 1000, 500000000LL, 123456789LL};
        int ok = 1;
        for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
            if (vw_wobble_step_interval(cases[i], T0) != cases[i]) ok = 0;
        }
        expect(ok, "0 值下步间隔原样返回（逐位一致）");
    }

    printf("== ② 参数 15%/15%：偏差有界、均值≈0、慢漂在动\n");
    vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.15f, 0.15f);
    {
        double mx = 0;
        max_abs_dev(VW_WOB_GROUP_ORIENTATION, 4000, T0, &mx);
        expect(mx <= 0.30 + 1e-6, "偏差不得超过 amp+rnd（0.30）");
        expect(mx > 0.02, "偏差必须真的在动（否则等于没生效）");
        double mu = mean_dev(VW_WOB_GROUP_ORIENTATION, 4000, T0);
        expect(fabs(mu) < 0.05, "偏差均值应≈0（无系统性偏置）");
    }
    {   /* 慢漂：同一时刻连续两次取值应几乎相同（时间相关），跨 1.5s 才明显变化 */
        double a = vw_wobble_dev(VW_WOB_GROUP_ORIENTATION, T0);
        double b = vw_wobble_dev(VW_WOB_GROUP_ORIENTATION, T0 + 1000000LL); /* +1ms */
        expect(fabs(a - b) < 0.30, "1ms 内慢漂不应跳变（时间相关）");
    }

    printf("== ③ 两组互不影响\n");
    {
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.0f, 0.0f);
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.0f, 0.0f);
        long long base = vw_wobble_step_interval(100000000LL, T0);
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.5f, 0.5f); /* 只动角度侧 */
        long long after = vw_wobble_step_interval(100000000LL, T0);
        expect(base == after, "改角度侧参数不得改变步频侧的步间隔");
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.5f, 0.5f);
        long long moved = 0;
        for (int i = 0; i < 50; i++) {
            if (vw_wobble_step_interval(100000000LL, T0 + i * 10000000LL) != 100000000LL) moved = 1;
        }
        expect(moved, "步频侧参数生效后步间隔必须真的变");
    }

    printf("== ④ 钳位与非法值\n");
    {
        float a = -1.0f, r = -1.0f;
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 5.0f, -3.0f); /* 超上限 / 负值 */
        vw_get_group_wobble(VW_WOB_GROUP_CADENCE, &a, &r);
        expect_near(a, 1.0, 1e-6, "amp 超上限钳到 1.0");
        expect_near(r, 0.0, 1e-6, "rnd 负值归 0");
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, NAN, NAN);
        vw_get_group_wobble(VW_WOB_GROUP_CADENCE, &a, &r);
        expect_near(a, 0.0, 1e-6, "amp NaN 归 0");
        expect_near(r, 0.0, 1e-6, "rnd NaN 归 0");
        vw_set_group_wobble(VW_WOB_GROUP_CADENCE, 0.15f, 0.15f);
    }

    printf("== ⑥ 角度类：只留慢漂 + 逐条抖动 ≤1°，且 100ms 内保持同一个值\n");
    {
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.0f, 1.0f);   /* 只开逐条随机，便于看上限 */
        double mx = 0.0;
        int held_ok = 1;
        for (int i = 0; i < 200; i++) {
            long long t = T0 + (long long) i * 30000000LL;           /* 30ms 一抽 */
            double a = vw_wobble_angle_dev(VW_WOB_GROUP_ORIENTATION, t);
            double b = vw_wobble_angle_dev(VW_WOB_GROUP_ORIENTATION, t + 1000000LL); /* 同窗 */
            if (fabs(a) > mx) mx = fabs(a);
            if (fabs(a - b) > 1e-9) held_ok = 0;                     /* 同窗必须同一个值 */
        }
        expect(mx <= 1.0 + 1e-9, "角度逐条抖动不得超过 1°");
        expect(held_ok, "100ms 保持窗内必须返回同一个角度抖动（否则三者会互相打脸）");

        /* 慢漂仍在：amp>0、rnd=0 时角度偏差应随时间游走且幅度可达数十度 */
        vw_set_group_wobble(VW_WOB_GROUP_ORIENTATION, 0.15f, 0.0f);
        double m2 = 0.0;
        for (int i = 0; i < 4000; i++) {
            double a = fabs(vw_wobble_angle_dev(VW_WOB_GROUP_ORIENTATION, T0 + i * 10000000LL));
            if (a > m2) m2 = a;
        }
        expect(m2 > 1.0, "开慢漂时角度偏差必须明显大于 1°（慢漂没被误关）");
        expect(m2 <= 0.15 * 180.0 + 1e-6, "慢漂角度偏差不得超过 amp×180°");
    }

    printf("== ⑤ 参考量：角度类按参考量加，而不是逐值百分比\n");
    {
        expect_near(vw_wobble_ref(1), 9.80665, 1e-6, "加速度参考量 = 1g");
        expect_near(vw_wobble_ref(4), 1.0, 1e-6, "陀螺参考量 = 1 rad/s");
        expect_near(vw_wobble_ref(2), 50.0, 1e-6, "磁场参考量 = 50 µT");
        expect_near(vw_wobble_ref(3), 180.0, 1e-6, "方向角参考量 = 180°");
        expect(vw_wobble_dims(3) == 1, "方向角吃 1 个分量");
        expect(vw_wobble_dims(1) == 3, "加速度吃 3 个分量");
        expect(vw_wobble_dims(11) == 0, "旋转矢量不走向量口径（加在半角上）");
    }

    if (failures == 0) printf("\n按组波动判据全部通过 ✓\n");
    else printf("\n有 %d 条判据失败 ✗\n", failures);
    return failures == 0 ? 0 : 1;
}
