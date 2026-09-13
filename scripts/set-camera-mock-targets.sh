#!/bin/bash
# 查看 / 变更「应用层相机模拟」的启用对象（目标应用）。
#
# ## ⚠️ 一条必须写在最前面的更正（2026-09-14，走过一轮弯路之后）
#
# 这个脚本的**第一版是错的**：它直接改写 `/data/system/portalex-camera/manifest.json` 里的
# `targets`，并保留了当时的 `tagFilter`。两处都错（docs/camera-mock.md §70.6 有实测证据）：
#
#   1. **启用机制不是 `targets`** —— 是**给目标包授 content URI 权限**
#      （`granted[content://moe.fuqiuluo.portalex.cameramock/manifest]`、
#        `granted[content://moe.fuqiuluo.portalex.cameramock/video/<id>]`）。
#      `targets: []` 是**正常状态**，写它没有意义（自创语义）。
#   2. **`tagFilter` 应为 `[]`**（受支持播种写出来的就是空）；改成 `["fixture"]` 会改变筛选语义。
#
# 受支持的路径是 `scripts/seed-camera-mock.sh`（在 master 分支；`GRANT_PKG=` 指定目标包），
# 它一次完成：播种素材 + 打开开关 + **授 URI 权限**，并把 `tagFilter` 留空。
# 所以本脚本现在**只做两件事**：查看状态、委派播种 —— 不再直接改 manifest。
#
# 用法：
#   sh scripts/set-camera-mock-targets.sh                 # 查看：enabled / tagFilter / targets / points
#   sh scripts/set-camera-mock-targets.sh --grant <包名>  # 委派受支持播种（= 授权该包）
#   sh scripts/set-camera-mock-targets.sh --uri <包名>    # 试补授 URI 权限（provider 支持时）
set -u
M=/data/system/portalex-camera/manifest.json
AUTH=moe.fuqiuluo.portalex.cameramock

show() {
  adb shell "cat $M" 2>/dev/null | tr -d '\r' | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(' enabled   =', d['enabled'])
print(' tagFilter =', d.get('tagFilter'), '  <- 应为 []')
print(' targets   =', d.get('targets'), '  <- 不是启用列表，空是正常的')
print(' points    =', [(p['name'], p.get('lat'), p.get('lon'), [v['name'] for v in p.get('videos',[])]) for p in d.get('points',[])])
"
  echo " 存储内的视频：$(adb shell "ls /data/system/portalex-camera/videos/ 2>/dev/null" | tr -d '\r' | tr '\n' ' ')"
}

# 受支持脚本不在本分支工作区（它在 master）—— 需要时用 git show 取出来
ensure_seed_script() {
  [ -f /tmp/appacc/seed-camera-mock.sh ] && return 0
  mkdir -p /tmp/appacc
  git show master:scripts/seed-camera-mock.sh > /tmp/appacc/seed-camera-mock.sh 2>/dev/null || return 1
  for f in camera-fixture-check.py make-fixture-clip.py; do
    [ -f /tmp/appacc/$f ] || git show master:scripts/$f > /tmp/appacc/$f 2>/dev/null
  done
  chmod +x /tmp/appacc/*.sh /tmp/appacc/*.py 2>/dev/null
}

case "${1:-}" in
  "")
    show; exit 0;;
  --grant)
    PKG=${2:?用法: --grant <包名>}
    ensure_seed_script || { echo "取不到 master 的 seed-camera-mock.sh"; exit 1; }
    echo "== 委派受支持播种（GRANT_PKG=$PKG）=="
    ( cd /tmp/appacc && GRANT_PKG="$PKG" bash seed-camera-mock.sh ) 2>&1 | tail -6
    echo; show;;
  --uri)
    PKG=${2:?用法: --uri <包名>}
    adb shell "content call --uri content://$AUTH/manifest --method grant --arg $PKG" 2>&1 | tail -3
    echo "（若该 provider 不提供 grant 方法，请改用 --grant 走受支持播种）";;
  *)
    echo "未知参数：$1"; show; exit 2;;
esac
