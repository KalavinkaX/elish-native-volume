#!/system/bin/sh
# The Android shell usually has no awk. Use Magisk BusyBox for stable utilities.
if [ -x /data/adb/magisk/busybox ] && [ "$ELISH_BUSYBOX" != 1 ]; then
  export ELISH_BUSYBOX=1 ASH_STANDALONE=1
  exec /data/adb/magisk/busybox sh "$0" "$@"
fi
MODDIR=${0%/*}
fail() { echo "ERROR: $*" >&2; return 1; }
query_volume() {
  RESPONSE=$(timeout 10 cmd media_session volume --stream 3 --get 2>&1) || {
    fail "Cannot query AudioService: $RESPONSE"; return 1;
  }
  PARSED=$(printf '%s\n' "$RESPONSE" | sed -n 's/.*volume is \([0-9][0-9]*\) in range \[\([0-9][0-9]*\)\.\.\([0-9][0-9]*\)\].*/\1 \2 \3/p')
  set -- $PARSED
  [ "$#" -eq 3 ] || { fail "Unrecognized media command output: $RESPONSE"; return 1; }
  CURRENT=$1 MINIMUM=$2 MAXIMUM=$3
}
require_range() {
  [ "$MINIMUM" = 0 ] && [ "$MAXIMUM" = 100 ] || {
    fail "Actual range [$MINIMUM..$MAXIMUM], expected [0..100]. Reboot and run diagnose. No value was set."
    return 1
  }
}
set_index() {
  # Canonical decimal only: no signs, spaces, octal, expressions or overflow.
  case "$1" in
    [0-9]|[1-9][0-9]|100) TARGET=$1 ;;
    *) fail "Use one integer 0..100 (examples: set 1, set 25)."; return 1 ;;
  esac
  query_volume && require_range || return 1
  RESULT=$(timeout 10 cmd media_session volume --stream 3 --set "$TARGET" --show --get 2>&1) || {
    fail "Volume command failed: $RESULT"; return 1;
  }
  # VolumeCtrl may print an error but still exit 0. Check actual state separately.
  query_volume || return 1
  if [ "$CURRENT" != "$TARGET" ]; then
    fail "Requested $TARGET, read back $CURRENT. Route, safe-volume policy, mute state or another app may intervene. $RESULT"
    return 1
  fi
  echo "Media volume: $CURRENT / $MAXIMUM (API readback; not a sound-pressure measurement)"
}
status() {
  echo "Elish Volume 3.1.0-key-test1"
  echo "Device: $(getprop ro.product.device)"
  echo "Requested property: $(getprop ro.config.media_vol_steps)"
  query_volume || return 1
  echo "Actual STREAM_MUSIC: $CURRENT in [$MINIMUM..$MAXIMUM]"
  cat "$MODDIR/state/curve-status" 2>/dev/null
  XML=system/vendor/etc/audio_policy_engine_stream_volumes_mi.xml
  if [ -f "$MODDIR/$XML" ] && cmp -s "$MODDIR/$XML" /vendor/etc/audio_policy_engine_stream_volumes_mi.xml; then
    echo "Curve overlay: visible file matches module"
  else
    echo "Curve overlay: missing or different; inspect diagnostics"
  fi
  require_range
}
diagnose() {
  mkdir -p "$MODDIR/state" || return 1
  {
    date
    echo "=== Properties ==="
    for key in ro.product.device ro.build.version.release ro.build.version.sdk ro.lineage.version ro.build.fingerprint ro.config.media_vol_steps ro.config.media_vol_default; do
      echo "$key=$(getprop "$key")"
    done
    echo "=== Runtime check ==="
    status
    echo "=== Early boot ==="
    cat "$MODDIR/state/boot.log" 2>/dev/null
    echo "=== Audio service ==="
    timeout 15 dumpsys audio
    echo "=== Vendor XML checksum ==="
    sha256sum /vendor/etc/audio_policy_engine_stream_volumes_mi.xml
  } > "$MODDIR/state/diagnostics.txt" 2>&1
  echo "Saved: $MODDIR/state/diagnostics.txt"
}
usage() {
  echo "Usage: su -c 'sh /data/adb/modules/elish_volume_steps/volume.sh COMMAND'"
  echo "Commands: status | get | set 0..100 | up | down | menu | diagnose | app ..."
  echo "Per-app example: volume.sh app set com.example.player 30"
  echo "Media only. 0..100 are discrete indices, not linear loudness percentages."
}
[ "$(id -u)" = 0 ] || { fail "Run using su -c (root required)."; exit 1; }
COMMAND=${1:-status}
case "$COMMAND" in
  app) shift; sh "$MODDIR/app-volume.sh" "$@" ;;
  status) [ "$#" -le 1 ] || { usage; exit 2; }; status ;;
  get) query_volume && echo "$CURRENT [$MINIMUM..$MAXIMUM]" ;;
  set) [ "$#" -eq 2 ] || { usage; exit 2; }; set_index "$2" ;;
  up|down)
    query_volume && require_range || exit 1
    TARGET=$CURRENT
    if [ "$COMMAND" = up ] && [ "$TARGET" -lt 100 ]; then TARGET=$((TARGET + 1)); fi
    if [ "$COMMAND" = down ] && [ "$TARGET" -gt 0 ]; then TARGET=$((TARGET - 1)); fi
    set_index "$TARGET"
    ;;
  menu)
    echo "Enter 0..100, +, -, s (status), or q (quit). Nothing changes until you choose."
    while printf '> ' && IFS= read -r INPUT; do
      case "$INPUT" in
        q) break ;;
        s) status ;;
        +) sh "$0" up ;;
        -) sh "$0" down ;;
        *) set_index "$INPUT" ;;
      esac
    done
    ;;
  diagnose) diagnose ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
