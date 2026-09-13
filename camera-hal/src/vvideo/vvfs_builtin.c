/*
 * 内置后端登记。
 *
 * 核（vvideo_core.c）**不认识任何具体后端** —— 它只有注册表与操作表。
 * "这个构建里到底带了哪些后端"是这一件事，收敛在这一个文件里：
 * 加后端 = 加一行；想裁体积 = 删一行。
 */
#include "vvideo_internal.h"

#include <errno.h>
#include <string.h>

void vv_builtin_register(void) {
    struct vvideo_fs_type *all[] = {
        &vv_type_mem,  &vv_type_raw,  &vv_type_gen, &vv_type_shm,
        &vv_type_xform, &vv_type_ctl, &vv_type_notify,
    };
    for (size_t i = 0; i < sizeof(all) / sizeof(all[0]); i++) {
        int rc = vvideo_register_filesystem(all[i]);
        if (rc != 0 && rc != -EEXIST) {
            vvideo_logf(VV_LOG_ERR, "注册后端 %s 失败：%d", all[i]->name, rc);
        }
    }
}
