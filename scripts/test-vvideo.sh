#!/usr/bin/env bash
#
# VVideo 层自测（**在开发机上跑**，不需要设备）。
#
# 为什么要有这个脚本：本层的价值全在"不变量"上（路径解析、挂载语义、卸载引用、
# 事件驱动是否真的在睡、变换链的代数），而这些在真机上**看不出来** ——
# 真机上只能看到"画面对不对"。这里逐条钉死，并挂 ASan/UBSan 把内存错误一起抓掉。
#
#   用法：scripts/test-vvideo.sh [--no-sanitize]
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CC_BIN="${CC:-gcc}"
OUT="${TMPDIR:-/tmp}/vvideo_selftest"

SAN=1
[ "${1:-}" = "--no-sanitize" ] && SAN=0

FLAGS=(-std=gnu11 -O1 -g -Wall -Wextra -Wno-unused-parameter -DVVVIDEO_HOST
       -I "$ROOT/camera-hal/src/vvideo")
if [ "$SAN" = 1 ]; then
    FLAGS+=(-fsanitize=address,undefined -fno-omit-frame-pointer)
fi

echo "== 编译 =="
# shellcheck disable=SC2086
"$CC_BIN" "${FLAGS[@]}" \
    "$ROOT"/camera-hal/src/vvideo/*.c \
    "$ROOT"/camera-hal/tools/vvideo_selftest.c \
    -lpthread -o "$OUT" || {
    echo "编译失败"
    exit 2
}
echo "   → $OUT"

echo
echo "== 运行 =="
if [ "$SAN" = 1 ]; then
    # leak 检测打开：本层有引用计数与递归释放，漏内存/重复释放都该在这里暴露。
    # （rootfs 单例由自测自己用 __lsan_ignore_object 豁免，见 vvideo_root_singleton()）
    ASAN_OPTIONS=detect_leaks=1:abort_on_error=0 \
    UBSAN_OPTIONS=print_stacktrace=1 \
        "$OUT"
else
    "$OUT"
fi
rc=$?

echo
if [ $rc -eq 0 ]; then
    echo "✅ VVideo 自测通过"
else
    echo "❌ VVideo 自测失败（退出码 $rc）"
fi
exit $rc
