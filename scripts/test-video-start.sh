#!/usr/bin/env bash
#
# 冷启动录像回归：**开头不允许泄漏真实画面**。
#
# ## 载体
#
# 用 `:app` 的 `CameraRecordActivity`（PortalEX 自己的录制页），不用系统相机：
#   · 它**默认就用前置**，与 `cam=1` 的配置天然对齐，不需要点"翻转"；
#   · 录像落在应用私有目录 ⇒ "这次新出现的那个文件"就是判据的适用范围；
#   · 按钮位置由 `uiautomator dump` 按**资源 id** 现查，不写死坐标。
#
# ## 判据为什么必须是"两段录像"
#
# 第一版拿**同一段录像的末帧**当参考，只比"开头 vs 结尾"。**负对照（不注入）当场把它将死**：
# 整段都是实景时帧间本来就几乎一样 ⇒ 判据必然通过。
# **一个不会失败的回归比没有回归更糟**，所以判据改成自标定的两段式：
#
#   A 段：正常注入          → 参考帧 A（末帧 = 我们填的画面）
#   B 段：不注入（纯转发）  → 参考帧 B（末帧 = 真实画面）
#
#   ① `D = |A末 − B末|` 必须**大**：证明确实"注入改变了画面"（否则谈不上泄漏不泄漏）
#   ② `d[i] = |A 段第 i 帧 − A末|` 必须**小**：证明**从第一帧起**就已经是改变后的画面
#
# 三种情形因此都能判对：
#   · 注入生效且开头干净 → D 大、d 小            ⇒ 通过
#   · 注入生效但开头泄漏 → D 大、前几帧 d 接近 D ⇒ 失败（并指出是哪几帧）
#   · 注入根本没生效     → D ≈ 0                 ⇒ 失败（"注入没生效"，不是通过）
#
# 阈值取比值（`d ≤ max(ABS_FLOOR, D×FRAC)`），所以换机型/换模板都不用重新标定。
#
# ## 用法
#
#   scripts/test-video-start.sh                 # 默认两段（约 1 分钟）
#   REC_SECS=8 scripts/test-video-start.sh      # 每段录久一点
#   CONTROL=0 scripts/test-video-start.sh       # 省掉 B 段（快，但判据弱很多，见上）
#   KEEP=1 scripts/test-video-start.sh          # 跑完不恢复干净配置
#
# 退出码：0 = 通过；1 = 判据不满足；2 = 环境/前置条件不满足
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 可覆盖参数 -------------------------------------------------------------
SHIM="${SHIM:-$ROOT/camera-hal/build/intermediates/cxx/Debug/4r1u4l52/obj/arm64-v8a/camshim.so}"
APP_ID="${APP_ID:-moe.fuqiuluo.portalex}"
CAM_COMPONENT="${CAM_COMPONENT:-$APP_ID/.ui.camera.CameraRecordActivity}"
REC_BUTTON_ID="${REC_BUTTON_ID:-camera_record_button}"
VID_DIR="${VID_DIR:-/data/data/$APP_ID/files/camera}"
REC_SECS="${REC_SECS:-5}"
HEAD_FRAMES="${HEAD_FRAMES:-12}"
CONTROL="${CONTROL:-1}"            # 1 = 也录一段不注入的（判据需要它）
FRAC="${FRAC:-0.12}"               # 允许的开头偏差 ≤ D 的这个比例
ABS_FLOOR="${ABS_FLOOR:-4}"        # 且不超过这个绝对值（编码器暖机量级 ~3）
MIN_CONTRAST="${MIN_CONTRAST:-20}" # D 至少这么大，才说明注入真的改变了画面
KEEP="${KEEP:-0}"
ADB="${ADB:-adb}"

# 参考机（本项目研究记录里那台）的部署配置，见 docs/camera-mock.md §58/§60
# ⚠️ 尺寸必须与**载体应用那条会话真实的缓冲**对得上。PortalEX 自己的录制页挑的录像尺寸
# 与系统相机不同：实测它的录像缓冲是 **2,400,256** 字节（系统相机是 3,194,880）。
# 对不上的表现是注入线程一直报"还没靶可打"，而**录出来的是真画面** ——
# 第一版就是这样：判据在两段真画面上比来比去，怎么看都"通过"。
# 这里用 `gen` 后端按那个尺寸**现造**一个源挂进 VVideo（免外部素材、内容确定）：
CFG_INJ="real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;cam=1;inject=1;inject_us=0;vvideo=1;vv_stream_vaddr=0x6c554;vv_burst_ms=3000;vv_probe_us=15000;vv_mount=gen:/vvideo/src/app-video::size=2400256,w=1600,h=1000,stride=1600,mode=stripes|raw:/vvideo/src/front-preview:/data/vendor/camera/ffront.raw:w=2176,h=1256,stride=2176|raw:/vvideo/src/front-video:/data/vendor/camera/fvidmock.raw:w=1920,h=1088,stride=1920;mock_map=2400256:/vvideo/src/app-video|4038656:/vvideo/src/front-preview|3194880:/vvideo/src/front-video"
CFG_RAW="real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;cam=1"
CFG_CLEAN="real_hal=/vendor/lib64/hw/camera.qcom.so;real_hmi_bytes=376;ops_index=1;dump=1;report_period=20"

TMP="${TMPDIR:-/tmp}/vvstart.$$"
mkdir -p "$TMP"

say() { printf '%s\n' "$*"; }
die() {
    printf '✗ %s\n' "$*" >&2
    exit 2
}
# ⚠️ 传进来的命令里**不能带单引号**：sh_root 是 `su -c '<命令>'` 拼出来的。
sh_dev() { $ADB shell "$1"; }
sh_root() { $ADB shell "su -c '$1'"; }

# ---- 前置检查 ---------------------------------------------------------------
say "== 前置检查 =="
[ -f "$SHIM" ] || die "找不到 shim：$SHIM（先 ./gradlew :camera-hal:assembleDebug）"
$ADB get-state >/dev/null 2>&1 || die "没有可用设备（adb devices 为空）"
sh_root id | grep -q "uid=0" || die "设备上没有 root（su -c id 不是 uid=0）"
for f in /data/vendor/camera/ffront.raw /data/vendor/camera/fvidmock.raw; do
    sh_root "test -f $f" || die "素材缺失：$f（前置预览 / 前置录像的模板）"
done
sh_dev "pm list packages" | grep -q "package:$APP_ID" || die "设备上没装 $APP_ID"
# 摄像头是运行时权限：脚本自己试着授一次（有 root 时这是确定能成的），授不上再报错
if ! sh_dev "dumpsys package $APP_ID" | grep -q "android.permission.CAMERA: granted=true"; then
    say "   $APP_ID 还没有摄像头权限，尝试自动授予…"
    sh_root "pm grant $APP_ID android.permission.CAMERA" >/dev/null 2>&1
    sh_dev "dumpsys package $APP_ID" | grep -q "android.permission.CAMERA: granted=true" ||
        die "$APP_ID 没有摄像头权限，且自动授予失败（先在应用里授权一次）"
fi
command -v ffmpeg >/dev/null || die "需要 ffmpeg"
command -v ffprobe >/dev/null || die "需要 ffprobe"
python3 -c 'import PIL, numpy' 2>/dev/null || die "需要 python3 的 PIL 与 numpy"
say "   shim    : $(basename "$SHIM")（$(stat -c%s "$SHIM") 字节）"
say "   录制页  : $CAM_COMPONENT"
say "   两段录像: $([ "$CONTROL" = "1" ] && echo 是 || echo 否)"

# 录像目录的属主必须是应用自己：**被 root 操作过之后属主会变成 root**，应用再建文件就是
# `EACCES`（实测：CameraRecordActivity 报 FileNotFoundException ... EACCES，录像静默地一次都没开始）。
APP_UID="$(sh_root "stat -c %u /data/data/$APP_ID/files" | tr -d '\r')"
APP_GID="$(sh_root "stat -c %g /data/data/$APP_ID/files" | tr -d '\r')"
[ -n "$APP_UID" ] && sh_root "chown -R $APP_UID:$APP_GID $VID_DIR" >/dev/null 2>&1

$ADB push "$SHIM" /data/local/tmp/camshim.new.so >/dev/null || die "推送 shim 失败"

# ---- 一次录像 ---------------------------------------------------------------
# record_once <标签> <cfg> <期望：inject|raw>
record_once() {
    tag="$1"
    cfg="$2"
    expect="$3"
    log="$TMP/$tag.log"
    say
    say "== [$tag] 部署 + 录像 ${REC_SECS}s（期望：$expect）=="
    sh_root "am force-stop $APP_ID" >/dev/null 2>&1
    $ADB logcat -c >/dev/null 2>&1
    $ADB logcat -s PortalCamShim:V VVVideo:V >"$log" 2>&1 &
    logpid=$!
    sleep 1
    before="$(sh_root "ls $VID_DIR 2>/dev/null" | tr -d '\r')"
    sh_root "sh /data/local/tmp/deploy.sh \"$cfg\"" >/dev/null 2>&1
    sleep 4
    if [ "$expect" = "inject" ]; then
        grep -aq "VVideo 统一层已就绪" "$log" || {
            grep -a "PortalCamShim\|VVVideo" "$log" | tail -15
            kill $logpid 2>/dev/null
            die "[$tag] VVideo 层没起来"
        }
        grep -aq "经 VVideo 统一层" "$log" || die "[$tag] 模板没走统一层"
    else
        grep -aq "真实 HAL 已加载并整块接管" "$log" || die "[$tag] shim 没加载"
    fi

    sh_root "am start -n $CAM_COMPONENT" >/dev/null 2>&1
    sleep 5
    # 按钮位置**现查**（按资源 id）。注意按 `<node` 切行，**不要**用 `tr '>' '>\n'` ——
    # tr 映射的是字符集，那个写法是恒等替换，贪婪的 sed 会匹配到别的元素的 bounds
    # （第一版就是这么把点击落在状态栏上，录像一次都没开始）。
    sh_dev "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
    ui="$(sh_dev "cat /sdcard/ui.xml" 2>/dev/null)"
    center="$(printf '%s' "$ui" | sed 's/<node/\n<node/g' | grep "$REC_BUTTON_ID" |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' |
        head -1)"
    if [ -z "$center" ]; then
        sh_dev "screencap -p /sdcard/ui.png" >/dev/null 2>&1
        $ADB pull /sdcard/ui.png "$TMP/$tag-ui.png" >/dev/null 2>&1
        kill $logpid 2>/dev/null
        die "[$tag] 界面上没找到 $REC_BUTTON_ID（快照在 $TMP/$tag-ui.png）"
    fi
    set -- $center
    tx=$((($1 + $3) / 2))
    ty=$((($2 + $4) / 2))
    say "   录制按钮 = ($tx, $ty)"
    sh_dev "input tap $tx $ty"
    sleep "$REC_SECS"
    sh_dev "input tap $tx $ty"
    sleep 3
    kill $logpid 2>/dev/null

    newest=""
    for _try in 1 2 3 4 5; do
        after="$(sh_root "ls -t $VID_DIR 2>/dev/null" | tr -d '\r')"
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            printf '%s\n' "$before" | grep -qxF "$f" && continue
            newest="$f"
            break
        done <<EOF2
$after
EOF2
        [ -n "$newest" ] && break
        sleep 2
    done
    if [ -z "$newest" ]; then
        say "   $VID_DIR 里仍只有旧的："
        printf '%s\n' "$after" | sed 's/^/     /'
        grep -a "PortalCamShim" "$log" | tail -8 | sed 's/^/     /'
        die "[$tag] 这次没录出新文件 ⇒ 判据无从谈起（不是通过）"
    fi
    sh_root "cp $VID_DIR/$newest /data/local/tmp/vv_rec.mp4; chmod 666 /data/local/tmp/vv_rec.mp4" >/dev/null 2>&1
    $ADB pull /data/local/tmp/vv_rec.mp4 "$TMP/$tag.mp4" >/dev/null 2>&1
    [ -s "$TMP/$tag.mp4" ] || die "[$tag] 拉取录像失败"
    say "   新录：$newest"
    ffprobe -v error -select_streams v:0 -show_entries stream=width,height,nb_frames,duration \
        -of default=nw=1 "$TMP/$tag.mp4" | tr '\n' ' ' | sed 's/^/   /'
    say ""
    mkdir -p "$TMP/$tag"
    ffmpeg -v error -i "$TMP/$tag.mp4" -vf "select='lt(n\,$HEAD_FRAMES)'" -fps_mode passthrough \
        "$TMP/$tag/%03d.png" >/dev/null 2>&1
    ffmpeg -v error -sseof -0.5 -i "$TMP/$tag.mp4" -frames:v 1 "$TMP/$tag-last.png" >/dev/null 2>&1
    [ -s "$TMP/$tag-last.png" ] || die "[$tag] 抽末帧失败"
    n="$(ls "$TMP/$tag" | wc -l)"
    [ "$n" -ge 3 ] || die "[$tag] 抽帧失败（只有 $n 帧）"
    say "   抽到 $n 帧 + 末帧参考"
}

record_once inj "$CFG_INJ" inject
[ "$CONTROL" = "1" ] && record_once raw "$CFG_RAW" raw

# ---- 判据 -------------------------------------------------------------------
say
say "== 判据 =="
python3 - "$TMP" "$CONTROL" "$FRAC" "$ABS_FLOOR" "$MIN_CONTRAST" <<'PY'
import glob, os, sys
import numpy as np
from PIL import Image

tmp = sys.argv[1]
control = sys.argv[2] == "1"
frac, floor, min_contrast = float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])

def load(p, size=None):
    im = Image.open(p).convert("L")
    if size:
        im = im.resize(size)
    return np.asarray(im, dtype=np.int16)

ref_a = load(os.path.join(tmp, "inj-last.png"))
shape = (ref_a.shape[1], ref_a.shape[0])
heads = [load(p, shape) for p in sorted(glob.glob(os.path.join(tmp, "inj", "*.png")))]

print(f"   A 段（注入）{len(heads)} 帧待判；参考 = 该段末帧")
if control:
    ref_b = load(os.path.join(tmp, "raw-last.png"), shape)
    d_ab = float(np.abs(ref_a - ref_b).mean())
    print(f"   ① 对比度 D = |A末 − B末| = {d_ab:6.2f}（要求 ≥ {min_contrast:g}）")
    if d_ab < min_contrast:
        print("   ❌ D 太小：注入并没有改变画面 ⇒ 判据无从谈起，**不是通过**")
        sys.exit(1)
else:
    d_ab = 8.0
    print("   ⚠️ CONTROL=0：没有 B 段，无法确认『注入确实改变了画面』，判据只在比值意义上成立")

limit = max(floor, d_ab * frac)
print(f"   ② 每帧允许偏差 ≤ max({floor:g}, D×{frac:g}) = {limit:6.2f}")
bad = []
for i, a in enumerate(heads, 1):
    d = float(np.abs(a - ref_a).mean())
    flag = ""
    if d > limit:
        flag = "   ← 还不像我们填的画面（开头泄漏）"
        bad.append(f"{i:03d}")
    print(f"     帧{i:03d}  mean={a.mean():6.1f} std={a.std():5.1f}  |diff-A|={d:6.2f}{flag}")
if bad:
    print(f"   ❌ 失败：第 {','.join(bad)} 帧不像我们填的画面")
    sys.exit(1)
print(f"   ✅ 通过：开头 {len(heads)} 帧全都是我们填的画面（D={d_ab:.1f}，逐帧偏差 ≤ {limit:.1f}）")
sys.exit(0)
PY
PYRC=$?

# ---- 换靶时间线（上下文）----------------------------------------------------
say
say "== 换靶时间线（探询窗口，来自 A 段日志）=="
if grep -aq "探询窗口\|靶集变了" "$TMP/inj.log" 2>/dev/null; then
    grep -a "探询窗口\|靶集变了\|块缓冲" "$TMP/inj.log" | tail -10 | sed 's/^/   /'
else
    say "   （本次没有重配流：直接在前置上录像 ⇒ 没有换靶；"
    say "     这条只在模式切换/重开会话时才有内容，那时为空才需要查 vv_stream_vaddr）"
fi

# ---- 收尾 -------------------------------------------------------------------
if [ "$KEEP" != "1" ]; then
    sh_root "sh /data/local/tmp/deploy.sh \"$CFG_CLEAN\"" >/dev/null 2>&1
    sleep 4
    say
    say "已恢复干净配置（KEEP=1 可保留）"
fi
exit $PYRC
