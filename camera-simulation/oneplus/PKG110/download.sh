#!/system/bin/sh
DIR=${0%/*}
SRC=/data/system/portalex/camera
DST="$DIR/io/download"
mkdir -p "$DST"
[ -f "$SRC/library.json" ] || { echo "system_server library missing"; exit 1; }
cp -f "$SRC/library.json" "$DST/routes.json"
for f in "$SRC"/videos/*.mp4; do [ -f "$f" ] && cp -f "$f" "$DST/"; done
chmod 644 "$DST"/* 2>/dev/null || true
echo "cammock library downloaded from system_server"
