#!/system/bin/sh
# 让 hal_camera_default 能执行我们挂进 /vendor 的原件副本（camera.msm8998.orig.so）。
#
# 两个坑都踩过，所以这里两条路都留着：
#  1) `sepolicy.rule` 在模块经过 disable → enable 切换后**有时不再被应用**（静默失效），
#     所以这里在 post-fs-data 阶段再显式打一次；
#  2) magiskpolicy 的语句语法是 `allow <source> <target> <class> { perms }`，
#     写成 `target:class p1,p2` 会报 Syntax error 而**静默不生效**。
# 3) **root 方案有两家**（本模块两边都要能跑）：
#    · Magisk：`magiskpolicy --live "<语句>"`
#    · KernelSU Next（一加 ACE5 实测就是它）：`ksud sepolicy patch "<语句>"`
#    语法都是 `allow <source> <target> <class> { perms }`，所以同一批语句两边通用，
#    只有"往哪个命令里塞"不同 —— 这里自动挑。
if [ -x /data/adb/magisk/magiskpolicy ]; then
    MP=/data/adb/magisk/magiskpolicy
    policy() { "$MP" --live "$1"; }
elif command -v magiskpolicy >/dev/null 2>&1; then
    policy() { magiskpolicy --live "$1"; }
elif [ -x /data/adb/ksu/bin/ksud ]; then
    policy() { /data/adb/ksu/bin/ksud sepolicy patch "$1"; }
elif command -v ksud >/dev/null 2>&1; then
    policy() { ksud sepolicy patch "$1"; }
else
    policy() { :; }   # 两家都没有：静默跳过（模块仍会挂载，只是缺规则时 HAL 读不到东西）
fi

policy "allow hal_camera_default system_file file { read open getattr map execute }"
policy "allow hal_camera_default magisk_file file { read open getattr map execute }"

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
# ---------------------------------------------------------------------------
# 自己把 HAL 换掉（**不能依赖模块文件挂载**）
#
# 实测（一加 ACE5 / ColorOS 16 / KernelSU Next）：`mount | grep adb/modules` 是 **0** ——
# 这台 ROM 上内核方案**只执行模块脚本、不挂载模块文件**（`/vendor`、`/product` 已被 ROM 自己的
# `/my_*` overlay 栈占住）。所以"把 shim 放进 system/vendor/ 就会生效"这条在 MI6 上成立的路，
# 在这里不成立；脚本必须自己动手。
#
# 做法：**给 HAL 目录叠一层 overlayfs**
#   lower = 真实 /vendor/<dir>（其余 HAL 仍然可见）
#   upper = 我们准备好的目录（我们的 shim + 真件副本 + portal.cfg）
# 为什么不用单文件 bind：真件副本需要一个**已存在**的挂载目标，而 /vendor 只读、建不了文件；
# overlay 的 upper 层正好能在只读挂载之上"加文件"。
#
# 时序：KernelSU 的 post-fs-data 早于 init 启动 vendor.camera-provider ⇒ provider 加载到的
# 就是叠加后的（= 我们的 shim）。
# ---------------------------------------------------------------------------
MODDIR=${0%/*}
OVL=/data/adb/portalex-overlay
for HALDIR in /vendor/lib64/hw /vendor/lib/hw; do
    # 载荷可能在两种布局里（都实测过，见 scripts/build-camera-hal-module.sh 的文件头）：
    #   · `vendor/…`            —— ksud 安装的包（顶层 vendor/ 才装得上）
    #   · `system/vendor/…`     —— 手工安装的树（KernelSU 只认这种布局去挂载）
    REL=${HALDIR#/}
    SRC=""
    for cand in "$MODDIR/$REL" "$MODDIR/system/$REL"; do
        [ -d "$cand" ] && SRC="$cand" && break
    done
    [ -n "$SRC" ] || continue
    SHIM=$(ls "$SRC"/camera.*.so 2>/dev/null | grep -v '\.orig\.so$' | head -1)
    [ -s "$SHIM" ] || continue
    NAME=$(basename "$SHIM")
    ORIG="$MODDIR/orig/$NAME.orig.so"
    [ -s "$ORIG" ] || { echo "portalex: 没有真件副本，跳过 $HALDIR"; continue; }
    UP="$OVL/upper${HALDIR}"
    rm -rf "$UP"; mkdir -p "$UP" "$OVL/work${HALDIR}"
    cp -f "$SHIM" "$UP/$NAME"
    cp -f "$ORIG" "$UP/$NAME.orig.so"
    cp -f "$SRC/portal.cfg" "$UP/portal.cfg" 2>/dev/null
    # 标签必须像 vendor 库：HAL 域只被允许读/执行 vendor_file（新建在 /data 上的文件默认不是）
    chcon u:object_r:vendor_file:s0 "$UP"/* 2>/dev/null
    if mount -t overlay overlay -o "lowerdir=$HALDIR,upperdir=$UP,workdir=$OVL/work${HALDIR}" "$HALDIR" 2>/dev/null; then
        echo "portalex: overlay 已挂载到 $HALDIR（shim=$NAME）"
    else
        echo "portalex: overlayfs 不可用，退回单文件 bind（真件路径可能缺失）"
        mount --bind "$UP/$NAME" "$HALDIR/$NAME" 2>/dev/null && echo "portalex: bind $NAME"
    fi
done

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
policy "type portalex_frame_file { mlstrustedobject }"
policy "allow portalex_frame_file labeledfs filesystem associate"
# root 域两家都打：Magisk 是 `magisk`、KernelSU 是 `ksu`（打不存在的域只会报错，不致命）
for ROOTDOM in magisk ksu; do
    policy "allow $ROOTDOM portalex_frame_file dir { relabelfrom relabelto search write add_name read open getattr }"
    policy "allow $ROOTDOM portalex_frame_file file { relabelfrom relabelto read write open getattr create unlink map }"
    policy "allow $ROOTDOM shell_data_file dir { relabelfrom }"
    policy "allow $ROOTDOM shell_data_file file { relabelfrom }"
done
policy "allow untrusted_app portalex_frame_file dir { search read open getattr write add_name remove_name }"
policy "allow untrusted_app portalex_frame_file file { read write open getattr map create unlink }"
policy "allow hal_camera_default portalex_frame_file dir { search read open getattr }"
policy "allow hal_camera_default portalex_frame_file file { read write open getattr map }"
policy "allow untrusted_app shell_data_file dir { search getattr }"

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
