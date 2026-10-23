#!/system/bin/sh
# MI6 cammock 由 app 显式启动：挂载虚拟 HAL overlay，重启 camera provider。
HALDIR=/vendor/lib/hw
BASE=/cache/portalex-vcam
UPPER=$BASE/upper
WORK=$BASE/work
[ -d "$UPPER" ] || mkdir -p "$UPPER"
[ -d "$WORK" ] || mkdir -p "$WORK"
if ! mountpoint -q "$HALDIR" || ! mount | grep -q "overlay.*$HALDIR"; then
    mount -t overlay overlay -o "lowerdir=$HALDIR,upperdir=$UPPER,workdir=$WORK" "$HALDIR" || {
        echo "failed to mount HAL overlay"
        exit 1
    }
fi
setprop ctl.stop vendor.camera-provider-2-4
sleep 1
setprop ctl.start vendor.camera-provider-2-4
setprop ctl.restart cameraserver
for i in 1 2 3 4 5 6 7 8 9 10; do
  dumpsys media.camera 2>/dev/null | grep -q "Number of camera devices: [1-9]" && break
  sleep 0.5
done
echo "MI6 cammock started"
