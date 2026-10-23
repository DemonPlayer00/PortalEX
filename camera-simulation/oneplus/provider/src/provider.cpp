#include "provider.h"
#include "frame_reader.h"
#include "selection_client.h"
#include "video_source.h"
#include "camera_metadata_tags.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <android/log.h>
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "PortalProvider", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PortalProvider", __VA_ARGS__)
#include <time.h>
#include <unistd.h>

#include <cstring>
#include <chrono>
#include <thread>
#include <vector>

#include <aidl/android/hardware/camera/device/BufferStatus.h>
#include <aidl/android/hardware/camera/device/RequestTemplate.h>
#include <aidl/android/hardware/camera/provider/ICameraProvider.h>
#include <aidl/android/hardware/graphics/common/BufferUsage.h>
#include <aidl/android/hardware/graphics/common/Dataspace.h>
#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <aidl/android/hardware/common/NativeHandle.h>

using namespace aidl::android::hardware::camera;
using namespace aidl::android::hardware::camera::device;
using namespace aidl::android::hardware::camera::provider;
using namespace aidl::android::hardware::camera::common;
using aidl::android::hardware::graphics::common::BufferUsage;
using aidl::android::hardware::graphics::common::Dataspace;
using aidl::android::hardware::graphics::common::PixelFormat;

extern "C" bool portal_jpeg_encode_rgb(const uint8_t* rgb, int w, int h, int quality,
                                       uint8_t** out, size_t* out_len);

namespace portal {
std::mutex gSelectionMutex;
ServerSelection gSelection;
static uint64_t gOutputSeq = 0;
std::atomic<bool> gManualMode{false};

namespace {

struct native_handle_t {
    int version;
    int numFds;
    int numInts;
    int data[1];
};

native_handle_t* nh_create(int nfds, int nints) {
    size_t bytes = sizeof(native_handle_t) + (size_t)(nfds + nints - 1) * sizeof(int);
    native_handle_t* h = (native_handle_t*)calloc(1, bytes);
    if (!h) return nullptr;
    h->version = 12;
    h->numFds = nfds;
    h->numInts = nints;
    return h;
}
void nh_close(native_handle_t* h) {
    if (!h) return;
    for (int i = 0; i < h->numFds; i++) close(h->data[i]);
}
void nh_delete(native_handle_t* h) { free(h); }

constexpr const char* kFakeCameraId = "device@1.1/vendor_qti/1";
constexpr uint64_t kPreviewUsage = 0x33 | 0x20000 | 0x40000 | 0x100 | 0x200;

struct Rect {
    int32_t left, top, right, bottom;
};
struct YCbCr {
    void* y;
    void* cb;
    void* cr;
    size_t ystride;
    size_t cstride;
    size_t chroma_step;
    uint32_t reserved[8];
};

struct Mapper {
    void* self = nullptr;
    int (*preload)(void*) = nullptr;
    int (*import)(void*, void*, void**) = nullptr;
    int (*freeBuf)(void*, void*) = nullptr;
    int (*lockYCbCr)(void*, void*, uint32_t, const Rect*, YCbCr*) = nullptr;
    int (*lock)(void*, void*, uint32_t, const Rect*, void**) = nullptr;
    int (*unlock)(void*, void*, void*) = nullptr;

    bool init() {
        if (self) return true;
        void* h = dlopen("libui.so", RTLD_NOW | RTLD_LOCAL);
        if (!h) { ALOGE("provider: dlopen libui failed: %s", dlerror()); return false; }
        preload = (int (*)(void*))dlsym(h, "_ZN7android19GraphicBufferMapper10preloadHalEv");
        import = (int (*)(void*, void*, void**))dlsym(
                h, "_ZN7android19GraphicBufferMapper22importBufferNoValidateEPK13native_handlePS3_");
        freeBuf = (int (*)(void*, void*))dlsym(h, "_ZN7android19GraphicBufferMapper10freeBufferEPK13native_handle");
        void** slot = (void**)dlsym(h, "_ZN7android9SingletonINS_19GraphicBufferMapperEE9sInstanceE");
        self = slot ? *slot : nullptr;
        if (!self && slot) {
            // 手工构造 GraphicBufferMapper 单例（构造函数未走 Singleton::getInstance）。
            auto ctor = (void (*)(void*))dlsym(h, "_ZN7android19GraphicBufferMapperC1Ev");
            if (ctor) {
                self = calloc(1, 64);
                ctor(self);
                *slot = self;
            }
        }
        lockYCbCr = (int (*)(void*, void*, uint32_t, const Rect*, YCbCr*))dlsym(
                h, "_ZN7android19GraphicBufferMapper9lockYCbCrEPK13native_handlejRKNS_4RectEP13android_ycbcr");
        lock = (int (*)(void*, void*, uint32_t, const Rect*, void**))dlsym(
                h, "_ZN7android19GraphicBufferMapper4lockEPK13native_handlejRKNS_4RectEPPv");
        unlock = (int (*)(void*, void*, void*))dlsym(
                h, "_ZN7android19GraphicBufferMapper6unlockEPK13native_handlePNS_4base14unique_fd_implINS4_13DefaultCloserEEE");
        ALOGE("mapper self=%p preload=%p import=%p free=%p lockY=%p lock=%p unlock=%p",
              self, (void*)preload, (void*)import, (void*)freeBuf,
              (void*)lockYCbCr, (void*)lock, (void*)unlock);
        if (!self || !import || !freeBuf || !lockYCbCr || !lock || !unlock) {
            ALOGE("provider: mapper symbols missing");
            self = nullptr;
            return false;
        }
        if (preload) preload(self);
        return true;
    }
};

Mapper& mapper() {
    static Mapper m;
    m.init();
    return m;
}

native_handle_t* toNative(const aidl::android::hardware::common::NativeHandle& nh) {
    int nfds = static_cast<int>(nh.fds.size());
    int nints = static_cast<int>(nh.ints.size());
    native_handle_t* h = nh_create(nfds, nints);
    if (!h) return nullptr;
    for (int i = 0; i < nfds; i++) h->data[i] = dup(nh.fds[i].get());
    for (int i = 0; i < nints; i++) h->data[nfds + i] = nh.ints[i];
    return h;
}

struct MetaApi {
    void* lib = nullptr;
    void* (*alloc)(size_t, size_t) = nullptr;
    int (*add)(void*, uint32_t, const void*, size_t) = nullptr;
    size_t (*size)(const void*) = nullptr;
    const char* (*name)(uint32_t) = nullptr;
    uint32_t ts = 0, af = 0, ae = 0, awb = 0;
    bool tried = false;

    void init() {
        if (tried) return;
        tried = true;
        lib = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return;
        alloc = (void* (*)(size_t, size_t))dlsym(lib, "allocate_camera_metadata");
        add = (int (*)(void*, uint32_t, const void*, size_t))dlsym(lib, "add_camera_metadata_entry");
        size = (size_t (*)(const void*))dlsym(lib, "get_camera_metadata_size");
        name = (const char* (*)(uint32_t))dlsym(lib, "get_camera_metadata_tag_name");
        if (!name) return;
        for (uint32_t s = 0; s <= 0x20; s++) {
            for (uint32_t i = 0; i <= 0x40; i++) {
                uint32_t tag = (s << 16) | i;
                const char* n = name(tag);
                if (!n) continue;
                if (!strcmp(n, "timestamp")) ts = tag;
                else if (!strcmp(n, "afState")) af = tag;
                else if (!strcmp(n, "aeState")) ae = tag;
                else if (!strcmp(n, "awbState")) awb = tag;
            }
        }
    }

    CameraMetadata build(int64_t timestamp) {
        init();
        CameraMetadata out;
        if (!alloc || !add || !size) return out;
        void* m = alloc(32, 512);
        if (!m) return out;
        if (ts) add(m, ts, &timestamp, 1);
        uint8_t afv = 4, aev = 2, awbv = 2;
        if (af) add(m, af, &afv, 1);
        if (ae) add(m, ae, &aev, 1);
        if (awb) add(m, awb, &awbv, 1);
        size_t n = size(m);
        out.metadata.assign((uint8_t*)m, (uint8_t*)m + n);
        return out;
    }
};

MetaApi& metaApi() {
    static MetaApi m;
    m.init();
    return m;
}

int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

const uint8_t kBars[8][3] = {
    {255, 0, 0}, {255, 128, 0}, {255, 255, 0}, {0, 255, 0},
    {0, 255, 255}, {0, 0, 255}, {128, 0, 255}, {255, 0, 255},
};

static std::vector<uint8_t> gFace;
static int gFaceW = 0, gFaceH = 0;

static bool loadFaceImage() {
    const char* path = getenv("PORTAL_IMAGE");
    // 不再默认加载 sdcard 上的人脸资源；没有显式 PORTAL_IMAGE 时保持黑屏。
    if (!path || !*path) return false;
    FILE* f = fopen(path, "rb");
    if (!f) {
        ALOGE("provider: face image not found: %s", path);
        return false;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    static constexpr int W = 501, H = 633;
    if (n != (long)W * H * 3) {
        ALOGE("provider: face image size %ld != %d", n, W * H * 3);
        fclose(f);
        return false;
    }
    gFace.resize((size_t)n);
    fread(gFace.data(), 1, (size_t)n, f);
    fclose(f);
    gFaceW = W;
    gFaceH = H;
    ALOGI("provider: loaded face image %dx%d", W, H);
    return true;
}

// 源图逆时针旋转 90° 后，再 cover 缩放到目标。
static void faceSample(float u, float v, uint8_t* out) {
    int rw = gFaceH, rh = gFaceW;
    float ax = u * rw, ay = v * rh;  // rotated coords
    int rx = std::max(0, std::min(rw - 1, (int)ax));
    int ry = std::max(0, std::min(rh - 1, (int)ay));
    int sx = gFaceW - 1 - ry;
    int sy = rx;
    const uint8_t* p = gFace.data() + ((size_t)sy * gFaceW + sx) * 3;
    out[0] = p[0]; out[1] = p[1]; out[2] = p[2];
}

int tri(int t, int span) {
    if (span <= 1) return 0;
    int p = t % (span * 2);
    return p < span ? p : span * 2 - p - 1;
}

void drawRgb(uint8_t* rgb, int w, int h, int frame) {
    if (!gFace.empty()) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                faceSample((x + 0.5f) / w, (y + 0.5f) / h,
                           rgb + ((size_t)y * w + x) * 3);
            }
        }
        return;
    }
    // 没有任何帧/人脸资源时输出黑帧。
    std::memset(rgb, 0, (size_t)w * h * 3);
}

void drawYuv(YCbCr& yc, int w, int h, int frame) {
    if (!gFace.empty()) {
        uint8_t p[3];
        for (int y = 0; y < h; y++) {
            uint8_t* row = (uint8_t*)yc.y + (size_t)y * yc.ystride;
            for (int x = 0; x < w; x++) {
                faceSample((x + 0.5f) / w, (y + 0.5f) / h, p);
                int Y = (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8;
                row[x] = (uint8_t)std::max(0, std::min(255, Y));
                if (yc.cb && yc.cr && yc.cstride) {
                    size_t ci = (size_t)(y / 2) * yc.cstride + (size_t)(x / 2) * yc.chroma_step;
                    ((uint8_t*)yc.cb)[ci] = (uint8_t)std::max(0, std::min(255,
                            ((-43 * p[0] - 85 * p[1] + 128 * p[2]) >> 8) + 128));
                    ((uint8_t*)yc.cr)[ci] = (uint8_t)std::max(0, std::min(255,
                            ((128 * p[0] - 107 * p[1] - 21 * p[2]) >> 8) + 128));
                }
            }
        }
        return;
    }
    // 没有任何帧/人脸资源时输出黑帧。
    for (int y = 0; y < h; y++) {
        std::memset((uint8_t*)yc.y + (size_t)y * yc.ystride, 16, (size_t)w);
        if (yc.cb && yc.cr && yc.cstride) {
            size_t ci = (size_t)(y / 2) * yc.cstride;
            std::memset((uint8_t*)yc.cb + ci, 128, (size_t)(w / 2) * yc.chroma_step);
            std::memset((uint8_t*)yc.cr + ci, 128, (size_t)(w / 2) * yc.chroma_step);
        }
    }
    return;
    int radius = std::max(8, std::min(w, h) / 7);
    int spanX = std::max(1, w - 2 * radius);
    int spanY = std::max(1, h - 2 * radius);
    int cx = radius + tri(frame * 9, spanX);
    int cy = radius + tri(frame * 5 + 17, spanY);
    int r2 = radius * radius, rin = (radius - 4) * (radius - 4);
    static const uint8_t ys[8] = {76, 150, 226, 150, 226, 29, 76, 105};
    static const uint8_t us[8] = {85, 73, 0, 44, 128, 255, 214, 171};
    static const uint8_t vs[8] = {240, 200, 149, 21, 43, 128, 197, 240};
    for (int y = 0; y < h; y++) {
        uint8_t* row = (uint8_t*)yc.y + (size_t)y * yc.ystride;
        for (int x = 0; x < w; x++) {
            int bar = ((x + frame * 7) % w) * 8 / w;
            if (bar > 7) bar = 7;
            uint8_t v = ys[bar];
            int dx = x - cx, dy = y - cy, d2 = dx * dx + dy * dy;
            if (d2 <= r2) v = d2 > rin ? 16 : 235;
            row[x] = v;
            if (yc.cb && yc.cr && yc.cstride) {
                size_t ci = (size_t)(y / 2) * yc.cstride + (size_t)(x / 2) * yc.chroma_step;
                ((uint8_t*)yc.cb)[ci] = us[bar];
                ((uint8_t*)yc.cr)[ci] = vs[bar];
            }
        }
    }
}

}  // namespace

static CameraMetadata buildRequestSettings();
static CameraMetadata makeResultMetadata(const CameraMetadata& req, int64_t ts);

PortalSession::PortalSession(std::shared_ptr<ICameraDeviceCallback> cb)
        : mCallback(std::move(cb)) {
    mSessionId = std::to_string(
            (long long)std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count());
}

ndk::ScopedAStatus PortalSession::close() {
    std::lock_guard<std::mutex> l(mLock);
    mClosed = true;
    mStreams.clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::configureStreams(
        const StreamConfiguration& requested, std::vector<HalStream>* out) {
    ALOGE("provider: configureStreams called (%zu streams)", requested.streams.size());
    std::lock_guard<std::mutex> l(mLock);
    mStreams.clear();
    out->clear();
    for (const auto& s : requested.streams) {
        int fmt = static_cast<int>(s.format);
        StreamInfo info;
        info.width = s.width;
        info.height = s.height;
        info.bufferSize = s.bufferSize;
        info.blob = (fmt == static_cast<int>(PixelFormat::BLOB));
        info.format = info.blob ? fmt : static_cast<int>(PixelFormat::YCBCR_420_888);
        mStreams[s.id] = info;

        HalStream h;
        h.id = s.id;
        h.maxBuffers = info.blob ? 2 : 4;
        if (info.blob) {
            h.overrideFormat = PixelFormat::BLOB;
            h.producerUsage = static_cast<BufferUsage>(
                    (uint64_t)BufferUsage::CPU_WRITE_OFTEN | (uint64_t)BufferUsage::CAMERA_OUTPUT);
            h.overrideDataSpace = Dataspace::JFIF;
        } else {
            h.overrideFormat = PixelFormat::YCBCR_420_888;
            h.producerUsage = static_cast<BufferUsage>(kPreviewUsage);
            /* YUV 沿用框架给的 dataSpace，不算 override。 */
            h.overrideDataSpace = s.dataSpace;
        }
        out->push_back(h);
        ALOGI("provider: stream id=%d %dx%d fmt=0x%x -> override=0x%x max=%d blob=%d",
              s.id, s.width, s.height, fmt, (int)h.overrideFormat, h.maxBuffers, info.blob);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::configureStreamsV2(
        const StreamConfiguration& requested, ConfigureStreamsRet* out) {
    return configureStreams(requested, &out->halStreams);
}

ndk::ScopedAStatus PortalSession::constructDefaultRequestSettings(
        RequestTemplate, CameraMetadata* out) {
    *out = buildRequestSettings();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::flush() {
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::getCaptureRequestMetadataQueue(
        aidl::android::hardware::common::fmq::MQDescriptor<
                int8_t, aidl::android::hardware::common::fmq::SynchronizedReadWrite>* out) {
    *out = {};
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::getCaptureResultMetadataQueue(
        aidl::android::hardware::common::fmq::MQDescriptor<
                int8_t, aidl::android::hardware::common::fmq::SynchronizedReadWrite>* out) {
    *out = {};
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::isReconfigurationRequired(
        const CameraMetadata&, const CameraMetadata&, bool* out) {
    *out = false;
    return ndk::ScopedAStatus::ok();
}

bool PortalSession::fillBuffer(const StreamBuffer& sb, const StreamInfo& info, int64_t timestamp,
                               void* cachedNhVoid) {
    static int fillDbg = 0;
    const bool dbg = ++fillDbg <= 20;
    if (dbg) ALOGE("provider: fill enter #%d blob=%d cached=%d", fillDbg, info.blob ? 1 : 0, cachedNhVoid != nullptr);
    native_handle_t* nh = cachedNhVoid ? (native_handle_t*)cachedNhVoid : toNative(sb.buffer);
    const bool ownsNh = cachedNhVoid == nullptr;
    if (!nh) { if (dbg) ALOGE("provider: fill no native handle #%d", fillDbg); return false; }
    Mapper& m = mapper();
    if (!m.self) { if (ownsNh) nh_delete(nh); return false; }
    native_handle_t* imported = nullptr;
    int importRc = m.import(m.self, nh, (void**)&imported);
    if (importRc != 0 || imported == nullptr) {
        // 部分 HAL 直接给的是已可锁定的 native_handle；import 失败不应丢帧。
        if (dbg) ALOGE("provider: fill import failed #%d rc=%d nfds=%d nints=%d, use raw handle",
                       fillDbg, importRc, nh->numFds, nh->numInts);
        imported = nullptr;
    }
    Rect rect{0, 0, info.width, info.height};
    const uint32_t usage = 0x33;
    native_handle_t* use = imported ? imported : nh;
    bool ok = false;
    if (info.blob) {
        void* vaddr = nullptr;
        if (m.lock(m.self, use, usage, &rect, &vaddr) == 0 && vaddr) {
            size_t cap = info.bufferSize > 0 ? (size_t)info.bufferSize : 0;
            if (cap == 0) {
                const int32_t* ints = nh->data + nh->numFds;
                if (nh->numInts >= 8) cap = (uint32_t)ints[7];
            }
            if (cap > 4096) {
                std::vector<uint8_t> rgb((size_t)info.width * info.height * 3);
                if (!frame_reader_draw_rgb(rgb.data(), info.width, info.height)) {
                    drawRgb(rgb.data(), info.width, info.height, (int)(timestamp % 100000));
                }
                uint8_t* jpg = nullptr;
                size_t jlen = 0;
                if (portal_jpeg_encode_rgb(rgb.data(), info.width, info.height, 85, &jpg, &jlen) &&
                    jpg && jlen + 8 <= cap) {
                    memcpy(vaddr, jpg, jlen);
                    struct Blob { uint16_t id; uint32_t size; } __attribute__((packed));
                    Blob* blob = (Blob*)((uint8_t*)vaddr + cap - sizeof(Blob));
                    blob->id = 0x00FF;
                    blob->size = (uint32_t)jlen;
                    ok = true;
                    ALOGI("provider: JPEG %dx%d %zu bytes cap=%zu", info.width, info.height, jlen, cap);
                }
                free(jpg);
            }
            int fence = -1;
            m.unlock(m.self, use, &fence);
        }
    } else {
        YCbCr yc{};
        if (m.lockYCbCr(m.self, use, usage, &rect, &yc) == 0 && yc.y) {
            static bool loggedYuv = false;
            if (!loggedYuv) {
                loggedYuv = true;
                ALOGE("provider: target YUV yStride=%zu cStride=%zu step=%zu manual=%d fd=%d",
                      yc.ystride, yc.cstride, yc.chroma_step,
                      gManualMode.load() ? 1 : 0, gSelection.fd);
            }
            bool drew = false;
            {
                std::lock_guard<std::mutex> l(gSelectionMutex);
                if (!gManualMode.load() && gSelection.fd >= 0) {
                    drew = video_source_play(gSelection.fd, gSelection.startMs,
                                             yc.y, yc.cb, yc.cr, yc.ystride, yc.cstride,
                                             yc.chroma_step, info.width, info.height);
                }
            }
            if (!drew) {
                // 仅显式调试模式才回退到共享帧文件；正常模式不读外部 cammock。
                if (gManualMode.load()) {
                    frame_reader_draw_yuv(yc.y, yc.cb, yc.cr, yc.ystride, yc.cstride,
                                          yc.chroma_step, info.width, info.height);
                } else {
                    drawYuv(yc, info.width, info.height, (int)(timestamp % 100000));
                }
            }
            // 相机 output buffer 级探针：确认每次 request 写出的内容/序号。
            {
                uint64_t seq = ++gOutputSeq;
                if (seq % 10 == 1) {
                    const uint8_t* yp = (const uint8_t*)yc.y;
                    uint64_t h = 1469598103934665603ULL;
                    for (int i = 0; i < 64 && i < info.width; ++i) {
                        h ^= yp[i]; h *= 1099511628211ULL;
                    }
                    ALOGE("provider: out seq=%llu ts=%lld hash=%llx",
                          (unsigned long long)seq, (long long)timestamp,
                          (unsigned long long)h);
                }
            }
            ok = true;
        } else {
            static bool logged = false;
            if (!logged || dbg) ALOGE("provider: lockYCbCr failed #%d", fillDbg);
        if (!logged) logged = true;
        }
        int fence = -1;
        m.unlock(m.self, use, &fence);
    }
    if (imported) m.freeBuf(m.self, imported);
    if (ownsNh) { nh_close(nh); nh_delete(nh); }
    return ok;
}

static bool portal_manual_frame_mode() {
    // 正常生产路径只由 system_server selection 驱动。
    // cammock 手动帧仅保留显式调试环境变量，不再读取 /data/local/tmp 下的文件。
    return getenv("PORTAL_USE_CAMMOCK") != nullptr;
}

ndk::ScopedAStatus PortalSession::processCaptureRequest(
        const std::vector<CaptureRequest>& requests,
        const std::vector<BufferCache>&, int32_t* out) {
    int32_t done = 0;
    ALOGE("provider: processCaptureRequest called requests=%zu", requests.size());
    static uint64_t lastSelectionQueryNs = 0;
    static uint64_t selectionValidUntilNs = 0;
    const bool useCammock = portal_manual_frame_mode();
    gManualMode.store(useCammock);
    uint64_t now = nowNs();
    // 确认一个视频点后进入静默期：播放期间不再向 system_server 重新选点。
    // 这样同一个视频不会因为 3 秒刷新而重开/seek；至少完整播放当前片段，
    // 到 EOS 后只做最后 3 秒循环。只有当前没有有效选片时才请求。
    bool haveSelection;
    {
        std::lock_guard<std::mutex> l(gSelectionMutex);
        haveSelection = gSelection.valid && gSelection.fd >= 0;
    }
    if (!useCammock && !haveSelection && now >= selectionValidUntilNs) {
        lastSelectionQueryNs = now;
        ServerSelection sel = selection_request(mSessionId);
        std::lock_guard<std::mutex> l(gSelectionMutex);
        if (gSelection.fd >= 0) ::close(gSelection.fd);
        gSelection = sel;
        if (sel.valid) {
            selectionValidUntilNs = now + 3000000000ULL;
            ALOGE("provider: selection locked video=%s start=%lld, hold 3s+",
                  sel.videoId, (long long)sel.startMs);
        } else {
            selectionValidUntilNs = now + 2000000000ULL;
        }
    }
    for (const auto& req : requests) {
        frame_reader_note_request();
        int64_t ts = nowNs();
        std::vector<StreamBuffer> outs;
        outs.reserve(req.outputBuffers.size());
        {
            std::lock_guard<std::mutex> l(mLock);
            if (mClosed) break;
            for (const auto& sb : req.outputBuffers) {
                auto it = mStreams.find(sb.streamId);
                if (it == mStreams.end()) continue;
                // AIDL buffer cache：同一个 bufferId 的后续请求可能把 handle 省略。
                const auto key = std::make_pair(sb.streamId, sb.bufferId);
                const bool emptyHandle = sb.buffer.fds.empty() && sb.buffer.ints.empty();
                void* cachedNh = nullptr;
                if (emptyHandle) {
                    auto cached = mBufferHandles.find(key);
                    if (cached != mBufferHandles.end()) cachedNh = cached->second;
                } else {
                    auto old = mBufferHandles.find(key);
                    if (old != mBufferHandles.end() && old->second) {
                        nh_close((native_handle_t*)old->second); nh_delete((native_handle_t*)old->second);
                    }
                    mBufferHandles[key] = toNative(sb.buffer);
                }
                fillBuffer(sb, it->second, ts, cachedNh);
                StreamBuffer out;
                out.streamId = sb.streamId;
                out.bufferId = sb.bufferId;
                /* 非 HAL buffer manager：结果里不需要回传 buffer handle；
                   回传空 handle，避免 dup(-1)/非法 fd 让相机服务崩溃。 */
                out.buffer = {};
                out.status = BufferStatus::OK;
                outs.push_back(std::move(out));
            }
        }
        if (mCallback) {
            ShutterMsg sm;
            sm.frameNumber = req.frameNumber;
            sm.timestamp = ts;
            NotifyMsg nm = NotifyMsg::make<NotifyMsg::Tag::shutter>(sm);

            CaptureResult res;
            res.frameNumber = req.frameNumber;
            res.result = makeResultMetadata(req.settings, ts);
            res.outputBuffers = std::move(outs);
            res.inputBuffer.streamId = -1;  /* 无输入流；默认 0 会让相机服务解引用空 inputStream */
            res.partialResult = 1;

            mCallback->notify({nm});
            std::vector<CaptureResult> results;
            results.push_back(std::move(res));
            mCallback->processCaptureResult(results);
        }
        done++;
    }
    *out = done;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::signalStreamFlush(const std::vector<int32_t>&, int32_t) {
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalSession::switchToOffline(
        const std::vector<int32_t>&, CameraOfflineSessionInfo*,
        std::shared_ptr<ICameraOfflineSession>*) {
    return ndk::ScopedAStatus::fromServiceSpecificError(
            static_cast<int32_t>(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus PortalSession::repeatingRequestEnd(int32_t, const std::vector<int32_t>&) {
    return ndk::ScopedAStatus::ok();
}

PortalDevice::PortalDevice(CameraMetadata chars,
                           std::shared_ptr<ICameraDevice> vendorDev)
        : mChars(std::move(chars)), mVendorDev(std::move(vendorDev)) {}

ndk::ScopedAStatus PortalDevice::getCameraCharacteristics(CameraMetadata* out) {
    ALOGE("provider: getCameraCharacteristics this=%p size=%zu", (void*)this, mChars.metadata.size());
    *out = mChars;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::getPhysicalCameraCharacteristics(
        const std::string&, CameraMetadata* out) {
    out->metadata.clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::getResourceCost(CameraResourceCost* out) {
    ALOGE("provider: getResourceCost");
    out->resourceCost = 100;
    out->conflictingDevices.clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::isStreamCombinationSupported(
        const StreamConfiguration&, bool* out) {
    *out = true;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::open(
        const std::shared_ptr<ICameraDeviceCallback>& cb,
        std::shared_ptr<ICameraDeviceSession>* out) {
    ALOGE("provider: open called");
    *out = ndk::SharedRefBase::make<PortalSession>(cb);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::openInjectionSession(
        const std::shared_ptr<ICameraDeviceCallback>&,
        std::shared_ptr<ICameraInjectionSession>*) {
    return ndk::ScopedAStatus::fromServiceSpecificError(
            static_cast<int32_t>(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus PortalDevice::setTorchMode(bool) {
    return ndk::ScopedAStatus::ok();
}
ndk::ScopedAStatus PortalDevice::turnOnTorchWithStrengthLevel(int32_t) {
    return ndk::ScopedAStatus::ok();
}
ndk::ScopedAStatus PortalDevice::getTorchStrengthLevel(int32_t* out) {
    *out = 0;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::constructDefaultRequestSettings(
        RequestTemplate type, CameraMetadata* out) {
    ALOGE("provider: constructDefaultRequestSettings type=%d vendor=%p", (int)type,
          (void*)mVendorDev.get());
    *out = buildRequestSettings();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::isStreamCombinationWithSettingsSupported(
        const StreamConfiguration&, bool* out) {
    *out = true;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalDevice::getSessionCharacteristics(
        const StreamConfiguration&, CameraMetadata* out) {
    *out = mChars;
    return ndk::ScopedAStatus::ok();
}

struct Entry {
    size_t index;
    uint32_t tag;
    uint8_t type;
    size_t count;
    union { uint8_t* u8; int32_t* i32; float* f; int64_t* i64; double* d; void* r; } data;
};

static void filterStandardMetadata(CameraMetadata* cm) {
    if (cm->metadata.empty()) return;
    void* h = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return;
    auto alloc = (void* (*)(size_t, size_t))dlsym(h, "allocate_camera_metadata");
    auto add = (int (*)(void*, uint32_t, const void*, size_t))dlsym(h, "add_camera_metadata_entry");
    auto getCount = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_entry_count");
    auto getEntry = (int (*)(void*, size_t, Entry*))dlsym(h, "get_camera_metadata_entry");
    auto getSize = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_size");
    auto getName = (const char* (*)(uint32_t))dlsym(h, "get_camera_metadata_tag_name");
    if (!alloc || !add || !getCount || !getEntry || !getSize || !getName) return;
    void* src = cm->metadata.data();
    size_t n = getCount(src);
    void* dst = alloc(n > 0 ? n : 1, 65536);
    if (!dst) return;
    size_t kept = 0;
    for (size_t i = 0; i < n; i++) {
        Entry e{};
        if (getEntry(src, i, &e) != 0) continue;
        const char* n = getName(e.tag);
        if (n && strstr(n, "depth")) continue;  // 去掉 dynamic depth 相关标签
        if (add(dst, e.tag, e.data.u8, e.count) == 0) kept++;
    }
    size_t sz = getSize(dst);
    cm->metadata.assign((uint8_t*)dst, (uint8_t*)dst + sz);
    ALOGE("provider: filtered metadata -> %zu bytes (%zu/%zu entries)", sz, kept, n);
}

struct Rational { int32_t numerator, denominator; };

static void buildStaticMetadata(CameraMetadata* cm) {
    void* h = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return;
    auto alloc = (void* (*)(size_t, size_t))dlsym(h, "allocate_camera_metadata");
    auto add = (int (*)(void*, uint32_t, const void*, size_t))dlsym(h, "add_camera_metadata_entry");
    auto getSize = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_size");
    if (!alloc || !add || !getSize) return;
    void* m = alloc(256, 65536);
    if (!m) return;
    auto A = [&](uint32_t tag, const void* data, size_t count) {
        int r = add(m, tag, data, count);
        if (r != 0) ALOGE("provider: add tag 0x%x count=%zu failed %d", tag, count, r);
    };

    uint8_t u8;
    int32_t i32[64];
    int64_t i64[64];
    float f32[4];
    Rational rat;

    u8 = 2; A(ANDROID_INFO_SUPPORTED_HARDWARE_LEVEL, &u8, 1); /* LEGACY */
    i32[0] = 90; A(ANDROID_SENSOR_ORIENTATION, i32, 1);
    u8 = 0; A(ANDROID_LENS_FACING, &u8, 1); /* FRONT */
    u8 = 0; A(ANDROID_REQUEST_AVAILABLE_CAPABILITIES, &u8, 1); /* BACKWARD_COMPATIBLE */
    u8 = 0; A(ANDROID_FLASH_INFO_AVAILABLE, &u8, 1);
    u8 = 0; A(ANDROID_SENSOR_INFO_TIMESTAMP_SOURCE, &u8, 1);
    u8 = 0; A(ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, &u8, 1);
    u8 = 0; A(ANDROID_LENS_INFO_FOCUS_DISTANCE_CALIBRATION, &u8, 1);
    u8 = 0; A(ANDROID_LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AVAILABLE_EFFECTS, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AVAILABLE_SCENE_MODES, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AF_AVAILABLE_MODES, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AVAILABLE_MODES, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AE_AVAILABLE_MODES, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AWB_AVAILABLE_MODES, &u8, 1);
    u8 = 0; A(ANDROID_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, &u8, 1);
    u8 = 0; A(ANDROID_COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, &u8, 1);
    u8 = 2; A(ANDROID_REQUEST_PIPELINE_MAX_DEPTH, &u8, 1);
    u8 = 0; A(ANDROID_SCALER_CROPPING_TYPE, &u8, 1);

    i32[0] = 1; A(ANDROID_REQUEST_PARTIAL_RESULT_COUNT, i32, 1);
    i32[0] = 0; A(ANDROID_SYNC_MAX_LATENCY, i32, 1);
    i32[0] = 0; A(ANDROID_TONEMAP_MAX_CURVE_POINTS, i32, 1);
    i32[0] = 0; A(ANDROID_STATISTICS_INFO_MAX_FACE_COUNT, i32, 1);
    i32[0] = 0; i32[1] = 0; i32[2] = 0; A(ANDROID_CONTROL_MAX_REGIONS, i32, 3);
    i32[0] = 0; i32[1] = 0; A(ANDROID_CONTROL_AE_COMPENSATION_RANGE, i32, 2);
    rat.numerator = 1; rat.denominator = 1;
    A(ANDROID_CONTROL_AE_COMPENSATION_STEP, &rat, 1);
    i32[0] = 0; i32[1] = 0; i32[2] = 2592; i32[3] = 1944;
    A(ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE, i32, 4);
    i32[0] = 2592; i32[1] = 1944;
    A(ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE, i32, 2);
    i32[0] = 100; i32[1] = 800;
    A(ANDROID_SENSOR_INFO_SENSITIVITY_RANGE, i32, 2);
    i64[0] = 1000; i64[1] = 100000000;
    A(ANDROID_SENSOR_INFO_EXPOSURE_TIME_RANGE, i64, 2);
    i32[0] = 800; A(ANDROID_SENSOR_MAX_ANALOG_SENSITIVITY, i32, 1);
    i32[0] = 255; A(ANDROID_SENSOR_INFO_WHITE_LEVEL, i32, 1);
    i64[0] = 1000000000; A(ANDROID_SENSOR_INFO_MAX_FRAME_DURATION, i64, 1);
    f32[0] = 1.0f; A(ANDROID_SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, f32, 1);
    f32[0] = 3.6f; f32[1] = 2.7f;
    A(ANDROID_SENSOR_INFO_PHYSICAL_SIZE, f32, 2);
    f32[0] = 4.0f; A(ANDROID_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, f32, 1);
    f32[0] = 2.0f; A(ANDROID_LENS_INFO_AVAILABLE_APERTURES, f32, 1);
    i32[0] = 15; i32[1] = 30; i32[2] = 30; i32[3] = 30;
    A(ANDROID_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, i32, 4);
    i32[0] = 8 * 1024 * 1024;
    A(ANDROID_JPEG_MAX_SIZE, i32, 1);
    i32[0] = 0; i32[1] = 0; i32[2] = 160; i32[3] = 120; i32[4] = 320; i32[5] = 240;
    A(ANDROID_JPEG_AVAILABLE_THUMBNAIL_SIZES, i32, 6);
    {
        int32_t keys[] = {
            ANDROID_CONTROL_MODE, ANDROID_CONTROL_AE_MODE, ANDROID_CONTROL_AF_MODE,
            ANDROID_CONTROL_AWB_MODE, ANDROID_CONTROL_CAPTURE_INTENT,
            ANDROID_CONTROL_AE_LOCK, ANDROID_CONTROL_AWB_LOCK,
            ANDROID_CONTROL_AE_ANTIBANDING_MODE, ANDROID_CONTROL_EFFECT_MODE,
            ANDROID_CONTROL_SCENE_MODE, ANDROID_CONTROL_VIDEO_STABILIZATION_MODE,
            ANDROID_CONTROL_AF_TRIGGER, ANDROID_COLOR_CORRECTION_MODE,
            ANDROID_TONEMAP_MODE, ANDROID_EDGE_MODE, ANDROID_NOISE_REDUCTION_MODE,
            ANDROID_SHADING_MODE, ANDROID_BLACK_LEVEL_LOCK,
            ANDROID_STATISTICS_FACE_DETECT_MODE, ANDROID_LED_TRANSMIT,
            ANDROID_LENS_OPTICAL_STABILIZATION_MODE,
            ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION,
            ANDROID_CONTROL_AE_TARGET_FPS_RANGE,
            ANDROID_CONTROL_AF_REGIONS, ANDROID_CONTROL_AE_REGIONS,
            ANDROID_SCALER_CROP_REGION, ANDROID_JPEG_QUALITY,
            ANDROID_JPEG_ORIENTATION,
        };
        A(ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS, keys,
          sizeof(keys) / sizeof(keys[0]));
    }
    A(ANDROID_REQUEST_AVAILABLE_RESULT_KEYS, i32, 0);
    A(ANDROID_REQUEST_AVAILABLE_CHARACTERISTICS_KEYS, i32, 0);

    static const int32_t kSizes[][2] = {
        {640,480}, {1280,720}, {1920,1080}, {2592,1944}
    };
    static const int32_t kFormats[] = {0x23, 0x21, 0x22}; /* YUV, JPEG, PRIVATE */
    int n = 0;
    for (int32_t fmt : kFormats) {
        for (auto& sz : kSizes) {
            if (fmt == 0x21 && sz[0] > 1280) continue;  // 只保留小 JPEG，避开 perf-class 过滤
            i32[n++] = fmt; i32[n++] = sz[0]; i32[n++] = sz[1]; i32[n++] = 0;
        }
    }
    ALOGE("provider: stream cfg n=%d first=%d,%d,%d,%d last=%d,%d,%d,%d", n,
          i32[0],i32[1],i32[2],i32[3], i32[n-4],i32[n-3],i32[n-2],i32[n-1]);
    A(ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, i32, n);
    n = 0;
    for (int32_t fmt : kFormats) {
        for (auto& sz : kSizes) {
            if (fmt == 0x21 && sz[0] > 1280) continue;
            i64[n++] = fmt; i64[n++] = sz[0]; i64[n++] = sz[1];
            i64[n++] = (fmt == 0x21) ? 200000000 : 33333333;
        }
    }
    A(ANDROID_SCALER_AVAILABLE_STALL_DURATIONS, i64, n);
    n = 0;
    for (int32_t fmt : kFormats) {
        for (auto& sz : kSizes) {
            if (fmt == 0x21 && sz[0] > 1280) continue;
            i64[n++] = fmt; i64[n++] = sz[0]; i64[n++] = sz[1];
            i64[n++] = 33333333;  // 30 fps
        }
    }
    A(ANDROID_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, i64, n);
    {
        int32_t jcfg[] = {0x21, 640, 480, 0, 0x21, 1280, 720, 0};
        A(ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, jcfg, 8);
        int64_t jmin[] = {0x21, 640, 480, 33333333, 0x21, 1280, 720, 33333333};
        A(ANDROID_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, jmin, 8);
    }

    size_t bytes = getSize(m);
    cm->metadata.assign((uint8_t*)m, (uint8_t*)m + bytes);
    ALOGE("provider: built static metadata %zu bytes", bytes);
}

static CameraMetadata buildRequestSettings() {
    CameraMetadata cm;
    void* h = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return cm;
    auto alloc = (void* (*)(size_t, size_t))dlsym(h, "allocate_camera_metadata");
    auto add = (int (*)(void*, uint32_t, const void*, size_t))dlsym(h, "add_camera_metadata_entry");
    auto getSize = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_size");
    if (!alloc || !add || !getSize) return cm;
    void* m = alloc(64, 2048);
    if (!m) return cm;
    auto A = [&](uint32_t tag, const void* data, size_t count) { add(m, tag, data, count); };
    uint8_t u8;
    int32_t i32[8];
    u8 = 1; A(ANDROID_CONTROL_MODE, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AE_MODE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AF_MODE, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AWB_MODE, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_CAPTURE_INTENT, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AE_LOCK_AVAILABLE, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AWB_LOCK_AVAILABLE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AE_LOCK, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AWB_LOCK, &u8, 1);
    u8 = 1; A(ANDROID_CONTROL_AE_ANTIBANDING_MODE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_EFFECT_MODE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_SCENE_MODE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_VIDEO_STABILIZATION_MODE, &u8, 1);
    u8 = 0; A(ANDROID_CONTROL_AF_TRIGGER, &u8, 1);
    u8 = 0; A(ANDROID_COLOR_CORRECTION_MODE, &u8, 1);
    u8 = 0; A(ANDROID_TONEMAP_MODE, &u8, 1);
    u8 = 0; A(ANDROID_EDGE_MODE, &u8, 1);
    u8 = 0; A(ANDROID_NOISE_REDUCTION_MODE, &u8, 1);
    u8 = 0; A(ANDROID_SHADING_MODE, &u8, 1);
    u8 = 0; A(ANDROID_BLACK_LEVEL_LOCK, &u8, 1);
    u8 = 0; A(ANDROID_STATISTICS_FACE_DETECT_MODE, &u8, 1);
    u8 = 1; A(ANDROID_LED_TRANSMIT, &u8, 1); /* LED ON，避免 CAMERA_DISABLE_TRANSMIT_LED 权限检查 */
    u8 = 0; A(ANDROID_LENS_OPTICAL_STABILIZATION_MODE, &u8, 1);
    i32[0] = 0; A(ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION, i32, 1);
    i32[0] = 15; i32[1] = 30;
    A(ANDROID_CONTROL_AE_TARGET_FPS_RANGE, i32, 2);
    i32[0] = 0; i32[1] = 0; i32[2] = 0; i32[3] = 0;
    A(ANDROID_CONTROL_AF_REGIONS, i32, 4);
    A(ANDROID_CONTROL_AE_REGIONS, i32, 4);
    size_t bytes = getSize(m);
    cm.metadata.assign((uint8_t*)m, (uint8_t*)m + bytes);
    ALOGE("provider: built request settings %zu bytes", bytes);
    return cm;
}

static CameraMetadata makeResultMetadata(const CameraMetadata& req, int64_t ts) {
    CameraMetadata out;
    const CameraMetadata* src = &req;
    CameraMetadata fallback;
    if (req.metadata.empty()) {
        fallback = buildRequestSettings();
        src = &fallback;
    }
    void* h = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return out;
    auto alloc = (void* (*)(size_t, size_t))dlsym(h, "allocate_camera_metadata");
    auto add = (int (*)(void*, uint32_t, const void*, size_t))dlsym(h, "add_camera_metadata_entry");
    auto count = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_entry_count");
    auto getEntry = (int (*)(void*, size_t, Entry*))dlsym(h, "get_camera_metadata_entry");
    auto getSize = (size_t (*)(const void*))dlsym(h, "get_camera_metadata_size");
    if (!alloc || !add || !count || !getEntry || !getSize) return out;

    size_t n = src->metadata.empty() ? 0 : count(src->metadata.data());
    void* m = alloc(n + 4, 16384);
    if (!m) return out;
    for (size_t i = 0; i < n; i++) {
        Entry e{};
        if (getEntry((void*)src->metadata.data(), i, &e) != 0) continue;
        if (e.tag == ANDROID_SENSOR_TIMESTAMP) continue;
        add(m, e.tag, e.data.u8, e.count);
    }
    add(m, ANDROID_SENSOR_TIMESTAMP, &ts, 1);
    size_t sz = getSize(m);
    out.metadata.assign((uint8_t*)m, (uint8_t*)m + sz);
    ALOGE("provider: result metadata %zu bytes", sz);
    return out;
}

static void dumpTags(const char* path, const std::vector<VendorTagSection>& tags) {
    FILE* f = fopen(path, "wb");
    if (!f) return;
    uint32_t n = tags.size();
    fwrite(&n, sizeof(n), 1, f);
    for (const auto& sec : tags) {
        uint32_t len = sec.sectionName.size();
        fwrite(&len, sizeof(len), 1, f);
        fwrite(sec.sectionName.data(), 1, len, f);
        uint32_t tn = sec.tags.size();
        fwrite(&tn, sizeof(tn), 1, f);
        for (const auto& t : sec.tags) {
            fwrite(&t.tagId, sizeof(t.tagId), 1, f);
            uint32_t nl = t.tagName.size();
            fwrite(&nl, sizeof(nl), 1, f);
            fwrite(t.tagName.data(), 1, nl, f);
            int32_t ty = (int32_t)t.tagType;
            fwrite(&ty, sizeof(ty), 1, f);
        }
    }
    fclose(f);
    ALOGE("provider: dumped %u vendor tag sections", n);
}

static void loadTags(const char* path, std::vector<VendorTagSection>* tags) {
    FILE* f = fopen(path, "rb");
    if (!f) return;
    uint32_t n = 0;
    if (fread(&n, sizeof(n), 1, f) != 1) { fclose(f); return; }
    tags->clear();
    for (uint32_t i = 0; i < n && i < 64; i++) {
        VendorTagSection sec;
        uint32_t len = 0;
        if (fread(&len, sizeof(len), 1, f) != 1 || len > 4096) break;
        sec.sectionName.resize(len);
        if (fread(sec.sectionName.data(), 1, len, f) != len) break;
        uint32_t tn = 0;
        if (fread(&tn, sizeof(tn), 1, f) != 1 || tn > 4096) break;
        for (uint32_t j = 0; j < tn; j++) {
            VendorTag t;
            if (fread(&t.tagId, sizeof(t.tagId), 1, f) != 1) break;
            uint32_t nl = 0;
            if (fread(&nl, sizeof(nl), 1, f) != 1 || nl > 4096) break;
            t.tagName.resize(nl);
            if (fread(t.tagName.data(), 1, nl, f) != nl) break;
            int32_t ty = 0;
            if (fread(&ty, sizeof(ty), 1, f) != 1) break;
            t.tagType = (CameraMetadataType)ty;
            sec.tags.push_back(std::move(t));
        }
        tags->push_back(std::move(sec));
    }
    fclose(f);
    ALOGE("provider: loaded %u vendor tag sections", n);
}

PortalProvider::PortalProvider() {
    loadFaceImage();
    const char* metaPath = getenv("PORTAL_META");
    if (metaPath && *metaPath) {
        FILE* f = fopen(metaPath, "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long n = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (n > 0) {
                mChars.metadata.resize((size_t)n);
                fread(mChars.metadata.data(), 1, (size_t)n, f);
            }
            fclose(f);
            ALOGI("provider: loaded cached metadata %ld bytes", n);
            buildStaticMetadata(&mChars);
        }
        const char* tagsPath = getenv("PORTAL_TAGS");
        if (tagsPath && *tagsPath) loadTags(tagsPath, &mTags);
        return;
    }

    AIBinder* b = portalWaitForService(
            "android.hardware.camera.provider.ICameraProvider/vendor_qti/0");
    if (!b) {
        ALOGE("provider: vendor_qti/0 not found");
        return;
    }
    auto p = ICameraProvider::fromBinder(ndk::SpAIBinder(b));
    if (!p) return;
    std::vector<std::string> ids;
    auto stIds = p->getCameraIdList(&ids);
    ALOGE("provider: vendor ids status=%d count=%zu", (int)stIds.getExceptionCode(), ids.size());
    size_t bestSize = SIZE_MAX;
    for (const auto& id : ids) {
        std::shared_ptr<ICameraDevice> dev;
        auto stDev = p->getCameraDeviceInterface(id, &dev);
        if (!stDev.isOk() || !dev) {
            ALOGE("provider: vendor dev %s failed", id.c_str());
            continue;
        }
        CameraMetadata chars;
        auto stCh = dev->getCameraCharacteristics(&chars);
        ALOGE("provider: vendor %s chars status=%d size=%zu", id.c_str(),
              (int)stCh.getExceptionCode(), chars.metadata.size());
        if (stCh.isOk() && !chars.metadata.empty() && chars.metadata.size() < bestSize) {
            bestSize = chars.metadata.size();
            mChars = std::move(chars);
            mVendorDev = dev;
        }
    }
    buildStaticMetadata(&mChars);
    ALOGE("provider: selected metadata size=%zu", mChars.metadata.size());
    auto stTags = p->getVendorTags(&mTags);
    ALOGE("provider: vendor tags status=%d sections=%zu", (int)stTags.getExceptionCode(), mTags.size());
    const char* dumpTagsPath = getenv("PORTAL_DUMP_TAGS");
    if (dumpTagsPath && *dumpTagsPath) dumpTags(dumpTagsPath, mTags);
    const char* dumpPath = getenv("PORTAL_DUMP_META");
    if (dumpPath && *dumpPath && !mChars.metadata.empty()) {
        FILE* f = fopen(dumpPath, "wb");
        if (f) {
            fwrite(mChars.metadata.data(), 1, mChars.metadata.size(), f);
            fclose(f);
            ALOGI("provider: dumped metadata %zu bytes to %s", mChars.metadata.size(), dumpPath);
        }
    }
}

ndk::ScopedAStatus PortalProvider::setCallback(
        const std::shared_ptr<ICameraProviderCallback>& cb) {
    mCallback = cb;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::getVendorTags(std::vector<VendorTagSection>* out) {
    *out = mTags;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::getCameraIdList(std::vector<std::string>* out) {
    *out = {kFakeCameraId};
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::getCameraDeviceInterface(
        const std::string& name, std::shared_ptr<ICameraDevice>* out) {
    if (name != kFakeCameraId) {
        return ndk::ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EX_ILLEGAL_ARGUMENT));
    }
    auto dev = ndk::SharedRefBase::make<PortalDevice>(mChars, mVendorDev);
    mLastDevice = dev;
    *out = dev;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::notifyDeviceStateChange(int64_t) {
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::getConcurrentCameraIds(
        std::vector<ConcurrentCameraIdCombination>* out) {
    out->clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PortalProvider::isConcurrentStreamCombinationSupported(
        const std::vector<CameraIdAndStreamCombination>&, bool* out) {
    *out = false;
    return ndk::ScopedAStatus::ok();
}

}  // namespace portal
