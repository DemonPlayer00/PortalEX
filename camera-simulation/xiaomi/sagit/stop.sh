#!/system/bin/sh
# 显式停止：撤销 overlay，重启 vendor provider，回到真实相机。
HALDIR=/vendor/lib/hw
umount "$HALDIR" 2>/dev/null
setprop ctl.stop vendor.camera-provider-2-4
sleep 1
setprop ctl.start vendor.camera-provider-2-4
setprop ctl.restart cameraserver
for i in 1 2 3 4 5 6 7 8 9 10; do
  dumpsys media.camera 2>/dev/null | grep -q "Number of camera devices: [1-9]" && break
  sleep 0.5
done
echo "MI6 cammock stopped; real camera restored"
