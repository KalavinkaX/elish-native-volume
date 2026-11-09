import os
from pathlib import Path
import subprocess
import time
import tempfile
import unittest
from compile_helper import ROOT, JAVA, JAVAC, run

class PerAppTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes=ROOT/"host-test-classes"
        cls.classes.mkdir(exist_ok=True)
        sources=list((ROOT/"host-tests").rglob("*.java"))
        sources.append(ROOT/"panel/java/elish/volume/panel/SliderUpdates.java")
        run([JAVAC,"--release","8","-Xlint:-options","-encoding","UTF-8","-cp",ROOT/"helper-classes","-d",cls.classes,*sources])
    def scenario(self,name):
        with tempfile.TemporaryDirectory(dir=ROOT) as temp:
            result=subprocess.run([str(JAVA),"-cp",str(ROOT/"helper-classes")+os.pathsep+str(self.classes),"elish.volume.AppVolumeHostTest",name,temp],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=15)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
    def test_only_configured_uid_is_attenuated(self): self.scenario("isolation")
    def test_multiple_apps_and_players(self): self.scenario("multiple")
    def test_same_curve_not_stacked_and_changes_replace_it(self): self.scenario("deduplicate")
    def test_remove_rule_restores_paused_player(self): self.scenario("restore")
    def test_alarms_system_and_unsupported_players_excluded(self): self.scenario("excluded")
    def test_hundred_percent_removes_extra_attenuation(self): self.scenario("hundred")
    def test_reused_player_id_does_not_inherit_rule(self): self.scenario("reused-player")
    def test_shared_uid_conflicts_rejected(self): self.scenario("shared-uid")
    def test_package_and_numeric_validation(self): self.scenario("validation")
    def test_atomic_storage_and_multi_user_rules(self): self.scenario("storage")
    def test_reflection_adapter_and_all_gain_values(self): self.scenario("adapter")
    def test_command_persistence_and_foreground_user(self): self.scenario("commands")
    def test_unknown_package_does_not_persist_rule(self): self.scenario("missing-package")
    def test_panel_protocol_active_aaudio_saved_rules_and_backend_error(self): self.scenario("ui-contract")
    def test_stream_persists_latest_value_and_recovers_from_bad_record(self):self.scenario("stream-updates")
    def test_daemon_rule_change_is_fast_and_reuses_effect(self):self.scenario("daemon-fast-update")
    def test_closing_panel_and_writer_keeps_daemon_effect_and_rules(self):self.scenario("daemon-panel-independent")
    def test_panel_close_cancels_pending_and_late_touch(self):self.panel_scenario("close-pending")
    def test_panel_untouched_close_never_writes_zero(self):self.panel_scenario("untouched-close")
    def test_panel_drag_coalesces_and_keeps_final_value(self):self.panel_scenario("latest-final")
    def panel_scenario(self,name):
        result=subprocess.run([str(JAVA),"-cp",str(self.classes),"elish.volume.panel.SliderUpdatesHostTest",name],capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
    def test_session_aaudio_spotify_fixture_is_included(self):self.scenario("session-aaudio")
    def test_session_multiple_apps_and_sessions(self):self.scenario("session-multiple")
    def test_session_gain_change_updates_existing_effect(self):self.scenario("session-update")
    def test_session_hundred_sets_unity_and_rule_removal_releases(self):self.scenario("session-restore")
    def test_session_repeated_unity_transition_reuses_chain_and_preserves_other_app(self):self.scenario("session-unity-reuse")
    def test_session_zero_never_attaches_global_effect(self):self.scenario("session-zero-rejected")
    def test_session_shared_across_uids_rejected(self):self.scenario("session-shared-rejected")
    def test_session_alarm_sharing_rejected(self):self.scenario("session-alarm-excluded")
    def test_session_creation_error_is_visible(self):self.scenario("session-create-error")
    def test_session_readback_error_releases_effect(self):self.scenario("session-readback-error")
    def test_session_disappearance_releases_effect(self):self.scenario("session-disappears")
    def test_session_amplitude_to_db_mapping(self):self.scenario("session-gain-mapping")
    def test_session_dynamics_reflection_and_readback(self):self.scenario("session-dynamics-adapter")
    def test_session_snapshot_gap_keeps_gain_and_handle(self):self.scenario("session-snapshot-gap")
    def test_session_valid_and_zero_does_not_report_false_error(self):self.scenario("session-mixed-valid-zero")
    def test_session_transient_error_preserves_attenuation(self):self.scenario("session-retain-on-error")
    def test_session_explicit_restore_bypasses_grace(self):self.scenario("session-remove-during-gap")
    def test_session_shared_owner_bypasses_grace(self):self.scenario("session-conflict-during-grace")
    def test_session_initial_gain_is_set_before_enable(self):self.scenario("session-preloaded-gain")
    def test_session_recovers_owned_disabled_effect_at_target_gain(self):self.scenario("session-reenable-owned")
    def test_session_recovery_does_not_steal_control(self):self.scenario("session-reenable-no-control")
    def test_daemon_heartbeat_singleton_and_module_disable(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as temp:
            module=Path(temp)/"module"
            data=Path(temp)/"data"
            module.mkdir()
            command=[str(JAVA),"-cp",str(ROOT/"helper-classes")+os.pathsep+str(self.classes),"elish.volume.AppVolume",str(module),str(data),"run"]
            daemon=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                status=module/"state/app-volume-status.txt"
                deadline=time.monotonic()+8
                while not status.exists() and time.monotonic()<deadline and daemon.poll() is None:
                    time.sleep(0.05)
                self.assertTrue(status.exists(),"no heartbeat")
                duplicate=subprocess.run(command,capture_output=True,text=True,timeout=5)
                self.assertEqual(duplicate.returncode,0,duplicate.stderr)
                self.assertIn("already running",duplicate.stdout)
                (module/"disable").write_text("")
                stdout,stderr=daemon.communicate(timeout=5)
                self.assertEqual(daemon.returncode,0,stderr)
                self.assertIn("stopped=true",status.read_text())
            finally:
                if daemon.poll() is None:
                    daemon.terminate()
                    daemon.communicate(timeout=5)

if __name__=="__main__": unittest.main(verbosity=2)
