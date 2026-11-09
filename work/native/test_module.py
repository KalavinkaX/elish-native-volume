"""Host-side behavioral checks. These do NOT emulate Android audio hardware."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
MODULE = ROOT / "module"
BASH = os.environ.get("ELISH_TEST_BASH", "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else "/bin/bash")
STOCK = [(1,-6300),(13,-4650),(20,-3800),(27,-2950),(33,-2500),(40,-2200),(47,-1900),(53,-1600),(60,-1300),(100,0)]
OLD = [(1,-8500),(13,-6000),*STOCK[2:]]
GENTLE = [(1,-8500),(5,-7000),(10,-5800),(20,-4000),(60,-1700),(100,0)]

def path(p):
    value = str(p).replace("\\", "/")
    if len(value) > 2 and value[1] == ":":
        value = "/" + value[0].lower() + value[2:]
    return value

def write(p, text):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")

def shell(command, env=None):
    return subprocess.run([BASH, "--noprofile", "--norc", "-c", command], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, timeout=40)

def curve(points=STOCK, category="SPEAKER", spaced=False):
    return ('  <volume deviceCategory="DEVICE_CATEGORY_%s">\n' % category
            + "".join("    <point>%s</point>\n" % ((" %s , %s " if spaced else "%s,%s") % p) for p in points)
            + "  </volume>\n")

class PatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT)
        self.folder = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def patch(self, text):
        src = self.folder / "source.xml"
        write(src, text)
        return shell("awk -f '%s' '%s'" % (path(MODULE / "scripts/patch-curve.awk"), path(src)))

    def test_known_curves_and_idempotence(self):
        for points in [STOCK, OLD, GENTLE]:
            with self.subTest(points=points):
                result = self.patch("<volumes>\n" + curve(points) + "</volumes>\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                root = ET.fromstring(result.stdout)
                got = [tuple(map(int, x.text.split(","))) for x in root.findall("volume/point")]
                self.assertEqual(got, GENTLE)
                again = self.patch(result.stdout)
                self.assertEqual(result.stdout, again.stdout)

    def test_non_targets_preserved(self):
        other = curve(STOCK, "HEADSET") + curve([(0,-4200),(100,0)]) + '  <volume ref="DEFAULT_VOLUME_CURVE" deviceCategory="DEVICE_CATEGORY_EARPIECE"/>\n'
        text = "<volumes>\n" + other + curve() + "</volumes>\n"
        result = self.patch(text)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(other, result.stdout)
        self.assertEqual(len(ET.fromstring(result.stdout).findall("volume")), 4)

    def test_whitespace(self):
        result = self.patch("<volumes>\n" + curve(spaced=True) + "</volumes>\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        ET.fromstring(result.stdout)

    def test_unknown_extra_or_malformed_fail(self):
        cases = [curve([(0,-4200),(100,0)]), curve(STOCK + [(101,0)]),
                 curve().replace("1,-6300", "1,-6301"), curve().replace("</volume>", ""),
                 curve().replace("<point>1,-6300</point>", "<point>nonsense</point>"),
                 "<volumes/>\n"]
        for text in cases:
            with self.subTest(text=text[:100]):
                self.assertNotEqual(self.patch(text).returncode, 0)

class ControlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT)
        self.folder = Path(self.temp.name)
        self.bin = self.folder / "bin"
        self.bin.mkdir()
        self.state = self.folder / "current"
        self.calls = self.folder / "calls"
        write(self.state, "7\n")
        write(self.bin / "id", '#!/bin/sh\necho 0\n')
        write(self.bin / "getprop", '#!/bin/sh\ncase "$1" in ro.product.device) echo elish;; ro.config.media_vol_steps) echo 100;; esac\n')
        write(self.bin / "cmd", '''#!/bin/sh
echo "$*" >> "$MOCK_CALLS"
if [ "$MOCK_MODE" = broken ]; then echo 'incompatible output'; exit 0; fi
if [ "$MOCK_MODE" = disconnected ]; then exit 1; fi
SET=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = --set ]; then shift; SET=$1; fi
  shift
done
if [ -n "$SET" ] && [ "$MOCK_MODE" != nochange ]; then
  echo "$SET" > "$MOCK_STATE"
fi
echo "[V] volume is $(cat "$MOCK_STATE") in range [0..${MOCK_MAX:-100}]"
''')
        self.env = os.environ.copy()
        self.env.update(MOCK_STATE=path(self.state), MOCK_CALLS=path(self.calls), MOCK_MAX="100", MOCK_MODE="")

    def tearDown(self):
        self.temp.cleanup()

    def run_control(self, args):
        return shell("export PATH='%s':/usr/bin:/bin; sh '%s' %s" % (path(self.bin), path(MODULE / "volume.sh"), args), self.env)

    def test_exact_indices_and_mute(self):
        for index in [0,1,2,5,10,15,25,50,99,100]:
            result = self.run_control("set %s" % index)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(int(self.state.read_text()), index)

    def test_reject_invalid_values_without_setting(self):
        for value in ["-1", "101", "1000", "01", "+1", "0.5", "abc", "999999999999999999999999", "'1 2'", "'$(id)'", "''"]:
            with self.subTest(value=value):
                result = self.run_control("set " + value)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(int(self.state.read_text()), 7)
        self.assertFalse(self.calls.exists())

    def test_refuse_old_range(self):
        self.env["MOCK_MAX"] = "15"
        result = self.run_control("set 10")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("--set", self.calls.read_text())

    def test_report_errors_even_when_command_exits_zero(self):
        self.env["MOCK_MODE"] = "nochange"
        result = self.run_control("set 15")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("read back 7", result.stderr)

    def test_unparseable_or_disconnected_api(self):
        for mode in ["broken", "disconnected"]:
            self.env["MOCK_MODE"] = mode
            result = self.run_control("set 15")
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("--set", self.calls.read_text())

    def test_up_down_and_boundaries(self):
        for current, command, expected in [(0,"down",0),(0,"up",1),(7,"down",6),(99,"up",100),(100,"up",100)]:
            write(self.state, str(current))
            result = self.run_control(command)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(int(self.state.read_text()), expected)

    def test_status_does_not_change_volume(self):
        result = self.run_control("status")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("--set", self.calls.read_text())

class ScriptTests(unittest.TestCase):
    def test_shell_syntax(self):
        for source in MODULE.glob("*.sh"):
            result = shell("sh -n '%s'" % path(source))
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_early_boot_has_no_blocking_api_or_sleep(self):
        script = (MODULE / "post-fs-data.sh").read_text()
        for line in script.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            self.assertNotIn("sleep ", line)
            self.assertNotIn("cmd media_session", line)
            self.assertFalse(line.startswith("setprop "))

class BootTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT)
        self.folder = Path(self.temp.name)
        self.module = self.folder / "module"
        shutil.copytree(MODULE, self.module)
        self.source = self.folder / "vendor.xml"
        write(self.source, "<volumes>\n" + curve() + "</volumes>\n")
        self.overlay = self.module / "system/vendor/etc/audio_policy_engine_stream_volumes_mi.xml"
        self.bin = self.folder / "bin"
        write(self.bin / "getprop", '#!/bin/sh\ncase "$1" in ro.product.device) echo elish;; ro.config.media_vol_steps) echo 100;; esac\n')
        self.prop_calls = self.folder / "props"
        write(self.bin / "resetprop", '#!/bin/sh\necho "$*" >> "$PROP_CALLS"\n')
        write(self.bin / "chcon", '#!/bin/sh\nexit 0\n')
        self.env = os.environ.copy()
        self.env["PROP_CALLS"] = path(self.prop_calls)
        # Only replace the absolute vendor path in the temporary test copy.
        for name in ["customize.sh", "post-fs-data.sh"]:
            target = self.module / name
            write(target, target.read_text().replace("SRC=/vendor/etc/audio_policy_engine_stream_volumes_mi.xml", "SRC='%s'" % path(self.source)))

    def tearDown(self):
        self.temp.cleanup()

    def boot(self):
        return shell("export PATH='%s':/usr/bin:/bin; sh '%s'" % (path(self.bin), path(self.module / "post-fs-data.sh")), self.env)

    def install(self):
        harness = self.folder / "install-harness.sh"
        write(harness, '''#!/bin/sh
export PATH='%s':/usr/bin:/bin
export MODPATH='%s' BOOTMODE=true MAGISK_VER_CODE=30000
ui_print() { echo "$*"; }
abort() { echo "$*" >&2; exit 1; }
set_perm_recursive() { :; }
set_perm() { :; }
. '%s'
''' % (path(self.bin), path(self.module), path(self.module / "customize.sh")))
        return shell("sh '%s'" % path(harness), self.env)

    def test_install_valid_and_refuse_unknown(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        ET.parse(self.overlay)
        self.assertEqual((self.module / "state/install-source.xml").read_text(), self.source.read_text())
        write(self.source, "<volumes>\n" + curve([(0,-4200),(100,0)]) + "</volumes>\n")
        self.assertNotEqual(self.install().returncode, 0)

    def test_boot_generates_current_overlay_and_properties(self):
        result = self.boot()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("<point>5,-7000</point>", self.overlay.read_text())
        self.assertEqual((self.module / "state/curve-status").read_text(), "ready\n")
        self.assertEqual(self.prop_calls.read_text().splitlines(), ["-n ro.config.media_vol_steps 100", "-n ro.config.media_vol_default 10"])
        ET.parse(self.overlay)

    def test_unknown_ota_removes_stale_overlay(self):
        self.boot()
        write(self.source, "<volumes>\n" + curve([(0,-4200),(100,0)]) + "</volumes>\n")
        result = self.boot()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.overlay.exists())
        self.assertIn("curve disabled", (self.module / "state/curve-status").read_text())
        self.assertIn("ro.config.media_vol_steps 100", self.prop_calls.read_text())

    def test_missing_source_removes_stale_overlay(self):
        self.boot()
        self.source.unlink()
        result = self.boot()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.overlay.exists())

if __name__ == "__main__":
    unittest.main(verbosity=2)
