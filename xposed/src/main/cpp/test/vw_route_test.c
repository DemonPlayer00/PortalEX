/*
 * vw_route_expand / vw_route_length 的 host 测试（不需要设备/NDK）。
 *
 * 判据全部对着**测试内部另写一份的朴素参考实现**（线性扫描，不做游标优化）——
 * 拿被测实现跟它自己比是没有意义的。
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "virtual_world.h"

#define EARTH_R 6371000.0
#define LOOKAHEAD 2.0

static int failures = 0;

static void check(int ok, const char *what) {
    if (!ok) {
        printf("  ✗ %s\n", what);
        failures++;
    }
}

static double to_rad(double d) { return d * (M_PI / 180.0); }

static double hav(double la1, double lo1, double la2, double lo2) {
    double p1 = to_rad(la1), p2 = to_rad(la2);
    double dp = to_rad(la2 - la1), dl = to_rad(lo2 - lo1);
    double sp = sin(dp / 2), sl = sin(dl / 2);
    double a = sp * sp + cos(p1) * cos(p2) * sl * sl;
    if (a > 1) a = 1;
    return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a));
}

static double bearing(double la1, double lo1, double la2, double lo2) {
    double p1 = to_rad(la1), p2 = to_rad(la2), dl = to_rad(lo2 - lo1);
    double y = sin(dl) * cos(p2);
    double x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl);
    double b = atan2(y, x) * 180.0 / M_PI;
    return b < 0 ? b + 360.0 : b;
}

/*
 * 局部平面弦长（米）。**0.25m 这个尺度上绝不能用 haversine**：
 * 那里 a = sin²(Δ/2) + … ≈ 1.5e-16，两个近乎相等的量相减发生灾难性抵消，
 * 单次 haversine 的相对误差能到百分级 —— 我第一版就是用它量间距，
 * 量出"弦长 > 弧长"这种违反几何的结论。
 *
 * 每度米数必须与实现同源：`R·π/180 = 6371000·π/180 = 111194.93`。
 * 用常见的粗值 111320 会带来 +0.11% 的系统偏差 —— 我第二版就栽在这，
 * 0.25m 的间距被量成 0.250281m，"弦长超过弧长"又假报了一轮（4477 项）。
 */
static double chord_m(double la1, double lo1, double la2, double lo2) {
    const double m_per_deg = 6371000.0 * M_PI / 180.0;   /* 111194.93，与实现同源 */
    double dy = (la2 - la1) * m_per_deg;
    double dx = (lo2 - lo1) * m_per_deg * cos(to_rad((la1 + la2) / 2));
    return sqrt(dx * dx + dy * dy);
}

/* ---- 朴素参考实现：全程线性扫描 ---- */
static double ref_cum(const double *lat, const double *lon, int n, int i) {
    double s = 0;
    for (int k = 1; k <= i; k++) s += hav(lat[k - 1], lon[k - 1], lat[k], lon[k]);
    return s;
}

static void ref_point(const double *lat, const double *lon, int n, double d,
                      double *o_lat, double *o_lon) {
    if (d <= 0) { *o_lat = lat[0]; *o_lon = lon[0]; return; }
    for (int i = 1; i < n; i++) {
        double c0 = ref_cum(lat, lon, n, i - 1), c1 = ref_cum(lat, lon, n, i);
        if (d <= c1 || i == n - 1) {
            double seg = c1 - c0;
            double f = seg > 1e-9 ? (d - c0) / seg : 0;
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            *o_lat = lat[i - 1] + (lat[i] - lat[i - 1]) * f;
            *o_lon = lon[i - 1] + (lon[i] - lon[i - 1]) * f;
            return;
        }
    }
}

static double ref_bearing(const double *lat, const double *lon, int n, double d, double total) {
    double a_lat, a_lon, b_lat, b_lon;
    ref_point(lat, lon, n, d, &a_lat, &a_lon);
    double da = d + LOOKAHEAD > total ? total : d + LOOKAHEAD;
    ref_point(lat, lon, n, da, &b_lat, &b_lon);
    if (hav(a_lat, a_lon, b_lat, b_lon) < 1e-6)
        return bearing(lat[n - 2], lon[n - 2], lat[n - 1], lon[n - 1]);
    return bearing(a_lat, a_lon, b_lat, b_lon);
}

/* 一条带转弯的路线（正北 → 东北 → 正东），点间距 ~2m，模拟真机展开后的路线 */
static void make_route(double **out_lat, double **out_lon, int *out_n) {
    const int n = 600;
    double *lat = malloc(sizeof(double) * n);
    double *lon = malloc(sizeof(double) * n);
    for (int i = 0; i < n; i++) {
        double t = (double) i;
        if (i < 200) { lat[i] = 30.0 + t * 0.0000180; lon[i] = 120.0; }
        else if (i < 400) { lat[i] = 30.0036 + (t - 200) * 0.0000127; lon[i] = 120.0 + (t - 200) * 0.0000127; }
        else { lat[i] = 30.0061; lon[i] = 120.00254 + (t - 400) * 0.0000180; }
    }
    *out_lat = lat; *out_lon = lon; *out_n = n;
}

static void test_expand_matches_reference(void) {
    printf("== 展开结果与朴素参考一致\n");
    double *lat, *lon; int n;
    make_route(&lat, &lon, &n);

    const double step = 0.25;
    double total = vw_route_length(lat, lon, n);
    double ref_total = ref_cum(lat, lon, n, n - 1);
    check(fabs(total - ref_total) < 1e-6, "vw_route_length 与参考总长一致");
    printf("  路线 %d 点 / %.1f m，step=%.2fm ⇒ 预计 %d 点\n",
           n, total, step, (int) (total / step) + 2);

    const int cap = (int) (total / step) + 4;
    double *out = malloc(sizeof(double) * 3 * cap);
    double got_total = 0;
    int cnt = vw_route_expand(lat, lon, n, step, out, cap, &got_total);
    check(cnt >= 2, "发射点数 >= 2");
    check(fabs(got_total - total) < 1e-6, "回写总长一致");

    /* 首点 = 路线起点；末点 = 路线终点（必须精确落在终点上） */
    check(fabs(out[0] - lat[0]) < 1e-12 && fabs(out[1] - lon[0]) < 1e-12, "首点=起点");
    check(fabs(out[(cnt - 1) * 3] - lat[n - 1]) < 1e-12 &&
          fabs(out[(cnt - 1) * 3 + 1] - lon[n - 1]) < 1e-12, "末点=终点");

    /* 每个采样点：坐标与朝向都必须与参考一致，且间距 = step（末段除外） */
    double max_err = 0, max_brg_err = 0, max_spacing_err = 0;
    for (int i = 0; i < cnt; i++) {
        double d = (double) i * step;
        if (d > total) d = total;
        double e_lat, e_lon;
        ref_point(lat, lon, n, d, &e_lat, &e_lon);
        double dl = fabs(out[i * 3] - e_lat);
        double dg = fabs(out[i * 3 + 1] - e_lon);
        if (dl > max_err) max_err = dl;
        if (dg > max_err) max_err = dg;
        double rb = ref_bearing(lat, lon, n, d, total);
        double db = fabs(out[i * 3 + 2] - rb);
        if (db > 180) db = 360 - db;              /* 方位角是环形量 */
        if (db > max_brg_err) max_brg_err = db;
        if (i > 0) {
            double sp = chord_m(out[(i - 1) * 3], out[(i - 1) * 3 + 1], out[i * 3], out[i * 3 + 1]);
            /* 注意：这里量的是**弦长**，而发射是按**弧长**等间距的。
             * 转角处弦长必然短于弧长（纯几何），所以判据只能是"弦长 ≤ 弧长 step"；
             * "弧长严格等于 step" 由上面那条"坐标与参考在 d=i·step 处完全一致"保证
             * （实测偏差 0.000e+00）。我第一版把弦长当弧长断言，是自己写错了。 */
            double err = step - sp;                /* ≥0，转角处为正 */
            if (i < cnt - 1 && err > max_spacing_err) max_spacing_err = err;
            /* 容差必须是**相对**的：弦长由两个几乎相等的坐标相减得出
             * （如 30.0061235 vs 30.0061252），灾难性抵消后只剩 ~1e-6 的相对精度。
             * 我第三版用绝对容差 1e-9（相对 4e-9），比可达精度还紧 ⇒ 又假报 977 项。 */
            check(sp <= step * (1.0 + 1e-5), "弦长不超过弧长 step");
            check(sp > 0, "采样点严格前进（无重复/倒退）");
        }
    }
    printf("  坐标最大偏差 %.3e 度，朝向最大偏差 %.3e 度，弦长相对弧长的最大缩短 %.3e m（转角几何）\n",
           max_err, max_brg_err, max_spacing_err);
    check(max_err < 1e-9, "坐标与参考一致（<1e-9 度 ≈ 0.1µm）");
    check(max_brg_err < 1e-6, "朝向与参考一致");
    check(max_spacing_err < 0.09, "弦长缩短量在转角几何的合理范围内（<9cm）");

    free(out); free(lat); free(lon);
}

static void test_bearing_rule(void) {
    printf("== 朝向规则与 Kotlin 侧一致（2m 前视 / 端点退化）\n");
    /* 纯正北直线：朝向应恒为 ~0/360 */
    double lat[5] = {30.0, 30.0000180, 30.0000360, 30.0000540, 30.0000720};
    double lon[5] = {120.0, 120.0, 120.0, 120.0, 120.0};
    double out[3 * 16];
    int cnt = vw_route_expand(lat, lon, 5, 0.5, out, 16, NULL);
    check(cnt > 2, "正北直线有采样点");
    for (int i = 0; i < cnt; i++) {
        double b = out[i * 3 + 2];
        check(b < 1e-6 || b > 359.999999, "正北直线朝向为 0/360");
    }
    /* 终点那一拍：前视点与当前点重合 ⇒ 退化为最后一段方位角，仍须是 0/360 */
    double last_b = out[(cnt - 1) * 3 + 2];
    check(last_b < 1e-6 || last_b > 359.999999, "终点退化朝向 = 最后一段方位角");

    /* 同纬度两点之间是**大圆**，初始方位角略小于 90°（恒向线才恰好 90°）——
     * 所以这里只能断言"很接近 90"，精确一致性由与参考实现的对撞保证。 */
    double lat2[3] = {30.0, 30.0, 30.0};
    double lon2[3] = {120.0, 120.0000180, 120.0000360};
    int c2 = vw_route_expand(lat2, lon2, 3, 0.5, out, 16, NULL);
    for (int i = 0; i < c2; i++) {
        check(fabs(out[i * 3 + 2] - 90.0) < 0.05, "正东直线朝向≈90（大圆，非恒向线）");
    }
}

static void test_edge_cases(void) {
    printf("== 边界：非法入参 / 退化路线 / 容量不足\n");
    double out[3 * 8];
    double lat[3] = {30.0, 30.0001, 30.0002};
    double lon[3] = {120.0, 120.0, 120.0};
    check(vw_route_expand(NULL, lon, 3, 1.0, out, 8, NULL) == 0, "lat=NULL ⇒ 0");
    check(vw_route_expand(lat, lon, 1, 1.0, out, 8, NULL) == 0, "n<2 ⇒ 0");
    check(vw_route_expand(lat, lon, 3, 0.0, out, 8, NULL) == 0, "step=0 ⇒ 0");
    check(vw_route_expand(lat, lon, 3, -1.0, out, 8, NULL) == 0, "step<0 ⇒ 0");
    check(vw_route_expand(lat, lon, 3, NAN, out, 8, NULL) == 0, "step=NaN ⇒ 0");
    check(vw_route_expand(lat, lon, 3, 1.0, out, 1, NULL) == 0, "max_points<2 ⇒ 0");

    /* 容量不足：绝不能越界写（这里靠 canary 检测） */
    double buf[3 * 4 + 4];
    for (int i = 0; i < 3 * 4 + 4; i++) buf[i] = -999.0;
    int cnt = vw_route_expand(lat, lon, 3, 1.0, buf, 4, NULL);
    check(cnt <= 4, "发射点数不超过 max_points");
    for (int i = 3 * 4; i < 3 * 4 + 4; i++) check(buf[i] == -999.0, "未越界写 canary");

    /* 退化路线：所有点重合 ⇒ 长度为 0，展开只应给出起点（+终点），不得死循环 */
    double dlat[4] = {30.0, 30.0, 30.0, 30.0};
    double dlon[4] = {120.0, 120.0, 120.0, 120.0};
    check(vw_route_length(dlat, dlon, 4) < 1e-9, "重合路线长度为 0");
    double dout[3 * 8];
    int dc = vw_route_expand(dlat, dlon, 4, 1.0, dout, 8, NULL);
    check(dc >= 1 && dc <= 2, "重合路线只发射起点/终点");
}

int main(void) {
    test_expand_matches_reference();
    test_bearing_rule();
    test_edge_cases();
    if (failures == 0) {
        printf("vw_route: 全部通过 ✓\n");
        return 0;
    }
    printf("vw_route: %d 项失败 ✗\n", failures);
    return 1;
}
