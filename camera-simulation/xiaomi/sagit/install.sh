#!/system/bin/sh
# MI6 实现自己的 root 模块对接；PortalEX 只调用本脚本，不直接依赖 APatch/Magisk。
DIR=${0%/*}
ZIP="$DIR/source.zip"
[ -s "$ZIP" ] || { echo "source.zip missing"; exit 1; }
if [ -x /data/adb/apd ]; then
    /data/adb/apd module install "$ZIP" || exit 1
elif [ -x /data/adb/magisk/magisk ]; then
    /data/adb/magisk/magisk --install-module "$ZIP" || exit 1
else
    echo "no APatch/Magisk module manager"
    exit 1
fi
echo "MI6 cammock module installed; reboot required"
