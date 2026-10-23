#!/system/bin/sh
DIR=${0%/*}
ID=$(cat "$DIR/io/export/request.txt" 2>/dev/null)
[ -n "$ID" ] || { echo "missing export request"; exit 1; }
SRC="/data/local/tmp/portalex/cammock/library/$ID.mp4"
[ -f "$SRC" ] || { echo "cammock video not found: $ID"; exit 1; }
cp -f "$SRC" "$DIR/io/export/$ID.mp4" || exit 1
chmod 644 "$DIR/io/export/$ID.mp4"
echo "exported $ID"
