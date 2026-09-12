# PortalEX Camera HAL Shim（M0：**暂不可用，不要安装**）

> 状态（2026-09-12）：**实验性、当前会让相机不可用**。MI6 上的模块已用 `disable` 停用。
> 保留本目录是为了固定"拦截点可达"这一结论与复现脚本，不是可发布的成品。

## 它想做什么

摄像头模拟的**数据面**：对目标应用零注入，把 vendor 摄像头 HAL 输出 buffer 的画面
换成视频点的素材。部署形态是 Magisk 模块（覆盖 `/vendor/lib/hw/camera.msm8998.so`）。

## 已经验证到的（真机，MI6 / Android 15 / LOS 22.2）

| 结论 | 证据 |
|---|---|
| 拦截点可达：我们的 .so 会被 camera provider 加载 | `I PortalCamShim: 真实 HAL 已加载：/vendor/lib/hw/camera.msm8998.orig.so（id=camera name=QCamera Module api=0x0204）` |
| 原件可以被我们 dlopen（含 SEAndroid） | 同上；需 `sepolicy.rule` 用 **magiskpolicy 的逗号语法**（`allow hal_camera_default system_file:file read,open,getattr,map,execute`），`{ }` 花括号写法**静默不生效** |
| 覆盖与回退都是干净的 | 覆盖后 `/vendor/lib/hw/camera.msm8998.so` = 11648 字节 shim；停用模块后 provider PID 恒定、相机照常 |

## 卡在哪（下一步要解的）

provider 在 `CameraModule::init()` 里 **SIGABRT**，每 5 秒被 init 重启一次（相机整机不可用）：

```
Abort message: 'Assertion failed: __builtin_mul_overflow(new_capacity, mItemSize, &new_allocation_size)'
#03 android::VectorImpl::setCapacity(unsigned int)+92            (libutils.so)
#04 CameraModule::init()+100                                     (android.hardware.camera.provider@2.4-legacy.so)
#05 LegacyCameraProviderImpl_2_4::initialize()+98
#07 HIDL_FETCH_ICameraProvider+96                                (…@2.4-impl.so)
```

要点：崩溃发生在 **init 里、还没走到我们的 `open()`**（调用栈里没有 shim 帧），
所以不是"转发 open 转错了"，而是 init 读到的某个字段与真实模块不一致。
已排除：`HARDWARE_MODULE_TAG`（已修正为 `'HWMT'`=0x48574D54，改前连载入都过不了）、
`id/name/author/api_version`（已改为照抄原件）。

下一步二选一：
1. 反汇编 `CameraModule::init()+0x64`（`objdump -d --start-address` 拉出来的
   `@2.4-legacy.so`），看它在 Vector 操作前读的是哪几个字段；
2. 换拦截点：包 `HIDL_FETCH_ICameraProvider`（provider impl 的 C++ 入口），
   完全绕开 `hw_get_module`/`CameraModule` 的语义 —— 更"透明"，但需要 HIDL 接口头。

## 复现

```sh
sh scripts/deploy-camera-hal.sh          # 构建 + 推送 + 建模块目录（需设备已 root）
# 回退：touch /data/adb/modules/portalex-camera-hal/disable && adb reboot
```
