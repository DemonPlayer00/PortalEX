#!/bin/bash
# 差分量具：把「相机 provider 手上所有 dmabuf 的尺寸」在**预览态**与**录制态**各采一次，
# 相减即得"编码器面（录像输入面）的缓冲尺寸/块数"。
#
# 为什么要这么量（docs/camera-mock.md §62）：
#   · `dump=1` 的流表只给 宽高/format/usage，**不给字节数**；
#   · 按尺寸猜哪块面喂给编码器，历史上猜错过很多次（§47/§55/§61.2）；
#   · `/proc/<pid>/fdinfo/<fd>` 里的 `size:` 是分配的真值，且**不需要重编 shim**。
#
# 用法：
#   sh scripts/camera-dmabuf-diff.sh [录制秒数，默认 8]
# 前置：HAL shim 已部署、相机 provider 在跑（见 scripts/deploy-camera-hal-pineapple.sh）；
#       被测应用默认是 PortalEX 自己的录制页（com.moe... 见 PKG），换成别的应用改 PKG/ACT/按钮 id。
set -u
PKG=${PKG:-moe.fuqiuluo.portalex}
ACT=${ACT:-$PKG/.ui.camera.CameraRecordActivity}
BTN_ID=${BTN_ID:-camera_record_button}
SECS=${1:-8}

adb shell 'cat > /data/local/tmp/portalex-inv.sh' <<'EOF'
#!/system/bin/sh
# 打印某进程所有 dmabuf 的 size 直方图：<块数> <字节数>
P=$1
for f in /proc/$P/fd/*; do
  n=${f##*/}; t=$(readlink $f 2>/dev/null)
  case "$t" in
    *dmabuf*)
      s=$(grep -m1 -i '^size:' /proc/$P/fdinfo/$n 2>/dev/null | awk '{print $2}')
      [ -n "$s" ] && echo "$s";;
  esac
done | sort -n | uniq -c
EOF
adb shell chmod 755 /data/local/tmp/portalex-inv.sh

PID=$(adb shell pidof vendor.qti.camera.provider-service_64 | tr -d '\r')
[ -n "$PID" ] || { echo "相机 provider 不在跑"; exit 1; }
echo "provider pid=$PID"

# 按**资源 id**现查按钮中心：模式条/按钮会随状态平移，写死坐标必然偶发点错（§30.2 的教训）
btn() {
  adb shell uiautomator dump /sdcard/portalex-ui.xml >/dev/null 2>&1
  adb pull /sdcard/portalex-ui.xml /tmp/portalex-ui.xml >/dev/null 2>&1
  python3 - "$BTN_ID" <<'PY'
import re,sys
want=sys.argv[1]
s=open('/tmp/portalex-ui.xml',encoding='utf-8',errors='replace').read()
for m in re.finditer(r'<node[^>]*>', s):
    n=m.group(0); rid=re.search(r'resource-id="([^"]*)"',n)
    if not rid or not rid.group(1).endswith(want): continue
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if b: print((int(b.group(1))+int(b.group(3)))//2,(int(b.group(2))+int(b.group(4)))//2)
    break
PY
}
snap() { adb shell "sh /data/local/tmp/portalex-inv.sh $PID" | tr -d '\r' > "$1"; }

adb shell am force-stop "$PKG"; sleep 1
adb shell am start -n "$ACT" >/dev/null; sleep 7
snap /tmp/portalex-hist-preview.txt

C=$(btn)
[ -n "$C" ] || { echo "找不到 $BTN_ID（页面没起来？）"; exit 1; }
adb shell input tap $C; sleep "$SECS"
snap /tmp/portalex-hist-recording.txt
C=$(btn); [ -n "$C" ] && adb shell input tap $C   # 停止并保存
sleep 3

python3 - <<'PY'
def load(p):
    d={}
    for line in open(p):
        line=line.strip()
        if not line: continue
        c,s=line.split(); d[int(s)]=int(c)
    return d
A=load('/tmp/portalex-hist-preview.txt'); B=load('/tmp/portalex-hist-recording.txt')
print('\n=== 只在录制态出现/增多的尺寸（= 编码器面的缓冲）===')
for s in sorted(B):
    if B[s]-A.get(s,0) > 0:
        print(f'  {s:>10}  +{B[s]-A.get(s,0):<3} (预览态 {A.get(s,0)})')
print('\n=== 两态都在（多数是预览/其他常驻面）===')
for s in sorted(B):
    if s in A and B[s]==A[s] and s > 1024*1024:
        print(f'  {s:>10}  {B[s]}')
PY

echo
echo "下一步：对该尺寸取活帧判布局 ——"
echo "  adb shell sh /data/local/tmp/deploy.sh '<原 cfg>;raw_dump=<尺寸>:/data/vendor/camera/probe.raw'"
echo "  录制后拉 /data/vendor/camera/probe.raw.<尺寸>，按线性/瓦片两种假设各算一次与真景的 NCC"
