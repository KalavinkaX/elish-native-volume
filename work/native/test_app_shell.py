import os
from pathlib import Path
import tempfile
import time
import unittest
from test_module import MODULE, ROOT, path, shell, write

class AppShellTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT)
        self.folder=Path(self.temp.name)
        self.module=self.folder/"module"
        self.bin=self.folder/"bin"
        self.calls=self.folder/"calls"
        self.marker=self.folder/"injected"
        self.env=os.environ.copy()
        self.env["MOCK_CALLS"]=path(self.calls)
        write(self.bin/"id",'#!/bin/sh\necho 0\n')
        runner=self.bin/"runner"
        write(runner,'#!/bin/sh\nprintf "ARG:%s\\n" "$@" >> "$MOCK_CALLS"\n')
        script=(MODULE/"app-volume.sh").read_text(encoding="utf-8")
        script=script.replace("/system/bin/app_process",path(runner))
        script=script.replace("DATA=/data/adb/elish_volume_steps","DATA='"+path(self.folder/"data")+"'")
        write(self.module/"app-volume.sh",script)
        write(self.module/"volume.sh",(MODULE/"volume.sh").read_text(encoding="utf-8"))
        write(self.module/"app-volume.jar","")
    def tearDown(self):
        self.temp.cleanup()
    def invoke(self,args,source="app-volume.sh"):
        return shell("export PATH='%s':/usr/bin:/bin; sh '%s' %s"%(path(self.bin),path(self.module/source),args),self.env)
    def test_set_forwards_package_percent_user_without_global_volume(self):
        result=self.invoke("set com.test.player 30 10")
        self.assertEqual(result.returncode,0,result.stderr)
        deadline=time.monotonic()+2
        while "ARG:run" not in self.calls.read_text() and time.monotonic()<deadline:
            time.sleep(0.02)
        calls=self.calls.read_text()
        self.assertIn("ARG:set\nARG:com.test.player\nARG:30\nARG:10\n",calls)
        self.assertIn("ARG:run",calls)
        self.assertNotIn("media_session",calls)
    def test_disable_does_not_start_another_process(self):
        result=self.invoke("disable")
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn("ARG:disable",self.calls.read_text())
        self.assertNotIn("ARG:run",self.calls.read_text())
    def test_existing_controller_dispatches_to_per_app_helper(self):
        result=self.invoke("app list",source="volume.sh")
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn("ARG:list",self.calls.read_text())
    def test_menu_never_evaluates_shell_input(self):
        input_file=self.folder/"input"
        write(input_file,"list $(touch "+path(self.marker)+")\nq\n")
        result=self.invoke("menu < '"+path(input_file)+"'")
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertFalse(self.marker.exists())

if __name__=="__main__":unittest.main(verbosity=2)
