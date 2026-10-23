#include "frame_reader.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PortalVcam", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PortalVcam", __VA_ARGS__)

namespace portal {
namespace {

constexpr const char* kPath = "/data/local/tmp/portalex/cammock.frames";
constexpr uint32_t kMagic = 0x46584550;  // PEXF
constexpr int kHdr = 4096;
constexpr int OFF_MAGIC = 0, OFF_VERSION = 4, OFF_W = 8, OFF_H = 12;
constexpr int OFF_YS = 16, OFF_UVC = 20, OFF_SLOT_BYTES = 28, OFF_SLOTS = 32, OFF_ENABLED = 36;
constexpr int OFF_SEQ = 40, OFF_WRITE = 44, OFF_REQ_SEQ = 108;

struct Map {
    uint8_t* base = nullptr;
    size_t size = 0;
    int fd = -1;
    int width = 0, height = 0, yStride = 0, uvStride = 0, slots = 0;
    size_t slotBytes = 0;
    bool open() {
        if (base) return true;
        fd = ::open(kPath, O_RDWR);
        if (fd < 0) return false;
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size < kHdr + 16) { close(); return false; }
        size = (size_t)st.st_size;
        base = (uint8_t*)mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (base == MAP_FAILED) { base = nullptr; close(); return false; }
        uint32_t magic = *(uint32_t*)(base + OFF_MAGIC);
        if (magic != kMagic) { close(); return false; }
        width = *(int32_t*)(base + OFF_W);
        height = *(int32_t*)(base + OFF_H);
        yStride = *(int32_t*)(base + OFF_YS);
        uvStride = *(int32_t*)(base + OFF_UVC);
        slots = *(int32_t*)(base + OFF_SLOTS);
        slotBytes = *(int32_t*)(base + OFF_SLOT_BYTES);
        if (width <= 0 || height <= 0 || slots <= 0 || slotBytes == 0 ||
                yStride < width || uvStride < width) {
            close(); return false;
        }
        return true;
    }
    void close() {
        if (base) { munmap(base, size); base = nullptr; }
        if (fd >= 0) { ::close(fd); fd = -1; }
    }
    ~Map() { close(); }
};

Map& map() {
    static Map m;
    return m;
}

inline uint8_t yAt(const uint8_t* y, int sx, int sy) { return y[sy * 0 + sx]; }

void refreshGeometry(Map& m) {
    if (!m.base) return;
    int w = *(int32_t*)(m.base + OFF_W);
    int h = *(int32_t*)(m.base + OFF_H);
    int ys = *(int32_t*)(m.base + OFF_YS);
    int uvs = *(int32_t*)(m.base + OFF_UVC);
    int sb = *(int32_t*)(m.base + OFF_SLOT_BYTES);
    int slots = *(int32_t*)(m.base + OFF_SLOTS);
    if (w > 0 && h > 0 && ys >= w && uvs >= w && sb > 0 && slots > 0) {
        m.width = w;
        m.height = h;
        m.yStride = ys;
        m.uvStride = uvs;
        m.slotBytes = (size_t)sb;
        m.slots = slots;
    }
}

// Source is NV21: Y plane at slot, interleaved VU plane after yStride*srcH.
void copyYuvCover(const uint8_t* slot, int sw, int sh, int ys, int uvs,
                  void* outY, void* outCb, void* outCr, size_t oys, size_t ocs,
                  size_t step, int dw, int dh) {
    const uint8_t* srcY = slot;
    const uint8_t* srcUV = slot + (size_t)ys * sh;
    const int cw = dw / 2, ch = dh / 2;
    for (int y = 0; y < dh; ++y) {
        int sy = (int)((int64_t)y * sh / dh);
        uint8_t* row = (uint8_t*)outY + (size_t)y * oys;
        const uint8_t* srow = srcY + (size_t)sy * ys;
        for (int x = 0; x < dw; ++x) {
            int sx = (int)((int64_t)x * sw / dw);
            row[x] = srow[sx];
        }
    }
    for (int y = 0; y < ch; ++y) {
        int sy = (int)((int64_t)y * (sh / 2) / std::max(1, ch));
        size_t dst = (size_t)y * ocs;
        const uint8_t* srow = srcUV + (size_t)sy * uvs;
        for (int x = 0; x < cw; ++x) {
            int sx = (int)((int64_t)x * (sw / 2) / std::max(1, cw));
            ((uint8_t*)outCb)[dst + (size_t)x * step] = srow[sx * 2];
            ((uint8_t*)outCr)[dst + (size_t)x * step] = srow[sx * 2 + 1];
        }
    }
}

inline void yuvToRgb(int y, int u, int v, uint8_t* out) {
    int c = y - 16, d = u - 128, e = v - 128;
    int r = (298 * c + 409 * e + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;
    out[0] = (uint8_t)std::clamp(r, 0, 255);
    out[1] = (uint8_t)std::clamp(g, 0, 255);
    out[2] = (uint8_t)std::clamp(b, 0, 255);
}

}  // namespace

bool frame_reader_draw_yuv(void* y, void* cb, void* cr, size_t yStride,
                           size_t cStride, size_t chromaStep, int width, int height) {
    Map& m = map();
    if (!m.open()) return false;
    refreshGeometry(m);
    if (*(int32_t*)(m.base + OFF_ENABLED) == 0) return false;
    for (int attempt = 0; attempt < 3; ++attempt) {
        int slots = m.slots;
        int idx = *(int32_t*)(m.base + OFF_WRITE);
        if (idx < 0 || idx >= slots) return false;
        const uint8_t* slot = m.base + kHdr + (size_t)idx * m.slotBytes;
        uint32_t s1 = *(uint32_t*)(m.base + OFF_SEQ);
        copyYuvCover(slot, m.width, m.height, m.yStride, m.uvStride,
                     y, cb, cr, yStride, cStride, chromaStep, width, height);
        uint32_t s2 = *(uint32_t*)(m.base + OFF_SEQ);
        if (s1 == s2) return true;
    }
    return false;
}

bool frame_reader_draw_rgb(uint8_t* rgb, int width, int height) {
    Map& m = map();
    if (!m.open()) return false;
    refreshGeometry(m);
    if (*(int32_t*)(m.base + OFF_ENABLED) == 0) return false;
    int idx = *(int32_t*)(m.base + OFF_WRITE);
    if (idx < 0 || idx >= m.slots) return false;
    const uint8_t* slot = m.base + kHdr + (size_t)idx * m.slotBytes;
    const uint8_t* srcY = slot;
    const uint8_t* srcUV = slot + (size_t)m.yStride * m.height;
    for (int y = 0; y < height; ++y) {
        int sy = (int)((int64_t)y * m.height / height);
        for (int x = 0; x < width; ++x) {
            int sx = (int)((int64_t)x * m.width / width);
            uint8_t yy = srcY[(size_t)sy * m.yStride + sx];
            int cx = sx / 2, cy = sy / 2;
            const uint8_t* uv = srcUV + (size_t)cy * m.uvStride + (size_t)cx * 2;
            yuvToRgb(yy, uv[0], uv[1], rgb + ((size_t)y * width + x) * 3);
        }
    }
    return true;
}

void frame_reader_note_request() {
    Map& m = map();
    if (!m.open()) return;
    __atomic_add_fetch((int32_t*)(m.base + OFF_REQ_SEQ), 1, __ATOMIC_RELEASE);
}

}  // namespace portal
