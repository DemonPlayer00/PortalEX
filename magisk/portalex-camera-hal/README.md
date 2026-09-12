# PortalEX Camera HAL Shim

> **状态（2026-09-12）：冻结归档。** 完整的设计、实测事实表、卡点与"从哪接着做"见
> [`docs/camera-mock.md`](../../docs/camera-mock.md)。
> **线上当前配置是安全的**：模块启用中，但挂的是 `camshim.so`（外层包住 vendor HAL、无注入），
> 真实相机直通、provider 稳定。`vcam.so`（自建虚拟摄像头）会产帧但会话链路未走通。

## camshim（当前挂载的这个）

shim 跑在 camera provider 进程里、帧真的经过我们手里、相机行为不变；
**画面替换未实现**（在这批 buffer 上被证明既不能 CPU 写也不能 EGL 画，见归档文档 §5.2）。

## 它是什么

摄像头模拟的**数据面**：对目标应用零注入，把 vendor 摄像头 HAL 输出的画布换成视频点素材。
部署形态是 Magisk 模块（覆盖 `/vendor/lib/hw/camera.msm8998.so`）。
拦在 HAL 层的好处：**Camera1 / Camera2 / CameraX / NDK 全部覆盖**（不像应用侧 hook 只能覆盖某一种 API）。

## 真机验证（MI6 / Android 15 / LineageOS 22.2 / msm8998，provider 为 32 位）

```
I PortalCamShim: 真实 HAL 已加载并整块接管：/vendor/lib/hw/camera.msm8998.orig.so
                 （tag=0x48574d54 id=camera name=QCamera Module api=0x0204）
I PortalCamShim: 已接管 process_capture_request（ops[4]）
I PortalCamShim: process_capture_request 已接管：#1 / #120 / #600 / #720   ← 约 29.7 fps
```
同时：provider PID 稳定不重启、`/data/tombstones` 无新增、相机预览画面正常。

## 四个坑（每条都让相机挂过一次，别改回去）

1. **`HARDWARE_MODULE_TAG` 必须精确是 `'HWMT'`（0x48574D54）**。写错不是"不生效"，而是
   `hw_get_module` 判定"不是 HAL 模块" ⇒ provider 每 5 秒 SIGABRT ⇒ **整机相机不可用**。
2. **模块入口必须留足空间并整块照抄真实模块**：真实 `HMI` 是 **`camera_module_t`（176 字节）**，
   含 `get_number_of_cameras` 等回调。照教科书只声明 `hw_module_t`（128 字节）会让
   `CameraModule::init()` 读到相邻内存当"相机数量" ⇒ `libutils` Vector 容量断言溢出 ⇒
   init 每 5 秒重启 provider。现在留 512 字节 + `memcpy` 176 字节 + 只改 `methods`。
3. **改完 ops 表要按 `/proc/self/maps` 的原始权限逐页还原**：QTI 的 ops 表与 HAL 的 `.bss`
   静态量同页且本来就**可写**；按"只读"还原会让 HAL 写自己的函数内静态量时 SEGV_ACCERR
   （tombstone: `translateCapabilityToMetadata` → `__cxa_guard_acquire`）。
4. **SELinux 两件事**：① 原件副本放**同一个 vendor 库目录**换名字（放 `/data/adb` 会被标成
   `adb_data_file`，HAL 域读不了，dlopen 报 "library not found" 实为 EACCES）；
   ② `magiskpolicy` 的语句格式是 `allow <source> <target> <class> { perms }` ——
   写成 `target:class p1,p2` 会报 Syntax error 且**静默不生效**。另外实测模块经过
   disable → enable 切换后 `sepolicy.rule` 有时不再被应用，所以 `post-fs-data.sh` 里
   再用 `magiskpolicy --live` 打一次。

## 下一步（M1）

在 `shim_process_capture_request` 里把解码后的视频帧写进 `request->output_buffers[i].buffer`
（gralloc 句柄；格式/stride 按 stream 元数据，先支持 YUV_420_888 / IMPLEMENTATION_DEFINED）。
素材通道：HAL 进程读不到 app 私有目录，需 root 侧把素材搬到一个可读路径并补 SELinux 规则。

## 复现与回退

```sh
sh scripts/deploy-camera-hal.sh          # 构建 + 推送 + 建模块目录（需设备已 root）
# 回退：touch /data/adb/modules/portalex-camera-hal/disable && adb reboot
```
装机后**必须重启**（模块代码只在开机时被 provider 加载）。
