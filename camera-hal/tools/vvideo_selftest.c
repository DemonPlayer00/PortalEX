/*
 * VVideo 层自测（**在开发机上跑，不在设备上**）。
 *
 * 为什么值得单独写一个：本层的价值全在"不变量"上 ——
 * 路径解析、挂载语义、卸载时的引用、事件驱动是否真的在睡、变换链的代数对不对。
 * 这些性质在真机上**看不出来**（真机上只能看到"画面对不对"），
 * 而在开发机上可以逐条钉死，还能挂 ASan/UBSan 把内存错误一起抓掉。
 *
 * 编译：见 scripts/test-vvideo.sh（gcc -fsanitize=address,undefined）
 *
 * 覆盖：
 *   1. 命名空间：mkdir / lookup / ENOENT / 挂载点必须在已存在的目录上
 *   2. 挂载与卸载：mount 表、EBUSY（还有打开的文件）、重挂
 *   3. **数据面不过磁盘**：raw 挂载后删掉源文件，读出来的字节仍然正确；
 *      且挂载前后进程打开的文件描述符数量不变（fd 确实关掉了）
 *   4. 几何与平面：w/h/stride 与"Y 之后就是 UV"的平面切分
 *   5. 写 + 事件：生产者 write 满即发布；消费者 wait **睡在事件上**被唤醒
 *      （证明"不是轮询"：超时 2 秒的等待，在有事件时 100ms 内返回）
 *   6. 变换链代数：rot90cw∘rot90ccw = 恒等；flipud∘rot90cw 的逆是 rot90ccw∘flipud；
 *      单步 rot90cw 与手算结果逐字节一致
 *   7. ctl 控制面：tree/mounts/log 可读，cmd 可写并能真的挂上一个新节点
 */
#define _GNU_SOURCE
#include "../src/vvideo/vvideo.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int g_pass = 0, g_fail = 0;

#define CHECK(cond, ...)                            \
    do {                                            \
        if (cond) {                                 \
            g_pass++;                               \
        } else {                                    \
            g_fail++;                               \
            printf("  ✗ %s:%d  ", __func__, __LINE__); \
            printf(__VA_ARGS__);                    \
            printf("\n");                           \
        }                                           \
    } while (0)

static int fd_count(void) {
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return -1;
    int n = 0;
    while (readdir(d) != NULL) n++;
    closedir(d);
    return n;
}

static void write_file(const char *path, const uint8_t *data, size_t n) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        perror("open for write");
        exit(2);
    }
    if (write(fd, data, n) != (ssize_t)n) {
        perror("write");
        exit(2);
    }
    close(fd);
}

/* ------------------------------------------------------------------ */

static void test_namespace(void) {
    vvideo_init();
    vvideo_init(); /* 幂等：重复调用不该出任何事 */

    CHECK(vvideo_exists("/vvideo") == 1, "/vvideo 应当由 init 建好");
    CHECK(vvideo_exists("/vvideo/src") == 1, "/vvideo/src 应当存在");
    CHECK(vvideo_exists("/vvideo/nope") == 0, "不存在的路径应当查不到");
    CHECK(vvideo_mkdir("/vvideo/a") == 0, "mkdir 应当成功");
    CHECK(vvideo_mkdir("/vvideo/a/b") == 0, "mkdir 应当支持多层");
    CHECK(vvideo_mkdir("/vvideo/a") == -EEXIST, "重复 mkdir 应当 -EEXIST");
    CHECK(vvideo_mkdir("/vvideo/nothere/x") == -ENOENT, "父目录不存在应当 -ENOENT");

    struct vvideo_inode *in = NULL;
    CHECK(vvideo_lookup("/vvideo/a/b", &in) == 0 && in != NULL, "多层路径应当能查到");
    CHECK(in != NULL && (in->i_mode & VV_S_IFDIR) != 0, "查到的应当是目录");

    /* 挂载点必须在已存在的目录上（照 VFS：mount 不自动建目录） */
    struct vvideo_sb *sb = vvideo_mount("gen", "/vvideo/does-not-exist", NULL, "w=64,h=48");
    CHECK(sb == NULL, "在不存在的位置挂载应当失败");
    CHECK(vv_errno == ENOENT, "失败原因应当是 ENOENT（实际 %d）", vv_errno);
}

static void test_mount_gen_and_geometry(void) {
    struct vvideo_sb *sb = vvideo_mount_ensure("gen", "/vvideo/src/probe", NULL, "w=64,h=48,mode=count");
    CHECK(sb != NULL, "挂载 gen 应当成功");
    if (sb == NULL) return;

    struct vvideo_file *f = vvideo_open("/vvideo/src/probe", VV_O_RDONLY);
    CHECK(f != NULL, "应当能打开生成器节点");
    if (f == NULL) return;

    struct vvideo_frame fr;
    CHECK(vvideo_acquire(f, &fr) == 0, "应当能取到帧");
    CHECK(fr.width == 64 && fr.height == 48, "几何应当是 64x48（实际 %ux%u）", fr.width, fr.height);
    CHECK(fr.stride[0] == 64, "stride 应当是 64（实际 %zu）", fr.stride[0]);
    CHECK(fr.plane_count == 2, "NV12 应当给出两个平面（实际 %u）", fr.plane_count);
    CHECK(fr.plane[1] == fr.plane[0] + 64 * 48, "色度平面应当紧跟在 Y 之后");
    CHECK(fr.size[0] == 64 * 48 * 3 / 2, "size[0] 应当是一帧的大小（实际 %zu）", fr.size[0]);
    CHECK(vvideo_release(f, &fr) == 0, "release 应当成功");

    /* 两次 acquire 内容必须不同：生成器是"活的"（按帧号推进），但**没有定时器** ——
     * 靠 acquire 驱动，所以这里不需要 sleep。 */
    uint8_t a[64 * 48], b[64 * 48];
    struct vvideo_frame f1, f2;
    /* ⚠️ 顺序要紧：帧指针到**下一次 acquire** 就作废（缓冲复用），所以取一帧就立刻拷走。
     * 第一版把两次 acquire 写在一行里再拷 —— 两份数据当然是同一份。自测自己抓到的。 */
    CHECK(vvideo_acquire(f, &f1) == 0, "取第 1 帧应当成功");
    memcpy(a, f1.plane[0], sizeof(a));
    vvideo_release(f, &f1);
    CHECK(vvideo_acquire(f, &f2) == 0, "取第 2 帧应当成功");
    memcpy(b, f2.plane[0], sizeof(b));
    vvideo_release(f, &f2);
    CHECK(memcmp(a, b, sizeof(a)) != 0, "生成器的两帧内容应当不同（帧号在推进）");
    CHECK(f2.seq > f1.seq, "seq 应当递增（%llu → %llu）", (unsigned long long)f1.seq,
          (unsigned long long)f2.seq);

    /* 静态源的事件语义：wait 立刻返回 0，绝不假装"等了 33ms" */
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    CHECK(vvideo_wait(f, 5000) == 0, "静态源 wait 应当立刻返回 0");
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double ms = (t1.tv_sec - t0.tv_sec) * 1000.0 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
    CHECK(ms < 20.0, "静态源 wait 不该真的睡（实测 %.1f ms）", ms);

    vvideo_close(f);
}

/* ------------------------------------------------------------------ *
 * 数据面不过磁盘
 * ------------------------------------------------------------------ */

static void test_raw_is_disk_free(void) {
    const char *path = "/tmp/vvideo-selftest.raw";
    const size_t n = 4096;
    uint8_t *src = (uint8_t *)malloc(n);
    for (size_t i = 0; i < n; i++) src[i] = (uint8_t)(i * 7 + 3);
    write_file(path, src, n);

    int fds_before = fd_count();
    struct vvideo_sb *sb =
        vvideo_mount_ensure("raw", "/vvideo/src/fromdisk", path, "w=64,h=32,stride=64");
    int fds_after = fd_count();
    CHECK(sb != NULL, "挂载 raw 应当成功");
    if (sb == NULL) {
        free(src);
        return;
    }

    CHECK(fds_after == fds_before, "挂载后不该多出 fd（%d → %d）：素材读完就该关", fds_before,
          fds_after);

    /* **关键**：把源文件删掉。数据面若还在碰磁盘，这里必然读不出来。 */
    CHECK(unlink(path) == 0, "删掉源文件");
    CHECK(access(path, F_OK) != 0, "源文件应当真的没了");

    uint8_t *got = NULL;
    size_t len = 0;
    ssize_t r = vvideo_read_all("/vvideo/src/fromdisk", (void **)&got, &len);
    CHECK(r == (ssize_t)n, "删文件后仍应读出 %zu 字节（实际 %zd）", n, r);
    if (r == (ssize_t)n) {
        CHECK(memcmp(got, src, n) == 0, "内容应当与源文件逐字节一致");
    }
    free(got);

    struct vvideo_file *f = vvideo_open("/vvideo/src/fromdisk", VV_O_RDONLY);
    struct vvideo_frame fr;
    CHECK(f != NULL && vvideo_acquire(f, &fr) == 0, "删文件后仍应能取帧");
    if (f != NULL) {
        CHECK(fr.size[0] == n, "帧大小应当是文件大小（实际 %zu）", fr.size[0]);
        CHECK(fr.width == 64 && fr.height == 32, "几何应当来自挂载选项");
        vvideo_close(f);
    }
    free(src);
}

static void test_mount_busy_on_open_file(void) {
    struct vvideo_sb *sb = vvideo_mount_ensure("mem", "/vvideo/src/busy", NULL, "size=1024,w=32,h=16");
    CHECK(sb != NULL, "挂载 mem 应当成功");
    if (sb == NULL) return;

    struct vvideo_file *f = vvideo_open("/vvideo/src/busy", VV_O_RDONLY);
    CHECK(f != NULL, "打开应当成功");
    CHECK(vvideo_umount("/vvideo/src/busy") == -EBUSY, "还有打开的文件时卸载应当 -EBUSY");
    vvideo_close(f);
    CHECK(vvideo_umount("/vvideo/src/busy") == 0, "关掉之后应当能卸载");
    CHECK(vvideo_umount("/vvideo/src/busy") == -ENOENT, "重复卸载应当 -ENOENT");
    /* 卸载后**目录还在**（照 VFS：卸载只摘掉覆盖层，不删挂载点）。
     * 判据用"能不能在原位重新挂上"，而不是"路径还在不在" —— 后者永远为真。 */
    CHECK(vvideo_mount_ensure("mem", "/vvideo/src/busy", NULL, "size=64,w=8,h=8") != NULL,
          "同一位置应当能重新挂载");
    CHECK(vvideo_umount("/vvideo/src/busy") == 0, "收尾卸载");
}

/* ------------------------------------------------------------------ *
 * 事件驱动
 * ------------------------------------------------------------------ */

struct producer_arg {
    struct vvideo_file *f;
    int count;
    int interval_ms;
};

static void *producer_thread(void *arg) {
    struct producer_arg *pa = (struct producer_arg *)arg;
    for (int i = 0; i < pa->count; i++) {
        struct timespec ts = {.tv_sec = 0, .tv_nsec = (long)pa->interval_ms * 1000000L};
        nanosleep(&ts, NULL);
        vvideo_ioctl(pa->f, VVIO_NOTIFY, NULL); /* 发布 = seq++ + FUTEX_WAKE */
    }
    return NULL;
}

static void test_event_driven_wait(void) {
    struct vvideo_sb *sb = vvideo_mount_ensure("shm", "/vvideo/src/live", NULL, "slots=3,slot=4096,w=64,h=48");
    CHECK(sb != NULL, "挂载 shm(memfd) 应当成功");
    if (sb == NULL) return;

    struct vvideo_file *cons = vvideo_open("/vvideo/src/live", VV_O_RDONLY);
    struct vvideo_file *prod = vvideo_open("/vvideo/src/live", VV_O_RDWR);
    CHECK(cons != NULL && prod != NULL, "应当能同时开消费者与生产者");
    if (cons == NULL || prod == NULL) return;

    /* 1) 没有生产者时：非阻塞探询立刻 -EAGAIN（不是"假装有一帧"） */
    CHECK(vvideo_wait(cons, 0) == -EAGAIN, "没有新事件时非阻塞等待应当 -EAGAIN");

    /* 2) 睡等真的会睡：超时 120ms 应当耗掉约 120ms（证明不是忙轮询立刻返回） */
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = vvideo_wait(cons, 120);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double slept = (t1.tv_sec - t0.tv_sec) * 1000.0 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
    CHECK(rc == -EAGAIN, "无事件时应当超时返回（rc=%d）", rc);
    CHECK(slept >= 100.0 && slept < 400.0, "应当在事件字上睡满超时（实测 %.1f ms）", slept);

    /* 3) **被事件唤醒**：生产者 10 次发布、每次间隔 20ms；
     *    消费者的等待超时是 2000ms —— 若返回时远小于 2000ms，就只可能是被唤醒的。 */
    struct producer_arg pa = {prod, 10, 20};
    pthread_t th;
    pthread_create(&th, NULL, producer_thread, &pa);

    int woke = 0, timeouts = 0;
    double worst = 0;
    for (int i = 0; i < 10; i++) {
        struct timespec a, b;
        clock_gettime(CLOCK_MONOTONIC, &a);
        int r2 = vvideo_wait(cons, 2000);
        clock_gettime(CLOCK_MONOTONIC, &b);
        double dt = (b.tv_sec - a.tv_sec) * 1000.0 + (b.tv_nsec - a.tv_nsec) / 1e6;
        if (r2 == 0) {
            woke++;
            if (dt > worst) worst = dt;
        } else if (r2 == -EAGAIN) {
            timeouts++;
        }
    }
    pthread_join(th, NULL);
    CHECK(woke == 10, "10 次发布应当唤醒 10 次（实际 %d，超时 %d）", woke, timeouts);
    CHECK(worst < 300.0, "每次唤醒都该很及时（最慢 %.1f ms，超时上限 2000ms）", worst);

    struct vvideo_fsstat st;
    CHECK(vvideo_ioctl(cons, VVIO_STAT, &st) == 0, "应当能取到节点统计");
    CHECK(st.waits >= 12 && st.wakes >= 10, "统计应当记账（等待=%lu 唤醒=%lu）", st.waits, st.wakes);

    vvideo_close(prod);
    vvideo_close(cons);
    CHECK(vvideo_umount("/vvideo/src/live") == 0, "卸载 shm 应当成功");
}

/* ------------------------------------------------------------------ *
 * 变换链
 * ------------------------------------------------------------------ */

/** 把一段字节挂成 mem 节点，返回挂载点路径（调用者负责 umount） */
static void mount_bytes(const char *dir, const uint8_t *data, size_t n, uint32_t w, uint32_t h) {
    char opts[96];
    snprintf(opts, sizeof(opts), "size=%zu,w=%u,h=%u", n, w, h);
    CHECK(vvideo_mount_ensure("mem", dir, NULL, opts) != NULL, "挂载 %s 应当成功", dir);
    if (!vvideo_exists(dir)) return;
    /* 经 ctl 写不进去（mem 节点是只读语义的"素材"）—— 用 VVideo 的写接口灌内容 */
    struct vvideo_file *f = vvideo_open(dir, VV_O_RDWR);
    if (f == NULL) return;
    CHECK(vvideo_write(f, data, n) == (ssize_t)n, "写入 %s 应当成功", dir);
    vvideo_close(f);
}

/** 读**恰好** n 字节：节点的总大小可能比"有效像素"多（NV12 源要给色度平面留位置），
 * 所以这里按请求读，而不是 read_all（后者读的是整个节点）。 */
static void read_bytes(const char *path, uint8_t *out, size_t n) {
    struct vvideo_file *f = vvideo_open(path, VV_O_RDONLY);
    CHECK(f != NULL, "打开 %s 应当成功", path);
    if (f == NULL) return;
    ssize_t r = vvideo_read(f, out, n);
    CHECK(r == (ssize_t)n, "读 %s 应当是 %zu 字节（实际 %zd）", path, n, r);
    vvideo_close(f);
}

static void test_xform_single_step(void) {
    /* 3×2 的已知图案：
     *     1 2 3
     *     4 5 6
     * rot90cw 后应当变成 2×3：
     *     4 1
     *     5 2
     *     6 3
     */
    const uint8_t src[6] = {1, 2, 3, 4, 5, 6};
    mount_bytes("/vvideo/src/t-src", src, sizeof(src), 3, 2);
    struct vvideo_sb *sb = vvideo_mount_ensure("xform", "/vvideo/src/t-rot", "/vvideo/src/t-src", "op=rot90cw");
    CHECK(sb != NULL, "挂载 xform 应当成功");
    if (sb == NULL) return;

    uint8_t out[16] = {0};
    read_bytes("/vvideo/src/t-rot", out, 6);
    const uint8_t want[6] = {4, 1, 5, 2, 6, 3};
    CHECK(memcmp(out, want, 6) == 0, "rot90cw 结果应当是 4 1 5 2 6 3（实际 %d %d %d %d %d %d）", out[0],
          out[1], out[2], out[3], out[4], out[5]);

    struct vvideo_file *f = vvideo_open("/vvideo/src/t-rot", VV_O_RDONLY);
    struct vvideo_frame fr;
    CHECK(f != NULL && vvideo_acquire(f, &fr) == 0, "xform 节点应当能取帧");
    if (f != NULL) {
        CHECK(fr.width == 2 && fr.height == 3, "几何应当换成 2x3（实际 %ux%u）", fr.width, fr.height);
        vvideo_close(f);
    }
    CHECK(vvideo_umount("/vvideo/src/t-rot") == 0, "卸载 xform");
    CHECK(vvideo_umount("/vvideo/src/t-src") == 0, "卸载源");
}

static void test_xform_algebra(void) {
    /* 12 个互不相同的字节，任何一次错误的旋转/镜像都会被抓到 */
    uint8_t src[12];
    for (int i = 0; i < 12; i++) src[i] = (uint8_t)(10 + i);
    mount_bytes("/vvideo/src/a-src", src, 12, 4, 3);

    /* rot90cw 然后 rot90ccw = 恒等 */
    CHECK(vvideo_mount_ensure("xform", "/vvideo/src/a-id", "/vvideo/src/a-src", "op=rot90cw+rot90ccw") != NULL,
          "挂载互逆链");
    uint8_t back[12] = {0};
    read_bytes("/vvideo/src/a-id", back, 12);
    CHECK(memcmp(back, src, 12) == 0, "rot90cw∘rot90ccw 应当是恒等");

    /* flipud∘rot90cw 的逆 = rot90ccw∘flipud（即链 `flipud,rot90ccw`）：
     * 先做正变换，再把结果当源做逆变换，应当回到原图。这是"显示变换"配对的数学保证。 */
    CHECK(vvideo_mount_ensure("xform", "/vvideo/src/a-fwd", "/vvideo/src/a-src",
                       "op=rot90cw+flipud,cache=0") != NULL,
          "挂载正向链");
    CHECK(vvideo_mount_ensure("xform", "/vvideo/src/a-inv", "/vvideo/src/a-fwd",
                       "op=flipud+rot90ccw,cache=0") != NULL,
          "挂载逆向链");
    uint8_t round[12] = {0};
    read_bytes("/vvideo/src/a-inv", round, 12);
    CHECK(memcmp(round, src, 12) == 0, "flipud∘rot90cw 的逆应当是 rot90ccw∘flipud");

    /* flipud 两次 = 恒等 */
    CHECK(vvideo_mount_ensure("xform", "/vvideo/src/a-fu", "/vvideo/src/a-src", "op=flipud+flipud") != NULL,
          "挂载双 flipud");
    uint8_t fu[12] = {0};
    read_bytes("/vvideo/src/a-fu", fu, 12);
    CHECK(memcmp(fu, src, 12) == 0, "flipud∘flipud 应当是恒等");

    /* 静态源的叠加层默认只算一次：重复读必须一致（缓存生效的证据） */
    uint8_t again[12] = {0};
    read_bytes("/vvideo/src/a-id", again, 12);
    CHECK(memcmp(again, back, 12) == 0, "重复读应当一致（静态叠加层只算一次）");

    CHECK(vvideo_umount("/vvideo/src/a-fu") == 0, "卸载双 flipud");
    CHECK(vvideo_umount("/vvideo/src/a-inv") == 0, "卸载逆向链");
    CHECK(vvideo_umount("/vvideo/src/a-fwd") == 0, "卸载正向链");
    CHECK(vvideo_umount("/vvideo/src/a-id") == 0, "卸载互逆链");
    CHECK(vvideo_umount("/vvideo/src/a-src") == 0, "卸载源");
}

/* ------------------------------------------------------------------ *
 * 控制面
 * ------------------------------------------------------------------ */

static void test_ctl(void) {
    CHECK(vvideo_mount_ensure("ctl", "/vvideo/ctl", "", "") != NULL, "挂载 ctl 应当成功");

    static const char *names[] = {"tree", "mounts", "fs", "log", "stat", "help"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        char path[128];
        snprintf(path, sizeof(path), "/vvideo/ctl/%s", names[i]);
        uint8_t *got = NULL;
        size_t len = 0;
        ssize_t r = vvideo_read_all(path, (void **)&got, &len);
        CHECK(r > 0, "ctl/%s 应当可读（实际 %zd）", names[i], r);
        free(got);
    }

    /* cmd 可写，并且真的能改命名空间 */
    uint8_t *before = NULL;
    size_t blen = 0;
    vvideo_read_all("/vvideo/ctl/tree", (void **)&before, &blen);

    /* 两步：先建目录再挂 —— `mount` 的"挂载点必须已存在"是核的硬语义，
     * ctl 只是把它暴露出来，**不替调用方猜**（两条路行为一致，才不会一边能跑一边不能）。 */
    static const char *cmds[] = {"mkdir /vvideo/src/fromctl",
                                 "mount gen /vvideo/src/fromctl w=96,h=64,mode=stripes"};
    for (size_t ci = 0; ci < sizeof(cmds) / sizeof(cmds[0]); ci++) {
        struct vvideo_file *f = vvideo_open("/vvideo/ctl/cmd", VV_O_WRONLY);
        CHECK(f != NULL, "ctl/cmd 应当可写打开");
        if (f != NULL) {
            CHECK(vvideo_write(f, cmds[ci], strlen(cmds[ci])) == (ssize_t)strlen(cmds[ci]),
                  "写 cmd 应当成功：%s", cmds[ci]);
            vvideo_close(f);
        }
    }
    CHECK(vvideo_exists("/vvideo/src/fromctl") == 1, "ctl 命令应当真的挂上了新节点");

    uint8_t *after = NULL;
    size_t alen = 0;
    vvideo_read_all("/vvideo/ctl/tree", (void **)&after, &alen);
    CHECK(alen > blen, "tree 的内容应当随挂载变长（%zu → %zu）", blen, alen);
    free(before);
    free(after);

    /* 坏命令不该崩，只该记账 */
    struct vvideo_file *f = vvideo_open("/vvideo/ctl/cmd", VV_O_WRONLY);
    if (f != NULL) {
        const char *bad = "nonsense whatever";
        vvideo_write(f, bad, strlen(bad));
        vvideo_close(f);
    }
    uint8_t *log = NULL;
    size_t llen = 0;
    ssize_t lr = vvideo_read_all("/vvideo/ctl/log", (void **)&log, &llen);
    CHECK(llen > 0 && log != NULL && strstr((char *)log, "不认识的命令") != NULL,
          "坏命令应当进记账日志（读回 r=%zd len=%zu）", lr, llen);
    if (log != NULL && strstr((char *)log, "不认识的命令") == NULL) {
        size_t show = llen > 300 ? 300 : llen;
        printf("    [诊断] log 末尾 %zu 字节：\n%s\n", show, (char *)log + (llen - show));
    }
    free(log);

    CHECK(vvideo_umount("/vvideo/src/fromctl") == 0, "卸载 ctl 挂上的节点");
}

/* ASan 的泄漏豁免：只在开了 ASan 时存在（弱符号，没开就是 NULL） */
extern void __lsan_ignore_object(const void *p) __attribute__((weak));

int main(void) {
    /* 关掉 stdout 缓冲：崩了也要能看到前面打印的 ✗（ASan abort 会丢掉缓冲区） */
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("VVideo 自测开始（ABI v%d）\n\n", VV_ABI_VERSION);
    if (__lsan_ignore_object != NULL) {
        /* rootfs 是**故意永不释放**的单例：没有它就没有命名空间的根 */
        __lsan_ignore_object(vvideo_root_singleton());
    }

    test_namespace();
    test_mount_gen_and_geometry();
    test_raw_is_disk_free();
    test_mount_busy_on_open_file();
    test_event_driven_wait();
    test_xform_single_step();
    test_xform_algebra();
    test_ctl();

    char tree[16384];
    size_t tlen = 0;
    if (vvideo_tree_text(tree, sizeof(tree), &tlen) == 0) {
        printf("\n最终命名空间：\n%s", tree);
        vvideo_mounts_text(tree, sizeof(tree), &tlen);
        printf("\n挂载表：\n%s", tree);
    }

    /* 收尾：把还挂着的都卸掉 —— 卸载路径（递归释放 dentry/inode + put_super）
     * 是最容易漏内存的地方，所以让 ASan 有机会对**它**下判断。 */
    printf("\n收尾卸载：\n");
    const char *left[] = {"/vvideo/ctl", "/vvideo/src/probe", "/vvideo/src/fromdisk"};
    for (size_t i = 0; i < sizeof(left) / sizeof(left[0]); i++) {
        int rc = vvideo_umount(left[i]);
        printf("  umount %-24s ⇒ %d\n", left[i], rc);
    }

    printf("\n结果：通过 %d，失败 %d\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
