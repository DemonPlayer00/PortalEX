#!/system/bin/sh
killall portal_camera_provider 2>/dev/null
setprop ctl.start vendor.camera-provider
echo "cammock provider stopped; vendor provider restored"
