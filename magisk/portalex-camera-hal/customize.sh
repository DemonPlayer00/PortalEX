#!/system/bin/sh
# 安装时执行。**只在分区树之外做事** —— 理由见 scripts/build-camera-hal-module.sh 的文件头：
# 实测 KernelSU Next 的安装器一旦发现 vendor 树里有"它不认识的"文件（新建或替换），
# 就会在 "Handle partition /vendor" 步骤失败并整包回滚。所以载荷、占位文件、portal.cfg
# 全部预先打进 zip，这里只负责**把真件 HAL 复制到 orig/**（非分区目录，安装器不碰）。
#
# 真件为什么现在就要复制：模块一挂载，`/vendor/…/camera.<soc>.so` 就变成我们的 shim 了，
# 之后再想拿真件就没机会 —— 这是唯一的时间窗。
#
# ⚠️ **这里绝对不能写 `set -e`**（实测踩过，一加 ACE5 + KernelSU Next）：
# 安装器是 **source** 这个脚本的，`set -e` 会**泄漏进安装器自己的 shell**，
# 于是它后续步骤（"Handle partition /vendor"）里任何一次非零返回都会让整包装失败 ——
# 现象是脚本正常打印完、随后报 `Error: Failed to install module script` 并整包回滚，
# 而且换布局（system/vendor → vendor）、换载荷大小都照旧失败，极难定位。
# 所以这里改成**显式判错**（下面每处关键步骤都自己检查返回值）。

ui_print "- PortalEX 摄像头 HAL 包装层（按机器出包）"

PLATFORM="$(getprop ro.board.platform)"
ui_print "- ro.board.platform=$PLATFORM"

case "$PLATFORM" in
    msm8998)
        DIR="vendor/lib/hw"; NAME="camera.msm8998.so"
        REAL_HAL="/vendor/lib/hw/camera.msm8998.so" ;;
    *)
        # pineapple（SD8Gen3 / 一加 ACE5）以及其它 64 位 QTI
        DIR="vendor/lib64/hw"; NAME="camera.qcom.so"
        REAL_HAL="/vendor/lib64/hw/camera.qcom.so" ;;
esac

# 包与机器是否配套：包内必须已经有这台机器的载荷（否则说明拿错了包）
if [ ! -s "$MODPATH/$DIR/$NAME" ]; then
    abort "本包不含 $DIR/$NAME —— 大概是为另一台机器打的包（$PLATFORM 用不了）"
fi
ui_print "- 载荷 $DIR/$NAME；真件 $REAL_HAL"

# 真件副本 → orig/（分区树之外）
ORIG="$MODPATH/orig/$NAME.orig.so"
if [ ! -s "$ORIG" ]; then
    if [ -s "$REAL_HAL" ]; then
        mkdir -p "$MODPATH/orig"
        cp -f "$REAL_HAL" "$ORIG"
        ui_print "- 已保存真实 HAL 到 $ORIG（分区树之外）"
    else
        ui_print "! 找不到真件 $REAL_HAL"
        abort "无法取得真实 HAL：放弃安装（相机保持原样，绝不留下一个加载不了真件的 shim）"
    fi
fi
chmod 644 "$ORIG" 2>/dev/null || true
ui_print "- 开机后 post-fs-data.sh 会把真件 bind 到 /vendor/${DIR#vendor/}/$NAME.orig.so"
ui_print "- 开关文件（包内已带）：$DIR/portal.cfg"
ui_print "$(cat "$MODPATH/$DIR/portal.cfg" 2>/dev/null)"
ui_print "- 安装完成（**需重启一次**：模块文件只在开机时挂载）"
