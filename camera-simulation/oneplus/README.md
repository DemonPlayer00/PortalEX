# OnePlus 实现

复用 `oneplus-provider` 的 AIDL V2 provider 代码，新增 `frame_reader.cpp`：

- 读取 `/data/local/tmp/portalex/cammock.frames` 的 NV21 槽位；
- 仅当 `enabled != 0` 时接管；
- 每次 `processCaptureRequest` 对头部 `consumer_req_seq` 自增；
- YUV 流按 cover 缩放写入输出 buffer；JPEG 流先转 RGB 再交给原 libjpeg 编码。

构建和部署见上一级 `README.md`。这个目录只关心“把标准帧通道接到 OnePlus 相机会话”，
播放状态机、load/pause/limit/loop/stay 全部在 app 侧。
