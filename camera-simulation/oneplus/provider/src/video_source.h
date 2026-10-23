#pragma once
#include <cstdint>
#include <cstddef>

namespace portal {

/** 用 MediaCodec 把 selection fd 解码成 YUV420，供相机输出面使用。 */
bool video_source_play(int fd, int64_t startMs, void* y, void* cb, void* cr,
                       size_t yStride, size_t cStride, size_t chromaStep,
                       int dstW, int dstH);
void video_source_close();

}  // namespace portal
