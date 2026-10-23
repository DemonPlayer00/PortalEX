#!/system/bin/sh
DIR=${0%/*}
SRC=/data/local/tmp/portalex/cammock/library
DST="$DIR/io/download"
mkdir -p "$DST"
if [ -f "$SRC/routes.json" ]; then
    cp -f "$SRC/routes.json" "$DST/routes.json"
    for f in "$SRC"/*.mp4; do [ -f "$f" ] && cp -f "$f" "$DST/"; done
else
    echo '[]' > "$DST/routes.json"
fi
chmod 644 "$DST"/* 2>/dev/null || true
echo "MI6 cammock library downloaded"
