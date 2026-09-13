# 摄像头 HAL 包装层 — 兼容性分析

> 本文只谈**兼容性**：这套东西在什么条件下能用、哪里会因为平台/系统差异而失效、换一台机器要重新测什么。
> 机制与逐字节布局见 `docs/camera-mock.md`；本文不重复那些实测数据，只引用章节号。

## 1. 适用范围（前提条件，不满足就无解）

这套方案的立足点是**在系统层替换摄像头 HAL**，因此它**天然只适用于高通 camx 相机栈**：

| 前提 | 说明 | 不满足会怎样 |
| --- | --- | --- |
| 相机 HAL 是 QTI camx（`camera.<soc>.so` + `camera.oemlayer.so` + `camx.*.so`） | 全部钩子都打在这几个模块的**内部偏移**上（§32/§33/§44.2） | MTK / Exynos / Tensor 等一律不适用；偏移无意义 |
| HAL 加载器会先扫某个**可写（可叠 overlay）**的目录 | 实测该平台 `/odm/lib64/hw` 先于 `/vendor/lib64/hw` 被扫（§42） | 只能去叠 `/vendor`，而那会**打断 `media.swcodec`**，拍照直接坏掉（§25.1） |
| 有 root + 模块系统（Magisk / KernelSU Next）能在 `post-fs-data` 阶段挂载并改 sepolicy | 冷启动自动部署靠它（§52） | 只能运行期手工部署，重启即失效 |
| 目标相机 App 的输出面能被 CPU 侧 mmap 写 | 我们按 dmabuf fd 直接 `mmap`，不依赖 SW usage 位 | 若 buffer 由受保护/安全内存承载（如 secure camera），写入会失败 |

**明确不在范围内**：非高通平台；`PROTECTED`/secure 会话；需要注入目标 App 才能生效的做法
（本项目红线：**零注入目标应用**，一切在系统层完成）。

## 2. 已实测的两种平台形态（兼容性的两个活样本）

| 维度 | 形态 A：**32 位 / HIDL** | 形态 B：**64 位 / AIDL** |
| --- | --- | --- |
| SoC 平台 | `msm8998` | `pineapple`（SD8Gen3 级） |
| Android / ROM | 较老的 QTI ROM | Android 16 / ColorOS 16 |
| Root | Magisk | KernelSU Next |
| HAL 接口 | HIDL 2.4 | AIDL（`api=0x0205`） |
| `hw_module_t` 头长度（`real_hmi_bytes`） | **176** | **376** |
| `device->ops` 位置 | 直接取（`ops_index` 默认可） | **`ops_index=1`**（`ops` 为 NULL，需兜底扫描，§32.3） |
| 真件导出符号 | （本文未逐项核对） | **没有** `process_capture_request` / `configure_streams` ⇒ `usage_patch` 那条正路不可用，只能**按地址挂钩** |
| 可按地址挂钩 | 无（32 位无此实现，桩为空） | **有**（arm64：`ldr x16; br x16` + 重放，§23） |
| 取景器布局 | 一个面 | 后置/前置**不同**：Y 面 1200 行 vs **1256 行**，色度面偏移差 121,856（§47.2） |
| 录像面格式 | （未逐字节测） | 前置**线性**、后置**压缩**（§55.3） |

⇒ **结论一**：这套方案在同一套 camx 栈上有两种"落地面"，**32 位形态主要靠 ops 表包装 + 帧通道，
64 位形态才用得上按地址挂钩**。两者共用同一份 shim 源码（32 位分支是桩）。

## 3. 兼容性依赖清单（逐项可自检）

新机型接入前，按下表逐项确认；任一项不满足，先解决它再谈注入。

| # | 依赖 | 怎么确认 |
| --- | --- | --- |
| 1 | 真件 HAL 路径与大小 | `ls -l /vendor/lib64/hw/camera.qcom.so`（或 32 位的 `/vendor/lib/hw/camera.<soc>.so`） |
| 2 | 加载器扫描顺序 | 在候选目录放一个**符号不全**的同名 .so，看日志里 `CamxLoad: load /…` 走的是哪一个；或直接看 §42 的方法 |
| 3 | `hw_module_t` 头长度 | 打开真件后 dump 前 96 字节，从 `tag/version/module/priv` 之后推断（§32.2） |
| 4 | `device->ops` 槽位 | `ops_index` 逐个试，日志会打"槽位 [n]=…(exec=…)" |
| 5 | 钩子偏移 | 三种钩子各自定位法见 §44.2：调用链回溯 + 调用者偏移 |
| 6 | 各输出面的尺寸/布局 | `raw_dump=<尺寸>` + `portal_dmabuf_report`（§44.2 有完整命令） |
| 7 | 显示/录制变换（旋转/镜像） | 手性探针（大号 `F` + 四角标记），8 种二面体变换比 IoU（§50.1） |
| 8 | 写入策略（盲写/成簇） | 按"每轮总字节量"选：小（≤百 MB/轮）盲写即可；大（数百 MB/轮）必须成簇，否则饿死 ISP（§47.4） |
| 9 | sepolicy | 叠 tmpfs-upper 的 overlay 需要 `tmpfs dir/file/lnk_file` 三条（§52） |

## 4. 症状 → 病因 → 处置（历史上的坑，按"看到什么"索引）

| 现象 | 病因 | 处置 |
| --- | --- | --- |
| provider **崩溃重启循环**，日志 `CamxLoad: Can't access file /odm/…oemlayer.so` + `__cfi_slowpath` | `/odm` overlay 的 upper/work 在 tmpfs 上，HAL 域没有穿过 tmpfs 的权限 | 补 `tmpfs dir/file/lnk_file` 三条（§52）——**只在开机路径现形，运行期手工部署不会暴露** |
| 按快门后**照片永不落盘**，日志 `GraphicBufferMapper: gralloc-mapper is missing` | 叠了 `/vendor/lib64/hw`（打断了 `media.swcodec`） | 只叠 `/odm`，**永不碰 `/vendor`**（§25.1） |
| 取景器变成**规则噪声/花屏** | 清了预览流的 UBWC 位，但产帧端没在跑（真实相机仍按平铺写） | `usage_patch` 只允许"产帧端活着"时生效（§15.8） |
| **录像**变平铺噪声 | 清了编码器流的 UBWC 位 | 编码器流一律不动（§15.8）；4K 上清掉会让录像 38 帧就断（§54.4） |
| 注入"**完全没反应**"，日志一行都没有 | `mock_map` 指向的模板文件不存在 ⇒ `tpl==NULL` ⇒ 线程静默找不到靶 | 看 ERROR 行 `注入：打不开模板 …（errno=2）`（§49.3） |
| 前置取景器有**圆盘**（中央清晰、四周发白） | App 自身的显示处理 | **不是缺陷**，不用处理（§48） |
| 照片偶发**残缺 JPEG**（`broken data stream`） | 概率性撕裂（写入与 ISP/编码器抢同一块） | 拍完校验 + 重拍；或改用"盲写 + 只改一处"的短路径（§39） |

## 5. 移植到新机型：清单

1. **摸清 HAL 形态**：真件路径、32/64 位、HIDL/AIDL、`hw_module_t` 长度、`device->ops` 槽位（§3 表 1–4）。
2. **定位三类钩子**：请求路径、结果路径、`configure_streams` 等价的 oemlayer 函数（§44.2 的回溯法）。
3. **量各输出面**：预览 / 照片 RAW / 录像，各自的**尺寸 + 平面布局 + 色序（NV12/NV21）**（§44.2）。
4. **定显示与录制变换**：手性探针 → 反变换写进生成器（`scripts/gen-camera-mock.py` 的 mode 表）。
5. **定写入策略**：按每轮字节量选盲写或成簇；量"接管率 + 帧纯净度"两个指标（§47.4 的判据）。
6. **接模块**：改 `scripts/build-camera-hal-module.sh` 的 `pack_one` 参数（平台名、载荷目录、真件路径、HMI 字节数、ops 槽位），
   `customize.sh` 只负责把真件拷到 `orig/`——**安装期一个字节都不碰分区树**（否则整包回滚）。
7. **冷启动实测**：装包 → 重启 → 看 provider 是否 `running`、有无新增 tombstone、拍照是否正常（§52.2 的表）。

> **可移植的是方法，不可移植的是参数**：钩子偏移、面布局、显示变换、写入策略全部**因机/因 ROM 而异**，
> 必须逐台实测；把它们照抄到另一台机器上，最可能的结局是 provider 崩溃或画面花掉。

## 6. 与其他模块 / 系统组件的共存

| 项 | 观察 |
| --- | --- |
| 与 LSPosed / Zygisk / susfs 等模块共存 | 实测同机同时装着 6 个模块（`ksud module list`：本模块 + LSPosed、Zygisk、susfs、tricky_store、WorkSettingPro）时，本模块部署与相机功能均正常，且本模块全程 enabled=true |
| 模块安装顺序 | 本模块**不参与** `system` 分区树挂载（ACE5 形态下只叠 `/odm`），因此与其它模块的挂载顺序**无耦合** |
| 与目标 App 的 Xposed 模块（本项目另一半） | 二者**互不依赖**：模拟完全在系统层；关掉 Xposed 侧不影响相机接管，反之亦然 |
| 卸载/禁用 | 禁用模块 + 重启即回到厂商原始行为；**运行期**回滚只需 `umount /odm/lib64/hw; umount /data/local/tmp/odmtmp; setprop ctl.start vendor.camera-provider` |
| 安全补丁/SELinux 收紧 | 若 ROM 收紧 `hal_camera_default` 对 `tmpfs`/`shell_data_file` 的访问，需要在模块 `sepolicy.rule` 里补规则（§52 就是这一类） |

## 7. 未验证 / 已知不兼容的边界（别默认它行）

| 项 | 状态 |
| --- | --- |
| 非高通平台 | **不适用**（前提不成立） |
| 后置**录像**接管（1080p 与 4K） | **不兼容当前做法**：两个面都是厂商**压缩面**，线性/瓦片写入会被解成绿或黑（§53/§55） |
| 小时级连续录像 soak | **未验证**（最长 32 秒） |
| 人像 / 专业 / 60fps / 前置 4K / 慢动作 / 延时等模式 | **未验证** |
| 长时间反复开关相机的累积行为 | **未验证**（shim 有无缓慢泄漏未测） |
| 32 位形态下的按地址挂钩 | **不可用**（无该实现，只有桩）；32 位靠 ops 包装 + 帧通道 |
