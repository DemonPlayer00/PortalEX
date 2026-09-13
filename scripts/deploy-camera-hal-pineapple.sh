#!/bin/sh
# SD8Gen3（pineapple）运行期部署：**只挂 /odm/lib64/hw**。
#
# 为什么砍掉 /vendor/lib64/hw 的 overlay（v3 的坑，实测）：那层 overlay 会让
# `media.swcodec` 起不来（`GraphicBufferMapper: gralloc-mapper is missing` → abort），
# 于是相机 App 一按快门就死、照片永远不落盘。**把 /vendor 那层退掉，照片立刻恢复正常。**
# cfg 与素材改放 /data/local/tmp/portalex/（已被标成 portalex_frame_file，
# sepolicy 里本来就有对 hal_camera_default 的读写放行），根本不需要 /vendor 可写。
#
# 用法： sh /tmp/deploy-camera-hal-pineapple.sh "<portal.cfg 内容行，分号分隔>"
CFG_LINES="${1:-real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1}"

setprop ctl.stop vendor.camera-provider
sleep 2

mkdir -p /data/local/tmp/odmtmp
mount | grep -q " /data/local/tmp/odmtmp " || mount -t tmpfs tmpfs /data/local/tmp/odmtmp
mkdir -p /data/local/tmp/odmtmp/upper /data/local/tmp/odmtmp/work

cp -f /data/local/tmp/camshim.new.so /data/local/tmp/odmtmp/upper/camera.qcom.so
chmod 644 /data/local/tmp/odmtmp/upper/camera.qcom.so
ln -sf /vendor/lib64/hw/camera.qcom.so /data/local/tmp/odmtmp/upper/camera.qcom.so.orig.so

mount | grep -q " /odm/lib64/hw " || \
  mount -t overlay overlay -o lowerdir=/odm/lib64/hw,upperdir=/data/local/tmp/odmtmp/upper,workdir=/data/local/tmp/odmtmp/work /odm/lib64/hw

ksud sepolicy patch 'allow hal_camera_default tmpfs file { read open getattr map execute }' >/dev/null 2>&1
ksud sepolicy patch 'allow hal_camera_default tmpfs lnk_file { read getattr }' >/dev/null 2>&1

# cfg 放相机自己的数据目录（vendor_camera_data_file：可写、hal_camera_default 可读，无需 overlay）
# ⚠️ 先删再建：`: >` 只截断、会保留旧文件的标签（踩过：残留的 portalex_frame_file 标签让 HAL 读不到）
rm -f /data/vendor/camera/portal.cfg
: > /data/vendor/camera/portal.cfg
echo "$CFG_LINES" | tr ';' '\n' | while read -r L; do
    [ -n "$L" ] || continue
    echo "$L" >> /data/vendor/camera/portal.cfg
done
chmod 666 /data/vendor/camera/portal.cfg
chcon u:object_r:vendor_camera_data_file:s0 /data/vendor/camera/portal.cfg 2>/dev/null
chmod 666 /data/vendor/camera/mock.raw 2>/dev/null
# ⚠️ 必须 chcon：新建文件在这里是 unlabeled，hal_camera_default 读不到（restorecon 也救不了，
# 因为该路径的 file_contexts 规则来自 KernelSU 注入的策略，plat file_contexts 里没有）
[ -f /data/local/tmp/portalex/mock.raw ] && 
# ---- 量具：一起装（否则被清理过一次就"量具不见了"，第 14 轮踩过）----
# 打印某进程所有 dmabuf 的 size 直方图：`sh /data/local/tmp/portalex-inv.sh <pid>`
cat > /data/local/tmp/portalex-inv.sh <<'INVEOF'
#!/system/bin/sh
# 用法：sh /data/local/tmp/portalex-inv.sh <pid>   →  每行 "<块数> <字节数>"
P=$1
for f in /proc/$P/fd/*; do
  n=${f##*/}; t=$(readlink $f 2>/dev/null)
  case "$t" in
    *dmabuf*)
      s=$(grep -m1 -i '^size:' /proc/$P/fdinfo/$n 2>/dev/null | awk '{print $2}')
      [ -n "$s" ] && echo "$s";;
  esac
done | sort -n | uniq -c
INVEOF
chmod 755 /data/local/tmp/portalex-inv.sh
# 现场帧落盘/流表都要用的辅助：把 provider pid 直接算出来
cat > /data/local/tmp/portalex-pid.sh <<'PIDEOF'
#!/system/bin/sh
pidof vendor.qti.camera.provider-service_64
PIDEOF
chmod 755 /data/local/tmp/portalex-pid.sh
echo "-- 量具已装 --"
ls -l /data/local/tmp/portalex-inv.sh /data/local/tmp/portalex-pid.sh

echo "-- 部署后 --"
ls -l /odm/lib64/hw/camera.qcom.so
cat /data/vendor/camera/portal.cfg
echo "-- /vendor overlay 应当没有 --"
mount | grep -c "/vendor/lib64/hw "
setprop ctl.start vendor.camera-provider
sleep 6
echo "provider=$(getprop init.svc_debug_pid.vendor.camera-provider)"
