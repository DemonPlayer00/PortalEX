/*
 * PortalEX VVideo —— 视频数据的统一层（形状照 Linux VFS）。
 *
 * ## 为什么要它
 *
 * 摄像头模拟这条线到目前长出了**四种互不相干的取帧方式**，各自一套开关、各自一套错误处理：
 *   · `mock_map=<尺寸>:<路径>` 直接 open/read 一个 .raw 模板（注入线程用）；
 *   · `src_file=<帧文件>` mmap 一块共享内存 + 自己解析 4096 字节头（外源帧通道用）；
 *   · `inject_pattern` / `portal_template()` 在 C 里现算渐变与竖条；
 *   · 真 HAL 的流缓冲（扫 `/proc/self/fd` 的 dmabuf，按尺寸认领）。
 * 每条路都要自己回答同样几个问题：**几何是什么、显示变换是什么、拿不到帧怎么办**。
 * 结果同一个 bug 要在四个地方各修一遍，而"这条流现在到底吃的是哪份数据"只能靠读日志猜。
 *
 * 本层把这件事收成**一个命名空间 + 一张操作表**：像 VFS 那样，
 * 调用者只知道"打开一个路径、等一帧、读一帧"，至于是内存块、生成器、共享环还是真 HAL，
 * 由**挂载**决定；显示变换是**叠在**源上的另一层（stacking），不是散在各处的 if。
 *
 * ## 与 Linux VFS 的对应关系（刻意的，不是比喻）
 *
 *   | Linux             | 本层                                                   |
 *   |-------------------|--------------------------------------------------------|
 *   | `file_system_type`| `struct vvideo_fs_type`（register/unregister，按名查）  |
 *   | `super_block`     | `struct vvideo_sb`（一次 mount 的实例，带 s_dev/s_opts）|
 *   | `inode`           | `struct vvideo_inode`（i_mode/i_size/i_op/i_fop/i_private）|
 *   | `dentry` + dcache | `struct vvideo_dentry`（名字树，挂载点挂在 dentry 上）  |
 *   | `file`            | `struct vvideo_file`（f_pos/f_flags/f_private）         |
 *   | `file_operations` | `struct vvideo_file_operations`                        |
 *   | `mount`/`umount`  | `vvideo_mount()` / `vvideo_umount()`（挂载点须已存在）|
 *   | `seq_file`        | `vv_seq_*`（文本节点"生成一次、按 f_pos 切片"）          |
 *   | `wait_queue_head` | `struct vvideo_event` + `vv_event_wait/wake`（futex）   |
 *   | `errno` 负数返回  | 同左（-ENOENT/-EAGAIN/-EBUSY/…）                        |
 *
 * **不一样的地方，必须写清楚**：VFS 在核里、用户态经 syscall 进来；本层是**进程内**的 ——
 * HAL 进程里那份命名空间只属于该进程。跨进程那一半（app 产帧 → HAL 取帧）不是特例代码，
 * 而是**挂载一个 `shm` 后端**：共享内存 + 共享事件字，和进程内节点走同一张操作表。
 *
 * ## 两条硬约束（项目的既定要求，不是建议）
 *
 * ### 1. 数据流**不过物理磁盘**
 *
 *  · 热路径（每帧）**只碰 RAM**：所有后端在 mount 时就把内容物化到内存
 *    （`raw` 读一次文件后关 fd；`gen` 现算；`shm` 是 `memfd_create` 的匿名共享内存，
 *    落在内核 tmpfs 上，**没有块设备、没有回写**）；
 *  · 冷路径**允许**读一次磁盘：`raw` 后端的 `dev=<文件>` 是"上电素材"，
 *    在挂载时读进 RAM，之后整个生命周期不再 open 它；
 *  · 跨进程环**不要**放在 `/data`（f2fs/ext4，会被回写）——
 *    放 `memfd`，或放模块建的 **tmpfs** 挂载点（`/data/local/tmp/odmtmp` 就是这么建的）。
 *
 * ### 2. 事件驱动，不轮询
 *
 *  · 消费者调 `vvideo_wait(f, timeout_ms)`，**睡在事件字上**（`FUTEX_WAIT`），
 *    生产者发布后 `vvideo_notify(f)`（`FUTEX_WAKE`）—— 无新帧时**零 CPU**；
 *  · 共享事件字就放在共享内存头部，所以跨进程也是同一套（这就是 Android BufferQueue 的做法）；
 *  · "静态源"（`raw`/`gen`/`mem`）永远就绪：`wait` 立刻返回 0，不制造虚假节拍；
 *  · **反例（不许写）**：拿 `sleep()`/`usleep()` 或忙循环去"等新帧"。
 *    HAL 里既没有那个 CPU 预算，也会把帧率抖动的锅甩给我们的循环。
 *
 * ## 三条不变量
 *
 *  1. **任何失败都返回负 errno，绝不 abort**。本层跑在 camera provider 进程里，
 *     崩了整机相机就没了。分配失败、节点不存在、后端没准备好 —— 一律安静降级。
 *  2. **零拷贝优先，拷贝是退路**。⚠️ 动源（gen/shm、以及 cache=0 的叠加层）的帧指针
 *     只到**下一次 acquire / release** 为止有效（V4L2 的 buffer 复用约定）；
 *     静态源（mem/raw、cache=1 的叠加层）内容定稿，指针可以长期留着。
`VVIO_ACQUIRE` 返回**源自己的内存**（共享环的槽、
 *     后端的常驻缓冲），调用者用完 `VVIO_RELEASE`；要整块拷走的用 `vvideo_read_all()`。
 *  3. **记账**。挂载了什么、谁在读、失败多少次、最近一次错误 —— `ctl/` 下都可读
 *     （见 vvfs_ctl.c）。本项目最容易出、最难查的故障是"错了但不报错"。
 *
 * ## 最小用法
 *
 *     vvideo_init();
 *     vvideo_mount("raw", "/vvideo/src/front-preview",
 *                  "/data/vendor/camera/ffront.raw", "w=2176,h=1256,stride=2176");
 *     struct vvideo_file *f = vvideo_open("/vvideo/src/front-preview", VV_O_RDONLY);
 *     struct vvideo_frame fr;
 *     if (vvideo_wait(f, 100) == 0 && vvideo_acquire(f, &fr) == 0) {
 *         memcpy(dst, fr.plane[0], fr.size[0]);   // 静态源：一次拷贝即定稿
 *         vvideo_release(f, &fr);
 *     }
 *     vvideo_close(f);
 */
#ifndef PORTAL_VVIDEO_H
#define PORTAL_VVIDEO_H

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/* ------------------------------------------------------------------ *
 * 常量
 * ------------------------------------------------------------------ */

#define VV_ABI_VERSION 1

#define VV_NAME_MAX 64
#define VV_PATH_MAX 256
#define VV_OPTS_MAX 256
#define VV_DEV_MAX 192

/* i_mode */
#define VV_S_IFDIR 0040000u
#define VV_S_IFREG 0100000u

/* open() 的 flags */
#ifndef VV_O_RDONLY
#define VV_O_RDONLY 0x0
#define VV_O_WRONLY 0x1
#define VV_O_RDWR 0x2
#define VV_O_NONBLOCK 0x4
#endif

/* 帧格式 */
enum {
    VVFMT_UNKNOWN = 0,
    VVFMT_OPAQUE = 1, /* 一串没有内部结构的字节（.raw 模板就是它） */
    VVFMT_NV12 = 2,   /* Y + 交织 UV，U 在前 */
    VVFMT_NV21 = 3,   /* Y + 交织 VU，V 在前 */
    VVFMT_I420 = 4,   /* Y + U + V 三平面 */
    VVFMT_RAW10 = 5,  /* MIPI RAW10，2.5 字节/像素 */
    VVFMT_JPEG = 6
};

/* 事件标志（`struct vvideo_event.flags`） */
enum {
    VV_EV_ALWAYS_READY = 1u << 0, /* 静态源：永远就绪，等待立刻返回 */
    VV_EV_SHUTDOWN = 1u << 1      /* 生产者退出：等待者应当收手（-ESHUTDOWN） */
};

/* ioctl 命令码。刻意**不用** _IOR/_IOW 宏：调用方包括 32 位与 64 位两种进程，
 * 结构体尺寸参与编码只会制造"同一命令两个号"的坑。命令码只表示"哪件事"，
 * 结构体形状由本头文件规定。 */
enum {
    VVIO_QUERYCAP = 0x5601, /* → struct vvideo_cap */
    VVIO_GET_FORMAT = 0x5602, /* ← struct vvideo_fmt */
    VVIO_SET_FORMAT = 0x5603, /* → struct vvideo_fmt */
    VVIO_ACQUIRE = 0x5604,    /* → struct vvideo_frame（零拷贝） */
    VVIO_RELEASE = 0x5605,    /* → struct vvideo_frame */
    VVIO_CTL = 0x5606,        /* → struct vvideo_ctl（后端自定义键值控制） */
    VVIO_BIND = 0x5607,       /* → struct vvideo_bind（把外部资源绑到节点上） */
    VVIO_STAT = 0x5608,       /* → struct vvideo_fsstat */
    VVIO_WAIT = 0x5609,       /* → struct vvideo_wait（事件驱动：睡等新帧） */
    VVIO_NOTIFY = 0x560a,     /* → 忽略参数；生产者唤醒等待者 */
    VVIO_EVENT = 0x560b       /* ← struct vvideo_event *（拿到共享事件字，供外部自旋/等待） */
};

/* ------------------------------------------------------------------ *
 * 数据结构
 * ------------------------------------------------------------------ */

/**
 * 事件字。**必须放在共享内存里**（进程内与跨进程用同一套），
 * 所以它只能是 POD、且不能含指针。
 *
 * 为什么用 futex 而不是 eventfd：eventfd 是"每进程一个 fd"，跨进程要传 fd
 * （SCM_RIGHTS，链路更长、SELinux 上更麻烦）；而 futex 直接等在一个**共享内存里的
 * 32 位整数**上，生产者一次 `FUTEX_WAKE` 就能叫醒另一进程的等待者 ——
 * Android 的 BufferQueue / AAudio 环都是这个做法。
 */
struct vvideo_event {
    volatile uint32_t seq;     /* 生产者递增；消费者等在这个字上 */
    volatile uint32_t flags;   /* VV_EV_* */
    volatile int32_t waiters;  /* 诊断用：当前有几个等待者 */
    volatile int32_t reserved;
};

/** 一帧。**指针指向源自己的内存**，`vvideo_release()` 之前一直有效。 */
struct vvideo_frame {
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t plane_count;
    uint8_t *plane[3];
    size_t stride[3];
    size_t size[3]; /* 该平面**可用**的字节数（不是"有意义"的字节数） */
    uint64_t seq;   /* 同一个 file 上每次 acquire 递增 */
    int64_t ts_ns;  /* 采集时刻（CLOCK_MONOTONIC），0 = 不知 */
    void *owner;    /* 内部：所属 file，调用者不要改 */
};

struct vvideo_cap {
    char driver[32]; /* 后端名（"vvfs-raw"…） */
    char card[48];   /* 节点名 */
    char bus[48];    /* 设备/来源 */
    uint32_t version;
    uint32_t flags;
};

struct vvideo_fmt {
    uint32_t width, height, format;
    size_t stride;
};

/** 后端私有的键值控制（`set` 写入、`get` 读回）。 */
struct vvideo_ctl {
    const char *key;
    char *val;
    size_t vallen;
};

/** 把外部资源绑到节点上（"这条流属于哪个相机、什么角色"）。 */
struct vvideo_bind {
    int camera_id;
    const char *role; /* "preview" / "video" / "photo" */
    uint32_t width, height;
    uint32_t usage;
};

/** 等待参数。`timeout_ms < 0` = 一直等（调用方自负责任）。 */
struct vvideo_wait {
    int timeout_ms;
};

/** 节点自己的计数（`ctl/stat` 与后端自检用）。 */
struct vvideo_fsstat {
    unsigned long opens;    /* 累计 open 次数 */
    unsigned long acquires; /* 累计取帧次数 */
    unsigned long fails;    /* 累计失败次数 */
    unsigned long waits;    /* 累计等待次数 */
    unsigned long wakes;    /* 被唤醒次数（不含超时） */
    unsigned long bytes;    /* 累计交付的字节数 */
    int last_errno;         /* 最近一次失败的 errno（0 = 没失败过） */
};

struct vvideo_sb;
struct vvideo_inode;
struct vvideo_file;
struct vvideo_fs_type;
struct vvideo_dentry;

/* ------------------------------------------------------------------ *
 * 操作表
 * ------------------------------------------------------------------ */

/**
 * 文件操作。语义与 `file_operations` 一致，字段可为 NULL（不支持 ⇒ -ENOTTY）。
 *
 * `read/write` 收到 `off_t *pos`：实现**必须**按它定位并前移（和普通文件一样）。
 */
struct vvideo_file_operations {
    int (*open)(struct vvideo_inode *inode, struct vvideo_file *f);
    int (*release)(struct vvideo_inode *inode, struct vvideo_file *f);
    ssize_t (*read)(struct vvideo_file *f, void *buf, size_t n, off_t *pos);
    ssize_t (*write)(struct vvideo_file *f, const void *buf, size_t n, off_t *pos);
    long (*ioctl)(struct vvideo_file *f, unsigned cmd, void *arg);
    int (*acquire)(struct vvideo_file *f, struct vvideo_frame *out);
    int (*release_frame)(struct vvideo_file *f, struct vvideo_frame *fr);
    /**
     * 事件驱动等待。**不要在这里 sleep 或自旋**：实现应当
     * `return vv_event_wait(f->f_event, seq, timeout_ms)`（或对静态源直接返回 0）。
     * 返回 0 = 有新事件；-EAGAIN = 超时；-ESHUTDOWN = 生产者已退出。
     */
    int (*wait)(struct vvideo_file *f, int timeout_ms);
    /** 生产者侧：发布完成后唤醒等待者（`vv_event_wake`） */
    int (*notify)(struct vvideo_file *f);
    /** 目录/文本节点：把整份内容生成一次（seq_file 那一套） */
    int (*iterate)(struct vvideo_file *f, char *buf, size_t cap, size_t *out_len);
};

/** 目录操作。`lookup` 允许**动态造 inode**（ctl 那类节点就是这么来的）。 */
struct vvideo_inode_operations {
    int (*lookup)(struct vvideo_inode *dir, const char *name, struct vvideo_inode **out);
    int (*iterate)(struct vvideo_inode *dir, char *buf, size_t cap, size_t *out_len);
};

struct vvideo_super_operations {
    int (*statfs)(struct vvideo_sb *sb, char *buf, size_t cap, size_t *out_len);
    /** 卸载前调用；释放 s_private，**不要**碰 inode/dentry（核统一释放） */
    void (*put_super)(struct vvideo_sb *sb);
    int (*remount)(struct vvideo_sb *sb, const char *opts);
};

/** 一个"文件系统类型"（后端）。mount() 成功返回新建的 sb，失败返回 NULL。 */
struct vvideo_fs_type {
    const char *name;
    struct vvideo_sb *(*mount)(struct vvideo_fs_type *type, const char *dev, const char *opts);
    struct vvideo_fs_type *next; /* 核维护 */
};

/* ------------------------------------------------------------------ *
 * 核内对象（后端要填，所以可见；**不要**直接改核管的字段）
 * ------------------------------------------------------------------ */

struct vvideo_inode {
    uint64_t i_ino;
    uint32_t i_mode;
    size_t i_size;
    struct vvideo_sb *i_sb;
    const struct vvideo_inode_operations *i_op;
    const struct vvideo_file_operations *i_fop;
    void *i_private;
    struct vvideo_fsstat i_stat;
    /* --- 以下由核维护 --- */
    int i_ref;
};

struct vvideo_dentry {
    char name[VV_NAME_MAX];
    struct vvideo_inode *inode;
    struct vvideo_dentry *parent;
    struct vvideo_dentry *child;
    struct vvideo_dentry *next;
    struct vvideo_sb *mounted; /* 此目录上挂了哪个 sb（挂载点） */
};

struct vvideo_sb {
    const struct vvideo_fs_type *s_type;
    const struct vvideo_super_operations *s_op;
    struct vvideo_inode *s_root;
    struct vvideo_dentry *s_root_dentry;
    char s_dev[VV_DEV_MAX];
    char s_opts[VV_OPTS_MAX];
    char s_dir[VV_PATH_MAX]; /* 挂载点路径 */
    void *s_private;
    /* --- 以下由核维护 --- */
    int s_open_files;
    bool s_dead;
    struct vvideo_sb *s_next;
};

struct vvideo_file {
    struct vvideo_dentry *f_dentry;
    struct vvideo_inode *f_inode;
    const struct vvideo_file_operations *f_op;
    unsigned f_flags;
    off_t f_pos;
    void *f_private;
    struct vvideo_event *f_event; /* 该节点的共享事件字（可为 NULL） */
    uint64_t f_seq;               /* acquire 计数 */
    bool f_frame_held;
    int f_ref;
    void *f_core; /* 核私有（文本缓存）；后端不要碰 */
};

/* ------------------------------------------------------------------ *
 * 对外接口
 * ------------------------------------------------------------------ */

/** 初始化（幂等）：挂 rootfs、建 /vvideo/{src,ctl}、注册**所有**内置后端。 */
void vvideo_init(void);

int vvideo_register_filesystem(struct vvideo_fs_type *fs);
int vvideo_unregister_filesystem(const char *name);
struct vvideo_fs_type *vvideo_find_filesystem(const char *name);

/**
 * 挂载：`dir` 必须是**已存在**的目录（照 VFS，挂载不自动建目录）。
 *
 * 为什么坚持这个严格语义：把挂载点写错一个字母时，宁可 -ENOENT 当场报错，
 * 也不要**悄悄替调用方造出一棵新目录树**（那样错字会变成"多个幽灵挂载点"）。
 */
struct vvideo_sb *vvideo_mount(const char *type, const char *dir, const char *dev,
                                const char *opts);

/**
 * 挂载（自动补齐挂载点目录，等价 `mkdir -p` 后 `mount`）。
 *
 * 为什么两个都要有：核内的命名空间是**我们自己搭的**（不像 Linux 有现成的根文件系统），
 * 调用方（camshim 的启动段）要一口气挂十几个节点，逐个 mkdir 是纯粹的噪音。
 * 语义差别只有这一点，其余完全一致 —— 严格的那个留着给"必须确认目录已存在"的场合。
 */
struct vvideo_sb *vvideo_mount_ensure(const char *type, const char *dir, const char *dev,
                                      const char *opts);
/** 卸载：还有打开的文件就 -EBUSY（与 VFS 同义，也是本层"无引用计数"的安全依据）。 */
int vvideo_umount(const char *dir);
int vvideo_mounts_text(char *buf, size_t cap, size_t *out_len);

int vvideo_mkdir(const char *path);

/** 路径解析：成功返回 0 并把 inode 写进 `*out`（inode 归 sb 所有，不需释放）。 */
int vvideo_lookup(const char *path, struct vvideo_inode **out);
int vvideo_exists(const char *path);
/** 文本列目录（`ls -l` 风格） */
int vvideo_readdir(const char *path, char *buf, size_t cap, size_t *out_len);

struct vvideo_file *vvideo_open(const char *path, int flags);
int vvideo_close(struct vvideo_file *f);
ssize_t vvideo_read(struct vvideo_file *f, void *buf, size_t n);
ssize_t vvideo_write(struct vvideo_file *f, const void *buf, size_t n);
long vvideo_ioctl(struct vvideo_file *f, unsigned cmd, void *arg);
int vvideo_acquire(struct vvideo_file *f, struct vvideo_frame *out);
int vvideo_release(struct vvideo_file *f, struct vvideo_frame *fr);

/** 事件驱动等待/唤醒（**替代轮询**）。0 = 有新事件；-EAGAIN = 超时。 */
int vvideo_wait(struct vvideo_file *f, int timeout_ms);
int vvideo_notify(struct vvideo_file *f);

/** 便捷：整份读出来（内部 malloc，调用者 free）。失败返回负 errno。 */
ssize_t vvideo_read_all(const char *path, void **out_buf, size_t *out_len);

/** 观测 */
int vvideo_tree_text(char *buf, size_t cap, size_t *out_len);
int vvideo_log_text(char *buf, size_t cap, size_t *out_len);

/**
 * 核单例（rootfs 的 super_block）地址。
 *
 * 它**故意永不释放**（没有它就没有命名空间的根），所以内存检查工具会把它报成泄漏。
 * 这个接口就是给那些工具做豁免用的；正常调用方不需要它。
 */
void *vvideo_root_singleton(void);

/** 记账日志：进环形缓冲，同时按优先级打到 logcat（host 上是 stderr） */
void vvideo_logf(int prio, const char *fmt, ...);
enum { VV_LOG_ERR = 3, VV_LOG_WARN = 4, VV_LOG_INFO = 6, VV_LOG_DEBUG = 7 };

extern int vv_errno;

/* ------------------------------------------------------------------ *
 * 给后端用的小工具
 * ------------------------------------------------------------------ */

/** seq_file 的最小实现：生成一次、按 f_pos 切片 */
struct vv_seq {
    char *buf;
    size_t len;
    size_t cap;
};

void vv_seq_init(struct vv_seq *s);
void vv_seq_free(struct vv_seq *s);
void vv_seq_printf(struct vv_seq *s, const char *fmt, ...);
int vv_text_read(struct vvideo_file *f, void *buf, size_t n, off_t *pos,
                 int (*gen)(struct vvideo_file *f, struct vv_seq *s, void *priv), void *priv);

struct vvideo_inode *vv_alloc_inode(struct vvideo_sb *sb, uint32_t mode, size_t size,
                                    const struct vvideo_inode_operations *i_op,
                                    const struct vvideo_file_operations *fop, void *priv);
struct vvideo_sb *vv_alloc_sb(struct vvideo_fs_type *type, const struct vvideo_super_operations *op,
                              const char *dev, const char *opts);
void vv_set_root(struct vvideo_sb *sb, struct vvideo_inode *root);

/** 选项解析（`w=2176,h=1256` 这种）。找不到返回 def。 */
unsigned long vv_opt_u32(const char *opts, const char *key, unsigned long def);
bool vv_opt_str(const char *opts, const char *key, char *out, size_t cap);

/* --- 事件（后端直接用这几个，别自己 syscall） --- */

/** 睡在事件字上，直到 seq != expect 或超时。0 = 有事件；-EAGAIN = 超时；-ESHUTDOWN */
int vv_event_wait(struct vvideo_event *ev, uint32_t expect, int timeout_ms);
/** 唤醒等待者（生产者调用） */
void vv_event_wake(struct vvideo_event *ev);
/** 读当前序号（**不是**轮询：只在判断"有没有新帧"时读一次） */
uint32_t vv_event_seq(struct vvideo_event *ev);
/** 当前单调时钟（纳秒） */
int64_t vv_now_ns(void);

/* --- 内存 --- */

/** `memfd_create` 的兼容包装（minSdk 24 上 bionic 不导出它，直接走 syscall） */
int vv_memfd_create(const char *name, unsigned flags);

#endif /* PORTAL_VVIDEO_H */
