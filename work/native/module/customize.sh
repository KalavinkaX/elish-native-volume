#!/system/bin/sh
ui_print "- Elish Native App Volume v3.1.0-key-test1"
[ "$BOOTMODE" = true ] || abort "! Install from the Magisk app in Android."
[ "$MAGISK_VER_CODE" -ge 20400 ] || abort "! Magisk 20.4+ required."
DEVICE="$(getprop ro.product.device)"
[ "$DEVICE" = elish ] || abort "! Unsupported device: $DEVICE; expected elish."
SRC=/vendor/etc/audio_policy_engine_stream_volumes_mi.xml
DST="$MODPATH/system/vendor/etc/audio_policy_engine_stream_volumes_mi.xml"
[ -r "$SRC" ] || abort "! Required vendor XML missing: $SRC"
mkdir -p "$MODPATH/state" "$MODPATH/system/vendor/etc" || abort "! mkdir failed"
cp -f "$SRC" "$MODPATH/state/install-source.xml" || abort "! Backup failed"
awk -f "$MODPATH/scripts/patch-curve.awk" "$SRC" > "$DST.tmp" 2> "$MODPATH/state/install.log" || {
  cat "$MODPATH/state/install.log"
  abort "! Unknown/incompatible XML. Installation stopped; please provide the ROM XML."
}
mv -f "$DST.tmp" "$DST" || abort "! XML installation failed"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for file in customize.sh post-fs-data.sh service.sh action.sh volume.sh app-volume.sh; do
  set_perm "$MODPATH/$file" 0 0 0755
done
ui_print "- Known speaker curve installed; original saved in state/."
ui_print "- Early boot media maximum: 100; fallback default: 10."
ui_print "- Saved Android volumes are NOT rescaled."
ui_print "- Per-app helper included; no apps attenuated until configured."
ui_print "- App rules persist in /data/adb/elish_volume_steps/."
ui_print "- Install matching Elish Native LSPosed APK separately."
ui_print "- LSPosed scope: System UI (com.android.systemui) + Android/System Framework (android)."
ui_print "- Disable the OLD Elish Volume Key accessibility service."
ui_print "- Native UI does not request root; IPC uses authenticated peer credentials."
ui_print "- Key acceleration device test: reboot and check Magisk Action / LSPosed logs."
