#!/bin/sh
# 高清**图像**链路的一命令回归：RAW → 12MP 照片 是否还在我们手里（§79/§80）。
#
# 判据（§79.3 / §80.2）：把 RAW10 写成"左半 0x40 / 右半 0xC0"，拍一张 12MP 照片，
# 比较照片**上/下**两半的均值 —— **下 > 上 且 差 > 15** 记为命中。
# 为什么是上/下：RAW 与 JPEG 之间是 **90° 旋转**（§41/§79.2）。
# 为什么不是固定 40：RAW 的分割要过 ISP 的自动曝光/色调曲线，对比度会被压
# （手动那次 74.8，脚本首跑 19.7，暗场里更小）。
#
# **为什么连拍三张**（第 48 轮的教训）：两次连跑得到 19.7 与 0.7，差别只在时序 ——
# `mock_map` 靠注入线程按尺寸认领，而 RAW 那批缓冲是拍照流程里才凑齐的。
# 单次通过/失败都说明不了问题，**命中率**才是这条链路的真实状态。
#
# 用法：sh scripts/hal-photo-check.sh
# 退出码：0 = 至少一张命中且现场已复原；1 = 三张全未命中或复原失败
set -u

DATADIR=/data/vendor/camera
TMPL=$DATADIR/raw_split.raw
CFG=$DATADIR/portal.cfg
PKG=com.oplus.camera
FAIL=0
say() { printf '%s\n' "$*"; }

say "=== 0 造模板（左 0x40 / 右 0xC0 的 RAW10）==="
python3 - <<'PYSH' || exit 1
w, h = 4096, 3072
row = w * 10 // 8
buf = bytearray()
for _ in range(h):
    buf += bytes([0x40]) * (row // 2) + bytes([0xC0]) * (row - row // 2)
open('/tmp/hpc_split.raw', 'wb').write(bytes(buf))
print("  本地模板 /tmp/hpc_split.raw", len(buf), "字节")
PYSH
adb push /tmp/hpc_split.raw /data/local/tmp/hpc_split.raw >/dev/null 2>&1 || exit 1

say "=== 1 装机（cp + chmod + chcon，注册 mock_map）==="
adb shell su -c "rm -f $TMPL; cp /data/local/tmp/hpc_split.raw $TMPL; chmod 666 $TMPL; \
  chcon u:object_r:vendor_camera_data_file:s0 $TMPL; \
  sed -i '/^mock_map=/d' $CFG; echo mock_map=15728640:$TMPL >> $CFG; \
  setprop ctl.stop vendor.camera-provider; sleep 1; setprop ctl.start vendor.camera-provider; sleep 4" \
  >/dev/null 2>&1
adb shell su -c "grep mock_map $CFG" 2>/dev/null | tail -1

say "=== 2 连拍三张（量成功率，不赌单次）==="
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell svc power stayon true >/dev/null 2>&1
adb shell am force-stop $PKG >/dev/null 2>&1
sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
# **必须切到"照片"模式再判**（第 50 轮踩的坑）：相机应用会记住上一次的模式，
# 而视频模式下取景器来自另一路流 —— 我们在 2136×1200 上写得再好也看不见，
# 脚本就会报"没接管"（实测：切回照片模式后取景区立刻 mean=128.0 std=0.0）。
# 这个坐标是这台设备(1264×2780)底部模式行里的"照片"。
adb shell input tap 600 2140 >/dev/null 2>&1
sleep 4

OKN=0
i=1
while [ $i -le 3 ]; do
    adb shell input keyevent 27 >/dev/null 2>&1
    sleep 7
    J=$(adb shell "ls -t /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -1" | tr -d '\r')
    if [ -z "$J" ]; then
        say "  第 $i 张：没找到照片（快门没生效？）"
    else
        adb pull "$J" /tmp/hpc_shot_$i.jpg >/dev/null 2>&1
        D=$(python3 - "$i" <<'PYSH'
import sys
from PIL import Image, ImageStat
im = Image.open('/tmp/hpc_shot_%s.jpg' % sys.argv[1]).convert('L')
w, h = im.size
top = ImageStat.Stat(im.crop((int(w*0.20), int(h*0.05), int(w*0.80), int(h*0.35)))).mean[0]
bot = ImageStat.Stat(im.crop((int(w*0.20), int(h*0.65), int(w*0.80), int(h*0.95)))).mean[0]
print("%.1f %.1f %.1f" % (top, bot, bot - top))
PYSH
)
        TOP=$(echo "$D" | awk '{print $1}')
        BOT=$(echo "$D" | awk '{print $2}')
        DT=$(echo "$D" | awk '{print $3}')
        if awk "BEGIN{exit !($DT > 15)}"; then
            say "  第 $i 张：上 $TOP / 下 $BOT / 差 $DT  => 命中（我们的画面）"
            OKN=$((OKN + 1))
        else
            say "  第 $i 张：上 $TOP / 下 $BOT / 差 $DT  => 未命中（真实场景）"
        fi
    fi
    i=$((i + 1))
done
say "  命中率：$OKN/3"
if [ "$OKN" -gt 0 ]; then
    say "  PASS：至少一张命中（机制成立；命中率才是它的真实状态，见 §80.3）"
else
    say "  FAIL：三张全未命中 —— RAW→照片 链路这次完全没接管"
    FAIL=1
fi

say "=== 3 复原现场（删 mock_map、重启 provider、核实取景器又是我们的画面）==="
adb shell su -c "sed -i '/^mock_map=/d' $CFG; setprop ctl.stop vendor.camera-provider; sleep 1; \
  setprop ctl.start vendor.camera-provider; sleep 4" >/dev/null 2>&1
adb shell am force-stop $PKG >/dev/null 2>&1
sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 14
adb shell su -c "screencap -p /data/local/tmp/hpc.png" >/dev/null 2>&1
adb pull /data/local/tmp/hpc.png /tmp/hpc.png >/dev/null 2>&1
R=$(python3 - <<'PYSH'
from PIL import Image, ImageStat
im = Image.open('/tmp/hpc.png').convert('L'); w, h = im.size
st = ImageStat.Stat(im.crop((int(w*0.05), int(h*0.16), int(w*0.95), int(h*0.42))))
print("%.1f %.1f" % (st.mean[0], st.stddev[0]))
PYSH
)
RM=$(echo "$R" | awk '{print $1}')
RS=$(echo "$R" | awk '{print $2}')
say "  取景区 mean/std = $RM / $RS"
# 复原也要**带重试**（§86.2/§87 的同一条经验）：设备跑过多次强制重启之后，provider 会话会漂，
# 表现是"靶集掉到 0 块、取景区一直黑"，再重启一次就回来。判 FAIL 前先按顺序排查：
# 相机模式 → 靶集是否为 0 → 才轮到注入逻辑。
rtry=1
while [ $rtry -le 3 ]; do
    if awk "BEGIN{exit !($RS < 8 && $RM > 60)}"; then break; fi
    rtry=$((rtry + 1))
    say "  （复原第 $rtry 次尝试：重启 provider + 重拉相机）"
    adb shell am force-stop $PKG >/dev/null 2>&1
    adb shell su -c "setprop ctl.restart vendor.camera-provider; sleep 6" >/dev/null 2>&1
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    sleep 1
    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 12
    adb shell input tap 600 2140 >/dev/null 2>&1
    sleep 5
    adb shell su -c "screencap -p /data/local/tmp/hpc.png" >/dev/null 2>&1
    adb pull /data/local/tmp/hpc.png /tmp/hpc.png >/dev/null 2>&1
    R=$(python3 - <<'PYSH'
from PIL import Image, ImageStat
im = Image.open('/tmp/hpc.png').convert('L'); w, h = im.size
st = ImageStat.Stat(im.crop((int(w*0.05), int(h*0.16), int(w*0.95), int(h*0.42))))
print("%.1f %.1f" % (st.mean[0], st.stddev[0]))
PYSH
)
    RM=$(echo "$R" | awk '{print $1}')
    RS=$(echo "$R" | awk '{print $2}')
    say "    取景区 mean/std = $RM / $RS"
done
if awk "BEGIN{exit !($RS < 8 && $RM > 60)}"; then
    say "  PASS：复原成功（取景器又是均匀的虚拟画面）"
else
    say "  FAIL：复原后取景器不是我们的画面 —— 现场没清干净（已重试 3 次）"
    FAIL=1
fi

say ""
if [ "$FAIL" = 0 ]; then say "=== 照片链路本次通过 ==="; else say "=== 有 FAIL（见上）==="; fi
exit $FAIL
