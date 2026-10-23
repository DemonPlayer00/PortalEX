#!/system/bin/sh
# cammock 执行面：把 app 提供的 io/upload 快照送到 system_server 库。
DIR=${0%/*}
SRC="$DIR/io/upload"
DST=/data/system/portalex/camera
[ -f "$SRC/routes.json" ] || { echo "io/upload/routes.json missing"; exit 1; }
mkdir -p "$DST/videos"
cp -f "$SRC/routes.json" "$DST/library.json"
for f in "$SRC"/*.mp4; do [ -f "$f" ] && cp -f "$f" "$DST/videos/"; done
chown -R system:system "$DST"
chmod 600 "$DST/library.json"
echo "cammock library uploaded to system_server"
