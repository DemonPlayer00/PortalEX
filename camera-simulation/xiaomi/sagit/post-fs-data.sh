#!/system/bin/sh
# APatch / Magisk 兼容：不改 /vendor，运行时用 overlay 把虚拟 HAL 叠到 HAL 目录上。
# 关键点：overlay 的 upper/work 必须放在 ext4（/cache），/data 在这台机器上不被
# overlayfs 接受（实测 "filesystem ... not supported as upperdir"）。
MODDIR=${0%/*}
HALDIR=/vendor/lib/hw
BASE=/cache/portalex-vcam
UPPER=$BASE/upper
WORK=$BASE/work
LOG=/data/local/tmp/portalex-vcam.log

fail_boot() {
    echo "portalex-vcam: FAILED: $1" >> "$LOG"
    umount "$HALDIR" 2>/dev/null
    exit 1
}

mkdir -p "$UPPER" "$WORK" || fail_boot "cannot create overlay dirs"
[ -s "$MODDIR/system/vendor/lib/hw/camera.msm8998.so" ] || fail_boot "module payload missing"
cp -f "$MODDIR/system/vendor/lib/hw/camera.msm8998.so" "$UPPER/" 2>/dev/null || fail_boot "payload copy failed"
# 真件副本在**挂载前**从 /vendor 现取：不把厂商 blob 放进仓库/模块包。
if [ ! -s "$UPPER/camera.msm8998.orig.so" ]; then
    cp -f /vendor/lib/hw/camera.msm8998.so "$UPPER/camera.msm8998.orig.so" 2>/dev/null || fail_boot "real HAL copy failed"
fi
chcon u:object_r:vendor_file:s0 "$UPPER"/* 2>/dev/null
chcon u:object_r:vendor_file:s0 "$UPPER" "$WORK" 2>/dev/null

# 先补 SELinux：overlay 根目录继承 upper 的 cache_file 标签，HAL 域需要能穿过它。
if [ -x /data/adb/apd ]; then
    /data/adb/apd sepolicy --live "allow hal_camera_default cache_file dir { getattr read open search }" 2>/dev/null
    /data/adb/apd sepolicy --live "allow hal_camera_default cache_file file { read open getattr map execute }" 2>/dev/null
    /data/adb/apd sepolicy --live "allow hal_camera_default vendor_file dir { getattr read open search }" 2>/dev/null
elif [ -x /debug_ramdisk/magiskpolicy ]; then
    /debug_ramdisk/magiskpolicy --live "allow hal_camera_default cache_file dir { getattr read open search }" 2>/dev/null
    /debug_ramdisk/magiskpolicy --live "allow hal_camera_default cache_file file { read open getattr map execute }" 2>/dev/null
    /debug_ramdisk/magiskpolicy --live "allow hal_camera_default vendor_file dir { getattr read open search }" 2>/dev/null
fi

# 如果已有旧的 overlay，先摘掉（幂等）
umount "$HALDIR" 2>/dev/null
if mount -t overlay overlay -o "lowerdir=$HALDIR,upperdir=$UPPER,workdir=$WORK" "$HALDIR"; then
    # 挂载后必须确认 provider 看到的确实是虚拟载荷，而不是旧 HAL。
    if cmp -s "$UPPER/camera.msm8998.so" "$HALDIR/camera.msm8998.so"; then
        echo "portalex-vcam: overlay mounted on $HALDIR (payload verified)" > "$LOG"
    else
        fail_boot "overlay mounted but payload mismatch"
    fi
else
    fail_boot "overlay mount failed"
fi
