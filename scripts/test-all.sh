#!/bin/sh
# PortalEX 一键测试门禁：host 测试（native）+ 两个模块的 JVM 单测。
# 任何一个失败即非零退出 —— 改造/修复后先跑这个，再谈装机。
#
#   sh scripts/test-all.sh
#
# 说明：
#   · host 测试不需要设备与 NDK（虚拟世界无 Android 依赖，见 xposed/src/main/cpp/test/README.md）；
#   · app 侧只跑 arm64 变体（与实机部署一致）；要跑全变体自己加 :app:testAppDebugUnitTest 等。
set -e
cd "$(dirname "$0")/.."

echo "== host 测试（native 投递不变量） =="
sh xposed/src/main/cpp/test/run.sh

echo
echo "== JVM 单测（:xposed 纯函数 / :app 序列化契约） =="
./gradlew :xposed:testDebugUnitTest :app:testArm64DebugUnitTest --console=plain

echo
echo "全部测试通过 ✓"
