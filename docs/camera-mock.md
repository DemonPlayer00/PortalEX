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
