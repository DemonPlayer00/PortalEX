/*
 * vvfs-xform —— 显示变换叠加层（VFS 意义上的 stacking，像 ecryptfs/overlayfs）。
 *
 * ## 为什么它必须是一层，而不是散在各处的 if
 *
 * 上一轮真机上一共测出三种变换，每一种都是**烧了一轮实验**换来的：
 *
 *   · 后置预览：`display = rot90cw(buffer)`；
 *   · 前置预览：`display = flipud(rot90cw(buffer))`（手性探针 IoU 0.683 vs 0.352 定的）；
 *   · 前置录像：`display = fliplr(rot90cw(buffer))`。
 *
 * 把它们写成 `if (前面) {...} else if (录像) {...}` 的后果已经出现过：同一份逻辑在
 * "卡片生成脚本"和"HAL 注入"两边各写一遍，改了一边忘了另一边，画面就成了镜像。
 * 做成叠加层之后，变换是**挂载选项**（数据），不是代码路径：
 *
 *     vvideo_mount("xform", "/vvideo/src/front-preview",
 *                  "/vvideo/src/front-preview-buf", "op=flipud+rot90ccw");
 *
 * ## 单遍实现（不是"依次跑 N 遍"）
 *
 * 每个变换都是仿射的：`src = A · dst + b`（坐标反解）。整条链的反解可以**先合成**
 * （一个 2×3 整数矩阵），于是逐像素只需一次矩阵乘 —— 不需要 N 个中间缓冲，
 * 也不会有"N 遍转置"的缓存灾难。链接收的 op 名字与顺序都按"从源到目标"写。
 *
 * ## 事件语义
 *
 *  · 下层是**静态源**（mem/raw/gen）⇒ 默认 `cache=1`：变换**只做一次**，
 *    之后 `always_ready = true`，每帧零成本。这是本项目最常见的用法
 *    （素材定稿、注入只做 memcpy）；
 *  · 下层是**事件源**（shm）⇒ 每帧变换一次，`always_ready = false`，
 *    `wait` 直接转给下层的 file，事件链不断。
 */
#define _GNU_SOURCE
#include "vvideo_internal.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ *
 * 仿射反解：src = A · dst + b
 * ------------------------------------------------------------------ */

struct vv_affine {
    int a, b, c, d;
    long e, f;
};

struct vv_chain {
    struct vv_affine inv;
    uint32_t sw, sh; /* 源几何 */
    uint32_t dw, dh; /* 目标几何 */
    int nops;
    bool single_plane;
};

enum { OP_ROT90CW, OP_ROT90CCW, OP_ROT180, OP_FLIPLR, OP_FLIPUD };

static int op_parse(const char *name) {
    if (strcmp(name, "rot90cw") == 0 || strcmp(name, "rot90") == 0) return OP_ROT90CW;
    if (strcmp(name, "rot90ccw") == 0) return OP_ROT90CCW;
    if (strcmp(name, "rot180") == 0) return OP_ROT180;
    if (strcmp(name, "fliplr") == 0) return OP_FLIPLR;
    if (strcmp(name, "flipud") == 0) return OP_FLIPUD;
    return -1;
}

/** 该 op 的反解（给定它**输入**的宽高） */
static struct vv_affine op_inverse(int op, uint32_t w, uint32_t h) {
    struct vv_affine m = {1, 0, 0, 1, 0, 0};
    switch (op) {
        case OP_FLIPLR: /* sx = w-1-x' ; sy = y' */
            m.a = -1;
            m.e = (long)w - 1;
            break;
        case OP_FLIPUD: /* sx = x' ; sy = h-1-y' */
            m.d = -1;
            m.f = (long)h - 1;
            break;
        case OP_ROT90CW: /* sx = y' ; sy = h-1-x' */
            m.a = 0;
            m.b = 1;
            m.c = -1;
            m.d = 0;
            m.e = 0;
            m.f = (long)h - 1;
            break;
        case OP_ROT90CCW: /* sx = w-1-y' ; sy = x' */
            m.a = 0;
            m.b = -1;
            m.c = 1;
            m.d = 0;
            m.e = (long)w - 1;
            m.f = 0;
            break;
        case OP_ROT180: /* sx = w-1-x' ; sy = h-1-y' */
            m.a = -1;
            m.d = -1;
            m.e = (long)w - 1;
            m.f = (long)h - 1;
            break;
        default:
            break;
    }
    return m;
}

/** 先应用 `g`，再应用 `f`：`M(x) = f(g(x))` */
static struct vv_affine aff_compose(struct vv_affine f, struct vv_affine g) {
    struct vv_affine m;
    m.a = f.a * g.a + f.b * g.c;
    m.b = f.a * g.b + f.b * g.d;
    m.c = f.c * g.a + f.d * g.c;
    m.d = f.c * g.b + f.d * g.d;
    m.e = (long)f.a * g.e + (long)f.b * g.f + f.e;
    m.f = (long)f.c * g.e + (long)f.d * g.f + f.f;
    return m;
}

static void op_forward_dims(int op, uint32_t *w, uint32_t *h) {
    if (op == OP_ROT90CW || op == OP_ROT90CCW) {
        uint32_t t = *w;
        *w = *h;
        *h = t;
    }
}

/**
 * 解析 `op=a+b+c` 并合成反解矩阵。
 *
 * 顺序按**从源到目标**写（"先 rot90cw 再 flipud"就写 `rot90cw+flipud`），与人的直觉一致；
 * 内部合成的是反解，所以遍历方向相反 —— 这段是整层最容易写错的地方，
 * host 自测里专门有 `rot90cw+rot90ccw == 恒等` 与 `flipud+flipud == 恒等` 两条用例盯着它。
 *
 * ⚠️ **分隔符必须是 `+`，不能是 `,`**：挂载选项本身用逗号分隔（`vv_opt_str` 取到下一个
 * 逗号为止），写 `op=rot90cw,flipud` 只会拿到 `rot90cw` —— 后半个 op 被**静默丢掉**，
 * 表现是"画面对了但方向不对"，而且日志里看不出少了东西。这个坑在自测里露过一次，
 * 所以现在分隔符与选项分隔符刻意错开。
 */
static int chain_build(struct vv_chain *c, const char *opstr, uint32_t w, uint32_t h) {
    int ops[8];
    int n = 0;
    char buf[128];
    snprintf(buf, sizeof(buf), "%s", opstr ? opstr : "");
    char *save = NULL;
    for (char *tok = strtok_r(buf, "+", &save); tok != NULL && n < 8;
         tok = strtok_r(NULL, "+", &save)) {
        int op = op_parse(tok);
        if (op < 0) {
            vvideo_logf(VV_LOG_ERR, "xform 不认识 op=%s（可用 rot90cw/rot90ccw/rot180/fliplr/flipud）",
                        tok);
            return -EINVAL;
        }
        ops[n++] = op;
    }
    if (n == 0) return -EINVAL;

    /* 正向推几何 */
    uint32_t cw = w, ch = h;
    uint32_t dims_w[9], dims_h[9];
    dims_w[0] = w;
    dims_h[0] = h;
    for (int i = 0; i < n; i++) {
        op_forward_dims(ops[i], &cw, &ch);
        dims_w[i + 1] = cw;
        dims_h[i + 1] = ch;
    }
    c->sw = w;
    c->sh = h;
    c->dw = cw;
    c->dh = ch;
    c->nops = n;

    /* 反解合成：M = I_1 ∘ I_2 ∘ … ∘ I_n（先应用 I_n） */
    struct vv_affine m = {1, 0, 0, 1, 0, 0};
    for (int i = n - 1; i >= 0; i--) {
        struct vv_affine ik = op_inverse(ops[i], dims_w[i], dims_h[i]);
        m = aff_compose(ik, m);
    }
    c->inv = m;
    return 0;
}

/* ------------------------------------------------------------------ *
 * 单遍变换
 * ------------------------------------------------------------------ */

/** 把 src（sw×sh，元素 `elem` 字节）按链变换到 dst（dw×dh） */
static void xform_plane(uint8_t *dst, size_t dstride, const uint8_t *src, size_t sstride,
                        const struct vv_chain *c, size_t elem) {
    const struct vv_affine *m = &c->inv;
    for (uint32_t y = 0; y < c->dh; y++) {
        uint8_t *drow = dst + (size_t)y * dstride;
        long sx0 = (long)m->b * (long)y + m->e;
        long sy0 = (long)m->d * (long)y + m->f;
        for (uint32_t x = 0; x < c->dw; x++) {
            long sx = (long)m->a * (long)x + sx0;
            long sy = (long)m->c * (long)x + sy0;
            if (sx < 0 || sy < 0 || (uint32_t)sx >= c->sw || (uint32_t)sy >= c->sh) continue;
            memcpy(drow + (size_t)x * elem, src + (size_t)sy * sstride + (size_t)sx * elem, elem);
        }
    }
}

/* ------------------------------------------------------------------ *
 * 节点
 * ------------------------------------------------------------------ */

struct xform_priv {
    struct vvideo_file *lower;
    struct vv_node *own;
    struct vv_chain chain;
    uint32_t sfmt;
    bool cache;
    bool done;
    /* 色度平面（YUV 源）也一起变换：几何是 Y 的一半 */
    bool with_chroma;
};

static int xform_apply(struct vv_node *n) {
    struct xform_priv *p = (struct xform_priv *)n->priv;
    if (p == NULL || p->lower == NULL) return -ENODEV;
    if (p->cache && p->done) return 0;

    struct vvideo_frame fr;
    int rc = vvideo_acquire(p->lower, &fr);
    if (rc != 0) return rc;
    if (fr.plane[0] == NULL || fr.width == 0 || fr.height == 0) {
        vvideo_release(p->lower, &fr);
        return -EINVAL;
    }
    /* 几何必须与挂载时一致，否则链是错的 —— 宁可什么都不写，也不要写出一幅歪图 */
    if (fr.width != p->chain.sw || fr.height != p->chain.sh) {
        static bool warned = false;
        if (!warned) {
            warned = true;
            vvideo_logf(VV_LOG_WARN, "xform：下层几何变了（挂载时 %ux%u，现在 %ux%u）⇒ 本帧不变换",
                        p->chain.sw, p->chain.sh, fr.width, fr.height);
        }
        vvideo_release(p->lower, &fr);
        return -EAGAIN;
    }
    xform_plane(n->data, n->stride, fr.plane[0], fr.stride[0], &p->chain, 1);
    if (p->with_chroma && fr.plane_count > 1 && fr.plane[1] != NULL) {
        /* 色度是 2 字节/元素、几何减半：同一条链，只是宽高减半 */
        struct vv_chain c2 = p->chain;
        c2.sw = p->chain.sw / 2;
        c2.sh = p->chain.sh / 2;
        c2.dw = p->chain.dw / 2;
        c2.dh = p->chain.dh / 2;
        size_t y_bytes = n->stride * p->chain.dh;
        if (n->bytes > y_bytes) {
            xform_plane(n->data + y_bytes, n->stride, fr.plane[1], fr.stride[1], &c2, 2);
        }
    }
    n->ts_ns = fr.ts_ns;
    vvideo_release(p->lower, &fr);
    p->done = true;
    return 0;
}

static int xform_fill(struct vvideo_file *f, struct vv_node *n) {
    (void)f;
    return xform_apply(n);
}

static int xform_wait(struct vvideo_file *f, int timeout_ms) {
    struct vv_node *n = vv_node_of(f->f_inode);
    struct xform_priv *p = n != NULL ? (struct xform_priv *)n->priv : NULL;
    if (p == NULL) return -ENODEV;
    if (p->cache) return 0; /* 静态：永远就绪 */
    /* 事件链不能断：把等待直接转给下层 file（就是"睡在真正的事件字上"） */
    return p->lower != NULL ? vvideo_wait(p->lower, timeout_ms) : -ENOTTY;
}

static struct vvideo_file_operations g_xform_fops;

static void xform_put_super(struct vvideo_sb *sb) {
    struct vv_node *n = (struct vv_node *)sb->s_private;
    if (n != NULL) {
        struct xform_priv *p = (struct xform_priv *)n->priv;
        if (p != NULL) {
            if (p->lower != NULL) vvideo_close(p->lower);
            free(p);
        }
        n->priv = NULL;
        vv_node_free(n);
    }
}

static const struct vvideo_super_operations g_xform_sop = {.put_super = xform_put_super};

static struct vvideo_sb *xform_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    if (dev == NULL || dev[0] == '\0') {
        vvideo_logf(VV_LOG_ERR, "xform 缺 dev=<下层节点路径>");
        errno = EINVAL;
        return NULL;
    }
    char opstr[128];
    if (!vv_opt_str(opts, "op", opstr, sizeof(opstr))) {
        vvideo_logf(VV_LOG_ERR, "xform 缺 op=<变换链>（如 op=flipud+rot90ccw）");
        errno = EINVAL;
        return NULL;
    }
    struct vvideo_file *lower = vvideo_open(dev, VV_O_RDONLY);
    if (lower == NULL) {
        vvideo_logf(VV_LOG_ERR, "xform 打不开下层 %s（errno=%d）", dev, vv_errno);
        errno = vv_errno ? vv_errno : ENOENT;
        return NULL;
    }
    struct vvideo_frame fr;
    int rc = vvideo_acquire(lower, &fr);
    if (rc != 0) {
        vvideo_logf(VV_LOG_ERR, "xform 下层 %s 取不到帧（rc=%d）", dev, rc);
        vvideo_close(lower);
        errno = -rc;
        return NULL;
    }
    struct vv_chain chain;
    rc = chain_build(&chain, opstr, fr.width, fr.height);
    uint32_t sfmt = fr.format;
    vvideo_release(lower, &fr);
    if (rc != 0) {
        vvideo_close(lower);
        errno = EINVAL;
        return NULL;
    }
    size_t stride = chain.dw;
    /* 输出大小跟着**格式**走：不透明源（.raw 素材就是这种）只有 Y 那么多字节，
     * 按 NV12 多留半帧会让 `read_all()` 多吐出一截无意义的尾巴。 */
    bool opaque = (sfmt == VVFMT_OPAQUE || sfmt == VVFMT_RAW10 || sfmt == VVFMT_JPEG);
    uint32_t dfmt = opaque ? sfmt : VVFMT_NV12;
    size_t bytes = stride * chain.dh * (opaque ? 1 : 3 / 2);
    struct vv_node *n = vv_node_create(NULL, bytes, chain.dw, chain.dh, dfmt, stride);
    if (n == NULL) {
        vvideo_close(lower);
        errno = ENOMEM;
        return NULL;
    }
    struct xform_priv *p = (struct xform_priv *)calloc(1, sizeof(*p));
    if (p == NULL) {
        vv_node_free(n);
        vvideo_close(lower);
        errno = ENOMEM;
        return NULL;
    }
    p->lower = lower;
    p->own = n;
    p->chain = chain;
    p->sfmt = sfmt;
    unsigned long cache = vv_opt_u32(opts, "cache", 1);
    p->cache = cache != 0;
    p->with_chroma = vv_opt_u32(opts, "chroma", 0) != 0;
    n->priv = p;
    n->fill = xform_fill;
    n->always_ready = p->cache;
    n->writable = false;

    g_xform_fops = *vv_node_fops();
    g_xform_fops.wait = xform_wait;

    struct vvideo_sb *sb = vv_alloc_sb(type, &g_xform_sop, dev, opts);
    if (sb == NULL) {
        free(p);
        vv_node_free(n);
        errno = ENOMEM;
        return NULL;
    }
    sb->s_private = n;
    n->sb = sb;
    struct vvideo_inode *root = vv_alloc_inode(sb, VV_S_IFREG, bytes, NULL, &g_xform_fops, n);
    if (root == NULL) {
        free(p);
        vv_node_free(n);
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    vv_set_root(sb, root);
    vvideo_logf(VV_LOG_INFO, "xform 就绪：%s → %ux%u（%s，op=%s%s）", dev, chain.dw, chain.dh,
                p->cache ? "只算一次" : "每帧算", opstr,
                p->with_chroma ? "，含色度平面" : "");
    /* 把合成出来的反解矩阵打出来：这条链最容易写错的就是"谁先谁后"，
     * 有了这一行，画面对不上时第一眼就能分辨"链错了"还是"链对了但源不对"。 */
    vvideo_logf(VV_LOG_DEBUG, "xform 反解矩阵：sx=%d*x%+d*y%+ld  sy=%d*x%+d*y%+ld（源 %ux%u）",
                chain.inv.a, chain.inv.b, chain.inv.e, chain.inv.c, chain.inv.d, chain.inv.f,
                chain.sw, chain.sh);
    return sb;
}

struct vvideo_fs_type vv_type_xform = {.name = "xform", .mount = xform_mount};
