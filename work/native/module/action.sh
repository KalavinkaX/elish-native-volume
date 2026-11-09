#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/volume.sh" status
RESULT=$?
sh "$MODDIR/volume.sh" diagnose
echo "=== Per-app attenuation ==="
sh "$MODDIR/app-volume.sh" status
sh "$MODDIR/app-volume.sh" doctor
APPRESULT=$?
echo "=== Native IPC log (latest 35 lines) ==="
tail -n 35 "$MODDIR/state/app-volume-events.log"
echo "Runtime status exit code: $RESULT"
echo "Per-app API probe exit code: $APPRESULT"
echo "Read README for exact/global and per-app controls. Action does not change volume."
[ "$RESULT" -ne 0 ] || RESULT=$APPRESULT
exit "$RESULT"
