# Third-party material

`META-INF/com/google/android/update-binary` comes from the official Magisk
scripts/module_installer.sh, normalized to LF:
https://github.com/topjohnwu/Magisk/blob/master/scripts/module_installer.sh
Its GPLv3 license is included as MAGISK-LICENSE. Original project source is
published under GPL-3.0-or-later; see the repository LICENSE.

Xposed API82, Android SDK and D8/R8 are build-time dependencies, not bundled
framework implementations. Android/SystemUI classes are supplied by the ROM.
The repository does not redistribute firmware, extracted framework binaries,
the Android SDK, signing secrets or user logs. Host test doubles are not shipped
in the Android runtime DEX.
