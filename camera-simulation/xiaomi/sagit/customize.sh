#!/system/bin/sh
# 安装期自检：任何一项失败都 abort，不留下一个“看起来装了但加载不了”的模块。
# 注意：Magisk/APatch 会 source 本脚本，绝不能写 set -e（会污染安装器 shell）。

ui_print "- PortalEX MI6/sagit cammock 安装自检"

fail() {
    ui_print "! $1"
    abort "安装中止：$1"
}

[ -f "$MODPATH/module.prop" ] || fail "缺少 module.prop"
[ -s "$MODPATH/system/vendor/lib/hw/camera.msm8998.so" ] || fail "缺少 32 位 HAL 载荷 camera.msm8998.so"

MAGIC="$(head -c 4 "$MODPATH/system/vendor/lib/hw/camera.msm8998.so" 2>/dev/null)"
[ "$MAGIC" = "ELF" ] || fail "HAL 载荷不是 ELF 文件"
CLASS="$(od -An -t u1 -j 4 -N 1 "$MODPATH/system/vendor/lib/hw/camera.msm8998.so" 2>/dev/null | tr -d ' ')"
[ "$CLASS" = "1" ] || fail "HAL 载荷不是 32 位 ELF（MI6 需要 32 位）"

MODEL="$(getprop ro.product.model)"
DEVICE="$(getprop ro.product.device)"
PLATFORM="$(getprop ro.board.platform)"
ui_print "- model=$MODEL device=$DEVICE platform=$PLATFORM"
case "$DEVICE:$MODEL:$PLATFORM" in
    *sagit*|*MI\ 6*|*msm8998*) ;;
    *) fail "机型不匹配：需要 sagit/MI 6/msm8998" ;;
esac

[ -s /vendor/lib/hw/camera.msm8998.so ] || fail "找不到 vendor 真件 camera.msm8998.so"
[ -d /cache ] && [ -w /cache ] || fail "/cache 不可写，无法建立 overlay upperdir"
[ -x /data/adb/apd ] || [ -x /debug_ramdisk/magiskpolicy ] || [ -x /data/adb/magisk/magiskpolicy ] || \
    fail "找不到可用的 root 策略工具（APatch/Magisk）"

ui_print "- 安装自检通过；重启后由 post-fs-data.sh 再次验证 overlay"
ui_print "- 失败时查看 /data/local/tmp/portalex-vcam.log"
