#!/bin/sh
# 一键跑 host 测试（不需要设备 / NDK）。任一失败即非零退出。
#   sh xposed/src/main/cpp/test/run.sh
set -e
cd "$(dirname "$0")/.."      # → xposed/src/main/cpp

CC=${CC:-cc}
OUT=${TMPDIR:-/tmp}
fail=0

for t in test/vw_consumer_split_test.c test/vw_invariants_test.c; do
    name=$(basename "$t" .c)
    printf '== %s\n' "$name"
    $CC -D_GNU_SOURCE -I test/stub -I . "$t" virtual_world.c vw_rand.c vw_noise.c vw_gait.c -lpthread -lm -o "$OUT/$name" || {
        echo "  编译失败"; fail=1; continue; }
    "$OUT/$name" || fail=1
done

[ "$fail" = 0 ] && printf '\n全部 host 测试通过 ✓\n' || printf '\n有失败 ✗\n'
exit $fail
