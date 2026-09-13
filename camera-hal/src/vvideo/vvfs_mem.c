/*
 * vvfs-mem / vvfs-raw —— RAM 帧节点，以及本层**通用的数据面**。
 *
 * ## 为什么这两个"文件系统"放在一个文件里
 *
 * mem / raw / gen / shm / xform 五个后端的**数据面是同一个东西**：
 * "一块 RAM + 几何 + 一个共享事件字 + 一个可选的填充函数"。
 * 差别只在两件事：内存怎么来、内容怎么变。所以这里定义
 *   · `struct vv_node`（数据面的全部状态）与它的**通用 file_operations**；
 *   · 两个最直接的后端：`mem`（纯内存）与 `raw`（挂载时从文件**灌一次**再关 fd）。
 * 其余后端（gen/shm/xform）复用同一套 ops，只提供各自的 `fill` 与内存来源。
 *
 * 这跟 VFS 用 page cache 收拢各家文件系统是同一个道理：只要有一个地方负责
 * "这块字节就是当前帧"，几何、事件、错误处理、统计就不可能各写一遍各错一遍。
 *
 * ## 数据面不过磁盘（硬约束 1）
 *
 *  · `mem`：`malloc`（进程内）或 `memfd_create`（匿名共享内存，内核 tmpfs 上，
 *    **没有块设备、没有回写**）；
 *  · `raw`：`dev=<文件>` 只在 **mount 时读一次**，读完立刻 `close(fd)` ——
 *    文件描述符不留在生命周期里，热路径（每帧）不可能再碰磁盘。
 *    它存在的意义是"上电素材"，不是数据通道。真正的数据通道是 `shm`。
 *
 * ## 事件驱动（硬约束 2）
 *
 *  · 静态源（mem/raw）`always_ready = true`：`wait` 立刻返回 0 —— 不制造虚假节拍，
 *    调用方拿到的语义是"随时可以取"，而不是"每 33ms 假装有一帧"；
 *  · 生产者节点（`writable`）：`write()` 写满即 `vv_node_publish()`（seq++ + 唤醒），
 *    等待者在自己进程里睡着，不烧 CPU；
 *  · **本文件里没有任何 sleep/usleep/忙等**。
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

/* ------------------------------------------------------------------ *
 * 帧节点
 * ------------------------------------------------------------------ */

struct vv_node *vv_node_create(struct vvideo_sb *sb, size_t bytes, uint32_t w, uint32_t h,
                               uint32_t format, size_t stride) {
    struct vv_node *n = (struct vv_node *)calloc(1, sizeof(*n));
    if (n == NULL) return NULL;
    n->sb = sb;
    n->fd = -1;
    n->width = w;
    n->height = h;
    n->format = format;
    n->stride = stride;
    if (bytes > 0) {
        n->data = (uint8_t *)malloc(bytes);
        if (n->data == NULL) {
            free(n);
            return NULL;
        }
        memset(n->data, 0, bytes);
    }
    n->bytes = bytes;
    n->ev = (struct vvideo_event *)calloc(1, sizeof(*n->ev));
    if (n->ev == NULL) {
        free(n->data);
        free(n);
        return NULL;
    }
    n->ev_from_map = false;
    return n;
}

struct vv_node *vv_node_create_shared(struct vvideo_sb *sb, int fd, void *map, size_t map_len,
                                      uint32_t w, uint32_t h, uint32_t format, size_t stride,
                                      size_t data_off) {
    if (map == NULL || map_len <= data_off || map_len < sizeof(struct vvideo_event)) {
        errno = EINVAL;
        return NULL;
    }
    struct vv_node *n = (struct vv_node *)calloc(1, sizeof(*n));
    if (n == NULL) return NULL;
    n->sb = sb;
    n->fd = fd;
    n->map = map;
    n->map_len = map_len;
    n->data = (uint8_t *)map + data_off;
    n->bytes = map_len - data_off;
    n->width = w;
    n->height = h;
    n->format = format;
    n->stride = stride;
    /* 事件字就在共享内存头部：跨进程也是同一套 futex 语义 */
    n->ev = (struct vvideo_event *)map;
    n->ev_from_map = true;
    return n;
}

void vv_node_free(struct vv_node *n) {
    if (n == NULL) return;
    if (n->map != NULL) {
        munmap(n->map, n->map_len);
        n->map = NULL;
    } else {
        free(n->data);
    }
    n->data = NULL;
    if (!n->ev_from_map) free(n->ev);
    n->ev = NULL;
    if (n->fd >= 0) close(n->fd);
    n->fd = -1;
    free(n);
}

struct vv_node *vv_node_of(struct vvideo_inode *i) {
    return i != NULL ? (struct vv_node *)i->i_private : NULL;
}

void vv_node_publish(struct vv_node *n) {
    if (n == NULL) return;
    n->ts_ns = vv_now_ns();
    vv_event_wake(n->ev);
}

/* ------------------------------------------------------------------ *
 * 通用 file_operations
 * ------------------------------------------------------------------ */

/** 每个打开实例的消费位点：futex 要"和上次看到的值比"，所以必须按 file 记 */
struct vv_file_state {
    uint32_t last_seq;
};

static int node_open(struct vvideo_inode *inode, struct vvideo_file *f) {
    struct vv_node *n = vv_node_of(inode);
    struct vv_file_state *st = (struct vv_file_state *)calloc(1, sizeof(*st));
    if (st == NULL) return -ENOMEM;
    if (n != NULL && n->ev != NULL) {
        f->f_event = n->ev;
        st->last_seq = vv_event_seq(n->ev);
    }
    f->f_private = st;
    return 0;
}

static int node_release(struct vvideo_inode *inode, struct vvideo_file *f) {
    (void)inode;
    free(f->f_private);
    f->f_private = NULL;
    f->f_event = NULL;
    return 0;
}

static int node_acquire(struct vvideo_file *f, struct vvideo_frame *out) {
    struct vv_node *n = vv_node_of(f->f_inode);
    if (n == NULL || n->data == NULL) return -ENODEV;
    if (n->fill != NULL) {
        int rc = n->fill(f, n);
        if (rc != 0) return rc;
    }
    out->width = n->width;
    out->height = n->height;
    out->format = n->format;
    out->ts_ns = n->ts_ns != 0 ? n->ts_ns : vv_now_ns();
    out->plane[0] = n->data;
    out->stride[0] = n->stride;
    out->size[0] = n->bytes;
    out->plane_count = 1;
    /* YUV 平面式源：顺手把色度平面也指出来（同一块内存里，Y 之后就是 UV），
     * 于是调用方不必自己算偏移 —— 偏移算错是这类代码最常见的越界来源。 */
    if (n->height > 0 && n->stride >= n->width && n->format != VVFMT_OPAQUE &&
        n->format != VVFMT_JPEG) {
        size_t y_bytes = n->stride * (size_t)n->height;
        if (y_bytes < n->bytes) {
            out->plane[1] = n->data + y_bytes;
            out->stride[1] = n->stride;
            out->size[1] = n->bytes - y_bytes;
            out->plane_count = 2;
        }
    }
    struct vv_file_state *st = (struct vv_file_state *)f->f_private;
    if (st != NULL && n->ev != NULL) st->last_seq = vv_event_seq(n->ev);
    return 0;
}

static ssize_t node_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos) {
    struct vv_node *node = vv_node_of(f->f_inode);
    if (node == NULL || node->data == NULL) return -ENODEV;
    if (node->fill != NULL) {
        int rc = node->fill(f, node);
        if (rc != 0) return rc;
    }
    off_t p = pos != NULL ? *pos : 0;
    if (p < 0) return -EINVAL;
    if ((size_t)p >= node->bytes) return 0;
    size_t take = node->bytes - (size_t)p;
    if (take > n) take = n;
    memcpy(buf, node->data + p, take);
    if (pos != NULL) *pos += (off_t)take;
    return (ssize_t)take;
}

static ssize_t node_write(struct vvideo_file *f, const void *buf, size_t n, off_t *pos) {
    struct vv_node *node = vv_node_of(f->f_inode);
    if (node == NULL || node->data == NULL) return -ENODEV;
    if (!node->writable) return -EACCES;
    off_t p = pos != NULL ? *pos : 0;
    if (p < 0) return -EINVAL;
    if ((size_t)p >= node->bytes) return -ENOSPC;
    size_t take = node->bytes - (size_t)p;
    if (take > n) take = n;
    memcpy(node->data + p, buf, take);
    if (pos != NULL) *pos += (off_t)take;
    /* 写满才发布：生产者一次写完一整帧（或分片写完整帧）才会唤醒等待者，
     * 于是消费者永远看不到半帧 —— 这是共享环最基本的一致性要求。 */
    if ((size_t)*pos >= node->bytes) vv_node_publish(node);
    return (ssize_t)take;
}

static int node_wait(struct vvideo_file *f, int timeout_ms) {
    struct vv_node *n = vv_node_of(f->f_inode);
    if (n == NULL) return -ENODEV;
    if (n->always_ready) return 0; /* 静态源：永远就绪，不睡 */
    if (n->ev == NULL) return -ENOTTY;
    struct vv_file_state *st = (struct vv_file_state *)f->f_private;
    uint32_t last = st != NULL ? st->last_seq : 0;
    uint32_t cur = vv_event_seq(n->ev);
    if (cur != last) {
        if (st != NULL) st->last_seq = cur;
        return 0;
    }
    if (timeout_ms == 0) return -EAGAIN; /* 非阻塞探询：不睡 */
    int rc = vv_event_wait(n->ev, last, timeout_ms);
    if (rc == 0 && st != NULL) st->last_seq = vv_event_seq(n->ev);
    return rc;
}

static int node_notify(struct vvideo_file *f) {
    vv_node_publish(vv_node_of(f->f_inode));
    return 0;
}

static long node_ioctl(struct vvideo_file *f, unsigned cmd, void *arg) {
    struct vv_node *n = vv_node_of(f->f_inode);
    if (n == NULL) return -ENODEV;
    switch (cmd) {
        case VVIO_QUERYCAP: {
            struct vvideo_cap *c = (struct vvideo_cap *)arg;
            if (c == NULL) return -EINVAL;
            memset(c, 0, sizeof(*c));
            snprintf(c->driver, sizeof(c->driver), "%s",
                     n->sb && n->sb->s_type ? n->sb->s_type->name : "vvfs");
            snprintf(c->card, sizeof(c->card), "%.40s", f->f_dentry ? f->f_dentry->name : "?");
            snprintf(c->bus, sizeof(c->bus), "%.40s", n->sb ? n->sb->s_dev : "-");
            c->version = VV_ABI_VERSION;
            c->flags = (n->writable ? 1u : 0u) | (n->map != NULL ? 2u : 0u) |
                       (n->always_ready ? 4u : 0u);
            return 0;
        }
        case VVIO_GET_FORMAT: {
            struct vvideo_fmt *fmt = (struct vvideo_fmt *)arg;
            if (fmt == NULL) return -EINVAL;
            fmt->width = n->width;
            fmt->height = n->height;
            fmt->format = n->format;
            fmt->stride = n->stride;
            return 0;
        }
        case VVIO_CTL: {
            struct vvideo_ctl *c = (struct vvideo_ctl *)arg;
            if (c == NULL || c->key == NULL) return -EINVAL;
            if (strcmp(c->key, "layout") == 0) {
                snprintf(c->val, c->vallen, "%s", n->map != NULL ? "shared-map" : "private-ram");
                return 0;
            }
            if (strcmp(c->key, "bytes") == 0) {
                snprintf(c->val, c->vallen, "%zu", n->bytes);
                return 0;
            }
            if (strcmp(c->key, "fps_hint") == 0) {
                snprintf(c->val, c->vallen, "%s", n->always_ready ? "static" : "event");
                return 0;
            }
            return -ENOENT;
        }
        default:
            return -ENOTTY;
    }
}

static const struct vvideo_file_operations g_node_fops = {
    .open = node_open,
    .release = node_release,
    .read = node_read,
    .write = node_write,
    .ioctl = node_ioctl,
    .acquire = node_acquire,
    .wait = node_wait,
    .notify = node_notify,
};

const struct vvideo_file_operations *vv_node_fops(void) { return &g_node_fops; }

struct vvideo_inode *vv_node_inode(struct vv_node *n, struct vvideo_sb *sb, size_t size) {
    return vv_alloc_inode(sb, VV_S_IFREG, size, NULL, &g_node_fops, n);
}

/* ------------------------------------------------------------------ *
 * 选项 → 几何
 * ------------------------------------------------------------------ */

static uint32_t parse_format(const char *opts) {
    char v[16];
    if (!vv_opt_str(opts, "fmt", v, sizeof(v))) return VVFMT_OPAQUE;
    if (strcasecmp(v, "nv12") == 0) return VVFMT_NV12;
    if (strcasecmp(v, "nv21") == 0) return VVFMT_NV21;
    if (strcasecmp(v, "i420") == 0) return VVFMT_I420;
    if (strcasecmp(v, "raw10") == 0) return VVFMT_RAW10;
    if (strcasecmp(v, "jpeg") == 0) return VVFMT_JPEG;
    return VVFMT_OPAQUE;
}

/**
 * 由 `bytes` 与选项推出几何。
 *
 * 为什么允许"推不出来就退成 OPAQUE"：`.raw` 模板本来就是**一整块不透明字节**
 * （注入就是一次 memcpy），硬要给它编一个宽高只会制造错误信息。
 * 只有调用方真的给了 w/h 才当它是图像 —— 这也让"几何"这件事有唯一来源。
 */
static void fill_geometry(const char *opts, size_t bytes, uint32_t *w, uint32_t *h, uint32_t *fmt,
                          size_t *stride) {
    unsigned long ow = vv_opt_u32(opts, "w", 0);
    unsigned long oh = vv_opt_u32(opts, "h", 0);
    unsigned long os = vv_opt_u32(opts, "stride", 0);
    *fmt = parse_format(opts);
    if (ow > 0 && oh > 0) {
        *w = (uint32_t)ow;
        *h = (uint32_t)oh;
        *stride = os > 0 ? (size_t)os : (size_t)ow;
    } else {
        /* 推不出宽高就不编：`.raw` 模板本来就是"一整块不透明字节"，硬给它安一个
         * 几何只会制造错误信息（调用方要几何就得显式给 w=/h=）。 */
        *w = *h = 0;
        *stride = os;
        *fmt = VVFMT_OPAQUE;
    }
}

static void node_put_super(struct vvideo_sb *sb) { vv_node_free((struct vv_node *)sb->s_private); }

static const struct vvideo_super_operations g_node_sop = {.put_super = node_put_super};

/** mem/raw 共用的挂载骨架：建节点 → 建 inode → 挂根 */
static struct vvideo_sb *node_mount_common(struct vvideo_fs_type *type, const char *dev,
                                           const char *opts, struct vv_node *n) {
    if (n == NULL) return NULL;
    struct vvideo_sb *sb = vv_alloc_sb(type, &g_node_sop, dev, opts);
    if (sb == NULL) {
        vv_node_free(n);
        return NULL;
    }
    sb->s_private = n;
    n->sb = sb;
    struct vvideo_inode *root = vv_node_inode(n, sb, n->bytes);
    if (root == NULL) {
        vv_node_free(n);
        free(sb);
        return NULL;
    }
    vv_set_root(sb, root);
    return sb;
}

/* ------------------------------------------------------------------ *
 * vvfs-mem：纯内存帧节点（可选从文件灌一次 = 冷启动素材）
 * ------------------------------------------------------------------ */

static struct vvideo_sb *mem_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    unsigned long size = vv_opt_u32(opts, "size", 0);
    uint32_t w = 0, h = 0, fmt = VVFMT_OPAQUE;
    size_t stride = 0;

    /* `prefill=<路径>`：**挂载时读一次**，之后 fd 关掉（冷路径唯一的磁盘 IO） */
    char prefill[VV_DEV_MAX];
    uint8_t *loaded = NULL;
    size_t loaded_len = 0;
    if (vv_opt_str(opts, "prefill", prefill, sizeof(prefill))) {
        int fd = open(prefill, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            vvideo_logf(VV_LOG_ERR, "mem 预灌失败：打不开 %s（errno=%d）", prefill, errno);
            errno = ENOENT;
            return NULL;
        }
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size <= 0) {
            close(fd);
            errno = EIO;
            return NULL;
        }
        loaded = (uint8_t *)malloc((size_t)st.st_size);
        if (loaded != NULL) {
            ssize_t got = read(fd, loaded, (size_t)st.st_size);
            if (got == st.st_size) {
                loaded_len = (size_t)st.st_size;
            } else {
                free(loaded);
                loaded = NULL;
            }
        }
        close(fd); /* ← 关键：素材读完就与磁盘脱钩 */
        if (loaded == NULL) {
            errno = ENOMEM;
            return NULL;
        }
        if (size == 0) size = loaded_len;
    }
    if (size == 0) {
        vvideo_logf(VV_LOG_ERR, "mem 挂载缺参数：需要 size=<字节>（或 prefill=<文件>）");
        free(loaded);
        errno = EINVAL;
        return NULL;
    }
    fill_geometry(opts, (size_t)size, &w, &h, &fmt, &stride);
    struct vv_node *n = vv_node_create(NULL, (size_t)size, w, h, fmt, stride);
    if (n == NULL) {
        free(loaded);
        errno = ENOMEM;
        return NULL;
    }
    if (loaded != NULL) {
        memcpy(n->data, loaded, loaded_len < n->bytes ? loaded_len : n->bytes);
        free(loaded);
        n->ts_ns = vv_now_ns();
    }
    n->always_ready = true; /* 静态源：wait 立刻返回，不制造虚假节拍 */
    n->writable = true;     /* 允许生产者往里面写（写满即发布） */
    struct vvideo_sb *sb = node_mount_common(type, dev, opts, n);
    if (sb != NULL) {
        vvideo_logf(VV_LOG_INFO, "mem 就绪：%zu 字节 %ux%u fmt=%u stride=%zu%s", n->bytes, w, h, fmt,
                    stride, loaded_len ? "（冷启动从文件灌过一次，fd 已关）" : "（纯匿名内存）");
    }
    return sb;
}

/* ------------------------------------------------------------------ *
 * vvfs-raw：把"一个文件 = 一份素材"显式化
 *
 * 与 mem+prefill 的数据面完全一样，单独给一个类型名是因为**语义不同**：
 * 说 `raw` 就是在说"这份内容的来源是那个文件"，日志与 ctl 里一眼看得出来。
 * ------------------------------------------------------------------ */

static struct vvideo_sb *raw_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    if (dev == NULL || dev[0] == '\0') {
        vvideo_logf(VV_LOG_ERR, "raw 挂载缺 dev=<文件路径>");
        errno = EINVAL;
        return NULL;
    }
    /* `dev` 就是"上电素材"：转成 mem 的 prefill —— 数据面与 mem 完全相同
     * （挂载时读一次、立刻关 fd），单独留一个类型名只为让日志与 ctl 里一眼看出
     * 这份内容的来源是一个文件。 */
    char merged[VV_OPTS_MAX];
    snprintf(merged, sizeof(merged), "prefill=%s%s%s", dev, (opts && opts[0]) ? "," : "",
             opts ? opts : "");
    return mem_mount(type, dev, merged);
}

struct vvideo_fs_type vv_type_mem = {.name = "mem", .mount = mem_mount};
struct vvideo_fs_type vv_type_raw = {.name = "raw", .mount = raw_mount};
