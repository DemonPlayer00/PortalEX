/*
 * vvfs-shm —— 跨进程共享环（本层真正的**数据通道**）。
 *
 * ## 它解决什么
 *
 * app 里有解码好的真实视频像素，HAL 里要有帧可写。两边是不同进程、不同 SELinux 域、
 * 不同 uid。上一次的做法是一个**路径约定 + 自解析的 4096 字节头**，散在 camshim.c 里
 * （`portal_frame_hdr` / `portal_fsrc_*`），于是：
 *   · 生产者与消费者各自实现一遍"换槽规则"，两边不一致就是撕裂或冻结；
 *   · "有没有新帧"靠**轮询头里的 seq**，而 HAL 的注入线程是按帧率跑的，
 *     轮询本身就把"等"的成本摊到了热路径上；
 *   · 通道坏了只能靠日志猜。
 *
 * 本后端把同一件事做成 VFS 里的**一个节点**：共享内存当"文件"，事件字当"等待队列"。
 *
 * ## 布局（`layout=vv`，本后端自己的格式）
 *
 *     +0    struct vvideo_event      事件字（**必须在头部**：futex 就等在这里）
 *     +16   magic / version
 *     +24   width / height / format / slot_bytes / slots / enabled / write_idx
 *     +56   ts_ns / seq（生产者每次发布 +1）
 *     4096 起：slots 个槽，每槽 slot_bytes，页对齐
 *
 * 发布协议（生产者侧，**顺序不能反**）：
 *     写像素到 slot → `write_idx = slot` → `seq++` → `FUTEX_WAKE`
 * 消费者只在 `seq` 变化时换槽，没新帧就**重发上一帧** ——
 * 这条是从真机上学来的：没新帧就不写，画面会在真图与假图之间闪。
 *
 * ## 内存从哪来（硬约束 1：不过磁盘）
 *
 *  · `dev=` 空 + `shared=1`：`memfd_create` + `ftruncate` + `mmap(MAP_SHARED)`。
 *    **匿名共享内存，落在内核 tmpfs 上，没有块设备、没有回写**；跨进程靠传 fd。
 *  · `dev=<路径>`：映射一个**已存在的文件**——此时它有责任落在 tmpfs 上
 *    （模块建的 `/data/local/tmp/<name>tmp` 就是 tmpfs；放在 `/data` 上会被 f2fs 回写，
 *    那正是这条约束要避免的）。选这条路是因为有些进程之间传不了 fd，
 *    而"路径"是唯一双方都能拿到的东西。
 *
 * ## 事件驱动（硬约束 2）
 *
 * 消费者 `vvideo_wait(f, 超时)` 睡在事件字上；生产者发布后唤醒它。
 * **本文件里没有任何 sleep/轮询**。
 */
#define _GNU_SOURCE
#include "vvideo_internal.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define VV_SHM_MAGIC 0x56565348u /* 'VVSH' */
#define VV_SHM_VERSION 1u
#define VV_SHM_HDR_BYTES 4096u

struct vv_shm_hdr {
    struct vvideo_event ev; /* +0，必须第一个 */
    uint32_t magic;         /* +16 */
    uint32_t version;       /* +20 */
    uint32_t width;         /* +24 */
    uint32_t height;        /* +28 */
    uint32_t format;        /* +32 */
    uint32_t slot_bytes;    /* +36 */
    uint32_t slots;         /* +40 */
    uint32_t enabled;       /* +44：生产者总开关，0 = 别接管（露出真机） */
    uint32_t write_idx;     /* +48：最近一次写入的槽 */
    uint32_t reserved;      /* +52 */
    uint64_t ts_ns;         /* +56 */
    uint64_t seq;           /* +64：每次发布 +1 */
    uint64_t reserved2[2];  /* +72 */
};

struct shm_priv {
    struct vv_shm_hdr *hdr;
    size_t data_off;
    size_t slot_bytes;
    uint32_t slots;
    uint64_t last_seq;
    bool inited_here;
};

static int shm_fill(struct vvideo_file *f, struct vv_node *n) {
    struct shm_priv *p = (struct shm_priv *)n->priv;
    if (p == NULL || p->hdr == NULL) return -ENODEV;
    struct vv_shm_hdr *h = p->hdr;
    if (h->magic != VV_SHM_MAGIC) return -EINVAL;
    if (h->enabled == 0) return -EAGAIN; /* 生产者说"不要接管" */
    uint32_t slots = h->slots ? h->slots : p->slots;
    if (slots == 0 || h->slot_bytes < 4096) return -EINVAL;
    uint64_t seq = __atomic_load_n(&h->seq, __ATOMIC_ACQUIRE);
    if (seq != p->last_seq) {
        p->last_seq = seq;
        uint32_t idx = h->write_idx;
        if (idx >= slots) idx = 0;
        n->data = (uint8_t *)n->map + p->data_off + (size_t)idx * h->slot_bytes;
        n->bytes = h->slot_bytes;
    }
    n->ts_ns = (int64_t)h->ts_ns;
    (void)f;
    return 0;
}

static long shm_ioctl(struct vvideo_file *f, unsigned cmd, void *arg) {
    struct vv_node *n = vv_node_of(f->f_inode);
    struct shm_priv *p = n != NULL ? (struct shm_priv *)n->priv : NULL;
    if (p == NULL) return -ENODEV;
    if (cmd == VVIO_CTL) {
        struct vvideo_ctl *c = (struct vvideo_ctl *)arg;
        if (c == NULL || c->key == NULL || c->val == NULL) return -EINVAL;
        if (strcmp(c->key, "seq") == 0) {
            snprintf(c->val, c->vallen, "%llu", (unsigned long long)p->hdr->seq);
            return 0;
        }
        if (strcmp(c->key, "enabled") == 0) {
            snprintf(c->val, c->vallen, "%u", p->hdr->enabled);
            return 0;
        }
        if (strcmp(c->key, "write_idx") == 0) {
            snprintf(c->val, c->vallen, "%u", p->hdr->write_idx);
            return 0;
        }
        if (strcmp(c->key, "slots") == 0) {
            snprintf(c->val, c->vallen, "%u x %u", p->hdr->slots, p->hdr->slot_bytes);
            return 0;
        }
        return -ENOENT;
    }
    if (cmd == VVIO_BIND) {
        /* 生产者让"这一轮开始接管"：置 enabled 并唤醒（app 侧不必自己写头） */
        p->hdr->enabled = 1;
        vv_node_publish(n);
        return 0;
    }
    return -ENOTTY;
}

static struct vvideo_file_operations g_shm_fops;

static void shm_put_super(struct vvideo_sb *sb) {
    struct vv_node *n = (struct vv_node *)sb->s_private;
    if (n != NULL) {
        free(n->priv);
        n->priv = NULL;
    }
    vv_node_free(n);
}

static const struct vvideo_super_operations g_shm_sop = {.put_super = shm_put_super};

/**
 * 打开后备存储。
 *
 * `shared=1`（默认）走 `memfd_create`：**匿名共享内存**。它的内存来自内核 tmpfs，
 * 不经过任何块设备、不会被回写 —— 这是"数据流不过物理磁盘"最干净的落点。
 * `dev=<路径>` 则交给调用方保证那块文件在 tmpfs 上。
 */
static int shm_open_backing(const char *dev, const char *opts, size_t total, int *out_created) {
    char name[64];
    if (!vv_opt_str(opts, "name", name, sizeof(name))) snprintf(name, sizeof(name), "vvvideo-shm");
    if (dev != NULL && dev[0] != '\0') {
        int fd = open(dev, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
        if (fd < 0) {
            vvideo_logf(VV_LOG_ERR, "shm 打不开后备文件 %s（errno=%d）", dev, errno);
            return -1;
        }
        struct stat st;
        if (fstat(fd, &st) == 0 && (size_t)st.st_size < total) {
            if (ftruncate(fd, (off_t)total) != 0) {
                vvideo_logf(VV_LOG_ERR, "shm ftruncate 失败（%s → %zu）", dev, total);
                close(fd);
                return -1;
            }
            *out_created = 1;
        }
        /* ⚠️ 提醒：文件后备时"不过磁盘"是**调用方的责任**（必须放 tmpfs）。
         * 这里不去猜路径 —— 猜错比不猜更糟。 */
        return fd;
    }
    int fd = vv_memfd_create(name, 0);
    if (fd < 0) {
        vvideo_logf(VV_LOG_ERR, "shm 建 memfd 失败（errno=%d）——本平台不支持匿名共享内存", errno);
        return -1;
    }
    if (ftruncate(fd, (off_t)total) != 0) {
        vvideo_logf(VV_LOG_ERR, "shm memfd ftruncate 失败（%zu 字节）", total);
        close(fd);
        return -1;
    }
    *out_created = 1;
    return fd;
}

static struct vvideo_sb *shm_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    unsigned long slots = vv_opt_u32(opts, "slots", 3);
    unsigned long slot_bytes = vv_opt_u32(opts, "slot", 0);
    unsigned long w = vv_opt_u32(opts, "w", 0);
    unsigned long h = vv_opt_u32(opts, "h", 0);
    unsigned long want = vv_opt_u32(opts, "size", 0);
    if (slots == 0 || slots > 64) {
        vvideo_logf(VV_LOG_ERR, "shm 参数非法：slots=%lu", slots);
        errno = EINVAL;
        return NULL;
    }
    if (slot_bytes == 0) {
        if (w == 0 || h == 0) {
            vvideo_logf(VV_LOG_ERR, "shm 缺参数：要么 slot=<字节>，要么 w=,h=（按 NV12 算）");
            errno = EINVAL;
            return NULL;
        }
        slot_bytes = (w * h * 3) / 2; /* NV12 一帧 */
    }
    size_t total = VV_SHM_HDR_BYTES + (size_t)slots * (size_t)slot_bytes;
    if (want > total) total = want;

    int created = 0;
    int fd = shm_open_backing(dev, opts, total, &created);
    if (fd < 0) {
        errno = ENODEV;
        return NULL;
    }
    void *map = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        vvideo_logf(VV_LOG_ERR, "shm mmap 失败（%zu 字节，errno=%d）", total, errno);
        close(fd);
        errno = ENOMEM;
        return NULL;
    }

    struct vv_shm_hdr *hdr = (struct vv_shm_hdr *)map;
    bool init_here = false;
    if (hdr->magic != VV_SHM_MAGIC) {
        unsigned long force_init = vv_opt_u32(opts, "init", dev && dev[0] ? 0 : 1);
        if (!force_init) {
            vvideo_logf(VV_LOG_ERR, "shm %s 头部魔数不对（0x%08x）且未允许 init=1 —— 拒绝挂载",
                        dev && dev[0] ? dev : "memfd", hdr->magic);
            munmap(map, total);
            close(fd);
            errno = EINVAL;
            return NULL;
        }
        memset(map, 0, VV_SHM_HDR_BYTES);
        hdr->magic = VV_SHM_MAGIC;
        hdr->version = VV_SHM_VERSION;
        hdr->width = (uint32_t)w;
        hdr->height = (uint32_t)h;
        hdr->format = VVFMT_NV12;
        hdr->slots = (uint32_t)slots;
        hdr->slot_bytes = (uint32_t)slot_bytes;
        hdr->enabled = 0; /* 先关：等生产者真的能产帧了再开（照 app 侧的老规矩） */
        init_here = true;
    }

    struct vv_node *n = vv_node_create_shared(NULL, fd, map, total, hdr->width, hdr->height,
                                              hdr->format ? hdr->format : VVFMT_NV12,
                                              hdr->slot_bytes, VV_SHM_HDR_BYTES);
    if (n == NULL) {
        munmap(map, total);
        close(fd);
        errno = ENOMEM;
        return NULL;
    }
    struct shm_priv *p = (struct shm_priv *)calloc(1, sizeof(*p));
    if (p == NULL) {
        vv_node_free(n);
        errno = ENOMEM;
        return NULL;
    }
    p->hdr = hdr;
    p->data_off = VV_SHM_HDR_BYTES;
    p->slot_bytes = hdr->slot_bytes;
    p->slots = hdr->slots;
    p->last_seq = 0;
    p->inited_here = init_here;
    n->priv = p;
    n->fill = shm_fill;
    n->always_ready = false; /* 真·事件源：等生产者唤醒 */
    n->writable = true;      /* 生产者可以经本进程写（同一个节点两用） */
    n->bytes = hdr->slot_bytes;
    n->data = (uint8_t *)map + VV_SHM_HDR_BYTES;
    n->stride = hdr->width;

    g_shm_fops = *vv_node_fops();
    g_shm_fops.ioctl = shm_ioctl;

    struct vvideo_sb *sb = vv_alloc_sb(type, &g_shm_sop, dev, opts);
    if (sb == NULL) {
        free(p);
        vv_node_free(n);
        errno = ENOMEM;
        return NULL;
    }
    sb->s_private = n;
    n->sb = sb;
    struct vvideo_inode *root = vv_alloc_inode(sb, VV_S_IFREG, hdr->slot_bytes, NULL, &g_shm_fops, n);
    if (root == NULL) {
        free(p);
        vv_node_free(n);
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    vv_set_root(sb, root);

    vvideo_logf(VV_LOG_INFO,
                "shm 就绪：%s%zu 字节、%u 槽 × %u 字节、head=%zu；事件字 @%p（futex）",
                dev && dev[0] ? dev : "(memfd 匿名共享内存) ", total, hdr->slots, hdr->slot_bytes,
                (size_t)VV_SHM_HDR_BYTES, (void *)&hdr->ev);
    return sb;
}

struct vvideo_fs_type vv_type_shm = {.name = "shm", .mount = shm_mount};
