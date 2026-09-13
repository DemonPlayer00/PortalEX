/*
 * VVideo 核 —— 命名空间、挂载表、路径解析、文件操作分发。
 *
 * 这一层**不认识任何具体后端**：它只知道 fs_type / sb / inode / dentry / file 五样东西
 * 和两张操作表。所有"帧从哪来"的知识都在后端里（vvfs_*.c），
 * 加一种新来源 = 加一个文件，不动这里一行。
 *
 * ## 并发模型（一句话）
 *
 * **一把大锁保护命名空间，文件的数据路径不持锁。** 依据：
 *  · 命名空间变更（mount/umount/lookup/mkdir）是**低频**的，用户态毫秒级，锁争用不存在；
 *  · 取帧是**高频**的（30–60 fps，多流并发），持锁会让"注入线程"和"HAL 结果线程"互相等；
 *  · 那为什么没有 use-after-free：**umount 在 s_open_files > 0 时直接 -EBUSY**
 *    （与 VFS 语义一致：有进程持有该 sb 上的文件就卸载不掉）。
 *    所以"正在读的文件"本身就保证它的 sb/inode 不会被释放 —— 不需要引用计数式 RCU。
 *
 * ## 内存归属
 *
 *  · sb / inode / dentry 归**核**所有：挂载时建，卸载时由核递归释放；
 *  · 后端的私有状态一律挂 `s_private` / `i_private`，由后端的 `put_super` 释放；
 *  · `struct vvideo_file` 归调用者所有（`vvideo_open` 返回、`vvideo_close` 释放）。
 */
#define _GNU_SOURCE
#include "vvideo.h"
#include "vvideo_internal.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int vv_errno = 0;

/**
 * 文本缓存：核挂在 `file->f_core` 上的私有状态（seq_file 的等价物）。
 *
 * 为什么归核而不归后端：`read()` 会被按 4KB 分片调用多次，而"生成一次、之后切片"
 * 这件事与后端无关 —— 放在核里，每个文本节点（ctl 的每一个）都不用重复实现。
 */
struct vv_text_state {
    struct vv_seq s;
    bool done;
};

/* ------------------------------------------------------------------ *
 * 日志 + 记账
 *
 * 为什么要自己留一份环形日志：HAL 进程的 logcat 在本机经常抓不全（本项目的既定事实），
 * 而"为什么没接管"这类问题必须能在设备上**当场**读出来 —— `cat /vvideo/ctl/log`（或
 * 启动时打印一次）比事后捞 logcat 可靠得多。
 * ------------------------------------------------------------------ */

#define VV_LOG_SLOTS 32
#define VV_LOG_LEN 192

static struct {
    unsigned seq;
    char text[VV_LOG_SLOTS][VV_LOG_LEN];
} g_log;
static unsigned g_log_next = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

#define VV_LOCK() pthread_mutex_lock(&g_lock)
#define VV_UNLOCK() pthread_mutex_unlock(&g_lock)

#ifndef VVVIDEO_HOST
#include <android/log.h>
#define VV_EMIT(prio, tag, s) \
    __android_log_print((prio) == VV_LOG_ERR ? ANDROID_LOG_ERROR : (prio) == VV_LOG_WARN \
                                                                    ? ANDROID_LOG_WARN  \
                                                                    : ANDROID_LOG_INFO, \
                        tag, "%s", (s))
#else
#define VV_EMIT(prio, tag, s)                                              \
    do {                                                                   \
        fprintf(stderr, "[%s] %s\n", (tag), (s));                          \
    } while (0)
#endif

void vvideo_logf(int prio, const char *fmt, ...) {
    char line[VV_LOG_LEN];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);

    VV_LOCK();
    unsigned slot = g_log_next % VV_LOG_SLOTS;
    g_log_next++;
    g_log.seq = g_log_next;
    snprintf(g_log.text[slot], VV_LOG_LEN, "%s", line);
    VV_UNLOCK();

    VV_EMIT(prio, "VVVideo", line);
}

int vvideo_log_text(char *buf, size_t cap, size_t *out_len) {
    if (buf == NULL || cap == 0) return -EINVAL;
    size_t n = 0;
    VV_LOCK();
    unsigned start = g_log_next > VV_LOG_SLOTS ? g_log_next - VV_LOG_SLOTS : 0;
    for (unsigned i = start; i < g_log_next; i++) {
        const char *t = g_log.text[i % VV_LOG_SLOTS];
        int w = snprintf(buf + n, cap - n, "%06u %s\n", i, t);
        if (w < 0 || (size_t)w >= cap - n) break;
        n += (size_t)w;
    }
    VV_UNLOCK();
    if (out_len) *out_len = n;
    return 0;
}

/* ------------------------------------------------------------------ *
 * 内部对象：dentry 与挂载表
 * ------------------------------------------------------------------ */

struct vvideo_mount {
    struct vvideo_sb *sb;
    struct vvideo_dentry *point; /* 挂载点 dentry */
    struct vvideo_mount *next;
};

static struct vvideo_dentry *g_root = NULL;   /* rootfs 的根 */
static struct vvideo_sb *g_root_sb = NULL;
static struct vvideo_mount *g_mounts = NULL;
static struct vvideo_fs_type *g_fs = NULL;
static uint64_t g_ino_next = 1;
static bool g_inited = false;

static struct vvideo_dentry *effective(struct vvideo_dentry *d) {
    /* 走一层就跨过一层挂载；链式挂载（在挂载点里再挂）也成立 */
    while (d != NULL && d->mounted != NULL && d->mounted->s_root_dentry != NULL) {
        d = d->mounted->s_root_dentry;
    }
    return d;
}

static struct vvideo_dentry *find_child(struct vvideo_dentry *d, const char *name) {
    for (struct vvideo_dentry *c = d->child; c != NULL; c = c->next) {
        if (strcmp(c->name, name) == 0) return c;
    }
    return NULL;
}

/* ------------------------------------------------------------------ *
 * 分配器（后端也用）
 * ------------------------------------------------------------------ */

struct vvideo_inode *vv_alloc_inode(struct vvideo_sb *sb, uint32_t mode, size_t size,
                                    const struct vvideo_inode_operations *i_op,
                                    const struct vvideo_file_operations *fop, void *priv) {
    struct vvideo_inode *i = (struct vvideo_inode *)calloc(1, sizeof(*i));
    if (i == NULL) {
        vv_errno = ENOMEM;
        return NULL;
    }
    i->i_ino = __atomic_add_fetch(&g_ino_next, 1, __ATOMIC_RELAXED);
    i->i_mode = mode;
    i->i_size = size;
    i->i_sb = sb;
    i->i_op = i_op;
    i->i_fop = fop;
    i->i_private = priv;
    i->i_ref = 1;
    return i;
}

static struct vvideo_dentry *alloc_dentry(struct vvideo_dentry *parent, const char *name,
                                          struct vvideo_inode *inode) {
    struct vvideo_dentry *d = (struct vvideo_dentry *)calloc(1, sizeof(*d));
    if (d == NULL) return NULL;
    snprintf(d->name, sizeof(d->name), "%s", name);
    d->inode = inode;
    d->parent = parent;
    if (parent != NULL) {
        d->next = parent->child;
        parent->child = d;
    }
    return d;
}

struct vvideo_sb *vv_alloc_sb(struct vvideo_fs_type *type, const struct vvideo_super_operations *op,
                              const char *dev, const char *opts) {
    struct vvideo_sb *sb = (struct vvideo_sb *)calloc(1, sizeof(*sb));
    if (sb == NULL) {
        vv_errno = ENOMEM;
        return NULL;
    }
    sb->s_type = type;
    sb->s_op = op;
    snprintf(sb->s_dev, sizeof(sb->s_dev), "%s", dev ? dev : "");
    snprintf(sb->s_opts, sizeof(sb->s_opts), "%s", opts ? opts : "");
    return sb;
}

void vv_set_root(struct vvideo_sb *sb, struct vvideo_inode *root) {
    sb->s_root = root;
    sb->s_root_dentry = alloc_dentry(NULL, "/", root);
}

/* ------------------------------------------------------------------ *
 * 卸载时的递归释放
 * ------------------------------------------------------------------ */

static void free_dentry_tree(struct vvideo_dentry *d) {
    if (d == NULL) return;
    struct vvideo_dentry *c = d->child;
    while (c != NULL) {
        struct vvideo_dentry *next = c->next;
        /* ⚠️ 递归**自己会释放 c**（含它的 inode），这里绝不能再 free(c) 一次
         * —— 第一版就是这么写的，ASan 当场报了 double-free（selftest 抓到的第一个真 bug）。 */
        free_dentry_tree(c);
        c = next;
    }
    if (d->inode != NULL) free(d->inode);
    free(d);
}

/* ------------------------------------------------------------------ *
 * 后端注册表
 * ------------------------------------------------------------------ */

int vvideo_register_filesystem(struct vvideo_fs_type *fs) {
    if (fs == NULL || fs->name == NULL || fs->mount == NULL) return -EINVAL;
    VV_LOCK();
    for (struct vvideo_fs_type *p = g_fs; p != NULL; p = p->next) {
        if (strcmp(p->name, fs->name) == 0) {
            VV_UNLOCK();
            return -EEXIST;
        }
    }
    fs->next = g_fs;
    g_fs = fs;
    VV_UNLOCK();
    vvideo_logf(VV_LOG_INFO, "已注册文件系统类型：%s", fs->name);
    return 0;
}

int vvideo_unregister_filesystem(const char *name) {
    if (name == NULL) return -EINVAL;
    VV_LOCK();
    struct vvideo_fs_type **pp = &g_fs;
    while (*pp != NULL && strcmp((*pp)->name, name) != 0) pp = &(*pp)->next;
    if (*pp == NULL) {
        VV_UNLOCK();
        return -ENOENT;
    }
    struct vvideo_fs_type *victim = *pp;
    *pp = victim->next;
    VV_UNLOCK();
    victim->next = NULL;
    return 0;
}

struct vvideo_fs_type *vvideo_find_filesystem(const char *name) {
    if (name == NULL) return NULL;
    VV_LOCK();
    struct vvideo_fs_type *hit = NULL;
    for (struct vvideo_fs_type *p = g_fs; p != NULL; p = p->next) {
        if (strcmp(p->name, name) == 0) {
            hit = p;
            break;
        }
    }
    VV_UNLOCK();
    return hit;
}

/* ------------------------------------------------------------------ *
 * 路径解析
 * ------------------------------------------------------------------ */

/**
 * 走一遍路径。失败返回 NULL 且 `vv_errno` = ENOENT/EINVAL。**调用者需持锁**。
 *
 * `follow_mount`：走到终点时若终点本身是挂载点，要不要"穿过去"拿里面那个根。
 *  · **要**（lookup/open/exists）：VFS 语义里挂载**覆盖**挂载点，
 *    `open("/mnt/x")` 打开的是挂载进去的那个文件系统的根。
 *    第一版没穿，结果 `open()` 拿到的是挂载点那个空目录 inode（`i_fop == NULL`）⇒
 *    取帧永远 -ENOTTY。这个 bug 是自测里"gen 取不到帧"那条抓到的。
 *  · **不要**（mount/mkdir）：它们要判"这个挂载点上是不是已经有东西了"，
 *    穿过去就看不到那个判断依据了（也就没法返回 -EBUSY）。
 */
static struct vvideo_dentry *walk_locked(const char *path, bool follow_mount) {
    if (path == NULL || path[0] != '/') {
        vv_errno = EINVAL;
        return NULL;
    }
    struct vvideo_dentry *cur = g_root;
    if (cur == NULL) {
        vv_errno = ENODEV;
        return NULL;
    }
    char buf[VV_PATH_MAX];
    snprintf(buf, sizeof(buf), "%s", path);
    char *save = NULL;
    for (char *tok = strtok_r(buf, "/", &save); tok != NULL; tok = strtok_r(NULL, "/", &save)) {
        if (tok[0] == '\0' || strcmp(tok, ".") == 0) continue;
        if (strcmp(tok, "..") == 0) {
            if (cur->parent != NULL) cur = cur->parent;
            continue;
        }
        if (strlen(tok) >= VV_NAME_MAX) {
            vv_errno = ENAMETOOLONG;
            return NULL;
        }
        struct vvideo_dentry *base = effective(cur);
        if (base == NULL || base->inode == NULL) {
            vv_errno = ENOTDIR;
            return NULL;
        }
        struct vvideo_dentry *c = find_child(base, tok);
        if (c == NULL) {
            /* 动态节点：交给目录的 lookup（ctl 那类节点靠它现造 inode） */
            if ((base->inode->i_mode & VV_S_IFDIR) != 0 && base->inode->i_op != NULL &&
                base->inode->i_op->lookup != NULL) {
                struct vvideo_inode *ni = NULL;
                if (base->inode->i_op->lookup(base->inode, tok, &ni) == 0 && ni != NULL) {
                    ni->i_sb = base->inode->i_sb;
                    c = alloc_dentry(base, tok, ni);
                }
            }
        }
        if (c == NULL) {
            vv_errno = ENOENT;
            return NULL;
        }
        cur = c;
    }
    return follow_mount ? effective(cur) : cur;
}

int vvideo_lookup(const char *path, struct vvideo_inode **out) {
    /* `out == NULL` 是**合法**的：`vvvideo_exists()` 就靠它问"在不在"。
     * （第一版把它当参数错误，结果 exists() 永远返回 0 —— 自测当场抓到。） */
    VV_LOCK();
    struct vvideo_dentry *d = walk_locked(path, true);
    if (d != NULL && out != NULL) *out = d->inode;
    int rc = d != NULL ? 0 : (vv_errno ? -vv_errno : -ENOENT);
    VV_UNLOCK();
    return rc;
}

int vvideo_exists(const char *path) { return vvideo_lookup(path, NULL) == 0 ? 1 : 0; }

int vvideo_mkdir(const char *path) {
    if (path == NULL || path[0] != '/') return -EINVAL;
    VV_LOCK();
    char buf[VV_PATH_MAX];
    snprintf(buf, sizeof(buf), "%s", path);
    /* 找最后一段的父目录 */
    char *slash = strrchr(buf, '/');
    if (slash == NULL) {
        VV_UNLOCK();
        return -EINVAL;
    }
    char *name = slash + 1;
    if (*name == '\0') { /* 结尾带 / ：去掉再看 */
        *slash = '\0';
        slash = strrchr(buf, '/');
        if (slash == NULL) {
            VV_UNLOCK();
            return -EINVAL;
        }
        name = slash + 1;
    }
    *slash = '\0';
    const char *parent_path = buf[0] ? buf : "/";
    struct vvideo_dentry *pd = walk_locked(parent_path, true);
    if (pd == NULL) {
        int rc = -(vv_errno ? vv_errno : ENOENT);
        VV_UNLOCK();
        return rc;
    }
    pd = effective(pd);
    if (pd == NULL || pd->inode == NULL || (pd->inode->i_mode & VV_S_IFDIR) == 0) {
        VV_UNLOCK();
        return -ENOTDIR;
    }
    if (find_child(pd, name) != NULL) {
        VV_UNLOCK();
        return -EEXIST;
    }
    struct vvideo_inode *i = vv_alloc_inode(pd->inode->i_sb, VV_S_IFDIR, 0, NULL, NULL, NULL);
    struct vvideo_dentry *d = i != NULL ? alloc_dentry(pd, name, i) : NULL;
    if (d == NULL) {
        free(i);
        VV_UNLOCK();
        return -ENOMEM;
    }
    VV_UNLOCK();
    return 0;
}

/* ------------------------------------------------------------------ *
 * 挂载 / 卸载
 * ------------------------------------------------------------------ */

static void free_sb_locked(struct vvideo_sb *sb) {
    if (sb->s_op != NULL && sb->s_op->put_super != NULL) sb->s_op->put_super(sb);
    if (sb->s_root_dentry != NULL) free_dentry_tree(sb->s_root_dentry);
    free(sb);
}

struct vvideo_sb *vvideo_mount(const char *type, const char *dir, const char *dev,
                                const char *opts) {
    if (type == NULL || dir == NULL) {
        vv_errno = EINVAL;
        return NULL;
    }
    struct vvideo_fs_type *fs = vvideo_find_filesystem(type);
    if (fs == NULL) {
        vvideo_logf(VV_LOG_ERR, "挂载失败：没有这种文件系统类型 「%s」（%s）", type, dir);
        vv_errno = ENODEV;
        return NULL;
    }

    VV_LOCK();
    /* 不穿挂载：要在**挂载点本身**上判"是否已被占用" */
    struct vvideo_dentry *point = walk_locked(dir, false);
    if (point == NULL) {
        int e = vv_errno ? vv_errno : ENOENT;
        VV_UNLOCK();
        vvideo_logf(VV_LOG_ERR, "挂载失败：挂载点不存在 %s（errno=%d）", dir, e);
        vv_errno = e;
        return NULL;
    }
    if (point->mounted != NULL) {
        VV_UNLOCK();
        vvideo_logf(VV_LOG_ERR, "挂载失败：%s 上已经有东西了", dir);
        vv_errno = EBUSY;
        return NULL;
    }
    if (point->inode == NULL || (point->inode->i_mode & VV_S_IFDIR) == 0) {
        VV_UNLOCK();
        vv_errno = ENOTDIR;
        return NULL;
    }
    VV_UNLOCK();

    /* **不持锁调用后端**：后端 mount() 会 open/mmap 文件，可能慢；
     * 此时挂载点还没挂上，别的线程看不到半成品。 */
    errno = 0; /* 清掉上一次失败留下的 errno，否则下面会报出**别人的**错误码 */
    struct vvideo_sb *sb = fs->mount(fs, dev, opts);
    if (sb == NULL) {
        /* 后端自己设的 errno 才是真相；vv_errno 只是兜底 ——
         * 第一版优先报 vv_errno，结果把上一次失败的 5 报成了这一次的原因（误导性诊断）。 */
        int e = errno != 0 ? errno : (vv_errno ? vv_errno : EIO);
        vvideo_logf(VV_LOG_ERR, "挂载失败：%s 后端 mount() 没给出 super_block（dev=%s errno=%d）",
                    type, dev ? dev : "-", e);
        vv_errno = e;
        return NULL;
    }
    snprintf(sb->s_dir, sizeof(sb->s_dir), "%s", dir);
    if (sb->s_root_dentry == NULL) {
        vv_errno = EINVAL;
        free_sb_locked(sb);
        return NULL;
    }

    VV_LOCK();
    if (point->mounted != NULL) { /* 竞态：别人先挂上了 */
        VV_UNLOCK();
        free_sb_locked(sb);
        vv_errno = EBUSY;
        return NULL;
    }
    point->mounted = sb;
    struct vvideo_mount *m = (struct vvideo_mount *)calloc(1, sizeof(*m));
    if (m == NULL) {
        point->mounted = NULL;
        VV_UNLOCK();
        free_sb_locked(sb);
        vv_errno = ENOMEM;
        return NULL;
    }
    m->sb = sb;
    m->point = point;
    m->next = g_mounts;
    g_mounts = m;
    VV_UNLOCK();

    vvideo_logf(VV_LOG_INFO, "已挂载 %s ← %s（dev=%s opts=%s）", dir, type, dev ? dev : "-",
                (opts && opts[0]) ? opts : "-");
    return sb;
}

/** 逐级建目录（`mkdir -p`）。已存在不算错。 */
static int mkdir_p(const char *path) {
    if (path == NULL || path[0] != '/') return -EINVAL;
    char buf[VV_PATH_MAX];
    snprintf(buf, sizeof(buf), "%s", path);
    for (char *p = buf + 1; *p != '\0'; p++) {
        if (*p != '/') continue;
        *p = '\0';
        vvideo_mkdir(buf);
        *p = '/';
    }
    return vvideo_mkdir(buf);
}

struct vvideo_sb *vvideo_mount_ensure(const char *type, const char *dir, const char *dev,
                                      const char *opts) {
    if (dir != NULL) mkdir_p(dir);
    return vvideo_mount(type, dir, dev, opts);
}

int vvideo_umount(const char *dir) {
    if (dir == NULL) return -EINVAL;
    VV_LOCK();
    struct vvideo_mount **pp = &g_mounts;
    while (*pp != NULL && strcmp((*pp)->sb->s_dir, dir) != 0) pp = &(*pp)->next;
    if (*pp == NULL) {
        VV_UNLOCK();
        return -ENOENT;
    }
    struct vvideo_mount *m = *pp;
    if (m->sb->s_open_files > 0) {
        int n = m->sb->s_open_files;
        VV_UNLOCK();
        vvideo_logf(VV_LOG_WARN, "卸载 %s 被拒：还有 %d 个文件开着（-EBUSY）", dir, n);
        return -EBUSY;
    }
    *pp = m->next;
    m->point->mounted = NULL;
    m->sb->s_dead = true;
    struct vvideo_sb *sb = m->sb;
    free(m);
    VV_UNLOCK();

    free_sb_locked(sb);
    vvideo_logf(VV_LOG_INFO, "已卸载 %s", dir);
    return 0;
}

int vvideo_mounts_text(char *buf, size_t cap, size_t *out_len) {
    if (buf == NULL || cap == 0) return -EINVAL;
    size_t n = 0;
    VV_LOCK();
    for (struct vvideo_mount *m = g_mounts; m != NULL; m = m->next) {
        int w = snprintf(buf + n, cap - n, "%s %s %s %s 0 0\n", m->sb->s_dev,
                         m->sb->s_dir, m->sb->s_type->name, m->sb->s_opts);
        if (w < 0 || (size_t)w >= cap - n) break;
        n += (size_t)w;
    }
    VV_UNLOCK();
    if (out_len) *out_len = n;
    return 0;
}

/* ------------------------------------------------------------------ *
 * 文件
 * ------------------------------------------------------------------ */

struct vvideo_file *vvideo_open(const char *path, int flags) {
    VV_LOCK();
    struct vvideo_dentry *d = walk_locked(path, true);
    if (d == NULL) {
        int e = vv_errno ? vv_errno : ENOENT;
        VV_UNLOCK();
        vvideo_logf(VV_LOG_WARN, "打不开 %s（errno=%d）", path ? path : "(null)", e);
        vv_errno = e;
        return NULL;
    }
    struct vvideo_inode *i = d->inode;
    if (i == NULL) {
        VV_UNLOCK();
        vv_errno = ENOENT;
        return NULL;
    }
    if ((i->i_mode & VV_S_IFDIR) != 0 && (flags & VV_O_WRONLY) != 0) {
        VV_UNLOCK();
        vv_errno = EISDIR;
        return NULL;
    }
    struct vvideo_file *f = (struct vvideo_file *)calloc(1, sizeof(*f));
    if (f == NULL) {
        VV_UNLOCK();
        vv_errno = ENOMEM;
        return NULL;
    }
    f->f_dentry = d;
    f->f_inode = i;
    f->f_op = i->i_fop;
    f->f_flags = (unsigned)flags;
    f->f_ref = 1;
    if (i->i_sb != NULL) i->i_sb->s_open_files++;
    i->i_stat.opens++;
    VV_UNLOCK();

    if (f->f_op != NULL && f->f_op->open != NULL) {
        int rc = f->f_op->open(i, f);
        if (rc != 0) {
            vvideo_close(f);
            vv_errno = -rc;
            return NULL;
        }
    }
    return f;
}

int vvideo_close(struct vvideo_file *f) {
    if (f == NULL) return -EINVAL;
    f->f_ref--;
    if (f->f_ref > 0) return 0;
    if (f->f_op != NULL && f->f_op->release != NULL) f->f_op->release(f->f_inode, f);
    if (f->f_core != NULL) { /* 核自己挂在文件上的文本缓存 */
        struct vv_text_state *st = (struct vv_text_state *)f->f_core;
        vv_seq_free(&st->s);
        free(st);
        f->f_core = NULL;
    }
    VV_LOCK();
    if (f->f_inode != NULL && f->f_inode->i_sb != NULL && f->f_inode->i_sb->s_open_files > 0) {
        f->f_inode->i_sb->s_open_files--;
    }
    VV_UNLOCK();
    free(f);
    return 0;
}

/* ------------------------------------------------------------------ *
 * 文本节点（seq_file 的最小实现）
 * ------------------------------------------------------------------ */

void vv_seq_init(struct vv_seq *s) {
    s->buf = NULL;
    s->len = 0;
    s->cap = 0;
}

void vv_seq_free(struct vv_seq *s) {
    free(s->buf);
    s->buf = NULL;
    s->len = s->cap = 0;
}

void vv_seq_printf(struct vv_seq *s, const char *fmt, ...) {
    /*
     * ⚠️ **必须先问"需要多少字节"，再扩容，最后才写。**
     *
     * 第一版是"先按 1KB 起步、满了翻倍，再 vsnprintf"：当一次写入（比如把整棵
     * 命名空间的 2KB 文本用 `%s` 塞进来）**超过当前容量**时，vsnprintf 返回的是
     * "本来需要多少"（2048），而缓冲区只写进去 1023 —— 代码却按返回值累加并钳到
     * 容量上，于是文本被**静默截断**在 1023 字节。
     * 表现：`ctl/tree`、`ctl/log` 永远只有一屏，看起来"就是这样"，实际是丢数据。
     * 这正是本项目最怕的那类故障"错了但不报错"，所以在这里钉死：两趟 vsnprintf。
     */
    va_list ap, ap2;
    va_start(ap, fmt);
    va_copy(ap2, ap);
    int need = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (need < 0) {
        va_end(ap2);
        return;
    }
    size_t want = s->len + (size_t)need + 1;
    if (want > s->cap) {
        size_t ncap = s->cap ? s->cap : 256;
        while (ncap < want) ncap *= 2;
        char *nb = (char *)realloc(s->buf, ncap);
        if (nb == NULL) { /* 分配失败就丢这一条：观测文本不值得为它冒更大风险 */
            va_end(ap2);
            return;
        }
        s->buf = nb;
        s->cap = ncap;
    }
    vsnprintf(s->buf + s->len, s->cap - s->len, fmt, ap2);
    va_end(ap2);
    s->len += (size_t)need;
}

int vv_text_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos,
                 int (*gen)(struct vvideo_file *f, struct vv_seq *s, void *priv), void *priv) {
    struct vv_text_state *st = (struct vv_text_state *)f->f_core;
    if (st == NULL) {
        st = (struct vv_text_state *)calloc(1, sizeof(*st));
        if (st == NULL) return -ENOMEM;
        vv_seq_init(&st->s);
        f->f_core = st;
    }
    if (!st->done) {
        int rc = gen(f, &st->s, priv);
        st->done = true;
        if (rc != 0) return rc;
    }
    off_t p = pos != NULL ? *pos : 0;
    if (p < 0) return -EINVAL;
    if ((size_t)p >= st->s.len) return 0;
    size_t avail = st->s.len - (size_t)p;
    size_t take = avail < n ? avail : n;
    memcpy(buf, st->s.buf + p, take);
    if (pos != NULL) *pos += (off_t)take;
    return (ssize_t)take;
}

/** 目录默认文本读：把子节点与后端 i_op->iterate 的内容拼起来 */
static int dir_gen(struct vvideo_file *f, struct vv_seq *s, void *priv) {
    (void)priv;
    struct vvideo_inode *i = f->f_inode;
    if (i == NULL) return -EIO;
    if (i->i_op != NULL && i->i_op->iterate != NULL) {
        size_t len = 0;
        char tmp[4096];
        if (i->i_op->iterate(i, tmp, sizeof(tmp), &len) == 0 && len > 0) {
            tmp[len < sizeof(tmp) - 1 ? len : sizeof(tmp) - 1] = '\0';
            vv_seq_printf(s, "%s", tmp);
        }
    }
    VV_LOCK();
    struct vvideo_dentry *d = effective(f->f_dentry);
    if (d != NULL) {
        for (struct vvideo_dentry *c = d->child; c != NULL; c = c->next) {
            bool isdir = c->inode != NULL && (c->inode->i_mode & VV_S_IFDIR) != 0;
            size_t size = c->inode ? c->inode->i_size : 0;
            vv_seq_printf(s, "%s %-24s %10zu%s\n", isdir ? "d" : "-", c->name, size,
                          c->mounted != NULL ? "  [mount]" : "");
        }
    }
    VV_UNLOCK();
    return 0;
}

static ssize_t dir_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos) {
    return vv_text_read(f, buf, n, pos, dir_gen, NULL);
}

static const struct vvideo_file_operations g_dir_fops = {
    .read = dir_read,
};

/* ------------------------------------------------------------------ *
 * 读 / 写 / ioctl / 取帧
 * ------------------------------------------------------------------ */

ssize_t vvideo_read(struct vvideo_file *f, void *buf, size_t n) {
    if (f == NULL || buf == NULL) return -EINVAL;
    const struct vvideo_file_operations *op = f->f_op;
    if (op == NULL && f->f_inode != NULL && (f->f_inode->i_mode & VV_S_IFDIR) != 0) {
        op = &g_dir_fops;
    }
    if (op == NULL || op->read == NULL) return -ENOTTY;
    ssize_t rc = op->read(f, buf, n, &f->f_pos);
    if (rc < 0) {
        f->f_inode->i_stat.fails++;
        f->f_inode->i_stat.last_errno = (int)-rc;
        vv_errno = (int)-rc;
    } else {
        f->f_inode->i_stat.bytes += (unsigned long)rc;
    }
    return rc;
}

ssize_t vvideo_write(struct vvideo_file *f, const void *buf, size_t n) {
    if (f == NULL || buf == NULL) return -EINVAL;
    const struct vvideo_file_operations *op = f->f_op;
    if (op == NULL || op->write == NULL) return -ENOTTY;
    ssize_t rc = op->write(f, buf, n, &f->f_pos);
    if (rc < 0) {
        f->f_inode->i_stat.fails++;
        f->f_inode->i_stat.last_errno = (int)-rc;
        vv_errno = (int)-rc;
    } else {
        f->f_inode->i_stat.bytes += (unsigned long)rc;
    }
    return rc;
}

long vvideo_ioctl(struct vvideo_file *f, unsigned cmd, void *arg) {
    if (f == NULL) return -EINVAL;
    if (cmd == VVIO_ACQUIRE) return vvideo_acquire(f, (struct vvideo_frame *)arg);
    if (cmd == VVIO_RELEASE) return vvideo_release(f, (struct vvideo_frame *)arg);
    if (cmd == VVIO_WAIT) {
        struct vvideo_wait *w = (struct vvideo_wait *)arg;
        return vvideo_wait(f, w != NULL ? w->timeout_ms : 0);
    }
    if (cmd == VVIO_NOTIFY) return vvideo_notify(f);
    if (cmd == VVIO_EVENT) {
        if (arg == NULL) return -EINVAL;
        *(struct vvideo_event **)arg = f->f_event;
        return f->f_event != NULL ? 0 : -ENOTTY;
    }
    if (cmd == VVIO_STAT) {
        if (arg == NULL) return -EINVAL;
        *(struct vvideo_fsstat *)arg = f->f_inode->i_stat;
        return 0;
    }
    const struct vvideo_file_operations *op = f->f_op;
    if (op == NULL || op->ioctl == NULL) return -ENOTTY;
    long rc = op->ioctl(f, cmd, arg);
    if (rc < 0) {
        f->f_inode->i_stat.fails++;
        f->f_inode->i_stat.last_errno = (int)-rc;
    }
    return rc;
}

int vvideo_acquire(struct vvideo_file *f, struct vvideo_frame *out) {
    if (f == NULL || out == NULL) return -EINVAL;
    memset(out, 0, sizeof(*out));
    const struct vvideo_file_operations *op = f->f_op;
    if (op == NULL || op->acquire == NULL) {
        f->f_inode->i_stat.fails++;
        f->f_inode->i_stat.last_errno = ENOTTY;
        return -ENOTTY;
    }
    int rc = op->acquire(f, out);
    if (rc != 0) {
        f->f_inode->i_stat.fails++;
        f->f_inode->i_stat.last_errno = -rc;
        vv_errno = -rc;
        return rc;
    }
    f->f_seq++;
    out->seq = f->f_seq;
    out->owner = f;
    f->f_frame_held = true;
    f->f_inode->i_stat.acquires++;
    f->f_inode->i_stat.bytes += (unsigned long)(out->plane_count > 0 ? out->size[0] : 0);
    return 0;
}

int vvideo_release(struct vvideo_file *f, struct vvideo_frame *fr) {
    if (f == NULL) return -EINVAL;
    const struct vvideo_file_operations *op = f->f_op;
    int rc = 0;
    if (fr != NULL && op != NULL && op->release_frame != NULL) rc = op->release_frame(f, fr);
    f->f_frame_held = false;
    return rc;
}

/**
 * 事件驱动等待 —— **本层替代轮询的唯一入口**。
 *
 * 语义（照 `poll()` 收敛过）：
 *  · `timeout_ms < 0`：一直等；
 *  · `timeout_ms == 0`：**只看一眼**（非阻塞探询），没有新事件立刻 -EAGAIN；
 *  · 返回 0 = 有新事件可读；-EAGAIN = 超时；-ESHUTDOWN = 生产者已退出；
 *  · 节点没有事件字（既没有 `wait` 也没有 `f_event`）⇒ -ENOTTY，调用方应当
 *    退化成"直接 acquire"而不是转去忙等。
 */
int vvideo_wait(struct vvideo_file *f, int timeout_ms) {
    if (f == NULL) return -EINVAL;
    if (f->f_inode != NULL) f->f_inode->i_stat.waits++;
    const struct vvideo_file_operations *op = f->f_op;
    int rc;
    if (op != NULL && op->wait != NULL) {
        rc = op->wait(f, timeout_ms);
    } else if (f->f_event != NULL) {
        if (f->f_event->flags & VV_EV_ALWAYS_READY) {
            rc = 0;
        } else if (timeout_ms == 0) {
            rc = -EAGAIN;
        } else {
            rc = vv_event_wait(f->f_event, 0, timeout_ms);
        }
    } else {
        rc = -ENOTTY;
    }
    if (f->f_inode != NULL) {
        if (rc == 0) {
            f->f_inode->i_stat.wakes++;
        } else if (rc != -EAGAIN && rc != -ENOTTY) {
            f->f_inode->i_stat.last_errno = -rc;
        }
    }
    return rc;
}

/** 生产者侧唤醒（写端调用；只读端调它没有副作用） */
int vvideo_notify(struct vvideo_file *f) {
    if (f == NULL) return -EINVAL;
    const struct vvideo_file_operations *op = f->f_op;
    if (op != NULL && op->notify != NULL) return op->notify(f);
    if (f->f_event != NULL) {
        vv_event_wake(f->f_event);
        return 0;
    }
    return -ENOTTY;
}

ssize_t vvideo_read_all(const char *path, void **out_buf, size_t *out_len) {
    if (out_buf == NULL || out_len == NULL) return -EINVAL;
    *out_buf = NULL;
    *out_len = 0;
    struct vvideo_file *f = vvideo_open(path, VV_O_RDONLY);
    if (f == NULL) return vv_errno ? -vv_errno : -ENOENT;
    size_t cap = 64 * 1024, len = 0;
    uint8_t *buf = (uint8_t *)malloc(cap);
    if (buf == NULL) {
        vvideo_close(f);
        return -ENOMEM;
    }
    for (;;) {
        if (len == cap) {
            size_t ncap = cap * 2;
            uint8_t *nb = (uint8_t *)realloc(buf, ncap);
            if (nb == NULL) {
                free(buf);
                vvideo_close(f);
                return -ENOMEM;
            }
            buf = nb;
            cap = ncap;
        }
        ssize_t r = vvideo_read(f, buf + len, cap - len);
        if (r < 0) {
            free(buf);
            vvideo_close(f);
            return r;
        }
        if (r == 0) break;
        len += (size_t)r;
    }
    vvideo_close(f);
    *out_buf = buf;
    *out_len = len;
    return (ssize_t)len;
}

int vvideo_readdir(const char *path, char *buf, size_t cap, size_t *out_len) {
    if (buf == NULL || cap == 0) return -EINVAL;
    struct vvideo_file *f = vvideo_open(path, VV_O_RDONLY);
    if (f == NULL) return vv_errno ? -vv_errno : -ENOENT;
    ssize_t r = vvideo_read(f, buf, cap - 1);
    vvideo_close(f);
    if (r < 0) return (int)r;
    buf[r] = '\0';
    if (out_len) *out_len = (size_t)r;
    return 0;
}

/* ------------------------------------------------------------------ *
 * 观测
 * ------------------------------------------------------------------ */

/** 递归打印。**调用者必须持锁**（大锁不可重入，所以递归不能自己再锁一次） */
static void tree_walk_locked(struct vv_seq *s, struct vvideo_dentry *d, int depth) {
    char indent[64];
    int n = depth * 2;
    if (n > 60) n = 60;
    memset(indent, ' ', (size_t)n);
    indent[n] = '\0';
    for (struct vvideo_dentry *c = d->child; c != NULL; c = c->next) {
        bool isdir = c->inode != NULL && (c->inode->i_mode & VV_S_IFDIR) != 0;
        vv_seq_printf(s, "%s%s %s", indent, isdir ? "d" : "-", c->name);
        if (!isdir && c->inode != NULL && c->inode->i_size > 0) {
            vv_seq_printf(s, "  (%zu 字节)", c->inode->i_size);
        }
        if (c->mounted != NULL) {
            vv_seq_printf(s, "  [挂载 %s dev=%s opts=%s]",
                          c->mounted->s_type ? c->mounted->s_type->name : "?",
                          c->mounted->s_dev[0] ? c->mounted->s_dev : "-",
                          c->mounted->s_opts[0] ? c->mounted->s_opts : "-");
        }
        if (c->inode != NULL) {
            const struct vvideo_fsstat *st = &c->inode->i_stat;
            if (st->acquires || st->fails || st->waits) {
                vv_seq_printf(s, "  取帧=%lu 等待=%lu 失败=%lu", st->acquires, st->waits, st->fails);
                if (st->last_errno) vv_seq_printf(s, " 最后错误=%d", st->last_errno);
            }
        }
        vv_seq_printf(s, "\n");
        if (isdir) {
            struct vvideo_dentry *sub = effective(c);
            if (sub != NULL) tree_walk_locked(s, sub, depth + 1);
        }
    }
}

void *vvideo_root_singleton(void) { return g_root_sb; }

int vvideo_tree_text(char *buf, size_t cap, size_t *out_len) {
    if (buf == NULL || cap == 0) return -EINVAL;
    struct vv_seq s;
    vv_seq_init(&s);
    vv_seq_printf(&s, "/  (vvfs-rootfs)\n");
    VV_LOCK();
    if (g_root != NULL) tree_walk_locked(&s, g_root, 1);
    for (struct vvideo_mount *m = g_mounts; m != NULL; m = m->next) {
        vv_seq_printf(&s, "mount %s ← %s  dev=%s opts=%s  打开文件=%d\n", m->sb->s_dir,
                      m->sb->s_type ? m->sb->s_type->name : "?", m->sb->s_dev[0] ? m->sb->s_dev : "-",
                      m->sb->s_opts[0] ? m->sb->s_opts : "-", m->sb->s_open_files);
    }
    VV_UNLOCK();
    size_t take = s.len < cap - 1 ? s.len : cap - 1;
    memcpy(buf, s.buf ? s.buf : "", take);
    buf[take] = '\0';
    if (out_len) *out_len = take;
    vv_seq_free(&s);
    return 0;
}

/* ------------------------------------------------------------------ *
 * 选项解析
 * ------------------------------------------------------------------ */

unsigned long vv_opt_u32(const char *opts, const char *key, unsigned long def) {
    if (opts == NULL || key == NULL) return def;
    size_t klen = strlen(key);
    const char *p = opts;
    while (*p != '\0') {
        while (*p == ',' || *p == ' ') p++;
        if (strncmp(p, key, klen) == 0 && p[klen] == '=') {
            return strtoul(p + klen + 1, NULL, 0);
        }
        const char *comma = strchr(p, ',');
        if (comma == NULL) break;
        p = comma + 1;
    }
    return def;
}

bool vv_opt_str(const char *opts, const char *key, char *out, size_t cap) {
    if (opts == NULL || key == NULL || out == NULL || cap == 0) return false;
    size_t klen = strlen(key);
    const char *p = opts;
    while (*p != '\0') {
        while (*p == ',' || *p == ' ') p++;
        if (strncmp(p, key, klen) == 0 && p[klen] == '=') {
            const char *v = p + klen + 1;
            const char *end = strchr(v, ',');
            size_t n = end != NULL ? (size_t)(end - v) : strlen(v);
            if (n >= cap) n = cap - 1;
            memcpy(out, v, n);
            out[n] = '\0';
            return true;
        }
        const char *comma = strchr(p, ',');
        if (comma == NULL) break;
        p = comma + 1;
    }
    return false;
}

/* ------------------------------------------------------------------ *
 * 初始化
 * ------------------------------------------------------------------ */

void vvideo_init(void) {
    VV_LOCK();
    if (g_inited) {
        VV_UNLOCK();
        return;
    }
    g_inited = true;
    VV_UNLOCK();

    /* rootfs：核自带的、不可卸载的一层，只用来放"目录"这个骨架 */
    struct vvideo_fs_type *rootfs = (struct vvideo_fs_type *)calloc(1, sizeof(*rootfs));
    if (rootfs == NULL) return;
    rootfs->name = "vvfs-rootfs";
    g_root_sb = vv_alloc_sb(rootfs, NULL, "rootfs", "");
    if (g_root_sb == NULL) return;
    struct vvideo_inode *ri = vv_alloc_inode(g_root_sb, VV_S_IFDIR, 0, NULL, NULL, NULL);
    if (ri == NULL) return;
    vv_set_root(g_root_sb, ri);
    g_root = g_root_sb->s_root_dentry;

    vv_builtin_register();

    int rc = vvideo_mkdir("/vvideo");
    if (rc != 0 && rc != -EEXIST) {
        vvideo_logf(VV_LOG_ERR, "建 /vvideo 失败：%d", rc);
    }
    vvideo_mkdir("/vvideo/src");
    vvideo_mkdir("/vvideo/ctl");
    vvideo_logf(VV_LOG_INFO, "VVideo 已就绪（ABI v%d，已注册后端由 ctl/fs 列出）", VV_ABI_VERSION);
}
