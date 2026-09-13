/*
 * VVideo 内部接口 —— 只给本目录下的后端用（不对外）。
 */
#ifndef PORTAL_VVIDEO_INTERNAL_H
#define PORTAL_VVIDEO_INTERNAL_H

#include "vvideo.h"

/* 内置后端登记（vvfs_builtin.c）。核不认识任何具体后端，只调这个。 */
void vv_builtin_register(void);

extern struct vvideo_fs_type vv_type_mem;
extern struct vvideo_fs_type vv_type_raw;
extern struct vvideo_fs_type vv_type_gen;
extern struct vvideo_fs_type vv_type_shm;
extern struct vvideo_fs_type vv_type_xform;
extern struct vvideo_fs_type vv_type_ctl;

/* ------------------------------------------------------------------ *
 * 帧节点（vvfs_node.c）
 *
 * mem / raw / gen / shm / xform 五个后端的**数据面是同一个东西**：
 * "一块 RAM + 它的几何 + 一个共享事件字 + 一个可选的填充函数"。
 * 它们只在两件事上不同 —— 内存怎么来（malloc / memfd / 映射）、内容怎么变（不be变 /
 * 从文件灌一次 / 每次算 / 由别的进程写 / 从下层变换）。
 *
 * 这么收的理由与 VFS 用 page cache 收各家文件系统一样：只要有一个地方负责
 * "这块字节就是当前帧"，几何、事件、错误处理、统计就不可能各写一遍各错一遍。
 * ------------------------------------------------------------------ */

struct vv_node;

typedef int (*vv_fill_fn)(struct vvideo_file *f, struct vv_node *n);

struct vv_node {
    /* --- 数据面 --- */
    uint8_t *data;
    size_t bytes;
    uint32_t width, height, format;
    size_t stride;
    int64_t ts_ns;

    /* --- 事件字（跨进程时在共享映射里，进程内时单独分配） --- */
    struct vvideo_event *ev;
    bool ev_from_map;

    /* --- 内存来源 --- */
    int fd;         /* >=0：数据区来自 fd 的映射（memfd/共享文件） */
    void *map;      /* mmap 基址（fd>=0 时有效；进程内私有内存时为 NULL） */
    size_t map_len;

    /* --- 行为 --- */
    vv_fill_fn fill;      /* NULL = 数据已定稿（静态源）；否则 acquire 时按需重建 */
    bool always_ready;    /* true：wait 立刻返回（静态源不该制造虚假节拍） */
    bool writable;        /* true：允许 write() 写入（生产者节点） */
    void *priv;           /* 后端私有（如 xform 的下层 file） */
    bool shared_layout;   /* true：数据区头部就是 struct vv_shm_hdr（见下） */

    /* --- 记账 --- */
    struct vvideo_sb *sb;
};

/** 建一个节点：`bytes` 用 malloc 分配（进程内私有内存，热路径零磁盘）。 */
struct vv_node *vv_node_create(struct vvideo_sb *sb, size_t bytes, uint32_t w, uint32_t h,
                               uint32_t format, size_t stride);
/** 用一段已映射好的共享内存建节点（memfd / tmpfs 文件）。映射由节点接管。 */
struct vv_node *vv_node_create_shared(struct vvideo_sb *sb, int fd, void *map, size_t map_len,
                                      uint32_t w, uint32_t h, uint32_t format, size_t stride,
                                      size_t data_off);
/** 释放（连续调用安全）。**不要**在 map 之外再 munmap。 */
void vv_node_free(struct vv_node *n);
/** 把一个 inode 挂到节点上（节点的通用 file_operations 用 i_private 找回来） */
struct vvideo_inode *vv_node_inode(struct vv_node *n, struct vvideo_sb *sb, size_t size);
/** 通用 ops（open/read/write/ioctl/acquire/wait/notify）；后端直接用它即可 */
const struct vvideo_file_operations *vv_node_fops(void);
/** 生产者发布完成：seq++ 并唤醒所有等待者 */
void vv_node_publish(struct vv_node *n);
/** 由 inode 取节点 */
struct vv_node *vv_node_of(struct vvideo_inode *i);

#endif /* PORTAL_VVIDEO_INTERNAL_H */
