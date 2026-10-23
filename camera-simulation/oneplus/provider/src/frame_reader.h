#pragma once
#include <cstdint>
#include <cstddef>

namespace portal {
bool frame_reader_draw_yuv(void* y, void* cb, void* cr, size_t yStride,
                           size_t cStride, size_t chromaStep, int width, int height);
bool frame_reader_draw_rgb(uint8_t* rgb, int width, int height);
void frame_reader_note_request();
}
