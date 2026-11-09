#!/system/bin/sh
if [ -x /data/adb/magisk/busybox ] && [ "$ELISH_BUSYBOX" != 1 ]; then
  export ELISH_BUSYBOX=1 ASH_STANDALONE=1
  exec /data/adb/magisk/busybox sh "$0" "$@"
fi
umask 077
MODDIR=${0%/*}
DATA=/data/adb/elish_volume_steps
[ "$(id -u)" = 0 ] || { echo "ERROR: root required" >&2; exit 1; }
[ -f "$MODDIR/app-volume.jar" ] || { echo "ERROR: DEX helper missing" >&2; exit 1; }
mkdir -p "$DATA" "$MODDIR/state" || exit 1
chmod 0700 "$DATA"
java_command() {
  CLASSPATH="$MODDIR/app-volume.jar" /system/bin/app_process /system/bin elish.volume.AppVolume "$MODDIR" "$DATA" "$@"
}
start_daemon() {
  [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || {
    echo "ERROR: module disabled or pending removal; reboot or re-enable first" >&2; return 1;
  }
  # The Java daemon owns an OS file lock; duplicate starts exit immediately.
  LOG="$MODDIR/state/app-volume.log"
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 262144 ]; then mv -f "$LOG" "$LOG.previous"; fi
  nohup env CLASSPATH="$MODDIR/app-volume.jar" /system/bin/app_process /system/bin elish.volume.AppVolume "$MODDIR" "$DATA" run >> "$LOG" 2>&1 < /dev/null &
  echo "Daemon start requested; use status/doctor to verify."
}
usage() {
  echo "Session-based per-app media attenuation (0..100%, 100 = unchanged; 0 = -120dB):"
  echo "  app-volume.sh set PACKAGE PERCENT [USER_ID]"
  echo "  app-volume.sh remove PACKAGE [USER_ID]"
  echo "  app-volume.sh apps | list | players | status | doctor"
  echo "  app-volume.sh start | enable | disable | reset | menu"
  echo "No system-wide stream volume changes. Default user is current foreground user."
}
COMMAND=${1:-status}
case "$COMMAND" in
  apps)
    USER_ID=$(timeout 10 am get-current-user) || exit 1
    case "$USER_ID" in ''|*[!0-9]*) echo "ERROR: cannot identify current Android user" >&2; exit 1 ;; esac
    timeout 15 cmd package list packages -3 -U --user "$USER_ID"
    ;;
  start) start_daemon ;;
  set|remove|reset|enable)
    java_command "$@" || exit $?
    start_daemon
    ;;
  disable) java_command disable ;;
  panel) java_command "$@" ;;
  list|players|status|doctor|ui|stream|report) java_command "$COMMAND" ;;
  menu)
    echo "Commands: set PACKAGE PERCENT [USER], remove PACKAGE [USER], apps, list, players, status, enable, disable, reset, q"
    while printf 'app-volume> ' && IFS= read -r INPUT; do
      # Split into words only; never eval input and disable pathname expansion.
      set -f
      set -- $INPUT
      case "${1:-}" in
        q) break ;;
        set|remove|apps|list|players|status|enable|disable|reset) sh "$0" "$@" ;;
        *) usage ;;
      esac
    done
    ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
