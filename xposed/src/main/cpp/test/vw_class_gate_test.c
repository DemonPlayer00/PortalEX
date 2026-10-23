/*
 * **拆分的验收判据**（host 可测）：关掉一侧 ⇒ 那一侧的传感器 type 一律"不归我们管"。
 *
 * 为什么这条判据落在 vw_owns_type()：portal_sensor.c 的分叉是
 * 「归我们管 ⇒ 发我们生成的事件；不归我们管 ⇒ **真实事件原样放行**」。
 * 所以 vw_owns_type()==0 在语义上就等于"这一侧一个事件都不会被注入"。
 */
#include <stdio.h>
#include "virtual_world.h"

static int failures = 0;
static void expect(int got, int want, const char *what) {
    if (got != want) { printf("  ✗ %s：期望 %d 得到 %d\n", what, want, got); failures++; }
}

/* 两侧各自的代表 type（与 Kotlin 侧枚举同值） */
#define T_STEP_COUNTER   19
#define T_STEP_DETECTOR  18
#define T_ACCEL           1
#define T_GYRO            4
#define T_MAG             2
#define T_ORIENTATION    11   /* TYPE_ORIENTATION（已废弃但仍在接管清单里） */
#define T_ROTATION_VEC   15
#define T_LIGHT           5   /* 不归我们管：永远 0 */

int main(void) {
    printf("== 默认（两侧都开）：与拆分前逐位一致\n");
    vw_set_class_enable(1, 1);
    expect(vw_owns_type(T_STEP_COUNTER), 1, "默认·步数计数器");
    expect(vw_owns_type(T_STEP_DETECTOR), 1, "默认·步数检测器");
    expect(vw_owns_type(T_ACCEL), 1, "默认·加速度");
    expect(vw_owns_type(T_GYRO), 1, "默认·陀螺");
    expect(vw_owns_type(T_MAG), 1, "默认·磁场");
    expect(vw_owns_type(T_ORIENTATION), 1, "默认·朝向");
    expect(vw_owns_type(T_ROTATION_VEC), 1, "默认·旋转矢量");
    expect(vw_owns_type(T_LIGHT), 0, "默认·光照（不归我们管）");

    printf("== 只开步频 ⇒ 角度指南针侧一个都不许注入\n");
    vw_set_class_enable(1, 0);
    expect(vw_owns_type(T_STEP_COUNTER), 1, "只开步频·步数计数器");
    expect(vw_owns_type(T_STEP_DETECTOR), 1, "只开步频·步数检测器");
    expect(vw_owns_type(T_ACCEL), 0, "只开步频·加速度必须为 0");
    expect(vw_owns_type(T_GYRO), 0, "只开步频·陀螺必须为 0");
    expect(vw_owns_type(T_MAG), 0, "只开步频·磁场必须为 0");
    expect(vw_owns_type(T_ORIENTATION), 0, "只开步频·朝向必须为 0");
    expect(vw_owns_type(T_ROTATION_VEC), 0, "只开步频·旋转矢量必须为 0");

    printf("== 只开角度指南针 ⇒ 步频侧一个都不许注入\n");
    vw_set_class_enable(0, 1);
    expect(vw_owns_type(T_STEP_COUNTER), 0, "只开角度·步数计数器必须为 0");
    expect(vw_owns_type(T_STEP_DETECTOR), 0, "只开角度·步数检测器必须为 0");
    expect(vw_owns_type(T_ACCEL), 1, "只开角度·加速度");
    expect(vw_owns_type(T_GYRO), 1, "只开角度·陀螺");
    expect(vw_owns_type(T_MAG), 1, "只开角度·磁场");

    printf("== 两侧全关 ⇒ 全 0（链会卸载，但只要还被问到就必须为 0）\n");
    vw_set_class_enable(0, 0);
    expect(vw_owns_type(T_STEP_COUNTER), 0, "全关·步数计数器");
    expect(vw_owns_type(T_ACCEL), 0, "全关·加速度");
    expect(vw_owns_type(T_LIGHT), 0, "全关·光照");

    /* 还原默认，免得影响同进程后续用例 */
    vw_set_class_enable(1, 1);
    if (failures == 0) { printf("vw_class_gate: 全部通过 ✓\n"); return 0; }
    printf("vw_class_gate: %d 项失败 ✗\n", failures);
    return 1;
}
