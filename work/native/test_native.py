import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from compile_helper import ROOT,JAVA

class NativeProtocolTests(unittest.TestCase):
    def scenario(self,name):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            r=subprocess.run([str(JAVA),'-cp',str(ROOT/'helper-classes')+os.pathsep+str(ROOT/'host-test-classes'),'elish.volume.NativeBridgeHostTest',name,folder],capture_output=True,text=True,timeout=10)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr)
    def test_peer_uid_and_systemui_process_both_required(self):self.scenario('credentials')
    def test_systemui_identity_is_resolved_and_missing_package_fails_closed(self):self.scenario('resolve-identity')
    def test_protocol_limits_and_truncated_record(self):self.scenario('bounded')
    def test_allowlist_rejects_shell_invalid_percent_user_and_package(self):self.scenario('protocol')
    def test_foreground_user_aaudio_inactive_rules_and_disable(self):self.scenario('ui-users')
    def test_shared_uid_appears_once_and_is_labelled(self):self.scenario('shared-uid')

if __name__=='__main__':unittest.main(verbosity=2)
