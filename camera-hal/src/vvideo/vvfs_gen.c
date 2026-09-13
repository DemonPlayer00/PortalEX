/*
 * vvfs-gen —— 合成帧源（"视频界的 /dev/zero"）。
 *
 * 为什么要有它：
 *   · 真机上调"注入通路是否活着"时，**素材本身**是最容易被怀疑的一环。
 *     有了它就能把素材排除掉：`/vvideo/src/probe` 挂一个生成器，图案必然正确，
 *     若画面还是不对，问题就一定在写缓冲那一段；
 *   · 它不需要任何外部文件、不碰磁盘、不需要 app 参与 —— 装机即可自证。
 *
 * ## 事件语义
 *
 * `always_ready = true`：帧是**按需算**的，没有定时器线程、没有睡眠、没有轮询。
 * 每次 acquire 前进一帧（`seq` 由核递增），于是调用方拿到的是"活的"图案
 * （移动竖条 / 计数块），一眼能看出帧在不在推进。**刻意不用定时器**：
 * 定时器就是本层要消灭的那种"为了产帧而产帧"的假节拍。
 *
 * ## 静态底图只算一次
 *
 * 这是从 camshim 的实测教训直接搬过来的：逐像素重建底图 **48 ms/帧**，
 * 而"底图算一次 + 每帧只覆盖动态那几行"是 3 ms 级。生成器不许比真实源还贵。
 */
#define _GNU_SOURCE
#include "vvideo_internal.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    GEN_SOLID = 0,
    GEN_GRAD = 1,    /* 横向渐变 + 细横纹 + 移动竖条（与 camshim 的老图案同款） */
    GEN_STRIPES = 2, /* 竖条纹 + 移动的亮带 */
    GEN_CARD = 3,    /* 测试卡：四角手性标记 + 上/下标记 + 中心块 */
    GEN_COUNT = 4    /* 帧计数块：像素里直接写着帧号（十六进制半字节放大） */
};

struct gen_priv {
    uint8_t *base; /* 静态底图（只算一次） */
    uint32_t frame;
    int mode;
    int byte;
    uint32_t margin;
};

/**
 * 测试卡。
 *
 * 为什么要"手性标记"（四角各一个不对称的块）：**任何一次错误的镜像/旋转都会让
 * 四角的相对位置变化**，而纯渐变或条纹在 rot90 与 fliplr 下长得差不多 ——
 * 上一轮真机定显示变换时，就是因为图案本身不辨手性，白白多烧了一轮实验。
 * 四角块边长不同（TL 最大、BR 最小），看一眼就知道自己拿的是哪一角。
 */
static void draw_card(uint8_t *y, size_t stride, uint32_t w, uint32_t h) {
    memset(y, 40, stride * h);
    uint32_t m = w / 16;
    if (m < 8) m = 8;
    /* 外框 */
    for (uint32_t r = 0; r < h; r++) {
        if (r < m || r >= h - m) {
            memset(y + (size_t)r * stride, 220, w);
            continue;
        }
        memset(y + (size_t)r * stride, 220, m);
        memset(y + (size_t)r * stride + w - m, 220, m);
    }
    /* 四角手性块：边长依次 4m / 3m / 2m / m（TL / TR / BL / BR） */
    const uint32_t sizes[4] = {4 * m, 3 * m, 2 * m, m};
    const uint32_t xs[4] = {2 * m, w - 2 * m - sizes[1], 2 * m, w - 2 * m - sizes[3]};
    const uint32_t ys[4] = {2 * m, 2 * m, h - 2 * m - sizes[2], h - 2 * m - sizes[3]};
    const uint8_t vals[4] = {255, 200, 160, 120};
    for (int k = 0; k < 4; k++) {
        if (xs[k] + sizes[k] > w || ys[k] + sizes[k] > h) continue;
        for (uint32_t r = 0; r < sizes[k]; r++) {
            memset(y + (size_t)(ys[k] + r) * stride + xs[k], vals[k], sizes[k]);
        }
    }
    /* 上下标记：顶部一条实心横带（"TOP"），底部一条点划线（"BOTTOM"） */
    uint32_t band = m;
    for (uint32_t r = 0; r < band; r++) {
        memset(y + (size_t)(h / 2 - 4 * m + r) * stride + w / 4, 90, w / 2);
    }
    for (uint32_t r = 0; r < band; r++) {
        uint8_t *row = y + (size_t)(h / 2 + 4 * m + r) * stride;
        for (uint32_t x = w / 4; x < w / 4 + w / 2; x += 4 * m) {
            memset(row + x, 90, 2 * m);
        }
    }
    /* 中心块 */
    for (uint32_t r = 0; r < 2 * m; r++) {
        memset(y + (size_t)(h / 2 - m + r) * stride + w / 2 - m, 30, 2 * m);
    }
}

static void draw_count(uint8_t *y, size_t stride, uint32_t w, uint32_t h, uint32_t frame) {
    /* 画面正中画 8 个半字节块，直接写着帧号低 32 位 —— 抓一帧就能读出"这是第几帧" */
    memset(y, 16, stride * h);
    uint32_t bw = w / 24;
    if (bw < 4) bw = 4;
    uint32_t bh = h / 12;
    if (bh < 4) bh = 4;
    uint32_t total = bw * 8 + bw * 7;
    uint32_t x0 = (w - total) / 2;
    uint32_t y0 = (h - bh) / 2;
    for (int i = 0; i < 8; i++) {
        uint32_t nib = (frame >> (4 * (7 - i))) & 0xF;
        uint32_t x = x0 + (uint32_t)i * (bw + bw);
        for (uint32_t r = 0; r < bh; r++) {
            memset(y + (size_t)(y0 + r) * stride + x, (uint8_t)(16 + nib * 15), bw);
        }
    }
}

static void build_base(struct vv_node *n, struct gen_priv *p) {
    uint32_t w = n->width, h = n->height;
    size_t stride = n->stride;
    p->base = (uint8_t *)malloc(stride * h);
    if (p->base == NULL) return;
    memset(p->base, p->byte, stride * h);
    switch (p->mode) {
        case GEN_GRAD:
            for (uint32_t r = 0; r < h; r++) {
                uint8_t *row = p->base + (size_t)r * stride;
                uint8_t shade = (r & 32) ? 8 : 0;
                for (uint32_t x = 0; x < w; x++) {
                    uint32_t v = 16 + (x * 200u) / (w ? w : 1) / 2 + shade;
                    row[x] = (uint8_t)(v > 235 ? 235 : v);
                }
            }
            break;
        case GEN_STRIPES:
            for (uint32_t r = 0; r < h; r++) {
                uint8_t *row = p->base + (size_t)r * stride;
                for (uint32_t x = 0; x < w; x++) row[x] = (uint8_t)(((x / 32) & 1) ? 200 : 40);
            }
            break;
        case GEN_CARD:
            draw_card(p->base, stride, w, h);
            break;
        default:
            break;
    }
}

static int gen_fill(struct vvideo_file *f, struct vv_node *n) {
    (void)f;
    struct gen_priv *p = (struct gen_priv *)n->priv;
    if (p == NULL || n->data == NULL) return -ENODEV;
    uint32_t w = n->width, h = n->height;
    size_t stride = n->stride;
    if (p->base == NULL && (p->mode == GEN_GRAD || p->mode == GEN_STRIPES || p->mode == GEN_CARD)) {
        build_base(n, p);
        if (p->base == NULL) return -ENOMEM;
    }
    p->frame++;
    uint32_t frame = p->frame;
    if (p->mode == GEN_COUNT) {
        draw_count(n->data, stride, w, h, frame);
        n->ts_ns = vv_now_ns();
        return 0;
    }
    if (p->base != NULL) {
        for (uint32_t r = 0; r < h; r++) {
            memcpy(n->data + (size_t)r * stride, p->base + (size_t)r * stride, stride);
        }
    }
    if (p->mode == GEN_GRAD || p->mode == GEN_STRIPES) {
        /* 动态部分：一条随帧号移动的亮竖带（只需覆盖几列，不重建底图） */
        uint32_t band = w / 12 > 8 ? w / 12 : 8;
        uint32_t pos = (frame * 12u) % (w ? w : 1);
        uint32_t x0 = pos > band ? pos - band : 0;
        uint32_t x1 = pos + band;
        if (x1 > w) x1 = w;
        for (uint32_t r = 0; r < h; r++) {
            if (x1 > x0) memset(n->data + (size_t)r * stride + x0, 235, x1 - x0);
        }
    }
    /* 色度抹中性：生成器不做彩色，留下 ISP 的颜色只会干扰判读 */
    size_t y_bytes = stride * h;
    if (n->bytes > y_bytes) memset(n->data + y_bytes, 128, n->bytes - y_bytes);
    n->ts_ns = vv_now_ns();
    return 0;
}

static void gen_put_super(struct vvideo_sb *sb) {
    struct vv_node *n = (struct vv_node *)sb->s_private;
    if (n != NULL) {
        struct gen_priv *p = (struct gen_priv *)n->priv;
        if (p != NULL) {
            free(p->base);
            free(p);
        }
        n->priv = NULL;
        vv_node_free(n);
    }
}

static const struct vvideo_super_operations g_gen_sop = {.put_super = gen_put_super};

static struct vvideo_sb *gen_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    char mode[24];
    int m = GEN_GRAD;
    if (vv_opt_str(opts, "mode", mode, sizeof(mode))) {
        if (strcmp(mode, "solid") == 0) m = GEN_SOLID;
        else if (strcmp(mode, "grad") == 0) m = GEN_GRAD;
        else if (strcmp(mode, "stripes") == 0) m = GEN_STRIPES;
        else if (strcmp(mode, "card") == 0) m = GEN_CARD;
        else if (strcmp(mode, "count") == 0) m = GEN_COUNT;
        else {
            vvideo_logf(VV_LOG_ERR, "gen 不认识 mode=%s（可用 solid/grad/stripes/card/count）", mode);
            errno = EINVAL;
            return NULL;
        }
    }
    unsigned long w = vv_opt_u32(opts, "w", 640);
    unsigned long h = vv_opt_u32(opts, "h", 480);
    unsigned long stride = vv_opt_u32(opts, "stride", w);
    unsigned long bytes = vv_opt_u32(opts, "size", 0);
    if (stride < w) stride = w;
    if (bytes == 0) bytes = stride * h * 3 / 2; /* NV12 一帧 */
    if (w == 0 || h == 0 || bytes < stride * h) {
        vvideo_logf(VV_LOG_ERR, "gen 参数非法：%lux%lu stride=%lu size=%lu", w, h, stride, bytes);
        errno = EINVAL;
        return NULL;
    }
    struct vv_node *n = vv_node_create(NULL, (size_t)bytes, (uint32_t)w, (uint32_t)h, VVFMT_NV12,
                                       (size_t)stride);
    if (n == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    struct gen_priv *p = (struct gen_priv *)calloc(1, sizeof(*p));
    if (p == NULL) {
        vv_node_free(n);
        errno = ENOMEM;
        return NULL;
    }
    p->mode = m;
    p->byte = (int)vv_opt_u32(opts, "byte", 0);
    n->priv = p;
    n->fill = gen_fill;
    n->always_ready = true; /* 事件语义：按需产帧，没有定时器 */
    n->writable = false;

    struct vvideo_sb *sb = vv_alloc_sb(type, &g_gen_sop, dev, opts);
    if (sb == NULL) {
        free(p);
        vv_node_free(n);
        errno = ENOMEM;
        return NULL;
    }
    sb->s_private = n;
    n->sb = sb;
    struct vvideo_inode *root = vv_node_inode(n, sb, (size_t)bytes);
    if (root == NULL) {
        free(p);
        vv_node_free(n);
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    vv_set_root(sb, root);
    vvideo_logf(VV_LOG_INFO, "gen 就绪：mode=%d %lux%lu stride=%lu（按需产帧，无定时器）", m, w, h,
                stride);
    return sb;
}

struct vvideo_fs_type vv_type_gen = {.name = "gen", .mount = gen_mount};
