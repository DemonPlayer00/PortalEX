# 摄像头模拟（Camera Mock）：设计、实测事实与冻结状态

> 本文只写"读完代码也未必知道"的东西：**架构取舍**、**真机实测出来的事实**（每条都有命令或日志出处）、
> 以及**卡在哪、从哪接着做**。代码里的注释负责局部原因，本文负责全局。
> 冻结时间：2026-09-12（用户裁决"冻结归档"），**2026-09-12 二次开工：HAL 数据面已打通**（见 §9）。
> 当前线上配置是安全的：`portal.cfg` 全 0 ⇒ 纯转发，真实相机直通。

## 0. 一句话现状

| 部分 | 状态 |
|---|---|
| app 侧：视频点库 / 录制 / 导入 / 标签 / 地图选点 / 选片引擎 / 跨进程投递 | ✅ 已完成，真机验证通过 |
| system_server 控制面：谁在何时用了哪个摄像头（**应用 0 注入**） | ✅ 已完成，真机验证通过 |
| 模块侧 app hook 内容替换（Camera2 换面 + GL 喂帧） | ✅ 已实现并真机验证（**当前停用**，见 §4） |
| HAL 数据面：**改应用 buffer 的像素**（camshim 路线） | ✅ **已打通**（2026-09-12）：应用预览画面 100% 由 HAL 内 shim 写入，3–7 ms/帧，见 §9 |
| HAL 数据面：自建虚拟摄像头（vcam 路线） | ⛔ 仍冻结（§5.3 的会话链路问题未动；camshim 路线已够用） |
| 真实视频帧喂进 HAL：HAL 侧「帧文件」通道 | ✅ 已打通并验证（2026-09-12）：HAL 从共享文件取帧写进相机 buffer，预览里就是视频画面，4.8 ms/帧（§12） |
| app 侧产帧端（解码 → 写共享帧文件） | ✅ 已打通并真机验证（2026-09-12）：Aperture 预览里是**解码出来的视频画面**，见 §13 |
| 端到端（选片 → 解码 → 共享文件 → HAL 缩放 → 相机 buffer） | ✅ 已验证；app 被杀/关开关后自动回到真实相机（§13.5） |
| 模拟页的地图操作（点位/表象位置标记、可折叠、卫星图） | ✅ 已实现并真机验证（2026-09-12，§14） |
| **拍照（JPEG/BLOB 流）也吃虚拟帧** | ✅ 已实现并真机验证（2026-09-12，§15）：照片就是视频帧、**2592×1944**（跟随相机请求）、长度由我们的 `camera3_jpeg_blob` 决定（文件尾部 0 填充） |
| 录像（编码器流） | ⛔ 不接管：编码器按 UBWC 读，清了 UBWC 录到的是噪声 ⇒ 该流一律跳过，**录像内容退回真实相机**（§15.8） |
| 照片触发方式 | ✅ 按需编码：HAL 在请求阶段通知（`photo_req_seq`），不再周期性编 5 MP（§15.10） |
| 线上配置 | 模块**启用**，挂 `camshim.so` 的**纯 M0**（只换 `process_capture_request` 做计数）：实测相机预览正常、帧计数在涨（`#240`） |

## 1. 三条路线与各自结论（为什么最后冻结在 HAL 这一层）

用户口径（2026-09-12）：**拦截改为系统侧实现、应用 0 注入**。沿着这个口径试了两条系统侧路线：

1. **在外层包住 vendor HAL**（`camera-hal/src/camshim.c`）：拦它的 camera3 C API，往**它的**输出
   buffer 写画面。
2. **自建虚拟摄像头 HAL**（`camera-hal/src/vcam.c`）：自己就是 HAL，帧自己产出、buffer 用途位自己提。

第 1 条被实测堵死（§5.2），第 2 条打通到"应用能打开、能配流、能产出帧写进 buffer"，但请求链路
还有若干层框架要求没走完（§5.3）。第 3 条（复用模块侧 app hook）是**唯一已被证明能真正替换画面**的
实现，见 §4。

## 2. app 侧（已完成，真机验证）

代码：`app/src/main/java/moe/fuqiuluo/portalex/ui/camera/`、`service/CameraMockProvider.kt`，
共享层在 `xposed/src/main/java/moe/fuqiuluo/xposed/camera/`。

- **数据模型与选片规则**（`CameraMockModel/Codec/Selector.kt`，纯函数 + 19 个单测）：
  标签 **全含**语义；最近命中点（同距按 id 定序）；点内随机取一段；
  起播偏移取 `[0,时长)` 的**随机整秒**；播到末尾回 **offset 0** 循环；位置未知时在全部命中
  素材里等概率取（不当 0,0）。
- **库与素材**：元数据存 `portal` 偏好的一个 JSON 串、视频本体在 `filesDir/camera`（`CameraMockStore`）。
- **录制**：`CameraRecordActivity` 用 Camera2 + MediaRecorder **应用内**录制（不走系统相机 Intent），
  **默认前置**、只录视频轨、60 秒上限；预览尺寸取"显示方向"（传感器 90/270 时转置）并锁竖屏，
  录制尺寸用传感器方向 + `setOrientationHint`；**不镜像**（录下来的文件要原样喂给别的应用）。
- **跨进程投递**：`CameraMockProvider`（authority `moe.fuqiuluo.portalex.cameramock`）——
  `call` 回清单/位置/收上报，`openFile` 发**视频 fd**（被注入进程没有文件路径权限，只能拿 fd）。
  门禁按 `Binder.getCallingUid()`；**开关关闭时照样回一份 `enabled=false` 的清单**，
  否则"关掉开关"这条指令永远传不到已注入的进程。
- **页面**：`CameraMockFragment`（同路线模拟页结构 + 标签筛选 + 预计命中提示 + ⓘ 对话框）、
  `CameraPointEditFragment`（地图准星取点，gcj02 → 入库转 wgs84）。

## 3. system_server 控制面（已完成，应用 0 注入）

代码：`xposed/src/main/java/moe/fuqiuluo/xposed/hooks/camera/CameraWatch.kt` +
`xposed/src/main/java/moe/fuqiuluo/xposed/camera/CameraWatchState.kt`（纯逻辑，10 个单测）。

- 挂 `AppOpsService`（`startOperation`/`finishOperation`/`noteOperation`/`noteOperationUnchecked`，
  `OP_CAMERA`）+ `CameraServiceProxy`。
- **热路径纪律**：先按参数下标取 op code 早退（绝大多数调用不是相机）；下标按"第几个 Int /
  第几个 String"推并缓存；活跃期内的重复记账交给纯逻辑核去抖（CameraService 会按捕获请求反复
  记账，可能逐帧）。
- **类名与类加载器多候选逐个试**，都不中则退到 `SystemServiceManager.startService` 延迟捕获
  （用实例的类挂）—— 实测本机类名是 `com.android.server.appop.AppOpsService`（Android 10+ 布局）。
- 回读走命令通道 `get_camera_events`（Cmd 30→31 / Key 40→41，计数断言在 `PortalProtocolTest`）。
- 真机证据：`startOperation uid=10135 pkg=org.lineageos.aperture` → Test 页显示
  `计数=开 1 / 关 1` + 开/关两条记录。

## 4. 模块侧 app hook 内容替换（已实现、已验证、**当前停用**）

代码：`xposed/src/main/java/moe/fuqiuluo/xposed/hooks/camera/`（`Camera2MockHook`、`CameraMockController`、
`CameraVideoPlayer` 1006 行、`CameraMockClient`、`ProviderVideoSource`）。

- 拦截形态：`CameraDeviceImpl#createCaptureSession(List<Surface>,…)` 把应用的输出面**换成我们的排水面**
  （640×480 YUV ImageReader，持续取走丢掉），真实相机照常跑但画面进桶即丢；应用原来的面由
  `CameraVideoPlayer` 用 GL 直接喂视频；`CaptureRequest$Builder#addTarget` 把应用的面重定向到排水面。
  **不伪造任何系统对象**，任何一环失败就完全不替换（应用照旧看真实摄像头）。
- **为什么当前停用**：它需要把目标应用加进 LSPosed 作用域，与"应用 0 注入"的裁决冲突。
  但它是唯一已装机验证"画面真被换掉"的实现；要收成成品，只需启用此路径并接上 §2 的库与选片引擎。

### 4.1 一个必须记住的回归（本会话踩到并已修）

在 M1a 阶段我给 camshim 加了 `initialize`（包框架回调）与 `configure_streams` 两处接管，
**结果应用看到黑屏**（即便注入代码被编译期关掉也一样）—— 说明这两处接管本身就会破坏
vendor HAL 的图形/回调契约。修复方式：camshim 只保留 `process_capture_request` 一处接管
（只计数、不改任何东西），并加 `PORTAL_CAMSHIM_ENABLE_INJECTION` 编译开关（默认 0）。
改回之后实测：**预览正常 + 帧计数在涨**。教训：在 HAL 层，"看起来无害"的接管也要单独验证画面。

## 5. 数据面 HAL：实测事实表（本文最有价值的部分）

### 5.1 进程拓扑与可注入性

```
1373     1 cameraserver  cameraserver                                ← 父进程 init，独立原生进程
1038     1 cameraserver  android.hardware.camera.provider@2.4-service ← 摄像头 HAL 也是 init 起
service list: media.camera 属 cameraserver；system_server 只有 media.camera.proxy + appops
```
⇒ **视频流从不经过 system_server**；cameraserver/HAL 不经 zygote，LSPosed 注入不到。
"在 system_server 拦截视频流"物理上不成立；system_server 只能做控制面（§3）。

### 5.2 包住 vendor HAL 路线（camshim）卡在哪

- 覆盖 `/vendor/lib/hw/camera.msm8998.so` 后，我们的 shim 确实被 provider 加载（构造函数跑起来），
  也能 dlopen 原件副本（`camera.msm8998.orig.so`）。
- 但**应用那批输出 buffer 既不能 CPU 写、也不能当 EGLImage**：
  · `GraphicBufferMapper::getUsage` → **rc=-38 (ENOSYS)**（这些 buffer 没有 mapper 描述符）
  · `eglCreateImageKHR` 三种属性组合全部失败 → **eglGetError=0x300c (EGL_BAD_PARAMETER)**
  · `lock`/`lockYCbCr` 对不可 CPU 映射的 buffer 是 **abort**（打死过 provider 三次）
  ⇒ "改别人的 buffer"既写不进也画不进，且**无法安全探测**（abort 型 API 不能拿来试探）。

### 5.3 自建虚拟摄像头路线（vcam）做到哪一步

已打通（都有日志）：
- `get_number_of_cameras`/`get_camera_info` **整份转发真 HAL** ⇒ 框架认到 4 个相机（元数据自己编必被 CameraX 拒）；
- device 被框架接受 → `initialize` → `configure_streams`（可给流补 SW 用途位：实测 `0x900 → 0x933`）→
  `construct_default_request_settings`（转发真 device 的模板，非空）→ 进入捕获循环 →
  **产出帧并写进应用 buffer**：
  ```
  I PortalVCam: 已用 lock() 兜底写入（lockYCbCr 不可用）
  I PortalVCam: 虚拟相机已产出帧：#1 (frame=0)
  ```
卡点（本轮冻结时）：
```
配流时总账：请求=0 产帧=0
Camera3-Device: Set real time priority for request queue thread      ← 框架请求队列线程已起
Active Camera Clients: (Camera ID: 1, State: 2, Package Name: moe.fuqiuluo.portalex)  ← 框架认为在流式
```
即**框架认为客户端在流式请求，但 `process_capture_request` 一次都没到 HAL**；ops 表 5/6/7/8 槽的
探针也没被调用 ⇒ 请求停在**框架/HIDL 层到 HAL 之间**，不是我们的写帧路径。

### 5.4 逐条实测事实（踩一次记一条，别再凭记忆写代码）

| 事实 | 出处 |
|---|---|
| `HMI` 符号实测 **176 字节**，是 `camera_module_t`（含 `get_number_of_cameras` 等回调），不是裸 `hw_module_t`(128) | 少声明 48 字节 ⇒ provider 读相邻内存当"相机数量" ⇒ `libutils` Vector 断言溢出 → SIGABRT → init 每 5 秒重启 provider |
| `HARDWARE_MODULE_TAG` 必须精确 `'HWMT'`=0x48574D54 | 写错不是"不生效"，是 provider 每 5 秒崩 |
| device 版本号必须与真 device 对齐：本机 **0x304** | 写 0x300 ⇒ 框架 open 后立刻关设备，`initialize` 根本不会被调用 |
| `camera3_stream_t::max_buffers` **必须由 HAL 填** | 不填 ⇒ `configureConsumerQueueLocked: Camera HAL requested max_buffer count: 0` ⇒ `-38` ⇒ `onConfigureFailed` |
| 结果元数据**不能是 NULL** | `Camera2-Metadata: acquire: Failed to validate metadata structure 0x0` ⇒ 会话作废 |
| `ANDROID_SENSOR_TIMESTAMP` 本机值 = **0x000e0010** | 与 AOSP 记忆的 0x00090003 不同。取法：`libcamera_metadata.so` 导出 `get_camera_metadata_tag_name(tag)`，扫 (section×index) 反查真名 |
| `camera3_capture_request_t` **布局与 AOSP 头文件不一致**：本机 `num_output_buffers` 在 `output_buffers` **之前** | 日志 `frame=0 settings=<ptr> raw[8]=0x0 raw[12]=0x1 raw[16]=<ptr>`；按头文件解引用 ⇒ `SIGSEGV fault addr 0x1`（反汇编确认 `ldr r0,[r0]`） |
| `GraphicBufferMapper` 的 `lock`/`unlock`/`getUsage`/`lockYCbCr`/`preloadHal` 都是**非静态成员函数** | 当普通函数调 ⇒ `this` 错位 ⇒ 入口第 2 字节 SIGSEGV。单例指针从导出的全局 `Singleton<GraphicBufferMapper>::sInstance` 读（`get()` 没有导出符号） |
| `lockYCbCr` 对本机相机 buffer 始终失败；**老的 `lock()` 可用** | vcam 就是靠 `lock()` 兜底写出第一帧 |
| `libui.so` 在 Android 15 上**没有** `lockYCbCr`？—— 有，但长度前缀是 9（`9lockYCbCr`） | grep mangled 名字要宽松，我按 8 写漏过一次，白绕一圈 |
| SELinux：`/data/adb/portalex/**` 是 `adb_data_file`，HAL 域读不了（dlopen 报 not found，实为 EACCES）；模块里**新增**文件按源路径 `system/**` 标成 `system_file`，替换已存在文件的那份继承 `vendor_file` | 原件副本要放**同一个 vendor 库目录换名字** |
| `magiskpolicy` 语句是 `allow <s> <t> <class> { perms }` | `target:class p1,p2` 报 Syntax error 且**静默不生效** |
| 模块 disable → enable 切换后 `sepolicy.rule` **有时不再被应用** | 改用 `post-fs-data.sh` 里 `magiskpolicy --live` 再打一次 |

## 6. 复现、切换与回退（命令级）

```sh
# 构建（32 位 shim / 虚拟相机）
./gradlew :camera-hal:assembleDebug
# 产物：camera-hal/build/intermediates/cxx/Debug/*/obj/armeabi-v7a/{camshim,vcam}.so

# 部署（二选一）：
adb push <so> /data/local/tmp/x.so
adb shell su -c 'cp -f /data/local/tmp/x.so \
    /data/adb/modules/portalex-camera-hal/system/vendor/lib/hw/camera.msm8998.so; \
    kill $(pidof android.hardware.camera.provider@2.4-service)'
#   注：替换 .so 内容后 kill provider 即重新加载，无需重启；
#       模块本身首次安装/改动 module.prop 后需重启。

# 回退到安全状态（当前线上配置）：
adb shell su -c 'touch /data/adb/modules/portalex-camera-hal/disable && reboot'
#   或把 camshim.so（无注入版）拷回上面的 HAL 路径 + kill provider
```

- **camshim.so** = 外层包住 vendor HAL（M0：只接管与转发，安全）
- **vcam.so** = 自建虚拟摄像头（会产帧，但会话链路未走通）

## 7. 下一步（2026-09-12 二次开工后已重排；§9 已完成的项不再列）

1. **请求链路诊断**（vcam）：框架认为客户端 State=2 却没把请求交给 HAL —— 下一手是确认
   HIDL 层（provider impl）是否收到 `processCaptureRequest`；必要时把
   `/vendor/lib/hw/android.hardware.camera.provider@2.4-impl.so` 也包一层打点。
   同时确认 `endConfigure` 在 cameraserver 侧的返回（`dumpsys media.camera` 的 Client State 可对照）。
2. **收成可用成品**（成本最低、收益确定）：启用 §4 的模块侧 app hook 内容替换，
   接上 §2 已有的库/标签/选片引擎/provider 投递 —— 代价是目标应用需要加入 LSPosed 作用域。
3. 若坚持 0 注入：把 HAL 会话链路的剩余框架要求逐条补齐（本表 §5.4 已经省掉大部分试错）。

## 8. 已知边界与风险

- **HAL 进程里任何 abort/段错误都会让整机相机不可用**：只接受"失败返回错误码"的 API，
  abort 型的 API（`lock`/`lockYCbCr` 对不可映射 buffer）**不能用来试探**。
- 装载进 HAL 的 `.so` 必须在所有失败路径上安静降级为纯转发。
- 模块停用/替换后**必须重启**（或至少 kill provider）才会生效。
- app 侧录制只录视频轨（不录音频），且默认前置 —— 素材语义如此，不要当成 bug。


---

# 二次开工（2026-09-12 晚）：HAL 数据面打通

## 9. 结论：应用 0 注入的像素通路已成立

**一句话**：把 `usage_patch=2 + init_wrap=1 + inject=1` 写进 `/vendor/lib/hw/portal.cfg`，
应用（Aperture）的预览画面就 **100% 由 vendor HAL 里的 shim 写入**，应用进程一行代码都没有；
关掉开关即恢复真实相机（实测回退后画面统计量与基线逐项一致）。写整幅 1600×1200 的 CPU 成本
**3–7 ms/帧**。

三个必要条件，缺一不可：

| # | 条件 | 为什么 | 怎么开 |
|---|---|---|---|
| 1 | **给输出流补 SW 读写用途位** | 无 SW 位的相机 buffer 上调 mapper `lock` 是 **abort**（打死过 provider） | `usage_patch>=1` |
| 2 | **清掉 QTI `GRALLOC_USAGE_PRIVATE_0`（UBWC）** | UBWC 是平铺+压缩布局，按线性 stride 写进去，读取方看到的是**规则碎块阵列**（实测：真实场景消失了，但图案形态完全不对） | `usage_patch=2` |
| 3 | **包一层框架回调表**（在 `process_capture_result` 里写） | 时序：HAL 填完 → 我们改写 → 框架交给应用。在 `process_capture_request` 里写会被真 HAL 覆盖 | `init_wrap=1` |

**改用途位必须排在真 HAL 之后**：框架进来时流 usage 只有 `0x900`，`HW_CAMERA_WRITE`/`PRIVATE_0`
是厂商 HAL 在 configure_streams 里加的；框架在它返回后回读整张表（我们补的位就是这么生效的）。

### 9.1 运行期开关文件（本轮新增的实验基建）

`/vendor/lib/hw/portal.cfg`（模块内 `system/vendor/lib/hw/portal.cfg`，Magisk 挂载后标签是
`system_file`，现有 sepolicy 已允许 `hal_camera_default` 读它）。`key=value`，一行一条：

```
dump=1          # 打印请求/结果的真实布局、流参数、gralloc 句柄私有段（只读）
usage_patch=1   # 只补 SW 用途位；=2 额外清 UBWC 位
init_wrap=1     # 包框架回调表（注入的时序前提）
inject=1        # 往输出 buffer 写图案
pattern=0|1|2|3 # 0=渐变+横纹+移动竖条 1=横向渐变 2=纵向渐变 3=纯灰（后三个是布局探针）
freeze=1        # 不把请求转发给真 HAL（只有自己能投结果时才可开）
cam=<id>        # 只对指定相机生效（本机 Aperture 用 id=1）
```

**用法（秒级 A/B，不必重编）**：
```sh
adb push portal.cfg /data/local/tmp/ && adb shell su -c 'cp -f /data/local/tmp/portal.cfg \
    /data/adb/modules/portalex-camera-hal/system/vendor/lib/hw/portal.cfg; \
    kill $(pidof android.hardware.camera.provider@2.4-service)'
```
**坑（实测）**：Magisk **只在开机时**挂载模块文件 —— **新增** `portal.cfg` 后必须重启一次；
此后改内容立即生效（同一 inode，bind 挂载可见）。模块里**替换已存在文件**的那份（我们的 .so）
继承 `vendor_file`，**新增**文件是 `system_file`。

### 9.2 本轮新增的实测事实（接 §5.4，别凭记忆写代码）

| 事实 | 出处 |
|---|---|
| **ops 表是 AOSP 序**：`initialize`=ops[0]、`configure_streams`=ops[1]、`process_capture_request`=ops[4] | 日志 `已接管 process_capture_request（ops[4]）` / `（ops[0]）` / `（ops[1]）` |
| **请求结构是"交换序"**（`num_output_buffers` 在 `output_buffers` 前） | `请求布局#N：w8=0 w12=1 w16=<ptr> → 交换序(out@16,num@12)` |
| **结果结构是 AOSP 序**（`num@8, out@12`），与请求**相反** | `结果布局#240：w8=0x1 w12=<ptr> → AOSP序(out@12,num@8)`；首帧是"部分结果"（全 0）⇒ 解析器必须能判"认不出"并放弃 |
| 框架交给 HAL 的流 usage 只有 **0x900**；最终 buffer 是 **0x10020900**（厂商 HAL 加了 `PRIVATE_0`+`HW_CAMERA_WRITE`） | `改用途位[0]：format=0x22 1600x1200 usage 0x10020900 → 0x20933（UBWC 已清）` |
| `GraphicBufferMapper::getUsage` 在本机恒返回 **-38 (ENOSYS)**，但 `lockYCbCr` **可用** | `buffer 自报：getUsage rc=-38` + `lockYCbCr 成功：ystride=1664 cstride=1664 step=2` ⇒ **不能拿 getUsage 当门**，门应该是"流的 usage 有 SW 位" |
| gralloc 私有句柄（v=12 nFds=2 nInts=24）字段：`[0]`=magic 0x676D6F6D、`[2]`=**stride**(1664=ALIGN(1600,128))、`[3]`=aligned height(1216=ALIGN(1200,32))、`[4]/[5]`=宽/高、`[19]/[21]`=usage 类字段 | `out[0] handle ... ints=0:1735226221 ... 2:1664 3:1216 4:1600 5:1200 ... 19:268568832 ...` |
| **写帧开销几乎全在"逐像素循环"，不在内存** | 逐像素直写 123.77 ms/帧 → 模板+逐行 memcpy 但每帧重建 51.4 ms → 静态模板 **3.1 ms** → 动画图案（静态底图+画竖条）6.4–7.3 ms。即：写内存本身 ≈900 MB/s |
| 映射缓存（每 buffer 只 lock 一次）**没有**改变耗时 | 51.37 → 51.12 ms ⇒ 那 51 ms 与 IPC 无关，是模板重建；缓存保留只是少两次 AIDL 往返 |
| 暗环境下传感器本身约 **12 fps**（AE 拉长曝光），不是我们的通路限速 | `捕获请求速率：120 个用时 10037 ms ≈ 12.0 fps`（注入开/关都一样） |
| 历史"黑屏"**不是**回调包装造成的 | 本轮 `usage_patch=1 + init_wrap=1` 组合下预览完全正常 ⇒ 当年的黑屏出在那版 configure_streams 接管（改 format/max_buffers），与回调表无关 |

### 9.3 复现（命令级）

```sh
# 1) 构建 + 部署（替换 .so 内容后 kill provider 即可，无需重启）
./gradlew :camera-hal:assembleDebug
SO=$(find camera-hal/build -name camshim.so -path '*obj*' | head -1)
adb push "$SO" /data/local/tmp/camshim.so
adb shell su -c 'cp -f /data/local/tmp/camshim.so \
    /data/adb/modules/portalex-camera-hal/system/vendor/lib/hw/camera.msm8998.so; \
    kill $(pidof android.hardware.camera.provider@2.4-service)'

# 2) 开像素接管（首次新增该文件需重启一次；之后改内容即时生效）
adb shell su -c 'printf "usage_patch=2\ninit_wrap=1\ninject=1\n" > \
    /data/adb/modules/portalex-camera-hal/system/vendor/lib/hw/portal.cfg; \
    kill $(pidof android.hardware.camera.provider@2.4-service)'

# 3) 回退到真实相机（把开关清零即可，不必换 .so、不必重启）
adb shell su -c 'echo "dump=0" > \
    /data/adb/modules/portalex-camera-hal/system/vendor/lib/hw/portal.cfg; \
    kill $(pidof android.hardware.camera.provider@2.4-service)'
```

验收判据（截图统计，Aperture 预览区）：真实相机基线 `mean≈8 std≈16 dark≈89%`；
接管后 `mean≈76 std≈83 dark≈19%`、帧间变化 20–25%；回退后逐项回到基线。

## 10. 待办：把**真实视频帧**喂进这条通路

数据面已成立，缺的是"帧从哪来"。两条路，选 **A**：

**A. app 侧解码 + 共享内存通道（选定）**
- PortalEX 用 MediaCodec + ImageReader 解码"选片引擎"选中的那一段，写入一块
  ashmem/MemFD（双缓冲 + 序号），通过 **abstract Unix socket + SCM_RIGHTS** 把 fd 交给 HAL；
- HAL 每帧只做"读序号 → 换缓冲 → 逐行 memcpy 进相机 buffer"（= 今天已经量过的 3 ms 路径）；
- 控制面（开关/标签/位置/选片）**复用已有**：`CameraMockProvider` + `CameraMockStore` 不用改；
- 需要新增的 sepolicy（模块 `sepolicy.rule`/`post-fs-data.sh`）：
  `allow hal_camera_default untrusted_app_all:unix_stream_socket { connectto }` 等几条；
  另外 HAL 需要能读**视频文件**（若把解码放在 app 侧，就只需要共享内存，不需要读 app 私有目录）。

**B. HAL 侧自己解码**（MediaCodec/AMediaCodec + AImageReader 在 provider 进程里）
- 少一条帧通道，但要在 vendor HAL 进程里跑编解码（32 位、SELinux 要放开到 `media.codec`），
  还要能读到视频文件 —— 依赖更多、更容易把 HAL 弄崩。**不选**。

**A 的落地顺序**：① 先把"共享内存 + socket"跑通（HAL 侧只打印收到的帧号/尺寸，不写 buffer）；
② 再接进 `portal_fill_frame` 的源（把模板 memcpy 换成共享帧 memcpy）；③ 最后接选片引擎
（当前选中片段、随机起播秒、播完回 offset 0 循环）。

## 11. 纪律（本轮补充）

- HAL 里**每一条新路径都要单独验证画面**，不能只看"没崩、PID 稳"——本轮"usage_patch=1 时
  图案是碎块"就是只有看图才发现的问题（统计量只告诉你"画面变了"）。
- 只接受"失败返回错误码"的 API；`getUsage` 这类返回错误码的查询可以放心调，
  `lock` 这类会 abort 的必须先过"流的 usage 有 SW 位"这道门。
- 实验条件尽量做成**运行期开关**（`portal.cfg`）：HAL 实验的重编-部署-复现成本高，
  而"改文件 + kill provider"是秒级；出问题也能立刻清零回退。


## 12. 帧通道（app 解码 → HAL 取帧）：协议、MLS 墙与实测

### 12.1 结论先行

HAL 侧的"帧文件"通道**已打通并验证**：把一个 NV12 帧序列文件放到 HAL 读得到的位置，
`src_file=<路径>` 一开，Aperture 预览里出现的就是**视频画面本身**（不是合成图案），
写帧 4.8 ms/帧。缺的只剩 app 侧把解码结果写进这个文件。

### 12.2 为什么是"共享文件"而不是 socket

原本想用 abstract socket + SCM_RIGHTS。实测否掉了 app 私有目录这条路：

```
avc: denied { search } for name="data" scontext=u:r:hal_camera_default:s0
             tcontext=u:object_r:system_data_file:s0:c512,c768 tclass=dir
```

`/data/data/**` 带 **MLS 类别**（`s0:c512,c768`，app 自己还多带一组），而 vendor HAL 跑在
**`s0`（无类别）**。MLS 的支配关系是单方向的（`s0:c197` ⊒ `s0`，反之不成立）⇒ **app 私有目录
对 vendor HAL 是结构性不可达**，加多少条 `allow` 都没用。这是本轮最有价值的一条教训：
**"app ↔ vendor 域"之间共享数据，不能走文件路径，除非给共享对象打上 `mlstrustedobject`。**

于是方案定为：**共享文件 + 自定义类型 + `mlstrustedobject`**（绕过 MLS 约束），
放进一个两域都能到达的目录（本机用 `/data/local/tmp/portalex/`，标签换成自定义类型）。

### 12.3 让这条路生效需要的四条（缺一条都不行）

| # | 需要什么 | 命令/规则 | 实测现象 |
|---|---|---|---|
| 1 | 自定义类型 + `mlstrustedobject` | `type portalex_frame_file { mlstrustedobject }` | 只有类型不够：写侧的 MLS 约束要求 `t2 == mlstrustedobject` |
| 2 | **类型与文件系统关联** | `allow portalex_frame_file labeledfs filesystem associate` | 少了它 `chcon` 直接 `Permission denied`，AVC 是 `denied { associate } ... tclass=filesystem` |
| 3 | 生产侧（app）能写 | `allow untrusted_app portalex_frame_file dir { write add_name search }` + `file { create write open map }` | —— |
| 4 | 消费侧（HAL）能读 | `allow hal_camera_default portalex_frame_file dir { search }` + `file { read open map }` | 生效后日志出现 `帧文件已挂上：…` |

**坑**：`magiskpolicy --apply <文件>` 里 `type_transition … "name"`（带 object_name 的那两行）
会让 magiskpolicy **段错误**；类型/属性/allow 都正常。所以本轮改成"**预建文件 + chcon**"，
不依赖 type_transition。（另一个坑：`--live` 语句里带引号会被 su→sh 吃掉，用 `--apply` 文件最稳。）

### 12.4 帧文件协议（HAL 与 app 共用，`camera-hal/src/camshim.c` 里是权威定义）

```
偏移 0  头 80 字节（小端）：
  0  magic 'PEXF'=0x46584550   4  version=1
  8  width        12 height    16 y_stride   20 uv_stride
  24 format(0=NV12)            28 slot_bytes
  32 slots        36 enabled   40 seq        44 write_idx
  48 flags        52 reserved
  56 timestamp_ns (u64)
  64 consumer_w   68 consumer_h  72 producer_state  76 reserved2
偏移 4096 起是槽位数据：第 i 槽 = 4096 + i*slot_bytes，槽内先 Y 后交织 UV（NV12）
```

- **生产者（app）**：写数据 → 更新 `write_idx` → `seq++`（顺序不能反）。`enabled=0` 是总开关，
  HAL 见到就完全不接管（露出真实相机）。
- **消费者（HAL）**：只在 `seq` 变化时换槽；**没新帧就重发上一帧**（绝不能"没新帧就不写"，
  否则画面会在真图/假图之间闪）。
- `flags & 1` = "HAL 自行轮转槽位"：**不需要 app**，用于离线验证/演示（本轮就是这么验的）。
- `consumer_w/h` 由 HAL 回写它实际的流尺寸，app 据此配置解码器（Surface 上的缩放是免费的）。

### 12.5 本轮验证方式（离线可复现）

```sh
# 1) 从设备上已有的录制素材里抽 24 帧，缩到相机流尺寸（1600×1200），转 NV12
ffmpeg -i v.mp4 -vf "fps=8,scale=1600:1200" -pix_fmt nv12 -frames:v 24 -f image2 -c:v rawvideo f%03d.nv12
# 2) 按上面的协议拼成帧文件（头 + 24 槽，flags=1）
# 3) 放进共享目录 + 打标签 + 下规则（见 §12.3 四条）
# 4) portal.cfg: usage_patch=2 / init_wrap=1 / inject=1 / src_file=<路径>
```
验收：日志 `帧文件已挂上：…（1600x1200 stride=1600/1600 槽=24 … flags=1）`、
`写帧耗时：… 平均 4.80 ms`，截图里是**视频画面**（本轮是录像里的灯光场景，颜色正确 ⇒
"目标色度是 NV12 U 在前"这一条同时被证实：`cb=0xd308f000 cr=0xd308f001（NV12(U前)）`）。

### 12.6 app 侧待办（下一步）

1. `CameraFrameSink`：`FileChannel.map` 共享文件，写头、按 §12.4 发布帧（`VarHandle` 做 release 写）；
2. `CameraMockVideoSource`：`MediaExtractor` + `MediaCodec`（输出到 **ImageReader**，
   尺寸取 `consumer_w/h`，**缩放交给 Surface/GPU**）+ 按 PTS 节流 + 播完回起点循环；
3. 接现有选片引擎（最近命中点 + 标签全含 + 随机起播秒）；
4. 模块化：`post-fs-data.sh` 建目录/打标签/预建文件，`sepolicy.rule` 收 §12.3 的四条规则。


## 13. app 侧产帧端（已打通）：从"合成图案"到"真视频"

### 13.1 现在跑起来是什么样

```
PortalEX（选片引擎 → MediaCodec 解码 → 写共享帧文件）
        │  /data/local/tmp/portalex/cammock.frames（16 MiB，3 槽）
        ▼
camshim（vendor HAL 内）：读最新槽 → cover 缩放 1920×1080 → 1600×1200 → memcpy 进相机 buffer
        ▼
Aperture / 任何应用：预览里就是那段视频，**应用进程零注入**
```

实测数字：app 侧发布约 **24 fps**（输入侧按 PTS 节流）；HAL 侧写帧 **4.8–6.4 ms/帧**
（含缩放）；杀掉 app 后 5 秒内 HAL 自动停止接管。

### 13.2 本轮新增的实测事实（都是"只有真跑才会发现"的）

| 事实 | 现象 / 出处 |
|---|---|
| **MediaCodec 给 Surface 输出时，ImageReader 读到的是平铺数据** | 配 `configure(format, imageReader.surface, ...)` 后画面是规则**绿噪声**；把 Y 平面单独解出来同样是噪声 ⇒ 解码器把画面交给 GPU/UBWC 布局的图形缓冲。**解法：不给 Surface**（`configure(format, null, null, 0)` + `getOutputImage`），解码器才交出 CPU 可读平面 |
| **解码器图像带高度填充** | 1920×1080 的视频，`getOutputImage` 给的是 **1920×1088**；几何判据必须是"≥ 流几何"而不是"=="，否则一帧都发不出去（静默失败） |
| **`enabled` 必须在 `open()` 之后写** | `setEnabled(true)` 在 `open()` 前调用是 no-op，而 `open()` 会把头里的 enabled 清零 ⇒ **seq 一直涨、enabled 恒为 0**，HAL 全程不接管（最难查的一次：两边日志都"正常"） |
| **EOS 后必须 `flush()` 才能继续解码** | 不 flush 直接 seek 回文件头再喂样本，解码器停在终止态：`dequeueOutputBuffer` 永久阻塞（线程栈实测卡在 `MediaCodec::dequeueOutputBuffer`），表现为"发布 142 帧后无声无息" |
| **cached 进程会被冻结** | 退到后台后进程 `cur=LAST`，Cached Apps Freezer 冻结产帧线程 ⇒ 发布停止。复用 `MockKeepAliveService`（前台服务，`cur=FGS`）解决 |
| **清 UBWC 位会破坏真实相机的落图路径** | 补位后 ISP 仍按 UBWC 平铺写进线性 buffer ⇒ **真实画面本身变成绿噪声**（实测：app 被杀后预览是噪声而非真实场景）。⇒ 补位必须**按会话判定**，见 §13.3 |
| **缩放必须放 HAL** | 相机流是 1600×1200、素材是 1920×1080；`dw*sh >= dh*sw` 判宽主导，cover + 居中裁剪；色度半分辨率、每元素 2 字节一起取 |

### 13.3 补位（usage_patch）为什么改成"按会话判定"

`usage_patch=2` 是"能让 CPU 正确写进相机 buffer"的前提，但它同时改掉了**真实相机的分配路径**。
于是 `portal_configure_streams` 里先问一句"产帧端现在活着吗"（`enabled=1` 且心跳 <5 s）：

- **活着** ⇒ 补 SW 位 + 清 UBWC（模拟接管成立，写进去的像素就是视频帧）；
- **没活着** ⇒ 一个位都不改，走厂商原始分配（**真实相机工作正常**，实测 preview 统计量与基线逐项一致）。

代价（已知、写在这里免得当成 bug）：**同一次相机会话中途**产帧端挂掉时，那一次会话的画面会坏到
应用重开相机为止；下一次 `configure_streams` 就会走回原始路径。

### 13.4 模块化（重装后免手工）

`magisk/portalex-camera-hal/`：
- `post-fs-data.sh`：建 `/data/local/tmp/portalex`、预建 16 MiB 帧文件、`chcon` 到
  `portalex_frame_file`、按 app uid `chown`；并把 §12.3 的规则用 `magiskpolicy --live` 再打一遍
  （`sepolicy.rule` 在 disable→enable 后有时不再被应用）；
- `sepolicy.rule`：同一批规则的静态版本；
- `system/vendor/lib/hw/portal.cfg`：默认开关（`usage_patch=2 / init_wrap=1 / inject=1 / src_file=…`）。

**为什么帧文件要模块预建**：新建文件的标签由创建者域决定 —— app 建出来的文件会落在 app 域的类型上，
HAL 读不到；由模块建好并打标签、app 只负责打开写，才绕得开。首次新增这些文件后需**重启一次**。

### 13.5 验收（本轮实测）

| 场景 | 结果 |
|---|---|
| 打开模拟 + 打开相机 | 预览 = 视频画面（cover 裁剪填满 4:3），HAL 日志 `缩放映射已建立：1920x1080 → 1600x1200` |
| `am force-stop` 掉 PortalEX 后再开相机 | 预览 = **真实相机**（基线统计量），HAL 日志 `产帧端未在运行 ⇒ 本次不改用途位` |
| app 侧关掉总开关 | 同上（走 `setEnabled(false)`，不用等心跳超时） |
| 长跑 | seq 持续增长（10 s 内 686 → 924 ≈ 24 fps），无阻塞 |

### 13.6 还没做（下一步）

1. **拍照（JPEG）不覆盖**：BLOB 流不在注入范围内，拍照拿到的仍是真实画面 ——
   要"拍照也是模拟的"就得在 HAL 侧把我们的帧编码成 JPEG（或让 JPEG 流走 reprocess 输入）；
2. **位置联动已具备但未充分验证**：选片用 `CameraMockRuntime.position()`，位置一变会在
   "绕回文件头"时重选（同一个视频播完才换点，长视频体感偏慢，可按需改成定时重选）；
3. **多相机/多应用**：目前按相机 id 全量生效（`cam=` 可定向），未验证同一时刻多应用并发取流；
4. **音频不受影响**（素材只有视频轨），录像类应用拿到的是"无声视频 + 真实音频"。


## 14. 模拟页的"参照地图操作"（本轮补齐）

**做了什么**：摄像头模拟页新增一张可折叠地图（`fragment_camera_mock.xml` 的 `camera_mock_map_card`）：

- 每个**视频点**画一个标记（标题带"（N 段）"），**点标记直接进该点的编辑页**；
- 有位置模拟时，再画一个**当前表象位置**标记 —— 选片规则是"距当前表象位置最近的命中点"，
  只给列表用户没法判断"现在到底会播哪一个"，画到图上是一眼的事（拿不到位置**不画**，绝不猜坐标）；
- 实现 `MapControlsHost`：三点展开栏的"卫星图/普通图"与主界面/路线编辑页共用同一套（`isSatellite`/`toggleSatellite`）；
- MapView 生命周期照抄路线编辑页那套（`onCreate/onResume/onPause/onDestroy` 逐个转发，不转发地图不渲染）。

**为什么默认收起**：地图 180dp 是固定高度，展开时把列表卡（0dp/weight=1）挤到**只剩一条缝**
（实测：点位的名字/坐标/标签全看不见 —— 与 §4.1 那次"列表被挤没"是同一个坑）。
所以做成"一键展开/收起"：默认保证列表可用，要看"点在哪儿、我在哪儿"时再展开
（展开实测：地图正常渲染、标记 1 个、列表仍在；收起实测：列表项 `剖难题-` 及其坐标/标签都回来）。

**踩坑（值得记）**：百度 `MarkerOptions` **必须显式给 icon** —— 不给会抛
`BDMapSDKException: when you add marker, you must set the icon or icons`；
而被 `runCatching` 包住之后表现是"地图渲染正常、一个标记都没有"（静默失败）。
现用 `R.drawable.icon_selected_location_16`（点位）与 `R.drawable.icon_my_location`（表象位置）。


## 15. 让「图片」也吃虚拟帧：拍照走 BLOB 流的替换

### 15.1 做法（为什么由 app 编码、HAL 只搬运）

拍照走的是 `format=0x21`（BLOB）流，实测几何 **2592×1944**、usage `0x20003`
（有 `SW_READ_OFTEN`、`HW_CAMERA_WRITE`，**没有 SW 写位** ⇒ 直接 CPU 写会被拒）。

分工：**app 用 `YuvImage.compressToJpeg` 编码，HAL 只做整块替换**。

- 不在这层用 libjpeg：`jpeg_compress_struct` 是**版本相关的 ABI**（`jpeg_CreateCompress`
  会校验结构体大小），在 HAL 里自带头文件等于赌版本；
- app 侧 `YuvImage` 是现成的（Skia 原生），解码器给的是 NV12，转 NV21 只需把色度**成对交换**；
- app 按固定节拍（1.5 s）刷新"当前帧的 JPEG"（1920×1080 q85 ≈ 125 KB，编码 ~几十毫秒）——
  **不能等拍照请求到来再编码**：HAL 是在 `process_capture_result` 里替换的，那一刻必须已经有可搬的字节，
  同步等 app 会把 HAL 的结果线程堵住。

### 15.2 协议扩展：帧文件里的「照片区」

头部 96 字节（在 §12.4 的 80 字节之后追加）：

```
80  photo_w   84  photo_h   88  photo_bytes   92  photo_seq
```
- 布局：`HDR(4096) + slots × slot_bytes` 起是**照片区**（长度 = 文件长 − 该偏移）。
  `slot_bytes` 因此改成**固定 4 MiB**（不再从文件长度反推）——否则槽位会把文件吃干、照片区长度为 0；
- 发布顺序与亮度槽位一致：**先写字节 → 再写尺寸/长度 → 最后 `photo_seq++`**；
- `close()` 会把 `photo_bytes` 清零，避免陈旧 JPEG 被搬走。

### 15.3 HAL 侧：替换 + 清尾巴

- `portal_scrub_streams` 现在也给 **BLOB 流补 SW 写位**（只补写位、**不动 UBWC** ——
  JPEG buffer 是线性字节块，没有平铺问题，动它的分配只会把拍照弄坏）；
- `portal_process_capture_result` 里对 `format==0x21` 的输出整块替换：
  `lock()` 拿到基址 → memcpy 我们的 JPEG → **把自己长度之后的一段清零** → `unlock()`。
  清尾巴是必须的：真 HAL 已经先写了一张**真实照片**进去（实测 919,710 字节），不清就会留下真实画面的残片。

### 15.4 实测结果

- HAL 日志：`照片已替换：124502 字节（来自共享帧文件的照片区）`；
- 相机 app 保存的照片取回后**解码出来就是视频帧**（1920×1080 灯景，`PIL` 解出尺寸一致），
  不是真实摄像头画面 ✅；
- **清零验证**（重要，既是数据正确也是"不留真实画面残片"的安全断言）：
  文件里只有**一个** `FFD8FF`（偏移 0）与**一个** `FFD9`（偏移 124,686），
  EOI 之后 **934,889 字节全为 0**（非零 0 个）。

### 15.5 JPEG 长度：`camera3_jpeg_blob`（本轮查清并已接管）

**结论**：AOSP 的约定 —— HAL 把 JPEG 写在 buffer 开头，并在 **buffer 末尾**放
`camera3_jpeg_blob_t { uint16 jpeg_blob_id(=0x00FF); uint32 jpeg_size; }`（含对齐 8 字节），
框架读这 8 个字节才知道这张 JPEG 多长。

**怎么找到的**（可复现的探针思路）：句柄整数段里的 `ints[2] = 7,661,171` 就是 buffer 容量候选；
把它当容量、读 `[cap-8, cap)` 的 8 字节，正好是 `id=0x00FF` + `size=922409`（真 HAL 那张的长度）：

```
容量候选 ints[2]=7661171 → 尾部 id=0x00ff size=922409
真 HAL 的 blob：容量=7661171 它那张=922409 字节（我们=120336）
```

**修法**：搬完我们的 JPEG 后，**在同一个位置写我们自己的 blob**（长度=我们的字节数），
并且只清到 blob 之前。效果（实测）：

| | 修之前 | 修之后 |
|---|---|---|
| 保存的文件 | 924,960 字节（我们的 126 KB + **~800 KB 零尾巴**） | 120,520 字节 = 我们的 120,336 + 相机 app 插的 EXIF |
| 文件尾部 | 一堆 0 | `FFD9` 之后 **0 字节** |

### 15.6 照片几何：按静态流请求的尺寸出（本轮完成）

- HAL 在 `configure_streams` 里把 BLOB 流的几何**回写**进头部新字段 `photo_req_w/h`
  （实测 `静态流几何回写：2592x1944`）；
- app 据此把 NV21 用**查表最近邻**缩放到该尺寸再编码（解码帧是 1920×1080，请求是 2592×1944）；
- 实测：`照片几何：请求 2592x1944（解码帧 1920x1080）→ 255439 字节`，
  相册里取回的照片 **解码尺寸 2592×1944**、内容是视频帧、尾部无零填充。

### 15.7 顺手修掉的一个真 bug（否则上面的回写全是空转）

`portal_fsrc_open()` 原来是 **`O_RDONLY` 打开 + `PROT_WRITE` 映射** ⇒ mmap 直接 `EACCES`，
然后静默退回**只读**映射 ⇒ HAL 所有"回写"（`consumer_w/h`、`photo_req_w/h`）**全部失效**。
现在改成先试 `O_RDWR`、失败再退 `O_RDONLY`；挂载日志里能直接看出结果（`… RW` / `… RO`）。

### 15.8 录像流（编码器）**必须完全不碰** —— 本轮实测

用 PortalEX 自己的录制页（Camera2 + MediaRecorder，前置）在开着模拟的情况下录一段，抽帧结果是
**平铺绿色噪声**（不是我们的测试彩条，也不是真实画面）。

抓会话的流参数一看就明白了：

```
改用途位[0]：format=0x22 320x240   usage 0x10020800 → 0x20833（UBWC 已清）
改用途位[0]：format=0x22 1200x1200 usage 0x10020100 → 0x20133（UBWC 已清）
改用途位[1]：format=0x22 1920x1080 usage 0x10030022 → 0x30033（UBWC 已清）  ← 编码器流
```
（第三条的 usage 含 `HW_VIDEO_ENCODER = 0x10000`。）

**结论**：**视频编码器（Venus）按 UBWC 布局读这块内存** —— 把 UBWC 清掉之后它读到的是垃圾。
也就是说"补位 + 注入"会把**录像内容弄坏**，比不接管更糟。

**修法**：`usage & HW_VIDEO_ENCODER` 的流一律跳过（不补位、不清 UBWC、不注入）。
修后同一条路径再录一段，抽帧是**连贯的真实相机画面**（前置视角），不再是噪声 ——
即**录像内容退回真实相机**（已知边界，不是"坏了"）。

**顺带排除一个误判**：Aperture 自身的录像在这台机器上**本来就录不出文件**
（`QCamera: toActiveArray: sensor/active array sizes are not initialized!` ×402）。
A/B 验证：把补位全关（cfg 全零）后同样录不出文件、同样报这批错误 ⇒
**与我们的数据面无关**，是这台设备/Aperture 录像路径的既有问题。所以录像验证改用
PortalEX 自己的录制页（那才是"我们确实覆盖到的编码器流"）。

### 15.9 顺手修掉的第二个真 bug（性能）

缩放映射原来是**一份缓存**，而亮度用 `(1920x1080 → 1200x1200)`、色度用
`(960x540 → 600x600)` 两次调用 —— **互相作废对方的缓存**，于是**每帧重建两张映射表**
（日志里 `缩放映射已建立` 每帧刷屏就是证据）。现在按用途分成两份（亮度/色度各一份），
几何不变就不再重建。

### 15.10 照片改成**按需编码**（性能修复）

原来 app **无条件每 1.5 s** 编一张 5 MP（缩放 1920×1080→2592×1944 + JPEG ≈ 200–300 ms 重活）。
实测它对热路径的污染是可量化的：

| 场景 | HAL 写帧耗时 |
|---|---|
| 无争用基线（分段日志） | 亮度 **11–12 ms** + 色度 **5.6 ms** ≈ **17 ms** |
| 叠加 app 的周期性 5 MP 重活 / ISP 同时写同一块 buffer | 平均 **43–52 ms**，并出现 `49.0/18.4`、`63.8/24.7` 这类**成对尖峰** |

修法：**按需编码**。HAL 在 `process_capture_request` 阶段就能看到 BLOB 流（比 BLOB 结果早一百多毫秒，
而 vendor 自己编那张也要这么久，正好并行），于是：

- HAL：把 `photo_req_seq` +1（头部新字段，偏移 104）+ 回写请求几何；
- app：看到 `photo_req_seq` 变化才编（保留 10 s 保活刷新兜底）；
- HAL：结果阶段最多等 200 ms（5 ms × 40）等 app 的新照片，超时就沿用上一张（旧但合法）。

实测（真机一次拍照）：

```
PortalCamShim: 拍照请求：已通知 app 编码 2592x1944（req_seq=1）
PortalCamShim: 照片已替换：263676 字节（来自共享帧文件的照片区）   ← 427 ms 后，按需编好并搬运
DCIM/Camera/…jpg: 263860 字节（我们的 + 相机 app 插的 EXIF）
```

**仍未解决（记在这里）**：即使去掉周期性重活，写帧平均仍在 45–52 ms —— 因为我们的搬运与
**ISP 同时写同一块 buffer**（这条路径拿不到 acquire_fence），内存带宽互相踩。
基线 17 ms 说明"写 3 MB"本身不慢；要彻底解决得换思路（例如让 app 侧用 GL 把画面直接渲染到
相机流几何，HAL 回到纯 memcpy 快路径 4.8 ms —— 见 §15.11）。

### 15.11 其他输出类型的覆盖结论（本轮逐条验证）

| 输出类型 | 结论 | 证据 |
|---|---|---|
| 预览（`IMPLEMENTATION_DEFINED`） | ✅ 注入 | 预览截图即视频帧 |
| **YUV 分析流**（`YUV_420_888`） | ✅ **注入**（本轮新验证） | 见 §16.1 的探针：只挂 YUV 流，抓回的 640×480 帧就是视频画面 |
| 拍照（`BLOB`/JPEG） | ✅ 注入（长度与几何都对） | §15.5–15.7 |
| 录像（`HW_VIDEO_ENCODER`） | ⛔ 不接管（退回真实相机） | §15.8（编码器按 UBWC 读） |
| **多应用并发取流** | ⛔ **本机不支持**（不是"没做"） | `CameraManager.getConcurrentCameraIds() = {}` —— 这台设备根本不支持两个相机会话同时存在；实测让 Aperture 占后摄、探针占前摄，Aperture 失去前台即释放相机，框架也不允许并发 |

- 照片区仍是**单槽**（按需编码后同一时刻只有一张，风险已显著降低；正解仍是双槽轮换）；
- 写帧性能：见 §15.10 末段（GL 渲染路线）。

## 16. 诊断工具与一个必须记住的副作用（本轮）

### 16.1 `CameraProbeActivity`：只挂 YUV 流的抓帧探针

`app/.../ui/camera/CameraProbeActivity.kt`（不导出，只有 adb/root 能起）：

```sh
adb shell su -c 'am start -n moe.fuqiuluo.portalex/.ui.camera.CameraProbeActivity \
    --ei cameraId 0 --ei width 640 --ei height 480 [--ei holdMs 9000]'
```
它用 Camera2 + **唯一输出 ImageReader(YUV_420_888)** 开相机，抓一帧转 NV21 存成 JPEG 到
`filesDir/probe/`，并打印可用相机、`getConcurrentCameraIds()`、每帧的 planes/stride。
`holdMs>0` 时抓完继续持有会话，用于观察并发。

它同时验证了三件事：**YUV 分析流确实被注入**、**本机相机拓扑**（0/2/3 前置、1 后置）、
**并发相机是否可用**（本机 `{}` ⇒ 不支持）。

### 16.2 副作用（真 bug）：模块侧旧钩子会**弄坏本应用自己的相机**

现象：探针"一张帧都收不到"，而日志里只有一句
`[Portal][WARN] 摄像头模拟：播放失败（GL shader 编译失败）`。

原因：**PortalEX 自己也在 LSPosed 作用域里**，模块侧的旧内容替换钩子（`Camera2MockHook`）会先
把应用的输出面换成排水面、再去起 GL 播放器；GL 一失败，应用自己的相机就**永远收不到帧**
（既不是真实画面、也不是虚拟帧）。录制页、探针都受影响。

修法：`CameraMockHooks.install()` 里对本应用包名（`CameraMockProtocol.OWN_PACKAGE`）**直接返回**——
对本应用做内容替换本来也没有意义（调试探针要读的正是"真实相机 + HAL 注入"的结果）。

**教训**：模块作用域里包含本应用时，"帮别人换画面"的钩子会顺手把自己的调试通道掐死；
凡是"注入型"功能，都要显式排除自己的进程。

### 16.3 模块化（本轮更新）

- `post-fs-data.sh`：注明 16 MiB 是**协议 v2 的最小值**（头部 4096 + 3×4 MiB 槽位 + ≈4 MiB 照片区），
  且槽位大小必须正好 4 MiB（不能再从文件长度反推，否则照片区为 0）；
- `module.prop`：版本 → `0.4.0-M3`，描述补上"预览/录像/拍照"三类输出的实际覆盖情况；
- `portal.cfg` 默认开关不变（`usage_patch=2 / init_wrap=1 / inject=1 / src_file=…`）。

### 16.4 还没做

- 照片区双槽轮换（§15.11）；
- 写帧性能的 GL 路线（§15.10 末段）；
- 若将来要覆盖录像，需要把帧写成 **UBWC 布局**（编码器硬件的读法），目前没有可行手段。
