#include "video_source.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <unistd.h>
#include <algorithm>
#include <cstring>
#include <vector>
#include <dlfcn.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PortalVcam", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PortalVcam", __VA_ARGS__)

namespace portal {
namespace {

using Nv12ToI420Fn = int (*)(const uint8_t*, int, const uint8_t*, int,
                             uint8_t*, int, uint8_t*, int, uint8_t*, int, int, int);
using Nv21ToI420Fn = Nv12ToI420Fn;
using I420ScaleFn = int (*)(const uint8_t*, int, const uint8_t*, int, const uint8_t*, int,
                            int, int, uint8_t*, int, uint8_t*, int, uint8_t*, int,
                            int, int, int);

struct LibYuv {
    void* handle = nullptr;
    Nv12ToI420Fn nv12 = nullptr;
    Nv21ToI420Fn nv21 = nullptr;
    I420ScaleFn scale = nullptr;
    bool load() {
        if (handle) return true;
        handle = dlopen("libyuv.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) return false;
        nv12 = (Nv12ToI420Fn)dlsym(handle, "NV12ToI420");
        nv21 = (Nv21ToI420Fn)dlsym(handle, "NV21ToI420");
        scale = (I420ScaleFn)dlsym(handle, "I420Scale");
        return nv12 && nv21 && scale;
    }
};

LibYuv& libyuv() { static LibYuv l; return l; }


struct Decoder {
    int fd = -1;
    int sourceFd = -1;
    AMediaExtractor* extractor = nullptr;
    AMediaCodec* codec = nullptr;
    int width = 0;
    int height = 0;
    int64_t startUs = 0;
    int64_t loopStartUs = 0;
    int64_t durationUs = 0;
    int64_t frameIntervalUs = 33333;
    int rotation = 0;
    bool eos = false;

    bool open(int targetFd, int64_t startMs) {
        close();
        sourceFd = targetFd;
        fd = dup(targetFd);
        if (fd < 0) return false;
        extractor = AMediaExtractor_new();
        if (!extractor) { close(); return false; }
        if (AMediaExtractor_setDataSourceFd(extractor, fd, 0, 1LL << 40) != AMEDIA_OK) {
            LOGE("video: setDataSourceFd failed");
            close();
            return false;
        }
        ssize_t track = -1;
        int count = AMediaExtractor_getTrackCount(extractor);
        for (int i = 0; i < count; i++) {
            AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor, i);
            const char* mime = nullptr;
            if (fmt && AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) &&
                mime && strncmp(mime, "video/", 6) == 0) {
                int32_t w = 0, h = 0, rot = 0;
                AMediaFormat_getInt32(fmt, "rotation-degrees", &rot);
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                    rotation = rot;
                    track = i;
                    AMediaFormat_delete(fmt);
                    break;
                }
            }
            if (fmt) AMediaFormat_delete(fmt);
        }
        if (track < 0) { close(); return false; }
        AMediaExtractor_selectTrack(extractor, (size_t)track);
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor, (size_t)track);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        AMediaCodec* c = AMediaCodec_createDecoderByType(mime ? mime : "video/avc");
        if (!c) { if (fmt) AMediaFormat_delete(fmt); close(); return false; }
        // ByteBuffer 输出模式：不走 ImageReader Surface。
        // 很多厂商 H.264 decoder 不接受 YUV_420_888 的 ImageReader 输出面，
        // 直接让 MediaCodec 输出 flexible YUV，再用 AMediaCodec_getOutputImage 取图。
        if (AMediaCodec_configure(c, fmt, nullptr, nullptr, 0) != AMEDIA_OK) {
            LOGE("video: codec configure failed %dx%d", width, height);
            AMediaCodec_delete(c);
            if (fmt) AMediaFormat_delete(fmt);
            close();
            return false;
        }
        AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &durationUs);
        float fps = 0.f;
        if (AMediaFormat_getFloat(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, &fps) && fps > 1.f) {
            frameIntervalUs = (int64_t)(1000000.0 / fps);
        }
        loopStartUs = durationUs > 3000000LL ? durationUs - 3000000LL : 0;
        AMediaFormat_delete(fmt);
        codec = c;
        if (AMediaCodec_start(codec) != AMEDIA_OK) { LOGE("video: codec start failed"); close(); return false; }
        LOGE("video: decoder started %dx%d rotation=%d", width, height, rotation);
        startUs = startMs * 1000;
        // selection 的 startMs 来自原视频轨迹；测试视频更短时可能超出文件末尾。
        // 超出时从 0 开始，而不是 seek 到不存在的位置后反复 EOS。
        if (durationUs > 0 && startUs >= durationUs) startUs = 0;
        AMediaExtractor_seekTo(extractor, startUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        return true;
    }

    void seekTo(int64_t us) {
        if (!extractor) return;
        AMediaExtractor_seekTo(extractor, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        if (codec) AMediaCodec_flush(codec);
        eos = false;
    }

    void seekStart() { seekTo(startUs); }

    /** 到达末尾后不回到起点，而是取最后 3 秒循环。 */
    void seekLoopTail() {
        const int64_t wall = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        LOGE("video: probe loop wall_ms=%lld from=%lldms",
             (long long)(wall / 1000000), (long long)(loopStartUs / 1000));
        seekTo(loopStartUs);
    }

    bool decodeOneFrame(void* y, void* cb, void* cr, size_t yStride, size_t cStride,
                        size_t chromaStep, int dstW, int dstH) {
        if (!codec) return false;
        for (int loop = 0; loop < 12; loop++) {
            ssize_t in = AMediaCodec_dequeueInputBuffer(codec, 3000);
            if (in >= 0) {
                size_t cap = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec, (size_t)in, &cap);
                ssize_t sample = AMediaExtractor_readSampleData(extractor, buf, cap);
                int64_t pts = AMediaExtractor_getSampleTime(extractor);
                if (sample < 0) {
                    AMediaCodec_queueInputBuffer(codec, (size_t)in, 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eos = true;
                } else {
                    AMediaCodec_queueInputBuffer(codec, (size_t)in, 0, (size_t)sample, pts, 0);
                    AMediaExtractor_advance(extractor);
                }
            }
            AMediaCodecBufferInfo info{};
            ssize_t out = AMediaCodec_dequeueOutputBuffer(codec, &info, 3000);
            if (out >= 0) {
                size_t size = 0;
                uint8_t* data = AMediaCodec_getOutputBuffer(codec, (size_t)out, &size);
                bool ok = false;
                if (data != nullptr && info.size > 0) {
                    AMediaFormat* of = AMediaCodec_getOutputFormat(codec);
                    int color = 0, stride = width, slice = height;
                    if (of != nullptr) {
                        AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_COLOR_FORMAT, &color);
                        AMediaFormat_getInt32(of, "stride", &stride);
                        AMediaFormat_getInt32(of, "slice-height", &slice);
                        AMediaFormat_delete(of);
                    }
                    if (stride <= 0) stride = width;
                    if (slice <= 0) slice = height;
                    ok = copyBuffer(data, size, color, stride, slice, width, height,
                                    y, cb, cr, yStride, cStride, chromaStep, dstW, dstH,
                                    rotation == 270);
                }
                AMediaCodec_releaseOutputBuffer(codec, (size_t)out, false);
                if (ok) return true;
            } else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* of = AMediaCodec_getOutputFormat(codec);
                if (of != nullptr) {
                    int color = 0, stride = 0, slice = 0;
                    AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_COLOR_FORMAT, &color);
                    AMediaFormat_getInt32(of, "stride", &stride);
                    AMediaFormat_getInt32(of, "slice-height", &slice);
                    LOGE("video: output format color=0x%x stride=%d slice=%d", color, stride, slice);
                    AMediaFormat_delete(of);
                }
            }
            if (eos) {
                seekLoopTail();
                return false;
            }
        }
        return false;
    }

    static bool copyBuffer(const uint8_t* data, size_t size, int color, int srcStride,
                           int srcSlice, int srcW, int srcH,
                           void* y, void* cb, void* cr,
                           size_t yStride, size_t cStride, size_t chromaStep,
                           int dstW, int dstH, bool flip180) {
        if (data == nullptr || size == 0 || dstW <= 0 || dstH <= 0) return false;
        // 常见输出：19=YUV420Planar，21=YUV420SemiPlanar，0x7F420888=Flexible。
        // Flexible 的具体布局由 codec 报的 color-format 决定；未知时按 SemiPlanar 尝试。
        bool planar = (color == 19);
        bool semi = (color == 21);
        if (!planar && !semi) semi = true; // flexible/unknown: 先按 NV12/NV21 布局
        const int ySize = srcStride * srcSlice;
        if ((size_t)ySize > size) return false;
        uint8_t* dy = (uint8_t*)y;
        uint8_t* du = (uint8_t*)cb;
        uint8_t* dv = (uint8_t*)cr;
        for (int row = 0; row < dstH; row++) {
            int sy = row * srcH / dstH;
            if (sy >= srcSlice) sy = srcSlice - 1;
            std::memcpy(dy + (size_t)row * yStride, data + (size_t)sy * srcStride, (size_t)dstW);
        }
        int cw = (dstW + 1) / 2;
        int ch = (dstH + 1) / 2;
        if (semi) {
            // 优先交给系统 libyuv 完成 NV12/NV21 -> I420 -> 缩放，
            // 避免手写 chroma 偏移在厂商 Codec 上出错。
            LibYuv& ly = libyuv();
            if (ly.load()) {
                // 按请求方宽高比做 center-crop：不拉伸、不留黑边。
                // 不读取 rotation-degrees；调用方期望什么宽高比，就裁成什么比例。
                const int dstAspectNum = dstW;
                const int dstAspectDen = dstH;
                int cropW = srcW;
                int cropH = srcH;
                if (srcW * dstAspectDen > srcH * dstAspectNum) {
                    cropW = srcH * dstAspectNum / dstAspectDen;
                } else {
                    cropH = srcW * dstAspectDen / dstAspectNum;
                }
                cropW &= ~1;
                cropH &= ~1;
                if (cropW < 2 || cropH < 2) { cropW = srcW & ~1; cropH = srcH & ~1; }
                const int cropX = (srcW - cropW) / 2;
                const int cropY = (srcH - cropH) / 2;
                const uint8_t* srcY = data + (size_t)cropY * srcStride + cropX;
                const uint8_t* srcUV = data + ySize + (size_t)(cropY / 2) * srcStride + (size_t)(cropX / 2) * 2;
                std::vector<uint8_t> i420((size_t)cropW * cropH * 3 / 2);
                std::vector<uint8_t> scaled((size_t)dstW * dstH * 3 / 2);
                uint8_t* iy = i420.data();
                uint8_t* iu = iy + (size_t)cropW * cropH;
                uint8_t* iv = iu + (size_t)(cropW / 2) * (cropH / 2);
                int rc = ly.nv12(srcY, srcStride, srcUV, srcStride,
                                 iy, cropW, iu, cropW / 2, iv, cropW / 2,
                                 cropW, cropH);
                if (rc != 0) {
                    rc = ly.nv21(srcY, srcStride, srcUV, srcStride,
                                 iy, cropW, iu, cropW / 2, iv, cropW / 2,
                                 cropW, cropH);
                }
                if (rc == 0) {
                    uint8_t* sy = scaled.data();
                    uint8_t* su = sy + (size_t)dstW * dstH;
                    uint8_t* sv = su + (size_t)(dstW / 2) * (dstH / 2);
                    if (ly.scale(iy, cropW, iu, cropW / 2, iv, cropW / 2, cropW, cropH,
                                 sy, dstW, su, dstW / 2, sv, dstW / 2,
                                 dstW, dstH, 0) == 0) {
                        // 只有源视频带 270° 旋转时才是倒置的横向帧；
                        // 无旋转的测试视频保持原方向，避免把正常数字倒过来。
                        if (flip180) {
                            for (int row = 0; row < dstH; row++) {
                                const uint8_t* src = sy + (size_t)(dstH - 1 - row) * dstW;
                                uint8_t* dst = (uint8_t*)y + (size_t)row * yStride;
                                for (int col = 0; col < dstW; col++) {
                                    dst[col] = src[dstW - 1 - col];
                                }
                            }
                            for (int row = 0; row < ch; row++) {
                                for (int col = 0; col < cw; col++) {
                                    size_t dst = (size_t)row * cStride + (size_t)col * chromaStep;
                                    int srcRow = ch - 1 - row;
                                    int srcCol = cw - 1 - col;
                                    du[dst] = su[(size_t)srcRow * (dstW / 2) + srcCol];
                                    dv[dst] = sv[(size_t)srcRow * (dstW / 2) + srcCol];
                                }
                            }
                        } else {
                            for (int row = 0; row < dstH; row++) {
                                std::memcpy((uint8_t*)y + (size_t)row * yStride,
                                            sy + (size_t)row * dstW, (size_t)dstW);
                            }
                            for (int row = 0; row < ch; row++) {
                                for (int col = 0; col < cw; col++) {
                                    size_t dst = (size_t)row * cStride + (size_t)col * chromaStep;
                                    du[dst] = su[(size_t)row * (dstW / 2) + col];
                                    dv[dst] = sv[(size_t)row * (dstW / 2) + col];
                                }
                            }
                        }
                        return true;
                    }
                }
            }
            const uint8_t* uv = data + ySize;
            for (int row = 0; row < ch; row++) {
                int sy = row * srcH / dstH;
                if (sy >= srcSlice) sy = srcSlice - 1;
                const uint8_t* srow = uv + (size_t)sy * srcStride;
                for (int col = 0; col < cw; col++) {
                    int sx = col * srcW / dstW;
                    size_t si = (size_t)sx * 2;
                    if ((size_t)si + 1 >= (size_t)(srcStride * srcH)) continue;
                    size_t dst = (size_t)row * cStride + (size_t)col * chromaStep;
                    du[dst] = srow[si];
                    dv[dst] = srow[si + 1];
                }
            }
        } else {
            const uint8_t* u = data + ySize;
            const uint8_t* v = u + (size_t)(srcStride / 2) * (srcSlice / 2);
            for (int row = 0; row < ch; row++) {
                int sy = row * srcH / dstH;
                for (int col = 0; col < cw; col++) {
                    int sx = col * srcW / dstW;
                    du[(size_t)row * cStride + col * 2] = u[(size_t)sy * (srcStride / 2) + sx];
                    dv[(size_t)row * cStride + col * 2] = v[(size_t)sy * (srcStride / 2) + sx];
                }
            }
        }
        // 相机目标 buffer 的最后两行在部分 Codec/stride 组合下会保留旧内容
        // （表现为底部绿色条）；用倒数第三行覆盖这两行，避免绿边。
        if (dstH >= 3) {
            const uint8_t* last = (const uint8_t*)y + (size_t)(dstH - 3) * yStride;
            for (int row = dstH - 2; row < dstH; row++) {
                std::memcpy((uint8_t*)y + (size_t)row * yStride, last, (size_t)dstW);
            }
        }
        return true;
    }

    void close() {
        if (codec) { AMediaCodec_stop(codec); AMediaCodec_delete(codec); codec = nullptr; }
        if (extractor) { AMediaExtractor_delete(extractor); extractor = nullptr; }
        if (fd >= 0) { ::close(fd); fd = -1; }
        sourceFd = -1;
    }
};

Decoder g_decoder;

}  // namespace

namespace {
std::vector<uint8_t> gCacheY, gCacheCb, gCacheCr;
size_t gCacheYStride = 0, gCacheCStride = 0;
int gCacheW = 0, gCacheH = 0;
bool gCacheValid = false;
int64_t gNextPresentNs = 0;
int64_t gDecodedFrames = 0;
int64_t gCacheHits = 0;
int64_t gLastDecodeWallNs = 0;
}  // namespace

static void cacheFrame(void* y, void* cb, void* cr, size_t yStride, size_t cStride,
                       int dstW, int dstH) {
    const size_t ch = (size_t)(dstH + 1) / 2;
    gCacheY.resize(yStride * (size_t)dstH);
    gCacheCb.resize(cStride * ch);
    gCacheCr.resize(cStride * ch);
    std::memcpy(gCacheY.data(), y, yStride * (size_t)dstH);
    std::memcpy(gCacheCb.data(), cb, cStride * ch);
    std::memcpy(gCacheCr.data(), cr, cStride * ch);
    gCacheYStride = yStride;
    gCacheCStride = cStride;
    gCacheW = dstW;
    gCacheH = dstH;
    gCacheValid = true;
}

static bool restoreFrame(void* y, void* cb, void* cr, size_t yStride, size_t cStride,
                         int dstW, int dstH) {
    if (!gCacheValid || gCacheW != dstW || gCacheH != dstH ||
        gCacheYStride != yStride || gCacheCStride != cStride) return false;
    const size_t ch = (size_t)(dstH + 1) / 2;
    std::memcpy(y, gCacheY.data(), yStride * (size_t)dstH);
    std::memcpy(cb, gCacheCb.data(), cStride * ch);
    std::memcpy(cr, gCacheCr.data(), cStride * ch);
    return true;
}

bool video_source_play(int fd, int64_t startMs, void* y, void* cb, void* cr,
                       size_t yStride, size_t cStride, size_t chromaStep,
                       int dstW, int dstH) {
    if (fd < 0) return false;
    LOGE("video: play fd=%d start=%lld dst=%dx%d", fd, (long long)startMs, dstW, dstH);
    if (g_decoder.sourceFd != fd) {
        if (!g_decoder.open(fd, startMs)) { LOGE("video: open failed"); return false; }
        gCacheValid = false;
        gNextPresentNs = 0;
        gLastDecodeWallNs = 0;
        LOGE("video: probe reset fd=%d start=%lld", fd, (long long)startMs);
    }
    const int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    // 摄像头请求可能高于视频帧率：按视频帧时间复用上一帧，禁止“按请求数快进”。
    if (gCacheValid && now < gNextPresentNs) {
        if (restoreFrame(y, cb, cr, yStride, cStride, dstW, dstH)) {
            gCacheHits++;
            if (gCacheHits % 100 == 1) LOGE("video: cache hit %lld decoded=%lld",
                                           (long long)gCacheHits, (long long)gDecodedFrames);
            return true;
        }
    }
    const bool ok = g_decoder.decodeOneFrame(y, cb, cr, yStride, cStride, chromaStep, dstW, dstH);
    if (!ok && gCacheValid && restoreFrame(y, cb, cr, yStride, cStride, dstW, dstH)) {
        // EOS/seek 的过渡帧：保持上一帧，不要交给 fillBuffer 画黑帧。
        const int64_t limitIntervalNs = 1000000000LL / 30;
        const int64_t interval = std::max<int64_t>(
                g_decoder.frameIntervalUs * 1000LL, limitIntervalNs);
        gNextPresentNs = now + interval;
        return true;
    }
    if (ok) {
        gDecodedFrames++;
        const int64_t wall = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        const double dtMs = gLastDecodeWallNs == 0 ? 0.0 :
                (wall - gLastDecodeWallNs) / 1000000.0;
        gLastDecodeWallNs = wall;
        LOGE("video: probe frame=%lld dt_ms=%.1f next_ms=%.1f",
              (long long)gDecodedFrames, dtMs, (gNextPresentNs - wall) / 1000000.0);
        cacheFrame(y, cb, cr, yStride, cStride, dstW, dstH);
        // 默认 LIMIT=30：即使源视频/摄像头请求更快，也保证 30 帧 ≈ 1 秒。
        const int64_t limitIntervalNs = 1000000000LL / 30;
        const int64_t interval = std::max<int64_t>(
                g_decoder.frameIntervalUs * 1000LL, limitIntervalNs);
        gNextPresentNs = now + interval;
    }
    return ok;
}

void video_source_close() { g_decoder.close(); }

}  // namespace portal
