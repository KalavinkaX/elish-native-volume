#!/system/bin/sh
MODDIR=${0%/*}
SRC=/vendor/etc/audio_policy_engine_stream_volumes_mi.xml
DST="$MODDIR/system/vendor/etc/audio_policy_engine_stream_volumes_mi.xml"
mkdir -p "$MODDIR/state"
# One log per boot; no sleep or framework/Binder calls in this blocking stage.
{
  echo "Elish Volume 3.1.0-key-test1 early boot"
  date
  if [ "$(getprop ro.product.device)" != elish ]; then
    echo "ERROR: unsupported device; disable this module."
    rm -f "$DST"
    exit 0
  fi
  resetprop -n ro.config.media_vol_steps 100
  resetprop -n ro.config.media_vol_default 10
  echo "media_vol_steps=$(getprop ro.config.media_vol_steps)"
  # Magisk has not mounted module files yet: repatch the current ROM after OTAs.
  if [ -r "$SRC" ] && awk -f "$MODDIR/scripts/patch-curve.awk" "$SRC" > "$MODDIR/state/curve.tmp"; then
    mkdir -p "${DST%/*}"
    if cp -f "$SRC" "$MODDIR/state/boot-source.xml" && mv -f "$MODDIR/state/curve.tmp" "$DST"; then
      chmod 0644 "$DST"
      # Newly generated files need the same readable context as installer files.
      chcon u:object_r:system_file:s0 "$DST" || echo "WARNING: failed to label generated overlay; verify SELinux access on device"
      echo "ready" > "$MODDIR/state/curve-status"
      sha256sum "$SRC" "$DST"
    else
      rm -f "$DST" "$MODDIR/state/curve.tmp"
      echo "ERROR: overlay write failed; curve disabled" > "$MODDIR/state/curve-status"
    fi
  else
    # Never keep a stale vendor overlay after an unrecognized ROM update.
    rm -f "$DST" "$MODDIR/state/curve.tmp"
    echo "ERROR: current ROM XML unknown/missing; curve disabled, 100-step property retained" > "$MODDIR/state/curve-status"
  fi
  cat "$MODDIR/state/curve-status"
} > "$MODDIR/state/boot.log" 2>&1
