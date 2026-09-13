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
| **拍照（JPEG/BLOB 流）也吃虚拟帧** | ✅ 已实现并真机验证（2026-09-12，§15）：照片就是视频帧、**2592×1944**（跟随相机请求）、长度由我们的 `camera3_jpeg_blob` 决定、**相册显示与预览一致**（EXIF 镜像已抵消，§17） |
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

## 17. 照片 EXIF：一个只有看图才认得出的偏差（本轮收口）

### 17.1 现象与 A/B

把模拟拍的照片在系统相册（LineageOS Glimpse）里打开，**画面是水平镜像的**
（预览与源视频里"花在右、灯在左"，照片里变成"花在左、灯在右"）。

A/B 一测就清楚了 —— 同一台设备、同一颗后摄（相机 1）：

| 照片来源 | EXIF Orientation | 含义 |
|---|---|---|
| **真实相机**（补位关闭） | **7** | 镜像 + 旋转 270°（相机 pipeline 的正常约定：内容按传感器方向存，靠 EXIF 掰正） |
| **我们的虚拟帧** | **2** | **只镜像、不旋转** |

### 17.2 为什么不一样

差别在 **我们的 JPEG 不带 EXIF**（`YuvImage.compressToJpeg` 只产出 JFIF）。
相机 app 拿到一张没有 EXIF 的 JPEG 时走了自己的**回退公式**（只镜像），
于是把"**已经正立**"的内容翻了过去 —— 传感器方向的内容被镜像后恰好是对的，而正立内容被镜像就是错的。

### 17.3 修法与验证

运行期开关 `photo_mirror=1`：HAL 把它写进头部 `reserved2`（复用字段、不改布局），
app 编码前一帧**水平镜像**（Y 逐行对调、色度按 VU 对交换），让 app 的镜像抵消掉。

```
PortalCamShim: 照片变换标志回写：0x1（bit0=水平镜像）
CameraMockVideoSource: 照片已按 HAL 标志水平镜像（抵消相机 app 的 EXIF 镜像）
PortalCamShim: 拍照请求：已通知 app 编码 2592x1944（req_seq=5） → 照片已替换：263683 字节
```
**验收（看图，不看日志）**：相册里那张照片恢复成"灯在左、花在右"，与预览、与源视频一致 ✅。

**为什么做成开关而不是写死**：这种错只有看图才认得出，而且"app 的回退公式"是相机 app 的行为、
换设备/换相机 app 未必相同；写死等于赌一个别处的实现细节。

### 17.4 还没做

- 照片区双槽轮换（§15.11）；
- 写帧性能的 GL 路线（§15.10 末段）；
- 若将来要覆盖录像，需要把帧写成 **UBWC 布局**（编码器硬件的读法），目前没有可行手段。


---

# 18. 兼容性测试：一加 ACE5（PKG110 / ColorOS 16 / Android 16 / SD8Gen3）

> 2026-09-12，换到第二台设备做兼容性测试。**结论先说**：app 侧基本可用（两处待办），
> **模块侧在这台机器上走不通** —— 不是"配置改一改"，而是底层栈换了三样东西（root 方案、provider 类型、
> 内核挂载能力），已定位到四个具体障碍，逐条记录在下面。测试后设备已回滚到可用状态。

## 18.1 两台机器的差异（实测）

| | MI6（已打通） | 一加 ACE5 |
|---|---|---|
| 系统 | LineageOS 22.2 / Android 15 | ColorOS 16 / **Android 16** |
| SoC | msm8998 | **pineapple**（SD8Gen3，arm64） |
| root 方案 | **Magisk**（有 `magiskpolicy`） | **KernelSU Next**（`ksud sepolicy`；root 域是 `ksu` 不是 `magisk`） |
| camera provider | HIDL `camera.provider@2.4-service`（**32 位**） | **AIDL** `vendor.qti.camera.provider-service_64`（**64 位**） |
| vendor HAL | `/vendor/lib/hw/camera.msm8998.so`（32 位） | `/vendor/lib64/hw/camera.qcom.so`（**64 位**，36 MB） |
| `HMI` 大小 | 176 字节 | **376 字节** |
| HAL 导出符号 | 有 `QCamera3HardwareInterface::*` | **只有 `HMI`**（不能 dlsym，只能按 ops 索引取指针） |
| HAL 域 | `hal_camera_default` | `hal_camera_default`（**同名，规则可复用**） |
| app 域 | `untrusted_app` | `untrusted_app`（同名 ✓） |

## 18.2 app 侧（PortalEX）：基本可用

| 项 | 结果 |
|---|---|
| 安装/启动（arm64 构建） | ✅ 正常（`targetSdk=35` 在 Android 16 上可装可跑） |
| 摄像头模拟页 | ✅ 渲染正常（状态卡 / 标签筛选 / 列表 / 地图开关） |
| **百度地图** | ✅ 卫星图正常渲染并定位（SDK 在 Android 16 上可用） |
| 录制页（Camera2 + MediaRecorder） | ✅ 授权 CAMERA 后预览正常；**注意：这台机器上该权限默认没给**（`granted=false`），首次使用需授权 |
| LSPosed 模块加载 | ⚠️ 我们的模块被注入 `system`，但日志里出现 `Reject hook registration ... already registered 64 times`（定位回调被重复注册，撞上 LSPosed 的 64 次上限）——**待查**（大概率是某个 hook 在 classLoader/重试路径上反复注册） |
| provider 通道（清单/位置/fd） | 本轮未验证（该机素材库为空、HAL 路线未成型；通道是 app 内部实现，预期无碍） |

## 18.3 模块侧：四个障碍（每个都有实测出处）

**障碍 1：`ksud module install` 被 `set -e` 毒化。**
安装器是 **source** `customize.sh` 的：脚本里写了 `set -e`，这个 shell 选项会**泄漏进安装器自己的
shell**，于是它后续步骤（"Handle partition /vendor"）里任何一次非零返回都会让整包装失败 ——
现象是"脚本正常打印完 → `Error: Failed to install module script` → 整包回滚"，
而且换布局、换载荷大小都照旧失败，极难定位。**已修**：去掉 `set -e`，改成显式判错。

**障碍 2：`system/vendor/**` 布局过不了安装器。**
`system/vendor/…` 会在 "Handle partition /vendor" 失败；改成**顶层 `vendor/…`** 才装得上。
（二分实验：同样的载荷，只改布局 → 前者失败、后者成功。）

**障碍 3：KernelSU 在这台 ROM 上"只跑脚本、不挂模块文件"。**
`mount | grep -c adb/modules` = **0**；`WorkSettingPro` 用的是 `system/product/app` 也照样没挂上。
而我们的 `post-fs-data.sh` **确实被执行**（它创建的 16 MiB 共享帧文件在）。
⇒ "把 shim 放进模块的 system/vendor 目录就会生效"这条 MI6 上成立的路，在这台机器上不成立。

**障碍 4：真件 HAL 进不了 /vendor，而 Vendor 命名空间不许从别处 dlopen。**
链接器明确拒绝（实测）：
```
library "/data/local/tmp/camera.qcom.real.so" is not accessible for the namespace "(default)"
permitted_paths="/odm:/vendor:/system/vendor"
```
- 单文件 bind 到 `/vendor/lib64/hw/camera.qcom.so` **可行**（我们的 shim 96 KB 成功顶上去）；
- 但 **overlayfs 在这台 ROM 上被拒**（`mount -t overlay … /vendor/lib64/hw` 失败）⇒
  无法在只读 /vendor 上"新增"一个真件副本；
- 退路（把真件 bind 到一个**没人用的既有** vendor 文件上，实测 `android.hardware.renderscript@1.0-impl.so`
  与 `audio@2.0-impl.so` 都没有任何进程映射）→ provider **SIGSEGV 崩溃循环**，
  且**我们的 shim 连一行日志都没打印**（崩在更早的环节，尚未定位）。

## 18.4 已经做出来的移植件（留在仓库，可继续）

- shim 增加三个运行期开关：`real_hal=`（真件路径）、`real_hmi_bytes=`（176/376）、`ops_index=1`
  （**按 AOSP ops 索引**从表里取真实函数指针，取前做"落在可执行映射里"的校验 —— 因为 ACE5 不导出符号）；
  开关文件支持三级回落（`/vendor/lib64/hw` → `/vendor/lib/hw` → `/data/local/tmp`）；
- 构建同时出 **arm64-v8a**（`camera-hal/build.gradle.kts`）；
- 模块改成**按设备出包**（`scripts/build-camera-hal-module.sh pineapple|msm8998`）：
  载荷/占位/开关全部预置在包里，安装期**不动 vendor 树**；
- 模块脚本同时支持 Magisk 与 KernelSU（自动挑 `magiskpolicy` / `ksud sepolicy`，root 域 `magisk`/`ksu` 都打）；
- `post-fs-data.sh` 会自己挂载（overlay 优先、单文件 bind 兜底），因为模块文件挂载在本机不可用。

## 18.5 下一步（两条路，需要用户裁决）

**A. 继续攻 ACE5 的 HAL 路线**。最近的下一步是把"provider 为什么在我们 shim 之前就崩"查清：
用 tombstone 的寄存器/调用栈定位是哪个 NULL（很可能是 ODM 层 `camera.oemlayer.so` 在读某个被我们
顶替的文件/属性），再换一个更安全的宿主文件；或改用本机允许的挂载方式（如 tmpfs+逐文件 bind：
先把 HAL 目录内容快照出来，再 tmpfs 覆盖该目录并把快照 bind 回去）。

**B. 改用模块侧 app hook（LSPosed 路线）**。这台机器 `zygisk_lsposed` 已装、我们的模块已被注入，
仓库里那条"Camera2 换面 + GL 喂帧"的实现是**唯一在 MI6 上被证明能真正替换画面**的路径，
代价是目标应用要进 LSPosed 作用域 —— 与"系统侧、应用 0 注入"的裁决冲突，所以必须由用户定。

## 18.6 测试后的设备状态（已确认）

- `/vendor/lib64/hw/camera.qcom.so` 与 `…renderscript@1.0-impl.so` 均已还原（36 MB / 72 KB）；
- `vendor.camera-provider` **running**，相机预览正常（PortalEX 录制页实时画面）；
- 模块已 `touch /data/adb/modules/portalex-camera-hal/disable`（KernelSU 会跳过它），
  载荷与真件副本仍留在模块目录里备查；
- 临时文件（`/data/local/tmp/{camshim64.so,cq.so,sm.so,camera.qcom.real.so,modtest*,md_*,mt*.zip,pch*.zip}`）待清理。

---

## 19. ACE5 HAL 路线实测：三道墙，两道已拆（2026-09-12/13）

§18.5 的猜测（"ODM 层在读某个 NULL，很可能是我们顶替的文件/属性"）方向对了，但机制不是"别人读不到"，
而是**我们自己交出了一个全零的模块**。本轮把 §18.5 的 A 路线推到第三道墙，方法是：
**每崩一次就读 `/data/tombstones/`，用 NDK 的 `llvm-objdump` 反汇编崩溃点**（可复用，见 19.5）。

### 19.0 摘要

| # | 现象 | 根因 | 状态 |
|---|---|---|---|
| 1 | provider 崩在**任何日志之前**（`SEGV_MAPERR addr 0x0`，6 份 tombstone 签名逐帧一致） | 真件没打开时 `HMI = {0}` ⇒ `common.id == NULL`；OPPO OEM 层 `camera.oemlayer.so::load()` 会 `strcmp("camera", HMI->common.id)` | **已修**（惰性合法 HMI） |
| 2a | shim 四个 cfg 路径**全部读不到**，dmesg 里没有对应 avc | `/data/local/tmp` 是 `shell_data_file`，`hal_camera_default` 缺 `search`（被 dontaudit 吞掉） | **已修**（一条 allow） |
| 2b | `SEGV_ACCERR` in `__cfi_slowpath`，`x1 = 0`（`OemLayer::init()+312`） | 真件导出 `SetOplusCameraInfoCallback`，OEM 层 dlsym 后**无条件调用**；我们替换了原件却没有这个符号 | **已修**（转发桩） |
| 3 | `SEGV_ACCERR` in `__cfi_slowpath`，`x1 = 0`（`OemLayer::init()+408`） | QTI/OPPO 的 **CHI 离线初始化**里，一个可打补丁的钩子表项（`[x10+0x78]`）从没被填过 | **未解**（见 19.4） |

### 19.1 部署形态（KernelSU 不挂模块树 ⇒ 全部手动）

```sh
# 真件宿主槽：必须选一个**当下没有被任何进程映射**的 /vendor 文件
# （vendor namespace 只允许 dlopen /vendor|/odm|/system/vendor）
mount --bind <模块>/orig/camera.qcom.so.orig.so /vendor/lib64/hw/android.hardware.renderscript@1.0-impl.so
mount --bind <模块>/system/vendor/lib64/hw/camera.qcom.so /vendor/lib64/hw/camera.qcom.so
# 开关文件：/vendor 只读、无法新建文件 ⇒ 用 /data/local/tmp/portalex/portal.cfg（该目录已标成 portalex_frame_file）
```

`camera.qcom.sm8650.so` **不能**当宿主槽：它是正被 provider 映射的 CamX 实现（21 MB，且不导出 `HMI`）。

### 19.2 第一道墙：全零 `HMI` 被 OPPO OEM 层读成 NULL

三条互相印证的证据：

1. tombstone：`#00 __strcmp_aarch64+156 ← #01 camera.oemlayer.so load(char const*)+276 ← getSocImpl ←
   OemLayer::init ← CameraModule::init()`，`fault addr 0x0`、`x1 = 0`
   （x0 指向 oemlayer rodata 里被比较的常量；bionic 的 strcmp 用后自增 `ldrb w2,[x0],#1`，所以 x0 比常量起始多 1 字节）；
2. 反汇编 `load()`：`android_load_sphal_library(名字,2)` → `dlsym(h,"SetOplusCameraInfoCallback")` →
   `dlsym(h,"HMI")` → `ldr x1,[x8,#8]` + `strcmp("camera", x1)`。`hw_module_t` 里 `tag`(4B) 之后是两个 **uint16**，
   所以 `id` 正好在 **+8** —— x1 就是 `common.id`；
3. 字符串表：`CamxLoad: Start load %s`、`HMI`、`camera`、`%s: load %s success`。

而我们的 shim 当时：cfg 读不到（见 19.3）⇒ `real_hal` 退化成**硬编码的 MI6 32 位路径**
`/vendor/lib/hw/camera.msm8998.orig.so` ⇒ `dlopen` 失败 ⇒ 旧代码只是 `LOGE + return`，**`HMI` 保持全零**。
于是"相机不可用"被放大成"provider 崩溃循环"。

修法：

- `HMI` 从 `.data` 第一个字节起就是**合法但惰性**的模块（tag/版本/`id="camera"`/`methods` 静态初始化，
  余下 48 个指针槽指向 `portal_inert_stub`（返回 `-ENODEV`））；真件加载成功后整块 `memcpy` 覆盖 + 尾部清零。
  最坏情况因此降级为"零个相机、进程活着"；
- 默认原件路径改为**自推导**：`dladdr()` 取自己 → `<自己>.orig.so`（部署约定本来就是"原件备份在 shim 旁边"）；
- 失败原因落盘 `/data/local/tmp/portalex/camshim.state`（该文件须由模块预建：HAL 域没有在该目录 create 的权限）。

验证（真机、不碰 /vendor）：用一个 arm64 探针 dlopen shim、复刻 OPPO 那句判据 ——
`tag=0x48574d54 api=0x0204 hal=0x0100 id=camera … RESULT inert-ok: strcmp("camera", id) == 0`。

### 19.3 第二道墙：cfg 读不到 + 少一个导出符号

**(a) cfg**：shim 报"四个开关文件都不存在"，但文件确实存在、标签也对。真正原因是路径解析要**穿过**
`/data/local/tmp`（`shell_data_file`），而 `hal_camera_default` 对它没有 `search`；dmesg 里看不到 avc
（被 `dontaudit` 吞掉）—— 极易误判成"文件没写进去"。修法是一条极窄的规则：

```
allow hal_camera_default shell_data_file dir { search }      # 只给目录穿透，不给文件任何权限
```

**(b) `SetOplusCameraInfoCallback`**：OEM 层 `OemLayer::init()+312` 的机器码是

```asm
6f250: ldr  x19, [x8]            ; = dlsym 结果
6f26c: bl   __cfi_slowpath       ; 先过 CFI（x1 = 目标指针）
6f278: blr  x19                  ; ★ NULL 在这里炸
```

它**只把 dlsym 失败当一条日志，然后照样调用**。真件导出它（`FUNC`，8 字节 = `bti c; b <CamX 深处>`）。
修法：shim 也导出这个名字并转发到真件实现；惰性时返回 `-ENODEV`（绝不假装成功）。

修完这两条后的日志三连（本轮最硬的证据）：

```
PortalCamShim: 开关文件：/data/local/tmp/portalex/portal.cfg
PortalCamShim: 真实 HAL 已加载并整块接管：/vendor/lib64/hw/android.hardware.renderscript@1.0-impl.so
               （tag=0x48574d54 id=camera name=QTI Camera HAL api=0x0205，照抄 376 字节）
CamxLoad: load: CameraInfoCallback dlsym success!
```

顺带确认：ACE5 上真件的 `HMI` 是 **376 字节**、`module_api_version = 0x0205`（MI6 是 176 字节 / 2.4），
`real_hmi_bytes` 必须按机型配。

### 19.4 第三道墙：CHI 离线初始化的空钩子表（未解）

```
OemLayer::init()+408 → OemLayer::LoadOfflineLib()+328
  → ChiOfflineOpenContext.cfi+576 (com.qti.chi.offline.so)
    → ChiOfflineCameraContext::ctor → chi_hal_override_entry.cfi+284 (com.qti.chi.override.so)
      → ExtensionModule::ctor +16012 → ExtensionModule::GetFilteredMetadataTagsForDump()+1280
        → __cfi_slowpath+28   ★ x1 = 0
```

`LoadOfflineLib()` 的字符串常量把它的行为说清楚了：它 `dlopen("/vendor/lib64/hw/com.qti.chi.offline.so")`
（**固定路径**，不是候选列表）、`dlsym("ChiOfflineOpenContext")` 然后调用 —— 与 `camera.qcom.so` 没有直接关系。

崩溃点机器码：

```asm
38de30: ldr  x20, [x10, #0x78]                     ; 一个可打补丁的钩子表项（x10 经 GOT 间接取得）
38de20: adrp x11, 0x10e000 ; add x11, x11, #0x30   ; 默认实现地址
38de40: cmp  x20, x11
38de44: b.ne 0x38e2cc                              ; 不等于默认值 ⇒ 走 CFI 校验
38e2cc: mov x0, #类型id ; mov x1, x20 ; bl __cfi_slowpath   ; ★ x20 = NULL
```

即：**这一格钩子从来没被填过**。谁该填它、为什么真件在场时会被填上，尚未确认；方向上更像
"QTI 离线 CHI 初始化的时序/状态依赖"，而不是我们又少导出了一个符号。

### 19.5 设备卫生（本轮踩到的两个坑，务必记住）

1. **连崩之后 init 会禁用服务**：provider 崩够次数后 init 不再重启它，`pidof` 为空、相机彻底消失。
   恢复：`setprop ctl.start vendor.camera-provider`（ACE5 实测有效）。
2. **回滚脚本的 `||` 分支会撒谎**：`umount X && echo unbound || echo "not bound"` 在 **umount 失败**时
   也打印 "not bound"，于是会误以为已卸载，而 `/vendor` 里仍是绑定后的内容。
   正确顺序：**先杀 provider**（否则它持有文件、umount 返回 EBUSY）→ 再 umount → 最后核对
   `ls -l` 的大小/时间戳是否为原件（ACE5：`camera.qcom.so` 36,114,272 B、renderscript impl 72,056 B）。

### 19.6 ACE5 上已经证明 / 仍未证明

**已证明**：手工部署后 —— shim 被加载并执行、能读到 cfg、能把真件从宿主槽加载并整块接管（376 字节）、
能满足 OPPO OEM 层的两道校验（`HMI->common.id` 与 `SetOplusCameraInfoCallback`）；
回滚后设备健康（provider running、`dumpsys media.camera` 报 6 个相机设备）。
**仍未证明**：provider 能存活到相机可用（卡在第三道墙），以及后续的 M0/M1（接管与注入）。

### 19.7 下一步（需要裁决）

> **A 已于 2026-09-13 执行，结果见 §19.8（否证）。**

- **A. 试"开机期部署"**：把 19.1 的三步 bind 搬进 `post-fs-data.sh`（模块脚本在本 ROM 上是会跑的，
  只是文件系统不被挂载），重启后看第三道墙是否还在。理由：目前是"运行期杀掉 provider 再拉起"，
  而 QTI 的 CHI 钩子表可能是按开机期的服务顺序填的 —— 这一条能把"我们的替换不兼容"与
  "运行期重启路径不同"分开。
- **B. 继续反汇编那一格钩子表**：在 `com.qti.chi.override.so` / `com.qti.chi.offline.so` 里找写
  `[x10+0x78]` 的地方，确认它是否依赖某个被我们顶替的库所提供的符号/状态。
- **C. 放弃 ACE5 的 HAL 路线，改用注入平面**（见 18.5 B）。两者不是替代关系：HAL 路线的独有价值是
  "对目标 app 完全不可见"，注入路线的独有价值是"装机不求人"。

### 19.8 开机期部署实验（A）：**否证**

把 19.1 的三步搬进模块脚本后重启（顺带加了 watchdog，见 19.10）。结果：

| 观测 | 值 |
|---|---|
| shim 是否加载了真件 | **是** —— `camshim.state`：`state=ok path=/vendor/lib64/hw/android.hardware.renderscript@1.0-impl.so id=camera bytes=376 open=0x… oplus_cb=0x… pcr=0x0 init=0x0 cfg=0x0` |
| provider | 开机后**连崩 6 次**（SIGSEGV ×4 + init SIGABRT ×2） |
| tombstone | 与运行期重启**完全相同的 CHI 签名**：`__cfi_slowpath ← GetFilteredMetadataTagsForDump()+1280 ← ExtensionModule ctor ← chi_hal_override_entry ← ChiOfflineCameraContext ctor ← ChiOfflineOpenContext ← LoadOfflineLib+328 ← OemLayer::init()+408` |
| watchdog | 判定不稳定 → 自动杀 provider → 卸挂载 → 删 cfg → `setprop ctl.start` → 相机恢复（pid 13399、6 个设备） |

**结论**：第三道墙与"运行期重启路径不同"无关，开机期一样撞。A 假设被否证。

（`pcr/init/cfg=0x0` 是预期的：ACE5 的 `camera.qcom.so` 不导出 `QCamera3HardwareInterface::*`，
那三处要靠 `ops_index=1` 在 `open()` 时从 ops 表按值取 —— 而 provider 根本没走到 `open()`。）

### 19.9 结构性结论：为什么这一层这么难

把三件事放在一起看，ACE5 上的障碍是**结构性的**，不是"再补一个符号"能解决的：

1. `camera.qcom.so` 同时是：HAL 入口（导出 `HMI`）、Oplus 回调提供者（导出 `SetOplusCameraInfoCallback`）、
   SoC 调度器（按名字加载 `camera.qcom.sm8650.so`）；OPPO 的 OEM 层和 QTI 的 CHI 都直接依赖这个**文件**；
2. 我们的 shim 必须在**同一个路径**上顶替它（provider 用 `hw_get_module` 按路径加载），
   于是真件只能被塞进另一个槽位 —— 而那个槽位**不是它的名字**；
3. 真件被我们加载后，OPPO/QTI 的后续初始化（CHI 离线上下文的钩子表）就没有被填上。

已排除的廉价解释：

- **"RTLD_GLOBAL 能让 `dlsym(handle)` 找到依赖里的符号"** —— 实测**不行**（`dlsym(host_handle,…)` 返回 NULL）；
  所以"把真件作为依赖挂上、让消费者从我们这里查到真符号"这条路只能靠 DT_NEEDED（运行时无法动态加，
  且 ACE5 上 /vendor 只读、无法为它新建文件）；
- **"真件自省自己的路径/名字所以行为不同"** —— 真件与 `com.qti.chi.override.so` 都**不导入**
  `dladdr`/`readlink`/`realpath`，也没有 `/proc/self/**` 之类的字符串 ⇒ 没有证据支持。

### 19.10 模块侧同时交付的两件东西

1. **自挂载的 bind 路线补全**（`post-fs-data.sh`）：旧版在 `overlayfs` 不可用时只 bind 了 shim，
   真件路径因此不存在 ⇒ shim 按惰性模块运行（相机不可用）。现在会：
   从白名单里挑一个**当下没有任何进程映射**的闲置槽位（首选 RenderScript）；bind 真件到该槽位；
   bind shim 到 `camera.qcom.so`；把开关文件写到 `/data/local/tmp/portalex/portal.cfg`
   （注意必须**在目录打好 `portalex_frame_file` 标签之后**创建，否则新文件继承
   `shell_data_file`，HAL 读不到）。
2. **watchdog（`service.sh`，late_start）**：开机后按"连续 3 次采样 pid 一致"判定 provider 是否稳定
   （崩溃循环时 init 每 5 秒重启一次，`pidof` 时有时无，不能只看非空）；不稳定就**自动回滚**
   （先杀 provider 再 umount，否则 EBUSY）并 `setprop ctl.start vendor.camera-provider`，
   全过程写 `deploy.log`。**这是 fail-inert 纪律在部署侧的实现**：一次实验不该换来一台需要手救的设备。
   本次实验里它按设计生效了。

---

## 20. 注入平面（用户裁决 C：ACE5 按机型分路走注入）

HAL 路线在 ACE5 上撞到结构性障碍（§19.9），用户裁决按机型分路：**ACE5 走注入平面，HAL 路线保留给
MI6 这类"模块挂载正常、没有 OPPO OEM 层"的机型**。注入平面本身早已存在于本仓（`Camera2MockHook`
换排水面 + 逐面 GL 喂帧 + provider 供片），缺的是 API 28+ 那条路。

### 20.1 补上 `SessionConfiguration` 路径（CameraX 走这条）

旧版只拦 `createCaptureSession(List<Surface>, …)`，对 `createCaptureSession(SessionConfiguration, Handler)`
**只记一条"暂不支持"的日志**。而 CameraX 正是走后者 —— 也就是说"装好了却没生效"的应用里，
CameraX 系的是最大的一类。

实现（`Camera2MockHook.interceptSessionConfig`）：

1. 从 `SessionConfiguration.getOutputConfigurations()` 里掏出应用请求的输出面
   （`OutputConfiguration#getSurfaces()` 是 @hide，退到公开的 `getSurface()`）；
2. 走与 List 路径**同一套**判定（`CameraMockController.prepare`：开关 + 命中素材 + 排水面）；
3. 用**公开构造器**重建配置：`OutputConfiguration(Surface)` + `SessionConfiguration(int, List, Executor, StateCallback)`，
   回调包一层（`onConfigured` 里先开始喂帧再回调应用）；
4. `addTarget` 那条重定向不用改 —— 应用手里的还是它自己的面，会被换成排水面。

不保留会话参数（`setSessionParameters`）：真实相机的画面整个丢弃，而 List 路径本来也没有会话参数。

**API 28 的类一律只用反射**（模块 `minSdk = 24`）：静态引用老设备上不存在的类会让整个
`Camera2MockHook` 解析失败，把别的钩子一起拖下水。安装时还会把 `createCaptureSession` 的
**重载清单**打一条日志（换 ROM/换 Android 版本时这是最省事的第一手资料）。

### 20.2 验证工具

- `MockLibraryJsonTest`（单测）：**手工构造的库 JSON 必须能被解码**。真机 bring-up 常常要绕过 UI
  直接塞一份素材（录一段要点十几下），这类人手写的 JSON 最容易在字段名/类型上与编解码器悄悄错开，
  而错开的后果是"库看起来是空的"而不是报错；
- 真机素材注入配方：`portal.xml` 里 `cameraMockEnabled=true` + `cameraMockLibrary=<JSON 数组>`，
  视频本体推到 `filesDir/camera/<file>`（**必须 chown 到 app 的 uid 并保持该文件的 SELinux 标签**：
  新文件由 root 创建会拿到 `app_data_file:s0` 而丢掉 MLS 类别，app 读不了 —— 实测踩过）。

### 20.3 ACE5 上验证卡在哪（**已查明：偏好被 ROM/LSPosed 重定向**）

现象（都是实测）：app 的**实际生效值**与 `<dataDir>/shared_prefs/portal.xml` 不一致 ——
文件里写 `cameraMockEnabled=false`/`speed=9.9`，Test 页显示**开**/3.50；`pm clear` 之后从零观察，
点开关**连 `shared_prefs/` 目录都不会被创建**（而 app 在 `files/`、`databases/`、`cache/` 里写得好好的）。

**根因**：这台 ROM 上 app 的偏好被**重定向**到了

```
/data/misc/apexdata/3330ff58-3211-44de-8268-d430d214ea4f/prefs/moe.fuqiuluo.portalex/portal.xml
  标签 u:object_r:lsposed_file:s0:c211,c257,c512,c768   （同一目录下还有另一个包的 moe.fuqiuluo.portal）
```

该文件是**活的**（`portal.xml` 11.8 KB，点一次开关它就更新），根命名空间里**没有**对应的 bind mount
（重定向发生在 app 自己的命名空间/LSPosed 侧）。`<dataDir>/shared_prefs/portal.xml` 是个**诱饵**：
早期版本留下的、之后再没被读过也没被写过。

两个后果：

1. **外部注入状态要写那一份**，而且必须**原地写**（`cat > file`，不能用 `sed -i`/`cp` 换 inode ——
   新文件会丢掉 `lsposed_file` 标签）。改完之后 provider 立刻返回了素材 ✓（这一步是实测通过的）。
2. **更重要**：模块侧的 `ModulePrefs`（XSharedPreferences，读 `<PortalEX dataDir>/shared_prefs/portal.xml`）
   在这种 ROM 上读到的是那份**诱饵** ⇒ 位置/传感器那些**开关的传播是断的**，
   而摄像头这条路没事（它走 provider，provider 在 app 进程里读的是活文件）。
   这很可能就是"ACE5 上模块看起来活着、却不干活"的根源。

另一个顺带发现：`pm clear` **清不掉**这份重定向的偏好（CE 被清了，它还在），所以那台机器上
"清除数据"并不等于清空设置。

候选修法（未实施）：① 模块侧统一改走 provider（摄像头这条路已经是这么做的）；
② `XSharedPreferences(File)` + 运行时发现重定向路径；③ 读不到时判定为"配置不可用"并明确报错，
而不是静默按"开关未开"处理。

### 20.4 目标应用上的实测（Android 16）

用已在 LSPosed 作用域里的 CameraX 应用（步道乐跑）实测，**钩子装上了**，并且重载清单是第一手资料：

```
[Portal] 摄像头模拟：createCaptureSession 重载 = SessionConfiguration | List,StateCallback,Handler
[Portal] 摄像头模拟：Camera2 hook 已安装（com.zjwh.android_wh_physicalfitness）
```

`am start .../.mvi.me.FaceActivity` 会立刻退出（需要登录态），所以"看到虚拟帧"这一步
还需要一个能自主进入相机的靶子（见 20.5）。

### 20.5 下一步（需要裁决）

- **A. 把 `com.oplus.camera` 加进 LSPosed 作用域**：它是 *List* 路径（dex 里 `createCaptureSession`
  22 处、无 CameraX），正好验证**既有**那条路 + 整条喂帧链路；本机没装 LSPosed 管理器，
  要用模块的 `action.sh`（`android_secret_code://5776733` 广播）拉起，或改
  `/data/adb/lspd/config/modules_config.db` 再重启（注意 WAL）。
- **B. 给本应用加一个 CameraX 探针页**（调试开关同时放开"OWN_PACKAGE 不注入"），
  这样 `SessionConfiguration` 这条**新**路径能被确定性地验证，不依赖第三方应用与登录态 ——
  代价是往 app 里加一个调试特性（与既有 `CameraProbeActivity` 的探针传统一致）。
- **C. 先修 20.3 的第 2 条后果**（模块侧配置通道），因为影响面比摄像头更大。

---

## 21. **突破**：让 vendor/odm 的 HAL 目录可写 ⇒ ACE5 上 HAL 路线跑起来了（2026-09-13）

起因：用户提出"把 vendor 挂载目标改成 `/system/vendor`，看能否突破"。先给出**字面结论**，再给出
真正突破的那一步。

### 21.1 `/system/vendor` 本身不带来新能力

```
/system/vendor -> /vendor                       （符号链接）
/vendor        /dev/block/dm-23 erofs（ro）      （只读）
同一 inode：65047 19722880 /vendor/lib64/hw/camera.qcom.so = /system/vendor/.../camera.qcom.so
```

内核解析符号链接后是**同一个挂载点、同一份只读 erofs** ⇒ 换这个名字既不能新建文件、也不改变解析路径。

### 21.2 真正的突破口：**tmpfs-upper 的 overlay 能把 HAL 目录变成可写**

模块里那条 overlayfs 自挂载一直是失败的。这次把失败原因抓出来了（很关键，之前被当成"SELinux 拒绝"）：

```
overlayfs: filesystem on '/data/local/tmp/ovl/upper' not supported     ← upperdir 在 f2fs 上被拒
```
- f2fs upper + `userxattr` ✗ 仍然被拒；
- **tmpfs upper ✓ 成功**：
  ```
  mount -t tmpfs tmpfs /data/local/tmp/ovltmp
  mount -t overlay overlay -o lowerdir=/vendor/lib64/hw,upperdir=/data/local/tmp/ovltmp/upper,workdir=… /vendor/lib64/hw
  ```
  叠加是**透明**的（54 个原件照常可见），新文件只是"多出来的那一层"。

新文件的标签会落在 `u:object_r:tmpfs:s0`（普通 tmpfs 不支持逐文件标签，`chcon` 会**静默无效**；
`mount -o context=…` 又被 SELinux 拒）。补一条窄规则即可让相机 HAL 域读到它们：

```
allow hal_camera_default tmpfs file    { read open getattr map execute }
allow hal_camera_default tmpfs lnk_file { read getattr }
```

### 21.3 部署形态（这是关键差别）

```
/odm/lib64/hw/camera.qcom.so      ← 我们的 shim（odm 优先于 vendor，两个消费者都先看它）
/odm/lib64/hw/camera.qcom.so.orig.so → /vendor/lib64/hw/camera.qcom.so   （软链，shim 自推导的本件路径）
/vendor/lib64/hw/camera.qcom.so   ← **真件原地不动**（36 MB 原件，自己的名字、自己的目录）
/vendor/lib64/hw/portal.cfg       ← 开关（real_hal=/vendor/lib64/hw/camera.qcom.so、real_hmi_bytes=376、ops_index=1）
```

与之前失败配置的**唯一实质差别**：真件这次是从**它自己的规范路径**加载的，而不是被塞进
renderScript 那个借用槽位。

### 21.4 结果（实测）

```
CamxLoad: find_in_path: … /odm/lib64/hw/camera.qcom.so name camera.qcom.so
CamxLoad: load: Start load /odm/lib64/hw/camera.qcom.so
PortalCamShim: 真实 HAL 已加载并整块接管：/vendor/lib64/hw/camera.qcom.so（tag=0x48574d54 id=camera api=0x0205，376 字节）
CamxLoad: load: CameraInfoCallback dlsym success!
CamxLoad: load: load /odm/lib64/hw/camera.qcom.so success
```

- provider **存活**（多次重启均不崩）、tombstone 计数不变；
- `dumpsys media.camera` 报 **6 个相机设备**，`com.oplus.camera` 能正常打开设备（客户端有记录）；
- provider 的 maps 同时包含 `/odm/…/camera.qcom.so`（我们的 shim）、`/vendor/…/camera.qcom.so`（真件）、
  `/vendor/…/camera.qcom.sm8650.so`（真件的 SoC 实现）、`/odm/…/camera.oemlayer.so`（OEM 层）。

**⇒ §19.4 那道"CHI 离线初始化读到空钩子表"的墙消失了。** 结论性表述（按实测）：
真件必须**从自己的规范路径**被加载，它才能完成自己那套初始化（很可能是按自身名字拼 `camera.qcom.<soc>.so`
并完成 Oplus/CHI 的注册）；从借用槽位加载时它做不到，于是 CHI 的钩子表始终是空的。§19.9 里
"结构性障碍"的说法因此要**修正**：障碍不是"必须同名同目录且无法两全"，而是"**真件必须在自己的目录里**"
—— 而这一点现在用一个 tmpfs-upper overlay 就解决了（shim 另起一个 odm 路径即可）。

### 21.5 仍未验证的一处（诚实记录）

冷启动相机后**没有看到 shim 的 `open()` 日志**（banner 有、`open` 那几行没有）⇒ 这台 QTI AIDL provider
打开设备的路径**可能不经过** `HMI.common.methods->open`。若如此，MI6 那套"在 open 里按值取
ops[4]/ops[0] 并打补丁"在 ACE5 上就不会生效（M0 不是没日志，而是没被调用）。
下一步只需在 `shim_open` 顶部加一行日志即可判定（overlay 让重部署变成**拷一个文件**）——
这也让迭代速度从"改模块+重装+重启"变成"cp + 杀 provider"。

### 21.6 对既有裁决的影响

裁决 C 的理由是"HAL 路线在 ACE5 上被结构性阻塞"。该前提**已不成立** ⇒ 值得重裁：
ACE5 上现在既能走 HAL 路线（对目标应用零注入），也能走注入平面；两者不再是二选一。

（另注：本节的挂载与 SELinux 补丁都是**运行期**的，重启即恢复原状；要固化得写进
`post-fs-data.sh` + `sepolicy.rule`，并把 overlay 的 upper 目录准备步骤一起带上。）



---

## 22. ACE5 的接管面：从 `device->ops` 一路查到"只能在函数入口挂钩"（2026-09-13）

§21 之后的目标是"在 ACE5 上把 M0 接管做出来"。这一节把**接管面到底在哪**查清楚了，
并且做出了一个**确实能命中**的入口钩子；但同时也测出：钩子一开，会话就撑不到
`configure_streams` 走完。下面全是实测数据，含失败路径 —— 失败的那部分是下次的起点。

### 22.1 `device->ops` 为什么永远是 NULL（结构性原因）

调用链（`ra0/ra1` 是钩子/包装层实测，不是推测）：

```
camx.provider-impl.so :: android::hardware::camera::common::V1_0::helper::CameraModule::open
  └─ camera.oemlayer.so+0xcdb30 区域（OPPO oemhal3.cpp 的 open，vaddr 0x6b908–0x6bd9c）
       └─ camera.oemlayer.so 在 0x6bb74 处 `blr x19`：
            x19 = 全局(0x14c718 的 .bss 槽) → hw_module_t* → [+0x20] methods → [0] open
       └─ /odm/lib64/hw/camera.qcom.so（我们的 shim）→ /vendor/lib64/hw/camera.qcom.so（真件）
```

真件的 `open()` 返回的 `camera3_device_t` 头是合法的（`tag=0x48574454`、`version=0x305`、
`module` 有效），但 **`ops`（偏移 +64）是 0**：open 返回后 50ms 内为 0，其后 500ms 内仍为 0。
原因是 OPPO 那层只是**借真件的设备指针用一下**：

- `oemlayer+0x1229dc`：`operator new(0x1b8)` 造一个 OemCamera3Device，把内层设备头
  （0x78=120B，含 +0x40 的 ops 字段）**拷进它自己的 +0x08**；构造器里**没有任何**写 +0x40 的指令。
- `oemlayer+0x795dc` 是**纯 getter**：`ldr x0,[x0,#0x8]; ret` —— 于是 `oemlayer+0x6bc7c`
  把 `*device` 又换回**内层那个原始指针**，包装对象只在那几行里活着。

⇒ provider 拿到的就是真件对象，而真件自己不填 `ops`；vendor 栈是**用它自己的接口表**驱动
CamX 的，不经过 `camera3_device_ops`。

### 22.2 那张"接口表"在运行时长什么样（读出来的，不是猜的）

`oemlayer+0xcdb30` 处的调用序列是
`ldur x8,[x29,#-0x8] → ldr x19,[x8,#0x8] → blr x19`（前面带 CFI 检查：`adr x8,0x141b30; cmp x19,x8`）。
照这个约定从**调用者栈帧**里把表读出来（实测，映射相对偏移）：

```
调用者帧里的指针 = 0x76f54475e8
  [ 0] +0  → /vendor/lib64/hw/camera.qcom.so+0x15170  [_ZN4CamX24CreateImageBufferManagerE…]
  [ 1] +8  → +0x13a60        ← configure_streams 走的就是这一格
  [ 3] +24 → +0x15998        [_ZN4CamX16HAL3MetadataUtil12FreeMetadataEPv]
  [ 4] +32 → +0x15e50
  [ 5] +40 → +0x14610
  [ 7]/[8]/[9]/[11] …
```

（上表偏移是**映射相对**的，加 0x30f000 才是文件 vaddr。这些是 CamX 内部的 HAL3 工具/入口表，
**不是** `camera3_device_ops`。）

### 22.3 `camera3_device_ops` 静态表不存在（三路独立证据）

1. **文件里没有任何 8 字节引用**：`0x1e46f60`/`0x1e49ca0`/`0x1e41a7c` 等在 36MB 文件里
   0 命中（唯一命中 `0x1e41a40` 的是 `.init_array`，构造器数组）。
2. **候选 run 全是 C++ vtable / CamX 内部桩**：`.rodata`/`.data.rel.ro`/`.data` 里所有 ≥12 连排
   指针 run 逐一验过，slot0 都是 `bti c; …ldr w0,[x0,x8]; ret` 这类 vtable thunk。
3. **运行时全地址空间探针也搜不到**：`/proc/self/mem` 扫 344MB（含只读 GOT），
   三个入口地址一处都没被存下来过（唯一命中是探针自己的栈变量）。

⇒ 结论：指针不落地、表不存在 ⇒ **唯一可行的接管面是函数入口本身**。

### 22.4 入口钩子（可用的机制 + 两个必须绕开的政策坑）

挂在 `真件基址 + vaddr`（`process_capture_request` = `0x1e49ca0`，
`configure_streams` = `0x1e46f60`，`open` = `0x1e41a7c`；`base` 必须用
**`dladdr(g_real_module)`**，`dladdr(&HMI)` 给的是我们 shim 自己的基址 —— 这个坑让前面
所有"搜不到表"的扫描都是在找垃圾地址）。

- **不能 `mprotect(PROT_WRITE|PROT_EXEC)`**：ACE5 上被 SELinux 直接拒，
  `avc: denied { execmem } for scontext=u:r:hal_camera_default`（Android 的 W^X）。
- **改走 `/proc/self/mem` + `pwrite`**（调试器插断点的同一条路，内核 `FOLL_FORCE` 只对私有映射
  生效）：不触发 mmap/mprotect 上的策略检查，页权限位也不变，依旧是 r-x。
- **CFI 安全**：改的是函数**体内**的字节，调用目标地址没变 ⇒ 调用方的
  `__cfi_slowpath` 校验照旧通过（这也解释了 §19 那次 `__cfi_slowpath` 崩溃：那次是拿
  借来的槽位当调用目标，地址本身就在 CFI 表外）。
- 桩的形态：入口前 16 字节换成 `ldr x16,#8; br x16; .quad 钩子`，钩子外壳 = 
  「5 条 mov 先把 x30/x0/x1/x29/sp 抄进 x9..x13」+「**运行时写入**的被顶掉的 4 条指令」
  + 记账调用 + 「跳回 entry+16」。被顶掉的 4 条先验证过**非 PC 相对**（adrp/adr/b/bl/b.cond/
  ldr-literal/cbz/tbz 一律拒绝），并且不许写 x9..x13。

### 22.5 结果：钩子**命中了**，但会话撑不住

命中（实测，钩子只记一次账）：

```
入口挂钩[0] real+0x1e46f60 第 1 次：调用者=0x7d19ee5b30（/odm/lib64/hw/camera.oemlayer.so+0x6fb30）
             a0=0xb400007cb0bd18d0（device）a1=0x7ffe…（stream_list）
```

即：**CamX 的 `configure_streams` 是被 OPPO 的 oemlayer 直接调的**（不是 provider 直接调）。

但把钩子打开后，会话会在 `configure_streams` 里挂掉：

| 配置 | Begin OPEN | Begin CONFIG | 逐帧出流 | provider |
|---|---|---|---|---|
| 原厂（无 shim） | 1 | 1 | 245 | 稳定 |
| shim，**无钩子** | 1 | 1 | 245 | 稳定 |
| shim，**开钩子** | 1 | **0** | 0–14 | ~1.5s 后被 init 重启，**无 tombstone** |

已排除的嫌疑：`mprotect`/execmem（已绕开）、CFI（地址未变）、`x8`/`x18` 未保存（已补上，
症状不变）、参数顺序写反（已修，日志正常了）、诊断线程（已改成 `open_probe=1` 才开，
默认关；关掉后症状依旧）。

下一步的几个具体方向（按代价从低到高）：
1. **改成"只顶掉 1 条指令"**：入口第 1 条是 `paciasp`，把它单独换成 `b` 到邻近的桩、
   桩里只重放 `paciasp` 再跳 entry+4 —— 这样若函数对"前 4 条必须连续执行"有隐含依赖
   （例如 vendor 的 SCS / PAC 记账），影响面就小得多；`b` 的 ±128MB 够不够要实测
   （或退回 `/proc/self/mem` 写 16 字节桩但要能证明 4 条重放等价）。
2. **挂在调用方而不是被调方**：oemlayer 的包装函数（`0x6c554`/`0x6cadc`/`0xcd94c`）
   序言不同（`paciasp; sub sp,#0x30; …`），钩子外壳已支持运行时填指令，可直接试。
3. **找 `process_capture_request` 在 ACE5 上的真实入口**：教科书的
   `0x1e49ca0` 这一格**从未被调用**（钩子挂上后一次都没响）——请求路径显然走的是
   CamX 的另一条路（候选：`0x1e4adc0` 那个函数、或经 CHI/CamX 内部 API）。

### 22.6 这一轮踩到的操作坑（都会伪装成"结论"，务必记住）

1. **别直接往 overlay 的 upper 目录写文件**：overlayfs 会继续供旧的缓存内容 ——
   表现是"改了 `portal.cfg` 却毫无效果"。**必须经挂载点写**（`> /vendor/lib64/hw/portal.cfg`）。
   这一条把"钩子疑似把相机搞坏"整整误导了一轮。
2. **`dladdr(&HMI)` 给的是 shim 自己的基址**，真件基址只能从 `dladdr(g_real_module)` 取。
3. **不能假定 `x29` 是栈帧指针**：有一次拿它当 FP 去读"调用者栈里的表"，结果 x29 里是
   oemlayer 的 .rodata 地址，读字符串字节再当指针解引用，直接把 provider 打死
   （`tombstone_03`：`SIGSEGV fault addr 0xf8a0`，回溯 `#01 portal_hook_01+72`）。
   现在有三重门槛：必须落在入口 SP 之上 1MB 内、所在映射可写、且不是文件映射。
4. **诊断必须在独立开关后面**：`open_probe=1` 才开（出参观察线程、读调用者帧、全地址空间探针）。
   这些动辄调用 `dladdr()`（拿 linker 全局锁）与读上千行的 `/proc/self/maps`，默认开着
   会把 provider 拖到被 init 重启。

### 22.7 当前设备状态（本轮收尾时）

dev 侧留的是**能用的形态**：overlay + shim（`/odm/lib64/hw/camera.qcom.so`）+ 真件原地不动
+ `portal.cfg` 里**没有** `hook_vaddrs`（钩子默认关）。实测 `Begin OPEN=1 / Begin CONFIG=1`、
逐帧出流、provider 稳定、tombstone 未增。全部改动都是运行期的，重启即回原厂。

### 22.8 突破：BTI 才是"钩子一开就打断会话"的真凶（2026-09-13 续）

§22.5 里"钩子一开、会话必死"的现象查清了，原因**不在**我们的 C 代码、也不在锁：
ACE5 真件 `camera.qcom.so` 的 `.text` 带 **BTI**（Branch Target Identification）。
我们把入口前 16 字节换成 `ldr x16,#8; br x16; .quad 钩子`，钩子里记账完再用
`br x16` 跳回 `entry+16` —— 而 `entry+16` 是函数**中部**，不是 `bti`/`paciasp` 这类合法落点，
于是间接分支被 BTI 判为非法目标，**直接 SIGILL**：

```
signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x00000078f6aaef70
  #00 pc 0x1e46f70  /vendor/lib64/hw/camera.qcom.so (CamX::configure_streams ... .cfi)+16
  #01 pc 0x10a00    /odm/lib64/hw/camera.qcom.so (portal_hook_nrec0+60)   ← 我们的外壳
  x16 0x78f6aaef70                                                        ← 返回点
```

**修法**：跳回中部改用 **`RET`**（RET 不受 BTI 约束）—— 把返回点写进 `x30` 再 `ret`。
`x30` 的原始（已签名）值早被重放的 `stp x29,x30,[sp,#-0x60]!` 存进函数自己的栈帧，
收尾时函数会自己恢复，不冲突。

### 22.9 现在的形态：**可用的、BTI 安全的、零注入的入口钩子**（实测）

- 记账**不调用任何 C 代码**：外壳里只用内联汇编读写我们自己的 `.bss`
  （`adrp/add/ldr/add/str/cmp/bne/…`），因此不会在 vendor 持锁的路径上触发 PLT 惰性绑定；
- 汇报放在 `shim_open`（非热路径）里做，钩子体一行 I/O 都没有；
- 外壳序言 → 重放槽的偏移必须**逐外壳对齐**（5 条 mov=20B、3 条 mov=12B；写错就把自己的计数指令覆盖掉）。

实测（真件 `configure_streams`，vaddr `0x1e46f60`）：

```
已挂钩[0] real+0x1e46f60：入口 0x7a21870f60 → 钩子 0x7a2c8e4a24（重放 d503233f a9ba7bfd a9016ffc a90267fa，返回点 0x7a21870f70）
零调用钩子[0] real+0x1e46f60 命中 1 次；首次：caller=/odm/lib64/hw/camera.oemlayer.so+0x6fb30
             a0=0xb4000079b59e1710（device） a1=0x7fd8129900（stream_list）
Begin CONFIG=2   逐帧出流=562   provider 稳定（pid 未变）   tombstone 未涨
```

即：**在系统层把 vendor HAL 的函数入口换掉、还能顺手拿到调用现场，同时相机行为完全不变**。
这就是 M0 在 ACE5 上真正跑通的那一刻；下一步（M1）就是让钩子**改东西**（请求/流/输出 buffer），
而"改"必须同样满足两条约束：**不许 call、不许跳回中部**。

余下的两个已知坑（都在本节新加）：
1. 跳回中部必须用 `ret`（见 22.8）；
2. 外壳序言与重放槽偏移必须对齐（见 22.9 第三条）——写错的表现是"挂上就崩、且没有 tombstone 说明"。

## 23. **M1 达成**：系统层把相机画面换掉，目标 App 零注入（2026-09-13）

一句话结论：在 ACE5 上，**不碰任何目标应用**，只在 vendor/odm 侧把相机 HAL 的输出缓冲内容换成我们自己的
像素 —— 预览整片变成我们填的颜色与图案，相机 App 的界面完全不受影响。这是 M1。

下面按"先纠正一个会误导全部分析的算术错误，再给实测路径，再给两条硬规矩"的顺序记。

### 23.1 先纠正：`portal_where` 打印的不是 vaddr

`portal_where` 输出的是"地址 − **所在映射**起始"，而 vendor 模块的文本段往往不是从 vaddr 0 开始的。
所以拿日志里的 `oemlayer+0xNNNN` 直接去反汇编文件是**错的**，正确换算：

| 模块 | 文本段映射 | 文件 vaddr 换算 |
| --- | --- | --- |
| `camera.oemlayer.so`（/odm） | `r-xp 0005e000` | 文件 vaddr = 打印值 **+ 0x5E000** |
| `camera.qcom.so`（/vendor，真件） | `r-xp 0030f000` | 文件 vaddr = 打印值 **+ 0x30F000** |
| `camera.qcom.so`（/odm，我们的 shim） | `r-xp 00000000` | 文件 vaddr = 打印值 |

踩过的表现：把 `oemlayer+0x6fb30` 当文件 vaddr 一查，落在一张分辨率常量表里（`bti c; nop; adr …; mov w1,#0xf00; mov w2,#0x870; b …`），
于是"调用者是谁"整段结论都是错的。加上 0x5E000 之后是 `0xCDB30`，正是上一轮离线分析里那个"调 CamX
`configure_streams` 的包装"，对上了。

### 23.2 实测出来的 ACE5 请求路径（每一级都有调用者与符号）

```
com.oplus.camera（目标 App，**完全没被碰**）
  └─ camera provider（AIDL）
      └─ camx.device-impl.so  CameraDeviceSession::configureStreams_Impl / ::processOneCaptureRequest
          └─ camera.oemlayer.so +0x6c554 / +0x6cadc      ← OPPO HAL3 层入口
              └─ camera.qcom.so +0x1e46f60 / +0x1e49ca0  ← CamX HAL3 层入口
```

命中计数是同一次会话里对齐的，两侧**逐一相等**：

```
零调用钩子[2] camera.oemlayer.so+0x6cadc   命中 215 次  caller=camx.device-impl.so processOneCaptureRequest
请求快照：      real+0x1e49ca0             命中 215 次  （同一会话）
零调用钩子[3] camera.oemlayer.so+0x6c554   命中   1 次  caller=camx.device-impl.so configureStreams_Impl
零调用钩子[0] real+0x1e46f60               命中   1 次  caller=oemlayer+0x6fb30
```

入口身份的确认方式不是猜，是**读它们自己引用的字符串**：把 `adrp/add` 对解出来，`0x1e49ca0`
引用了 `SCOPEEventHAL3ProcessCaptureRequest`、`process_capture_request`、
`camxhal3.cpp:1929 Invalid argument(s) for process_capture_request()`；`0x1e46f60` 引用 `configure_streams`。
（同一个技巧可继续给那 18 个"同序言"候选取名。）

顺带纠正上一轮的一条记录：`0x1e49ca0` **不是**"从没命中"——之前一次会话没命中是因为那次相机会话没跑起来，
不是地址不对。现在它是命中最多、最热的那条路。

### 23.3 CamX 看到的 HAL3 请求结构：**不是** AOSP `camera3.h` 那套

直接从 CamX 自己的日志点（`camxhal3.cpp:1845/1888`）反汇编它读的偏移，得到真布局：

| 偏移 | 含义 |
| --- | --- |
| `+0x00` | `frame_number` |
| `+0x08` | `settings`（`camera_metadata_t*`，实测 `size=9248 version=1`） |
| `+0x18` | `num_output_buffers` |
| `+0x20` | `output_buffers`（数组，每个条目 **0x28 字节**） |
| `+0x48` | `priv_settings` |
| `+0x58` | `priv_result` |
| `+0x68` | `num_priv_result` |

条目（`0x28` 字节）：`+0x00 stream`、`+0x08 buffer`、`+0x10 status`、`+0x14 acquire_fence`、
`+0x18 release_fence`、`+0x20 priv`。

实测快照（帧号 268）：`settings=0xb4000079cf396000  输出缓冲数=3  数组=0x7fe38269e8  priv_result=0xb40000799e6d3c00`，
三条流分别是 `2136x1200 format=0x23`（YUV_420_888，预览/录像）、`4096x3072 format=0x25`（RAW10）、
`640x358 format=0x23`（小图/分析）。

> 教训：**别照抄 `camera3.h`**。按 AOSP 顺序解这个结构，`settings` 会读成 `3`、`num_output_buffers` 会读成 `0`。

### 23.4 零调用请求快照钩子：形态 + 两条硬规矩

形态：3 条 `mov` 抄 `x30/x0/x1` → `+12` 重放被顶掉的 4 条 → 只抄 `req[0..23]` 共 192 字节 → `ret` 回 `entry+16`。
全程零 `call`（理由见 22.9）。

**规矩一：热路径里只许做"契约保证有效"的一级解引用。**
第一版按"标准布局"继续往下解（`req[+8]` → 缓冲数组 → `[+8]` → gralloc handle），当场 SIGSEGV：

```
signal 11 (SIGSEGV), fault addr 0x0000007700000000
#00 pc 0x12910  /odm/lib64/hw/camera.qcom.so (portal_hook_req0+132)
```

provider 与相机 App 一起死（相机 App 是连带受害者）。**"我以为布局是这样"的解引用不能放在钩子里。**

**规矩二：线程侧解析一切指针都走 `/proc/self/mem` + `pread`。**
坏地址只返回 `EFAULT/EIO`，**不可能崩**。配上"先看映射再读"，把"读异步快照里的脏指针"从致命变成无害 ——
这是"零调用钩子 + 线程侧解析"这套结构的正确读法。

汇报与记账解耦：钩子里一条 I/O 都不能有，`report_period=<秒>` 的线程负责把 `.bss` 里的账与快照打出来。

### 23.5 真正把画面换掉的那条路：fd 表里的 dmabuf

- provider 进程 `/proc/self/fd` 里实测 236 个 dmabuf；**相机输出缓冲是其中名字为 `MAP_UNKNOWN`、
  尺寸 `4038656` 的那 10 块**（按尺寸选靶，因为 fd 号每次 provider 重启都会变）。
- 在**我们自己的线程**里 `mmap(len, PROT_READ|PROT_WRITE, MAP_SHARED, fd, 0)` 之后就是普通内存写 ——
  不需要 call 进 vendor 的任何锁，钩子也不需要做重活。
- **必须跑赢 ISP**：相机以 ~30fps 把新帧 DMA 进同一批缓冲。实测三种写法的结果：

| 写法 | 结果 |
| --- | --- |
| 逐字节 `for` 循环写渐变（≈10 倍慢于 memcpy） | **输**：预览仍是实时画面 |
| `memset` 纯色 + `inject_us=25000` 间隔 | **输**：预览仍是实时画面 |
| `memset` 纯色 / 预生成模板 `memcpy`，紧循环（`inject_us=0`） | **赢**：预览整片是我们的内容 |

  推论：图案必须**预先算好**放在我们自己的内存里，热循环只剩 `memset`/`memcpy`。

视觉证据（截图取自设备，`screencap`）：

- 全 `0x00` 填充 → 预览整片**绿色**（Y=U=V=0 这条管线的呈现），相机 UI（快门、模式、EV）完全正常；
- 预生成渐变模板 `memcpy` → 预览整片**细条纹**（渐变周期 64 字节 < 一行的像素跨度）。

两条都证明：**这块缓冲的每一个字节都由我们决定**，而目标 App 一行代码都没被注入。

### 23.6 部署脚本的修正（v3）

`/tmp/ace5_deploy3.sh` 相对 v2 只改一件事：**cfg 在挂载 overlay 之后"透过挂载点"写**
（`> /odm/lib64/hw/portal.cfg`）。直接写 upperdir 会绕过 overlayfs 缓存，让 shim 读到旧配置 ——
这个坑上一轮已经让一整轮 A/B 结论作废。

### 23.7 现在的开关一览（都在 `/vendor/lib64/hw/portal.cfg`）

| 键 | 作用 |
| --- | --- |
| `hook_rec_vaddrs=` | 零调用记账钩子（真件，逗号分隔 vaddr，最多 32 个） |
| `hook_rec_ol_vaddrs=` | 同上，oemlayer |
| `req_vaddrs=` | 请求快照钩子（挂 `process_capture_request`） |
| `req_probe=1` | 汇报线程顺带列出进程内的 dmabuf |
| `report_period=<秒>` | 记账/快照的周期汇报（与热路径解耦） |
| `inject_size=<字节>` | 按尺寸选定注入靶（多块一起填） |
| `inject_fd=<fd>` | 指定单个 dmabuf |
| `inject_byte=0xNN` | 纯色填充字节 |
| `inject_us=<微秒>` | 填充间隔，`0` = 紧循环（要赢 ISP 基本得用 0） |
| `inject_pattern=1` | 不纯色，用预生成的渐变模板（证明逐像素可控） |

### 23.8 下一步（M2/M3）

M0（入口接管、不改行为）与 M1（系统层改输出缓冲、目标 App 零注入）都已在 ACE5 上跑通。剩下的是"内容"：

1. **定出这块缓冲的平面布局与几何**：`2136x1200` 的 YUV_420_888 无论按什么 stride 都乘不出 `4038656`
   （`4096×986` 倒是整除），说明有对齐/附加平面 —— 用"大周期图案 + 截图反推"就能定出来；
2. **照片**走的是另一条流（`4096x3072` BLOB/RAW），注入点要单独确认；
3. **视频**与预览同源，M1 的通道直接可用，缺的是"逐帧喂内容"；
4. 之后才是模块化：把"预生成图案/图像/视频帧"做成可配置的注入源。

## 24. M2：注入**真实图像帧**（几何标定 + 文件驱动的注入源）（2026-09-13）

§23 证明的只是"整块缓冲归我们写"。本节把内容从"纯色/渐变"升级成**真实的整帧图像**，
并且把"到底该怎么摆"这件事**测出来**而不是猜出来。结论：预览里显示的是我们自己合成的画面
（`PORTALEX / SYSTEM-LEVEL / CAMERA MOCK` 测试卡，方向正确、文字可读），相机 App 依旧一行没被注入。

### 24.1 先测行 stride：2176（不猜）

方法：同一幅图像相邻两行高度相关，所以"行间平均绝对差"在真 stride 处取极小。
候选 2048..4128 步进 32，在**两个基准偏移**（0 与 0xF4240）各扫一遍：

```
stride 候选 2176：平均行差 2     ← 真值
stride 候选 2208：平均行差 10
stride 候选 2144：平均行差 14
stride 候选 2080：平均行差 19
stride 候选 2112：平均行差 19
```

纵向剖面（stride=2176，每 200 行采一次）行差恒为 2 —— 说明整块都是图像数据。

> **踩过的坑**：第一版探针复用了 `inject_size` 选靶，而**注入线程同时开着**，
> 于是探针读到的是我们刚填进去的均匀值（全是 0x80），"平均行差"恒为 0，
> 看上去像"缓冲里没有图像"。修法：新增 `probe_size`，**只看不写**，与注入彻底解耦。

### 24.2 缓冲几何（实测）

| 项 | 值 |
| --- | --- |
| 行 stride | **2176**（= 128×17；有效宽 2136） |
| Y 平面 | 偏移 0，2176×1200 = 2,611,200 |
| UV 平面（NV12 交错） | 偏移 **2,611,200**，2176×600 |
| 帧数据合计 | 3,916,800 |
| 缓冲总长 | 4,038,656 = **2176×1856**（多出的 56 行是填充，不参与显示） |

### 24.3 显示变换：这块缓冲在竖屏上是被**转置+镜像**显示的

标定帧：整块填 0，只在 4 个已知偏移各画一条 1~4 行的白条。结果它们全部显示成
**贯穿整个预览高度的竖线**，且横坐标随缓冲行号单调增加 —— 于是一条缓冲行 = 屏幕上一列。
把标记画进 UV 平面（偏移 3,000,000 ⇒ 第 1378 行 > 1200）时该竖线带上**色度染色**，
反过来确认了 NV12 与 UV 平面起点。

由此得到唯一需要的变换（**缓冲内容 = 期望竖屏画面 先左右镜像、再转置**）：

```python
F = desired_upright.transpose(Image.FLIP_LEFT_RIGHT).transpose(Image.TRANSPOSE)
```

第一次只转置（没镜像）时，画面方向对、**文字左右反着**（测试卡上读出来是镜像的）；
补上镜像后 `PORTALEX / SYSTEM-LEVEL / CAMERA MOCK` 正常可读。方向不是推出来的，是看出来的。

### 24.4 注入源：文件 + 预生成模板，热循环只做 memcpy

- `mock_file=<路径>`：整帧原始数据（本例 4,038,656 字节），**读一次**放进模板；
- 热循环 = 对 10 块目标缓冲各做一次 `memcpy`，紧循环（`inject_us=0`）——
  必须跑赢 ISP 的 ~30fps DMA（§23.5）；
- 生成端（宿主机，PIL+numpy）：画测试卡 → 镜像+转置 → RGB→NV12（stride 2176）→ 写 `/data/vendor/camera/mock.raw`。

> 为什么 mock 文件放 `/data/vendor/camera/`：它是 `vendor_camera_data_file`，
> `hal_camera_default` 本来就能读；而把 3.9MB 文件直接写进 `/odm/lib64/hw` 的 tmpfs upper
> 会被策略拒（`touch` 都 `Permission denied`，`portal.cfg` 那种小文件是历史遗留才写得进去）。

### 24.5 现在的开关（在 §23.7 基础上新增）

| 键 | 作用 |
| --- | --- |
| `mock_file=<路径>` | 整帧原始数据当注入模板（优先于 `inject_byte`/`inject_pattern`） |
| `probe_size=<字节>` | **只看不写**的探针靶尺寸（必须与注入解耦） |
| `stride_probe=1` | 行间相关性扫描，实测 stride |
| `bufmap=1` | 按 256KB 段打印目标缓冲的内容分布 |

### 24.6 证据与遗留

证据：`screencap` 出的预览整幅是我们合成的测试卡（彩条、棋盘、圆环、三行可读文字），
相机 UI（快门/模式/EV/缩略图）完全正常，provider pid 稳定，无新增 tombstone。

遗留（下一轮）：

1. **照片**：点快门那一次 App 退到了后台、没有落盘新 JPEG，所以"拍照存下来的到底是谁的画面"**尚未验证**；
   紧接着要确认的是静止流（`4096x3072` RAW/BLOB）的缓冲类别与注入点；
2. **视频**：与预览同类缓冲，通道应当已通，但需要真录一段来验收；
3. 色度略有偏移（文字边缘有彩边），UV 平面对齐还需要再校一次。

## 25. 照片与录像：一个自己挖的坑、多类注入、以及诚实的中间状态（2026-09-13）

### 25.1 **推翻上一轮的结论**：`media.swcodec` 崩溃不是"与己无关"，是我们自己造成的

前几轮一直把 `media.swcodec` 反复 abort（`GraphicBufferMapper: gralloc-mapper is missing`）记成
"设备既有的、与我们无关的问题"。**错了。** 这一轮做了干净的对照：

| 部署状态 | 快门结果 | `gralloc-mapper is missing` |
| --- | --- | --- |
| `/odm` + `/vendor` 两层 overlay 都挂着（前几轮的状态） | **没有文件落盘**，相机 App 直接死 | 反复出现 |
| 把 `/vendor/lib64/hw` 的 overlay 退掉（`/odm` 保留） | **照片正常落盘**（3.3MB JPEG） | **0 次** |

即：**`/vendor/lib64/hw` 上的 overlay 会打死软件编解码进程**（JPEG 编码走 `media.swcodec`），
于是"按快门→编码→落盘"整条链路断掉，表象是"相机 App 一闪就没了"。
上一轮把点快门失败解释成"App 退到后台"也是被这个现象骗了。

> 教训：**"既有问题"这个判断必须用"退回原厂状态再验一次"来支撑**，不能因为"前几轮它就在"就归因给设备。
> 这次一退回，故障立刻消失 —— 归因错误代价是整整两轮的验证方向。

### 25.2 部署 v4：只挂 `/odm`，配置与素材全放相机自己的数据目录

`/tmp/ace5_deploy4.sh`：

- **只挂 `/odm/lib64/hw`**（shim 的 `.so` 放这里），不再碰 `/vendor`；
- `portal.cfg` 与注入素材放 **`/data/vendor/camera/`**（`vendor_camera_data_file`：
  相机 HAL 本来就在读写这个目录 ⇒ 不需要任何 overlay、也不需要额外 sepolicy）；
- shim 的 cfg 候选路径新增首选项 `/data/vendor/camera/portal.cfg`。

两个实测小坑：

1. **`: > 文件` 只截断、会保留旧标签**。曾经把 cfg 先 chcon 成 `portalex_frame_file`，
   后来即使路径改到 `/data/vendor/camera`，标签还留着 ⇒ HAL 读不到。**必须先 `rm` 再建**
   （或显式 chcon 成 `vendor_camera_data_file`）。
2. 把 cfg 放 `/data/local/tmp/portalex/`（标签正确）**也读不到** —— 路径中间那层
   `/data/local/tmp` 是 `shell_data_file`，`hal_camera_default` 没有 dir search。

### 25.3 多类注入：`mock_map=<尺寸>:<路径>[,…]`

预览、录像、4K 录像是**三块不同尺寸的缓冲**，一个模板套不了三张画面。
新增 `mock_map`，注入线程按**尺寸**给每块 dmabuf 选模板（尺寸找不到模板的就不动）。

同时修掉一个更隐蔽的 bug：**重扫周期用真实时钟**。
之前按"每轮 8 微秒"累加，可每轮其实要 memcpy 几十 MB（≈10 毫秒）——
所谓"15 秒重扫"要五小时才到；症状是**一从照片模式切到录像模式，注入线程还抱着旧缓冲**，
预览瞬间恢复成实时画面。

### 25.4 实测到的缓冲类别（ACE5）

| 用途 | dmabuf 名字 | 尺寸 | 几何（实测/推算） |
| --- | --- | --- | --- |
| 照片模式预览 | `MAP_UNKNOWN` ×10 | 4,038,656 | 2136×1200, stride **2176**（行差 2 实测），Y@0 / UV@2,611,200 |
| 录像模式预览 | `MAP_UNKNOWN` ×20 | 3,194,880 | 1920×1080, stride 1958..2048（未逐值实测） |
| **4K 录像** | `MAP_UNKNOWN` ×6 + `qcom,system` ×3 | 12,591,104 | 未定：stride 试探给出 2048/4096 并列 0（探针打得太早，缓冲还是空的） |

### 25.5 诚实的中间状态（这两条**还没有做到**）

- **照片**：点快门**没有**走我们挂钩的那批 HAL3 入口。实测把 18 个入口全挂上再拍照，
  所有计数只是预览帧在涨（281→365），没有任何一条新入口被点亮；
  同时请求快照里**只出现过预览那三路流**（2136×1200 / 4096×3072 RAW / 640×358），
  静止流一次都没出现在请求里。落盘的 JPEG 仍是真实场景。
  ⇒ 静止/快照走的是另一条路（很可能是 `camx.device-impl.so` 的快照/离线会话，或 provider 的
  snapshot API），**下一轮的第一件事就是把它找出来**（那里有符号，可以直接按名字挂）。
- **录像**：把 3,194,880 那一类注进去，**录像模式的预览**确实变成我们的画面；
  但**存下来的 4K MP4 不是**：一次录出来是真实场景，另一次（三类一起注、每轮 ~302MB）录出来是
  一片绿色噪声 —— 后者更像"内存带宽被我们压垮、编码器来不及"的损坏，而不是我们的内容。
  ⇒ 4K 那条流**还没拿下来**，需要先定几何、再只注这一类、把每轮写入量压下来。

### 25.6 这一轮之后的状态

- 部署：只挂 `/odm`、cfg+素材在 `/data/vendor/camera/`、**未开注入**（`req_vaddrs` + 两个记账钩子）；
- 相机：预览/拍照/录像全部正常，`gralloc-mapper` 零次 abort，provider 稳定，无新增 tombstone；
- 照片能落盘这件事本身就是这一轮的净收益（前几轮它一直是坏的，且被误判成设备问题）。

## 26. 照片的输入是 **RAW10**；录像被编码器那一侧挡住（2026-09-13）

### 26.1 结论先行

- **照片链路的输入是 RAW10（`4096x3072`，缓冲 15,728,640）** —— 实测：只注这一类，照片内容立刻由我们决定；
  注预览那一路 YUV（4,038,656）时照片**完全不变**（真实场景）。这是本轮最硬的结论。
- **静止/快照请求从不走我们挂钩的 HAL3 入口。** 把它钉死的是一个 2 毫秒周期的观察线程
  （`req_watch=1`：盯住请求快照，只要"输出流集合"变了就打印）——**两次快门期间一次变化都没有**，
  全程只有预览那三路流（2136×1200 / 4096×3072 RAW / 640×358）。
- **JPEG 不是在我们这个进程里编码的。** `jpeg_probe=1` 以 300 毫秒周期扫遍进程内所有 dmabuf
  找 `FF D8 FF`，快门前后一次都没命中 ⇒ 码流在别的进程里生成（这与"拍照那会儿
  `media.swcodec` 崩溃就拍不了"完全吻合：编码在软件编解码进程里）。
- **录像被编码器那一侧挡住**：注 12,591,104 那一类确实影响到了录像（存下来的画面变成我们数据的花屏），
  但拿不到干净画面（撕裂）；另外 **1080p30 这段配置在本机连基线都是全黑**（不注入也黑）——
  这条是设备/配置问题，不是我们造成的（这次先做了基线对照才敢下结论）。

### 26.2 照片：RAW 域可控，颜色由 ISP 决定

注入三个带（上 1/3 中灰、左下暗、右下亮）的合成 RAW10 后，落盘照片变成
"上方深紫带 + 下方亮粉渐变"——**结构是我们画的，颜色是 ISP 给的**。
注一份"横向灰阶 + 白竖条 + 棋盘"的 RAW 时，照片变成 1.16MB 的**残缺 JPEG**（PIL 拒绝解码、
无 EOI）——说明这份 RAW 让它的流水线出错，但**确实进了流水线**。

> 含义：照片模拟在 RAW 域是成立的（几何/明暗可控），但要"渲染一张自然颜色的任意图片"，
> 还差一次**逆 ISP**（黑电平、CFA、AWB/CCM、tone）。这不是这一轮能做完的。

为此新增了 **`raw_dump=<尺寸>:<路径>`**：把现场那一帧原样落盘，
将来可以"取真帧 → 改一处 → 喂回去"，人眼看到的是自然照片、改动处是我们的。

### 26.3 录像：写下多少不是瓶颈，时序才是

- 早期把三类一起注（每轮 **302MB**）→ 录像花屏，当时判断是带宽压垮；
- 加上 `mock_map=<尺寸>@<名字子串>:<路径>` 按 dmabuf 名字过滤后，每轮只剩 **41MB**（11~13 块），
  花屏**依旧** ⇒ **不是带宽问题，是撕裂**：我们在写、编码器在读同一块缓冲。
- 顺带排掉一个误判：把录像切到 1080p30 之后录出来全黑 —— **不注入也全黑**，
  基线对照证明与本项目无关（切分辨率后的会话状态问题）。

### 26.4 本轮新增的开关

| 键 | 作用 |
| --- | --- |
| `req_watch=1` | 2ms 周期盯请求快照，输出流集合一变就打印（用来回答"静止请求到底走不走这条路"） |
| `jpeg_probe=1` | 300ms 周期在进程内所有 dmabuf 里找 JPEG 码流 |
| `raw_dump=<尺寸>:<路径>` | 把现场那一帧原样落盘 |
| `mock_map=<尺寸>[@<名字子串>]:<路径>[,…]` | 按尺寸（可选按 dmabuf 名字）分模板 |

### 26.5 状态与遗留

- 部署已恢复干净：只挂 `/odm`、cfg+素材在 `/data/vendor/camera/`、未开注入；
  预览/拍照/录像正常、`gralloc-mapper` 零 abort、provider 稳定。
- 遗留（按优先级）：
  1. **录像拿干净画面**：要么找到编码器真正读的那一组缓冲（而不是按尺寸猜），
     要么把"写"与"帧节拍"对上（例如只在请求回调之后写一轮）；
  2. **照片逆 ISP**：RAW 域校准（黑电平/CFA/增益）才能喂任意图片；
     短路径是 `raw_dump` 取真帧后局部改写；
  3. 1080p30 全黑是设备侧问题，记下来免得下一轮又误判成自己的锅。

## 27. 想从"结果投递"那一刻写缓冲：设计做完了，落点被两件事挡住（2026-09-13）

### 27.1 为什么要换落点

录像拿不到干净画面的原因是**时序**（§26.3 已排掉带宽）：编码器是"ISP 写完 → 立刻读"，
盲目死循环的写入要么早于 ISP（随后被覆盖）、要么晚于编码器读完。唯一正确的做法是
**在结果投递那一刻写**，并且**写完再放行**。

### 27.2 已经做出来的东西

- `PORTAL_HOOK_STUB_RES` 外壳：零调用，除记账外只做三件事 —— 存结果指针、代次 +1、
  把 `g_res_done` 清零后**有限自旋**（`movz/movk` 拼出的 2,000,000 次上限，约 2~4 毫秒）
  等写手完成，超时也放行（绝不把 HAL 线程挂死；顺带记一笔：`mov x17, #2000000` 这种
  立即数在 AArch64 上根本编码不了，必须 `movz`+`movk`，编译器报的是
  "expected compatible register or logical immediate"）。
- `res_writer=1` 写手线程：按 `gen` 代次触发，解析 `camera3_capture_result_t`
  （+0 帧号 / +4 输出路数 / +8 输出缓冲数组），逐路取出 `buffer_handle_t`，
  解出 native_handle 的 fd → `fstat` 尺寸 → 在 `mock_map` 里找模板 → `mmap` + `memcpy`，
  然后置 `g_res_done` 放行；找不到模板就立刻放行（不拖慢预览）。

### 27.3 两个挡住落点的实测事实

1. **`camx.device-impl.so` 的结果投递函数是跳板，挂不上。**
   `sProcessCaptureResult` / `sNotify` / `sProcessCaptureRequest` 三个符号的头两条指令都是
   `bti c; b <实现>` —— 其中的 `b` 是 PC 相对，**不能搬到我们的外壳里重放**，
   安装器直接按规矩拒绝（`前 4 条指令里有 PC 相对指令，不能重放，跳过`）。
   这是"重放式入口钩子"的固有边界：**跳板类入口不可挂**。
2. **挂上这套函数会让相机 App 起不来。** 改成挂六个可重放的候选
   （`constructCaptureResult` `ResultBatcher::processCaptureResult/processOneCaptureResult/
   invokeProcessCaptureResultCallback` `processCaptureRequest` `processCaptureRequest_Impl`）之后，
   相机 App **两次启动都当场死掉**（`app died, no saved state`），一次命中都没有。
   把钩子撤掉、只留请求路径那几个，相机会话立刻恢复正常。

   ⇒ 经验规则（记下来）：**在 `camx.device-impl.so` 的会话函数上挂钩，无论"看起来多安全"，
   都必须先做一次"能不能开机"的对照**；本项目目前的可用落点仍然只有
   `camera.qcom.so` 的 HAL3 入口（`0x1e49ca0` 等）与 `camera.oemlayer.so` 的两个入口。

### 27.4 这一轮之后的诚实状态

- 已经**稳定可用**的：照片模式预览（任意图像、几何正确）、录像模式预览（1920×1080 那一路）。
- **尚未拿下**的：存下来的照片（RAW 域可控，颜色由 ISP 决定）、存下来的 4K 录像（能写进去但撕裂）。
- 这一轮没有把上面两件事做完，原因写清楚了：正确的落点（结果投递）在本机是一个
  **不可挂的跳板**，而绕道挂它的实现函数会把相机 App 打死。下一步要么找
  **可挂的结果路径**（例如 `camx.device-impl.so` 里别的、非跳板的结果函数，或
  oemlayer 侧的 `process_capture_result` 回调），要么改从
  **写时序**入手（把盲目循环换成"跟着请求节拍写一轮"）。

## 28. 现状交接：什么是真的做到了、什么没有、下一步从哪根线头接（2026-09-13，第 8 轮收尾）

### 28.1 能力矩阵（只写有实测证据的）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 系统层改相机输出缓冲（目标 App 零注入） | **做到** | 预览整幅变成我们填的内容（绿/渐变/测试卡），相机 UI 不受影响 |
| 照片模式预览 = 任意图像、几何正确 | **做到** | `PORTALEX / SYSTEM-LEVEL / CAMERA MOCK` 测试卡在预览里方向与文字均正确 |
| 录像模式预览（1920×1080 那一路） | **做到** | 视频模式预览显示 `VIDEO MOCK 1920x1080` 测试卡 |
| 存下来的**照片**由我们决定 | **部分** | 注 RAW10 ⇒ 照片内容随我们变；注预览 YUV ⇒ 照片完全不变。结构可控，颜色由 ISP 决定（逆 ISP 未做） |
| 存下来的**录像**由我们决定 | **未做到** | 注 4K 那一路 ⇒ 录像仍是真实场景；注 1080p 那一路 ⇒ 录像花屏（撕裂） |
| 入口钩子本身（零调用、BTI 安全、可挂 oemlayer 与 camera.qcom） | **做到** | 多轮实测，命中计数与相机行为均正常 |

### 28.2 复现"已做到"的那部分（最短路径）

```sh
# 1) 部署（只挂 /odm；cfg 与素材在相机自己的数据目录）
adb push camera-hal/build/intermediates/cxx/Debug/4r1u4l52/obj/arm64-v8a/camshim.so /data/local/tmp/camshim.new.so
adb push <宿主机生成的 mock 帧> /data/local/tmp/mock.raw
adb shell "cat /data/local/tmp/mock.raw > /data/vendor/camera/mock.raw; chmod 666 /data/vendor/camera/mock.raw"
adb shell "sh /data/local/tmp/ace5_deploy4.sh \
  'real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;report_period=30;\
mock_map=4038656:/data/vendor/camera/mock.raw;inject_us=0'"
```

- 预览帧的几何（实测）：`2136×1200 YUV420`，stride **2176**，Y@0、UV@2,611,200，
  缓冲 **4,038,656**；屏幕上是 **镜像+转置** 显示，所以素材要
  `F = desired.transpose(FLIP_LEFT_RIGHT).transpose(TRANSPOSE)` 之后再转 NV12。
- 录像模式预览用 **3,194,880** 那一类（1920×1080），4K 录像用 **12,591,104** 那一类（6 块 `MAP_UNKNOWN`）。
- 回滚：`umount /odm/lib64/hw; umount /data/local/tmp/odmtmp; setprop ctl.restart vendor.camera-provider`
  （**不要**挂 `/vendor/lib64/hw`，理由见 §25.1）。

### 28.3 未完成的两件事，以及为什么卡住

1. **存下来的录像**：正确落点是"结果投递那一刻"，但
   `camx.device-impl.so` 的 `sProcessCaptureResult`/`sNotify`/`sProcessCaptureRequest`
   都是 `bti c; b <实现>` 跳板（**不可重放、挂不上**），而改挂它们的实现函数会让
   相机 App 直接起不来（§27.3）。剩下的路：① 找 oemlayer 侧的 `process_capture_result`
   回调（那是我们能挂的模块）；② 把盲目循环换成"跟着请求节拍写一轮"。
2. **存下来的照片**：输入已确定是 RAW10，结构可控、颜色被 ISP 重写。
   要喂"任意自然图片"需要一次逆 ISP（黑电平 / CFA / 增益 / CCM 校准）；
   短路径是用 `raw_dump=<尺寸>:<路径>` 取真帧、局部改写后回注（本轮已把工具做好）。

### 28.4 这一路顺手纠正的两个错误判断（写在最后提醒未来的自己）

1. `media.swcodec` 的 `gralloc-mapper is missing` **不是设备既有问题**，是 `/vendor/lib64/hw`
   上的 overlay 造成的（§25.1）——"既有问题"必须退回原厂状态验一次才敢下结论。
2. 1080p30 录像全黑**也不是**我们的锅（不注入也黑，§26.3）——同样是先做了基线对照。

## 29. 录像面是**瓦片（UBWC）布局**，不是时序 —— 纠正 §26.3（2026-09-13，第 9 轮）

### 29.1 结论先行

- **录像这条路真正的障碍：编码器那一侧的像素面不是线性排布的（UBWC / 瓦片 + 压缩元数据）。**
  线性模板写进去，读出来只能是"规则小方块"；这**不是**竞态、不是带宽、也不是我们写得太晚。
- **§26.3 的"撕裂/时序"判断是错的。** 当时手上只有"花屏"一个现象，就把它归因成"我们在写、编码器在读"。
  本轮用三样互相独立的东西把它钉死（直方图 / 存活探针 / 活缓冲落盘 + 线性 stride 扫描），
  并做了**补光基线对照**：零注入干净、注了就出现瓦片状色带。
- **§28.1 的"注 4K 那一路 ⇒ 录像仍是真实场景"也不准确**：12,591,104 那一类**根本不是 4K 录像面**
  （是 HAL 内部面，34 块 `MAP_UNKNOWN`）。注它的效果是"实景 + 顶部一条瓦片状绿带"——
  属于**污染了流水线的中间面**，既不是"没反应"，也不是"我们的画面"。

### 29.2 先把量具修对（本轮的三处工具缺陷）

| 缺陷 | 症状 | 修法 |
| --- | --- | --- |
| `mock_map=<尺寸>@<名字>` 的分隔符是**逗号** | 高通堆名**自带逗号**（`qcom,system`）⇒ 条目被切成两半、**一条都解析不出来且静默丢弃**（实测：注入线程起来了、一行日志都没有，录像自然"没反应"） | 分隔符改成 `|`；解析结果**必须打日志**（条数 + 每条尺寸/名字/路径 + 丢弃数） |
| dmabuf 报告"只列前 40 条" | 按 `readdir` 顺序取前 40 ⇒ 每次都打印同一批，同一尺寸可能有 34 块，**录像面可能永远轮不到被打印**（据此曾误判"这一档只有 MAP_UNKNOWN"） | 改成**直方图**（尺寸 × 堆名 × 个数，按尺寸降序，上限 64 且显式标注"已截断"），与遍历顺序无关 |
| 现场帧落盘抓第一块 | 抓到的是**全零的闲置缓冲**（池化下同尺寸有 30+ 块）⇒ 分析布局等于分析空气 | 先做 **120 毫秒两轮采样**，只落"活的"那块；支持 `|` 分隔多个尺寸 |

新增/变更的运行期开关：

| 键 | 作用 |
| --- | --- |
| `live_probe=<秒>` | **存活探针**：把进程里所有 ≥512KB 的 dmabuf 各读三个 64 字节窗口，与上一轮比，报"内容在变"的那些（= 此刻有硬件在写的面） |
| `inject_sync=1` | 只在"ISP 刚重填过这块缓冲"时写一轮（头部 16 字节判据），而不是盲目死循环；每秒打印写次数 |
| `raw_dump=<尺寸>[\|<尺寸>…]:<路径>` | 每个尺寸落一块**活的**现场帧 |
| `mock_map=a@名字\|b@名字:…` | 多条目改 `|` 分隔 |

### 29.3 证据一：线性 stride 扫描 —— 预览是线性的，录像面不是

对**活的**现场帧做"行间差最小"扫描（`stride` 猜对了，相邻两行的差会显著塌下来）：

| 缓冲类 | 最佳 stride | 平均行间差 | 判读 |
| --- | --- | --- | --- |
| 4,038,656（照片模式预览） | **2176** | **0.42** | 干净的线性 NV12（与 §24 实测一致） |
| 3,194,880（录像模式预览） | **1920** | **0.88** | 干净的线性面 |
| 12,591,104（`MAP_UNKNOWN`） | 7168（最好的一个） | 33.8 | **任何线性 stride 都不成立** |
| 19,574,784（`MAP_MPG_90`） | 1024 | 25.5 | 同上 |
| 6,852,608（`MAP_MPG_92`） | 4096 | 33.8 | 同上 |
| 4,718,592（`MPG_73`） | 8192 | 16.0 | 同上（唯一"像样"的一个，仍远高于预览） |

预览面 0.4~0.9，录像链路的面 16~34 —— **差 20~80 倍**。线性布局下不可能出现这种行间噪声，
⇒ 这些面是**瓦片/压缩**排布（UBWC）。这也解释了为什么线性模板的可见效果是"规则小方块/阶梯边"的色带。

### 29.4 证据二：存活探针（录像中谁在写）

4K 录像期间"内容在变"的类（全部是 4096 的整数倍，说明分配按 4096 行距对齐）：

- `12,591,104` `MAP_UNKNOWN` ×24（HAL 内部面）
- `3,194,880` `MAP_UNKNOWN` ×多（预览）
- `19,574,784` `MAP_MPG_90_Buffer*`、`6,852,608` `MAP_MPG_92_Buffer*`、`4,718,592` `MPG_73_*`
- `7,532,544` / `1,105,920` `CBM_OplusVideo4kEIS3PreviewEIS2*`（EIS 相关）
- 另有 `12,443,648`（`MPG_139_Buffer1`，尺寸最接近 3840×2160 NV12）**整场录像只被写过一次** ⇒ 闲置面，注它录像完全不变（实测确认）。

### 29.5 证据三：补光基线对照

暗室里"实景黑"与"我们的内容"分不开，所以先用相机的**闪光灯常亮**补光再测：

| 配置 | 落盘 4K 帧的表现 |
| --- | --- |
| 零注入（基线） | 干净的实景（红/白物体），**无任何色带** |
| 注 `12,591,104`（均匀 0x80） | 实景 + **顶部约 13~15% 高度的一条瓦片状绿带**（阶梯边、块状） |
| 注 `12,443,648`（三带灰阶） | 与基线**逐像素同级**（均值 160.48 vs 160.30）⇒ 无影响 |

⇒ 色带确实来自我们（基线没有），且**形态就是瓦片**；但它只是"污染中间面"的副作用。

### 29.6 与代码里已有判断的关系（重要）

`camshim.c` 里其实早就有这条结论（§15.8 时期写下）：`0x10000000` 是**厂商 HAL 在
configure_streams 里后加的 UBWC 位**，`usage_patch=2` 能把它清掉、让分配退回线性。
但同处的实测教训同样有效：**编码器流不能清** —— 清掉之后录到的是"平铺噪声"，
说明 **Venus 按 UBWC 布局读这块内存**。

⇒ 所以"把录像面改成线性"这条路对**编码器流**不成立；要让录像变成我们的内容，
只能**把内容按 UBWC 写**。这与 §15.8 当年"目前没有可行手段"的结论并不冲突，
但现在我们有了当年没有的东西：**能落活缓冲、能补光、能做基线对照的完整量具**。

### 29.7 下一轮的入口（按优先级）

1. **UBWC NV12 写入器**：按瓦片排布（Y 面 32×8、UV 面 16×8 一类的几何）＋ 元数据平面，
   把合成画面写成瓦片；**用"录下来的 MP4"当 oracle 迭代**（量具已就绪：`raw_dump` 落活帧、
   闪光灯补光、零注入基线）。
2. 先做一个**布局求逆**的小实验：拿活的现场帧，按候选瓦片规则重排，看能否还原出与
   MP4 帧一致的画面 —— 若能，则同时拿到"是哪一块"与"怎么排"。
3. 不要再去动 12,591,104 那一类当"录像面"（它只是中间面，注它只会污染）；
   也不要清编码器流的 UBWC 位（记录在 §15.8，已复现过）。

## 30. 录像这一路的竞态与"带宽"都被量出来了；屏幕上那一半解释了之前的困惑（2026-09-13，第 10 轮）

### 30.1 结论先行

1. **写入时机只有两种结局**（在 12,591,104 这一类上实测）：
   - `inject_sync=1`（头部一变就写一轮）→ 我们的数据**只在顶部约 13~15% 存活**（下面被 ISP 后续的写入盖掉）；
   - `inject_sync=2`（等整块"静止"再写）→ **一帧都留不下**，录像与零注入基线逐像素同级。
   ⇒ **编码器是在 ISP 写完的瞬间就读**的：想靠"等 ISP 写完再写"取胜是不可能的。
2. **盲目连续写才是"赢下这一帧"的手段**（这正是轮 5~8 里"注 3,194,880 / 12,591,104 能改到画面"的原因），
   代价是**内存带宽**：本轮实测，对着 3,194,880 这一类（~20 块、每轮 64MB）全速盲写时，
   录出来的 4K 视频**整帧全黑**（均值 3.0/0.19/4.0）—— ISP 被挤掉了。
   ⇒ §26.3 当时"排掉带宽"排早了：带宽**确实**是一个独立的失效模式，只是与瓦片布局是两回事。
3. **4K 录像的画面不来自 3,194,880 那一类**（= 预览面）。对照很干净：同一次录制里，
   预览上**能看到我们的测试卡**（顶部色带条 + 棋盘格），而落盘的 4K 视频与零注入基线**逐像素同级**
   （160.8/102.9/87.2 vs 160.3/103.4/90.0）。
4. **4K 录像的画面确实来自 12,591,104 那一类**（注它就出现在录像里，虽然只是一条带）。
   ⇒ 这一轮把"是哪一块"钉住了：**12,591,104 `MAP_UNKNOWN`（34 块）就是 4K 录像面的候选**，
   剩下的问题纯粹是"**用正确的排布把内容写进去**"。

### 30.2 一个必须写下来的操作教训（本轮被它浪费了一次实验）

相机模式条的**位置随当前模式平移**，所以"固定坐标点视频"是不可靠的：
实测过一次把 `视频` 点成了 `专业`（随后两次"录制"点成了拍照），还遇到过闪光灯菜单吞掉点击。
**规矩：切模式前后都要 `uiautomator dump` 核对 `内容描述` 里的"…模式"字样，
坐标要按当前模式现算（照片居中时视频在左一格，专业居中时视频在右一格）。**

### 30.3 UBWC 解码尝试：移植了 Mesa 的布局数学，仍然解不出图

按 Mesa `fd6_tiled_memcpy.cc`（fdl6）逐条移植了布局数学：块内像素 swizzle（`get_pixel_offset`）、
块尺寸（cpp=1 ⇒ 32×8；cpp=2 且 r8g8 ⇒ 16×8）、宏瓦片 = 块×4、宏瓦片行距 = `macrotile_stride*4096`、
以及 bank swizzle 的异或掩码（模式 4/8 通道 × highest_bank_bit × bank_swizzle_levels）。
在**活的**12,591,104 现场帧上搜索了 几何 × pitch × 模式 × hbb × levels 共数百组参数，
**没有一组能解出像一张图的画面**（最优评分与次优几乎无差别）。

⇒ 判读：这些面是 **UBWC 压缩**的（像素数据不在固定的瓦片偏移上，要按元数据平面里的
per-tile 压缩模式去解码）。所以"把内容写对"需要的不是瓦片坐标，而是**元数据语义**。

### 30.4 下一轮的两条路（按期望值排序）

1. **用 GPU 写这一帧**（首选）：把相机那一侧 dmabuf 用 `EGLImage`（`EGL_LINUX_DMA_BUF_EXT`）导进来，
   用 GL 把我们的合成画面 **blit** 进去 —— UBWC 的排布与元数据由 GPU 自己写对，
   不必我们去实现压缩器/元数据；而且 GPU 写 4K 一帧是毫秒级，对"抢在 ISP 之后"也有帮助。
   要试的只是 modifier（`DRM_FORMAT_MOD_QCOM_COMPRESSED` 一类）与格式/plane 组合。
2. **移植 UBWC 元数据语义**：把 per-tile 元数据写成"未压缩/透传"模式 + 瓦片像素，
   让硬件按原样读。需要找到元数据布局的权威描述（Mesa 里若有 UBWC meta 编码器就直接移植）。

两条路都必须配一个**受控的写入节奏**：不是盲写到底（会饿死 ISP），也不是等静止（会全输），
而是"贴着 ISP 的写入速率持续覆盖"——本轮已经把这三种节奏的**可观测后果**都测出来了，
下一轮可以据此调参。

## 31. 下一轮的具体方案：用 GPU 把这一帧写对（可行性已实测，2026-09-13）

### 31.1 可行性不是猜的（本轮实测量）

在 **provider 进程内部**（不是宿主、不是相机 App）实测：

| 事实 | 实测值 |
| --- | --- |
| GPU 设备节点 | `/dev/dri/renderD128`（`crw-rw-rw-`，`graphics_device`）、`/dev/kgsl-3d0`（`crw-rw-rw-`，`gpu_device`）⇒ **全局可读写** |
| provider 进程已加载的库 | `libEGL.so`、`libEGL_adreno.so`、`libGLESv2.so`、`libGLESv2_adreno.so`、`libOpenCL.so`、`libgsl.so` |
| 相机自己的 GPU 资产 | `/data/vendor/camera/ChiNodeGPU.cl.bin` ⇒ **相机 HAL 本来就在用 GPU/OpenCL** |

⇒ 我们的 shim 不需要新增链接依赖：**EGL/GLES 的入口在进程里已经解析得到**
（`dlopen(NULL)` + `dlsym`，或 `dlsym(RTLD_DEFAULT, …)`）。

### 31.2 实施步骤（写进下一轮的 TODO）

1. `eglGetPlatformDisplay(EGL_PLATFORM_ANDROID_KHR)` → `eglInitialize` → `eglBindAPI(EGL_OPENGL_ES_API)`
   → 选 `EGL_PBUFFER_BIT` + `EGL_SURFACE_TYPE` 的 config → `eglCreateContext`（ES 3.x）。
2. 把相机那一侧的 dmabuf **导入成 EGLImage**：`eglCreateImageKHR(dpy, EGL_NO_CONTEXT,
   EGL_LINUX_DMA_BUF_EXT, NULL, attr)`，attr 里给 `EGL_DMA_BUF_PLANE0_{FD,OFFSET,PITCH,MODIFIER_LO,HI}`
   ——**modifier 是唯一的不确定项**，先试 `DRM_FORMAT_MOD_QCOM_COMPRESSED` 一族，
   配 `DRM_FORMAT_NV12`（必要时 10bit 的 P010）逐个试，记下哪个能建出来。
3. `glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, img)` → 用 `glBlitFramebuffer`
   或把我们的合成帧先上传到一张普通纹理再 blit 过去；`glFinish()` 之后写放行。
4. 计时与账目：GPU 写 4K 一帧应在毫秒级；把"建 image / blit / finish"三段耗时打进日志，
   和 ISP 的节拍对一下。**写入节奏仍按 §30.3 的三种可观测后果来调**（盲写会饿死 ISP、等静止会全输、
   贴着 ISP 速率持续覆盖才可能赢）。
5. 备选（若 EGL 路线卡在 modifier 上）：相机 HAL 自带的 OpenCL ——
   用 `cl_qcom_compressed_image` 一族扩展在 UBWC 缓冲上建 image，由驱动负责排布/压缩。

### 31.3 为什么这条路比"自己实现 UBWC 压缩器"好

- UBWC 的**压缩元数据语义没有权威公开文档**（§30.3 里把 Mesa 的瓦片数学全移植过来也解不出图，
  说明差的是元数据语义，不是瓦片坐标）；
- 而 GPU 是**这套格式的原生生产者**：它会同时写对像素、元数据、bank swizzle 与 pitch 对齐，
  我们不需要知道任何一个细节；
- 而且它顺带解决**速度**问题（CPU 逐块拼 4K 一帧要几十毫秒，GPU 是毫秒级），
  对"抢在编码器读之前把这一帧覆盖掉"是决定性的。

## 32. 录像面**确实被编码器读**，而且我们已经能改写它 —— 剩下的是标定（2026-09-13，第 11 轮）

### 32.1 结论先行

1. **编码器读的就是 12,591,104 这一类**（不再是推测）：对它盲写（20ms/轮）时，
   用 0.095 秒粒度抽 100 帧统计，**50 帧（50%）是我们写的**、50 帧是实景；
   对 `19,574,784`（`MAP_MPG_90`，19 块）同样盲写，**40 帧里 23 帧（58%）是我们的**。
   ⇒ 这两类都在录像内容的路径上，**可达性已经解决**（以前是"注了没反应"）。
2. **我们写的不是"噪声"，是能被空间解读的内容。** 判据是最直接的：同一类、同一节奏，
   换成逐字节渐变模板（`inject_pattern=1`）⇒ **40 帧全是我们没控制住**（效果为 0）；
   换回三带模板 ⇒ 58% 帧由我们主导。**内容本身决定成败**（不是时序、不是带宽）。
3. **被我们主导的帧有结构**：全分辨率看，绿帧上能看到**三条带**（帧内 486 种颜色、标准差 47）。
   ⇒ 布局关系是**存在**的：我们的字节偏移 → 画面位置是可解的。
   ⚠️ **本节对"竖直/转置"的判读已在 §33.1 更正**：当时看的是 ffmpeg **自动旋转后**的帧。

### 32.2 为什么"绿"不是失败，而是布局线索

- 均匀填充 ⇒ 整帧**平色**（绿）；三带填充 ⇒ 三条带。**如果只是"把流水线搞坏"，不会出现这种对应。**
- ~~三条带是竖直的 ⇒ 与 §24 的"镜像 + 转置"一致~~ ——**这条判读是错的**，见 §33.1：
  那是在 ffmpeg 自动旋转后的帧上看的；在**编码方向**上，变化是沿**行**的（不是列）。
- 颜色偏绿提示**平面顺序/格式与我的假设不同**：若 UV 平面在 Y 之前（或偏移不是 2/3 处），
  则我们写在开头的 `0x20`（本意是暗 luma）会被当成**色度**解读 ⇒ U/V 都低 ⇒ 绿；
  而后面 `0x80` 区被当成 luma ⇒ 中等亮度。实测的"中等亮度 + 绿"与这个假设**一致**。
  ⇒ 下一轮的第一件事就是把这个假设证伪/证实（见 §32.4）。

### 32.3 本轮排除掉的候选（都是干净的反例）

| 候选 | 结果 |
| --- | --- |
| `15,728,640`（RAW10 4096×3072，照片链路的输入，§26 证实过） | **4K 录像模式下不存在**（注入线程找到 0 块）⇒ 视频不用这个 |
| `11,796,480`（= 4096×2304×1.25，正好是 RAW10 的 16:9；`MPG_117_Buffer1/2` 双缓冲） | 静止判据（mode 2）**与**盲写（25ms/轮）**都没有任何效果** ⇒ 不是视频的 ISP 输入 |
| `12,443,648`（`MPG_139_Buffer1`，尺寸最接近 3840×2160 线性 NV12） | 整场录像只被写过一次（闲置面），注它录像像素级不变 |
| `3,194,880`（录像模式预览，线性） | 只在**预览**上可见，落盘视频与基线逐像素同级 ⇒ 录像内容不来自它 |

### 32.4 下一轮的标定方案（用"录下来的 MP4"当 oracle）

策略：**位置编码模板**。把缓冲按等分成 N 段（N=8/16/32），每段填一个**互不相同**的亮度值
（例如 0x10, 0x20, … 或 16 级台阶），其余部分填中性值；录一段 4K，然后看：

1. **段数**：画面里出现几条带 ⇒ 一段缓冲占据画面多少行/列 ⇒ 反推 stride；
2. **方向**：竖直带 ⇒ 转置（与 §24 的结论一致）；带的顺序 ⇒ 是否镜像；
3. **平面拆分**：把"中性值"放在不同的候选偏移（1/2、2/3、3/4）分别试，
   看哪一种能让画面变成**灰阶**（而不是绿）⇒ 直接确定 Y/UV 的平面边界；
4. 用同一套模板再试**10 bit（P010/TP10）**读法：如果按 8 bit 填 `0x80` 出绿、
   而按 10 bit 填"中性"出灰，则格式就是 10 bit。

标定完成后，把合成图按"缓冲偏移 → 画面位置"的映射反写进去即可——
**写入节奏沿用本轮验证过的"盲写 + 20ms 节流"**（这一档实测能拿到 50~58% 的帧）。

### 32.5 操作上必须记住的一条

**闪光灯状态会决定结论**：本轮有一次"零效果"其实是**没补光、房间全黑**（视频黑帧），
差点被当成"这一类不参与录像"。现在每次录制前都 `uiautomator dump` 核对
`闪光灯补光`，并且优先用**帧均值/帧内标准差**判读，而不是只看"看起来变了没有"。

## 33. 标定第一轮：拿到映射的"形状"，但还没拿到"规则"（2026-09-13，第 12 轮）

### 33.1 先更正一条方法论错误（它污染了 §32 的一半判读）

**ffmpeg 默认会自动旋转**：本机录的 4K MP4 带 `rotation=-90`（容器里 width/height 是 3840×2160，
显示矩阵要求转 90°）。不加 `-noautorotate` 抽帧，拿到的是 **2160×3840 的竖图**——
于是"三条带是竖直的 ⇒ 缓冲是转置的"这个判读**是错的**（§32.2 已标注更正）。

**新规矩：所有帧分析一律 `ffmpeg -noautorotate`，并在"编码方向"（3840×2160 横向）里推理。**
按编码方向重看：
- `ramp16`（16 段位置编码）：**16 条行带**（行方向极差 135，列方向仅 69）⇒ 缓冲的"段"映射到**行区间**；
- 段的取值顺序是 **162,150,133,146,164,164,165,132,115,129,136,138,137,86,89,168** ——
  **不是单调**的 ⇒ 段到行的映射被**瓦片/宏瓦片重排**过（不是简单的线性布局）。

### 33.2 这一轮试过的三种"标尺"，结果各不相同（都是有信息的反例）

| 模板 | 帧上的表现 | 判读 |
| --- | --- | --- |
| 16 段各一个亮度（1.22MB/段，全部不同） | **16 条行带**、顺序被打乱 | 段→行区间存在；顺序由瓦片重排决定 |
| 298 块 64KB 单调斜坡（均匀块，>256B 瓦片） | 行方向有变化但**非单调** | 线性读法不成立 |
| 均匀底 + 两块 1MB 标记 | 帧**近乎均匀**，只有**细密竖条纹** | 标记没有落成"局部亮块" |

细条纹的周期（对整帧绿通道做 FFT）：**57.3 / 28.4 / 9.5 像素**，即 3840 宽上 **67 / 135 / 404 条**。
`135` 正好是 `1080/8`（UV 面按 8 行一个瓦片行）——与"逐瓦片解码"的图像一致。

### 33.3 现在的判断（比上一轮更收敛）

- **可达性**：稳。这一轮的对照组里，我们主导的帧占**多数**（`均匀底+标记` 那一版几乎全程是我们，帧均值
  稳定在 (2.4,194.4,46.9)）；另一些版本是我们/实景交替。
- **可控性**：**还没到"画面级"**。证据是"两块 1MB 标记"没有变成两处亮块，而"16 段"却变成 16 条行带——
  说明我们影响的不是"像素地址"，更像**每个瓦片的描述符**（均匀区域决定"整瓦片一个颜色"）。
- 结论：要画任意图像，必须先搞清 **UBWC 描述符（元数据）的语义**，而不是继续猜 stride。

### 33.4 下一轮的定标方案（一次只动一个字节级的东西）

1. **单点扫**：模板 = 均匀底 + **仅一个 256 字节瓦片**改成另一个值。跑一次，看帧上是否只有**一处**颜色变化。
   如果是 ⇒ 我们拿到了"瓦片 → 位置"的正向映射；把该瓦片换成另一个值再跑，验证可重复。
2. **描述符步长**：把"改动点"按不同步长（256B / 4B / 16B）铺开若干次（例如 32 个点，每个不同值），
   一次录制同时给出 32 个映射点，从而直接读出**描述符所在的平面与步长**。
3. 备选仍然是 **GPU 路线**（§31）：让 GPU 直接写 UBWC（它天然会写对元数据）。
   现在的拿手证据是"我们确实能主导录下来的帧"，所以两条路都指向同一个待填的洞：**元数据语义**。

### 33.5 操作上新增的一件工具（本轮省下两次白跑）

相机 App **每次重启都会回到照片模式**，而模式条位置随当前模式平移 ⇒ 硬编码坐标必然出错。
新增 `/tmp/rec.sh <秒>`：**先 dump 核对当前模式与闪光灯，再按当前模式算坐标**，
录完还**核对确实产生了新文件**（`ls -t` 前后对比）。本轮之后所有录制都走它。

## 34. 排除"RAW 路线"；量到内容确实被渲染，但几何/格式仍未定（2026-09-13，第 13 轮）

### 34.1 一条干净的负结果（顺带纠正上一轮的一次无效实验）

上一轮有一次"注 RAW10 没效果"，本轮复查发现**那次实验本身是无效的**：相机 App 重启后回到照片模式，
我的"录制"其实在拍照，注入线程当时找到 0 块靶。这次按规矩重做（先核对模式/闪光灯、并确认产生了新文件）：

- `mock_map=15728640:/data/vendor/camera/mock_raw10.raw`（**照片链路的输入**，§26 已证实能改照片），
  在 4K 录像中盲写 20ms/轮 ⇒ 注入线程**找到 13 块靶**（这一类在视频模式下确实存在），
  但录下来的 **53 帧里 0 帧是我们主导的**（帧均值全程 161/103/86 = 实景）。
- ⇒ **照片用的那条 RAW10 平面不参与录像**。结合上一轮 `11,796,480`（RAW10 4096×2304）同样无效，
  "把传感器 RAW 换掉来驱动录像"这条路**到此关闭**。

### 34.2 内容确实被渲染（不是噪声），但被强烈变换

对 `12,591,104`（= 4096×3072 **+ 8192**，正好是"4096 行距 + 2 行填充"的传感器平面尺寸）写入**合成图像**：

| 模板 | 我们主导的帧 | 帧上的表现 |
| --- | --- | --- |
| 16 段台阶（每段前 i 个子块亮） | 30 / 53 | 行方向出现**周期 4 的台阶**（亮 87→116→121→125→128，暗 70 左右），列方向也有变化 |
| 4×4 亮度马赛克 + 亮边框 + 十字 | 35 / 53 | **顶部边框确实变成一条亮带**（行段 0 = 85.5，其余 ~44） |
| 四角各一块亮斑（四角亮度各不相同 0xFF/0xB0/0x90/0x70） | — | 帧近乎均匀（行极差 7.8、列极差 12.1），**只有左侧两角亮一点**（121/115 vs 92.8） |

⇒ 结论：**我们的字节确实进了画面**（顺序、周期、边框都能对上），但被**瓦片解码 + 色调/色彩变换**揉过，
所以"四角亮斑"这种精细结构只剩很小的局部差异，而"大块均匀区域"会变成整块颜色。

### 34.3 两条待定路线的取舍（下一轮的入口）

1. **元数据/描述符语义**（继续 CPU 写）：我们已经能主导帧，差的是"把像素排成硬件认的样子"。
   做法：单点扫（只改一个 256B 瓦片）+ 按 256B/16B/4B 步长铺 32 个改动点，一次录制读出描述符平面与步长。
2. **GPU 写这一帧**（§31）：让 GPU 按 UBWC 原生格式写，元数据天然正确；速度也是毫秒级。
   待解的唯一问题仍是"能否把 YUV/UBWC 面当渲染目标"（EGL 外部纹理通常只可采样；Vulkan 多平面 + DRM modifier 是备选）。

判断：**先做 1 的单点扫**——它便宜、可解释，而且能直接告诉我们"一个瓦片对应画面上的哪一块"；
如果单点扫证明改动是"整帧一个颜色"（即根本没有位置语义），那就说明这条路必须换成 GPU 方案。

### 34.4 本轮的工具（都留在宿主机）

- `/tmp/rec.sh <秒>`：先核对模式与闪光灯、按当前模式算坐标、录完核对确有新文件。
  （**相机 App 每次重启都会回到照片模式**，硬编码坐标已经坑过三次。）
- `/tmp/analyze.py <视频> <前缀>`：抽帧、按"与实景基线的偏差"分离出"我们主导的帧"、打印行/列剖面。

## 35. 主突破：`12,591,104` 是**线性**面（不是瓦片），而且我们能把整帧**抢下来**（2026-09-13，第 14 轮）

### 35.1 更正 §29：那一类根本不是瓦片布局

用"单块标记"做干净的对照实验（背景 0x30，一处 0xFF 标记，盲写）：

| 实验 | 结果 | 结论 |
| --- | --- | --- |
| 偏移 0 放 **2MB** 标记 | 帧的**第 0~543 行整幅变亮**，列方向完全均匀，下面全暗 | 直接映射、**行主序、无转置、无瓦片** |
| 偏移 8,294,400 放 1MB（U=255,V=0） | 帧第 **0~511 行变蓝** | 色度面起点就是 8,294,400 |
| 偏移 10,400,000 放 1MB（U=0,V=255） | 帧第 **1024~1599 行变红** | 色度行距 **4096**，与上一条独立吻合（差 4 行内） |

⇒ `12,591,104` = **Y 平面（偏移 0，行距 3840，8,294,400 = 3840×2160 正好是色度面起点）
+ 色度面（偏移 8,294,400，行距 4096）+ 尾巴**。**§29 的"录像面是瓦片（UBWC）"对本类不成立**
（那条结论仍适用于 `19,574,784` 那一类）。之前之所以看着像瓦片，是因为：
(a) 抽帧被 ffmpeg 自动旋转（§33.1），(b) 我一直按错误的平面切分去解释画面。

### 35.2 "主导了录像帧"里有一部分其实是**把流水线饿死了**

盲写会把**全部**目标每轮写一遍：24 块 × 12.59MB = **302MB/轮**。实测后果：

| 写入策略 | 录下来的帧数 |
| --- | --- |
| 盲写（302MB/轮） | 8~11 秒只有 **25~28 帧**（应约 270~330 帧） |
| `inject_sync=3` + `inject_burst=1`（**只写刚被重填的那一块，各一遍**） | 9.4 秒 **268 帧 = 28.4fps** ✅ |

⇒ 以前"50~58% 的帧由我们主导"是在**饿死 ISP** 的状态下量的（ISP 写不进去，编码器只好读我们的），
这个数字**不干净**，本轮作废重测。新开关：`inject_sync=3`、`inject_burst=<遍数>`。

### 35.3 写入时机才是真正的杠杆（本轮把"抢帧"做出来了）

机理已经看清：**ISP 从左往右把这一帧写进缓冲**。我们的写落在它写到 W% 的时刻，于是它**已经写完**的
左边 W% 留下我们的数据、右边被它后来的写覆盖 —— 实测到"左侧一条竖带"，宽度随运气在 0~30% 之间。

于是加了一个纯时序开关 `inject_delay_us`：**探测到重填之后等一段时间再写**。用"合成块占比"
（相邻像素梯度超过绝对阈值 3.0 的块比例，避开实景的模糊）当客观指标：

| 延迟 | 我们的块占比（均值 / 最佳帧） |
| --- | --- |
| 0（探测到就写） | 0.208 / 0.583 |
| 2 ms | 0.243 / 0.632 |
| 6 ms | 0.349 / **0.938** |
| 10 ms | 0.297 / **0.958** |

⇒ **能把整帧抢下来（95.8%）**，而且流水线还活着（84 帧 / 4.4 秒）。这是本轮最硬的成果：
"录像面能不能被我们说了算"这个问题，答案从"不知道"变成"**能，而且给出可调旋钮**"。

### 35.4 还差什么（下一轮的目标非常具体）

抢下来的那一帧里能看出我们那张测试卡的结构（顶部彩条带 + 方块），但：
1. **整体偏绿** ⇒ 色度面的位置/行距仍未定准（4096 与 3840 两个候选，或色度不是 NV12 交织）；
2. 画面上有**细密竖条纹**（周期约 9~57 像素，§33.2 也见过）⇒ 亮度面的行距或相位可能也差一点点。

下一轮就用**同一套客观指标**（合成块占比 + 帧内结构）做参数扫描：亮度行距 3840/3856、
色度起点 8,294,400、色度行距 3840/4096、色度交织顺序（UV / VU），把"偏绿 + 竖条纹"消掉，
就得到一张正确的 4K 模拟帧。

## 36. 布局又翻回来了（这次是**判决性**证据）；同时抓到一个真缺陷：注入会让流水线**卡死**（2026-09-13，第 15 轮）

### 36.1 判决性实验：线性布局被排除

§35.1 我据"2MB 标记 ⇒ 帧第 0~543 行变亮"判定 `12,591,104` 是线性的 —— **这个判定是错的**。
错在两套算术都能解释那个数字：

- 线性（行距 3840）：2,097,152 / 3840 = **546 行**；
- 瓦片（32×8、256B/块、120 块/行）：2,097,152 / 256 = 8192 块 = 68.27 瓦片行 = **544 行**。

544 与 546 差 2 行，落在测量误差里，**这个实验根本区分不了两者**。

于是设计了一个能区的实验：**把每个 256B 块的头 32 字节填 0xFF、其余填 0**。

- 线性布局 ⇒ 每行都会出现"32px 宽、间隔 256px"的亮段 ⇒ **行剖面必然处处相同**；
- 32×8 瓦片 ⇒ 每块只有"块内前 32 字节"亮 ⇒ 画面每 **8 行**出现一次结构。

实测行剖面：`0.04 0.04 0.04 0.04 | 0.02 0.02 0.02 0.02 | 0.00 ×8 | 0.04 …` ——
**严格的 8 行周期**。⇒ **线性被排除，这一类是瓦片布局**（§29 原来的结论是对的，§35.1 的"更正"作废）。

### 36.2 瓦片内字节序：与 Mesa 的 `pixel_offset` 吻合

把"瓦片测试"那一帧按 32×8 切块并**对所有瓦片求平均**（去噪），得到块内亮度分布：
最左 8 列明显偏亮（115 vs 100）、上两行略亮（115/110 vs 104/102）。
与 Mesa `fd6_tiled_memcpy.cc` 的 `get_pixel_offset(x,y) = (x&1)|(y&1)<<1|((x&2)>>1)<<2|((y&2)>>1)<<3|
((x&0x1c)>>2)<<4|((y&4)>>2)<<7` 所预测的"前 32 字节落在块左上 8×4"**相关系数 0.803**。
⇒ **块内序可直接用 Mesa 那套数学**（`/tmp/ubwc.py` 已经移植好）。

### 36.3 平面结构与"tiled NV12"完全自洽

- Y 面：32×8 瓦片、120×270 块 × 256B = **8,294,400**（= 3840×2160，与色度面起点实测值一致）；
- UV 面：16×8 瓦片、120×135 块 × 256B = **4,147,200**（UV 面宽 1920）；
- 尾巴：12,591,104 − 12,441,600 = **149,504**（元数据/填充）。

### 36.4 第一次按瓦片编码没有落地（块间参数还没定）

用 Mesa 公式（`mode=8, hbb=8, levels=0`）把测试卡编成瓦片写进去 ⇒ 覆盖率 **0.021**（几乎没赢）。
⇒ 块**间**的排布（宏瓦片/ bank swizzle 参数）还没对；块**内**已经对上了。

### 36.5 抓到一个真缺陷：注入会让相机流水线**卡死**

| 配置 | 录制时长 | 文件里的帧数 |
| --- | --- | --- |
| **零注入（基线）** | 9.47 s | **284 帧（30fps）** ✅ |
| 注入 + `delay=0`（round 14 的 b1） | 9.4 s | 268 帧 ✅ |
| 注入 + `delay=10ms` | 10 s（录像确实跑了 10 秒） | **只有 24 帧 = 1.13 s** ❌ |

日志里 `MediaRecorder: stop` 的时间与我的脚本一致（10 秒），也就是说**录像机跑了整 10 秒，
但编码器只吐了 24 帧就停了** —— 流水线在我们写缓冲之后**卡住**（三个不同的延迟配置里
`duration` 都恰好是 1.133378 秒，是个确定性的卡死特征，不是随机的）。

⇒ 这条有两个后果：
1. **round 14 那张"延迟 vs 覆盖率"表是在部分卡死的状态下量的**（延迟越大越容易卡），
   数字只能当趋势看，不能当性能指标；
2. 这本身是**用户可见的缺陷**：模拟出来的视频只有 1 秒 —— 在收尾之前必须解决。

**下一轮的两件事**（按优先级）：
1. **消掉卡死**：卡死与"写得太晚"相关（delay=0 不卡、10ms 卡）。候选做法：
   只在**探测到重填的下一瞬间**写一次（delay=0）+ 对同一块**最多写一次**；
   或者写之前先确认"这块的头不再变化"（即 ISP 已经写完这一帧）。
2. **补齐块间参数**：用"合成块占比"当目标函数，在 `mode ∈ {4,8}` × `hbb ∈ {8,9,10}` ×
   `levels ∈ {0..7}` 里扫；块内已经确定，扫描空间不大。

## 37. 用"宿主侧对照"钉死瓦片结论；小块结构在帧上**不落地**（2026-09-13，第 16 轮）

### 37.1 先排掉"8 行周期其实来自编码器"这个反驳

上一轮我据"行剖面 8/16 行周期"判定瓦片布局。这个推理有一个漏洞：**HEVC 的变换块也是 8 的倍数**，
周期可能来自编码器而不是布局。于是在宿主侧做了一次对照：

把同一张"每 256B 块头 32B 置亮"的图案**按线性**解释成 3840 宽的图像，
用 `libx265` 编成 4K、5 Mb/s（贴近相机录像码率）再解回来：

```
线性图案编解码后：行含量 极小 0.1250 极大 0.1250   ← 处处相同，没有任何 8 行周期
```

⇒ **编码器不会制造那个周期**；相机帧上看到的就是布局本身的性质。瓦片结论成立（§36.1 有效）。

### 37.2 单块标记：**整体内容落地、小块结构不落地**

模板：全 0，只有**偏移 0/256/512/768 四个整块**（各 256B）置 0xFF，其余 12.59MB 全 0。
这一版的 6 秒录制拿到 **166 帧**（比上一轮那种 24 帧的卡死好），抽帧结果：

| 区域 | 帧上的均值 | 判读 |
| --- | --- | --- |
| 左带（x 0~1100） | **(0, 96, 0)** | 纯绿 = 我们的 `Y=0 / UV=0` 被渲染出来的样子 ⇒ **左侧约 28% 是我们赢下的** |
| 右带（x 1300~） | (116, 119, 54) | 实景 |
| 四个亮块标记 | **完全看不见** | 1KB 的局部结构没有落到画面上 |

⇒ 结论：**"整幅/大面积内容"能落地（上一轮 2MB 标记 ⇒ 顶部 543 行变亮也是这个道理），
但 1KB 级的局部结构不落地。** 这和"每瓦片描述符决定颜色"的图像一致，也解释了为什么
测试卡那种有大色块、又有很多细节的图案看起来"半对半错"。

### 37.3 相关度指标（新工具）与它的坑

新增 `/tmp/match.py`：把帧与"我们期望的测试卡"逐列带求相关。**坑**：实景本身（上暗下亮）
与我们的卡（上亮下暗）**天然负相关**，所以"负相关"不能当成"布局反了"的证据 ——
上一轮的瓦片卡跑出 -0.55~-0.68 就是这么来的（那一帧其实是实景）。

### 37.4 下一轮：两条路各自的最短路径

1. **录像**：做一次**多标记测绘**（例如 64 块 × 4KB，每块用不同亮度分组编码），
   一次录制读出"缓冲偏移 → 画面位置"的完整映射；这一步能同时回答"块间排布"和"哪些偏移会被 ISP 覆盖"。
2. **照片**（目前 ~40%，比录像更接近"能交付"）：走已经做好的短路径 ——
   `raw_dump` 取一张真 RAW 帧 → 局部改写 → 回注。这样人眼看到的是**自然照片 + 我们的改动**，
   不依赖逆 ISP（那是长期工作）。

## 38. 照片短路径**打通**：真帧 → 改一处 → 回注 ⇒ 照片上出现我们的改动（2026-09-13，第 17 轮）

### 38.1 已经做出来的东西（端到端，有对照）

| 步骤 | 做法 | 结果 |
| --- | --- | --- |
| 1. 取真帧 | `raw_dump=15728640:/data/vendor/camera/rawlit.raw`（第 9 轮做的"只落活帧"版本） | 拿到一张**真实 RAW10**（4096×3072、值域 64~1023、均值 250） |
| 2. 改一处 | 把中央 1240×1024 区域**提亮 1.9 倍**，边缘 96px 羽化（保持场景结构 ⇒ ISP 友好） | 改动后整帧均值 250 → 268 |
| 3. 回注 | `mock_map=15728640:<改后的 RAW>` + **盲写**（`inject_us=0`） | 注入线程找到 14 块靶、每轮 220MB |
| 4. 拍照对照 | 同一场景、手电常亮，分别拍"零注入"与"注入"两张 | 注入那张上出现一块**干净的亮矩形**（见下） |

**证据（差分定位）**：把两张照片求差后按网格找"比整帧中位差高出 45 以上"的连通块 ——
整帧中位差只有 **+15.3**（两次拍摄之间的曝光漂移），而**矩形块内是 +74.3**，
位置约 **x 1785~2805、y 832~1408**（照片坐标 4096×2304）。这正是我们改的那块区域。

⇒ 人眼看到的是**自然照片**（红色物体、白色物体、木色桌面），改动处是我们加的。
这就是 §26 当时写下的"短路径"，现在从工具到结果都齐了。

### 38.2 这一轮最重要的"机理"发现：照片必须**盲写**，视频不能盲写

同一个写入策略在两边的结论**正好相反**，而原因都量出来了：

| 场景 | 写入策略 | 结果 |
| --- | --- | --- |
| **照片** | `inject_sync=3` + `inject_burst=4`（按重填成簇写） | 照片变成**残缺 JPEG**（PIL 直接报 broken data stream，无 EOI） |
| **照片** | **盲写**（`inject_us=0`，持续覆盖） | ✅ 正常 JPEG，改动可见 |
| **视频** | 盲写（302MB/轮） | 流水线被饿死：8~11 秒只录到 25~28 帧 |
| **视频** | `inject_sync=3` + `burst=1` | ✅ 28.4fps，且能抢帧 |

机理：**成簇写会让 ISP 读到"半新半旧"的 RAW（撕裂）⇒ 编码器直接失败**；
盲写让缓冲**始终**是完整的我们那一帧 ⇒ 编码正常。而视频那边相反：盲写的总写入量（302MB/轮）
会把 ISP 饿死，必须"只写刚重填的那块"。

⇒ 结论：**"照片用盲写、视频用成簇写"** 是两条链路的正确策略，不是随便调参。
（上一轮把"残缺 JPEG"归因于"内容太极端"是错的 —— 这一轮用一张**温和的提亮**同样复现了它，
真正的原因是撕裂。）

### 38.3 遗留：RAW → 照片的几何还没标定

我改的是 RAW 的 x 1448~2688 / y 1024~2048，出现在照片上的却是 x≈1785~2805 / y≈832~1408
（横向约 0.82×、纵向约 0.56× 且带偏移）⇒ 照片不是 RAW 的"垂直居中裁剪"那么简单。
要**精确摆位**（例如把某个内容放到指定位置）还需要一次几何标定：
在 RAW 上放几个已知位置的小方块，拍一张，读出它们在照片上的位置即可反推变换。

## 39. 照片链路的可靠性实测 + 几何标定为什么难（2026-09-13，第 18 轮）

### 39.1 残缺 JPEG 是**概率性**的，不是必然

同一配置（盲写 + `rawlit.raw` 的改版）连拍三次：

| 次 | 结果 |
| --- | --- |
| 1 | （九个亮块那一版）残缺 JPEG |
| 2 | ✅ 正常（2304×4096） |
| 3 | ✅ 正常 |

另一轮用"变暗"标记连拍三次：**3/3 全部正常**。

⇒ 判读：镜像/撕裂窗口是**随机命中**的（我们的 memcpy 15.7MB 期间若被 ISP 读到，就出残缺 JPEG）。
所以正确做法不是"换策略"，而是**拍完校验 + 重试**：
`PIL.open().load()` 抛 `broken data stream` 就是这一张废了，重拍一次即可。
（这也解释了为什么"同一条策略有时行有时不行"——之前把它当成内容问题，是把概率当成了因果。）

### 39.2 几何标定：方法本身被"检测器"卡住

试了两种标定模板，都在**检测**这一步失败，而不是在映射这一步：

1. **亮块（×1.6~2.6）×9 网格**：检测器按"品红偏高"去找，结果抓到了**场景里那个红色物体**
   （它在 `R+B-2G` 上和我们的亮块同号）。对 9 个点做仿射拟合，平均残差 **233 像素**、
   角点处最大 —— 说明"检测到的点"根本不是我们的方块，这个拟合没有意义。
2. **暗块（×0.12）×8 网格**：检测到三个可信的暗块（照片 x≈224、y≈608/2048/3488），
   彼此 **y 间距 1440**，而它们在 RAW 上的 y 间距是 **936** ⇒ 纵向尺度约 **1.54**；
   但同一列（RAW x 都是 600）的横坐标都是 224 ⇒ 横向尺度约 0.55。
   **纵横尺度差 2.8 倍**在照片上不可能，说明这一列"暗块"里混进了场景自身的暗区。

⇒ 结论：**要标定几何，必须先拿到"同一场景、同一曝光、开/关注入两张"的对照**，
用**差分**去定位方块，而不是靠颜色/亮度特征在单张图上猜。现在缺的正是"同一会话内切换注入"的手段。

### 39.3 下一轮：加一个"注入自动停止"开关，然后做干净的同会话 A/B

计划：加 `inject_stop_after=<秒>`（注入线程跑够时间自行退出），于是可以：
1. 起 provider（注入开）→ 拍一张（有我们标记）；
2. 等注入线程自行停止 → 在同一会话里再拍一张（纯实景，曝光/场景不变）；
3. 两张求差 ⇒ 标记的**精确**位置 ⇒ 拟合出 RAW→照片 的真实变换
   （含镜头畸变校正/EIS 的非线性项：用 8~9 个点拟合径向多项式，而不是仿射）。

这条闭环做完，"改动放到指定位置"就成了常规操作；否则现在只能"改动了但落点靠运气"。

## 40. 同会话 A/B 拿到了；RAW→照片 是"转置 + 镜像"，而且**对称模板测不出镜像**（2026-09-13，第 19 轮）

### 40.1 新开关 `inject_stop_after=<秒>`：注入线程自行退出

为了让几何标定拿到**同一会话、同一曝光**的对照，给注入线程加了"跑够 N 秒就退出"。
实测（日志）：`注入线程：已按 inject_stop_after=35 秒退出（此后相机输出不再被我们改写）`。
于是流程变成：起 provider（注入开）→ 拍 A → 等线程自己停 → **同一会话**再拍 B → 求差。

效果立竿见影：A/B 差分的整帧中位差从之前的 **+15.3**（跨会话）掉到可忽略，
**9 个标记方块全部干净检出**（每个面积 8~9 万像素、位置规整的 3×3 网格）。

### 40.2 标定结果：转置 + 单位尺度（`photo_y = raw_x - 10`）

用 3×3 网格（RAW x∈{600,2048,3500}、y∈{600,1536,2470}）在照片（2304×4096 竖幅）上定位：

- 照片横向间距 **938** ≈ RAW 的 **纵向**间距 935；照片纵向间距 **1459** ≈ RAW 的**横向**间距 1450
  ⇒ 两个轴**互换了** ⇒ 这是**转置**（90° 旋转），不是纵横拉伸（一度被 0.65/1.56 的假拟合误导）。
- 拟合：`photo_x ≈ 1.0028*raw_y - 388.7`、`photo_y ≈ 1.0056*raw_x - 9.6`，尺度都 ≈ **1.0**。
  照片 = RAW 转置后**纵向裁掉 768 列**（3072→2304，居中）的结果。

### 40.3 验证暴露了方法论错误：**对称模板测不出镜像**

用上面的逆变换把 3 个标记放到"照片的指定位置"（中心、左上 (300,300)、右下 (2000,3800)），
同一会话 A/B 后再检测：

| 目标 | 实测 | 偏差 |
| --- | --- | --- |
| 照片中心 (1152,2048) | (1150.4, 2047.9) | **2 像素** ✅ |
| 左上 (300,300) | (1930, 257) | 1631 像素 ❌ |
| 右下 (2000,3800) | (283, 3830) | 1717 像素 ❌ |

两个偏心点**在横向上被镜像**了（2304−300≈2004 与 1930 接近；2304−2000≈304 与 283 接近），
而中心点在任何镜像下都不动 —— **3×3 网格是对称的，镜像前后完全一样，所以拟合根本看不出这个翻转**。

⇒ 教训（写下来）：**标定必须用非对称图案**（例如四角标记做成不同大小、或加一个偏置的十字），
否则"对称的模板 + 对称的误差"会让镜像悄悄溜过去；**而且标定完必须做一次独立验证**
（这次正是验证把错误抓出来的）。

### 40.4 下一轮

把标定模板换成**非对称**（4 角标记尺寸各异 + 一个偏心十字），重跑 40.1 的 A/B 流程，
用 8~12 个点最小二乘拟合 `photo = M·raw + t`（允许镜像），再用**另一组**目标位置做验证。
做完这一步，"把改动放到照片上指定位置"就是常规操作。

## 41. 照片侧的落点标定**完成**：RAW→照片 是"90° 旋转 + 尺度 1.02"（2026-09-13，第 20 轮）

### 41.1 非对称模板 + 同会话 A/B

用 `inject_stop_after` 做同会话 A/B，模板换成**6 个非对称位置**的标记
（RAW (500,400) (2500,800) (1000,1500) (3300,1400) (1700,2500) (3600,2200)），
差分后 6 个块全部干净检出（面积 7.7~10 万像素）。

### 41.2 拟合结果（6 点最小二乘，直接给结论）

```
photo_x = +0.0095*raw_x - 1.0016*raw_y + 2657.8
photo_y = +1.0218*raw_x + 0.0002*raw_y -  44.9
⇒ 线性部分 ≈ [[0, -1.0016], [1.0218, 0]]，行列式 +1.02 ⇒ 这是 **90° 旋转**（不是镜像）
```
拟合残差：**平均 14.5、最大 24.7 像素**（在 2304×4096 的照片上，约 0.6%）。
逆变换（**把内容放到照片 (X,Y)**）：

```
raw_x = (Y + 44.9) / 1.0218
raw_y = (2657.8 - X) / 1.0016
```

### 41.3 独立验证（用**另一组**目标位置，不看拟合残差）

| 放样目标（照片坐标） | 实测 | 偏差 |
| --- | --- | --- |
| 中心 (1152, 2048) | (1160.9, 2047.3) | **9 像素** |
| 左下 (400, 3600) | (392.3, 3582.6) | **19 像素** |
| 右上 (1900, 700) | (1922.5, 716.1) | **28 像素** |

⇒ **"把改动放到照片上的指定位置"这件事成立了**（误差 <1% 画幅，对"改一处/贴一张图"完全够用）。
上一轮那个"横向镜像"其实不是镜像，而是**旋转方向**：对称网格在 90° 旋转下仍是自身，
所以拟合定不出方向 —— 换非对称模板后一次就定下来了，且独立验证也对上了。

### 41.4 照片这条线的状态（可以交付了吗）

- ✅ 系统层改照片内容（不注入 App）：真帧 → 改一处 → 回注 → 落盘照片出现我们的改动；
- ✅ 可靠性：残缺 JPEG 是概率性的（约 1/3），**拍完校验 + 重拍**即解决；
- ✅ **落点可精确控制**（本节）；
- ⛔ 仍然做不到的：**凭空生成一张自然照片**（需要逆 ISP：黑电平/CFA/增益/CCM/tone），
  这是长期项；现在的语义是"**在真实照片上做我们的改动**"。

### 41.5 复现命令（最短路径）

```sh
# 1) 取真帧（现场活帧）
sh ace5_deploy4.sh '…;raw_dump=15728640:/data/vendor/camera/real.raw'
# 2) 改一处（宿主机 rawedit.py：带羽化的方块，暗化/提亮倍数可选）
python3 rawedit.py real.raw mod.raw "2048,1523,0.10"     # 把 (2048,1523) 改暗 ⇒ 落在照片中心
# 3) 回注（照片必须**盲写**；成簇写会撕裂 ⇒ 残缺 JPEG）
sh ace5_deploy4.sh '…;mock_map=15728640:/data/vendor/camera/mod.raw;inject_us=0'
# 4) 拍完用 PIL 校验能解码，不能就重拍
```

## 42. 持久化：把运行期部署固化进模块（ACE5 走 /odm，**永不碰 /vendor**）（2026-09-13，第 21 轮）

### 42.1 一个关键发现：`/odm/lib64/hw` 上**本来没有** `camera.qcom.so`

卸掉运行期 overlay 之后看原始状态：`/odm/lib64/hw/` 里只有 `camera.oemlayer.so` 等，
**没有** `camera.qcom.so`；真件在 `/vendor/lib64/hw/camera.qcom.so`（36 MB）。

那为什么我们的 shim 放进 `/odm/lib64/hw/` 就会被加载？因为 **HAL 加载器按目录顺序找**
（`/odm/lib64/hw` 在 `/vendor/lib64/hw` 之前）。于是这条路线**只是"往前面的目录里加一个文件"**，
`/vendor` 一个字节都不用动 —— 这正是它既生效、又不会像 §25.1 那样把 `media.swcodec` 弄坏的原因。

### 42.2 模块新增的 ACE5 路线（`post-fs-data.sh`）

1. 探测 `payload/odm/camera.qcom.so` 是否随包发下来；探到了就 **`SKIP_VENDOR=1`，整段不动 `/vendor`**
   （老脚本那条 bind 路线在 ACE5 上是多余的，而且属于"没量过的风险路径"，直接跳过）；
2. 挂 tmpfs 到 `/data/local/tmp/odmtmp` → **再**建 `upper/`、`work/`（顺序见 42.3）；
3. shim 拷进 `upper/`，`mount -t overlay` 叠到 `/odm/lib64/hw`；
4. 开关文件写 **`/data/vendor/camera/portal.cfg`**（`vendor_camera_data_file`，无需任何 sepolicy 补丁）；
   先 `rm` 再建（`: >` 会留旧标签 —— 踩过），`chmod 666` + `chcon`；
5. `payload/odm/mock/` 里随包的素材搬到 `/data/vendor/camera/` 并打同标签，cfg 里可直接引用。

随包发的默认 `portal.cfg` = 我们一直用的"干净态"（只装钩子、不开注入）：

```
real_hal=/vendor/lib64/hw/camera.qcom.so
real_hmi_bytes=376
ops_index=1
dump=1
report_period=20
req_vaddrs=0x1e49ca0
hook_rec_vaddrs=0x1e46f60
hook_rec_ol_vaddrs=0x6cadc
```

### 42.3 写这条路线时踩到的三个坑（都已修，脚本里留了注释）

| 坑 | 症状 | 修法 |
| --- | --- | --- |
| 判据写成"目标文件存在" | `[ -s /odm/lib64/hw/camera.qcom.so ]` **永远为假**（那正是我们要加的文件，本来不存在）⇒ 整段不执行 | 判 **目录在** + "我们有载荷" |
| **先建 upper/work 再挂 tmpfs** | 目录被新挂的 tmpfs 盖住 ⇒ `cp ... upper/camera.qcom.so: No such file or directory`，overlay 挂不上 | **先挂 tmpfs，再 mkdir** |
| 老脚本的 `/vendor` bind 路线在 ACE5 上也会跑 | 属于未测量的风险路径（§25.1 那条 overlay 就是在这里把拍照弄坏的） | 探到 `/odm` 载荷即 `SKIP_VENDOR=1` |

### 42.4 验证（不用重启，手动跑模块脚本）

把打好的 zip 解到 `/data/local/tmp/modtest`，**先卸掉运行期部署**，再 `sh post-fs-data.sh`：

```
portalex: 检测到 /odm 路线可用 ⇒ 本次**不碰 /vendor**
portalex: 已在 /odm/lib64/hw 叠上我们的 shim（/vendor 未被触碰）
portalex: 开关文件 = /data/vendor/camera/portal.cfg
```
核对：`/odm/lib64/hw/camera.qcom.so` = 232,800 字节（我们的 shim）、
`/data/vendor/camera/portal.cfg` 内容正确且标签 `vendor_camera_data_file`、
`mount` 里能看到 tmpfs + overlay 两条。随后 `setprop ctl.start vendor.camera-provider`：

```
PortalCamShim: 开关文件：/data/vendor/camera/portal.cfg
PortalCamShim: 真实 HAL 已加载并整块接管：/vendor/lib64/hw/camera.qcom.so（api=0x0205）
PortalCamShim: 已挂钩[0]/[1]/[2] …
```
**并且拍照仍然正常**（落盘 2304×4096 JPEG 可解码、无新增 tombstone）⇒ 这条路线不碰 swcodec。

### 42.5 还差最后一步（需要用户点头）

模块当前在设备上是**已安装但被禁用**（`/data/adb/modules/portalex-camera-hal/disable` 存在）。
要让它开机自动部署，需要：把新 zip 装进 KernelSU（或把新文件覆盖到已安装目录）→ **重启一次**。
重启是设备级动作、且有"开机后相机被脚本弄坏"的小概率风险（脚本已实测通过，但没在冷启动路径上跑过），
所以这一步留给用户决定，不代劳。

## 43. 录像：**画面的结构进去了**（瓦片几何找对了一大半）（2026-09-13，第 22 轮）

### 43.1 先修工具：瓦片生成器原来是"越界的双射"

用 Mesa 公式生成"像素 → 瓦片偏移"映射时先自查了一遍：**它是双射**（8,294,400 个像素 →
8,294,400 个互不相同的偏移）✅，但**最大偏移是 8,355,327 > 8,294,400** ❌ ——
因为宏瓦片是 4 个瓦片高（32 行），而 2160 行 = 270 瓦片行 = **67.5** 个宏瓦片行，
最后那半个宏瓦片行仍然按"整块"寻址 ⇒ **Y 面实际要 68 个宏瓦片行 = 8,355,840 字节**。

⇒ 之前我一直把色度面放在 8,294,400（那是"线性读法"下的数字），**差了 61,440 字节**。

### 43.2 按修正后的几何编码 ⇒ 我们的测试卡结构出现在录像里

模板 = 按 Mesa 公式瓦片化的测试卡（彩条带 + 灰带 + 白块），写入 Y 面；其余部分填中性 128。

| 模板写法 | "列带相关 >0.5"的帧 | 最佳相关 |
| --- | --- | --- |
| 色度面写在 8,355,840（修正后） | 1 / 23 | +0.672 |
| **Y 瓦片化 + 其后全部填中性** | **21 / 30** | **+0.790** |

而且画面上一眼能认出我们的卡：**顶部 8 段彩条带、下面一条灰带、中右一个亮块** ✓
（背景是绿的，见 43.3）。

### 43.3 现在的图像：结构对、颜色不对、还有竖条纹

- **结构对**：彩条/灰带/方块的位置与我们的模板一致（这就是 21/30 帧高相关的原因）；
- **颜色不对**：整幅偏绿 —— 这与照片链路**同一个机理**：ISP 的 3A（AWB/CCM）是按真实场景调的，
  我们塞进去的合成内容没有对应的 3A 参数 ⇒ ISP 给出一个它自己的颜色解释（照片那次是品红/绿）。
  **不是"平面放错了"**（平面放错会连结构都没有）；
- **竖条纹**：瓦片级的细密条纹（周期 ~32px 及其谐波），说明块内/块间的**精确**参数还没完全对上
  （块内已按 Mesa 的 `get_pixel_offset`，块间还有宏瓦片/bank swizzle 的细节）。

### 43.4 录像这条线的诚实结论

| 能力 | 状态 |
| --- | --- |
| 接管录像帧（让编码器读我们写的内容） | ✅ 实测最高 95.8% 的帧 |
| 让**我们的图像结构**出现在录像里 | ✅ 本轮（21/30 帧结构相关 >0.5） |
| 让**颜色**也正确 | ⛔ 受 ISP 3A 限制（与照片链路同类问题） |
| 消掉瓦片级竖条纹（精确布局） | ⚠️ 未完成（块内已对，块间待定） |
| 长时间录制不卡死 | ⚠️ 部分（`delay=0` 不卡、`delay=10ms` 会卡；需按"赢帧 vs 卡死"折中调参） |

⇒ 录像不再是"够不着"，而是"**能改、结构能进去**，剩下的是颜色（ISP 决定）与精确布局的细活"。

## 44. 阶段总交接：做到了什么、怎么复现、卡在哪（2026-09-13，第 23 轮）

### 44.1 能力矩阵（只写有实测证据的）

| 能力 | 状态 | 证据 / 出处 |
| --- | --- | --- |
| 系统层接管摄像头输出缓冲（**对目标 App 零注入**） | ✅ | 预览整幅变成我们填的内容；相机 UI 不受影响（§23~§24） |
| 照片模式预览 = 任意图像、几何正确 | ✅ | 测试卡方向/文字/配色都对（§24、§28.2） |
| 录像模式预览（1920×1080 那一路） | ✅ | 视频模式预览显示我们的测试卡（§28.1） |
| **照片内容可改**（真帧 → 改一处 → 回注） | ✅ | 落盘照片出现我们的亮块；差分定位 +74 vs 整帧漂移 +15（§38） |
| 照片改动的**落点可精确控制** | ✅ | RAW→照片 是 90° 旋转 + 尺度 1.02；独立验证 9/19/28 像素（§41） |
| 照片链路的可靠性做法 | ✅ | 残缺 JPEG 是概率性的（~1/3）⇒ 拍完校验 + 重拍（§39.1） |
| 录像：**让编码器读我们写的内容** | ✅ | 最高 95.8% 的帧由我们主导（§35.3） |
| 录像：**我们的图像结构进入录像** | ✅ | 修正几何后 21/30 帧与我们的卡结构相关 >0.5（最佳 +0.790，§43.2） |
| 部署持久化（模块脚本） | ✅（手册验证） | 手册跑 `post-fs-data.sh` ⇒ 从 /odm 加载 shim、三钩子装上、拍照正常（§42.4） |
| 照片：**凭空生成一张自然照片** | ⛔ | 需要逆 ISP（黑电平/CFA/增益/CCM/tone），长期项（§26、§41.4） |
| 录像：**颜色正确** | ⛔ | 受 ISP 3A 限制（合成内容的颜色由 ISP 解释，§43.3） |
| 录像：消掉瓦片级竖条纹（精确布局） | ⚠️ | 块内已对（Mesa `pixel_offset`），**块间**（宏瓦片/bank swizzle）待定（§43.3） |
| 录像：长录不卡死 | ⚠️ | `delay=0` 不卡但赢帧少；`delay=10ms` 赢帧多但会卡（§36.5） |
| 冷启动持久化（开机自动部署） | ⚠️ | 脚本与打包就绪，**尚未在冷启动路径上跑过**，且模块当前是禁用状态（§42.5） |

### 44.2 复现命令（最短路径）

```sh
# A. 部署（运行期；只叠 /odm，**永不碰 /vendor**）
adb push camera-hal/build/.../camshim.so /data/local/tmp/camshim.new.so
adb shell "sh /data/local/tmp/ace5_deploy4.sh 'real_hal=/vendor/lib64/hw/camera.qcom.so;\
real_hmi_bytes=376;ops_index=1;dump=1;report_period=20;req_vaddrs=0x1e49ca0;\
hook_rec_vaddrs=0x1e46f60;hook_rec_ol_vaddrs=0x6cadc'"

# B. 预览模拟（照片模式；盲写，作用域只到 4,038,656 这一类）
#    mock_map=4038656:<NV12 素材>;inject_us=0     素材需 F=desired.transpose(FLIP_LEFT_RIGHT).transpose(TRANSPOSE)

# C. 照片改内容（真帧 → 改一处 → 回注；**必须盲写**）
adb shell setprop ctl.stop vendor.camera-provider   # 带 raw_dump 的 cfg 起一次，取真帧
#   host: python3 rawedit.py real.raw mod.raw "2048,1523,0.10"   # ⇒ 落在照片中心
#   再以 mock_map=15728640:<mod.raw>;inject_us=0 起 provider，拍照
#   拍完 PIL 校验能解码，不能就重拍

# D. 录像结构模拟（成簇写、**不要盲写**）
#   mock_map=12591104:<瓦片化素材>;inject_sync=3;inject_burst=1;inject_delay_us=10000

# E. 回滚（任何一条都不残留）
adb shell "umount /odm/lib64/hw; umount /data/local/tmp/odmtmp; setprop ctl.start vendor.camera-provider"
```
标签与路径的坑：cfg 放 `/data/vendor/camera/portal.cfg`（`vendor_camera_data_file`，**零 sepolicy 补丁**）、
建文件必须 `rm` 再写（`: >` 会留旧标签）、素材同样要 `chcon vendor_camera_data_file`。

### 44.3 卡点（都定位到了具体位置，不是"再试试"）

1. **录像块间布局**：块内已确定（Mesa `get_pixel_offset`，瓦片测试相关 0.80），块间（宏瓦片 4×4 瓦片、
   bank swizzle 的 `highest_bank_bit`/`levels`）还没定；试过 Mesa mode4/8 + levels0，以及"简单行主序瓦片"，
   都没有把 32px 级竖条纹消掉。下一步可用**多标记测绘**（64 块 × 4KB、按亮度分组）一次读出块间映射。
2. **录像颜色**：ISP 3A 对合成内容的解释（同样影响照片链路）——要"颜色也对"就需要逆 3A/逆 ISP。
3. **卡死 vs 赢帧的折中**：`inject_delay_us` 是单一旋钮，晚写赢面大但会卡；可以试"写成对半 + 逐块确认"。
4. **照片逆 ISP**：长期项。
5. **模块冷启动**：脚本已手册验证，需要在冷启动路径上跑一次（要重启设备）。

### 44.4 需要用户点头的两件事

- **装模块 + 重启**：把 `camera-hal/build/portalex-camera-hal-pineapple.zip` 装进 KernelSU（或覆盖已安装目录）
  再重启一次，才能验证"开机自动部署"。模块当前是禁用状态，且这台设备的冷启动路径还没跑过这个脚本。
- **是否继续啃录像的块间布局**：这条最费轮次（读数依赖录制 + 差分），收益是"录像颜色/几何都对"。

## 45. 收尾验证：一次"照相机看不到的画面"的照片（2026-09-13，第 24 轮）

### 45.1 为什么这一次的验证是决定性的

前置状态：**手电关闭、现场是暗的**（预览区均值 RGB ≈ 5.5），而我们要注入的 RAW 是几分钟前
**开着补光**拍下的那一帧。于是：

| 项 | 值 |
| --- | --- |
| 拍照时相机**实际**能看到的东西 | 暗（均值 ~5） |
| 落盘照片的均值 | **(179.6, 131.9, 119.1) —— 亮** |
| 照片内容 | 补光下的红/白物体（= 注入的那一帧），**不是**拍照时的现场 |
| 我们加的改动 | 正中央一个**黑色方块**（RAW (2048,1523) 暗化 ×0.08） |
| 落点 | **正好在照片中心**（= §41 标定预测的位置） |

⇒ 一次验证同时钉死三件事：**照片内容由我们决定**、**内容可以不是现场**、
**落点按标定精确命中**。而这一切发生在**模块脚本部署出来的干净状态**上（`/odm` overlay + 干净 cfg），
即"交付形态"而不是"实验室形态"。

### 45.2 收尾时的状态

- 设备：provider 运行中，cfg = 干净态（只装钩子、不开注入），`/odm/lib64/hw` 上是模块脚本部署的 shim；
- 无新增 tombstone（最新仍是 02:58 那批）；
- 仓库：`docs/camera-mock.md` 到 §45；本文件的 §44 是总交接（能力矩阵 + 逐项复现命令 + 卡点）。

### 45.3 目标完成度裁决（诚实版）

**未完成**，因此目标继续留在 active。差距是三条具体的东西，不是"还差一点感觉"：

1. **录像**：能接管帧（95.8%）、能让**结构**进去（21/30 帧相关 >0.5），但
   ①块间布局（宏瓦片 + bank swizzle）未定 ⇒ 32px 级竖条纹；
   ②颜色由 ISP 3A 决定 ⇒ 偏绿；③长录与赢帧之间要折中。
2. **照片**：能"在真帧上做我们的改动并精确落点"，但**不能凭空生成一张自然照片**（需逆 ISP）。
3. **持久化**：模块脚本手册验证通过，但**冷启动路径未验证**（需要装包 + 重启一次）。

这三条都已经定位到具体位置，并各自写明了下一步做法（见 §44.3）。

## 46. 收尾轮的决定性测量：视频瓦片布局**验证通过**（2026-09-13，第 25 轮实测）

### 46.1 用"行标尺"把布局照出来

思路：给 Y 面的每个瓦片行一个 **16 级循环灰度**（按"图像行 → 瓦片行"的关系填），
然后看录下来的画面里这个循环**在不在、周期对不对**。两种填法对照：

| 填法 | 画面上的周期 | 与理想循环的相关 |
| --- | --- | --- |
| 把缓冲**连续 30,720 字节**当作一个"瓦片行" | **周期 32 行（4 块）** ⇒ 错的 | 0.37 |
| 用 **Mesa 公式**把像素送到它的瓦片偏移（宏瓦片感知） | **周期 128 行（16 块）** ✅ | **+0.80** |

第二种的自相关：**lag16 = +0.84、lag32 = +0.85**（其余 lag 明显更低）——
也就是"我们铺的 16 级循环"原样出现在画面里。

### 46.2 这条测量的含义

1. **视频面的瓦片几何是对的**：`32×8 瓦片 / 120 块每行 / 宏瓦片 4×4 瓦片 /
   列主序宏瓦片寻址`（Mesa `fd6_tiled_memcpy.cc` 的 `plane_offsets`，参数 mode=8、hbb=8、levels=0）
   与硬件一致；Y 面大小 **8,355,840**（68 个宏瓦片行，最后半行仍整块寻址）。
2. 顺带解释了 46.1 里第一种填法为什么错：**一个"瓦片行"在缓冲里不是连续的 30,720 字节** ——
   宏瓦片行里 4 个瓦片行是**交错**放的，所以连续块会跨到别的图像行去（画面就变成周期 4 的乱序）。
3. 于是"录像还差什么"也被定死了：**不是几何**，而是
   ①**颜色**（ISP 3A 对合成内容的解释 ⇒ 偏绿，与照片链路同机理）；
   ②细腻的 32px 级竖条纹（合成内容在 ISP/编码器里的产物，不是布局错位）。

### 46.3 这一轮的净收益

- 录像线的**几何问题结案**：布局参数由"推测"变成"实测验证"（周期 + 自相关 + 相关三重证据）。
- 下一轮（若继续）只需要对付**颜色**：可以走"给合成内容配一套近似 3A 参数"或"测量 ISP 的
  YUV→YUV 变换并做逆变换"两条路，不再是猜布局。

---

## 47. 前置摄像头（HAL id 1）：布局标定与 100% 录像接管

本轮把工作对象从后置换到**前置**，并把前置的预览/录像/照片三条链路的缓冲布局逐个测准。
结论先说：**前置录像已经是完全接管（100% 帧、逐帧纯净）**；前置照片沿用"真帧改一处"的短路径可用；
前置**取景器**存在一个应用侧/显示侧的径向调制缺陷（见 47.5）。

### 47.1 前置 = HAL id 1，而且它和"后置"在同一套钩子上

`shim_open` 与 `dumpsys media.camera` 对齐后的对应关系（实测）：

| 打开的 HAL id | `dumpsys media.camera` | 含义 | 照片 RAW 流 |
| --- | --- | --- | --- |
| 1 | Device 1 | **前置** | `2304x1728` format 37（缓冲 **4,976,640** = 2880×1728，**无填充**） |
| 2 | Device 2 | 后置主摄 | `4096x3072` format 37（15,728,640） |

- 两种相机的**预览流尺寸完全一样**（`2136x1200` format 35、缓冲同为 **4,038,656**），
  所以 `mock_map` 用尺寸区分时它们会同时命中 —— 需要只打一个时用 `cam=1` / `cam=2`。
  ⚠️ 这条**当时并不成立**：注入线程原先不认 `cam=`、只按尺寸挑靶（App 在后置时照样被写），
  已在 **§48.4** 修好并做了前后验证。
- **踩过的坑**：相机的"上次用的是哪一颗"并不总是被 App 记住；每次重启 provider 之后，
  App 常常回到后置。判断当前到底在哪一颗，**只认日志**（`shim_open id=` / `dumpsys media.camera`），
  不要凭截图猜 —— 本轮有 5 次标定跑在"以为是前置、其实是后置"的会话里，
  导致把后置预览的布局当成了前置的，白绕一大圈。

### 47.2 三个目标面的实测布局（**后置预览 ≠ 前置预览**）

| 目标面 | 缓冲总长 | Y 面 | 色度面 | 色序 |
| --- | --- | --- | --- | --- |
| 后置预览 | 4,038,656 | @0，行距 2176，**1200 行** | @**2,611,200**，行距 2176，600 行 | **NV21**（V 在前） |
| 前置预览 | 4,038,656 | @0，行距 2176，**1256 行**（1200 有效 + 56 全零填充） | @**2,733,056** = 2176×1256 | NV21 |
| 前置录像 | 3,194,880 | @0，行距 1920，**1088 行**（1080 有效 + 8 填充） | @**2,088,960** = 1920×1088，544 行 | NV21 |

判定依据（都不是推测）：

- **前置预览 Y 面 1256 行**：真帧落盘（`raw_dump`，前置会话）里 **行 1200…1255 整行全零**，
  而 `2176×1256 = 2,733,056` 处开始是均值 124 的色度数据。
- **后置预览 1200 行**：同一手法 —— 用 `probe.raw` 把 `[2,611,200, 2,733,056)` 这段
  （在"前置布局"里是 Y 填充区）写成 `U=90,V=200`，**屏幕上真的出现了那块色** ⇒
  显示侧的后置色度面起点就是 2,611,200。
- **色序 NV21**：同一支探针把 `(90,200)` 写成"U 在前"时，画面里的红↔蓝对调
  （写的红三角显示成蓝、黄圆显示成青）⇒ 两个前置面都是 **V 在前**。
- **前置录像 1088 行**：真帧落盘后按 1920 行距解出来是**干净利落的 1080p 实景**，
  但色度面若取 2,073,600 会让顶部 16 行变成"纯绿条"；取 **2,088,960** 后整幅自然。

### 47.3 显示/录制变换（缓冲 → 画面）

- **后置预览**：`画面X = 1260.5 − 1.0524·缓冲y`、`画面Y = 351.9 + 1.04·缓冲x`
  （等价于"顺时针转 90°"）。可见区屏幕 y ∈ [352, 2573]，**之外作画会被裁掉**。
  这组常数是用"8 级灰度带 + 对角亮线"的探针实测标定的（带边界落在 158/316/…/1106，每档 158px）。
- **前置录像**：`画面 = fliplr(rot90(缓冲, +1))`，即"逆时针转 90° 再左右镜像"。
  反解 ⇒ **缓冲 = rot90(fliplr(设计稿), 3)**。
  验证：按此写的素材录出来 44 帧全部与设计稿 NCC ≥ 0.9（均值 **0.997**），
  与镜像稿 NCC 只有 0.369。
- **前置预览**：与后置预览同一组常数（`rot90cw` 那一套），但受 47.5 的径向调制影响。

### 47.4 写入策略：**前置用盲写就够了，而且最好**

在**前置 1080p 录像**上把四种策略跑了一遍（每轮录 9 秒、抽 5fps 采样判"这帧是不是我们的"）：

| 策略 | 接管率 | 纯净帧（NCC>0.9） | 平均坏点比例 | 结论 |
| --- | --- | --- | --- | --- |
| `inject_sync=3; inject_burst=1; inject_delay_us=10000` | 2.2%（另一次 10%） | 7% | 0.21 | 最差 |
| `inject_sync=3; inject_burst=1; inject_delay_us=0` | 86.7% | 7% | 0.21 | 接管高但**撕裂** |
| `inject_sync=2; inject_burst=3` | 100% | 40% | 0.017 | 好，仍有轻微撕裂 |
| **`inject=1; inject_us=0`（盲写）** | **100%** | **100%** | **0.000** | **本轮最优** |

原因不神秘：前置录像只有 **10 块 × 3.19MB ≈ 32MB/轮**，盲写循环完全追得上 30fps，
于是编码器**每一次**读到的都是我们写好的整帧；后置 4K 是 **34 块 × 12.6MB ≈ 428MB/轮**，
盲写会把 ISP 饿死（§35），所以才必须上"成簇/静默"策略。
**同一份代码，两个面要用相反的策略**——这条别再想当然。

### 47.5 前置取景器的径向（圆盘）调制 —— **归因见 §48：这是 App 自己的渲染，不是注入缺陷**

> ⚠️ **本节结论已在 §48 改判**：圆盘在**不注入**的实景取景器上同样存在，
> 而注入内容在圆盘内部是逐像素准确的。下面保留原始观测，但不要再引用"注入缺陷"这个归因。

把前置预览缓冲**整块写成纯 128 灰**，屏幕上并不是均匀灰，而是"中心暗、四周亮"
（列均值 155 ↔ 211）；写"均匀强色度"时，色度**只在中央一个圆盘里可见**，
圆外只剩亮度信息。后置同样测试则是逐像素精确的。

⇒ 前置**取景器显示链路**对画面做了一层空间相关的处理（推测是自拍向的美颜/LSC/局部色调映射，
与 HAL 缓冲无关）。因此：**注入进 HAL 缓冲的前置预览素材是精确的，但取景器上看到的是被调制过的版本。**
录像与照片两条**落盘**链路没有这个现象（47.3/47.6 的证据都是逐像素/高 NCC 的）。

### 47.6 前置照片：真帧改一处 → 拍出来就是"真实照片 + 我们的改动"

1. `raw_dump=4976640:/data/vendor/camera/fraw.raw` 取一张**真的前置 RAW10**
   （2304×1728、行距 2880、值域 50–1023、均值 115）。
2. 在 RAW 坐标里画"黑方块 + 白框"（复用 §41 的 RAW10 打包/解包工具）。
3. `mock_map=4976640:<改后的 RAW>` + **盲写**（照片必须盲写，成簇写会撕裂⇒残缺 JPEG）→ 按快门。
4. 得到的 `IMG*.jpg`（2592×4608）里：**实景完好**（天花板、灯、木纹），
   **我们画的黑方块 + 白框原样出现在画面中央**。

### 47.7 复现命令（前置）

```bash
# 1) 起 provider（只打前置：cam=1；预览+录像各一个模板，用 | 分隔）
adb shell sh /data/local/tmp/ace5_deploy4.sh \
  'real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;report_period=8;\
req_vaddrs=0x1e49ca0;hook_rec_vaddrs=0x1e46f60;hook_rec_ol_vaddrs=0x6cadc;cam=1;\
inject=1;inject_us=0;mock_map=4038656:/data/vendor/camera/fmock.raw|3194880:/data/vendor/camera/fvidmock.raw'

# 2) 起 App → 点"视频"→ 点右下翻转按钮（快照就是前置）→ 按录像
adb shell am start -n com.oplus.camera/.Camera
adb shell input tap 420 2150     # 视频页
adb shell input tap 1054 2411    # 切换摄像头（视频页的翻转键在 [970,2327][1138,2495]）
adb shell "dumpsys media.camera | grep 'is open'"   # 必须看到 Device 1
adb shell input tap 631 2410     # 开始/停止录像

# 3) 生成素材
python3 scripts/gen-camera-mock.py front-video  design.png out.raw   # 前置录像（1080p）
python3 scripts/gen-camera-mock.py rear-preview  design.png out.raw   # 后置预览
python3 scripts/gen-camera-mock.py front-preview design.png out.raw   # 前置预览（受 47.5 影响）
```

### 47.8 净收益与剩余

- **拿到**：前置 id/流尺寸/三个面的缓冲布局/两套显示变换/写入策略/照片短路径，
  以及一个**可复现的生成器** `scripts/gen-camera-mock.py`（三种目标面，参数全部来自实测）。
- **视频线**：前置已达"100% 帧、逐帧纯净"，可以直接拿来做"系统层录像模拟"的样板。
- **仍未解**：前置取景器的径向调制（47.5）；以及后置 4K 录像的颜色（§46.3，与前置无关）。

---

## 48. 前置取景器的"圆盘"改判：**是 App 自己的渲染，不是注入缺陷**（附 `cam=` 闸门修复）

§47.5 把前置取景器的径向调制记成"注入触发的缺陷"——**这个归因是错的**。本轮用
"把一张**真帧**原样注入回去"的办法把它钉死了，并顺带修掉一个真 bug。

### 48.1 决定性观察：**不注入也有那个圆盘**

关掉全部注入（cfg 只剩记账钩子），在前置拍一张取景器截图（`/tmp/real_front.png`）：
画面中央是一个清晰的圆盘，圆盘外是"发白、发糊"的版本 —— **相机自己是实景，圆盘照样在**。

再打开注入、把**同一张真帧**（`raw_dump` 从前置落下来的那张）注回去，径向剖面与实景几乎重合
（159.3/148.9/151.7/142.9/167.4/194.6 vs 159.4/155.8/164.3/156.5/174.2/199.9，差异来自两次拍摄的曝光差）。

⇒ **圆盘是前置取景器这条显示链路的固定行为，与注入无关**；
我们注入的内容在**圆盘内部是逐像素准确的**（探针：缓冲 `(Y=128,U=200,V=90)` → 屏幕 `(77,132,254)`，
理论值 `(69,133,255)`；缓冲 `(Y=200,U=90,V=200)` → 屏幕 `(254,165,137)`，理论值 `(255,170,137)`）。

### 48.2 圆盘的几何与传递特性（实测）

- 形状：**圆盘**，屏幕坐标中心约 **(590, 1192)**、半径约 **600 px**，边界**陡峭**（不是渐变）。
  判定法：把整块缓冲写成卡片本色 `(93,33,31)`，逐行/逐列找"本色像素"的范围，边界轨迹是圆。
- 盘外：内容被**强烈提亮并去饱和**，且**保留原来的色相方向**（蓝的内容盘外偏中性偏蓝、
  暖的内容盘外偏暖）—— 观感与"发光/朦胧（bloom + 柔光）"一致，而不是"叠加一层纯白"。
  线性混合模型拟合不出来（解出负的 α），说明不是简单的 `α·白 + (1−α)·内容`。
- **例子**：缓冲纯 128 灰 → 盘内 127.2（= 精确），盘外 212.7；
  缓冲满幅随机噪声（细节拉满）→ 盘内 127.4（精确），盘外 204.0，**局部标准差盘内 49.3 / 盘外 50.0
  （噪声没被抹掉，只是被抬亮）**。

### 48.3 已被排除的两个嫌疑（都试过）

| 嫌疑 | 操作 | 结果 |
| --- | --- | --- |
| **美颜** | 前置默认"美颜已开启"⇒ 进菜单选"**无美颜**"（UI 变"美颜已关闭"） | 圆盘**照旧** |
| **夜景** | 前置默认"夜景已开启"⇒ 点击关闭（UI 变"夜景已关闭"） | 圆盘**照旧** |

（另外附注：`柔光已关闭`、`滤镜已关闭` 也都在关态。）

### 48.4 一个真 bug：`cam=` **没有**管住注入线程

`cam=1` 以前只作用于**结果钩子**那一路（`portal_cam_selected()` 只在 `process_capture_result`
里被调用），而 `mock_map` 的**注入线程**只按**尺寸**挑靶 —— 于是 App 明明开在后置（id=2），
线程照样按 4,038,656 这个尺寸把**后置预览**写成了我们的卡片。
实测证据：`cam=1` + `mock_map=4038656:…frontP.raw`，App 在后置 ⇒ 后置取景器里出现了我们的卡片。

修法（`camshim.c` 注入线程主循环开头）：`cam=` 非空且当前相机不符 ⇒ 解除全部映射、退回重扫、
打一行日志等目标相机出现。**实测验证**：

1. `cam=1`，App 在后置：注入线程一行"9 块缓冲"都不打，取景器中心是实景 `(141.8,118.4,92.8)` ✅；
2. 翻到前置（Device 1）：线程立刻开打"9 块缓冲"，取景器出现我们的内容 ✅。

### 48.5 这一轮的净收益

- **归因纠正**：§47.5 的"前置注入缺陷"降级为"前置取景器的固定显示行为"，
  注入本身在盘内是逐像素准确的 —— 前置预览这条链路**不是缺陷，是达标**。
- **`cam=` 语义落实**：终于能"只打前置/只打后置"，这对 A/B 实验是刚需。
- 仍未解：圆盘的**成因**（app 侧，美颜/夜景已排除）；盘外内容无法还原（被提亮去饱和，信息有损）。

---

## 49. 前置稳定性实测 + 一条排查笔记

### 49.1 长录像零退化：32 秒 / 958 帧，逐帧恒定

配置：`cam=1; inject=1; inject_us=0; mock_map=3194880:<前置录像卡片>`（盲写），
前置 1080p30 连录 **31.97 秒**。

| 指标 | 实测 |
| --- | --- |
| 视频流 | 1080×1920 @30fps，**958 帧**（理论 959）⇒ **不丢帧** |
| 抽 64 帧（2fps）与设计稿的 NCC | 均值 **0.9972**，最小 **0.9972**，>0.95 的 **64/64** |
| 逐秒最差 NCC | 16 秒里全是 **0.997** ⇒ **零退化、零卡死** |

对照 §35/§36 的后置 4K：那里盲写会把 ISP 饿死（25–28 帧、确定性卡死签名）。
**同一个旋钮在前置上不但能用，还是最优解** —— 这就是"前置录像可以直接当样板"的依据。

### 49.2 前置照片可复现（第二次独立验证）

`raw_dump=4976640` 取真 RAW → 画黑框 → 盲写回注 → 快门：
落盘 JPEG（2592×4608）**中心均值 0.5（纯黑，= 我们画的黑块）**，
而黑块上方的实景均值 110.4（**实景完好**）。与 §47.6 的第一次验证互相独立、结论一致。

### 49.3 一条排查笔记：模板文件不在 ⇒ 注入线程**静默**什么都不做

本轮为了验证前置照片，连跑三次都"没反应"。真正的第一现场是**一行 ERROR**：

```
E PortalCamShim: 注入：打不开模板 /data/vendor/camera/fraw2.raw（errno=2）
```

`mock_map` 的模板读不进来时 `tpl == NULL`，而 `portal_build_targets` 在"有映射表但没模板"时
对**所有**尺寸都 `continue` ⇒ 目标数 0 ⇒ 线程每 2 秒重扫一次、**一行日志都不打**，
看上去就像"注入没生效"。教训有两条：

1. **先看 ERROR，再谈机理** —— 我为这个现象编过"RAW 池没分配""cam 闸门卡住"两套解释，全是错的；
2. 清理设备临时素材时**别把正在用的模板删掉**（本次就是自己上一轮收尾时把 `fraw2.raw` 删了）。

---

## 50. 前置预览还差的那一层：**上下翻转**（已标定）+ 跨状态稳定性矩阵

### 50.1 前置预览的显示变换 = 后置那套 `rot90cw` **再上下翻一次**

用一个**手性探针**（大号 `F` + 四角标记 + 顶/底文字）在 `front-preview` 布局下注入，
拿屏幕上的圆盘区域（盘内是逐像素准确的）与设计稿做 8 种二面体变换的 IoU 比对：

| 变换 | IoU |
| --- | --- |
| **rot180 + 左右镜像**（≡ 上下翻转） | **0.683** |
| rot180 | 0.352 |
| rot90 | 0.240 |
| 原样 | 0.234 |

⇒ **屏幕 = flipud(设计稿)**。这是前置传感器 180° 安装的体现（后置没有这一层）。
要"屏幕上正着"，就得在设计稿进反变换**之前先上下翻一次** ——
`scripts/gen-camera-mock.py` 的 `front-preview` 模式已按此实现（`display='rot90cw_flipud'`）。

验证（同一支卡片重跑）：屏幕上 `PORTALEX` / `FRONT CAMERA MOCK` / `系统层 · 零注入` **正着可读**，
TL 三角在左、TR 方块在右、中央 `FRONT` 十字正立 —— 与设计稿一致。

### 50.2 跨状态稳定性矩阵（前置，`cam=1`，盲写，一次性布好后走 6 个状态）

| # | 状态 | 相机 | 盘内均值 RGB | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 前置照片模式 | Device 1 | 67.8 / 67.6 / 71.5 | **我们的卡片** ✅ |
| 2 | 切到视频模式 | Device 1 | 61.6 / 61.8 / 67.0 | **我们的卡片**（录像预览面） ✅ |
| 3 | 切回照片 | Device 1 | 61.6 / 61.8 / 67.0 | ✅ |
| 4 | **强杀 App 再起** | Device 1 | 67.8 / 67.6 / 71.5 | ✅（与 #1 逐值相同） |
| 5 | **息屏 10 秒再亮** | Device 1 | 67.8 / 67.6 / 71.5 | ✅（与 #1 逐值相同） |
| 6 | 切到后置 | Device 2 | 147.5 / 113.6 / 90.6 | 实景 —— **`cam=1` 正确拦住了** ✅ |

两点值得单独记：

- **#4/#5 与 #1 的均值逐值相同**：跨 App 重启与息屏，渲染是确定性的，没有"重启后半接管"这种劣化；
- **#6 是对 §48.4 那个 `cam=` 闸门修复的独立复验**：修之前这一步会把后置预览也写成我们的卡片，
  现在后置是实景。

### 50.3 这一轮的净收益

- 前置**预览**：布局（§47.2）、色序（NV21）、显示变换（§50.1）三者齐了 ⇒
  卡片在前置取景器上**正着、逐像素准确**（盘内），圆盘的成因归 App（§48）。
- 前置**录像**：30 秒级 100% 接管、零丢帧、NCC 恒定 0.9972（§49.1）。
- 前置**照片**：真帧改一处的短路径两次独立验证通过（§47.6 / §49.2）。
- ⇒ **"切到前置并复现/稳定化视频与图像数据接管"这一阶段性目标已经达成**。

---

## 51. 后置 4K 录像的"绿帧"不是 ISP 3A —— 是**写坏了 Y 面之后那块**

§43 把后置录像的偏色归为"ISP 3A 对合成内容的解释"。本轮把这条归因**推翻了**，并找到一个可用的边界。

### 51.1 决定性观察：**整块填纯 128 也会录出纯绿帧**

把 4K 录像面（`12,591,104`，`MAP_UNKNOWN ×34`）**整块**写成纯 128（任何解释下都该是中灰）：

| 采样 | 结果 |
| --- | --- |
| 28 帧 | **14 帧纯绿 (0,102,0)**、其余是实景 |

⇒ 绿帧**与内容无关**。`(0,~91,0)` 换算回 YUV 是 **Y≈45、U=V=0** ——
是"色度被读成 0"的典型签名，而不是什么色调映射。同一次录像里另一半帧是完好的实景
（那是我们**没赢下**、被 ISP 重新写过的帧）。

### 51.2 边界：写到哪里会坏

新增开关 `inject_limit=<字节>`（每条缓冲只写前 N 字节），三档对照（各录 10 秒、3fps 抽 31 帧）：

| `inject_limit` | 写了什么 | 纯绿帧 |
| --- | --- | --- |
| 12591104（整块，默认） | Y + 色度 + 尾部 57,344 | **19/31** |
| 12533760 | Y + 色度面（**留着**尾部 57,344） | **19/31** |
| **8355840**（只写 Y 面） | 只写第一个平面 | **0/31** ✅ |

⇒ **坏就坏在 `[8,355,840, …)` 这一段**：只要往里写原始字节，硬件就解不出来（绿帧）。
换句话说，这块面在 Y 面之后**不是**"朴素的行间交织色度"——它带元数据/压缩语义，
用"像素字节直接铺"的做法会破坏它。

### 51.3 附带确认的事实

- **4K 录像面就是 `12,591,104 ×34`**：本轮录像期间完整的 dmabuf 直方图里，这一类的**块数最多**
  （34 块，其余尺寸都是个位数）⇒ 注入线程找到的 24–32 块确实是录像链路的主面。
- **1080p 是另一条路**：把视频尺寸改成 1080P30 后，`12,591,104` 一块都不存在；
  `3,194,880`、`12,582,912`、`12,591,104` 三档都不是它的录像面（注进去录像毫无变化）——
  1080p 的录像面**尚未定位**（当时在场的大块只有 `12,582,912 ×4` 与一堆 CBM/offline 面）。
- 因此"前置录像干净"与"后置 4K 录像发绿"**不是同一类问题**：前置那条是**线性 NV12**（§47.2），
  后置这条是**带元数据的行间布局**。

### 51.4 下一步（下轮的第一个实验）

1. 把 `inject_limit` 卡在 Y 面（8,355,840）时，录出来的是"**我们的亮度 + 现场的色度**"——
   说明 Y 面这条道是通的；要把颜色也接管，得先搞清 `[8,355,840, 12,591,104)` 的真实结构。
2. 最直接的读法：**打印录像缓冲的 native_handle**（`nFds`/`numInts` 与各 fd 的 size）——
   多平面的话一眼就能看出色度面是独立 fd 还是同一块的第二段。
   `portal_dump_handle()` 已存在，但它挂在"请求/结果布局解析"那条路上，实测没被触到（本轮日志里
   一条 `handle v=` 都没有）⇒ 需要把它挂到**确认可用**的那条路径（`req_vaddrs` 钩子的请求快照）。

---

## 52. 冷启动（重启）实测：**发现并修掉一个只在开机路径上现形的 sepolicy 缺失**

用户指令：先做重启冷启动测试，再查边界，最后处理 4K 色度。本节是前两项。

### 52.1 第一次重启：**相机挂了**（provider 崩溃重启循环）

把当前模块打包装上（`ksud module install` → 重启）后：

| 项 | 结果 |
| --- | --- |
| `/odm/lib64/hw` 上的 tmpfs + overlay | ✅ 模块自己挂上了（无人工介入） |
| `/odm/lib64/hw/camera.qcom.so` | ✅ 我们的 shim（236,520 字节） |
| `/data/vendor/camera/portal.cfg` | ✅ 随包干净态已写好 |
| **`init.svc.vendor.camera-provider`** | ❌ **`restarting`（崩溃重启循环）** |

崩溃现场（`logcat -b crash` + tombstone）：

```
I CamxLoad: find_in_path: Find camera soc impl file /odm/lib64/hw/camera.oemlayer.so
E CamxLoad: find_in_path: Can't access file /odm/lib64/hw/camera.oemlayer.so
F DEBUG   : signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
F DEBUG   : #00 __cfi_slowpath+28 (libdl.so)
F DEBUG   : #01 OemLayer::init() (.cfi)+312  (/odm/lib64/hw/camera.oemlayer.so)
```

**根因**：ACE5 的路线是在 `/odm/lib64/hw` 上叠 **tmpfs-upper 的 overlay**，而 HAL 域
（`hal_camera_default`）**没有穿过 tmpfs 的权限** ⇒ 它连 overlay **下面那一层**的
`camera.oemlayer.so` 都读不到（overlay 的查表要动 upper/work）⇒ `OemLayer::init()` 拿不到
函数指针 ⇒ CFI 检查失败 ⇒ SIGSEGV ⇒ provider 崩溃重启循环 ⇒ **相机整体不可用**。

**为什么之前一直没暴露**：运行时手工部署走的是 `ksud sepolicy patch`（设备上的
`ace5_deploy4.sh` 里就有这两条），而**模块包里的 `sepolicy.rule` 与 `post-fs-data.sh` 都漏了它们**
—— 只有"从开机路径走一遍"才会现形。这正是"冷启动必须实测"的价值。

### 52.2 修复与第二次重启：干净通过

给模块补上三条（`sepolicy.rule` 与 `post-fs-data.sh` 的 `policy` 列表**两处都写**，
沿用本仓库"两条路都留着防静默失效"的惯例）：

```
allow hal_camera_default tmpfs dir      { search getattr read open }
allow hal_camera_default tmpfs file     { read open getattr map execute }
allow hal_camera_default tmpfs lnk_file { read getattr }
```

重新打包安装 → 第二次重启：

| 项 | 结果 |
| --- | --- |
| tmpfs + overlay | ✅ |
| **`init.svc.vendor.camera-provider`** | ✅ **`running`（pid 1937）** |
| `cfi_slowpath` / `Can't access file /odm` 出现次数 | ✅ **0** |
| 拍照 | ✅ 落盘 JPEG（3.2 MB） |
| shim 钩子 | ✅ `零调用钩子[0]/[1]` 正常命中 |
| 相机设备数 | ✅ 18 |
| tombstone | ✅ 无新增（最新仍是 12:33 那批，属于修复前） |

⇒ **模块现在能自己走完开机路径**：装一次、重启即可，不需要再手工 deploy。这是"系统层"交付的最后一环。

### 52.3 边界复测：**"只写 Y 面"是唯一可复现的安全配方**

给 `inject_limit` 做细扫（纯 128 素材、各录 8 秒、抽 3fps 判"纯绿帧"占比）：

| `inject_limit` | 纯绿帧 | 备注 |
| --- | --- | --- |
| **8,355,840**（只写 Y 面） | **0/22、0/22、0/31**（三次一致） | ✅ 安全 |
| 9,500,000 | 0/17 | |
| 10,500,000 | 0/19 | |
| 11,500,000 | 0/19 | 首次扫描 |
| 11,490,000 / 11,510,000（复测） | 50% / 41% | ⚠️ **与上面矛盾** |
| 12,000,000 | 45% | |
| 12,500,000 | 77% | |
| 12,533,760 | 64% | |
| **12,591,104**（整块） | **68%（复测 4 次 64–77%）** | ❌ 必坏 |

**结论要照着"可复现性"说，不能照着一次扫描说**：

1. **只写 Y 面（8,355,840）= 三次独立复测全 0 绿** —— 这是可以依赖的配方
   （后置 4K 录像目前只能可靠接管**亮度**，录出来是"我们的亮度 + 现场的色度"）。
2. **整块写 = 四次复测 64–77% 绿** —— 稳定地坏。
3. 中间那一段（11.5M–12.5M）**是噪声区**：同一点两次能差 50 个百分点
   ⇒ 不能拿单次扫描去宣布"边界在 N 字节"。**上一轮那条"边界在 8,355,840"的措辞已按此收窄为
   "Y 面之内可复现安全，之外不可依赖"。**

（方法学上的教训记一笔：这类"占比型"指标必须**同点重复**再下结论；本轮正是靠复测才发现
单次扫描的 0% 是运气。）

### 52.4 4K 色度的第一个探针：**Y 面紧后面那段不是"朴素的色度"**

用户指令的最后一项（处理 4K 色度）已经开工，第一个探针就给出了方向：

**做法**：整块写中性 128（Y 面 = 平 128），但把 **[8,355,840, 11,500,000)** 这段写成强色度
（90/200 交替 —— **均匀/规律的内容与平面布局无关**，所以不需要先知道布局），
`inject_limit=11,500,000`，4K 录像 8 秒。

**结果**（25 个采样帧）：

| 帧的均值 | 平均饱和 | 说明 |
| --- | --- | --- |
| `(3, 84, 4)` × 18 帧 | 84.8 | **纯绿** —— 即"色度被解码成 0"的那个签名 |
| `(7, 4, 8)` / `(9, 5, 9)` × 3 帧 | 4.1 | 近黑 |
| 其余几帧 | 55–79 | 过渡 |

⇒ 我们往那段里写强色度，画面**并没有变成红/蓝**，反而变成"色度=0"的绿 ——
这正是**元数据被写坏**的表现（和"整块写"同一个签名）。

**推论**：`[8,355,840, …)` 开头那一段是 **Y 面的 UBWC 元数据**（或压缩描述），
**不是色度像素**。色度像素在这块 12,591,104 字节分配的更后面（连同它自己的元数据）。

⇒ 与 §52.3 完全自洽：**只写 Y 面之所以干净，是因为它恰好只碰像素、不碰元数据**。
下一轮的实验就是把"窗口"往后挪 —— 在 `[11,500,000, 12,591,104)` 里逐段放强色度，
找出真正的色度像素区（并避开它也带的那段元数据）。

---

## 53. 4K 色度：加了"窗口写"这把刀，仍切不开 —— 该面**不是朴素 NV12**

### 53.1 新工具 `inject_span=<起始>:<长度>`

`inject_limit` 只能写**前缀**，而后置 4K 面 Y 面之后那段一写就坏 —— 要在**不碰**那段的前提下
试探后面的窗口，就必须能"跳着写"。`inject_span` 就是它：只把模板 `[起始, 起始+长度)` 那段字节
写进缓冲的同偏移处（`inject_us=0` 盲写时窗口很小、总量也小，不会饿死 ISP）。

### 53.2 窗口扫描（256 KB 窗口，写 0）

| 窗口起点 | 盲写（`inject_us=0`） | 同步写（`inject_sync=2`） |
| --- | --- | --- |
| 9,000,000 | 13/13 黑帧（含绿带 7–10） | 13/13 黑帧（含绿带 10） |
| 11,000,000 | 13/13 黑 | — |
| 11,400,000 | 13/13 黑 | 13/13 黑 |
| 11,500,000 | 13/13 黑（另一次 13/13 是"场景+绿带"，**不可复现**） | 13/13 黑 |
| 11,600,000 | 13/13 黑 | 13/13 黑 |
| 12,000,000 | 13/13 黑 | — |
| 12,333,760 | 3/13 黑 | — |
| 12,400,000 | 5/13 黑 | — |

**读法**：`(5,3,7)` 那种"整帧近黑"是**解码被打断**的签名，绿带只是重新同步的那一小条。
⇒ **写这个区间里的任何一个 256 KB 窗口，都会把整帧打坏**，与偏移无关。

### 53.3 与"只写 Y 面安全"合起来看，结论变了

再对一次"只写 Y 面"（`inject_limit=8,355,840`）的帧：亮度范围 53–73 —— **和实景自己的范围重合**，
并没有出现"我们的平 128 灰 + 现场的色度"那种特征。

⇒ 也就是说：**只写 Y 面时我们既没弄坏、也没真正接管** —— 帧就是 ISP 自己的帧。
"0 绿"的真正含义是"我们的写在这一档**不起作用**"，而不是"安全地接管了亮度"。
（§52.3 里那句"可靠接管亮度"要按此**收回**。）

### 53.4 这条线到此的净结论

后置 4K 录像面 **不是朴素 NV12**：

1. Y 面之后那 4.2 MB 的行为像**压缩/元数据**（写哪都整帧坏）；
2. 但色度又确实来自那里（写坏了色度就没了）；
3. 于是"逐字节试探"这条黑盒路走到头了 —— 需要**从分配器侧拿到真实格式**：
   `stream->usage = 0x10030322` 的高位就是 UBWC 标志位，配合 `format=0x22`（IMPLEMENTATION_DEFINED）
   与 `/vendor/lib64/libgralloc*` 的格式表，才能知道"色度像素在哪、每块多少字节"。
   （前置那条录像面是**线性**的，所以同样的手法在那里一次就通 —— 两条面根本不是一回事。）

**本轮的净收益**：`inject_span` 工具 + 一条被证伪的乐观结论（不再自欺"亮度已接管"）+ 下一步的
明确入口（从 gralloc 侧读格式，而不是继续瞎试偏移）。

---

## 54. 想把 4K 录像面"退回线性"：钩子挂上了，但**挂早了一步**

思路：既然 4K 面是 UBWC 才导致"写进就绿"，那就让它**退回线性**（前置那条线性面一次就通）。
`usage_patch` 那条正规路在 ACE5 上走不通（真 HAL 没导出 `configure_streams` 符号），
于是改用**按地址挂钩**：前几轮已定位 `configureStreams_Impl = camera.oemlayer.so+0x6c554`。

### 54.1 新能力：`scrub_ol_vaddr=<偏移>`

在 oemlayer 的指定偏移上挂一个"改用途位"钩子（内部调用同一个 `portal_scrub_streams(流表)`），
并把 `usage_patch=3` 的含义扩为"**连编码器流也改**"（默认仍旧跳过它，见 §15.8）。

实测挂载成功、确实被调用：

```
已挂钩[2] camera.oemlayer.so+0x6c554(scrub)：入口 0x7a84ccc554 → 钩子 …
改用途位[0]：format=0x23 1920x1080 usage 0x103 → 0x133（UBWC 已清）
改用途位[1]：format=0x23 3840x2160 usage 0x3   → 0x33 （UBWC 已清）
```

### 54.2 但这一步是**空的**

注意上表里 4K 录像流的 usage 是 **`0x3`** —— **UBWC 位（0x10000000）根本不在这里**。
这与 shim 里早就写下的警告完全一致：

> 改位必须排在真 HAL **之后**：框架进来时是 0x900，UBWC / HW_CAMERA_WRITE 这些位是**厂商 HAL 加的**，
> 先改会被它覆盖掉。

我们挂的是 `configureStreams_Impl` 的**入口** ⇒ 见到的是"厂商还没加 UBWC"的表 ⇒ 改了个寂寞。
（也顺带印证了：UBWC 位是**厂商 HAL 在 configureStreams 内部**加的，不在框架下发的 usage 里。）

### 54.3 下一步（明确）

需要**同一个调用栈里、厂商加完位之后**的挂钩点，两条路：

1. **返回点钩子**：现有框架只支持"入口钩子 + 重放"，要给 `+0x6c554` 加"返回后回调"
   （把 `x30` 换成我们的尾巴 shell，跑完再跳回原返回地址）；这是对钩子框架的一次小扩展；
2. **更深的链**：`oemlayer+0x6c554 → camera.qcom.so+0x1e46f60`（后一个我们已经在当"零调用钩子"记数），
   往下再找一个"厂商加完位、但 buffer 还没分配"的点。

顺带记一个**已经拿到手的事实**：4K 录像面在 camera3 层是 `format=0x23 (YCbCr_420_888) 3840x2160`，
**框架下发的 usage 里没有 UBWC** ⇒ 只要能在正确时点清掉，就有机会把它变成线性面，
之后就是"前置那条路"的复刻（线性 NV21 + 盲写，我们已经验证过 100% 接管）。

### 54.4 决定性一步：**把 UBWC 清掉，4K 录像直接录不下去**

把 `usage_patch=3`（连编码器流也改）配合 `scrub_ol_vaddr=0x6c554` 跑通之后，
后续的 `configureStreams` 调用里**确实**看到了带 UBWC 的那条流，并把它清了：

```
改用途位[2]：format=0x22 3840x2160 usage 0x10010300 → 0x10333（UBWC 已清）
```

**两个后果，一次看清**：

1. **分配确实变了**：录像期间的大块清单里 **`12,591,104`（UBWC 面）整类消失**，
   换成 `11,796,480` 等尺寸 ⇒ "清 UBWC 能改变分配" 这件事是真的；
2. **但录像录不下去**：那一次 4K 录像只落了 **38 帧**（12 秒应该 ~360 帧）就断了
   —— 编码器/ISP 那条路要求 UBWC，清了它就供不上帧。

⇒ 与 §15.8（当年在 msm8998 上得出的结论）在同一件事上**互相印证**：
**4K 录像面必须保持 UBWC**。想接管它的色度，只剩"自己按 UBWC 写"这一条路
（瓦片布局我们已经会在 §46 里算，缺的是**每个块的元数据/压缩模式**要写真值）。

**所以本轮的净结论**：4K 录像这条路，黑盒试探与"退回线性"两条捷径**都被证据关掉了**；
剩下的是一条明确的实现路线（UBWC 编码），不是猜谜。

---

## 55. 后置 **1080p** 录像面找到了（`3,219,456`）—— 但它同样是压缩面

### 55.1 怎么找到的：把"存活探针"当搜索键

上一轮之前一直用"尺寸"猜哪块喂给编码器，猜不中。这轮改用**存活探针 + 制造画面变化**：

```
配置：live_probe=3（每 3 秒报一次"哪些 ≥512KB 的 dmabuf 内容在变"）
做法：后置录像中反复点变焦（0.6x ↔ 2x）——**静止场景下四窗判据会失效，得让画面动起来**
```

录像期间的活缓冲清单里出现了**两个** 1080p 级别的尺寸：

| 尺寸 | 数量 | 判断 |
| --- | --- | --- |
| `3,194,880` | 多块 | 视频**预览**面（与前置的录像预览同尺寸） |
| **`3,219,456`** | 多块 | **后置 1080p 的录像面**（新发现） |

（顺带解释了前几轮的误判：`3,219,456` 在前置会话里也出现过、但那时是**闲置**的，
我当时把它当成"无关池子"跳过了；在后置 1080p 录像时它才是活的。）

### 55.2 注进去确实能到编码器，但同样是"压缩面"

用整块 `128`（任何解释下都该是中灰）注入 `3,219,456`：

| 帧 | 结果 |
| --- | --- |
| 约一半 | **纯绿 `(0,103,0)`** |
| 其余 | 实景 `(98,77,58)` |

⇒ 两件事同时成立：**我们确实接管了这块面**（录像内容被我们改了），
**但它的格式不是线性 NV12**（整块中性灰被解成"色度=0"的绿）—— 与 4K 面同一类签名。

### 55.3 于是"后置录像"的定论

| 面 | 前置 | 后置 |
| --- | --- | --- |
| 1080p 录像面 | `3,194,880` **线性**（真帧落盘一次就解出干净图） | `3,219,456` **压缩** |
| 4K 录像面 | —— | `12,591,104` **压缩** |

⇒ **不是"4K 特有的问题"，而是"后置录像链路整体用压缩面"**；
前置那条之所以一次就通，是因为它**本来就是线性面**。
想让后置录像也被完全接管，只剩一条路：**自己按压缩格式写**（瓦片布局 §46 已会算，
缺的是每块的元数据真值）—— 而 1080p（24 块 × 3.2MB ≈ 77MB/轮）比 4K（428MB/轮）轻得多，
是更合适的第一个靶子。

---

## 56. 验收清单（可照着跑一遍）

模块装好并**启用**之后（§52：装一次 + 重启，开机自动部署），下面这套可以在几分钟内复现全部能力。
每条都给出了**判据**，而不是"看起来对"。

### 56.1 前置：取景器 / 录像 / 照片（三条全通）

```bash
# 1) 起 provider：只打前置（cam=1），预览面 + 照片 RAW 面各一个模板
adb shell sh /data/local/tmp/ace5_deploy4.sh \
 'real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;report_period=20;\
req_vaddrs=0x1e49ca0;hook_rec_vaddrs=0x1e46f60;hook_rec_ol_vaddrs=0x6cadc;cam=1;inject=1;inject_us=0;\
mock_map=4038656:/data/vendor/camera/ffront.raw|4976640:/data/vendor/camera/fraw2.raw'
adb shell am start -n com.oplus.camera/.Camera
adb shell input tap 1054 2411        # 切到前置
adb shell "dumpsys media.camera | grep 'is open'"   # 必须 Device 1
adb shell screencap -p /sdcard/acc.png && adb pull /sdcard/acc.png
adb shell input tap 631 2410         # 快门
```

| 项 | 判据（本轮实测值） |
| --- | --- |
| 前置取景器 | 屏幕中心 800px 圆内均值 **67.8 / 67.6 / 71.5**（= 我们的卡片；实景约 120+） |
| 前置照片 | 落盘 JPEG 中心均值 **0.5（纯黑 = 我们画的黑块）**，上方实景 **109.2** |

录像（换成 `3194880:/data/vendor/camera/fvidmock.raw`，进"视频"页录 8 秒）：

| 项 | 判据（实测） |
| --- | --- |
| 接管率 | 抽 32 帧 **32/32 = 100%**；32 秒长录像 958 帧、NCC 均值=最小 **0.9972**（§49.1） |
| 丢帧 | 958/959（不丢帧） |

### 56.2 后置：取景器 / 照片（两条通）；录像**未通**

| 项 | 状态 | 判据 |
| --- | --- | --- |
| 后置取景器 | ✅ | 注入 `4038656:fmock.raw` 后整屏即我们的卡片（逐像素） |
| 后置照片 | ✅ | 与前置同法（`15728640` RAW 面改一处 → 落盘 JPEG 里实景完好 + 我们的改动） |
| 后置**录像** | ❌ | 面是**压缩面**（1080p=`3,219,456`、4K=`12,591,104`），线性/瓦片式写入都会被解成绿或黑（§53/§55） |

### 56.3 模块与健康

| 项 | 判据 |
| --- | --- |
| 冷启动 | 重启后 `mount` 见 `/odm/lib64/hw` 上的 tmpfs+overlay；provider **running**；`cfi_slowpath`/`Can't access file /odm` **0 次** |
| 拍照可用 | 任意时候按快门都有 JPEG 落盘（模拟关掉也一样） |
| 崩溃 | `/data/tombstones` 无新增 |

### 56.4 一句话总结当前交付

**系统层、零注入目标应用**：前置**预览/照片/录像三条全通**（录像 100% 接管且零退化），
后置**预览/照片两条通**；后置录像因为厂商用**压缩面**（UBWC）而未通——
路线明确（自实现按压缩格式写），但需要格式细节，不是靠试探能绕过去的。

---

## 57. 归档索引（本届工作收束）

### 57.1 仓库里有什么

| 路径 | 内容 |
| --- | --- |
| `camera-hal/src/camshim.c` | HAL 包装层本体（钩子、注入、量具。**所有开关**见 §44.1 的表） |
| `camera-hal/jni`、`build.gradle.kts` | NDK 构建（armeabi-v7a + arm64-v8a 都要出，见 §18.2 的 32 位 no-op 桩） |
| `magisk/portalex-camera-hal/` | 模块源：`post-fs-data.sh`（开机部署）、`customize.sh`、`sepolicy.rule`（**含 tmpfs 三条，§52 的冷启动修复**）、`service.sh` |
| `scripts/build-camera-hal-module.sh` | 按设备出包（`pineapple` / `msm8998`） |
| `scripts/deploy-camera-hal-pineapple.sh` | 运行期部署（只叠 `/odm/lib64/hw`，**永不碰 `/vendor`**） |
| `scripts/gen-camera-mock.py` | 由设计稿生成可注入素材（三种目标面，参数全部实测） |
| `scripts/research/` | 本届用到的量具（UBWC 换算、RAW10 编辑、帧分类、列带相关）+ 两张测试卡设计稿 + README |
| `docs/camera-mock.md` | 本文档：从"这套路走不走得通"到"每个面的字节布局"的完整记录（§1–§57） |
| `docs/camera-hal-compat.md` | **兼容性分析**：适用范围、两种平台形态对照、依赖自检清单、症状索引、移植清单、未验证边界 |

### 57.2 设备上有什么（运行期产物）

| 路径 | 内容 | 谁建的 |
| --- | --- | --- |
| `/odm/lib64/hw/camera.qcom.so` | 我们的 shim（overlay 的 upper 层） | 模块 `post-fs-data.sh`（开机自动） |
| `/data/local/tmp/odmtmp/{upper,work}` | overlay 的 upper/work（tmpfs） | 同上 |
| `/data/vendor/camera/portal.cfg` | 开关文件（随包=干净态：只装钩子不开注入） | 同上 |
| `/data/vendor/camera/{fmock,ffront,fvidmock,fraw2}.raw` | 测试素材（预览/前置预览/录像/照片 RAW） | 手工 `adb push` |

### 57.3 本届（同一天）的提交

```
0023ec7 feat(camera-hal): inject_limit 开关；后置 4K 绿帧归因推翻（与内容无关，坏在 Y 面之后那段）
ad7fbfe docs(camera-mock): §49 前置稳定性实测（32s/958 帧/NCC 恒定 0.9972）+ 模板缺失的静默失败排查笔记
f03a19c fix(camera-hal): cam= 闸门管住注入线程；前置取景器'圆盘'改判为 App 自身渲染
0dda5db feat(camera-mock): 前置预览补上'上下翻转'标定；§50 跨状态稳定性矩阵
190cfbc feat(camera-hal): inject_span 窗口写；4K 色度黑盒探到头，两处结论收回
7c314f7 fix(module): 冷启动实测 —— 补上 tmpfs sepolicy（缺了会让 provider 崩溃重启循环）
acaf3b4 docs(camera-mock): §52.4 4K 色度探针 —— Y 面紧后那段是元数据而非色度像素
3afeb2b feat(camera-hal): scrub_ol_vaddr —— 按地址挂'改用途位'钩子；钩上了但挂早一步
576f833 docs(camera-mock): §54.4 清 UBWC 会让分配改变但录像录不下去（38 帧就断）
bc439d2 docs(camera-mock): §55 找到后置 1080p 录像面 3,219,456（存活探针+变焦制造变化）
c549b1e docs(camera-mock): §56 验收清单（可照跑 + 判据数字）+ 一句话总结当前交付
```

### 57.4 一句话状态（归档时的真相）

**已通且实测稳定**：前置**预览 / 照片 / 录像**三条 + 后置**预览 / 照片**两条 + **模块开机自部署**。
**未通**：**后置录像**（1080p 与 4K 都是厂商**压缩面**，线性/瓦片写入会被解成绿或黑）。
**待验证**：小时级 soak；人像/专业/60fps/前置 4K 等模式。

---

## 58. VVideo 统一取帧层接入（本次）

完整设计、ABI 与自测见 **[`docs/vvideo.md`](vvideo.md)**（形状照 Linux VFS：
super_block/inode/dentry/file + 操作表 + 挂载表 + 路径解析）。这里只记**本次落地与判据**。

### 58.1 改了什么

`camshim.c` 只在**模板加载**这一处接进来：`mock_map` 里以 `/vvideo/` 开头的路径
经统一层取帧（**零拷贝**，节点自己的内存），其余路径仍直接 `open()`。默认 `vvideo=0`，
行为与接入前逐字节一致。

```ini
vvideo=1
vv_mount=raw:/vvideo/src/front-preview:/data/vendor/camera/ffront.raw:w=2176,h=1256,stride=2176|raw:/vvideo/src/front-video:/data/vendor/camera/fvidmock.raw:w=1920,h=1088,stride=1920
mock_map=4038656:/vvideo/src/front-preview|3194880:/vvideo/src/front-video
```

两条硬约束（项目要求）：**数据流不过物理磁盘**（`raw` 挂载时读一次就 `close(fd)`；
`shm` 走 `memfd` 匿名共享内存）、**事件驱动不轮询**（消费者 `FUTEX_WAIT` 睡在共享事件字上，
无新帧时零 CPU；静态源 `always_ready`，`wait` 立刻返回，不制造虚假节拍）。

### 58.2 开发机自测：100 项全过（ASan/UBSan 干净）

`sh scripts/test-vvideo.sh`。**自测抓到 4 个真 bug**，都记在 `docs/vvideo.md` §7.2，
其中两个的性质值得在本文件里重述一遍：

| bug | 症状 | 教训 |
|---|---|---|
| `vv_seq_printf` 静默截断在 1023 字节 | `ctl/tree`、`ctl/log` 永远只有一屏 | "看起来就是这样"式故障 —— 本项目最怕的一类 |
| 路径解析不穿挂载点 | `open()` 拿到挂载点那个空目录 ⇒ 取帧永远 `-ENOTTY` | VFS 里挂载是**覆盖**，不是"并列" |
| 卸载 double free | ASan 当场报 | 递归释放函数自己会释放子节点 |
| 变换链分隔符与挂载选项分隔符撞车 | `op=rot90cw+flipud` 曾被写成逗号 ⇒ 后半个 op **静默丢失** | 表现是"画面对了但方向不对" |

### 58.3 真机判据（前置，`cam=1`）

| 项 | 判据 |
|---|---|
| 层在 HAL 里活起来 | `VVideo 统一层已就绪：挂载 2 个节点（失败 0），ABI v1` |
| 两个落地节点都经层取帧 | `注入：模板 ← /vvideo/src/front-preview（**经 VVideo 统一层**，后端=raw，4038656 字节，零拷贝）`；`.../front-video（3194880 字节）` |
| **A/B 等价**（前置预览） | 老路 vs 新路两张截图 `DIFF mean=0.00 std=0.00 max=0`（逐像素相同）；对照组日志 `经 VVideo` 出现 **0** 次 |
| **前置录像** | 8.0 s / 239 帧 @30fps / 1080×1920；抽 3 帧统计一致（静态卡片）；**目视 = 我们的前置录像测试卡**，TOP/BOTTOM 与四角手性标记方向正确（未镜像、未旋转） |
| 注入靶 | `注入线程：10 块缓冲，共 31948800 字节（2 个模板）`（3,194,880 的前置录像面） |

### 58.4 尚未接进来的

- **照片路径**、`vcam.c`（自建虚拟摄像头）未接入统一层；
- **`shm` 的跨进程用法**（app 产帧 → HAL 取帧）未接 —— 设备上仍是老的 `cammock.frames`
  通道（`portal_fsrc_*`）；`shm` 目前只在 host 自测里验证过（memfd + futex）；
- `xform` 的色度平面变换（`chroma=1`）与每帧变换（`cache=0`）的性能均未在真机上量过。

---

## 59. 事件驱动重扫 + 开门闸（本次）

两件事：**把"换了模式不知道"从 5 秒定时改成事件驱动**（已通），以及**开门闸**（机制做完，
但在本 ROM 上找不到可安全挂钩的每帧入口 —— 现场是两次 SIGILL，记在 §59.4）。

### 59.1 事件驱动的重扫（已通，真机实测）

老代码：`if (announced && now - last_scan >= 5) { munmap 全部; n = 0; }` ——
**每 5 秒无条件重扫一次**，即使什么都没变也要把一批 4MB 的 dmabuf 重新 mmap 一遍；
而"换成录像缓冲"这件事最坏要等 5 秒才跟上。

新做法：在**配置类入口**上挂一个通用钩子，命中就 `FUTEX_WAKE` 唤醒注入线程；
注入线程热路径上只做一次**非阻塞探询**（比一个 seq，零系统调用），15 秒只是安全带。

```ini
vv_stream_vaddr=0x6c554          # 配置类入口（oemlayer configureStreams_Impl）
vv_rescan_delay_us=30000         # 事件后等这么久再扫（给"新缓冲落地"留时间）
```

真机日志（前置 → 录像）：

```
14:03:59.307 注入线程：重扫目标（触发=事件(configureStreams)，上一批 9 块）
14:03:59.310 注入线程：目标集与上次相同（9 块）⇒ 新缓冲可能还没落地，500 毫秒后补看（第 1/3 次）
14:04:00.465 注入线程：10 块缓冲，共 31948800 字节（2 个模板，同步=0）      ← 3,194,880 的录像面
```

三个要点，都是实测逼出来的：

1. **钩子在 configureStreams 的*入口*** —— 事件到时新缓冲**还没分配**。
   所以"立刻重扫"拿到的仍是旧的一批（这条与 §54.1 的"挂早一步"是同一件事）。
   做法：事件只**排期**（+30 ms），并做**有界补看**（0.5 / 1 / 2 秒，最多 3 次），
   判据是**目标集有没有变** —— 变了就收工，没变才补。不发生时一秒都不醒。
2. **`req_vaddrs` / `hook_rec_vaddrs` 到不了 C**：那两套装的是"纯汇编记账"桩
   （为了避开加载期 PLT 懒绑定死锁，一条 `call` 都没有）。所以"命中时通知"必须另装
   **通用桩** —— 这就是 `vv_stream_vaddr` 存在的原因。
3. **顺带修掉的两个真问题**：`last_scan` 在首建目标后没有重置 ⇒ 同一批目标 5 毫秒内连建三次；
   钩子槽位分配器不认识"别人已经占掉的槽" ⇒ `req` 钩子会占掉改用途位钩子的槽，
   于是 `kind == g_scrub_slot` 这条判断被**另一个函数**的调用触发（把 AIDL 的
   `StreamConfiguration` 当 `camera3_stream_configuration_t` 解引用）。

### 59.2 开门闸：机制

需求：**检测到应用请求摄像头时，先别放帧，等我们填好再放**，避免开头泄漏真实画面。

机制（`gate=1` + `vv_gate_vaddr=<每帧入口>`）：

```
请求进入 → 若注入还没写过这一代的第一帧 ⇒ FUTEX_WAIT 睡在 /vvideo/ev/armed 上
        → 注入线程写好第一帧 ⇒ seq++ + FUTEX_WAKE → 请求继续
```

- **事件驱动**：等待是 futex，不轮询；已放行后热路径只剩两次原子读、零系统调用。
- **fail-open**：等不到（默认 300 ms 上限）就放行并记账 —— 宁可开头漏几帧，
  也绝不能让用户面对一台打不开相机的手机。实测本 ROM 的 `gate_timeout_ms=1500` 会让
  应用明显变慢，所以默认值取小。
- **只拦目标相机**：`cam=` 之外的相机立刻放行（否则"只打前置"的配置会让后置也慢 1.5 秒）。
- **每一代缓冲都重新关门**：重配流之后的新缓冲同样不许漏真画面。

配套：`vvfs-notify` 后端（"VVideo 里的 eventfd"，写一次 = 发一次信号、信号不累积），
自测里 13 条断言覆盖（非阻塞探询、不累积、被唤醒的时延、write 与 NOTE 等价）。

### 59.3 顺带修掉的一个 HAL 崩溃（真机 tombstone）

`portal_dump_handle` / `portal_dump_handle_fds` **直接解引用**请求快照里记下的指针，
而它们跑在**汇报线程**上 —— 那时缓冲可能早就释放了：

```
signal 11 → #00 portal_dump_handle+80 ← #01 portal_req_report+464 ← #02 portal_report_thread
```

改成一律经 `portal_peek`（`/proc/self/mem`）读：**"指针过期"从崩溃降级成少一行日志**。
同一个包里还加了**入口补丁回读校验**（写进去的 16 字节读回来比一遍才认），
以及**装不上就把槽位还回去**（原来失败路径会把名字留着，导致按名字判定的标记认一个不存在的钩子）。

### 59.4 开门闸在本 ROM 上装不了（两次 SIGILL，现场如下）

| 项 | 内容 |
|---|---|
| 目标 | `camx.device-impl.so:0x3771c` = `CameraDeviceSession::processCaptureRequest`（符号表查出来的，不是猜的偏移） |
| 现象 | 补丁**写进去了、回读也一致**，但应用一开相机，provider 立刻 SIGILL |
| tombstone | `signal 4 (SIGILL), code 1 (ILL_ILLOPC)`，`fault addr` **正好是被打补丁的那个地址**；`#00 camx.device-impl.so+0x3771c (processCaptureRequest+0)` |
| 根因（推断，证据充分） | 该 ROM 的 vendor 代码带 **BTI**：入口第一条本来是 `paciasp`（BTI 认可的**调用落点**）。我们的 16 字节补丁把落点换成了 `ldr x16,#8`，而它是**间接调用**进来的（AIDL `onTransact` → 函数指针），于是 BTI 检查在入口失败 ⇒ SIGILL 的 `fault addr` 就是入口本身。对照：`configureStreams_Impl` 出自同门、第一条同样是 `paciasp`，但它是**直接 `bl`** 调用的（BTI 不检查直接分支），所以能挂。 |
| 结论 | **每帧的请求入口在本 ROM 上不能这样挂钩**。`gate` 因此保持默认关闭。 |
| 下一步有两条路 | ① 把补丁做成 **BTI 兼容**（20 字节：`bti c` + `ldr/br` + 地址，重放 5 条而不是 4 条）；② 找一个**冷但分配之后**的入口，或者干脆不做闸门，改为把"注入线程的接管"提到首帧之前（下面的 59.5）。 |

### 59.5 关于"开头泄漏"的另一半

即使闸门装上，`processCaptureRequest` 之前的**缓冲分配**也已经发生了；所以真正缩短泄漏窗口的
另一条路是**让注入更早接管**：配置事件后做**有界快速重扫**（而不是等 0.5 秒的补看），
趁应用还没拿到第一帧结果就把缓冲覆盖掉。这条不依赖任何新钩子，是下一步的首选。

---

## 60. 方案 2：探询窗口 —— 把"开头泄漏"压掉（已通，真机实测）

§59.4 定论：本 ROM 上每帧请求入口**不能**挂钩（BTI + 间接调用 ⇒ SIGILL），
所以放弃闸门，改走"**让注入更早接管**"。核心洞察不是"扫得更勤"，而是：

> **等新缓冲的时候绝不能停手。**

老做法（§59.1）是事件一到就把靶全部 `munmap`（`n = 0`），等 30ms~1s 才重建 ——
那段真空期没人往缓冲里写，应用拿到的就是真画面。**那不是"跟不上"，而是我们自己制造的空窗。**

### 60.1 做法

把"重建"拆成**便宜的一步**与**贵的一步**，中间用指纹判断要不要做贵的那步：

| 步骤 | 成本 | 频率 |
|---|---|---|
| `portal_fd_signature()`：`opendir`/`readlink`/`fstat`，**不 mmap** | ~0.2 ms | 配置事件后的窗口内每 15 ms |
| `portal_build_targets()`：每块 4MB 的 dmabuf 重新 mmap | 数 ms～数十 ms | **只在指纹真的变了之后** |

指纹用 **`(st_ino, st_size)`** 而不是只用尺寸：重配流时框架常常**用同样的尺寸重新分配**
一批缓冲（fd 号还可能被复用），只看尺寸会得出"没变"，于是继续往已作废的那批里写。

窗口只由**配置事件**武装（`vv_burst_ms=3000`），窗口内**继续写旧靶**（陈旧但仍是我们的卡片，
不是实景）；指纹一变就换靶；窗口结束就回到"一动不动"的稳态
（唯一的周期动作是 15 秒一次**廉价核对** —— 它取代了原来"每 5 秒无条件 munmap+重建 36MB"）。

### 60.2 真机实测：三次换靶，每次 1–35 ms

```
14:20:55.227  收到流变更事件 ⇒ 探询窗口开到 3000 ms（期间**继续写旧缓冲**）
14:20:55.357  靶集变了（9 块/36347904 → 0 块/0）⇒ 换靶        (+130 ms)
14:20:55.378  3 块缓冲，共 9584640 字节                      (+21 ms)
14:20:55.413  靶集变了（3 块 → 8 块）⇒ 换靶                   (+35 ms)
14:20:55.679  靶集变了（8 块 → 10 块）⇒ 换靶                  (+265 ms)
14:20:55.681  10 块缓冲，共 31948800 字节（= 3,194,880 的录像面）
14:20:58.218  探询窗口结束，靶集始终没变（10 块）
```

一轮模式切换里靶集连着变了三次（旧靶释放 → 3 → 8 → 10），**每一次都在变化出现后
1–35 毫秒内被跟上**；而在此之前同样的序列要 ~2 秒（一次盲目丢靶 + 一次 2 秒等待）。

### 60.3 验收判据：录像开头不再有真实画面

冷启动 → 视频页 → 前置 → 录像 5 秒，抽前 12 帧与"参考帧"（第 12 帧，必是我们的卡片）
逐像素比：

```
001.png  mean=62.3 std=57.2  |diff-ref| 均值=0.14  >16 的比例=0.00%
002.png  mean=62.3 std=57.2  |diff-ref| 均值=0.09  >16 的比例=0.00%
...
012.png  mean=62.3 std=57.2  |diff-ref| 均值=0.00  >16 的比例=0.00%
```

**从录像第 1 帧起就是我们的卡片**，没有任何一帧是真画面。
（判据选"与参考帧逐像素差"而不是均值/方差：实景与卡片可能均值接近，
但逐像素差不会骗人。）

### 60.4 与闸门的关系

闸门（§59.2）的机制仍然留在代码里、默认关闭，作为"将来某个 ROM 上找到可安全挂钩的
每帧入口"时的现成零件；本 ROM 走的是本节的探询窗口 —— **零新增挂钩、零热路径补丁**。

---

## 61. 冷启动回归脚本 + 它当场抓到的一件事

### 61.1 脚本：`scripts/test-video-start.sh`

判据从"人肉跑一遍看一眼"变成一条命令。载体是 **PortalEX 自己的录制页**
（`CameraRecordActivity`）——它默认前置（与 `cam=1` 对齐）、录像落在应用私有目录
（"这次新出现的那个文件"就是判据的适用范围）、按钮位置由 `uiautomator dump`
**按资源 id 现查**（不写死坐标）。

**判据是两段录像**，自标定、与机型模板无关：

| 段 | 配置 | 参考帧 |
|---|---|---|
| A | 正常注入 | A 末帧 = 我们填的画面 |
| B | 不注入（纯转发） | B 末帧 = 真实画面 |

1. `D = |A末 − B末|` 必须**大** —— 证明确实"注入改变了画面"；
2. `d[i] = |A 段第 i 帧 − A末|` 必须**小** —— 证明**从第一帧起**就是改变后的画面。

阈值取比值（`d ≤ max(4, D×0.12)`），换机型不用重标定。

**为什么必须是两段**（这一条是负对照逼出来的）：第一版拿同一段录像的末帧当参考，
只比"开头 vs 结尾"。而整段都是实景时帧间本来就几乎一样 ⇒ **判据必然通过**。
一个不会失败的回归比没有回归更糟；现在"注入根本没生效"会被 `D ≈ 0` 拦住。

### 61.2 它当场抓到的：用 PortalEX 录制页时**注入没有生效**

第一次跑就红：`D = 2.08`（要求 ≥20）。查证过程与结论：

| 观察 | 事实 |
|---|---|
| 录像内容 | **真画面**（天花板/木墙/灯），不是我们填的 |
| 应用开的相机 | `shim_open id=1`（前置，与 `cam=1` 一致）✓ |
| 注入线程 | 一开始一直报"**还没靶可打**" |
| 量出的真实缓冲 | 当时记成"录像缓冲是 **2,400,256**" —— **归属记错了，见 §62.3：2,400,256 是那条会话的*预览*面** |
| 补上 2,400,256 的源（`gen` 后端现造）后 | 注入线程**确实认领了** `10 块缓冲，共 24002560 字节`，并跟着三次换靶走完 |
| 再录 | **仍然是真画面** ⇒ 写进去了但没生效 |

也就是说：**同一个 shim，在系统相机会话里能接管录像面（§58/§60 的实测），
在 PortalEX 录制页这条会话里不能。** 这与 §55 那条已知事实很可能同源 ——
后置录像面是厂商 **UBWC 压缩面**，线性写入会被解成噪声或干脆不生效。

**下一步要查的（二选一，都能一条实验定死）** —— **已结案，见 §62**：
1. 把注入线程认领到的那批缓冲的 **gralloc usage/format** 打出来 —— 有 `GRALLOC_USAGE_PRIVATE_0`
   （UBWC）就说明这条面也是压缩面，线性写注定不生效；
2. 加 `usage_patch` / `scrub_ol_vaddr` 走一遍：若缓冲尺寸随之变化（压缩面退回线性时尺寸会变），
   就当场证实了 UBWC。

### 61.3 顺带修掉的脚本级坑（每条都让"通过"变成假象）

| 坑 | 表现 | 修法 |
|---|---|---|
| 拿上一次的录像当本次结果 | **整个测试假通过** | 记录测试前的文件清单，只认新出现的；找不到就失败 |
| `tr '>' '>\n'` 根本不换行（tr 映射字符集） | sed 贪婪匹配到别的元素，点击落在状态栏 | 改按 `<node` 切行 |
| 录像目录属主被 root 改过 | 应用建文件 `EACCES`，录像静默地一次都没开始 | 按应用 files/ 的属主 `chown -R` 修回去 |
| 拿"末帧"当绝对参考 | 整段实景也判"通过" | 改成 A/B 两段的自标定判据 |

---

## 62. 应用录制页的编码器面：**量出来了、也当场看见了"压缩"**（§61.2 结案）

### 62.1 四个量具（最后一个是本轮新造的）

| 量具 | 做什么 | 备注 |
|---|---|---|
| `dump=1` 流表 | 打印**会话里的流**：宽高 / format / usage / max_buffers | 已在用，§61.2 那轮只是没读 usage |
| **dmabuf 尺寸直方图** | 把 provider 手上**所有** dmabuf 的 `size:` 列出来（读 `/proc/<pid>/fdinfo/<fd>`） | **新量具：不用重编 shim** —— 一个 `for` 循环就知道"这块面到底多大" |
| 直方图的**差分** | 预览态 vs 录制态两份相减 | 只在录制态出现的尺寸 = 编码器面的缓冲 |
| `raw_dump=<尺寸>:<路径>` | 把该尺寸的**活帧**原样落盘（实际文件名是 `<路径>.<尺寸>`） | 判布局：线性 / 瓦片 / 压缩一眼可分 |

### 62.2 结果（PortalEX 录制页 · 前置 · `cam=1`）

同一会话的 `dump=1` 流表：

| 输出 | 流 | usage | 解读 |
|---|---|---|---|
| `out[0]` | `1920x1080 format=34(0x22) max=8` | **`0x10030322`** | **编码器（录像）流**：含 `0x10000`(HW_VIDEO_ENCODER)、含 `0x10000000`(UBWC) |
| `out[1]` | `1200x1200 format=34(0x22) max=8` | `0x10020100` | 预览流：同样含 UBWC 位 |

provider dmabuf 直方图的差分（预览态 → 录制态）：

| 尺寸 | 预览态 | 录制态 | 结论 |
|---|---|---|---|
| **`3,219,456`** | **0** | **16** | **编码器面的缓冲**（MediaCodec 输入面） |
| `2,400,256` | 10 | 10 | **预览面**（两态都在 ⇒ 与录像无关） |
| `4,038,656` | 视会话 | 视会话 | 系统相机的前置预览面（同一个池子里常同时存在） |

`raw_dump=3219456` 落下来的活帧（3,219,456 字节）判布局：

| 假设 | 结果 |
|---|---|
| 线性 NV12、pitch 1920（Y 面 1088 行） | NCC(真景) = **−0.011**，图像是**竖直的压缩噪声带** |
| 瓦片反排（32×8 瓦片、宏瓦片 4×4、两种 within-macro-tile 序） | NCC(真景) = **+0.001 / −0.004** |
| 同一 dump 的段均值（每 1/8） | 44/44/42/38/38/33/32/28（暗、无结构） |

⇒ **数据不在任何固定偏移上** = **UBWC 压缩**（压缩模式下整块内存由元数据重新解释）。
§46 那套瓦片写法对它无效 —— 瓦片只解决"未压缩但 tiled"的面。

### 62.3 顺手纠一处 §61.2 的归属错误

§61.2 把 `2,400,256` 记成"应用那条会话的**录像**缓冲"。差分量具证明它是**预览**面
（预览态 10 块、录制态还是 10 块）。真正的录像面是 `3,219,456`（16 块）。
于是那轮"补上 2,400,256 后认领了 10 块、录像仍不变"**不是**"写进了录像面却没生效"，
而是"写进了预览面，录像面根本没被认领"。

### 62.4 为什么系统相机能、应用不能（本轮的真答案）

| 会话 | 录像面 | 布局 | 注入 |
|---|---|---|---|
| **系统相机**录像 | `3,194,880` | **线性**（§55 实测：真帧落盘一次就解出干净图） | ✅ §58/§60 实测 "100% 帧" |
| **应用**（MediaCodec 输入面）录像 | `3,219,456` | **UBWC 压缩**（§62.2） | ❌ 线性写不可能生效 |

差别不在"前置/后置"，而在**谁分配这块面**：系统相机用相机栈自己的缓冲（线性），
应用用 **MediaCodec 的输入面**（客户端请求时带了 UBWC 位 ⇒ gralloc 给压缩分配）。
ACE5 上 `usage_patch` 不可用（真 HAL 没有导出的 `configure_streams`），
所以"把面改回线性"这条路在 HAL 侧不存在。

### 62.5 系统层开关的实测：`vendor.gralloc.disable_ubwc`

本机存在 Qualcomm 官方的 gralloc 开关 `vendor.gralloc.disable_ubwc`（默认 `0`）——
纯系统层、无注入，值得一试。实测：

| 步骤 | 结果 |
|---|---|
| `setprop vendor.gralloc.disable_ubwc 1` + 重启 provider | dump 仍是压缩噪声（NCC ≈ −0.018） |
| 再 `setprop ctl.restart media.swcodec`（让分配进程重新加载 gralloc）+ 重启 provider | dump 仍是压缩噪声（NCC ≈ −0.030） |

⇒ **只靠 `setprop` 改不动这条分配路径**（属性在分配器加载时读一次；这条面还可能是别的进程分配的）。
下一步：把它**持久化后整机重启**，让分配器从头按它加载，再量一次（见 62.6 的第 2 条）。

### 62.6 复现命令（本轮全套）

```bash
# 0) 差分量具（已入库）：起录制页 → 预览态采一次直方图 → 按资源 id 点录制 → 录制态再采一次 → 相减
#    它自己会把设备侧的小脚本推到 /data/local/tmp/portalex-inv.sh
sh scripts/camera-dmabuf-diff.sh 8

# 1) 部署（与 §56.1 同一套，只多一个 raw_dump）
adb shell sh /data/local/tmp/deploy.sh \
  '...;cam=1;inject=1;inject_us=0;mock_map=4038656:/data/vendor/camera/ffront.raw|3194880:/data/vendor/camera/fvidmock.raw;raw_dump=3219456:/data/vendor/camera/rec_lin.raw'

# 2) 再录一次（按钮坐标每次按资源 id 现查；不要写死坐标，模式条会随状态平移）

# 3) 把活帧拉下来：按"线性/瓦片"两种假设各算一次与真景（录像里抽帧）的 NCC，见 §62.2 表
adb pull /data/vendor/camera/rec_lin.raw.3219456 /tmp/rec_lin.raw

# 4) 收尾：属性复位、cfg 去掉 raw_dump（否则线程会反复写 3MB 文件）
adb shell 'setprop vendor.gralloc.disable_ubwc 0'
```

**下一轮的入口（按代价排序）**：
1. **让编码器面退回线性**：把 `vendor.gralloc.disable_ubwc=1` 持久化（模块 `post-fs-data.sh` 里
   `resetprop`）+ 整机重启 → 重跑 §62.2。若面变线性，注入立刻可用，而且全程**零注入**（符合项目红线）；
2. 若不退：只剩 **GPU 写**那一条（§30.4 的首选）：shim 里已有 M1b 的 `EGLImage`/GL 合成段，
   让 GPU 把我们的画面 blit 进压缩面 —— 排布与元数据交给 GPU，我们不必自己实现压缩器。

---

## 63. GPU 写入这条路：**两扇门都量过了**（本轮新增，cfg 默认关闭）

§62.6 把 GPU 写列为"若线性化不成，就只剩它"。本轮把它实现到"能跑、能报错、能自证"的程度，
并把两扇门各自量了一次。**结论：在本机（一加 ACE5 / ColorOS 16 / Adreno）这两条路目前都不通**，
但每一处"不通"都留下了**可复现的错误码与证据**，不是猜测。

### 63.1 新增的 cfg（全部默认关闭，不动现役那套按尺寸注入）

| cfg | 作用 |
|---|---|
| `gpu=1` | 对 `gpu_size=` 那块靶改用 **GPU 画**（不再 CPU memcpy） |
| `gpu_size=<字节>` | 走 GPU 的靶（按尺寸选，理由同 `mock_map`） |
| `gpu_w=` / `gpu_h=` | 那块靶的几何（建 EGLImage 要用），默认 1920×1080 |

配套：注入线程现在会**按尺寸认领 GPU 靶**（不需要模板文件），并给每块靶留住 `fd`
（`EGL_LINUX_DMA_BUF_EXT` 只认 fd）；GPU 靶**故意不退回 CPU 写** —— 线性数据进压缩面只会变成噪声，
那会让"A/B 判据看到的画面变化"到底是 GPU 生效还是噪声变得说不清。

### 63.2 门一：`EGL_LINUX_DMA_BUF_EXT`（用 fd 直接建 EGLImage）—— **驱动不支持**

做法：拿注入线程手上的 dmabuf fd，按 8 组属性组合试 `eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT)`：
`NV12 + 无modifier/modifier=QCOM_COMPRESSED/QCOM_TILE/linear` × `pitch=w/a128` × `单平面/双平面`。

| 观察 | 结果 |
|---|---|
| EGL/GL 在 **provider 进程内**能不能用 | ✅ 能：`GPU 注入就绪：EGL 1.5 display=… context=…`（这是本轮最先要回答的未知） |
| 8 组组合 | **全部失败，`eglGetError=0x300c` = `EGL_BAD_PARAMETER`** |
| 根因 | **本机 EGL 扩展串（全长 1247 字符）里没有 `EGL_EXT_image_dma_buf_import`** |

⚠️ **本轮踩到的坑（值得单记）**：第一版把扩展串整串打出来，logcat 单行约 1KB 就截断，
前半段恰好"看起来"像缺了这一项 —— 于是差点得出"扩展不存在"的**正确结论、错误理由**。
改成**分块打印 + 关键字布尔判定**后才有硬证据：

```
EGL 关键词：dma_buf_import=0 modifiers=0 native_buffer=1 image_base=1
```

### 63.3 门二：`EGL_ANDROID_image_native_buffer`（用 gralloc 句柄）—— **拿不到句柄**

这条是本机唯一可用的 GPU 门（`native_buffer=1`、`image_base=1`）。它要的是**真 gralloc 句柄**。
本轮把"句柄在不在请求内存里"这个问题**用自证判据**量了：

- 判据：句柄自报的某个 fd 的 `fstat` 尺寸 **必须等于**要接管的靶尺寸（3,219,456）——
  尺寸对上才算找到，**不靠猜结构体偏移**；搜索做**两层指针追踪**（AIDL 的 `NativeHandle` 是一层壳）。
- 请求条目的布局（**实测**，窗口打印）：`+0 流指针`、`+8 句柄位`、`+16 status|acquire`、
  `+24 release`、`+32 priv`、`+48 起是字符串`（`…android.hardware…`）。
- `+8` 那个值指向的是一个**包装对象**，不是 `native_handle_t`（头三字 `v=0 nFds=0 nInts=<指针低位>`）。
  它的前 48 字节：`+0=0 +8=<堆指针> +16=0x1_00000035 +24=0x1 +32=<堆指针> +40=0x1_00000035`。
- 结果：**预览请求与编码器请求都探过**（探针线程确认采到 `1920x1080 format=34 usage=0x10030322`
  那一条），**两层指针图里没有任何带该 fd/尺寸的句柄**。

**为什么要专门写个探针线程**：请求快照是**汇编钩子**填的、只有一个槽位，
而汇报线程 5/20 秒才读一次 —— 实测两次采样都落在"预览请求"（`nout=1`）上，
真正的编码器请求（`nout=2`）根本没被看见。所以加了 `portal_gpu_probe_thread`：
每毫秒轮询快照、对每个条目跑一次搜索，找到就退出、找不到也自限次数（不留常驻线程）。

### 63.4 于是"录像面"这件事的现状（三条路，各自的硬证据）

| 路 | 状态 | 证据 |
|---|---|---|
| CPU 线性写 | ❌ 物理上不行 | §62：编码器面是 UBWC 压缩面，线性数据被解成噪声 |
| 让面退回线性（`vendor.gralloc.disable_ubwc`） | ❌ 只 `setprop` 改不动 | §62.5：重启 provider / 重启 codec 服务后仍是压缩面 |
| GPU 写：fd → EGLImage | ❌ 驱动没有该扩展 | §63.2：`dma_buf_import=0`，8 组组合全 `EGL_BAD_PARAMETER` |
| GPU 写：gralloc 句柄 | ❌ 请求内存里拿不到句柄 | §63.3：两层追踪 + 尺寸自证，预览/编码器两条都探过 |

**还没试过的（下一轮的入口，按代价）**：
1. **在 provider 进程里按"fd 号"扫堆**找句柄：`/proc/self/maps` 的可读区里搜
   `[version∈{12,16,20,24}, numFds∈1..8, numInts∈0..64]` 且紧跟的整数字段里有我们那个 fd 号
   的 `native_handle_t` —— 一次性的有界扫描（进程堆几百 MB，按 GB/s 算是秒级），判据同样是自证；
2. **钩 AIDL→camera3 的转换点**：句柄在 `camx.provider-impl.so` 里被搬进 `camera3_stream_buffer_t`
   之前的形态是 AIDL 结构，那里一定拿得到真句柄（代价：要再找一次入口地址，同 `hook_rec_vaddrs` 的做法）；
3. **承认 HAL 侧写不了编码器面**，把"给目标 App 的录像内容"继续交给已经验证过的
   **app 层 relay 通道**（master 分支那条线，§5.7/§5.8），HAL 只负责它已经能做到的
   （预览/照片/系统相机录像面）。

### 63.5 复现命令

```bash
# 部署（GPU 关 = 现役那套；GPU 开 = 本轮这套）
adb shell sh /data/local/tmp/deploy.sh '...;cam=1;inject=1;inject_us=0;gpu=1;gpu_size=3219456;gpu_w=1920;gpu_h=1080'
# 看两扇门与句柄探针
adb shell "logcat -d -s PortalCamShim:V" | grep -E 'EGL 关键词|GPU 建图像|GPU 探针|GPU：'
```

### 63.6 第 9 轮：**扫堆找到了句柄，但 EGL 还是不收**（A/B 证明不是压缩的锅）

§63.4 的第 ① 条（按 fd 号扫自己的堆）**实现了，而且有效**。判据仍然是双重自证：
候选 `native_handle_t` 的 fd 数组里必须有一个**正好是注入线程认领的那块靶的 fd**，
且 `fstat` 尺寸复核通过。

**两个面都扫到了句柄（同一形态）**：

| 靶 | 靶尺寸 | 扫堆结果 | 扫了多少 |
|---|---|---|---|
| 应用录制页的编码器面（压缩） | 3,219,456 | `命中 0x…380（version=12 numFds=2 numInts=26，fd=248 尺寸复核通过）` | 命中即停 |
| 系统相机前置预览面（**线性**、已知可 CPU 注入） | 4,038,656 | `命中 0x…000（version=12 numFds=2 numInts=26，fd=203 尺寸复核通过）` | 命中即停 |

**但 `eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID, handle)` 两边都失败**：
`eglGetError=0x300c`（`EGL_BAD_PARAMETER`）。

⇒ **卡点不是"压缩面"**（否则线性的那个面应该能过）。剩下三种可能，必须再做一次实验才能分开：

| 可能 | 判别实验 |
|---|---|
| (a) 扫到的那个 `native_handle_t` 不是"驱动认识的那一个"（例如是句柄的副本/别的消费者视图） | 用 `AHardwareBuffer_createFromHandle()` 试同一个句柄：**能建出 AHardwareBuffer** ⇒ 句柄本身合法；再用 `eglGetNativeClientBufferANDROID()` 走 AHardwareBuffer → EGLImage |
| (b) 本 ROM 的 Adreno EGL 在**这个进程**里根本不接受 native buffer 导入（mapper 权限/进程上下文） | 在同一进程里试 `EGL_KHR_image_base` + `EGL_ANDROID_image_native_buffer` 的最小用例（比如拿一块 **自己用 gralloc 分配的** buffer 去建 EGLImage）：能成 ⇒ 是我们的句柄不对，不成 ⇒ 是进程/驱动层面 |
| (c) 属性表写错 | 已被 3 组属性（none / PRESERVED=1 / PRESERVED=0）覆盖，且 dmabuf 路线的 8 组也全失败 ⇒ 可能性最低 |

### 63.7 本轮顺带修掉的两个真 bug（都是"让诊断消失"的那种）

| bug | 症状 | 修法 |
|---|---|---|
| **GPU 失败日志把 logcat 冲爆** | 每轮 8 行 × 30fps ⇒ 环形缓冲被刷穿，**别的诊断行（探针/扫堆）被挤掉**，表现为"代码明明在跑却一条日志都没有"（本轮为此白跑了三轮） | 组合尝试日志改成**只报一次**（`g_gpu_combo_logged`）；测设备时把缓冲调到 `logcat -G 64M` |
| **跨线程计数非原子读** | 扫堆线程里 `while (g_gpu_target_n == 0) sleep(1)` 用普通读，优化后可能被提出循环 ⇒ 永远等不到靶 | 改 `__atomic_load_n(&g_gpu_target_n, __ATOMIC_ACQUIRE)` |

**教训**：在"HAL 里做实验"这种场景，**日志可观测性本身就是实验设备的一部分** ——
一段高频日志足以让后面所有结论变成"没看见=不存在"。

### 63.8 第 10 轮判别实验：**(b) 被排除** —— 本进程能导入 native buffer；问题在"我们扫到的那个句柄"

用 NDK 的 `AHardwareBuffer` 当对照组（**注意**：Android 10+ 起 `AHardwareBuffer_*` 由
**libnativewindow.so** 提供，vendor 命名空间里 `dlopen("libandroid.so")` 会失败 —— 实测踩到）：

**(b) 对照组：框架自己分配的 buffer**
```
GPU 自检：AHardwareBuffer 来自 libnativewindow.so
GPU 自检(b)：AHardwareBuffer_allocate(640x480 RGBA) rc=0
GPU 自检(b)：eglGetNativeClientBufferANDROID → 0xb40000762767a010
GPU 自检(b)：eglCreateImageKHR(native buffer) → 0xb400007624f9df80（eglGetError=0x3000）
```
`0x3000` = **EGL_SUCCESS** ⇒ **这个进程里 native buffer 导入是通的**。
⇒ §63.6 的可能 (b) **排除**；也纠正了 §63.2/§63.3 的隐含结论（"EGL 不收"是错的，它**不认那个句柄**）。

**(a) 扫到的句柄**
```
GPU 自检(a)：句柄 0x760779e100 头 version=12 numFds=2 numInts=26
GPU 自检(a)：AHardwareBuffer_createFromHandle rc=-22（buf=0x0）
```
`-22` = `-EINVAL`：框架的 `createFromHandle` **不接受**这个句柄。
两种解释仍需分开：句柄本身不是 buffer 句柄（只是恰好含一个尺寸相同的 fd 的结构），
或者我们的 `desc`（`1920x1080 / layers=1 / format=0x22 / usage=0x10030322`）与真实分配不符。
**⇒ 扫描器的判据还不够**：只"fd 号 + 尺寸"自证会**误收**；下一轮要加一条更硬的判据。

**下一轮的扫描器加固（按代价）**：
1. 先拿**已知合法**的句柄做形态基准：`AHardwareBuffer_getNativeHandle(const AHardwareBuffer*)`
   （⚠️ 它**返回指针、没有出参**——本轮我按"出参"签名调，拿到的是垃圾 `rc=437175936`，记下来别重犯），
   把框架自己那块 640×480 的句柄头（`version/numFds/numInts`）打出来与 `12/2/26` **比形态**；
2. 再加一条硬判据：句柄的**第一个 fd 必须与注入线程 mmap 的那块靶是同一个 dmabuf**
   （比对 `fdinfo` 里的 `ino`/`size`，或 `DMA_BUF_IOCTL` 的 name）—— 不是"尺寸相同"而是"同一块"；
3. 若加固后仍 `-EINVAL`，则结论是**请求路径上与 HAL 打交道的那批 buffer 没有可被 EGL 导入的句柄**，
   GPU 线就此收束（转 §63.4 的 ③：录像内容交给 app 层 relay）。

**本轮顺带记下的两个"实验设备"级坑**：① `AHardwareBuffer_getNativeHandle` 的签名（返回值不是错误码）；
② 自检里 `portal_gpu_fill()` 没先 `eglMakeCurrent()` ⇒ FBO 必然不完整（这条"失败"是量具的问题，不是被测对象的问题）。

### 63.9 第 11 轮：形态基准一致、硬判据不加不减，但**原始句柄仍被 EGL 拒收**（AHB 路线却是通的）

**扫描器加固**：判据从"fd 号 + 尺寸"升级为"**同一块 dma-buf**"——比 `fstat().st_ino`
（同一个 dma-buf 的 fd 可以被 dup 成很多份：fd 号不同、**inode 相同**；尺寸也可能碰巧一样）。
登记与判据都在注入线程认领靶时完成（`portal_gpu_note_target_fd` 记录 fd + ino）。

**形态基准（本轮新增的对照，最有价值的一条）**：
```
GPU 自检(b) ★形态基准：句柄 0xb400007596d51480 头 version=12 numFds=2 numInts=26
GPU 扫堆：命中 0x7597f87180（头 version=12 numFds=2 numInts=26，fd=250 尺寸复核通过）
```
框架自己分配的 AHB 句柄与**扫到的句柄形态完全相同**（`12 / 2 / 26`）⇒
"扫到的是别的结构"这条解释基本被否掉：**它就是一类 gralloc/AHardwareBuffer 句柄**。

**但仍失败**（同一进程、同一时刻的对照）：
| 路线 | 结果 |
|---|---|
| `AHardwareBuffer`（框架自己分配）→ `eglGetNativeClientBufferANDROID` → `eglCreateImageKHR` | ✅ 图像建成（`0x3000`） |
| 扫到的 `native_handle_t*` → `eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID)` | ❌ `0x300c`（BAD_PARAMETER） |
| 扫到的句柄 → `AHardwareBuffer_createFromHandle` | ❌ `rc=-22`（EINVAL） |

⇒ **不对称本身就是答案**：在这套 ROM 上，EGL 吃的是**AHardwareBuffer 对象**，不是裸 `native_handle_t`。
所以剩下的路是"**把句柄变成 AHardwareBuffer**"，两条：
1. **修 `desc`**：`createFromHandle` 的 `desc` 必须与真实分配一致。目前用的是流表的
   `1920x1080 / layers=1 / format=0x22 / usage=0x10030322`；下一步把 `usage` 换几个候选
   （含/不含 `0x10000000`、含/不含 `0x10000`）与 `format` 备选（`0x22` / `0x23`）逐个试 ——
   `-EINVAL` 很可能就是"desc 与分配不符"；
2. **找框架已经建好的那个 `AHardwareBuffer` 对象**：框架把每个 camera3 buffer 包成 AHB 之后才交给上层，
   那个对象就在本进程堆里（可以按"对象里的句柄指针 == 我们扫到的句柄"来搜）。

**量具备注**：自检里给**非 YUV**（RGBA）的 AHB 用 `GL_TEXTURE_EXTERNAL_OES` 绑 FBO 是不对的
（RGB 系 AHB 要用 `GL_TEXTURE_2D`），所以那行"把它当 FBO 画一笔 → 失败"**不是结论**（第 9/10 轮各踩一次同类）。

### 63.10 第 12 轮：`desc` 不是原因 —— **18 组候选全被拒**，问题在"谁去解析这个句柄"

把 `createFromHandle` 的 `desc` 当变量逐个试（`format ∈ {0x22, 0x23, 0x1}` × `usage ∈ {0x10030322,
去掉 UBWC 位, 0x10020100, 0x10030022, 0x30322, 0x100|0x200}` = 18 组）：

```
GPU 自检(a)：句柄 0x7c23bfe100 头 version=12 numFds=2 numInts=26
GPU 自检(a)：createFromHandle(format=0x22 usage=0x10030322) rc=-22
GPU 自检(a)：createFromHandle(format=0x22 usage=0x30322)    rc=-22
GPU 自检(a)：createFromHandle(format=0x22 usage=0x10020100) rc=-22
…（18 组全 rc=-22，含最保守的 format=0x1 / usage=0x100|0x200）
```
⇒ **不是 desc 的问题**。句柄的形态与框架自己分配的一模一样（`12/2/26`）、它引用的 fd 与靶**是同一块
dma-buf**（`st_ino` 相同，§63.9），可 `AHardwareBuffer_createFromHandle` 就是不认。

**最可能的解释（可测）**：`createFromHandle` 与 EGL 的裸句柄导入**都要经过 mapper/分配器**
（`android.hardware.graphics.allocator` / `libgralloccore`）。相机 provider 是 **vendor 进程**
（`hal_camera_default`），它对这个服务/设备的访问可能在 SELinux 或命名空间上被挡住 ——
那样两条路的失败（`-22` 与 `0x300c`）就是**同一个原因**，而不是"句柄不对"。
**这正是本仓库已有的手段能管的**（部署脚本里用 `ksud sepolicy patch` 给 `hal_camera_default`
放过 tmpfs 读写）。下一步：给 `hal_camera_default` 补 graphics allocator/mapper/gpu 的 allow 规则再测。

**量具备注（别再踩）**：AHB 的 FBO 检查要 **`GL_TEXTURE_EXTERNAL_OES` + 先 `eglMakeCurrent`** ——
目前试过的两种组合各缺一半（§63.10 的 `target=0xde1 → 不完整` 与 §63.8 的"没 makeCurrent"），
所以那两行"FBO 不完整"**都还不是结论**；`eglCreateImageKHR` 成功（`0x3000`）才是。

### 63.11 第 13 轮：SELinux/命名空间假设**不成立**（没有拒绝），GPU 线到此收束

触发完整的 GPU 尝试（`gpu=1` 的会话：扫堆 + 两个自检 + EGL 导入）之后，直接读内核 AVC：

```
ps -AZ | grep provider-service  →  u:r:hal_camera_default:s0 … vendor.qti.camera.provider-service_64
dmesg | grep avc | grep -E 'hal_camera|cameraserver'
  … 只有 setsched（cameraserver → opluscamera_app）与 opluscamera_app 读 adsprpc，
     **没有任何 graphics / allocator / mapper / gpu / binder-service 相关的拒绝**
service list | grep allocator  →  android.hardware.graphics.allocator.IAllocator/default ✓ 在
```

⇒ §63.10 猜的"共因是 vendor 进程被挡住"**没有证据支持**：既不是 SELinux 拒绝，服务也在。

**那么 `createFromHandle` 的 `-22` 最可能就是它的本意**：框架的 `AHardwareBuffer` 不认这类
**厂商私有（QTI UBWC / camera 私用 format-usage）**的 gralloc 句柄 —— 这正好解释了为什么
`format`/`usage` 十八组全试也没用：不是 desc 填错，而是**这一类句柄本身不在 AHardwareBuffer 的可导入集合里**。

**于是"写 HAL 的编码器面"这件事的结论（四轮实测的汇总）**：

| 路 | 状态 | 证据 |
|---|---|---|
| CPU 线性写 | ❌ | §62：UBWC 压缩面，线性数据被解成噪声 |
| 让面退回线性 | ❌ | §62.5：`vendor.gralloc.disable_ubwc` 仅 setprop 无效 |
| fd → EGLImage | ❌ | §63.2：驱动没有 `EGL_EXT_image_dma_buf_import`（1247 字符扩展串全量核对） |
| 裸句柄 → EGLImage | ❌ | §63.3/§63.9/§63.10：`0x300c`；句柄与靶同一块 dma-buf、形态与框架句柄一致 |
| 句柄 → AHardwareBuffer → EGLImage | ❌ | §63.12 前身：18 组 desc 全 `-22`，且非 SELinux 所致 |

**→ 收束建议**：HAL 侧保留已能做到的部分（预览、照片、系统相机的录像面，§58/§60 的实测），
"给目标 App 的录像内容"交回**已验证的 app 层 relay 通道**（§5.7/§5.8），
把剩余轮次投到目标里仍未收口的边界（后置 4K 录像面、照片与录像一致性、前后置统一）。

---

## 64. 后置会话的实测底数（第 14 轮，纯观察、不注入）

GPU 那条线按 §63.11 收束后，本轮转去目标里一直没碰的边界（后置/4K/前后置统一），
第一步是**把后置会话的底数补齐**（此前文档里的后置数据都来自"录像/4K"专项，缺一份普通预览会话的表）。

**配置**：`dump=1;cam=2`，**不带** `inject`/`mock_map`/`gpu`（纯观察，一个字节都不写）。

`dump=1` 的流表（前置会话里没有的这几条）：

| 流 | format | usage | max | 判读 |
|---|---|---|---|---|
| `2136x1200` | `0x23`（YCbCr_420_888） | `0x20103` | 16 | 后置预览/分析流（**不含** UBWC 位） |
| `4096x3072` | `0x25`（RAW16） | `0x20003` | 32 | 照片 RAW 面（与 §26 的 4096×3072 一致） |
| `640x358` | `0x23` | `0x20000` | 32 | 小分析/视频流 |

provider 手上的 dmabuf 尺寸（≥1 MB，纯直方图）：

```
  3 1429504   10 4038656    1 4423680    1 6033408    1 6524928
  1 7147520    7 7778304   14 15728640   5 25165824   1 33554432
  1 3846144    1 1060864    4 1179648
```

**两条对"前后置统一"直接有用的观察**：
1. **`4,038,656` 在后置会话里也存在（10 块）** —— 文档 §47 把它标成"前置预览面"是**会话相关的归属**，
   不是"这块尺寸只属于前置"。⇒ 按尺寸选靶的机制（`mock_map`）本身是**相机无关**的，
   "前后置统一"缺的不是机制，而是**每个尺寸后面到底代表哪路流**的对照表；
2. 后置的 `usage` **不带** `0x10000000`（`0x20103/0x20003/0x20000`）⇒ 后置的**预览/RAW 面是线性**的，
   这与 §55"后置录像链路整体用压缩面"并不矛盾：压缩出现在**录像**那条链路的面上（`3,219,456`/`12,591,104`），
   而不是预览/RAW 面 —— 也就是说**后置的照片与预览本来就在"可 CPU 写"的面上**。

**下一步（未收口边界的具体入口）**：
1. 给后置那几路各造一份模板并按尺寸登记（`2136x1200` 与 `640x358` 的平面布局要先 dump 活帧确认），
   然后把 `cam=` 放开成"按尺寸自动覆盖前后置"，这就是"前后置统一"的落地形态；
2. 照片与录像一致性：两者共用模板内容（同一段视频帧），差异只在**写入策略**（照片盲写、录像成簇写，§38.2），
   一致性判据可用现成的"落盘照片 vs 视频帧"逐像素比对。

### 64.1 量具并入部署流程（第 15 轮）

**踩过**：第 14 轮去量后置底数时，`/data/local/tmp/portalex-inv.sh` 在设备上**已经不在了**
（之前某次清理把它带走），只能当场重新推 —— 这类"量具不见了"会直接让一轮实验作废。

**改法**：部署脚本 `scripts/deploy-camera-hal-pineapple.sh` 现在**顺带装量具**（幂等，每次部署都覆盖）：

| 装到 | 用途 |
|---|---|
| `/data/local/tmp/portalex-inv.sh <pid>` | 打印该进程所有 dmabuf 的 `size` 直方图（`<块数> <字节数>`）——差分量具与"哪块面多大"的底座 |
| `/data/local/tmp/portalex-pid.sh` | 一行拿到 provider pid（`pidof vendor.qti.camera.provider-service_64`），省掉到处写 pid |

用法（照抄）：
```bash
P=$(adb shell sh /data/local/tmp/portalex-pid.sh)
adb shell "sh /data/local/tmp/portalex-inv.sh $P" | tr -d '\r' | awk '$2>1000000'
```
这样"部署成功"与"量具就位"是同一件事，不再依赖会话记忆。

### 64.2 后置预览面的第一次取样：**尺寸猜错，拿到的是闲置零缓冲**（第 16 轮，未成功但方法已对）

做法：按 `2136×1200×1.5 = 3,844,800` 推出"最接近的分配尺寸 = **3,846,144**"（第 14 轮直方图里确有此尺寸），
用 `cam=2;raw_dump=3846144:/data/vendor/camera/rear_prev.raw` 取活帧。

结果：文件确实落下了（3,846,144 字节），但**整块全 0**（`mean=0.0 std=0.0`，行/列相关 `nan`）
⇒ 拿到的是一块**闲置（零）池缓冲**，不是后置预览面。

**这一轮的教训与下一轮的正确做法**：
1. `raw_dump` 的"活着"判据是"四个采样窗口在 120ms 内变了"；**静止场景会误判**，
   代码里此时会**退回第一块**并打日志 —— 所以拿到零缓冲时，**先看那条日志**（`现场帧落盘：… 活的` vs 退回），
   而不是直接分析内容（本轮先分析了内容，多花一步）；
2. "尺寸 ↔ 哪路流"不能靠算术猜：`2136×1200×1.5` 与真实分配差 1344 字节说明行距有对齐，
   但同一个尺寸在池子里可能有几十块（其中大部分是闲置的）⇒ **必须用差分量具先定"哪块是活的"**
   （`portalex-inv.sh` 的直方图 + 预览态/静止态对照），再决定 `raw_dump` 的尺寸；
3. 后置那几路的**平面布局仍未取到** —— 下一轮的入口不变，只是取样顺序要改成
   "先差分定尺寸 → 再看 `raw_dump` 日志确认拿到活块 → 最后才分析布局"。

### 64.3 后置预览面的活帧判据拿到手了：**`4,038,656` 是后置预览面**（并更正 §47 的归属）

按 §64.2 定的顺序（先定尺寸 → 再看 `raw_dump` 日志确认活块 → 最后判布局）：

1. **定尺寸**：先起相机再取直方图（§64.2 的坑：先取直方图会得到空表），后置会话 ≥1.5 MB 的尺寸是
   `10×4038656`、`14×15728640`、`7×7778304`、`5×25165824`，以及 `3846144 / 4423680 / 6033408 /
   6524928 / 7147520 / 33554432` 各 1 块；
2. **确认活块**：`cam=2;raw_dump=4038656:…` → 日志明确写 **`**活的**`**（对比 §64.2 那次的
   "静止场景强制落盘，共 1 块候选无一被判活"）；
3. **判布局**：拉回来的 4,038,656 字节按多种行距读，**行间相关 +0.966 ~ +0.997**（真图像特征），
   `mean=43.4 std=58.1`（房间偏暗）。

⇒ **`4,038,656` 是后置预览面，而且是线性面**（可 CPU 写）。

**顺带更正 §47 的一处归属**：§47 把 `4,038,656` 记成"**前置**预览面"。但它的字节数**对不上**
`1200×1200` YUV420（`4,038,656 ÷ 1.5` 非整数），却与 `2136×1200` 级别的行距吻合（`2240×1200 +
2240×600 = 4,032,000`，差 6,656 字节的头部/对齐）。更可能的解释是：第 6 轮那次"前置会话"测到的
10 块 4,038,656，其实是**前一次后置会话残留在池子里的预览缓冲**（当时我刚好 force-stop 过后置模式的相机）。
⇒ **归属不能靠"会话里看到它就归它"**，必须用"活帧 + 几何吻合"两条一起判（这正是本轮的方法）。

**对"前后置统一"的意义**：机制侧已经够了（`mock_map` 按尺寸选靶、相机无关），缺的只是**对照表**；
现在后置预览这一格可以填上：`4,038,656 → 后置预览（线性，几何 = 2136×1200 级别）`。

### 64.4 后置预览注入的 A/B：**本轮没跑成**，两个阻碍都有证据（第 18 轮）

按 §64.3 的结论造了后置预览模板（线性 NV12，**行距取活帧判据的 2176**：
`Y = 2176×1200 = 2,611,200` 全 235、`UV = 2176×600 = 1,305,600` 全 128，尾部留空到 4,038,656），
登记为 `mock_map=4038656:…`，`cam=2` 真机 A/B（截图数值对照）。

**阻碍一：模板读不到**（不是本轮的猜测，是 shim 自己报的）
```
E PortalCamShim: 注入：打不开模板 /data/local/tmp/portalex/rear_tpl.raw（errno=13）
```
文件在、标签也标了（`u:object_r:portalex_frame_file:s0`、`chmod 666`），但 **HAL 仍读不到**。
⇒ 结论：**模板一律放 `/data/vendor/camera/`**（现役的 `ffront.raw`/`fvidmock.raw` 都在那里、都在用），
新增文件要按 `portal.cfg` 的做法建（`rm -f` → 新建 → `chmod 666` → `chcon vendor_camera_data_file`），
不要用 `/data/local/tmp`。

**阻碍二：截图判据本身无效**
```
基线:   中央区 mean RGB=(4.1,3.6,5.5) 亮度std=0.6
注入后: 中央区 mean RGB=(4.1,3.6,5.5) 亮度std=0.6
```
两侧**逐位相同且接近全黑** ⇒ 屏幕/预览没有亮起来（屏幕熄灭或预览未渲染），
拿这种截图当"注入没生效"的证据是错的。⇒ 判据要先自检：`dumpsys display | grep -i state` +
必要时 `input keyevent KEYCODE_WAKEUP`，截图里必须**看得到取景器**才算有效样本。

**这两条都是量具问题，不是结论** —— 后置预览能不能注入，本轮**仍未被验证**。
下一轮的正确顺序：① 用 `/data/vendor/camera/` 建模板并确认 HAL 读得到（看 `注入：fd=… 模板 有`）；
② 唤醒屏幕、确认截图里能看到取景器；③ 再做 A/B（判据：中央区亮度/饱和度相对基线的变化）。

### 64.5 前置/后置预览**都验证生效**，并纠正 §64.3 的归属（第 19 轮）

用 §64.4 修好的量具（模板放 `/data/vendor/camera/`、屏幕唤醒、截图自检）做两次 A/B，**同一天、同一台机**：

| 会话 | 中央区 mean RGB | std | 结论 |
|---|---|---|---|
| 后置 · 基线（不注入） | (4.2, 3.7, 5.5) | 0.6 | 暗场实景 |
| 后置 · 注入后 | **(235.0, 235.0, 235.0)** | 0.0 | 全白模板铺满 ⇒ **生效** |
| 前置 · 基线（不注入） | (170.8, 164.6, 150.9) | 43.2 | 实景 |
| 前置 · 注入后 | **(114.4, 151.0, 54.8)** | 46.4 | 换成测试卡；**std 保持** ⇒ 有结构内容而非噪声 ⇒ **生效** |

**并纠正 §64.3**：§64.3 依据"字节数对不上 1200×1200 YUV420 + 与 2136×1200 级行距吻合"把 `4,038,656`
判成"后置预览面"。但本轮前置会话**同样**认领到 9 块 4,038,656（`注入线程：9 块缓冲，共 36,347,904 字节`）
且**注入确实改变了前置预览**。⇒ 正确表述是：
**`4,038,656` 在前后置会话里都出现、注入它都能改变可见预览；"这块尺寸属于哪个相机"不能靠会话观察判定。**
§64.3 那条"归属更正"因此**降级为待定**（几何算术只是线索，不是判据）。

**判据方法论（本轮真正沉淀下来的）**：面的归属要用**三条一起**判 ——
① 活帧（`raw_dump` 日志写"活的"）；② 几何/尺寸自洽；③ **注入前后可见画面的变化**（截图数值对照，
且截图必须先自检"看得到取景器"）。前两条只是线索，第三条才是判据。

### 65. 冷启动生效延迟的修复：`shim_open` 一发生就重扫（第 20 轮）

**症状**（用户实测）：相机开了以后要**十几秒**模拟画面才出现；体适能人脸核验页里**直到超时都没生效**。

**根因 1（延迟）**：换靶的快路依赖 VVideo 事件层，而实测部署里**没有挂那层**
（`grep -c '^vv_mount' portal.cfg` = 0）⇒ `portal_vv_wait_streams()` 返回 `-ENOTTY`，快路整条是死的；
线程只能等周期性安全带，期间继续往**上一场会话的旧缓冲**里写。**修法**：加一个 `g_rescan_now` 标志，
在 `shim_open`（新会话开始那一刻，日志里一直在打）置位，注入线程每轮原子读一次、置位就
`munmap` 旧靶并立刻重扫 —— 不依赖事件层。日志证据：`注入线程：收到 shim_open（新会话开始）⇒ 立刻重扫靶`。

**根因 2（体适能完全不生效）**：它的会话是**两条 640×480 流**
（`out[0] 640x480 format=0x23 usage=0x20003`、`out[1] 640x480 format=0x22 usage=0x10020900`），
而 `mock_map` 里只有 `4,038,656` / `3,194,880` ⇒ **尺寸不命中 ⇒ 一块靶都不认领**
（真机日志：全程 `注入：fd=` 计数 = 0）。这正是"按尺寸选靶"机制缺的那张**对照表**。
⚠️ 量具教训：我查直方图时按 `≥1MB` 过滤，把 640×480（~0.5MB）的块**滤掉了**，白绕一圈。
⇒ 量小块面时**不要设尺寸下限**，或把下限压到 64KB。

### 66. 体适能（640×480 人脸核验）接通：**尺寸对照表补上第一格**（第 20 轮）

**症状**：`com.bxkj.student` 的人脸核验页（`FaceCheckV2Activity`）里，模拟**直到超时都没挂上**。

**定位**（全部真机实测）：
- 它的会话是两条 **640×480** 流：`out[0] 640x480 format=0x23 usage=0x20003`（YUV 分析）、
  `out[1] 640x480 format=0x22 usage=0x10020900`（预览）；
- 而 `mock_map` 里只有 `4,038,656` / `3,194,880` ⇒ **尺寸不命中 ⇒ 一块靶都不认领**
  （日志：全程 `注入：fd=` 计数 = 0）。**这就是"完全无效果"的全部原因**，与"10 秒延迟"是两件事；
- 量具教训：我查直方图时按 `≥1MB` 过滤，把 640×480（~0.5MB）的块**滤掉了**，白绕一圈。
  小块面**不要设尺寸下限**（或压到 64KB）。

**量到的真实尺寸**（`shim_open` 后当场取不过滤直方图，前后两次采样对照）：
| 尺寸 | 判读 |
|---|---|
| **`491,520`** = `640×512×1.5`（精确） | 分析流面（线性 NV12，行距 640、512 行） |
| **`507,904`** = `491,520 + 16,384` | 预览流面（带 UBWC 位，多一块元数据） |

**模板与结果**：按 `Y = 640×512 = 327,680`（填 235）、`UV = 640×256`（填 128）、尾部补零造两份模板，
登记进 `mock_map` 后**立刻认领**：
```
注入：fd=274 size=491520 → …（模板 有）
注入：fd=286 size=507904 → …（模板 有）
注入线程：21 块缓冲，共 10,485,760 字节（4 个模板，同步=0）
```
人脸页中央区：**mean RGB=(185.5, 228.7, 183.3)、亮度 199.2、近白占比 0.621**（此前是实景暗场）。

⇒ **"按尺寸选靶"这条机制对第三方 App 同样成立**；缺的一直是那张"尺寸 ↔ 哪路流"的对照表 ——
现在多了两格（640×480 分析 / 640×480 预览）。**未做**：这两格的模板目前是纯色占位，
要放真画面得把模板换成解码后的视频帧（或走 app 层 relay 的视频通道）。

**顺带（同轮修掉）**：`shim_open` 一发生就置 `g_rescan_now`，注入线程立刻重扫靶 ——
真机日志 `shim_open 被调用` 后 **5 毫秒**即 `收到 shim_open（新会话开始）⇒ 立刻重扫靶`，
治的是"开相机后十几秒才生效"（根因：换靶快路依赖 VVideo 事件层，而部署里没挂那层）。

### 67. 640×480 两份模板换成**真实视频帧**（第 21 轮，无人值守首轮）

把占位纯色换成真帧的流程（已跑通，可复用）：
```
源：/data/system/portalex-camera/videos/<clip>.mp4（app 层 store 里的素材）
→ ffmpeg -i clip.mp4 -frames:v 1 -vf scale=640:480 -pix_fmt nv12 -f rawvideo frame.nv12   # 460,800 字节
→ 铺进模板：Y = frame[0 : 640*480]（写 0 偏移，480 行），480..511 行复制最后一行补齐
            UV = frame[640*480 : +640*240]（写 640*512 = 327,680 偏移）
→ 写 /data/vendor/camera/tpl_<size>.raw（rm -f → cat → chmod 666 → chcon vendor_camera_data_file）
→ 重新部署（模板在线程启动时加载）→ 真机验证
```
**判据对照**（人脸页中央区，同一 App 同一页面）：
| 模板 | 截图 mean | std |
|---|---|---|
| 纯色占位（Y=235/UV=128） | 240.1 | **0.0**（全平） |
| **真实帧**（fixture-video 一帧） | 240.1 | **20.7** |

⇒ 内容确实换成了帧（std 从 0 变 20.7）。⚠️ **判据要改进**：截图裁切把人脸页 UI 一起算进去了，
导致与源帧的相关只有 +0.121（UI 覆盖 + 缩放）。下一轮要么只截**取景器区域**，
要么直接量"provider 侧那块缓冲的内容"（`bufmap=1` 或 `raw_dump`）与源帧比 NCC —— 后者不受 UI 干扰。

### 68. **撤回 §66 的"接通"结论**：品红判据证明我们的帧没到体适能预览（第 21 轮）

§66 那个"接通"是**用截图亮度推的**（近白 0.621），而我在同一节里就标了风险：那可能是 App 自己的亮界面。
本轮换了一个**不可能与 UI 混淆**的判据：把两份 640×480 模板填成**品红**（Y=105 / U=212 / V=148），
看人脸页有没有品红。

**结果（会话活跃时）**：整屏品红占比 **0.0000**，中央区 **0.0000**，屏幕 mean RGB=(116.5, 119.4, 118.0)
⇒ **我们的内容没有到达 App 的预览**。**§66 的"接通"结论就此撤回。**

**为什么没到**（两条独立证据）：
1. `raw_dump` 抓到的"活块"与我们的模板**逐字节相同率仅 3.5% / 5.5%，Y 面 NCC −0.26 / −0.04**
   ⇒ dump 按"内容在变"选块，而**一直在变的是 ISP 写的那块**；我们持续覆写的那批反而"看起来不活"；
2. 也就是说：**我们认领的是旧块，ISP 往新块里写**。机理与 `shim_open` 的时序吻合 ——
   App 那批 640×480 缓冲往往在 `shim_open` **之后**才分配，单次重扫必然漏掉它们，
   而下一次重扫要等周期安全带 ⇒ 整个 10 秒窗口里我们写的都是作废的块。

**已实现（构建通过、已部署，但验证未完成）**：新会话开始后**密集重扫 5 秒** ——
`shim_open` 里除置 `g_rescan_now` 外，再把已有的"探询窗口"武装 5 秒（窗口内每 `vv_probe_us`（15ms）
比一次廉价指纹，一变就重建靶），这样后续分配的块能被及时认领。
⚠️ 最后一次验证**无效**：`am start` 只是把**上一轮已打开**的人脸页拉到前台，没有产生新会话
（日志里连 `shim_open` 都没有）⇒ 该修复**尚未被验证**，下一轮必须**先 force-stop App 或等页面超时关闭**再测。

**下一轮**：① 用"全新会话"重跑品红判据；② 若品红出现 ⇒ 修复成立，640×480 面可注入；
③ 若仍为 0 ⇒ 说明在这些面上 **ISP 赢下了竞争**（我们写不赢它的刷新），按 §62/§63 的同级证据
判定"HAL 侧内容注入对该面不适用"，转 app 层 relay 视频通道。

### 68.1 第 22 轮：品红仍不出现；`bufmap` 用来分开"没写进去"与"写进去被 ISP 盖掉"

- **全新会话重测**（force-stop 后重开人脸页）：整屏品红 **0.0003**、中央 **0.0010**（噪声级），
  屏幕 mean RGB=(211.5, 227.9, 215.9)（App 的亮界面）⇒ 我们的帧**仍不可见**。
- 时间轴上只看到上一批认领（`21 块缓冲 / 4 个模板`）与 `探询窗口结束，靶集始终没变（21 块）`，
  **没有 `shim_open`** ⇒ 这次测试**仍不能算数**（App 没在窗口内重开相机）。
- 于是加一条能分清两种失败的测量：`bufmap=1` 打印**认领缓冲**的内容分布 ——
  若分布是品红模板的值（Y≈105 / UV≈212,148）⇒ 我们**确实写进了认领的块**（问题在"ISP 盖掉/消费者不看"）；
  若是别的值 ⇒ 认领的块根本没被我们写（问题在"写哪里"）。
- ⚠️ 无人值守的两条纪律（本轮两次被它绊到）：**① 验证前必须确认"新会话真的开始"**（日志里要有 `shim_open`）；
  **② App 页面可能是残留的前台实例**，`am start` 不等于重开会话 —— 要么 force-stop 后确认页面重新渲染，
  要么等它超时关闭再测。

---

## 69. 结论：HAL 侧内容注入对**体适能那条 640×480 链路不生效**（第 23 轮，有效测量）

**这次是有效测量**（前两次被"没有新会话"绊倒，见 §68.1）：
```
shim_open 次数 = 2                     ← 新会话确实开始
品红 整屏 = 0.0003  中央 = 0.0010      ← 噪声级
屏幕 meanRGB = (211.0, 226.8, 215.8)   ← App 自己的亮界面
```

**证据链（三条互相独立）**：
1. **认领是成功的**：`注入线程：21 块缓冲，共 10,485,760 字节（4 个模板）`，含多块 `491520` 与 `507904`；
2. **`raw_dump` 的"活块"与模板不一致**：逐字节相同率 3.5% / 5.5%，Y 面 NCC −0.26 / −0.04
   ⇒ 按"内容在变"选出的块是 **ISP 正在写的那块**，不是我们持续覆写的那批；
3. **品红在有效会话里不可见** ⇒ 无论我们是"写错了块"还是"写对了但被 ISP 盖掉"，
   **App 的人脸 SDK 在它的 10 秒窗口里拿到的都是实景帧**。

**判定**：对这条链路，**HAL 侧内容注入不适用**（与 §62/§63 的编码器面同类：不是通道错了，
而是"我们写的那块不是消费者读的那块"或"我们抢不到刷新"）。**不再在这条路上加轮次。**

**保留的 HAL 能力（有结构化证据）**：系统相机的前置/后置预览面（`4,038,656`）注入**可见生效** ——
A/B 的签名是"均值大幅偏移 **且 std 保持 46.4**"（换成我们的测试卡），这比"亮度变化"强得多
（§66 就是被亮度骗过一次：App 的亮界面冒充了"生效"）。
⇒ **教训固化**：判据强度排序 **结构化签名（std/NCC）> 颜色占比 > 亮度**；亮度单独用不足以判定。

**面向 App 的交付改走**：**app 层 relay 视频通道**（master 分支已验收：`CameraRelayService` +
`/data/system/portalex-camera/` 的配置与视频、system_server 侧控制面、零注入目标应用）。
HAL 侧继续负责它已经证实的部分（系统相机预览面 / 照片链路 / 前置后置统一）。

---

## 70. 两层的真实状态（第 24 轮）：**应用层有注入能力但没配置；HAL 层只对系统相机预览有效**

判据来源：LSPosed 配置库 `/data/adb/lspd/config/modules_config.db`
（设备上没有 `sqlite3`，**拉回本地用 python 读**就行）。

**应用层（master 分支的 LSPosed 模块）**：
```
modules_state: ('moe.fuqiuluo.portalex', 0, enabled=1, scope_request_blocked=0)
scope (6 个): com.android.phone / com.bxkj.student / com.oplus.location /
              com.zjwh.android_wh_physicalfitness / moe.fuqiuluo.camerafixture / system
```
⇒ **模块已启用、体适能在作用域内**（两个体适能包都在），**夹具 App 也在** —— 应用层**具备注入能力**。

**但 relay 的配置是空的**：
```
/data/system/portalex-camera/manifest.json → enabled=true, tagFilter=["fixture"], targets=[]
```
⇒ **没有任何目标应用被配置**，模块即使注入进去也没有内容可下发 ⇒ 应用层现在对谁都不生效。

**HAL 层（feature/hal-vcam）**：只对系统相机的前置/后置预览面（`4,038,656`）可见生效；
App 自己的 640×480 链路不生效（§69，三条独立证据）。

### 结论：下一步的缺口不在"能不能注入"，而在"**有没有配置**"
把 `targets` 填上（例如 `com.bxkj.student`），应用层立刻有内容可发；验证顺序应当是
**先用夹具 App（`moe.fuqiuluo.camerafixture`，已在作用域内）跑通链路**，再上真实目标。
两条填充途径：① 应用内 UI 手动添加；② 走 relay 协议（app→system_server 的 `PUT_CONFIG`），
或据其存储格式直接写 `manifest.json`（后者有被 system_server 缓存覆盖的风险，需验证）。

### 70.1 应用层"配置"缺口：**直接写 manifest 可行且持久**（第 25 轮）

做法（保住 SELinux 标签的关键：**原地覆盖**，不要 push 到目标路径）：
```bash
adb shell "cp /data/system/portalex-camera/manifest.json /data/local/tmp/manifest.bak"   # 先备份
adb pull /data/system/portalex-camera/manifest.json /tmp/m.json
python3 -c "import json;d=json.load(open('/tmp/m.json'));d['targets']=['moe.fuqiuluo.camerafixture'];json.dump(d,open('/tmp/m.new.json','w'))"
adb push /tmp/m.new.json /data/local/tmp/m.new.json
adb shell "cat /data/local/tmp/m.new.json > /data/system/portalex-camera/manifest.json; chmod 600 …"
```
**实测结果**：写入后 **6 秒再读仍是写入值**（`"targets": ["moe.fuqiuluo.camerafixture"]`）
⇒ **system_server 没有用内存缓存把它盖回去**，文件属主/标签也没变
（`system system u:object_r:system_data_file:s0`）。⇒ "配置"这条缺口有一条**可脚本化**的通路。

夹具 App（`moe.fuqiuluo.camerafixture`，已在 LSPosed 作用域内）随即被拉起并开始采样：
```
I CameraFixture: [Fixture] sample #6 sig=0504050505… mean=05040b (+5002ms)
```
⚠️ **这一行还不能当判据**：帧的签名近乎均匀且偏暗（`mean=05040b`），
"是不是我们的模拟内容"要靠**夹具自己的验收脚本**（`scripts/verify-app-hook.sh` 的 `list|config|legacy*` 变体）
或夹具界面里的画面来判 —— 下一轮做这件事，然后再把真实目标（`com.bxkj.student`）加进去复测。

### 70.2 把"改配置"脚本化：`scripts/set-camera-mock-targets.sh`（第 26 轮）

后续每一轮都要动这个配置，所以固化成一个脚本（含**备份/回滚/持久性校验**）：

```bash
sh scripts/set-camera-mock-targets.sh                     # 只看：enabled / tagFilter / targets / points
sh scripts/set-camera-mock-targets.sh pkgA [pkgB ...]     # 设为目标包
sh scripts/set-camera-mock-targets.sh --clear             # 清空 targets
sh scripts/set-camera-mock-targets.sh --restore           # 回滚到上次备份
```

**实机验证**（本轮跑的）：
```
当前 manifest： enabled=True tagFilter=['fixture'] targets=['moe.fuqiuluo.camerafixture'] points=[('fixture-point',1)]
待写入 targets = ['moe.fuqiuluo.camerafixture']
写入后 targets = ['moe.fuqiuluo.camerafixture']
6 秒后 targets = ['moe.fuqiuluo.camerafixture']
✅ 持久（未被缓存盖回）
文件：system system u:object_r:system_data_file:s0
```
脚本内置的三条护栏：① 备份只做一次（避免把改坏的状态当备份）；② **原地覆盖**保标签；
③ 复核不通过就**直接失败并提示改走 relay 的 `PUT_CONFIG`**（不留下一个看起来成功的状态）。

### 70.3 第 27 轮：`targets` 填上后，夹具**仍未拿到模拟内容** —— 断点排查进行中

- 夹具（`moe.fuqiuluo.camerafixture`）被拉起后持续采样：`[Fixture] sample #N sig=05050505… mean=05040b`，
  签名一直是 4~6 的**近均匀偏暗**值 ⇒ 看起来是**实景暗场**，不是 `fixture-clip.mp4` 的条纹素材；
- 它自己的验收页（`ListSessionActivity`）**没有文本节点**（只渲染 surface），
  所以"用 UI 文本判"这条路不成立 —— 判据得换成**帧内容**（签名/均值）或它打印的其它日志；
- 于是往上游查：relay 存储（`gate.txt` / `sizes.txt` / `videos/`）、app 进程与 `CameraRelayService`、
  以及模块侧有没有为夹具请求配置/视频。**结论待本轮输出。**

**本轮的上游排查结果（把断点钉住了）**：
| 环节 | 状态 |
|---|---|
| relay 存储 | `gate.txt=1` ✓、`sizes.txt` 有 `fixture-video=16740` ✓、`videos/fixture-video.mp4` 在 ✓ |
| **PortalEX 应用进程** | **`pidof moe.fuqiuluo.portalex` 为空、无 `CameraRelayService`** ← **断点** |
| 模块侧（注入夹具） | **控制面确实拦到了**：`[Portal] 摄像头控制面：startOperation / finishOperation uid=10469 pkg=moe.fuqiuluo.camerafixture` |
| 帧内容 | 仍是 4~6 的近均匀暗（实景），不是 `fixture-clip` 条纹 |

⇒ **读法**：控制面（拦截相机会话）已工作，但**视频体是由 PortalEX 应用侧通过 `openFile` 发 fd 的**
（被注入进程没有文件路径权限，只能拿 fd）——**应用不在跑 ⇒ 拿不到视频 fd ⇒ 静默退回实景帧**。
这也解释了为什么"配置齐全、作用域正确、控制面命中"却依然看不到模拟画面。

**下一轮**：① 把 PortalEX 应用进程拉起来（前台或起它的服务），重复夹具这一次采样，看签名是否变成条纹素材；
② 若确实需要应用常驻，则把这条前提写进验收与部署脚本（`set-camera-mock-targets.sh` 旁边加一个"确保泵在跑"的检查）；
③ 复测通过后再把 `com.bxkj.student` 加进 `targets`。

### 70.4 第 28 轮：**"应用没在跑"假设被证伪**；拿到一个可复用的硬判据

| 假设/动作 | 结果 |
|---|---|
| 拉起 PortalEX 应用（`pidof` = 22885，确实在跑） | 夹具采样**没有任何变化** ⇒ 上一轮"应用不在跑导致拿不到视频 fd"**被证伪** |
| 回滚 manifest 备份 | **空操作**（备份是在我写入之后才建的，内容相同）；格式假设仍未验证 |
| `scripts/verify-app-hook.sh list` | **无输出、退出码 2** ⇒ 验收脚本有未满足的前置（本会话早先它是绿的，说明有状态漂移） |

**本轮真正的收获：一个可复用的硬判据**（比"看亮度"强得多）——
把素材帧解出来当参照：

| 对象 | mean RGB | 8×8 签名 |
|---|---|---|
| **素材帧**（`fixture-video.mp4` 解一帧） | `0x8f8f8f`（灰） | `02 02 … 04 04 … 06 … 0e`（**渐变**） |
| **夹具 App 实测采样** | `05040b` / `06040c`（近黑深蓝） | `04~06`（**近均匀**） |

⇒ **夹具确实没有拿到我们的视频**（不是"看错了"，两边签名形态完全不同）。
以后判"应用层有没有生效"直接用这条：**素材帧签名 vs 目标 App 采样签名**。

**下一轮（按代价）**：
1. 诊断 `verify-app-hook.sh` 为什么退出 2（读它的前置/环境要求）——它是最权威的验收器；
2. 验证"manifest 格式"假设：按**原始紧凑单行**格式重写（`{"version":1,"enabled":true,…}`，无空格）
   并保留 target，再跑夹具采样 —— 隔离"格式"这一个变量；
3. 若两者都不是，再查模块侧解码/取视频那条日志（`[Portal]` 里除控制面以外的行）。

### 70.5 第 29 轮：**用本项目的验收器拿到权威判定** —— 应用层当前两侧都不通过；首要嫌疑是我手写的 manifest

**先更正上一轮的一处误判**：`scripts/verify-app-hook.sh` **不在 feature/hal-vcam 分支**（它在 master），
所以那个"退出码 2"是 `ls` 失败，**不是脚本前置问题**。本轮用 `git show master:…` 把它与三个依赖脚本取到
`/tmp/appacc/` 后再跑（`SKIP_SEED=1`，不覆盖库里素材）。

**权威判定（`fixture` + `list` 变体）**：
```
模块侧（决策链）: ❌ 没拦 / 卡住
画面侧（内容）  : ❌ 不是合成素材（或没跑起来）
   14 个样本 mean 全 = 06040c，行均值恒为 [5 5 5 5 5 5 5 5]，跨度 0~1，命中阶梯行 0/14
   判定：多半仍是真实相机画面
   快照：/tmp/vapphook.702839/mock.png（人眼终判）
```
**关键推论**：这条链**本会话早先是绿的**（四种变体 + legacy-photo 都通过过），而现在**模块侧连"拦"都没发生**
⇒ **状态漂移**。本轮我在其间只改过一处：**手写 `/data/system/portalex-camera/manifest.json`**
（round 25：`json.dump` 重排成带空格的多行格式，并写入 `targets`）。模块读的就是这个存储 ⇒
**首要嫌疑是"我的写法破坏了配置加载"**（格式/字段/或它期望由播种路径写入）。

**下一轮（复原 + 定位，按顺序）**：
1. 用**受支持的路径**复原：`scripts/seed-camera-mock.sh`（master，已取到 `/tmp/appacc/`，支持 `GRANT_PKG=`）
   重新播种一次，再用验收器跑 `fixture legacy-photo`（脚本文档说这条是最强判据）；
2. 若复原后变绿 ⇒ **确证是我手写 manifest 造成的**，那就把 `set-camera-mock-targets.sh` 改成
   "**保格式改写**"（紧凑单行、字段顺序不变）或干脆改走 relay 协议，并在脚本里写明这条禁令；
3. 若复原后仍红 ⇒ 与我的改动无关，转查模块侧决策链的第一道门（它自己的日志会说是哪一条不满足）。

### 70.6 第 30 轮：受支持路径复原后**仍不通过** ⇒ 我的手写 manifest 被洗清（但暴露了真实启用机制）

**受支持的播种**（`/tmp/appacc/seed-camera-mock.sh`，`GRANT_PKG=moe.fuqiuluo.camerafixture`）成功：
```
[Seed] seeded file=fixture-clip.mp4 durationMs=8000 tags=[fixture] point=fixture-point … enabled=true points=1
       grant=[granted[content://moe.fuqiuluo.portalex.cameramock/manifest]
              granted[content://moe.fuqiuluo.portalex.cameramock/video/fixture-video]]
manifest → {"version":1,"enabled":true,"tagFilter":[],"targets":[],…}
```
**两条对我此前理解的重要更正**：
1. **启用机制不是 `targets`**，而是**给目标包授 content URI 权限**（`granted[content://…/manifest]`、
   `…/video/fixture-video`）——`targets:[]` 是正常状态；我 round 25 写 `targets=[…]` 属**自创语义**；
2. **`tagFilter` 应为 `[]`**，我却把它改成了 `["fixture"]` —— 这是我引入的真实差异（已还原）。

**但复原后验收仍然 ❌**（`fixture`/`list`：模块侧 ❌ 没拦/卡住；画面侧 ❌ 命中阶梯行 0/14）：
```
== 总结 ==
   模块侧（决策链）: ❌ 没拦 / 卡住
   画面侧（内容）  : ❌ 不是合成素材（或没跑起来）
```
⇒ **我的手写 manifest 不是（唯一）原因**；失败在**模块侧决策链**（脚本注释说它有**七道前置条件**，
任何一条不满足都会"安静地不拦截"）。下一轮直接读它自己的判定日志（`[Portal]` 行），看卡在哪一条。

**给 `set-camera-mock-targets.sh` 的修正项（下一轮做）**：既然启用靠 URI 授权，
这个脚本"写 `targets`"的做法**语义错误**，应改为：
保留"查看"能力，写入路径改成**调用受支持的播种**（`seed-camera-mock.sh GRANT_PKG=…`），
或至少加醒目警告 + `--clear` 时恢复 `tagFilter:[]`。

### 70.7 脚本语义修正：`set-camera-mock-targets.sh` 改为"查看 + 委派受支持播种"（第 31 轮）

它的第一版**语义是错的**（§70.6 已实测证明）：直接改 `manifest.targets` 并保留 `tagFilter` ——
而真实启用机制是**授 content URI 权限**，且 `tagFilter` 应为 `[]`。留在仓库里会继续误导
（包括我自己），所以本轮直接改掉：

| 现在的能力 | 行为 |
|---|---|
| 无参数 | **查看**：`enabled / tagFilter（标注应为 []）/ targets（标注空是正常的）/ points / 存储内视频` |
| `--grant <包名>` | **委派受支持播种** `seed-camera-mock.sh GRANT_PKG=<包名>`（从 master 用 `git show` 取到 `/tmp/appacc/`），
一次性完成"播种 + 开开关 + 授权" |
| `--uri <包名>` | 试直接补授 URI 权限（provider 支持时），失败则提示改用 `--grant` |

并在文件头部把**两处错误的实测结论**写死（`targets` 不是启用列表、`tagFilter` 应为空），
免得下一个人（或下一轮的我）再照旧脚本走一遍弯路。

### 70.8 第 32 轮：模块只出现在 **system_server** 侧；夹具进程侧**没有任何模块日志**

用最宽的过滤查了一轮（`logcat | grep LSPosedFramework`，排除 system 前缀、再按 portalex/load/init/hook 收窄）：

| 事实 | 证据 |
|---|---|
| PortalEX **确实被授权注入夹具** | LSPosed 作用域表：`[('moe.fuqiuluo.portalex','moe.fuqiuluo.camerafixture')]` |
| 夹具进程**在跑** | `pidof moe.fuqiuluo.camerafixture` = 14281 |
| 但**所有** `[Portal]` 日志都来自 **pid 3795 = system_server** | 只有 `摄像头控制面：startOperation / finishOperation uid=10469 pkg=…` 两类 |
| 夹具进程侧**没有任何模块日志** | 按 `lsposed` + `camerafixture` / `load|init|hook` 过滤均无命中 |

⇒ 读法（**待下一轮确认**，我不当结论）：控制面（system_server 侧）在工作，
但**模块在夹具进程里的那一半没有产出任何日志** —— 要么它没被加载进该进程，
要么它的决策链在**第一道门之前**就静默返回了（脚本注释："七道前置，任何一条不满足都会安静地不拦截"）。
下一轮的判别很具体：**看 LSPosed 有没有把模块 dex 注入夹具进程**（进程级注入日志 / 该进程内的
`[Portal]` 行是否存在），据此把"模块没加载"与"加载了但前置不满足"分开。

### 70.9 第 33 轮：**"模块没被注入 App 进程"这个读法被推翻** —— 反过来给了更好的靶子

LSPosed 的 `app_configs` 表（每 App 配置）里只有三条，且带 `invalidate_inline_hooks=true`：
```
('com.tencent.mobileqq',        HashMap{invalidate_inline_hooks=true})
('com.bxkj.student',            HashMap{invalidate_inline_hooks=true})   ← 体适能（人脸核验那个）
('com.zjwh.android_wh_physicalfitness', HashMap{invalidate_inline_hooks=true})  ← 另一个体适能
```
这个标记是**模块在 App 进程内自己写下来的**（不是 system_server 侧写的）⇒
**模块确实被注入进了这两个体适能 App 的进程**，并且在里面跑过（还要求过 inline hook 失效重建）。

⇒ 上一轮"夹具进程里没有模块日志 ⇒ 模块可能没被加载"的读法**不成立**（至少对体适能不成立）：
- 夹具包 `moe.fuqiuluo.camerafixture` **不在** `app_configs` 里（它可能只是没触发过 inline hook 失效）；
- App 进程里的模块日志**本来就很少**（verbose 日志里能搜到的注入词汇全是 system_server 侧的），
  所以"没看到日志"**不能**推出"没注入"——这是我上一轮差点犯的同类错误（把"没看见"当"不存在"）。

**下一手（靶子直接换成真实目标，而不是夹具）**：用验收器对**包名**跑（它的用法支持 `<包名>`）：
`scripts/verify-app-hook.sh com.bxkj.student legacy`（或 `list`），
并同时抓**该 App 进程内**的 `[Portal]` 行（用 `logcat --pid=$(pidof com.bxkj.student)`）——
体适能是本项目的真实目标，而且现在有证据表明模块确实在它进程里跑过。

### 70.10 第 34 轮（现场日志挖掘）：模块的 `put_config` 载荷里**没有相机字段**；整段窗口零相机日志

从上一轮那次手动测试留下的全量日志（`/tmp/all.log`，146 MB）里挖，两条硬事实：

1. **体适能的相机路径是走 HAL 的**（不是某个私有 SDK 绕开）：HAL 侧明确记录客户端包名 ——
   ```
   CamX: LoadOPPackageName() [OP_EXT] mClientPackageName:com.bxkj.student
         … m_activityName com.bxkj.student.v2.ui.face.FaceCheckV2Activity
   ```
   （这是 QTI/OPPO 扩展：把"哪个 App 在用相机"告诉 HAL —— 也说明我们那条 HAL 线确实在该会话里跑过。）
2. **整段窗口里，模块的非噪声日志全是定位/传感器**（`isProviderEnabledForUser`、`set_sensor_mock`、
   `registerGnssStatusCallback: injected!`、`融合定位 hook 状态：…`），**没有任何一行与相机有关**；
   而 `put_config` 的载荷里**只有定位/传感器字段**（`noise_profile / min_satellites / fused_mode / …`），
   **没有相机字段**。

⇒ **读法（下一步验证）**：模块的**相机那半没有拿到任何启用配置** —— 它收到的 `put_config` 里根本没有相机段，
所以相机 hook 只能"安静地不拦"，与我们在 §70.5 用验收器测到的"模块侧 ❌ 没拦/卡住"完全一致。

**下一手（读代码，不用设备）**：在 master 分支上找**相机 mock 的启用条件**（它读哪个配置键/哪条命令、
与 `put_config` 或 camera relay 的关系），据此确定"我们少发了什么"。这比继续在设备上试要快得多。

### 70.11 第 34 轮：读代码找到**拦截门**（应用层"不拦"的权威解释源）

`CameraMockController`（master）的拦截门，两条关键条件：

```kotlin
// ① 配置缓存没热 + 当前在主线程 ⇒ 本次不拦截，只做异步预热
if (!CameraMockClient.isWarm() && Looper.myLooper() == Looper.getMainLooper()) {
    CameraMockClient.warmUpAsync()
    if (FakeLoc.enableDebugLog) Logger.info("摄像头模拟：配置缓存未热且当前在主线程，本次不拦截（已后台预热）")
    return false
}
// ② 总开关 + 目标清单
if (!CameraMockClient.enabledFor(packageName)) {
    if (FakeLoc.enableDebugLog) Logger.debug("摄像头模拟：不拦截（总开关未开或不在目标清单）：${packageName ?: "?"}")
    return false
}
```
`enabledFor`（`CameraMockClient.kt`）：
```kotlin
val m = manifest()
if (!m.enabled) return false
if (packageName == CameraMockProtocol.OWN_PACKAGE) return true   // 自己（录制页）不看清单
if (m.targets.isEmpty()) return true                              // ★ 空清单 = 全放行
return m.targets.any { it == packageName }
```

**三条对我前几轮的更正/确认**：
1. **`targets=[]` 是全放行**（`if (m.targets.isEmpty()) return true`）⇒ 我 round 25 写 `targets` **完全没必要**，
   round 30 的还原**是对的**（这条结论现在有代码依据，不再靠猜）；
2. **日志为零是可解释的**：两道门都只在 `FakeLoc.enableDebugLog` 下打日志。
   而我们在 `put_config` 里看到过 `enable_debug_log=true` —— 所以"没日志"更可能意味着
   **相机那条门根本没被走到**（hook 没触发），而不是"模块没进进程"；
3. **应用层也有"冷启动"效应**：①说得很直白 —— 缓存没热 + 主线程时**本次就是拦不了**，
   只能异步预热等下一次。**与 HAL 线那条"冷启动注入未生效"同构**
   （那条我们已用 `shim_open` 立即重扫修掉；应用层这条目前**没有对应补丁**）。

**下一手（读代码即可）**：把 `CameraMockController` 的两道门**之外**的安装条件读完
（hook 是在什么时机被装到目标进程的、需要什么前置），据此确定"为什么相机门没被走到"。

### 70.12 第 35 轮：诊断的正确数据源是 **LSPosed 的模块日志文件**，不是 logcat

- 读代码确认： **无条件安装**（每个族各自 ，失败会 
  + 记进 ）⇒ **"hook 没装上"这个解释排除**（现场也没看到安装失败的 error）；
  拦截决策发生在  的两道门里，而那两道门**只在 
  下打日志**。
- 我在 146 MB 的 logcat 捕获里搜 `静默失败记账` 与 `摄像头模拟`：**0 命中**（本轮实测：
  摄像头模拟=45 条、静默失败记账=2 条）。
  ⇒ **模块的 App 进程日志不在 logcat 里**（LSPosed 把模块日志写进自己的文件：
  `/data/adb/lspd/log/modules_*.log`、`verbose_*.log`）。
  ⇒ 我前几轮那些"零相机日志"的观察**都不足以支撑结论**（当时只 tail 了那个文件，没全量搜）。
- **权威源**：`/data/adb/lspd/log/modules_*.log`（本轮：摄像头模拟 45 条、静默失败记账 2 条、
  camerafixture 34 条）。下一轮从这里入手读"两道门到底走了哪一支"。
