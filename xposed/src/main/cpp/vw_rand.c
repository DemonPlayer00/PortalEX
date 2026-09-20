/*
 * 轻量 PRNG（不碰 libc rand 的全局状态）。
 *
 * 拆出来的理由：它是一块**纯机械**的独立能力（xorshift + Box–Muller），
 * 与虚拟世界的时间轴/通道表毫无关系；放在 1200 行的主文件里只会淹没主线。
 * 种子 = 编译期常量 ⊕ 进程启动时间 ⊕ pid（见 [vw_rng_seed_process]）⇒ **每个进程不同、
 * 跨运行不可复现**（这正是我们要的：不能让"噪声"在不同进程/多次启动间可预测）。
 * 后果：任何统计类断言都只能按置信区间给，不能写精确值。
 */
#include <math.h>
#include <time.h>
#include <unistd.h>

#include "vw_internal.h"

static uint64_t g_rng = 0x9E3779B97F4A7C15ULL;

void vw_rng_seed_process(void) {
    /* 每个进程一份不同的序列：常量种子保证"没有真实随机源时也能跑"，
     * 时间与 pid 保证不同进程/不同次启动互不相同。 */
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    g_rng ^= (uint64_t) ts.tv_nsec * 0x2545F4914F6CDD1DULL ^ (uint64_t) getpid();
}

/*
 * **仅测试用**：把随机流钉到确定状态。
 *
 * 存在理由：host 测试要证明"参数为 0 的波动不消耗随机数"这类**流位置**性质 ——
 * 而生产种子按设计是不可复现的（时间 ⊕ pid），没有这个钩子就只能写统计断言，
 * 断言不出"某一个随机数有没有被悄悄抽走"。生产路径**不会调用**它。
 */
void vw_rng_seed_fixed(uint64_t seed) {
    g_rng = seed ? seed : 0x9E3779B97F4A7C15ULL;
}

uint64_t vw_rng_next(void) {
    uint64_t x = g_rng;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    g_rng = x;
    return x;
}

double vw_rng_unit(void) { return (double) (vw_rng_next() >> 11) * (1.0 / 9007199254740992.0); }

double vw_rng_range(double lo, double hi) { return lo + (hi - lo) * vw_rng_unit(); }

double vw_gauss(void) {
    double u1 = vw_rng_unit();
    if (u1 < 1e-12) u1 = 1e-12; /* u1 = 0 时 log 发散，兜一个下限 */
    double u2 = vw_rng_unit();
    return sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
}
