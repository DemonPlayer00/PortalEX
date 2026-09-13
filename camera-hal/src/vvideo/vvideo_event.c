/*
 * 事件原语：futex 等待/唤醒 + memfd 兼容包装。
 *
 * ## 为什么是 futex，而不是 eventfd / 信号量 / condvar
 *
 *  · `pthread_cond`：只能同进程用。我们的数据面**跨进程**（app 产帧 → HAL 取帧），
 *    所以事件字必须活在共享内存里 —— condvar 直接出局；
 *  · `eventfd`：跨进程要传 fd（SCM_RIGHTS）。链路更长，而且它是个**每进程**对象，
 *    节点的"事件"就没法跟着共享内存一起走；
 *  · **futex**：直接等在一个共享内存里的 32 位整数上。生产者改完数据、
 *    `seq++`、`FUTEX_WAKE`，另一进程的等待者立刻醒 —— 这是 Android BufferQueue、
 *    AAudio 环、Chrome 的 WaitableEvent 用的同一个原语。
 *
 * 无竞争时它**零系统调用**（用户态读一个整数就返回），所以"事件驱动"不会把
 * 每帧的成本抬高 —— 这正是本项目"不要轮询"的落点：等待者睡着时不烧 CPU，
 * 发布时只花一次唤醒。
 */
#define _GNU_SOURCE
#include "vvideo.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#ifndef FUTEX_WAIT
#define FUTEX_WAIT 0
#define FUTEX_WAKE 1
#endif

#if defined(__NR_futex)
#define VV_SYS_FUTEX __NR_futex
#elif defined(__NR_futex_time64)
#define VV_SYS_FUTEX __NR_futex_time64
#endif

static int futex_wait_word(volatile uint32_t *addr, uint32_t expect, int timeout_ms) {
#ifdef VV_SYS_FUTEX
    struct timespec ts;
    struct timespec *tsp = NULL;
    if (timeout_ms >= 0) {
        ts.tv_sec = timeout_ms / 1000;
        ts.tv_nsec = (long)(timeout_ms % 1000) * 1000000L;
        tsp = &ts;
    }
    long rc = syscall(VV_SYS_FUTEX, (uint32_t *)addr, FUTEX_WAIT, expect, tsp, NULL, 0);
    if (rc == 0) return 0;
    if (errno == EAGAIN) return 0;  /* 值已经变了 = 事件已发生（不是错误） */
    if (errno == ETIMEDOUT || errno == EINTR) return -EAGAIN;
    return -errno;
#else
    (void)addr;
    (void)expect;
    (void)timeout_ms;
    return -ENOSYS; /* 该平台没有 futex：调用方会退化成"立刻返回"，至少不阻塞 */
#endif
}

static void futex_wake_word(volatile uint32_t *addr, int n) {
#ifdef VV_SYS_FUTEX
    syscall(VV_SYS_FUTEX, (uint32_t *)addr, FUTEX_WAKE, n, NULL, NULL, 0);
#else
    (void)addr;
    (void)n;
#endif
}

int vv_event_wait(struct vvideo_event *ev, uint32_t expect, int timeout_ms) {
    if (ev == NULL) return -EINVAL;
    if (ev->flags & VV_EV_SHUTDOWN) return -ESHUTDOWN;
    if (ev->seq != expect) return 0; /* 已经有新事件：不必睡 */
    __atomic_add_fetch(&ev->waiters, 1, __ATOMIC_RELAXED);
    int rc = futex_wait_word(&ev->seq, expect, timeout_ms);
    __atomic_sub_fetch(&ev->waiters, 1, __ATOMIC_RELAXED);
    if (ev->flags & VV_EV_SHUTDOWN) return -ESHUTDOWN;
    return rc;
}

void vv_event_wake(struct vvideo_event *ev) {
    if (ev == NULL) return;
    __atomic_add_fetch(&ev->seq, 1, __ATOMIC_RELEASE);
    if (ev->waiters > 0) futex_wake_word(&ev->seq, 1 << 30);
}

uint32_t vv_event_seq(struct vvideo_event *ev) {
    if (ev == NULL) return 0;
    return __atomic_load_n(&ev->seq, __ATOMIC_ACQUIRE);
}

int64_t vv_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

/**
 * `memfd_create` 的兼容包装。
 *
 * 为什么不直接调 libc 的 `memfd_create()`：它的 **bionic 导出从 API 30 起**，
 * 而本模块 `minSdk = 24`（要覆盖老机型）—— 直接调会在旧系统上**加载期**就报
 * "cannot locate symbol"，那是比"功能不可用"严重得多的失败。
 * 内核侧 memfd 从 3.17 就有（Android 10 起必开），所以走 syscall 最稳。
 */
int vv_memfd_create(const char *name, unsigned flags) {
#ifdef __NR_memfd_create
    return (int)syscall(__NR_memfd_create, name, flags);
#else
    (void)name;
    (void)flags;
    errno = ENOSYS;
    return -1;
#endif
}
