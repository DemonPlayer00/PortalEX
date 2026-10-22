/*
 * libjpeg 编码器（dlopen，避免给 32 位 HAL 增加链接依赖）。
 * 只做一件事：RGB → 内存 JPEG。调用方负责写 BLOB + camera3_jpeg_blob。
 */
#include <stddef.h>
#include <stdio.h>
#include "jpeg/jpeglib.h"
#include <android/log.h>
#include <dlfcn.h>
#include <setjmp.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "PortalVCamJpeg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct portal_jerr {
    struct jpeg_error_mgr pub;
    jmp_buf jb;
};

static void *g_lib;
static bool g_tried, g_ok;
static struct jpeg_error_mgr *(*p_std_error)(struct jpeg_error_mgr *);
static void (*p_create)(j_compress_ptr, int, size_t);
static void (*p_destroy)(j_compress_ptr);
static void (*p_set_defaults)(j_compress_ptr);
static void (*p_set_quality)(j_compress_ptr, int, boolean);
static void (*p_start)(j_compress_ptr, boolean);
static JDIMENSION (*p_write)(j_compress_ptr, JSAMPARRAY, JDIMENSION);
static void (*p_finish)(j_compress_ptr);
static void (*p_mem_dest)(j_compress_ptr, unsigned char **, unsigned long *);

static void err_exit(j_common_ptr cinfo) {
    struct portal_jerr *e = (struct portal_jerr *)cinfo->err;
    char msg[JMSG_LENGTH_MAX];
    if (cinfo->err && cinfo->err->format_message) {
        cinfo->err->format_message(cinfo, msg);
        LOGE("libjpeg error_exit: %s", msg);
    }
    longjmp(e->jb, 1);
}

static void err_output(j_common_ptr cinfo) {
    char msg[JMSG_LENGTH_MAX];
    if (cinfo->err && cinfo->err->format_message) {
        cinfo->err->format_message(cinfo, msg);
        LOGE("libjpeg: %s", msg);
    }
}

static bool load_libjpeg(void) {
    if (g_tried) return g_ok;
    g_tried = true;
    g_lib = dlopen("libjpeg.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) {
        LOGE("dlopen libjpeg.so 失败：%s", dlerror());
        return false;
    }
    p_std_error = (struct jpeg_error_mgr *(*)(struct jpeg_error_mgr *))dlsym(g_lib, "jpeg_std_error");
    p_create = (void (*)(j_compress_ptr, int, size_t))dlsym(g_lib, "jpeg_CreateCompress");
    p_destroy = (void (*)(j_compress_ptr))dlsym(g_lib, "jpeg_destroy_compress");
    p_set_defaults = (void (*)(j_compress_ptr))dlsym(g_lib, "jpeg_set_defaults");
    p_set_quality = (void (*)(j_compress_ptr, int, boolean))dlsym(g_lib, "jpeg_set_quality");
    p_start = (void (*)(j_compress_ptr, boolean))dlsym(g_lib, "jpeg_start_compress");
    p_write = (JDIMENSION (*)(j_compress_ptr, JSAMPARRAY, JDIMENSION))
            dlsym(g_lib, "jpeg_write_scanlines");
    p_finish = (void (*)(j_compress_ptr))dlsym(g_lib, "jpeg_finish_compress");
    p_mem_dest = (void (*)(j_compress_ptr, unsigned char **, unsigned long *))
            dlsym(g_lib, "jpeg_mem_dest");
    if (!p_std_error || !p_create || !p_destroy || !p_set_defaults ||
        !p_set_quality || !p_start || !p_write || !p_finish || !p_mem_dest) {
        LOGE("libjpeg 符号不全");
        return false;
    }
    g_ok = true;
    return true;
}

bool portal_jpeg_encode_rgb(const uint8_t *rgb, int w, int h, int quality,
                            uint8_t **out, size_t *out_len) {
    if (!rgb || w <= 0 || h <= 0 || !out || !out_len) return false;
    if (!load_libjpeg()) return false;
    struct jpeg_compress_struct cinfo;
    struct portal_jerr jerr;
    unsigned char *mem = NULL;
    unsigned long mem_len = 0;
    *out = NULL;
    *out_len = 0;

    memset(&cinfo, 0, sizeof(cinfo));
    memset(&jerr, 0, sizeof(jerr));
    cinfo.err = p_std_error(&jerr.pub);
    jerr.pub.error_exit = err_exit;
    jerr.pub.output_message = err_output;

    if (setjmp(jerr.jb)) {
        p_destroy(&cinfo);
        free(mem);
        LOGE("JPEG 编码失败");
        return false;
    }

    p_create(&cinfo, JPEG_LIB_VERSION, sizeof(cinfo));
    p_mem_dest(&cinfo, &mem, &mem_len);
    cinfo.image_width = (JDIMENSION)w;
    cinfo.image_height = (JDIMENSION)h;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    p_set_defaults(&cinfo);
    p_set_quality(&cinfo, quality, TRUE);
    p_start(&cinfo, TRUE);

    for (int y = 0; y < h; y++) {
        JSAMPROW row = (JSAMPROW)(rgb + (size_t)y * (size_t)w * 3);
        if (p_write(&cinfo, &row, 1) != 1) {
            LOGE("jpeg: write_scanlines failed at y=%d", y);
            p_destroy(&cinfo);
            free(mem);
            return false;
        }
    }
    p_finish(&cinfo);
    p_destroy(&cinfo);

    *out = (uint8_t *)mem;
    *out_len = (size_t)mem_len;
    return true;
}
