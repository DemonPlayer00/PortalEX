# camera-simulation

PortalEX 的摄像头模拟子项目。目标是把「设备相关的相机接管」和「PortalEX 的播放控制」分开：

```text
PortalEX app
  VcamFramePlayer (interface/VcamFramePlayer.kt)
  CameraFrameSink  →  /data/local/tmp/portalex/cammock.frames
                              │
                              ▼ 相机 request 时才读
设备实现 (oneplus/PKG110/provider)
  frame_reader.cpp  →  YUV/JPEG 相机流
```

## 目录

- `interface/VcamFramePlayer.kt` — 标准播放状态机：load / pause / resume / limit / loop / stay。
- `interface/PROTOCOL.md` — 共享帧文件协议与语义。
- `android/VcamDebugActivity.kt` — 临时隐藏控制器（测试用，最终应移除或隐藏入口）。
- `oneplus/PKG110/provider/` — OnePlus AIDL provider 接管实现，含 `frame_reader.cpp`。

## 当前 OnePlus 实现

- 停 `vendor.qti.camera.provider-service_64`；
- 注册 `vendor_qti/0`，前置 camera id `1`；
- provider 的 `processCaptureRequest` 每次只做两件事：
  1. 递增共享帧文件的 `consumer_req_seq`；
  2. 从最新槽位读 NV21，写进相机 YUV/JPEG。
- 没有相机 request 时不读帧、不转换、不复制。

## 构建

```sh
# app
./gradlew :app:assembleArm64Debug

# provider
cd camera-simulation/oneplus/PKG110/provider
cmake -S . -B build \
  -DCMAKE_TOOLCHAIN_FILE=$HOME/Android/Sdk/ndk/27.3.13750724/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-35 -DANDROID_STL=c++_static
cmake --build build -j4
```

## 临时测试

```sh
# 共享帧文件（模块正式版应由 post-fs-data.sh 预建）
adb shell 'mkdir -p /data/local/tmp/portalex; \
  truncate -s 12849152 /data/local/tmp/portalex/cammock.frames; \
  chmod 777 /data/local/tmp/portalex; chmod 666 /data/local/tmp/portalex/cammock.frames'

# 安装 app、启动隐藏控制器
adb install -r app/build/outputs/apk/arm64/debug/Portal-v1.0.4.r535.787ec9f-arm64.apk
adb shell 'pm grant moe.fuqiuluo.portalex android.permission.CAMERA'
adb shell 'am start -n moe.fuqiuluo.portalex/.vcam.VcamDebugActivity --ez auto true'
```

隐藏控制器自带 camera 1 预览；加载内置人脸后应显示替换图。
Clear 后预览为黑帧，验证 `stay=false` / 清空路径。

### 镜像约定

OnePlus 前摄预览会做一次水平镜像。不要在 app、`frame_reader`、`faceSample`
三处同时翻转，否则会出现 180° 旋转或“镜像/正像”随帧切换。

当前约定：**源图只预镜像一次**。

- `app/src/main/assets/vcam_face.jpg` 是水平预镜像图；
- `oneplus/PKG110/provider/assets/face.rgb` 是同一张图的 RGB24 版本，
  部署到 `/sdcard/GlaDos_File/OnePlusProvider/face.rgb`；
- 运行时不再做任何水平/垂直翻转。

## 录制轨迹与真/假切换

- `CameraVideo` 新增 `recordedAt` 与 `track: List<CameraVideoTrackPoint>`；
  `CameraRecordActivity` 录制期间每秒采样一次 `CameraMockRuntime.position()`，
  通过 Activity result 的 `track_json` / `recorded_at` 交给视频点编辑页。
- 视频列表显示录制时间、轨迹点数；标签预设新增雨/雪/风/雾，仍支持任意自定义标签。
- 隐藏控制器新增“虚拟相机（关 = 真机）”开关：假 = 停 vendor provider 并启动
  `portal_camera_provider`；真 = 杀 provider 并启动 `vendor.camera-provider`。
  这是调试用的 `su` 路径，正式版应改为模块提供的受控命令。

## 正式化前要做

- 把隐藏 Activity 从 manifest 移除，或加 `android:exported=false` + 仅调试构建；
- 共享帧文件由 KernelSU/APatch 模块预建并打 SELinux 标签；
- 用 `post-fs-data.sh` / `service.sh` 启停 OnePlus provider；
- `VcamFramePlayer` 增加单元测试（pause 索引、limit 重复帧、loop/ENDED）。
