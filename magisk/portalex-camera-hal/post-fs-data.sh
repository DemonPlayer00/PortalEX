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

# ---------------------------------------------------------------------------
# 摄像头模拟的**共享帧通道**（app 解码 → HAL 取帧）
#
# 为什么需要这么一套（每条都是实测结论，见 docs/camera-mock.md §12）：
#  1) app 私有目录（/data/data/**）带 MLS 类别（s0:c512,c768），而 vendor HAL 跑在 s0（无类别）
#     ⇒ **app 私有目录对 HAL 结构性不可达**，加多少条 allow 都没用；
#  2) 所以共享对象必须放在双方都能到达的路径（/data/local/tmp），并给它一个
#     **带 mlstrustedobject 的自定义类型**（靠它绕过 MLS 约束）；
#  3) 新类型还必须与文件系统关联（`allow <t> labeledfs filesystem associate`），
#     否则 chcon 直接 Permission denied（AVC: denied { associate } ... tclass=filesystem）。
# ---------------------------------------------------------------------------
SHARED_DIR=/data/local/tmp/portalex
FRAME_FILE=$SHARED_DIR/cammock.frames
# 16 MiB 是**协议要求的最小值**（2026-09-12 协议 v2 起）：
#   头部 4096 + 3 个亮度槽位 × 4 MiB + 照片区
# 照片区 = 文件长 − (4096 + 3×4MiB) ≈ 4 MiB —— 拍照的 JPEG 由 app 编码后放这里，HAL 只负责搬运。
# 槽位大小**必须正好 4 MiB**（app 侧 CameraFrameSink.SLOT_BYTES 固定值），不能再"从文件长度反推"，
# 否则槽位会把整份文件吃干、照片区长度为 0。
FRAME_BYTES=16777216

mkdir -p "$SHARED_DIR"
chmod 0777 "$SHARED_DIR"

# 规则逐条打（也写进 sepolicy.rule，两条路都留着防静默失效）
"$MP" --live "type portalex_frame_file { mlstrustedobject }"
"$MP" --live "allow portalex_frame_file labeledfs filesystem associate"
"$MP" --live "allow magisk portalex_frame_file dir { relabelfrom relabelto search write add_name read open getattr }"
"$MP" --live "allow magisk portalex_frame_file file { relabelfrom relabelto read write open getattr create unlink map }"
"$MP" --live "allow magisk shell_data_file dir { relabelfrom }"
"$MP" --live "allow magisk shell_data_file file { relabelfrom }"
"$MP" --live "allow untrusted_app portalex_frame_file dir { search read open getattr write add_name remove_name }"
"$MP" --live "allow untrusted_app portalex_frame_file file { read write open getattr map create unlink }"
"$MP" --live "allow hal_camera_default portalex_frame_file dir { search read open getattr }"
"$MP" --live "allow hal_camera_default portalex_frame_file file { read write open getattr map }"
"$MP" --live "allow untrusted_app shell_data_file dir { search getattr }"

# 预建帧文件（**app 不建文件**：新建文件的标签由创建者决定，会落在 app 域的类型上；
# 由模块建好并打标签，app 只负责打开写、HAL 只负责读）
if [ ! -f "$FRAME_FILE" ]; then
    truncate -s "$FRAME_BYTES" "$FRAME_FILE"
fi
chmod 0666 "$FRAME_FILE"
chcon u:object_r:portalex_frame_file:s0 "$SHARED_DIR" 2>/dev/null
chcon u:object_r:portalex_frame_file:s0 "$FRAME_FILE" 2>/dev/null
# 文件属主给 PortalEX（装了就给，没装就留给 0666 —— 安装后再重启一次即可）
APP_UID=$(stat -c %u /data/data/moe.fuqiuluo.portalex 2>/dev/null)
[ -n "$APP_UID" ] && chown "$APP_UID:$APP_UID" "$FRAME_FILE"
