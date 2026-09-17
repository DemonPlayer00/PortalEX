/*
 * 路线展开器 —— 把折线路线**一次**展开成"按弧长等间距采样的坐标/朝向表"。
 *
 * ## 为什么要有它
 *
 * 迁移后的推进是"每拍现场算"：`二分定位区间 → 区间内按弧长线性插值 → 再算 2m 前视方位角`
 * （Kotlin 侧 MotionEngine.bearingAt）。一拍要做 3 次二分 + 约 20 次超越函数调用。
 * 总量虽然小（见 RouteBeatCostTest），但这是**每拍都在重复算同一件与时间无关的事**：
 * 折线上"弧长 d 处的坐标与朝向"只取决于路线本身，**与速度、体力倍率、dt 全都无关**。
 *
 * 所以正确的形状是：**加载路线时展开一次，之后每拍 O(1) 查表**。
 * 与时间无关这一点很关键 —— 它保证了这套预计算**不会**与体力倍率/迟到补偿打架：
 * 表是按**弧长**索引的，任何一拍只要知道"走了多少米"，就能 O(1) 找到位置，
 * 而"这一拍走多少米"仍然由 `速度×倍率×dt` 在 Kotlin 侧逐拍决定。
 *
 * ## 为什么放 native
 *
 *  · 展开是一次性的顺序扫描，天然缓存友好；编译器可以自动向量化
 *    （这里的循环是纯标量浮点，-O2 已经能向量化其中的线性插值部分）；
 *  · **热路径零 GC**：Kotlin 侧只在堆外/原始数组上做 2 次读 + 1 次乘加，
 *    不再产生 `Pair<Double,Double>` 装箱（Java 的盲区：小对象标量化只在 JIT 愿意时发生，
 *    而 ART 上这条链是 20Hz 常驻的）；
 *  · 朝向的前视点、段长、累计弧长全在 native 里按**单调游标**推进，没有二分。
 *
 * ## 语义约束（改这里必须同步改 Kotlin 侧）
 *
 *  · 采样点 = 弧长 0, step, 2·step, … **以及终点**（保证终点一定被发射）；
 *  · 朝向规则与 `MotionEngine.bearingAt` **逐字一致**：取 `d` 与 `d + 2m`（封顶在终点）两点，
 *    若两点重合（已在终点）则退化为**最后一段**的方位角；
 *  · 输出交错存放 `out[3i]=lat, out[3i+1]=lon, out[3i+2]=bearing`，便于热路径顺序访问。
 */
#include <math.h>
#include <stdlib.h>

#include "virtual_world.h"

#define VW_ROUTE_EARTH_R 6371000.0
/* 与 Kotlin 侧 MotionEngine.TANGENT_LOOKAHEAD_M 必须一致 */
#define VW_ROUTE_LOOKAHEAD_M 2.0

static double vw_to_rad(double deg) { return deg * (M_PI / 180.0); }
static double vw_to_deg(double rad) { return rad * (180.0 / M_PI); }

static double vw_hav(double lat1, double lon1, double lat2, double lon2) {
    const double p1 = vw_to_rad(lat1);
    const double p2 = vw_to_rad(lat2);
    const double dp = vw_to_rad(lat2 - lat1);
    const double dl = vw_to_rad(lon2 - lon1);
    const double sp = sin(dp * 0.5);
    const double sl = sin(dl * 0.5);
    double a = sp * sp + cos(p1) * cos(p2) * sl * sl;
    if (a > 1.0) a = 1.0;
    if (a < 0.0) a = 0.0;
    return 2.0 * VW_ROUTE_EARTH_R * atan2(sqrt(a), sqrt(1.0 - a));
}

static double vw_bearing(double lat1, double lon1, double lat2, double lon2) {
    const double p1 = vw_to_rad(lat1);
    const double p2 = vw_to_rad(lat2);
    const double dl = vw_to_rad(lon2 - lon1);
    const double y = sin(dl) * cos(p2);
    const double x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl);
    double b = vw_to_deg(atan2(y, x));
    if (b < 0.0) b += 360.0;
    return b;
}

/** 段内线性插值取点：[seg] 是段的右端点索引（1..n-1），[d] 是弧长 */
static void vw_point_at(const double *lat, const double *lon, const double *cum,
                        int seg, double d, double *out_lat, double *out_lon) {
    const double seg_len = cum[seg] - cum[seg - 1];
    double f = seg_len > 1e-9 ? (d - cum[seg - 1]) / seg_len : 0.0;
    if (f < 0.0) f = 0.0;
    if (f > 1.0) f = 1.0;
    *out_lat = lat[seg - 1] + (lat[seg] - lat[seg - 1]) * f;
    *out_lon = lon[seg - 1] + (lon[seg] - lon[seg - 1]) * f;
}

/** 累计弧长数组（调用方负责 free）。失败返回 NULL。 */
static double *vw_route_cum(const double *lat, const double *lon, int n, double *out_total) {
    double *cum = (double *) malloc(sizeof(double) * (size_t) n);
    if (cum == NULL) return NULL;
    cum[0] = 0.0;
    for (int i = 1; i < n; i++) {
        cum[i] = cum[i - 1] + vw_hav(lat[i - 1], lon[i - 1], lat[i], lon[i]);
    }
    if (out_total != NULL) *out_total = cum[n - 1];
    return cum;
}

double vw_route_length(const double *lat, const double *lon, int n) {
    if (lat == NULL || lon == NULL || n < 2) return 0.0;
    double total = 0.0;
    for (int i = 1; i < n; i++) {
        total += vw_hav(lat[i - 1], lon[i - 1], lat[i], lon[i]);
    }
    return total;
}

/**
 * 展开：输出 `[lat,lon,bearing]` 交错数组。
 *
 * @param out        至少能放下 `3 * max_points` 个 double
 * @param max_points 输出上限（调用方按 `总长/step + 2` 估）
 * @param out_total  可选，回写路线总长（米）
 * @return 实际发射的采样点数（0 = 参数非法或内存不足）
 */
int vw_route_expand(const double *lat, const double *lon, int n, double step_m,
                    double *out, int max_points, double *out_total) {
    if (lat == NULL || lon == NULL || out == NULL) return 0;
    if (n < 2 || max_points < 2 || !(step_m > 0.0) || !isfinite(step_m)) return 0;

    double total = 0.0;
    double *cum = vw_route_cum(lat, lon, n, &total);
    if (cum == NULL) return 0;
    if (out_total != NULL) *out_total = total;

    const double end_bearing = vw_bearing(lat[n - 2], lon[n - 2], lat[n - 1], lon[n - 1]);
    int count = 0;
    int seg = 1;        /* 位置游标：只向前走 ⇒ 摊还 O(1)，不做二分 */
    int seg_ahead = 1;  /* 前视游标：同样只向前走 */
    double d = 0.0;

    while (count < max_points) {
        while (seg < n - 1 && cum[seg] < d) seg++;
        double pa_lat, pa_lon;
        vw_point_at(lat, lon, cum, seg, d, &pa_lat, &pa_lon);

        double da = d + VW_ROUTE_LOOKAHEAD_M;
        if (da > total) da = total;
        while (seg_ahead < n - 1 && cum[seg_ahead] < da) seg_ahead++;
        double pb_lat, pb_lon;
        vw_point_at(lat, lon, cum, seg_ahead, da, &pb_lat, &pb_lon);

        const double brg = vw_hav(pa_lat, pa_lon, pb_lat, pb_lon) < 1e-6
                           ? end_bearing
                           : vw_bearing(pa_lat, pa_lon, pb_lat, pb_lon);

        out[count * 3 + 0] = pa_lat;
        out[count * 3 + 1] = pa_lon;
        out[count * 3 + 2] = brg;
        count++;

        if (d >= total) break;              /* 终点已发射 */
        d += step_m;
        if (d > total) d = total;           /* 保证终点一定被发射 */
    }

    free(cum);
    return count;
}
