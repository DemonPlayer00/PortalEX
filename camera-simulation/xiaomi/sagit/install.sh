#!/system/bin/sh
# MI6 实现自己的 root 模块对接；PortalEX 只调用本脚本，不直接依赖 APatch/Magisk。
DIR=${0%/*}
ZIP="$DIR/source.zip"
if [ ! -s "$ZIP" ]; then
    # Direct Magisk/APatch module installation: the root manager is already
    # installing this directory as a module. Nothing else to do.
    echo "MI6 cammock module is being installed by root manager; reboot required"
    exit 0
fi
if [ -x /data/adb/apd ]; then
    /data/adb/apd module install "$ZIP" || exit 1
elif [ -x /data/adb/magisk/magisk ]; then
    /data/adb/magisk/magisk --install-module "$ZIP" || exit 1
else
    echo "no APatch/Magisk module manager"
    exit 1
fi
echo "MI6 cammock module installed; reboot required"
