#!/system/bin/sh
if [ -x /data/adb/apd ]; then
    /data/adb/apd module uninstall portalex-vcam-test || exit 1
elif [ -x /data/adb/magisk/magisk ]; then
    /data/adb/magisk/magisk --remove-module portalex-vcam-test || exit 1
else
    echo "no APatch/Magisk module manager"
    exit 1
fi
echo "MI6 cammock module removed; reboot required"
