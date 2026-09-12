#!/system/bin/sh
# 部署 watchdog（fail-inert，late_start 阶段由 KernelSU/Magisk 执行）。
#
# ## 为什么必须有它
#
# 我们在动的是**相机 HAL**：一旦我们的替换在某台 ROM 上不兼容，provider 会崩溃循环，
# 而 init 在崩够次数后会**禁用服务**（`pidof` 为空、相机彻底消失，只能手动
# `setprop ctl.start vendor.camera-provider` 或重启才回得来 —— ACE5 实测）。
# 所以开机后必须有一步"回头看"：provider 没活下来就**自动卸掉我们的挂载、删掉开关文件、
# 把服务拉起来**。用户不应该因为一次实验得到一台需要手救的设备。
#
# ## 判据（为什么不是"pidof 非空就算活"）
#
# 崩溃循环时 init 每 5 秒重启一次 provider，`pidof` 会**时有时无** —— 必须看**稳定性**：
# 连续 3 次采样（间隔 5 秒）pid 一致才算活。整个过程写进 deploy.log，即使回滚了，
# 证据也留着（配合 /data/tombstones 就能知道是"没起来"还是"起来又崩"）。
MODDIR=${0%/*}
HAL_DIR=/vendor/lib64/hw
HAL_NAME=camera.qcom.so
SHARED_DIR=/data/local/tmp/portalex
CFG=$SHARED_DIR/portal.cfg
LOG=$SHARED_DIR/deploy.log
SVC=vendor.qti.camera.provider-service_64

log() { echo "$(date '+%m-%d %H:%M:%S') [watchdog] $*" >> "$LOG" 2>/dev/null; }

# 没挂载 = 本次没部署（overlay 路线或根本没装成功），什么也不管
grep -q " $HAL_DIR/$HAL_NAME " /proc/mounts || exit 0

log "检测到 HAL 部署，开始观察 provider 稳定性"

# 等 boot 完成 + provider 起来（最多 ~90 秒）
i=0
while [ $i -lt 9 ]; do
    sleep 10
    i=$((i + 1))
    [ -n "$(pidof $SVC)" ] && break
done

A=$(pidof $SVC)
sleep 5
B=$(pidof $SVC)
sleep 5
C=$(pidof $SVC)
log "采样：A=${A:-空} B=${B:-空} C=${C:-空}"

if [ -n "$A" ] && [ "$A" = "$B" ] && [ "$B" = "$C" ]; then
    log "provider 稳定存活（pid=$A），保留本次部署"
    exit 0
fi

log "provider 不稳定/未运行 ⇒ 判定部署在这台机器上不兼容，开始回滚"
SLOT=$(sed -n 's|^real_hal=||p' "$CFG" 2>/dev/null | head -1)

# 顺序很重要：**先杀 provider 再卸挂载**，否则它持有文件、umount 返回 EBUSY
# （2026-09-13 踩过：回滚脚本的 `||` 分支会把 EBUSY 谎报成"未挂载"）
P=$(pidof $SVC)
[ -n "$P" ] && kill -9 $P 2>/dev/null && log "已杀 provider（pid=$P）"
sleep 1

umount "$HAL_DIR/$HAL_NAME" 2>/dev/null && log "已卸下 shim（$HAL_NAME）" || log "卸 shim 失败"
if [ -n "$SLOT" ]; then
    umount "$SLOT" 2>/dev/null && log "已卸下真件槽位（$SLOT）" || log "卸真件槽位失败（$SLOT）"
fi
rm -f "$CFG" && log "开关文件已删"

setprop ctl.start vendor.camera-provider 2>/dev/null
sleep 8
log "回滚完成：provider pid=$(pidof $SVC)"
exit 0
