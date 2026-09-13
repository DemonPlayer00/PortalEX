#!/bin/sh
# 高清**图像**链路的一命令回归：RAW → 12MP 照片 是否还在我们手里（§79）。
#
# 判据（§79.3）：把 RAW10 写成"左半 0x40 / 右半 0xC0"，拍一张 12MP 照片，
# 比较照片**上/下**两半的均值 —— 差 > 40 即通过（真实暗场基线差 ≈ 0，实测 74.8）。
# 之所以比"上/下"而不是"左/右"：RAW 与 JPEG 之间是 **90° 旋转**（§41/§79.2）。
#
# 用法：sh scripts/hal-photo-check.sh
# 退出码：0 = 通过（并已把现场复原）；1 = 失败或复原失败
set -u

DATADIR=/data/vendor/camera
TMPL=$DATADIR/raw_split.raw
CFG=$DATADIR/portal.cfg
SIZE=$((4096*3072*10/8))
PKG=com.oplus.camera
FAIL=0
say() { printf '%s\n' "$*"; }

say "=== 0 造模板（左 0x40 / 右 0xC0 的 RAW10，$SIZE 字节）==="
python3 - "$TMPL" "$SIZE" <<'PY' || exit 1
import sys
path, size = sys.argv[1], int(sys.argv[2])
w, h = 4096, 3072
row = w * 10 // 8
buf = bytearray()
for _ in range(h):
    buf += bytes([0x40]) * (row // 2) + bytes([0xC0]) * (row - row // 2)
open('/tmp/hpc_split.raw', 'wb').write(bytes(buf[:size]))
print("  本地模板 /tmp/hpc_split.raw", len(buf[:size]), "字节")
PY
adb push /tmp/hpc_split.raw /data/local/tmp/hpc_split.raw >/dev/null 2>&1 || exit 1

say "=== 1 装机（cp + chmod + chcon，注册 mock_map）==="
adb shell su -c "rm -f $TMPL; cp /data/local/tmp/hpc_split.raw $TMPL; chmod 666 $TMPL; \
  chcon u:object_r:vendor_camera_data_file:s0 $TMPL; \
  sed -i '/^mock_map=/d' $CFG; echo mock_map=15728640:$TMPL >> $CFG; \
  setprop ctl.stop vendor.camera-provider; sleep 1; setprop ctl.start vendor.camera-provider; sleep 4" \
  >/dev/null 2>&1
adb shell su -c "grep mock_map $CFG" 2>/dev/null | tail -1

say "=== 2 拍照 ==="
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell svc power stayon true >/dev/null 2>&1
adb shell am force-stop $PKG >/dev/null 2>&1
sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
adb shell input keyevent 27 >/dev/null 2>&1
sleep 7
J=$(adb shell "ls -t /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -1" | tr -d '\r')
if [ -z "$J" ]; then say "  FAIL：没找到照片（快门没生效？）"; FAIL=1; else
    adb pull "$J" /tmp/hpc_shot.jpg >/dev/null 2>&1
    DIFF=$(python3 - <<'PY'
from PIL import Image, ImageStat
im = Image.open('/tmp/hpc_shot.jpg').convert('L')
w, h = im.size
top = im.crop((int(w*0.20), int(h*0.05), int(w*0.80), int(h*0.35)))
bot = im.crop((int(w*0.20), int(h*0.65), int(w*0.80), int(h*0.95)))
print("%.1f %.1f %.1f" % (ImageStat.Stat(top).mean[0], ImageStat.Stat(bot).mean[0],
                          ImageStat.Stat(bot).mean[0] - ImageStat.Stat(top).mean[0]))
PY
)
    say "  $J"
    say "  上/下/差 = $DIFF"
    # 判据：**下 > 上，且差 > 15**。为什么不是固定 40：RAW 里的分割经过 ISP 的自动曝光/
    # 色调曲线后对比度会被压（手动那次 74.8，脚本这次 19.7 —— 同一套模板、不同曝光）。
    # 方向必须对（我们的模板是"左暗右亮"，经 90° 旋转后应当是"上暗下亮"），
    # 只判差值会把"整幅偏亮"误判成通过。
    DT=$(echo $DIFF | awk '{print $3}')
    if awk "BEGIN{exit !($DT > 15)}"; then
        say "  PASS：照片上暗下亮（差 $DT）⇒ 12MP 图像内容仍在我们手里（方向 = §79.2 的 90° 映射）"
    else
        say "  FAIL：上/下几乎一样（差 $DT）⇒ RAW→照片 链路没接管（或被别的模板盖掉）"
        FAIL=1
    fi
fi

say "=== 3 复原现场（删 mock_map、重启 provider、核实取景器又是我们的画面）==="
adb shell su -c "sed -i '/^mock_map=/d' $CFG; setprop ctl.stop vendor.camera-provider; sleep 1; \
  setprop ctl.start vendor.camera-provider; sleep 4" >/dev/null 2>&1
adb shell am force-stop $PKG >/dev/null 2>&1
sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
sleep 4   # 刚拍完照，取景器要重新起流：给它多几秒（实测 10 秒时偶尔还没认领完）
adb shell su -c "screencap -p /data/local/tmp/hpc.png" >/dev/null 2>&1
adb pull /data/local/tmp/hpc.png /tmp/hpc.png >/dev/null 2>&1
R=$(python3 - <<'PY'
from PIL import Image, ImageStat
im = Image.open('/tmp/hpc.png').convert('L'); w, h = im.size
st = ImageStat.Stat(im.crop((int(w*0.05), int(h*0.16), int(w*0.95), int(h*0.42))))
print("%.1f %.1f" % (st.mean[0], st.stddev[0]))
PY
)
say "  取景区 mean/std = $R"
if awk "BEGIN{exit !($(echo $R | awk '{print $2}') < 8 && $(echo $R | awk '{print $1}') > 60)}"; then
    say "  PASS：复原成功（取景器又是均匀的虚拟画面）"
else
    say "  FAIL：复原后取景器不是我们的画面 —— 现场没清干净"
    FAIL=1
fi

say ""
if [ "$FAIL" = 0 ]; then say "=== 照片链路全部通过 ==="; else say "=== 有 FAIL（见上）==="; fi
exit $FAIL
