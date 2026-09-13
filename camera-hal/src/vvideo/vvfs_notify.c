/*
 * vvfs-notify —— 纯事件节点（"VVideo 里的 eventfd"）。
 *
 * ## 为什么需要一个没有数据的节点
 *
 * 本层此前只有**数据面**的节点（mem/raw/gen/shm/xform）。但真机上有一类信号与"有没有帧"
 * 无关，却决定了热路径能不能及时跟上：**相机流的配置变了**。
 *
 * 现在的注入线程是这么发现这件事的（camshim 老代码）：
 *
 *     if (announced && now - last_scan >= 5) {   // 每 5 秒无条件重扫
 *         munmap 全部目标; n = 0;
 *     }
 *
 * 两个毛病：
 *   1. **慢**：预览 → 录像会换一批缓冲（尺寸都不同），最坏要等 5 秒才跟上，
 *      这段时间注入线程抱着旧缓冲写 —— 实测症状就是"切到录像后预览恢复成实时画面"；
 *   2. **贵且抖**：这 5 秒一到就无条件 munmap + 重建，即使什么都没变也需要重新
 *      mmap 一批 4MB 的 dmabuf —— **每 5 秒一次的周期性抖动**，本来就是白给的。
 *
 * 而"流配置变了"这件事我们**本来就精确知道**：configureStreams 那条路上已经有我们的钩子
 * （`scrub_ol_vaddr`）。所以正确做法不是"更勤快地轮询"，而是**在那条路上发一次事件**。
 *
 * ## 语义（照 eventfd，**刻意不做计数**）
 *
 *  · `write(f, 任意字节, n)` / `VVIO_NOTIFY` = 发一次信号，返回 n；
 *  · `vvideo_wait(f, 超时)` = 睡到**下一次**信号（`timeout=0` 是非阻塞探询，零系统调用）；
 *  · **信号不累积**：只比较事件字的 seq 有没有变。理由：这类信号的语义是"去重扫一次"，
 *    攒了 10 个信号和攒 1 个要做的事完全一样 —— 累积只会让消费者醒来做 10 次无用功。
 *  · `read()` 返回 0（EOF）：它没有数据可读，别让调用方以为能读到东西。
 *
 * ## 事件字为什么放在堆上（而不是共享内存）
 *
 * 生产者与消费者**在同一个进程里**（configureStreams 钩子 与 注入线程都在 HAL 进程），
 * 所以不需要共享映射；但事件字本身仍然是 `struct vvideo_event`，
 * 于是"要用的时候换成共享内存里的那一个"是改一行的事（`shm` 后端就是这么做的）。
 */
#define _GNU_SOURCE
#include "vvideo_internal.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct notify_priv {
    struct vvideo_event *ev;
};

/** 每个打开实例的消费位点（futex 要"和上次看到的值比"，所以必须按 file 记） */
struct notify_state {
    uint32_t last_seq;
};

static int notify_open(struct vvideo_inode *inode, struct vvideo_file *f) {
    struct notify_priv *p = (struct notify_priv *)inode->i_private;
    struct notify_state *st = (struct notify_state *)calloc(1, sizeof(*st));
    if (st == NULL) return -ENOMEM;
    if (p != NULL && p->ev != NULL) {
        f->f_event = p->ev;
        st->last_seq = vv_event_seq(p->ev);
    }
    f->f_private = st;
    return 0;
}

static int notify_release(struct vvideo_inode *inode, struct vvideo_file *f) {
    (void)inode;
    free(f->f_private);
    f->f_private = NULL;
    f->f_event = NULL;
    return 0;
}

/** 没有数据可读：返回 0（EOF）而不是错误 —— 列表/读文本的调用方不该被它绊倒 */
static ssize_t notify_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos) {
    (void)f;
    (void)buf;
    (void)n;
    (void)pos;
    return 0;
}

/** 写任意字节 = 发一次信号（照 eventfd）；返回写入的字节数 */
static ssize_t notify_write(struct vvideo_file *f, const void *buf, size_t n, off_t *pos) {
    (void)buf;
    if (pos != NULL) *pos += (off_t)n;
    struct notify_priv *p = (struct notify_priv *)f->f_inode->i_private;
    if (p != NULL && p->ev != NULL) vv_event_wake(p->ev);
    return (ssize_t)n;
}

static int notify_wait(struct vvideo_file *f, int timeout_ms) {
    struct notify_priv *p = (struct notify_priv *)f->f_inode->i_private;
    if (p == NULL || p->ev == NULL) return -ENODEV;
    struct notify_state *st = (struct notify_state *)f->f_private;
    uint32_t last = st != NULL ? st->last_seq : 0;
    uint32_t cur = vv_event_seq(p->ev);
    if (cur != last) {
        if (st != NULL) st->last_seq = cur;
        return 0;
    }
    if (timeout_ms == 0) return -EAGAIN; /* 非阻塞探询：不睡、不 syscall */
    int rc = vv_event_wait(p->ev, last, timeout_ms);
    if (rc == 0 && st != NULL) st->last_seq = vv_event_seq(p->ev);
    return rc;
}

static int notify_notify(struct vvideo_file *f) {
    struct notify_priv *p = (struct notify_priv *)f->f_inode->i_private;
    if (p == NULL || p->ev == NULL) return -ENODEV;
    vv_event_wake(p->ev);
    return 0;
}

static long notify_ioctl(struct vvideo_file *f, unsigned cmd, void *arg) {
    struct notify_priv *p = (struct notify_priv *)f->f_inode->i_private;
    if (p == NULL) return -ENODEV;
    switch (cmd) {
        case VVIO_QUERYCAP: {
            struct vvideo_cap *c = (struct vvideo_cap *)arg;
            if (c == NULL) return -EINVAL;
            memset(c, 0, sizeof(*c));
            snprintf(c->driver, sizeof(c->driver), "vvfs-notify");
            snprintf(c->card, sizeof(c->card), "%s", f->f_dentry ? f->f_dentry->name : "?");
            c->version = VV_ABI_VERSION;
            return 0;
        }
        case VVIO_CTL: {
            struct vvideo_ctl *c = (struct vvideo_ctl *)arg;
            if (c == NULL || c->key == NULL || c->val == NULL) return -EINVAL;
            if (strcmp(c->key, "seq") == 0) {
                snprintf(c->val, c->vallen, "%u", vv_event_seq(p->ev));
                return 0;
            }
            if (strcmp(c->key, "waiters") == 0) {
                snprintf(c->val, c->vallen, "%d", p->ev->waiters);
                return 0;
            }
            return -ENOENT;
        }
        default:
            return -ENOTTY;
    }
}

static const struct vvideo_file_operations g_notify_fops = {
    .open = notify_open,
    .release = notify_release,
    .read = notify_read,
    .write = notify_write,
    .ioctl = notify_ioctl,
    .wait = notify_wait,
    .notify = notify_notify,
};

static void notify_put_super(struct vvideo_sb *sb) {
    struct notify_priv *p = (struct notify_priv *)sb->s_private;
    if (p != NULL) {
        free(p->ev);
        free(p);
    }
    sb->s_private = NULL;
}

static const struct vvideo_super_operations g_notify_sop = {.put_super = notify_put_super};

static struct vvideo_sb *notify_mount(struct vvideo_fs_type *type, const char *dev,
                                      const char *opts) {
    struct vvideo_sb *sb = vv_alloc_sb(type, &g_notify_sop, dev, opts);
    if (sb == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    struct notify_priv *p = (struct notify_priv *)calloc(1, sizeof(*p));
    if (p == NULL) {
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    p->ev = (struct vvideo_event *)calloc(1, sizeof(*p->ev));
    if (p->ev == NULL) {
        free(p);
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    sb->s_private = p;
    struct vvideo_inode *root = vv_alloc_inode(sb, VV_S_IFREG, 0, NULL, &g_notify_fops, p);
    if (root == NULL) {
        free(p->ev);
        free(p);
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    vv_set_root(sb, root);
    vvideo_logf(VV_LOG_INFO, "notify 就绪：%s（写一次 = 发一次信号；等 = 睡到下一次；信号不累积）",
                dev != NULL && dev[0] ? dev : "(无名事件)");
    return sb;
}

struct vvideo_fs_type vv_type_notify = {.name = "notify", .mount = notify_mount};
