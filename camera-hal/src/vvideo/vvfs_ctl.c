/*
 * vvfs-ctl —— 控制面与可观测性（"只读的世界，可写的一行"）。
 *
 * ## 为什么观测面也必须是一层
 *
 * 本项目的既定教训：**最容易出、最难查的故障是"错了但不报错"**。
 * VVideo 收拢了四种取帧方式之后，风险不会消失，只会**换个地方**：
 * "这条流通了但走的是哪个后端""为什么没接管""最后失败的是什么"。
 * 所以从一开始就把这些做成**可读的节点**，而不是事后去加日志：
 *
 *     /vvideo/ctl/tree     整棵命名空间 + 每个节点的取帧/等待/失败计数
 *     /vvideo/ctl/mounts   挂载表（含每个 sb 上还开着几个文件）
 *     /vvideo/ctl/fs       已注册的后端类型
 *     /vvideo/ctl/log      最近 32 条记账日志（HAL 进程里 logcat 常抓不全）
 *     /vvideo/ctl/stat     汇总：节点数、挂载数、活动后端
 *     /vvideo/ctl/help     用法
 *     /vvideo/ctl/cmd      可写：一行一条命令（mount / umount / mkdir）
 *
 * `cmd` 的存在意义：装机之后**不必重编 .so**就能改挂载 ——
 * 这对"换个素材试试"这类实验是决定性的（重编+重推+重启 provider 要一分钟起）。
 *
 * ⚠️ 它是**进程内**的控制面：能写它的只有本进程或已经能访问本进程的调试通道。
 * 不做鉴权，因为 HAL 进程里没有任何"别的用户"能碰到它。
 */
#define _GNU_SOURCE
#include "vvideo_internal.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    CTL_TREE = 0,
    CTL_MOUNTS,
    CTL_FS,
    CTL_LOG,
    CTL_STAT,
    CTL_HELP,
    CTL_CMD,
    CTL_COUNT
};

static const char *const g_ctl_names[CTL_COUNT] = {"tree", "mounts", "fs",     "log",
                                                   "stat", "help",   "cmd"};

static int ctl_gen(struct vvideo_file *f, struct vv_seq *s, void *priv) {
    intptr_t idx = (intptr_t)priv;
    (void)f;
    switch (idx) {
        case CTL_TREE: {
            size_t cap = 32768, len = 0;
            char *buf = (char *)malloc(cap);
            if (buf == NULL) return -ENOMEM;
            if (vvideo_tree_text(buf, cap, &len) == 0) vv_seq_printf(s, "%s", buf);
            free(buf);
            return 0;
        }
        case CTL_MOUNTS: {
            char buf[8192];
            size_t len = 0;
            if (vvideo_mounts_text(buf, sizeof(buf), &len) == 0) {
                vv_seq_printf(s, "# dev 挂载点 类型 选项 0 0\n%s", buf);
            }
            return 0;
        }
        case CTL_FS: {
            /* 遍历注册表：核没有对外的迭代器，这里用自己的接口拿 */
            static const char *known[] = {"mem", "raw", "gen", "shm", "xform", "ctl"};
            for (size_t i = 0; i < sizeof(known) / sizeof(known[0]); i++) {
                if (vvideo_find_filesystem(known[i]) != NULL) vv_seq_printf(s, "%s\n", known[i]);
            }
            return 0;
        }
        case CTL_LOG: {
            char buf[12288];
            size_t len = 0;
            if (vvideo_log_text(buf, sizeof(buf), &len) == 0) vv_seq_printf(s, "%s", buf);
            return 0;
        }
        case CTL_STAT: {
            char buf[32768];
            size_t len = 0;
            vv_seq_printf(s, "abi=%d\n", VV_ABI_VERSION);
            if (vvideo_tree_text(buf, sizeof(buf), &len) == 0) {
                int nodes = 0, mounts = 0;
                for (size_t i = 0; i < len; i++) {
                    if (buf[i] == '\n') nodes++;
                }
                for (size_t i = 0; i + 6 <= len; i++) {
                    if (strncmp(buf + i, "mount ", 6) == 0) mounts++;
                }
                vv_seq_printf(s, "行数=%d 挂载=%d\n", nodes, mounts);
            }
            return 0;
        }
        case CTL_HELP:
            vv_seq_printf(s,
                          "VVideo 控制面\n"
                          "  读：tree mounts fs log stat help\n"
                          "  写：cmd —— 一行一条命令\n"
                          "      mount <类型> <挂载点> <dev> <选项>\n"
                          "      umount <挂载点>\n"
                          "      mkdir <目录>\n"
                          "  类型：mem raw gen shm xform ctl\n"
                          "  例：echo 'mount gen /vvideo/src/probe w=640,h=480,mode=card' > cmd\n");
            return 0;
    }
    return -ENOENT;
}

/** 一行一条命令。解析刻意笨：空格切词，选项原样透传。 */
static void ctl_exec_line(const char *line) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s", line);
    char *save = NULL;
    char *verb = strtok_r(buf, " \t\r\n", &save);
    if (verb == NULL) return;
    if (strcmp(verb, "mount") == 0) {
        char *type = strtok_r(NULL, " \t\r\n", &save);
        char *dir = strtok_r(NULL, " \t\r\n", &save);
        char *dev = strtok_r(NULL, " \t\r\n", &save);
        char *opts = strtok_r(NULL, "\r\n", &save); /* 选项里可以带空格，整段收 */
        if (type == NULL || dir == NULL) {
            vvideo_logf(VV_LOG_WARN, "ctl: mount 用法 <类型> <挂载点> <dev> <选项>");
            return;
        }
        while (opts != NULL && (*opts == ' ' || *opts == '\t')) opts++;
        struct vvideo_sb *sb = vvideo_mount(type, dir, dev != NULL ? dev : "", opts ? opts : "");
        vvideo_logf(sb != NULL ? VV_LOG_INFO : VV_LOG_ERR, "ctl: mount %s → %s ⇒ %s", type, dir,
                    sb != NULL ? "成功" : "失败");
        return;
    }
    if (strcmp(verb, "umount") == 0) {
        char *dir = strtok_r(NULL, " \t\r\n", &save);
        if (dir == NULL) return;
        int rc = vvideo_umount(dir);
        vvideo_logf(rc == 0 ? VV_LOG_INFO : VV_LOG_ERR, "ctl: umount %s ⇒ %d", dir, rc);
        return;
    }
    if (strcmp(verb, "mkdir") == 0) {
        char *dir = strtok_r(NULL, " \t\r\n", &save);
        if (dir == NULL) return;
        int rc = vvideo_mkdir(dir);
        vvideo_logf(rc == 0 ? VV_LOG_INFO : VV_LOG_WARN, "ctl: mkdir %s ⇒ %d", dir, rc);
        return;
    }
    vvideo_logf(VV_LOG_WARN, "ctl: 不认识的命令「%s」（试 help）", verb);
}

static ssize_t ctl_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos) {
    return vv_text_read(f, buf, n, pos, ctl_gen, f->f_inode->i_private);
}

static ssize_t ctl_write(struct vvideo_file *f, const void *buf, size_t n, off_t *pos) {
    intptr_t idx = (intptr_t)f->f_inode->i_private;
    if (idx != CTL_CMD) return -EACCES;
    char line[512];
    size_t take = n < sizeof(line) - 1 ? n : sizeof(line) - 1;
    memcpy(line, buf, take);
    line[take] = '\0';
    ctl_exec_line(line);
    if (pos != NULL) *pos += (off_t)n;
    return (ssize_t)n;
}

static const struct vvideo_file_operations g_ctl_fops = {
    .read = ctl_read,
    .write = ctl_write,
};

static int ctl_lookup(struct vvideo_inode *dir, const char *name, struct vvideo_inode **out) {
    for (int i = 0; i < CTL_COUNT; i++) {
        if (strcmp(g_ctl_names[i], name) != 0) continue;
        struct vvideo_inode *i2 = vv_alloc_inode(dir->i_sb, VV_S_IFREG, 0, NULL, &g_ctl_fops,
                                                 (void *)(intptr_t)i);
        if (i2 == NULL) return -ENOMEM;
        *out = i2;
        return 0;
    }
    return -ENOENT;
}

static int ctl_iterate(struct vvideo_inode *dir, char *buf, size_t cap, size_t *out_len) {
    (void)dir;
    size_t n = 0;
    for (int i = 0; i < CTL_COUNT; i++) {
        int w = snprintf(buf + n, cap - n, "- %-24s %s\n", g_ctl_names[i],
                         i == CTL_CMD ? "(可写：一行一条命令)" : "");
        if (w < 0 || (size_t)w >= cap - n) break;
        n += (size_t)w;
    }
    if (out_len) *out_len = n;
    return 0;
}

static const struct vvideo_inode_operations g_ctl_dir_iops = {
    .lookup = ctl_lookup,
    .iterate = ctl_iterate,
};

static struct vvideo_sb *ctl_mount(struct vvideo_fs_type *type, const char *dev, const char *opts) {
    struct vvideo_sb *sb = vv_alloc_sb(type, NULL, dev, opts);
    if (sb == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    struct vvideo_inode *root = vv_alloc_inode(sb, VV_S_IFDIR, 0, &g_ctl_dir_iops, NULL, NULL);
    if (root == NULL) {
        free(sb);
        errno = ENOMEM;
        return NULL;
    }
    vv_set_root(sb, root);
    return sb;
}

struct vvideo_fs_type vv_type_ctl = {.name = "ctl", .mount = ctl_mount};
