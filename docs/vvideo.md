# VVideo —— 视频数据的统一层

> 形状照 **Linux VFS**：super_block / inode / dentry / file + 两张操作表 + 挂载表 + 路径解析。
> 代码在 [`camera-hal/src/vvideo/`](../camera-hal/src/vvideo/)，自测在
> [`camera-hal/tools/vvideo_selftest.c`](../camera-hal/tools/vvideo_selftest.c)，
> 跑法 `sh scripts/test-vvideo.sh`（在开发机上跑，不需要设备）。
>
> **两条硬约束**（项目要求，不是建议）：**数据流不过物理磁盘**、**事件驱动不轮询**。
> 见第 3 节 —— 这两条决定了整个数据面的形状。

---

## 1. 为什么要有它

摄像头模拟这条线到这一层之前，长出了**四种互不相干的取帧方式**，每种一套开关、一套错误处理：

| 取帧方式 | 现状代码 | 各自要回答的问题 |
|---|---|---|
| `.raw` 模板文件 | `mock_map` + `portal_mock_load()` | 几何？显示变换？读不到怎么办？ |
| 共享帧文件（app 产帧） | `src_file` + `portal_frame_hdr` + `portal_fsrc_*` | 换槽规则？谁算"新帧"？生产者死了怎么发现？ |
| 合成图案 | `inject_pattern` + `portal_template()` | 静态底图算几次？ |
| 真 HAL 流缓冲 | 扫 `/proc/self/fd` 的 dmabuf | 按什么认领？usage 能不能 CPU 写？ |

同一个问题要在四个地方各答一遍，于是**修一处忘三处**。已经付过代价的例子：
前置预览的显示变换（`rot90cw` + `flipud`）在生成脚本与 HAL 注入里各写了一份，
改了一边忘了另一边，画面就成了镜像。

VVideo 把这件事收成**一个命名空间 + 一张操作表**。调用者只知道：

```c
vvideo_wait(f, 超时);          // 事件驱动：等一帧（静态源立刻返回）
vvideo_acquire(f, &frame);     // 零拷贝拿到帧指针
```

至于帧从哪来 —— 内存块、生成器、共享环、还是**叠了变换的另一个节点** —— 由**挂载**决定，
调用方一行都不用改。

---

## 2. 与 Linux VFS 的对应（刻意的，不是比喻）

| Linux | 本层 | 位置 |
|---|---|---|
| `file_system_type` | `struct vvideo_fs_type` | `vvideo.h` |
| `super_block` | `struct vvideo_sb`（`s_dev`/`s_opts`/`s_op`/`s_private`） | 同 |
| `inode` | `struct vvideo_inode`（`i_mode`/`i_size`/`i_op`/`i_fop`/`i_private`） | 同 |
| `dentry` + dcache | `struct vvideo_dentry`（名字树；挂载点挂在 dentry 上） | 同 |
| `file` | `struct vvideo_file`（`f_pos`/`f_flags`/`f_private`） | 同 |
| `file_operations` | `struct vvideo_file_operations`（open/read/write/ioctl/acquire/wait/notify） | 同 |
| `inode_operations` | `struct vvideo_inode_operations`（lookup/iterate，支持动态造节点） | 同 |
| `super_operations` | `struct vvideo_super_operations`（statfs/put_super/remount） | 同 |
| `mount` / `umount` | `vvideo_mount()` / `vvideo_umount()` | `vvideo_core.c` |
| `seq_file` | `vv_seq_*` + `vv_text_read()`（生成一次、按 `f_pos` 切片） | 同 |
| `wait_queue_head` | `struct vvideo_event` + `vv_event_wait/wake`（**futex**） | `vvideo_event.c` |
| 负数 errno | 同（`-ENOENT`/`-EAGAIN`/`-EBUSY`/…） | 全层 |

**不一样的地方，必须写清楚**：VFS 在内核里、用户态经 syscall 进来；本层是**进程内**的 ——
HAL 进程里那份命名空间只属于该进程。跨进程那一半不是特例代码，而是**挂载一个 `shm` 后端**：
共享内存 + 共享事件字，和进程内节点走**同一张操作表**。

---

## 3. 两条硬约束，以及它们怎么落到代码里

### 3.1 数据流不过物理磁盘

| 后端 | 内存从哪来 | 热路径碰磁盘吗 |
|---|---|---|
| `mem` | `malloc`（进程内）或 `prefill=` 冷启动读一次 | 否 |
| `raw` | 挂载时读一次 `dev=<文件>`，**读完立刻 `close(fd)`** | 否（fd 不留在生命周期里） |
| `gen` | 常驻 RAM，按需重算 | 否 |
| `shm` | **`memfd_create`**（匿名共享内存，内核 tmpfs，无块设备、无回写）或 tmpfs 上的文件 | 否 |
| `xform` | 自己的 RAM 缓冲，从下层变换而来 | 否 |

两处刻意的做法：

- **`raw` 读完就关 fd**：文件描述符不留在生命周期里，热路径**不可能**再碰磁盘。
  自测里有一条专门盯它 —— 挂载后把源文件 `unlink` 掉，再读必须仍然逐字节正确
  （见 7.1 用例 3）。
- **`shm` 默认走 `memfd`**：`memfd` 的内存来自内核 tmpfs，不经过任何块设备、不会被回写。
  这是"不过物理磁盘"最干净的落点。走 `dev=<路径>` 时"落在 tmpfs 上"是**调用方的责任**，
  本层不去猜路径 —— 猜错比不猜更糟（层里只写一句注释说明这一点）。

### 3.2 事件驱动，不轮询

```c
struct vvideo_event {          // 必须放在共享内存里，所以只能是 POD
    volatile uint32_t seq;     // 生产者递增；消费者 FUTEX_WAIT 在这个字上
    volatile uint32_t flags;   // VV_EV_ALWAYS_READY / VV_EV_SHUTDOWN
    volatile int32_t  waiters;
};
```

- 为什么是 **futex** 而不是 `eventfd` / `pthread_cond`：
  · condvar 只能同进程用，而我们的数据面跨进程，**事件字必须活在共享内存里** ⇒ condvar 出局；
  · `eventfd` 跨进程要传 fd（SCM_RIGHTS），链路更长、SELinux 上更麻烦，而且它是个**每进程**对象，
    没法跟着共享内存一起走；
  · futex 直接等在一个共享内存里的 32 位整数上 —— Android 的 BufferQueue、AAudio 环都是这个做法。
  **无竞争时零系统调用**（用户态读一个整数就返回），所以"事件驱动"不会把每帧的成本抬高。
- **静态源不许制造虚假节拍**：`mem`/`raw`/`gen` 以及 `cache=1` 的 `xform` 都是
  `always_ready = true`，`vvvideo_wait()` 立刻返回 0。它们的语义是"随时可以取"，
  而不是"每 33ms 假装有一帧"。
- **本层代码里没有任何 `sleep`/`usleep`/忙等**。自测第 6 组专门验证这一点：
  超时 2000ms 的等待被 20ms 后的生产者唤醒 ⇒ 只可能是 `FUTEX_WAKE` 干的。

---

## 4. ABI 速查

### 4.1 函数

| 类别 | 函数 |
|---|---|
| 生命周期 | `vvideo_init()`（幂等） |
| 注册表 | `vvideo_register_filesystem()` / `vvideo_unregister_filesystem()` / `vvideo_find_filesystem()` |
| 挂载 | `vvideo_mount()`、`vvideo_mount_ensure()`（自动 `mkdir -p`）、`vvideo_umount()`、`vvideo_mounts_text()`、`vvideo_mkdir()` |
| 路径 | `vvideo_lookup()`、`vvideo_exists()`、`vvideo_readdir()` |
| 文件 | `vvideo_open/close/read/write/ioctl/acquire/release` |
| 事件 | `vvideo_wait(f, 超时)`、`vvideo_notify(f)` |
| 便捷 | `vvvideo_read_all(path, &buf, &len)` |
| 观测 | `vvideo_tree_text()`、`vvideo_log_text()`、`vvideo_logf()` |

### 4.2 ioctl

| 命令 | 参数 | 说明 |
|---|---|---|
| `VVIO_QUERYCAP` | `struct vvideo_cap*` | driver / card / bus / flags |
| `VVIO_GET_FORMAT` / `VVIO_SET_FORMAT` | `struct vvideo_fmt*` | 几何与格式 |
| `VVIO_ACQUIRE` / `VVIO_RELEASE` | `struct vvideo_frame*` | 零拷贝取帧 / 归还 |
| `VVIO_WAIT` | `struct vvideo_wait*` | 事件驱动等待（`timeout_ms`） |
| `VVIO_NOTIFY` | — | 生产者唤醒等待者 |
| `VVIO_EVENT` | `struct vvideo_event**` | 拿共享事件字 |
| `VVIO_CTL` | `struct vvideo_ctl*` | 后端自定义键值 |
| `VVIO_BIND` | `struct vvideo_bind*` | 把外部资源绑到节点（如 shm 的 enabled） |
| `VVIO_STAT` | `struct vvideo_fsstat*` | 该节点的 opens/acquires/waits/fails/bytes |

命令码不用 `_IOR/_IOW` 宏：调用方包括 32 位与 64 位两种进程，结构体尺寸参与编码只会制造
"同一命令两个号"的坑。

### 4.3 两条容易踩的语义

1. **`VVIO_ACQUIRE` 返回的指针有效期到下一次 acquire / release 为止**（V4L2 的缓冲复用约定）。
   动源（gen/shm、`cache=0` 的 xform）必须**取一帧就立刻拷走**；静态源（mem/raw、`cache=1`）
   内容定稿，指针可以长期留着。
   > 这条是自测逼出来的：第一版把两次 acquire 写在一行里再分别 memcpy，两份数据自然是同一份。
2. **挂载点是"覆盖"，卸载是"摘掉覆盖层"**：
   · `vvvideo_open("/a/b")` 在 `/a/b` 是挂载点时会**穿过**它，拿到里面那个文件系统的根；
   · 但 `vvvideo_mount()` 不穿 —— 它要判"这个挂载点是不是已被占用"（占用 ⇒ `-EBUSY`）；
   · 卸载后**目录还在**（照 VFS），只是里面不再是那个节点。
   > 第一条也是自测抓到的：第一版不穿，`open()` 拿到的是挂载点那个空目录 inode
   > （`i_fop == NULL`）⇒ 取帧永远 `-ENOTTY`。

---

## 5. 命名空间与后端

`vvideo_init()` 建好骨架并注册**所有**内置后端（登记表在 `vvfs_builtin.c`，加后端 = 加一行）：

```
/
└── vvideo/
    ├── ctl/            ← vvfs-ctl：控制面与观测（tree/mounts/fs/log/stat/help/cmd）
    └── src/            ← 帧源挂载点（调用方自己 mkdir + mount）
        ├── front-preview   [raw]
        └── front-video     [raw]
```

| 类型 | 挂载选项 | 用途 |
|---|---|---|
| `mem` | `size=`, `w=`, `h=`, `stride=`, `fmt=`, `prefill=<文件>` | 纯内存帧；`prefill` 是"冷启动素材" |
| `raw` | `dev=<文件>` + 同上 | 把"一个文件 = 一份素材"显式化（数据面与 mem 相同） |
| `gen` | `w=`, `h=`, `mode=solid\|grad\|stripes\|card\|count` | 合成帧源（"视频界的 /dev/zero"） |
| `shm` | `slots=`, `slot=` 或 `w=`/`h=`, `dev=<路径>`（空=memfd）, `name=`, `init=` | **跨进程共享环**，事件字在头部 |
| `xform` | `dev=<下层路径>`, `op=a+b+c`, `cache=`, `chroma=` | **叠加变换层**（像 ecryptfs/overlayfs） |
| `ctl` | — | 控制面：`cmd` 可写，一行一条命令 |

### 5.1 `xform`：显示变换是数据，不是代码路径

真机上测出来的三种变换，现在是**挂载选项**：

| 场景 | 实测关系 | 挂载写法 |
|---|---|---|
| 后置预览 | `display = rot90cw(buffer)` | `op=rot90cw` |
| 前置预览 | `display = flipud(rot90cw(buffer))` | `op=rot90cw+flipud` |
| 前置录像 | `display = fliplr(rot90cw(buffer))` | `op=rot90cw+fliplr` |

（生成素材时用的是逆向链，例如"设计图 → 前置预览缓冲区"是 `op=flipud+rot90ccw`。）

**单遍实现**：每个变换都是仿射的（`src = A·dst + b`），整条链的**反解先合成**成一个 2×3 矩阵，
逐像素只做一次矩阵乘 —— 不需要 N 个中间缓冲，也没有"N 遍转置"的缓存灾难。

⚠️ **链的分隔符是 `+`，不是 `,`**：挂载选项本身用逗号分隔（`vv_opt_str` 取到下一个逗号为止），
写 `op=rot90cw,flipud` 只会拿到 `rot90cw` —— 后半个 op 被**静默丢掉**，
表现是"画面对了但方向不对"，日志里还看不出少了东西。这个坑在自测里露过一次（见 7.2）。

### 5.2 `ctl`：观测面也是一层

本项目最容易出、最难查的故障是"**错了但不报错**"。VVideo 收拢了四种取帧方式之后风险不会消失，
只会换地方，所以从一开始就把观测做成可读节点：

| 节点 | 内容 |
|---|---|
| `ctl/tree` | 整棵命名空间 + 每个节点的取帧/等待/失败计数 |
| `ctl/mounts` | 挂载表（含每个 sb 上还开着几个文件） |
| `ctl/fs` | 已注册的后端类型 |
| `ctl/log` | 最近 32 条记账日志（HAL 里 logcat 常抓不全） |
| `ctl/stat` | 汇总 |
| `ctl/help` | 用法 |
| `ctl/cmd` | **可写**：`mount <类型> <挂载点> <dev> <选项>` / `umount <目录>` / `mkdir <目录>` |

`cmd` 的意义是**装机之后不必重编 .so 就能改挂载** —— 换个素材试试只需一行文本，
而不是"重编 + 重推 + 重启 provider"。

---

## 6. 接入：HAL 怎么用它

`camshim.c` 只在**一个地方**接进来：模板加载（`portal_mock_load()`）。

```ini
vvideo=1
# <类型>:<挂载点>:<dev>:<选项> ，多条用 | 分隔（选项里有逗号，不能用逗号当条目分隔）
vv_mount=raw:/vvideo/src/front-preview:/data/vendor/camera/ffront.raw:w=2176,h=1256,stride=2176|raw:/vvideo/src/front-video:/data/vendor/camera/fvidmock.raw:w=1920,h=1088,stride=1920
mock_map=4038656:/vvideo/src/front-preview|3194880:/vvideo/src/front-video
```

规则很简单：**`mock_map` 里以 `/vvideo/` 开头的路径经统一层取，其余直接 open 文件**。
取到的帧是**零拷贝**的（节点自己的内存，`file` 一直开着），失败一律安静降级回老路 ——
HAL 进程里没有"因为新功能起不来"这一说。

`vvideo=0`（默认）时行为与接入前**逐字节一致**。

---

## 7. 实测证据

### 7.1 host 自测（`sh scripts/test-vvideo.sh`）

`gcc -fsanitize=address,undefined`，**100 项检查全过**，ASan/UBSan 无任何报错、无泄漏。
覆盖：命名空间与 mkdir/ENOENT、挂载点必须已存在、EBUSY（还有打开的文件）、
**raw 删源文件后仍逐字节正确 + 挂载前后 fd 数不变**、几何与"Y 之后就是 UV"的平面切分、
shm 的 futex 事件（10 次发布 = 10 次唤醒，最慢一次 < 300ms 而超时上限是 2000ms）、
变换链代数（`rot90cw+rot90ccw` 与 `flipud+flipud` 恒等；`flipud+rot90cw` 的逆是 `rot90ccw+flipud`；
单步 `rot90cw` 与手算结果逐字节一致）、ctl 控制面（tree/mounts/log 可读、cmd 可写并真的改了命名空间）、
以及**收尾卸载**（递归释放路径最容易漏内存，所以让 ASan 对"它"下判断）。

### 7.2 自测抓到的两个真 bug（都不是理论问题）

| bug | 症状 | 根因 |
|---|---|---|
| 卸载时 **double free** | ASan 直接报 `double-free in free_dentry_tree` | 递归函数自己会释放子 dentry，外层又 `free()` 了一次 |
| `vv_seq_printf` **静默截断在 1023 字节** | `ctl/tree`、`ctl/log` 永远只有一屏 | 一次写入超过当前容量时，`vsnprintf` 返回的是"本来需要多少"，代码却按返回值累加并钳到容量上 |
| 路径解析**不穿挂载点** | `open("/vvideo/src/x")` 拿到挂载点那个空目录 inode ⇒ 取帧永远 `-ENOTTY` | VFS 里挂载是**覆盖**，第一版没实现这一步 |
| 变换链**只有第一个 op 生效** | `rot90cw+flipud` 变成 `rot90cw` | 分隔符 `,` 与挂载选项的分隔符是同一个，后半个 op 被 `vv_opt_str` 吃掉 |

第二条尤其值得记：它的表现是"看起来就是这样"，而不是报错 —— 正是本项目最怕的那类故障。
**开发机上的自测比真机先抓到了它**，这就是这套自测存在的理由。

### 7.3 真机（ACE5 / ColorOS 16 / AIDL HAL，`cam=1` 前置）

| 项 | 结果 |
|---|---|
| 层在 HAL 里活起来 | `VVideo 统一层已就绪：挂载 2 个节点（失败 0），ABI v1` |
| 两个落地节点都经层取帧 | `注入：模板 ← /vvideo/src/front-preview（**经 VVideo 统一层**，后端=raw，2176x1256，4038656 字节，零拷贝）`；`.../front-video（1920x1088，3194880 字节）` |
| **A/B 等价**（前置预览） | 老路（直接读 `ffront.raw`）与新路（经统一层）两张截图**逐像素相同**：`DIFF mean=0.00 std=0.00 max=0`；对照组日志里 `经 VVideo` 出现 **0** 次 |
| **前置录像** | 8.0 s / 239 帧 @30fps / 1080×1920；抽 3 帧统计完全一致（静态卡片，非实景）；**目视确认就是我们的前置录像测试卡**（TOP/BOTTOM、TL/TR/BL/BR 手性标记方向正确，未镜像未旋转） |
| 注入靶 | `注入线程：10 块缓冲，共 31948800 字节（2 个模板）` —— 打的是 3,194,880 的前置录像面 |

### 7.4 复现命令

```bash
# 1) 开发机上跑层自测（不需要设备）
sh scripts/test-vvideo.sh

# 2) 设备上：部署带 VVideo 的配置并重启 provider
adb push camera-hal/build/intermediates/cxx/Debug/*/obj/arm64-v8a/camshim.so /data/local/tmp/camshim.new.so
adb shell su -c 'sh /data/local/tmp/deploy.sh "real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;\
ops_index=1;dump=1;report_period=8;req_vaddrs=0x1e49ca0;hook_rec_vaddrs=0x1e46f60;hook_rec_ol_vaddrs=0x6cadc;\
cam=1;inject=1;inject_us=0;vvideo=1;\
vv_mount=raw:/vvideo/src/front-preview:/data/vendor/camera/ffront.raw:w=2176,h=1256,stride=2176|raw:/vvideo/src/front-video:/data/vendor/camera/fvidmock.raw:w=1920,h=1088,stride=1920;\
mock_map=4038656:/vvideo/src/front-preview|3194880:/vvideo/src/front-video"'
adb logcat -s PortalCamShim:I | grep -E "VVideo|经 VVideo"

# 3) App → 视频页 → 翻转（前置）→ 录像
adb shell am start -n com.oplus.camera/.Camera
adb shell input tap 420 2150     # 视频页
adb shell input tap 1054 2411    # 翻转（前置 = HAL id 1）
adb shell "dumpsys media.camera | grep 'is open'"   # 必须看到 Device 1
adb shell input tap 631 2410     # 开始 / 停止录像
```

### 7.5 回退

把 cfg 里的 `vvideo=1` 改成 `0`（或删掉 `vv_mount`/把 `mock_map` 指回真实文件路径）即可，
**不需要重新编译**：老路径一行没动过。

---

## 8. 未验证 / 已知边界

- **`shm` 后端只在 host 自测里验证过**（memfd + futex 跨线程）。设备上的**跨进程**用法
  （app 产帧 → HAL 取帧）还没接，因为那需要 app 侧一起改；现有 `cammock.frames` 通道
  仍是老实现（`portal_fsrc_*`）。
- **`gen` 的 `card` 模式**是新画的合成卡片，与 `scripts/gen-camera-mock.py` 生成的那张
  测试卡**不是同一张图**（不追求逐字节一致）；它的用途是把"素材本身"从故障排查里排除掉。
- **`xform` 的色度平面变换**（`chroma=1`）只在代码里，未在真机上用过 —— 目前两条落地
  节点是 `raw`（不透明整块），不走色度路径。
- **`xform` 对每帧变换（`cache=0`）的性能没测**。静态源默认 `cache=1`（只算一次），
  热路径零成本；动源每帧一次转置的代价还没量。
- **`ctl/cmd` 不做鉴权** —— 它是进程内控制面，HAL 进程里没有"别的用户"能碰到它。
  将来若有跨进程入口，必须先加门禁（参考 `RemoteCommandHandler` 的 uid 判定）。
- 层只在 **camshim**（HAL 包装层）里接了模板加载这一条路。`vcam.c`（自建虚拟摄像头）
  与照片路径**尚未接入**。
- 32 位（armeabi-v7a）**也在编译**（CMake 里同一批源文件），但**未在 32 位设备上验证过**；
  `vv_memfd_create` 走的是 `syscall(__NR_memfd_create)`（避开 bionic API 30 的导出限制），
  内核侧从 3.17 起就有。
