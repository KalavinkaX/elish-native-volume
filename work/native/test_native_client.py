import os
import subprocess
import unittest
from compile_helper import ROOT,JAVA,JAVAC,run

class NativeClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes=ROOT/'client-test-classes';cls.classes.mkdir(exist_ok=True)
        run([JAVAC,'--release','8','-Xlint:-options','-encoding','UTF-8','-cp',ROOT/'hook-build/classes','-d',cls.classes,*sorted((ROOT/'client-tests').rglob('*.java'))])
    def scenario(self,name):
        r=subprocess.run([str(JAVA),'-cp',str(self.classes)+os.pathsep+str(ROOT/'hook-build/classes'),'elish.volume.nativepanel.NativeClientHostTest',name],capture_output=True,text=True,timeout=10)
        self.assertEqual(r.returncode,0,r.stdout+r.stderr)
    def test_untouched_native_panel_close_never_writes_zero(self):self.scenario('untouched')
    def test_android_socket_connect_precedes_timeout_and_handshake(self):self.scenario('socket-lifecycle')
    def test_latest_drag_value_survives_immediate_panel_close(self):self.scenario('coalesce-close')
    def test_late_write_ack_does_not_remove_newer_pending_edit(self):self.scenario('late-ack')
    def test_rejected_write_surfaces_error_without_retry_or_reset(self):self.scenario('rejected')
    def key_scenario(self,name):
        r=subprocess.run([str(JAVA),'-cp',str(self.classes)+os.pathsep+str(ROOT/'hook-build/classes'),'elish.volume.nativepanel.KeyAccelerationHostTest',name],capture_output=True,text=True,timeout=10)
        self.assertEqual(r.returncode,0,r.stdout+r.stderr)
    def test_key_acceleration_tiers_and_one_event_one_adjustment(self):self.key_scenario('tiers')
    def test_key_release_and_rapid_taps_remain_fine(self):self.key_scenario('release')
    def test_stale_wrong_direction_duplicate_and_out_of_order_keys(self):self.key_scenario('stale')
    def test_only_speaker_media_normal_mode_above_ten_and_standard_step(self):self.key_scenario('guards')

if __name__=='__main__':unittest.main(verbosity=2)
