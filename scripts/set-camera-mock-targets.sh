#!/bin/bash
# 设置/查看「应用层相机模拟」的目标应用列表（relay 存储里的 manifest.targets）。
#
# 为什么需要它：应用层（LSPosed 模块）即使已启用、目标包也在作用域内，**没有 targets 就什么都不发生**
# （docs/camera-mock.md §70）。而应用内 UI 手动加目标不适合无人值守/可复现的流程。
#
# 实测要点（§70.1）：
#   · 必须**原地覆盖**文件内容（`cat > 目标路径`），不要 push 到 /data/system（会丢 SELinux 标签）；
#   · 写完 **6 秒后复核仍为写入值** ⇒ system_server 不会用内存缓存盖回；
#   · 文件属主/标签应为 `system system u:object_r:system_data_file:s0`。
#
# 用法：
#   sh scripts/set-camera-mock-targets.sh                      # 只看当前 targets
#   sh scripts/set-camera-mock-targets.sh pkgA [pkgB ...]      # 设为这些包
#   sh scripts/set-camera-mock-targets.sh --restore            # 回滚到上次备份
#   sh scripts/set-camera-mock-targets.sh --clear              # 清空 targets
set -u
M=/data/system/portalex-camera/manifest.json
BAK=/data/local/tmp/portalex-manifest.bak.json
TMP=/data/local/tmp/portalex-manifest.new.json

cur() { adb shell "cat $M" 2>/dev/null | tr -d '\r'; }

case "${1:-}" in
  "")
    echo "当前 manifest："; cur | python3 -c "import sys,json;d=json.load(sys.stdin);print(' enabled=',d['enabled'],' tagFilter=',d.get('tagFilter'),'\n targets=',d['targets'],'\n points=',[(p['name'],len(p.get('videos',[]))) for p in d.get('points',[])])" 2>/dev/null || cur
    exit 0;;
  --restore)
    adb shell "[ -f $BAK ] || exit 1"; adb shell "cat $BAK > $M; chmod 600 $M"
    echo "已回滚；当前 targets = $(cur | python3 -c "import sys,json;print(json.load(sys.stdin)['targets'])" 2>/dev/null)"; exit 0;;
  --clear)
    NEW="[]";;
  *) NEW="$(printf '%s\n' "$@" | python3 -c "import sys,json;print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))")";;
esac

# 1) 备份（只备份一次，避免把改坏的状态当备份）
adb shell "[ -f $BAK ] || cp $M $BAK"
echo "备份：$(adb shell "ls -l $BAK" | awk '{print $5}') 字节"

# 2) 本地改 + 推临时文件 + 原地上覆写
cur > /tmp/portalex-manifest.json
python3 - "$NEW" <<'PY'
import json,sys
d=json.load(open('/tmp/portalex-manifest.json'))
d['targets']=json.loads(sys.argv[1])
json.dump(d,open('/tmp/portalex-manifest.new.json','w'),ensure_ascii=False)
print('待写入 targets =',d['targets'])
PY
adb push /tmp/portalex-manifest.new.json $TMP >/dev/null 2>&1
adb shell "cat $TMP > $M; chmod 600 $M"

# 3) 复核：立即 + 6 秒后（确认没被 system_server 盖回）
now=$(cur | python3 -c "import sys,json;print(json.load(sys.stdin)['targets'])" 2>/dev/null)
sleep 6
later=$(cur | python3 -c "import sys,json;print(json.load(sys.stdin)['targets'])" 2>/dev/null)
echo "写入后 targets = $now"
echo "6 秒后 targets = $later"
[ "$now" = "$later" ] && echo "✅ 持久（未被缓存盖回）" || { echo "❌ 被改回去了 —— 别用这条路，改走 relay 协议 PUT_CONFIG"; exit 1; }
echo "文件：$(adb shell "ls -lZ $M" | awk '{print $3, $4, $5}')"
