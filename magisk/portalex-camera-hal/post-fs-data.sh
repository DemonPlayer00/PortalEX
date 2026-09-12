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

# ---------------------------------------------------------------------------
# 单文件 bind 的**完整版**（overlayfs 不可用时的兜底；ACE5 实测走这条）
#
# 两个关键点：
#  1) **真件也必须看起来在 /vendor 里**：vendor namespace 只允许 dlopen
#     /vendor|/odm|/system/vendor，所以真件不能留在 /data/adb（那会 dlopen 失败 ⇒
#     shim 只能按惰性模块运行 ⇒ 相机不可用）。而 /vendor 只读、建不了新文件，
#     于是真件必须**借用一个已存在的闲置槽位**；
#  2) 槽位白名单按"没人会加载"排序，且**当下没有任何进程映射它**才用：
#     RenderScript 自 Android 14 起没有任何组件会加载，是首选；
#     audio/vibrator/power 这类**会被懒加载**的绝不能碰。
# ---------------------------------------------------------------------------
pick_slot() {
    for cand in android.hardware.renderscript@1.0-impl.so consumerir.default.so \
        local_time.default.so vendor.qti.hardware.alarm-impl.so; do
        [ "$cand" = "$NAME" ] && continue
        [ -f "$HALDIR/$cand" ] || continue
        grep -qF "$cand" /proc/*/maps 2>/dev/null && continue
        echo "$cand"
        return 0
    done
    return 1
}

bind_deploy() {
    SLOT=$(pick_slot) || {
        echo "portalex: 找不到闲置槽位，放弃 bind（相机保持原样）"
        return 1
    }
    mount --bind "$ORIG" "$HALDIR/$SLOT" || {
        echo "portalex: 真件 bind 到 $SLOT 失败"
        return 1
    }
    if ! mount --bind "$SHIM" "$HALDIR/$NAME"; then
        umount "$HALDIR/$SLOT"
        echo "portalex: shim bind 失败，已回滚真件槽位"
        return 1
    fi
    # ⚠️ 开关文件**不在这里写**：它必须在 SHARED_DIR 被打成 portalex_frame_file **之后**创建
    # （新文件的标签继承目录；写在前面会拿到 shell_data_file，HAL 就读不到了 —— 这个坑很隐蔽，
    #  因为文件明明存在、权限也对）。这里只把槽位记下来，由脚本末尾统一落盘。
    PORTAL_SLOT="$HALDIR/$SLOT"
    PORTAL_CFG_SRC="$SRC/portal.cfg"
    PICKED_SLOT="$SLOT"
    echo "portalex: bind 部署完成（真件→$SLOT，shim→$NAME）"
    return 0
}

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
        # 旧版本这里只 bind 了 shim，真件路径因此不存在 ⇒ shim 按惰性模块运行（相机不可用）。
        # 现在改成完整版：真件借一个闲置槽位 + 写 cfg 指过去。
        echo "portalex: overlayfs 不可用，改用「真件借槽位 + shim 覆盖」"
        bind_deploy
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
# 让 HAL 域能"穿过" /data/local/tmp（shell_data_file）去读 portalex/ 里的开关文件与帧通道
policy "allow hal_camera_default shell_data_file dir { search }"
policy "allow untrusted_app shell_data_file dir { search getattr }"

# 预建帧文件（**app 不建文件**：新建文件的标签由创建者决定，会落在 app 域的类型上；
# 由模块建好并打标签，app 只负责打开写、HAL 只负责读）
if [ ! -f "$FRAME_FILE" ]; then
    truncate -s "$FRAME_BYTES" "$FRAME_FILE"
fi
chmod 0666 "$FRAME_FILE"
# shim 的故障状态文件：HAL 域对该目录只有 search/read，没有 create/add_name，
# 所以**必须由模块预建**（否则 shim 想落盘也写不出来 —— §19 排查时正因此丢过一次线索）。
STATE_FILE="$SHARED_DIR/camshim.state"
[ -f "$STATE_FILE" ] || : > "$STATE_FILE"
chmod 0666 "$STATE_FILE"
chcon u:object_r:portalex_frame_file:s0 "$SHARED_DIR" 2>/dev/null
chcon u:object_r:portalex_frame_file:s0 "$FRAME_FILE" 2>/dev/null
chcon u:object_r:portalex_frame_file:s0 "$STATE_FILE" 2>/dev/null
# 文件属主给 PortalEX（装了就给，没装就留给 0666 —— 安装后再重启一次即可）
APP_UID=$(stat -c %u /data/data/moe.fuqiuluo.portalex 2>/dev/null)
[ -n "$APP_UID" ] && chown "$APP_UID:$APP_UID" "$FRAME_FILE"

# ---------------------------------------------------------------------------
# 开关文件（bind 路线专用）：**必须在 SHARED_DIR 打好标签之后写**（见 bind_deploy 的注释）
# real_hal 指向刚借的那个闲置槽位；其余键沿用随包发下来的 per-device 值
# （real_hmi_bytes / ops_index 都是机型相关的，绝不能在这里硬编码）。
# ---------------------------------------------------------------------------
if [ -n "$PORTAL_SLOT" ]; then
    CFG_OUT="$SHARED_DIR/portal.cfg"
    if [ -f "$PORTAL_CFG_SRC" ]; then
        sed "s|^real_hal=.*|real_hal=$PORTAL_SLOT|" "$PORTAL_CFG_SRC" > "$CFG_OUT"
    else
        printf 'real_hal=%s\n' "$PORTAL_SLOT" > "$CFG_OUT"
    fi
    grep -q '^real_hal=' "$CFG_OUT" || printf 'real_hal=%s\n' "$PORTAL_SLOT" >> "$CFG_OUT"
    chmod 0644 "$CFG_OUT"
    chcon u:object_r:portalex_frame_file:s0 "$CFG_OUT" 2>/dev/null
    echo "portalex: 开关文件已写 $CFG_OUT（real_hal=$PORTAL_SLOT）"
fi
