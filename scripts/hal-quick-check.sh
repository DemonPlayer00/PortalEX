#!/bin/sh
# HAL 摄像头虚拟化：**一命令回归**（每次改完 shim、部署完就应当跑这一条）。
#
# 为什么需要它：这条线上的结论几乎全是"真机实测"得来的，而"实测"如果每次都要人手
# 截屏、肉眼比对、翻日志，那它就不会被跑 —— 于是回归就只能靠记忆（本项目已经吃过这个亏：
# §74 那个"假成功"就是没人回头复看一次画面）。这个脚本把三个**可判**的量固定下来：
#
#   ① OPPO 相机取景器是否真的变成我们的画面（截图取景区：**均匀 + 够亮**）
#   ② 首帧注入延迟（对标 OPPO 半秒；当前基线 ~1.3s，其中大头是应用自身启动）
#   ③ 压缩面（带 UBWC 位）是否被**明确拒绝**而不是被涂成假画面（§75/§76）
#
# 用法：sh scripts/hal-quick-check.sh            # 需要设备已部署好 shim 与 cfg（auto_tpl=1）
# 退出码：0 = 全过；1 = 有 FAIL
set -u

# 屏幕必须亮着：实测踩过 —— 熄屏时相机应用照样活着、shim 照样在注入，
# 但截图是全黑的，于是脚本会报"没接管"（假 FAIL）。顺手把常亮打开，无人值守也能跑。
wake_screen() {
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb shell wm dismiss-keyguard >/dev/null 2>&1
    adb shell svc power stayon true >/dev/null 2>&1
    sleep 1
}

PKG_CAM=com.oplus.camera
PKG_FIX=moe.fuqiuluo.camerafixture
FAIL=0

say() { printf '%s\n' "$*"; }

shot_uniform() { # $1 = 设备上的 png 路径 → 打印 "mean_std_flag" 供判定
    adb pull "$1" /tmp/hqc.png >/dev/null 2>&1 || { echo "nopull"; return; }
    python3 - <<'PY'
import zlib, struct
d = open('/tmp/hqc.png','rb').read(); pos = 8; idat = b''
while pos < len(d):
    ln = struct.unpack('>I', d[pos:pos+4])[0]; t = d[pos+4:pos+8]; da = d[pos+8:pos+8+ln]
    if t == b'IHDR': w, h, bd, ct = struct.unpack('>IIBB', da[:10])
    elif t == b'IDAT': idat += da
    pos += 12 + ln
raw = zlib.decompress(idat); bpp = 4 if ct == 6 else 3; st = w*bpp
out = bytearray(); prev = bytearray(st); i = 0
for y in range(h):
    f = raw[i]; i += 1; line = bytearray(raw[i:i+st]); i += st
    if f == 1:
        for x in range(bpp, st): line[x] = (line[x] + line[x-bpp]) & 255
    elif f == 2:
        for x in range(st): line[x] = (line[x] + prev[x]) & 255
    elif f == 3:
        for x in range(st): line[x] = (line[x] + ((line[x-bpp] if x >= bpp else 0) + prev[x])//2) & 255
    elif f == 4:
        for x in range(st):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            p = a + b - c; pa = abs(p-a); pb = abs(p-b); pc = abs(p-c)
            line[x] = (line[x] + (a if (pa <= pb and pa <= pc) else (b if pb <= pc else c))) & 255
    out += line; prev = line
px = bytes(out)
vals = []
for y in range(int(h*0.16), int(h*0.42), 9):        # 取景区（避开顶部/底部 UI）
    row = y*w*bpp
    for x in range(int(w*0.05), int(w*0.95), 9):
        vals.append(px[row + x*bpp])
n = len(vals); mean = sum(vals)/n
var = sum((v-mean)**2 for v in vals)/n
print("%.1f %.1f" % (mean, var ** 0.5))
PY
}

say "=== ⓪ 唤醒屏幕 ==="
wake_screen

say "=== ① OPPO 相机取景器 ==="
adb shell su -c "logcat -c" >/dev/null 2>&1
adb shell am force-stop $PKG_CAM >/dev/null 2>&1
sleep 1
adb shell monkey -p $PKG_CAM -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
# **必须切到"照片"模式再判**（第 50 轮踩的坑）：相机应用会记住上一次的模式，
# 而视频模式下取景器来自另一路流 —— 我们在 2136×1200 上写得再好也看不见，
# 脚本就会报"没接管"（实测：切回照片模式后取景区立刻 mean=128.0 std=0.0）。
# 这个坐标是这台设备(1264×2780)底部模式行里的"照片"。
adb shell input tap 600 2140 >/dev/null 2>&1
sleep 4
adb shell su -c "screencap -p /data/local/tmp/hqc.png" >/dev/null 2>&1
read -r MEAN SD <<EOF
$(shot_uniform /data/local/tmp/hqc.png)
EOF
say "取景区 mean=$MEAN std=$SD"
if [ "${SD:-999}" != "nopull" ] && awk "BEGIN{exit !($SD < 8 && $MEAN > 60)}"; then
    say "  PASS：均匀且够亮 ⇒ 取景器是我们的画面（不是真实场景）"
else
    say "  FAIL：取景区不均匀/太暗 ⇒ 没接管或注入没生效"
    FAIL=1
fi
LAT=$(adb logcat -d 2>/dev/null | grep -a "首帧注入延迟" | tail -1 | sed 's/.*延迟：//; s/ ms.*//')
say "首帧注入延迟：${LAT:-（未出现）} ms"
if [ -n "${LAT:-}" ] && awk "BEGIN{exit !($LAT < 3000)}"; then
    say "  PASS：< 3000ms（基线 ~1.3s，其中大头是应用自身启动）"
else
    say "  FAIL：没有报数或超过 3000ms —— 认领路径上多半又被加了等待（§76.5）"
    FAIL=1
fi

say "=== ② 压缩面必须被明确拒绝（不能涂成假画面）==="
adb shell su -c "logcat -c" >/dev/null 2>&1
adb shell am force-stop $PKG_CAM >/dev/null 2>&1
adb shell am force-stop $PKG_FIX >/dev/null 2>&1
sleep 1
adb shell am start -n $PKG_FIX/.ListSessionActivity >/dev/null 2>&1
sleep 8
REF=$(adb logcat -d 2>/dev/null | grep -a "拒绝" | grep -a "UBWC" | tail -1)
if [ -n "$REF" ]; then
    say "  PASS：$(printf '%s' "$REF" | sed 's/.*PortalCamShim: //')"
else
    say "  FAIL：没有看到带 UBWC 位 ⇒ 拒绝 的记录（夹具这条面在 §76 里是压缩面）"
    FAIL=1
fi
SIG=$(adb logcat -d -s CameraFixture:V 2>/dev/null | tail -1)
say "夹具取样：$(printf '%s' "$SIG" | sed 's/.*CameraFixture: //')"
case "$SIG" in
    *4c4c4c4c*|*808080*) say "  FAIL：夹具看到了均匀画面 ⇒ 说明又在对压缩面盲涂（假成功）"; FAIL=1 ;;
    *) say "  PASS：夹具仍是真实画面（我们没碰它）" ;;
esac

say ""
if [ "$FAIL" = 0 ]; then say "=== 全部通过 ==="; else say "=== 有 FAIL（见上）==="; fi
exit $FAIL
