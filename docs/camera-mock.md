# 摄像头模拟（Camera Mock）：设计、实测事实与冻结状态

> 本文只写"读完代码也未必知道"的东西：**架构取舍**、**真机实测出来的事实**（每条都有命令或日志出处）、
> 以及**卡在哪、从哪接着做**。代码里的注释负责局部原因，本文负责全局。
> 冻结时间：2026-09-12（用户裁决"冻结归档"）。**当前线上配置是安全的：真实相机直通。**

## 0. 一句话现状

| 部分 | 状态 |
|---|---|
| app 侧：视频点库 / 录制 / 导入 / 标签 / 地图选点 / 选片引擎 / 跨进程投递 | ✅ 已完成，真机验证通过 |
| system_server 控制面：谁在何时用了哪个摄像头（**应用 0 注入**） | ✅ 已完成，真机验证通过 |
| 模块侧 app hook 内容替换（Camera2 换面 + GL 喂帧） | ✅ 已实现并真机验证（**当前停用**，见 §4） |
| HAL 数据面（改别人的 buffer / 自建虚拟摄像头） | ⛔ **冻结**：已被证明"能产出帧"，但完整会话链路差若干框架级要求（见 §5） |
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

## 7. 下一步（按可行性排序）

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
