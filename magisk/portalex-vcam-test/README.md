# portalex-vcam-test

MI6（sagit / LineageOS 22.2 / Android 15）测试模块。

- `system/vendor/lib/hw/camera.msm8998.so` = `vcam.so`（虚拟 camera HAL）
- `system/vendor/lib/hw/camera.msm8998.orig.so` = 原厂 HAL 副本（只用于转发相机元数据/请求模板）
- provider 仍是系统的 `android.hardware.camera.provider@2.4-service`，它按原路径加载 HAL；
  本模块只替换 HAL，不替换 provider，不改 /vendor。

停用模块 + 重启即恢复原相机。
