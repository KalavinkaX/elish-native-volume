#!/system/bin/sh
MODDIR=${0%/*}
# Bounded wait in the non-blocking stage. Never set volume automatically.
COUNT=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$COUNT" -lt 90 ]; do
  sleep 2
  COUNT=$((COUNT + 1))
done
if [ "$(getprop sys.boot_completed)" != 1 ]; then
  echo "ERROR: boot completion timeout; tap Action after boot." > "$MODDIR/state/runtime.log"
  exit 0
fi
sh "$MODDIR/volume.sh" status > "$MODDIR/state/runtime.log" 2>&1
# This is a diagnostic only: changing a property after AudioService starts
# cannot change an already initialized range. Do not restart audio/framework.
sh "$MODDIR/app-volume.sh" start >> "$MODDIR/state/runtime.log" 2>&1
