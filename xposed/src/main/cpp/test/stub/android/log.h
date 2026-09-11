/* host 测试用桩：只提供 virtual_world.c 用到的日志入口（不打印，保持输出干净） */
#ifndef PORTAL_TEST_ANDROID_LOG_H
#define PORTAL_TEST_ANDROID_LOG_H
#include <stdarg.h>

/* 与 NDK 同名的优先级常量（virtual_world.c 的 LOGI/LOGW 宏会用到） */
#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define ANDROID_LOG_SILENT 8
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void) prio; (void) tag; (void) fmt;
    return 0;
}
#endif
