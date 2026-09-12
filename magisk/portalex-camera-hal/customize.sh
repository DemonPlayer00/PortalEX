#!/system/bin/sh
# 安装时执行：此刻模块**还没挂载**，/vendor/lib/hw/camera.msm8998.so 仍是原件 ——
# 这是唯一能拿到真实字节的机会（挂载生效后同一路径就是我们的 shim 了）。
#
# 副本放在**同一个 vendor 库目录**下、只换文件名：这样它继承 vendor_file 标签，
# HAL 域（hal_camera_default）本来就能读这个目录，不需要任何 sepolicy 规则。
# 放 /data/adb 会被标成 adb_data_file，dlopen 会被 SELinux 拒掉（实测踩过）。
ORIG="$MODPATH/system/vendor/lib/hw/camera.msm8998.orig.so"
if [ ! -s "$ORIG" ]; then
    cp -f /vendor/lib/hw/camera.msm8998.so "$ORIG"
    ui_print "- 已保存原始 HAL 到 $ORIG"
fi
chmod 644 "$ORIG"
ui_print "- 默认开关：/vendor/lib/hw/portal.cfg（usage_patch=2 / init_wrap=1 / inject=1）"
ui_print "- 共享帧文件由 post-fs-data.sh 在 /data/local/tmp/portalex 预建并打标签"
ui_print "- 安装后需重启一次（Magisk 只在开机时挂载模块文件）"
ui_print "- PortalEX Camera HAL Shim 安装完成"
