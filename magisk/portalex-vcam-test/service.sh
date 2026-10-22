#!/system/bin/sh
# 只记录现场，方便失败后取证；不主动重启任何东西。
sleep 12
{
  echo "=== portalex-vcam service.sh ==="
  getprop init.svc.vendor.camera-provider-2-4
  ps -A -o pid,args | grep -i camera
  logcat -d -s PortalVCam:* 2>/dev/null | tail -40
} >> /data/local/tmp/portalex-vcam.log 2>&1
