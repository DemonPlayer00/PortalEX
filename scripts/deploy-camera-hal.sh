#!/bin/sh
# 构建 camera HAL shim（32 位）并部署成 Magisk 模块（供实验/复现用）。
#
# ⚠️ 当前 M0 版本会让 camera provider 崩溃（见 magisk/portalex-camera-hal/README.md）。
#    部署后务必准备回退：touch /data/adb/modules/portalex-camera-hal/disable && adb reboot
#
# 用法：sh scripts/deploy-camera-hal.sh [设备序列号]
set -e
cd "$(dirname "$0")/.."
DEV="${1:--d}"
ADB="adb $DEV"
M=/data/adb/modules/portalex-camera-hal

echo "== 构建 shim（armeabi-v7a）=="
./gradlew :camera-hal:assembleDebug --console=plain -q
SO=$(find camera-hal/build -name camshim.so -path "*armeabi-v7a*" | head -1)
echo "   $SO"

echo "== 推送到设备 =="
$ADB push "$SO" /data/local/tmp/camshim.so >/dev/null
for f in module.prop customize.sh sepolicy.rule; do
    $ADB push "magisk/portalex-camera-hal/$f" "/data/local/tmp/$f" >/dev/null
done

echo "== 建立模块目录（原件复制必须在挂载生效前）=="
$ADB shell "su -c '
set -e
mkdir -p $M/system/vendor/lib/hw
cp -f /data/local/tmp/module.prop /data/local/tmp/customize.sh /data/local/tmp/sepolicy.rule $M/
rm -f $M/sepolicy.rule.orig
chmod 755 $M/customize.sh
# 原件副本：只在此刻（未挂载）能从 /vendor 拿到真件
if [ ! -s $M/system/vendor/lib/hw/camera.msm8998.orig.so ]; then
    cp -f /vendor/lib/hw/camera.msm8998.so $M/system/vendor/lib/hw/camera.msm8998.orig.so
fi
cp -f /data/local/tmp/camshim.so $M/system/vendor/lib/hw/camera.msm8998.so
chmod 644 $M/system/vendor/lib/hw/camera.msm8998*.so
rm -f $M/disable
find $M -type f | sort
'"
echo
echo "部署完成。重启生效：$ADB reboot"
echo "回退：$ADB shell su -c 'touch $M/disable' && $ADB reboot"
