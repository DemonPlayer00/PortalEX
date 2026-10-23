#!/system/bin/sh
# MI6 实现自己的库位置；app 只提供标准 io/upload 快照。
DIR=${0%/*}
SRC="$DIR/io/upload"
DST=/data/local/tmp/portalex/cammock/library
[ -f "$SRC/routes.json" ] || { echo "io/upload/routes.json missing"; exit 1; }
rm -rf "$DST"; mkdir -p "$DST"
cp -f "$SRC/routes.json" "$DST/routes.json"
for f in "$SRC"/*.mp4; do [ -f "$f" ] && cp -f "$f" "$DST/"; done
chmod -R 644 "$DST"/*
echo "MI6 cammock library stored"
