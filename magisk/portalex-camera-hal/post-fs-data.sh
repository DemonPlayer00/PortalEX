#!/system/bin/sh
# 让 hal_camera_default 能执行我们挂进 /vendor 的原件副本（camera.msm8998.orig.so）。
#
# 两个坑都踩过，所以这里两条路都留着：
#  1) `sepolicy.rule` 在模块经过 disable → enable 切换后**有时不再被应用**（静默失效），
#     所以这里在 post-fs-data 阶段再显式打一次；
#  2) magiskpolicy 的语句语法是 `allow <source> <target> <class> { perms }`，
#     写成 `target:class p1,p2` 会报 Syntax error 而**静默不生效**。
MP=/data/adb/magisk/magiskpolicy
[ -x "$MP" ] || MP=magiskpolicy
"$MP" --live "allow hal_camera_default system_file file { read open getattr map execute }"
"$MP" --live "allow hal_camera_default magisk_file file { read open getattr map execute }"
