#!/system/bin/sh
# MI6 HAL 模块在开机时由 root 管理器加载；PortalEX 只通过本脚本询问执行状态。
if [ -f /data/adb/modules/portalex-vcam-test/module.prop ] || [ -f /data/adb/modules/portalex-cammock-sagit/module.prop ]; then
    echo "MI6 cammock HAL module is active (reboot-managed)"
    exit 0
fi
echo "MI6 cammock module is not installed"
exit 1
