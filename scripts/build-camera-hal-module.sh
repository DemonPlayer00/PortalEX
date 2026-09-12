#!/bin/sh
# 打包「摄像头 HAL 包装层」模块（Magisk / KernelSU Next 通用）。**按设备出包**。
#
# ## 为什么按设备出两个包，而不是一个包带两套载荷
#
# 实测（KernelSU Next / ColorOS 16 / 一加 ACE5）：
# **`customize.sh` 在 vendor 树里新建/替换任何文件，安装都会在 "Handle partition /vendor"
# 步骤失败并整包回滚**（`Error: Failed to install module script`）—— 36 MB 真件副本试过、
# 0 字节占位文件也试过，都失败；而"脚本完全不碰 vendor 树"的对照实验全部成功。
# 所以**安装期一个字节都不动 vendor 树**：
#   · 载荷（我们的 shim）、占位文件、`portal.cfg` 全部**预先打进包里**；
#   · `customize.sh` 只做一件事：把真件 HAL 复制到 `orig/`（**分区树之外**，安装器不碰）；
#   · 开机时 `post-fs-data.sh` 把 `orig/` 里的真件 bind 到 vendor 里的占位路径上 ——
#     真件必须"看起来"在 /vendor 里，因为 Vendor 命名空间只允许 dlopen /odm|/vendor|/system/vendor。
#
# 用法：
#   sh scripts/build-camera-hal-module.sh              # 两台机器各出一个 zip
#   sh scripts/build-camera-hal-module.sh pineapple    # 只出 ACE5 的
set -e
cd "$(dirname "$0")/.."

WANT="${1:-all}"

echo "== 构建 shim（armeabi-v7a + arm64-v8a）=="
./gradlew :camera-hal:assembleDebug --console=plain -q

ARM32=$(find camera-hal/build -name camshim.so -path '*obj/armeabi-v7a*' | head -1)
ARM64=$(find camera-hal/build -name camshim.so -path '*obj/arm64-v8a*' | head -1)
[ -n "$ARM32" ] && [ -n "$ARM64" ] || { echo "找不到 shim 产物"; exit 1; }

SRC=magisk/portalex-camera-hal

pack_one() {
    PLATFORM="$1"; DIR="$2"; NAME="$3"; REAL="$4"; HMI="$5"; OPS="$6"; SO="$7"
    STAGE="camera-hal/build/msg-$PLATFORM"
    OUT="camera-hal/build/portalex-camera-hal-$PLATFORM.zip"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/$DIR" "$STAGE/orig"
    for f in module.prop customize.sh post-fs-data.sh service.sh sepolicy.rule README.md; do
        [ -f "$SRC/$f" ] && cp "$SRC/$f" "$STAGE/$f"
    done
    chmod 755 "$STAGE/customize.sh" "$STAGE/post-fs-data.sh"

    cp "$SO" "$STAGE/$DIR/$NAME"

    # ACE5（pineapple）：**另带一份 /odm 路线的载荷**。
    # 为什么两份都要：provider 在 ACE5 上 dlopen 的是 /odm/lib64/hw/camera.qcom.so，
    # 而 /vendor 那层 overlay 会让 media.swcodec 起不来（拍照废，见 docs §25.1）。
    # /odm 路线**不需要真件副本** —— 真件留在 /vendor 原封不动，shim 按 real_hal= 去 dlopen。
    if [ "$PLATFORM" = "pineapple" ]; then
        mkdir -p "$STAGE/payload/odm"
        cp "$SO" "$STAGE/payload/odm/$NAME"
        chmod 644 "$STAGE/payload/odm/$NAME"
        cat > "$STAGE/payload/odm/portal.cfg" <<EOF
real_hal=/vendor/lib64/hw/$NAME
real_hmi_bytes=$HMI
ops_index=$OPS
dump=1
report_period=20
req_vaddrs=0x1e49ca0
hook_rec_vaddrs=0x1e46f60
hook_rec_ol_vaddrs=0x6cadc
EOF
        chmod 644 "$STAGE/payload/odm/portal.cfg"
        # 模拟素材目录（可选）：把要用的 mock 文件放这里，post-fs-data 会搬到
        # /data/vendor/camera/ 并打上 vendor_camera_data_file 标签，cfg 里可直接引用。
        mkdir -p "$STAGE/payload/odm/mock"
        if [ -d magisk/mock ]; then
            cp -f magisk/mock/* "$STAGE/payload/odm/mock/" 2>/dev/null
        fi
        echo "  · 已带 /odm 路线载荷（payload/odm/）"
    fi
    # 占位文件：1 字节，只为给 post-fs-data 的 bind mount 一个**已存在**的目标
    printf 'P' > "$STAGE/$DIR/$NAME.orig.so"
    # 开关文件：逐机器写死（安装期不再生成，理由见文件头）
    cat > "$STAGE/$DIR/portal.cfg" <<EOF
dump=1
ops_index=$OPS
real_hal=/vendor/${DIR#vendor/}/$NAME.orig.so
real_hmi_bytes=$HMI
EOF
    chmod 644 "$STAGE/$DIR/$NAME" "$STAGE/$DIR/$NAME.orig.so" "$STAGE/$DIR/portal.cfg"

    rm -f "$OUT"
    python3 - "$STAGE" "$OUT" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _dirs, files in os.walk(stage):
        for f in sorted(files):
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, stage))
print("打包完成:", out, os.path.getsize(out), "字节")
PY
    python3 -c "import zipfile,sys; print('  内容:', zipfile.ZipFile(sys.argv[1]).namelist())" "$OUT"
}

case "$WANT" in
    all|msm8998) pack_one msm8998 vendor/lib/hw camera.msm8998.so /vendor/lib/hw/camera.msm8998.so 176 0 "$ARM32" ;;
esac
case "$WANT" in
    all|pineapple) pack_one pineapple vendor/lib64/hw camera.qcom.so /vendor/lib64/hw/camera.qcom.so 376 1 "$ARM64" ;;
esac
