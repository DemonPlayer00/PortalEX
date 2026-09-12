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
| 真实视频帧喂进 HAL（解码 + 帧通道） | ⛔ 未做：app 侧解码 + 共享内存通道，设计见 §10 |
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
